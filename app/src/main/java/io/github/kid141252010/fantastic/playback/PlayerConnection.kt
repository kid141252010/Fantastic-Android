package io.github.kid141252010.fantastic.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.kid141252010.fantastic.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A queue row retains its Media3 index, even if another item cannot be rendered. */
data class QueueEntry(
    val index: Int,
    val track: Track,
    val occurrence: Int = index,
    val inQueueSection: Boolean = false,
)

data class PlayerState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val error: String? = null,
    /** 每次跳转（含切歌）加一，歌词据此直接跳到位置 */
    val seekGeneration: Int = 0,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val currentTrack: Track? = null,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val quality: String? = null,
    val playWhenReady: Boolean = false,
    val ended: Boolean = false,
    val bufferedPositionMs: Long = 0L,
    val qualityLabel: String? = null,
    val audioDetails: String? = null,
    val isLive: Boolean = false,
    val isSeekable: Boolean = false,
    val canSelectQueue: Boolean = false,
    val canEditQueue: Boolean = false,
    val canShuffle: Boolean = false,
    val canRepeat: Boolean = false,
    /** Upcoming items in the engine's actual traversal order, not catalogue order. */
    val upNext: List<QueueEntry> = emptyList(),
    /** Reject a gesture if the engine changed the queue while the finger was down. */
    val queueRevision: Long = 0L,
    internal val trackOccurrence: Int = -1,
    internal val repeatGeneration: Int = 0,
) {
    val canAddToQueueSection: Boolean get() = canEditQueue && upNext.any { it.inQueueSection }
}

internal enum class QueueInsertion(val mediaValue: String) {
    PlayNext("play_next"),
    AddToQueue("add_to_queue"),
}

internal enum class QueueActionFeedback {
    PlayNext,
    AddToQueue,
}

/** Output clock corrections are not user seeks; media/occurrence changes still advance in refresh. */
internal fun shouldIncrementLyricSeekGeneration(mediaId: String?, reason: Int): Boolean =
    mediaId?.startsWith("apple:") != true || reason != Player.DISCONTINUITY_REASON_INTERNAL

