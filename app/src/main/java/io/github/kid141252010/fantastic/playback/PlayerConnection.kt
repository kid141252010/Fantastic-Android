package io.github.kid141252010.fantastic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
)

/** 界面这一侧的播放器：通过 MediaController 连到 PlaybackService。 */
class PlayerConnection(private val context: Context, private val prefs: Prefs) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            val item = c.currentMediaItem ?: return
            // 当前音质不可用时自动降一级重试。
            val next = nextQuality(item.mediaMetadata.extras?.getString(KEY_QUALITY))
            val mid = item.mediaMetadata.extras?.getString(KEY_ID)
            val source = item.mediaMetadata.extras?.getString(KEY_SOURCE)
            if (next != null && mid != null && source == Source.QQ.name) {
                val index = c.currentMediaItemIndex
                c.replaceMediaItem(index, qqItem(mid, item.mediaMetadata, next))
                c.prepare()
                c.play()
            } else {
                _state.value = _state.value.copy(error = error.errorCodeName)
            }
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
            refresh()
            pending?.invoke()
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    private fun whenReady(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending = { controller?.let(block) }
    }

    fun position(): Long = controller?.currentPosition ?: 0

    fun playQueue(tracks: List<Track>, index: Int) = whenReady { c ->
        val items = tracks.mapNotNull { toItem(it) }
        if (items.isEmpty()) return@whenReady
        val start = tracks.take(index).count { toItem(it) != null }
        c.setMediaItems(items, start.coerceIn(0, items.lastIndex), 0)
        c.prepare()
        c.play()
    }

    fun togglePlay() = whenReady { if (it.isPlaying) it.pause() else it.play() }
    fun next() = whenReady { it.seekToNext() }
    fun previous() = whenReady { it.seekToPrevious() }
    fun seekTo(ms: Long) = whenReady { it.seekTo(ms) }

    private fun toItem(t: Track): MediaItem? {
        // Apple 播放在第三阶段接入（需要解密核心），目前只播 QQ。
        if (t.source != Source.QQ) return null
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
            .setArtworkUri(t.artwork(600)?.let(Uri::parse))
            .build()
        return qqItem(t.id, meta, prefs.qqQuality)
    }

    private fun qqItem(mid: String, base: MediaMetadata, quality: String): MediaItem {
        val extras = Bundle().apply {
            putString(KEY_ID, mid)
            putString(KEY_SOURCE, Source.QQ.name)
            putString(KEY_QUALITY, quality)
        }
        return MediaItem.Builder()
            .setMediaId("qq:$mid")
            .setUri(prefs.baseUrl + "/qq/song/$mid/play?quality=$quality")
            .setMediaMetadata(base.buildUpon().setExtras(extras).build())
            .build()
    }

    private fun nextQuality(current: String?): String? {
        val order = Prefs.QQ_QUALITIES.map { it.first }
        val i = order.indexOf(current)
        return if (i < 0 || i == order.lastIndex) null else order[i + 1]
    }

    private fun refresh() {
        val c = controller ?: return
        val m = c.mediaMetadata
        _state.value = PlayerState(
            connected = true,
            mediaId = c.currentMediaItem?.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            artworkUri = m.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem() || c.currentPosition > 3000,
            error = c.playerError?.errorCodeName,
        )
    }

    private companion object {
        const val KEY_ID = "fantastic.id"
        const val KEY_SOURCE = "fantastic.source"
        const val KEY_QUALITY = "fantastic.quality"
    }
}