/** UI-side controller for the single service-owned Media3 session. */
class PlayerConnection(private val context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _queueFeedback = MutableSharedFlow<QueueActionFeedback>(extraBufferCapacity = 4)
    internal val queueFeedback: SharedFlow<QueueActionFeedback> = _queueFeedback.asSharedFlow()
    private var controller: MediaController? = null
    private var connectionFuture: ListenableFuture<MediaController>? = null
    private var pending: (() -> Unit)? = null
    private var seekGeneration = 0
    private var queueRevision = 0L
    private var queueSignature: List<Pair<Int, Boolean>> = emptyList()
    private var currentIndex = -1
    private var lastMediaId: String? = null
    private var lastOccurrence = -1
    private var discontinuityRefreshPending = false
    private var repeatGeneration = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeatGeneration++
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            refresh(discontinuity = shouldIncrementLyricSeekGeneration(new.mediaItem?.mediaId, reason))
        }
    }

    init {
        connectIfNeeded()
    }

    internal fun connectIfNeeded() {
        if (controller != null || connectionFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) { refresh() }
            })
            .buildAsync()
        connectionFuture = future
        future.addListener({
            if (connectionFuture !== future) return@addListener
            connectionFuture = null
            runCatching { future.get() }.onSuccess { value ->
                controller = value.also { it.addListener(listener) }
                refresh()
                pending?.invoke()
                pending = null
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message ?: "播放器连接失败")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    internal fun disconnectIfIdle() {
        val current = _state.value
        if (current.playWhenReady && !current.ended && current.error == null) return
        connectionFuture?.let { future ->
            connectionFuture = null
            if (!future.isDone) future.cancel(true)
            MediaController.releaseFuture(future)
        }
        controller?.let { value ->
            value.removeListener(listener)
            value.release()
        }
        controller = null
        pending = null
        queueSignature = emptyList()
        currentIndex = -1
        lastMediaId = null
        lastOccurrence = -1
        _state.value = PlayerState()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    private fun whenReady(block: (MediaController) -> Unit) {
        val value = controller
        if (value == null) pending = { controller?.let(block) } else block(value)
    }

    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun play(track: Track) = playQueue(listOf(track), 0)

    fun playQueue(tracks: List<Track>, index: Int, quality: String? = null, shuffle: Boolean? = null) {
        if (index !in tracks.indices) return
        whenReady { player ->
            player.setMediaItems(tracks.mapIndexed { i, track ->
                track.toQueueItem(quality.takeIf { i == index }, shuffle.takeIf { i == index })
            }, index, 0L)
            player.prepare()
            player.play()
        }
    }

    internal fun insertQueueItems(tracks: List<Track>, insertion: QueueInsertion) = whenReady { player ->
        submitQueueItems(player, tracks, insertion)
    }

    internal fun insertQueueItemsFromSwipe(tracks: List<Track>) = whenReady { player ->
        val insertion = if (_state.value.canAddToQueueSection) QueueInsertion.AddToQueue else QueueInsertion.PlayNext
        submitQueueItems(player, tracks, insertion)
    }

    private fun submitQueueItems(player: MediaController, tracks: List<Track>, requested: QueueInsertion) {
        if (tracks.isEmpty() || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val latest = _state.value
        val insertion = if (requested == QueueInsertion.AddToQueue && !latest.canAddToQueueSection)
            QueueInsertion.PlayNext else requested
        val currentIndex = player.currentMediaItemIndex.takeIf {
            player.currentMediaItem != null && it in 0 until player.mediaItemCount
        }
        val index = when (insertion) {
            QueueInsertion.PlayNext -> (currentIndex?.plus(1) ?: 0)
            QueueInsertion.AddToQueue -> latest.upNext.lastOrNull { it.inQueueSection }?.index?.plus(1)
                ?: (currentIndex?.plus(1) ?: 0)
        }
        player.addMediaItems(index, tracks.map { it.toQueueItem(queueInsertion = insertion) })
        _queueFeedback.tryEmit(if (insertion == QueueInsertion.PlayNext)
            QueueActionFeedback.PlayNext else QueueActionFeedback.AddToQueue)
    }

    fun clearUpcoming(expectedRevision: Long) = whenReady { player ->
        if (expectedRevision != _state.value.queueRevision || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return@whenReady
        val from = player.currentMediaItemIndex + 1
        if (from in 0 until player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
    }

    fun togglePlay() = whenReady {
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playerError == null) it.pause()
        else {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
            it.play()
        }
    }

    fun pause() = whenReady { it.pause() }
    fun next() = whenReady { it.seekToNext() }
    fun previous() = whenReady { it.seekToPrevious() }
    fun seekTo(ms: Long) = whenReady { it.seekTo(ms.coerceAtLeast(0L)) }
    fun seekLyric(ms: Long) = seekTo(ms)
    fun selectQueue(index: Int, expectedRevision: Long? = null) = whenReady { player ->
        if (expectedRevision != null && expectedRevision != _state.value.queueRevision) return@whenReady
        if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM) && index in 0 until player.mediaItemCount) {
            player.seekToDefaultPosition(index)
            if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) player.play()
        }
    }

    fun removeQueue(index: Int, expectedRevision: Long) = whenReady { player ->
        if (expectedRevision == _state.value.queueRevision &&
            player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) &&
            _state.value.upNext.any { it.index == index }) player.removeMediaItem(index)
    }

    fun moveQueue(fromIndex: Int, toIndex: Int, expectedRevision: Long) = whenReady { player ->
        if (expectedRevision == _state.value.queueRevision &&
            player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) &&
            _state.value.upNext.any { it.index == fromIndex } &&
            _state.value.upNext.any { it.index == toIndex }) player.moveMediaItem(fromIndex, toIndex)
    }

    fun toggleShuffle() = whenReady {
        if (it.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) it.shuffleModeEnabled = !it.shuffleModeEnabled
    }
    fun cycleRepeat() = whenReady { player ->
        if (!player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) return@whenReady
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun selectQuality(quality: String) = whenReady { player ->
        val item = player.currentMediaItem ?: return@whenReady
        player.sendCustomCommand(PlaybackService.SELECT_QUALITY, Bundle().apply {
            putString("mediaId", item.mediaId)
            putString("quality", quality)
        })
    }


    private fun refresh(discontinuity: Boolean = false) {
        val player = controller ?: return
        val item = player.currentMediaItem
        val mediaId = item?.mediaId
        val metadata = item?.mediaMetadata
        val entries = (0 until player.mediaItemCount).mapNotNull { index ->
            val queued = player.getMediaItemAt(index)
            queued.toTrack()?.let {
                QueueEntry(index, it, queued.mediaMetadata.extras?.getInt(KEY_OCCURRENCE, index) ?: index,
                    queued.mediaMetadata.extras?.getBoolean(KEY_QUEUE_SECTION, false) ?: false)
            }
        }
        val signature = entries.map { it.occurrence to it.inQueueSection }
        val selected = player.currentMediaItemIndex
        if (signature != queueSignature || selected != currentIndex) {
            queueSignature = signature
            currentIndex = selected
            queueRevision++
        }
        val occurrence = metadata?.extras?.getInt(KEY_OCCURRENCE, -1) ?: -1
        if (discontinuity || mediaId != lastMediaId || occurrence != lastOccurrence) seekGeneration++
        lastMediaId = mediaId
        lastOccurrence = occurrence
        val sessionExtras = player.sessionExtras
        val hasCurrentAudio = occurrence != -1 &&
            sessionExtras.getInt(PlaybackService.AUDIO_OCCURRENCE, -1) == occurrence &&
            sessionExtras.getString(PlaybackService.AUDIO_MEDIA_ID) == mediaId &&
            sessionExtras.getInt(PlaybackService.AUDIO_QUEUE_INDEX, -1) == selected
        _state.value = PlayerState(
            connected = true,
            mediaId = mediaId,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            artworkUri = metadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            ended = player.playbackState == Player.STATE_ENDED,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it > 0 } ?: item?.mediaMetadata?.extras?.getLong(KEY_DURATION_MS, 0L) ?: 0L,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = item != null && (player.isCurrentMediaItemSeekable || player.hasPreviousMediaItem()),
            error = item?.mediaMetadata?.extras?.getString(KEY_PLAYBACK_MESSAGE) ?: player.playerError?.localizedMessage,
            seekGeneration = seekGeneration,
            queue = entries.map { it.track },
            queueIndex = player.currentMediaItemIndex,
            currentTrack = item?.toTrack(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            quality = item?.mediaMetadata?.extras?.getString(KEY_QUALITY),
            qualityLabel = if (hasCurrentAudio) sessionExtras.getString(PlaybackService.AUDIO_LABEL) else null,
            audioDetails = if (hasCurrentAudio) sessionExtras.getString(PlaybackService.AUDIO_DETAILS) else null,
            isLive = item != null && player.isCurrentMediaItemLive,
            isSeekable = item != null && player.isCurrentMediaItemSeekable &&
                player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            canSelectQueue = player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
            canEditQueue = player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS),
            canShuffle = player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE),
            canRepeat = player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
            upNext = entries.filter { it.index > selected },
            queueRevision = queueRevision,
            trackOccurrence = occurrence,
            repeatGeneration = repeatGeneration,
        )
    }

}
