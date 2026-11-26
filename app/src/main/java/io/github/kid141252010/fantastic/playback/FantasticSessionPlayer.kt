package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.data.nativeAudioPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class CurrentAudio(
    val mediaId: String,
    val queueIndex: Int,
    val occurrence: Int,
    val source: Source,
    val requestedQuality: String?,
    val actualQuality: String?,
    val label: String?,
    val details: String?,
)

/** A single ordered session queue; each engine receives only its contiguous playable run. */
@UnstableApi
internal class FantasticSessionPlayer(
    context: Context,
    private val native: ExoPlayer,
    private val apple: ApplePlaybackEngine,
    private val prefs: Prefs,
    private val localAudio: (Track, String?) -> OfflineAudio?,
    private val cache: PlaybackCache,
    private val requests: NativeAudioRequests,
) : SimpleBasePlayer(context.mainLooper) {
    private data class Stream(
        val song: QueuedSong,
        val server: String,
        val key: PlaybackCacheKey?,
        val requestedQuality: String,
        val quality: String,
        val offline: OfflineAudio?,
        val cached: PlaybackCacheEntry?,
        val requestId: String? = null,
    ) {
        val onlineApple get() = song.track.source == Source.Apple && offline == null && cached == null
        val usesAppleEngine get() = onlineApple ||
            (song.track.source == Source.Apple && offline == null && cached?.isCompleteAppleAlac == true)

        fun matches(other: Stream) = song == other.song && server == other.server && key == other.key &&
            (onlineApple && other.onlineApple || requestedQuality == other.requestedQuality && quality == other.quality) &&
            offline == other.offline && cached?.fileId == other.cached?.fileId
    }

    private data class PlaybackStart(val uid: Int, val request: String?, val quality: String, val codec: String?, val group: String?)

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = PlaybackStore(context, prefs)
    private var ordered = emptyList<QueuedSong>()
    private var traversal = emptyList<QueuedSong>()
    private var run = emptyList<Stream>()
    private var current: QueuedSong? = null
    private var nextUid = 0
    private var shuffle = false
    private var repeat = Player.REPEAT_MODE_OFF
    private var appleActive = false
    private var switching = false
    private var released = false
    private val appleState get() = apple.state.value
    private var playlistMetadata = MediaMetadata.EMPTY
    private var streamSerial = 0L
    private var lastStart: PlaybackStart? = null
    private var stopped = false
    private var actualNativeFormat: Format? = null
    private var actualNativeRequest: String? = null
    private var nativeDetailsFormat: Format? = null
    private var nativePresentation: Pair<String?, String?>? = null
    internal var onCurrentAudioChanged: (CurrentAudio?) -> Unit = {}
    private var publishedAudio: CurrentAudio? = null
    private val spatialDecoders = mutableMapOf<Pair<String, Int?>, Boolean>()
    private var lastAppleLoops = 0L
    private var discontinuity: Pair<Int, Long>? = null
    private var automaticOccurrence: Int? = null
    private var cacheRecovery: Pair<Int, String>? = null
    private var audioRecovery: Pair<Int, String>? = null

    private val analyticsListener = object : AnalyticsListener {
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            if (released || switching || stopped || appleActive || mediaLoadData.trackType != C.TRACK_TYPE_AUDIO) return
            if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return
            val eventItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            val playing = native.currentMediaItem ?: return
            val request = eventItem.localConfiguration?.customCacheKey ?: return
            if (request != playing.localConfiguration?.customCacheKey || eventItem.occurrence() != playing.occurrence()) return
            actualNativeRequest = request
            actualNativeFormat = mediaLoadData.trackFormat
            audioRecovery = null
            val selected = currentNativeStream() ?: return
            observeNativePlayback(selected)
            publishCurrentAudio()
        }
    }

    private val nativeListener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (appleActive || switching || reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            automaticOccurrence = new.mediaItem.occurrence()
            if (old.mediaItem.occurrence() == automaticOccurrence) discontinuity = reason to new.positionMs
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (appleActive || switching || released) return
            val selected = run.getOrNull(native.currentMediaItemIndex)?.takeIf {
                it.requestId == native.currentMediaItem?.localConfiguration?.customCacheKey &&
                    it.song.uid == native.currentMediaItem.occurrence()
            }
            if (selected != null) engineSelected(selected.song, selected.requestId)
            val error = native.playerError
            if (error != null) {
                clearNativeFormat()
                if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                    val changed = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }
                        .any { it is AudioChanged }
                    if (retryCachedStream() || (if (changed) retryChangedAudio() else retryNativeQuality())) return
                }
            }
            val auto = automaticOccurrence
            automaticOccurrence = null
            if (selected != null && auto == selected.song.uid && !selected.matches(stream(selected.song))) {
                start(selected.song, position(), wantsPlay())
                publish()
                return
            }
            if (native.playbackState == Player.STATE_ENDED) {
                cache.pinCurrent(null)
                if (native.playWhenReady) adjacent(true, automatic = true)?.let { start(it, 0L, true); publish(); return }
            } else if (selected != null && native.playerError == null && !stopped) {
                cache.pinCurrent(selected.cached?.fileId)
                observeNativePlayback(selected)
            } else if (native.playerError != null) {
                cache.pinCurrent(null)
            }
            publish()
        }
    }

    init {
        native.addListener(nativeListener)
        native.addAnalyticsListener(analyticsListener)
        scope.launch { cache.runPending() }
        scope.launch(Dispatchers.Main) { cache.revision.collect { if (!released && !switching) syncUpcoming() } }
        scope.launch { apple.cacheSource.collect { if (it != null) observeApplePlayback(it) } }
        scope.launch {
            apple.state.collect { state ->
                if (!appleActive || switching || released) return@collect
                val song = appleSong(state)
                if (song != null) {
                    val previous = current?.uid
                    val looped = apple.completedLoops != lastAppleLoops && previous == song.uid
                    lastAppleLoops = apple.completedLoops
                    val wrapped = previous == run.lastOrNull()?.song?.uid && state.queueIndex == 0 && previous != song.uid
                    if (looped || wrapped) {
                        val next = stream(song)
                        if (!next.usesAppleEngine) {
                            start(song, apple.position(), state.playWhenReady, next)
                            publish()
                            return@collect
                        }
                    }
                    engineSelected(song)
                }
                apple.activeCacheSource?.let(::observeApplePlayback)
                if (state.ended && state.playWhenReady) adjacent(true, automatic = true)?.let { start(it, 0L, true) }
                publish()
            }
        }
    }

    fun restore() {
        val saved = store.load()
        shuffle = saved.shuffle
        repeat = saved.repeat
        ordered = saved.songs.map { song ->
            if (song.quality != null && song.track.source.qualities.none { it.first == song.quality }) song.copy(quality = null)
            else song
        }
        val byUid = ordered.associateBy { it.uid }
        traversal = saved.order.map(byUid::getValue)
        nextUid = (ordered.maxOfOrNull { it.uid } ?: -1) + 1
        saved.current?.let { uid ->
            val song = byUid.getValue(uid)
            val position = store.position(uid).let { if (it >= song.track.durationMs && song.track.durationMs > 0) 0 else it }
            start(song, position, false)
        }
        publish()
        scope.launch {
            while (true) {
                delay(5_000L)
                if (enginePlaying()) checkpoint()
            }
        }
    }

    private fun enginePlaying() = if (appleActive) appleState.isPlaying else native.isPlaying
    private fun wantsPlay() = if (appleActive) appleState.playWhenReady else native.playWhenReady
    private fun appleSong(state: PlayerState): QueuedSong? =
        run.getOrNull(state.queueIndex)?.song?.takeIf { it.uid == apple.currentUid && it.track.mediaId == state.mediaId }

    private fun position(): Long {
        val song = current ?: return 0L
        val value = if (appleActive) {
            if (appleSong(appleState)?.uid == song.uid) apple.position() else 0L
        } else {
            currentNativeStream()?.let { native.currentPosition } ?: 0L
        }
        return value.coerceAtLeast(0L)
    }
    private fun engineSelected(song: QueuedSong, nativeRequest: String? = null) {
        val old = current ?: return
        if (song.uid == old.uid) return
        run.firstOrNull { it.song.uid == old.uid }?.requestId?.let { requests.get(it)?.audio?.set(null) }
        current = song
        cacheRecovery = null
        audioRecovery = null
        clearNativeFormat(keepRequest = nativeRequest)
        store.checkpoint(song.uid, 0L)
    }

    private fun checkpoint() { current?.let { store.checkpoint(it.uid, position()) } }
    private fun persistQueue(positionOverride: Long? = null) {
        store.save(PlaybackSnapshot(ordered, traversal.map { it.uid }, current?.uid, shuffle, repeat), positionOverride ?: position())
    }
    private fun publish() {
        invalidateState()
        publishCurrentAudio()
    }

    private fun selectedAudioFormat(tracks: Tracks): Format? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
            var only: Format? = null
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                if (only != null) return null
                only = group.getTrackFormat(index)
            }
            return only
        }
        return null
    }

    private fun MediaItem?.occurrence() = this?.mediaMetadata?.extras?.getInt(KEY_OCCURRENCE, -1)

    private fun currentNativeStream(): Stream? = run.getOrNull(native.currentMediaItemIndex)?.takeIf {
        it.song.uid == current?.uid && it.song.uid == native.currentMediaItem.occurrence() &&
            it.requestId == native.currentMediaItem?.localConfiguration?.customCacheKey
    }

    private fun clearNativeFormat(keepRequest: String? = null) {
        if (keepRequest == null || keepRequest != actualNativeRequest) {
            actualNativeFormat = null
            actualNativeRequest = null
        }
        nativeDetailsFormat = null
        nativePresentation = null
    }

    private fun clearPublishedAudio() {
        if (publishedAudio == null) return
        publishedAudio = null
        onCurrentAudioChanged(null)
    }

    private fun nativeAudioFormat(stream: Stream): Format? {
        if (stopped || native.playerError != null || native.playbackState == Player.STATE_IDLE) return null
        if (actualNativeRequest == stream.requestId) actualNativeFormat?.let { return it }
        return if (native.playbackState == Player.STATE_BUFFERING) nativeDetailsFormat
            else selectedAudioFormat(native.currentTracks)
    }

    private fun publishCurrentAudio() {
        val song = current
        val audio = when {
            song == null || stopped -> null
            appleActive -> {
                val state = appleState
                val matches = appleSong(state)?.uid == song.uid
                if (!matches || state.error != null || state.qualityLabel == null && state.audioDetails == null) null else {
                    CurrentAudio(song.track.mediaId, indexOfCurrent(), song.uid, song.track.source,
                        song.quality ?: apple.activeCacheSource?.profile?.quality, state.quality, state.qualityLabel, state.audioDetails)
                }
            }
            else -> currentNativeStream()?.let { stream ->
                val format = nativeAudioFormat(stream)
                if (format != nativeDetailsFormat) {
                    nativeDetailsFormat = format
                    nativePresentation = format?.let(::nativeAudioPresentation)
                }
                nativePresentation?.let { (label, details) ->
                    val actual = stream.cached?.actualQuality ?: stream.offline?.quality ?: when (song.track.source) {
                        Source.Netease -> stream.requestId?.let(requests::get)?.audio?.get()?.quality
                        else -> stream.quality
                    }
                    CurrentAudio(song.track.mediaId, indexOfCurrent(), song.uid, song.track.source,
                        stream.requestedQuality, actual, label, details)
                }
            }
        }
        if (audio != publishedAudio) {
            publishedAudio = audio
            onCurrentAudioChanged(audio)
        }
    }

    private fun observeNativePlayback(stream: Stream) {
        if (!native.isPlaying || native.playerError != null || stopped) return
        val format = nativeAudioFormat(stream) ?: return
        val source = stream.song.track.source
        val resolution = if (source == Source.Netease) stream.requestId?.let(requests::get)?.audio?.get() else null
        val quality = stream.cached?.actualQuality ?: stream.offline?.quality ?: when (source) {
            Source.Netease -> resolution?.quality ?: return
            else -> stream.quality
        }
        val signature = PlaybackStart(stream.song.uid, stream.requestId, quality, format.codecs ?: format.sampleMimeType, null)
        if (lastStart == signature) return
        lastStart = signature
        Log.i(TAG, "Playing ${stream.song.track.mediaId} occurrence=${stream.song.uid} quality=$quality mime=${format.sampleMimeType} " +
            "codecs=${format.codecs?.appleLogToken() ?: "-"} " +
            when { stream.cached != null -> "cache=${stream.cached.fileId}"; stream.offline != null -> "download"; else -> "online" })
        val key = stream.key ?: return
        if (stream.offline != null) return
        cache.markPlayed(key)
        if (stream.cached != null || source.qualities.none { it.first == quality }) return
        cache.enqueue(PlaybackRendition(stream.song.track, stream.song.uid, stream.requestedQuality, quality,
            native.duration, format, stream.requestId, resolvedNetease = resolution,
            neteaseRequestQuality = stream.quality.takeIf { source == Source.Netease }, key = key))
    }

    private fun observeApplePlayback(source: AppleCacheSource) {
        if (!appleActive || switching || released || stopped) return
        val song = current?.takeIf { it.uid == source.occurrence } ?: return
        if (source !== apple.activeCacheSource || apple.currentUid != song.uid) return
        val selected = run.firstOrNull { it.song.uid == song.uid } ?: return
        val key = PlaybackCacheKey.from(source.server, source.storefront, Source.Apple, song.track.id) ?: return
        if (selected.key != key || selected.server != key.server) return
        val request = source.requestSerial.toString()
        val signature = PlaybackStart(song.uid, request, source.quality, source.codecs, source.group)
        if (lastStart == signature) return
        lastStart = signature
        Log.i(TAG, "Playing ${song.track.mediaId} occurrence=${song.uid} quality=${source.quality} group=${source.group} hosted")
        cache.markPlayed(key)
        cache.enqueue(PlaybackRendition(song.track, song.uid, song.quality ?: source.profile.quality,
            source.quality, source.durationMs, null, request, appleGroup = source.group, appleCodecs = source.codecs,
            appleProfile = source.profile, appleMode = source.mode, resolvedApple = source.resolvedAlac, key = key))
    }

    fun selectQuality(quality: String) {
        val song = current ?: return
        if (song.track.source.qualities.none { it.first == quality }) return
        when (song.track.source) {
            Source.QQ -> prefs.qqQuality = quality
            Source.Netease -> prefs.neteaseQuality = quality
            Source.Apple -> Unit
        }
        val changed = song.copy(quality = quality)
        ordered = ordered.map { if (it.uid == song.uid) changed else it }
        traversal = traversal.map { if (it.uid == song.uid) changed else it }
        cacheRecovery = null
        val selected = stream(changed)
        start(changed, position(), wantsPlay(), selected)
        persistQueue()
        publish()
    }

    override fun getState(): State {
        val song = current
        val hosted = appleState
        val hostedMatches = appleActive && song != null && appleSong(hosted)?.uid == song.uid
        val nativeMatches = !appleActive && currentNativeStream() != null
        val nativeError = if (appleActive) null else native.playerError
        val error = if (appleActive) hosted.error?.let { PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED) }
            else nativeError
        val playbackState = when {
            song == null || stopped || error != null -> Player.STATE_IDLE
            !appleActive -> native.playbackState
            hosted.ended -> Player.STATE_ENDED
            hosted.buffering || (hosted.playWhenReady && !hosted.isPlaying) -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val items = traversal.map { entry ->
            val selected = entry.uid == song?.uid
            val base = entry.track.toQueueItem(entry.quality, occurrence = entry.uid)
            val item = if (selected && appleActive && hosted.error != null) {
                base.buildUpon().setMediaMetadata(base.mediaMetadata.buildUpon().setExtras(
                    Bundle(base.mediaMetadata.extras).apply { putString(KEY_PLAYBACK_MESSAGE, hosted.error) },
                ).build()).build()
            } else base
            val window = if (selected && nativeMatches && !native.currentTimeline.isEmpty)
                native.currentTimeline.getWindow(native.currentMediaItemIndex, Timeline.Window()) else null
            val duration = when {
                selected && hostedMatches -> hosted.durationMs
                selected && nativeMatches -> native.duration
                else -> 0L
            }.takeIf { it > 0 } ?: entry.track.durationMs
            MediaItemData.Builder(entry.uid)
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setIsSeekable(window?.isSeekable ?: (duration > 0))
                .setIsDynamic(window?.isDynamic == true)
                .setLiveConfiguration(window?.liveConfiguration)
                .setDurationUs(duration.toUsOrUnset())
                .build()
        }
        val contentPosition = position()
        val buffered = when {
            hostedMatches -> hosted.bufferedPositionMs
            nativeMatches -> native.bufferedPosition
            else -> 0L
        }
        val builder = State.Builder()
            .setPlaylist(items)
            .setPlaylistMetadata(playlistMetadata)
            .setCurrentMediaItemIndex(indexOfCurrent())
            .setAvailableCommands(baseCommands())
            .setMaxSeekToPreviousPositionMs(PREVIOUS_THRESHOLD_MS)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setPlayWhenReady(song != null && wantsPlay(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaybackSuppressionReason(if (appleActive) Player.PLAYBACK_SUPPRESSION_REASON_NONE else native.playbackSuppressionReason)
            .setIsLoading(playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY && !appleActive && native.isLoading)
            .setAudioAttributes(native.audioAttributes)
            .setVolume(native.volume)
            .setContentPositionMs(contentPosition)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(buffered.coerceAtLeast(0L)))
            .setTotalBufferedDurationMs(PositionSupplier.getConstant((buffered - contentPosition).coerceAtLeast(0L)))
        error?.let(builder::setPlayerError)
        discontinuity?.let { (reason, at) -> builder.setPositionDiscontinuity(reason, at) }
        discontinuity = null
        return builder.build()
    }

    private fun baseCommands(): Player.Commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_RELEASE,
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        Player.COMMAND_SET_PLAYLIST_METADATA,
    ).build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val song = current
        if (song != null) {
            val ended = if (appleActive) appleState.ended else native.playbackState == Player.STATE_ENDED
            if (playWhenReady && (stopped || ended)) {
                val at = if (stopped) position() else 0L
                start(song, at, true)
                publish()
            } else if (appleActive) apple.setPlaying(playWhenReady) else {
                native.playWhenReady = playWhenReady
                if (playWhenReady && native.playbackState == Player.STATE_IDLE) native.prepare()
            }
            if (!playWhenReady) checkpoint()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!appleActive && current != null && !stopped) native.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        checkpoint()
        stopped = true
        lastStart = null
        clearNativeFormat()
        cache.cancelPending()
        cache.pinCurrent(null)
        if (appleActive) apple.pause() else { native.pause(); native.stop() }
        publish()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (!released) {
            checkpoint()
            released = true
            native.removeListener(nativeListener)
            native.removeAnalyticsListener(analyticsListener)
            cache.cancelPending()
            cache.pinCurrent(null)
            requests.clear()
            scope.cancel()
            apple.close()
            native.release()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        updateEngineRepeat()
        persistQueue()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffle == shuffleModeEnabled) return Futures.immediateVoidFuture()
        shuffle = shuffleModeEnabled
        val throughCurrent = (indexOfCurrent() + 1).coerceAtLeast(0)
        val upcoming = traversal.drop(throughCurrent)
        traversal = if (shuffle) traversal.take(throughCurrent) + upcoming.shuffled() else {
            // Restore catalogue order without replaying occurrences that have already been traversed.
            val played = traversal.take(throughCurrent).mapTo(hashSetOf()) { it.uid }
            traversal.take(throughCurrent) + ordered.filter { it.uid !in played }
        }
        syncUpcoming()
        persistQueue()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaylistMetadata(playlistMetadata: MediaMetadata): ListenableFuture<*> {
        this.playlistMetadata = playlistMetadata
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val playing = wantsPlay()
        val index = startIndex.takeIf { it in mediaItems.indices } ?: 0
        val items = mediaItems.mapNotNull(::queueSong)
        if (items.size != mediaItems.size) return rejectedQueue()
        val extras = mediaItems.getOrNull(index)?.mediaMetadata?.extras
        if (extras?.containsKey(KEY_SHUFFLE_START) == true) shuffle = extras.getBoolean(KEY_SHUFFLE_START)
        val selected = items.getOrNull(index)
        ordered = items
        traversal = if (shuffle && selected != null) listOf(selected) + items.filter { it.uid != selected.uid }.shuffled() else items
        if (selected == null) clearEngines() else start(selected, startPositionMs.coerceAtLeast(0L), playing)
        persistQueue(startPositionMs.coerceAtLeast(0L))
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val additions = mediaItems.mapNotNull(::queueSong)
        if (additions.size != mediaItems.size) return rejectedQueue()
        editQueue(index, index, additions)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        editQueue(fromIndex, toIndex, emptyList())
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val additions = mediaItems.mapNotNull(::queueSong)
        if (additions.size != mediaItems.size) return rejectedQueue()
        editQueue(fromIndex, toIndex, additions)
        return Futures.immediateVoidFuture()
    }

    private fun editQueue(from: Int, to: Int, additions: List<QueuedSong>) {
        val f = from.coerceIn(0, traversal.size)
        val t = to.coerceIn(f, traversal.size)
        val removed = traversal.subList(f, t).mapTo(hashSetOf()) { it.uid }
        val anchor = traversal.getOrNull(t)
        traversal = traversal.toMutableList().apply { subList(f, t).clear(); addAll(f, additions) }
        ordered = if (!shuffle) traversal else ordered.filter { it.uid !in removed }.toMutableList().apply {
            addAll(indexOf(anchor).takeIf { it >= 0 } ?: size, additions)
        }
        val active = current
        if (traversal.isEmpty()) clearEngines()
        else if (active == null || active.uid in removed) start(traversal[f.coerceAtMost(traversal.lastIndex)], 0L, wantsPlay())
        else syncUpcoming()
        persistQueue()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val from = fromIndex.coerceIn(0, traversal.size)
        val to = toIndex.coerceIn(from, traversal.size)
        val moved = traversal.subList(from, to).toList()
        traversal = traversal.toMutableList().apply {
            subList(from, to).clear()
            addAll(newIndex.coerceIn(0, size), moved)
        }
        if (!shuffle) ordered = traversal
        syncUpcoming()
        persistQueue()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val song = current ?: return Futures.immediateVoidFuture()
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> adjacent(true)
            Player.COMMAND_SEEK_TO_PREVIOUS -> if (position() >= PREVIOUS_THRESHOLD_MS) song else adjacent(false) ?: song
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> adjacent(false)
            else -> traversal.getOrNull(mediaItemIndex) ?: song
        } ?: return Futures.immediateVoidFuture()
        val requestedPosition = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> 0L
            else -> positionMs.coerceAtLeast(0L)
        }
        if (target.uid == song.uid) {
            val ended = if (appleActive) appleState.ended else native.playbackState == Player.STATE_ENDED
            if (ended && requestedPosition == 0L) start(target, 0L, wantsPlay())
            else if (appleActive) apple.seekTo(requestedPosition) else native.seekTo(requestedPosition)
            if (appleActive) publish() else publishCurrentAudio()
            checkpoint()
            return Futures.immediateVoidFuture()
        }
        checkpoint()
        val selected = stream(target)
        val engineIndex = run.indexOfFirst { it.song.uid == target.uid }
        val canReuse = run.getOrNull(engineIndex)?.matches(selected) == true
        when {
            !canReuse || appleActive -> start(target, 0L, wantsPlay(), selected)
            else -> {
                engineSelected(target)
                cache.pinCurrent(selected.cached?.fileId)
                native.seekTo(engineIndex, 0L)
                if (native.playbackState == Player.STATE_IDLE) native.prepare()
            }
        }
        publishCurrentAudio()
        store.checkpoint(target.uid, 0L)
        return Futures.immediateVoidFuture()
    }

    private fun queueSong(item: MediaItem): QueuedSong? {
        val track = item.toTrack() ?: return null
        val quality = item.mediaMetadata.extras?.getString(KEY_QUALITY)?.takeIf { q -> track.source.qualities.any { it.first == q } }
        return QueuedSong(nextUid++, track, quality)
    }

    private fun rejectedQueue(): ListenableFuture<*> = Futures.immediateFailedFuture<Any>(IllegalArgumentException("Invalid playback queue"))

    private fun indexOfCurrent(): Int = traversal.indexOfFirst { it.uid == current?.uid }

    private fun adjacent(forward: Boolean, automatic: Boolean = false): QueuedSong? {
        val index = indexOfCurrent()
        if (index < 0) return null
        if (automatic && repeat == Player.REPEAT_MODE_ONE) return current
        return traversal.getOrNull(index + if (forward) 1 else -1)
            ?: if (repeat == Player.REPEAT_MODE_ALL) (if (forward) traversal.firstOrNull() else traversal.lastOrNull()) else null
    }

    private fun stream(song: QueuedSong): Stream {
        val track = song.track
        val storefront = if (track.source == Source.Apple) track.country.normalizedAppleCountry() ?: prefs.storefront else prefs.storefront
        val key = PlaybackCacheKey.from(prefs.baseUrl, storefront, track.source, track.id)
        val server = key?.server ?: prefs.baseUrl
        val offline = localAudio(track, song.quality)
        val preferred = song.quality ?: prefs.preferredQuality(track.source)
        if (offline != null || key == null) return Stream(song, server, key, preferred, offline?.quality ?: preferred, offline, null)
        if (track.source != Source.Apple) {
            val cached = cache.find(key, preferred)
            return Stream(song, server, key, preferred, cached?.actualQuality ?: preferred, null, cached)
        }
        val previousNetwork = cache.appleNetwork(key) ?: return Stream(song, server, key, preferred, preferred, null, null)
        val network = applePlaybackNetwork(appContext)
        val profile = applePlaybackProfile(appContext, prefs, previousNetwork)
        val requested = song.quality ?: profile.quality
        val cached = cache.find(key, requested, profile)?.takeIf { entry ->
            val spatial = entry.appleMode in HOSTED_SPATIAL
            entry.isCompleteAppleAlac ||
                if (network != null) prefs.appleTransition == "off" && !spatial else !spatial || canDecodeSpatial(entry)
        }
        return Stream(song, server, key, requested, cached?.actualQuality ?: requested, null, cached)
    }

    private fun canDecodeSpatial(entry: PlaybackCacheEntry): Boolean = spatialDecoders.getOrPut(entry.mime to entry.sampleRate) {
        val format = Format.Builder().setSampleMimeType(entry.mime).setSampleRate(entry.sampleRate ?: Format.NO_VALUE).build()
        try {
            MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false).any { it.isFormatSupported(format) }
        } catch (_: MediaCodecUtil.DecoderQueryException) { false }
    }

    private fun withRequest(stream: Stream): Stream =
        if (stream.usesAppleEngine) stream else stream.copy(requestId = "${stream.song.uid}:${++streamSerial}")

    private fun mediaItem(stream: Stream): MediaItem {
        val cached = stream.cached
        val local = cached?.let { playbackCacheUri(it.fileId) } ?: stream.offline?.file?.let(Uri::fromFile)
        val item = stream.song.nativeItem(stream.server, stream.quality, local, stream.requestId)
        val track = stream.song.track
        if (local == null && track.source != Source.Apple) {
            val uri = checkNotNull(item.localConfiguration).uri
            val song = NativeSong(track.source, track.id, stream.quality, stream.server)
            requests.add(checkNotNull(stream.requestId), NativeAudioRequests.Request(uri, song))
        }
        return item
    }

    private fun start(song: QueuedSong, at: Long, playing: Boolean, selection: Stream = stream(song), recovery: Boolean = false) {
        val selected = withRequest(selection)
        val index = traversal.indexOf(song)
        val before = traversal.subList(0, index).asReversed().asSequence().map { stream(it) }
            .takeWhile { it.usesAppleEngine == selected.usesAppleEngine }.toList().asReversed()
        val after = traversal.subList(index + 1, traversal.size).asSequence().map { stream(it) }
            .takeWhile { it.usesAppleEngine == selected.usesAppleEngine }.toList()
        checkpoint()
        switching = true
        try {
            current = song
            stopped = false
            run = before.map(::withRequest) + selected + after.map(::withRequest)
            requests.clear()
            cache.pinCurrent(null)
            clearNativeFormat()
            clearPublishedAudio()
            if (!recovery) cacheRecovery = null
            audioRecovery = null
            automaticOccurrence = null
            discontinuity = null
            val wasApple = appleActive
            appleActive = selected.usesAppleEngine
            if (appleActive) {
                if (selected.onlineApple) Log.i(TAG, "apple-route id=${song.track.id.appleLogToken()} path=hosted occurrence=${song.uid}")
                if (!wasApple) { native.pause(); native.stop(); native.clearMediaItems() }
                apple.setRepeatMode(engineRepeatMode())
                apple.playQueue(run.map { it.song }, before.size, at, playing, song.quality,
                    server = selected.server, storefront = selected.key?.storefront ?: song.track.country.normalizedAppleCountry() ?: prefs.storefront)
                lastAppleLoops = apple.completedLoops
            } else {
                if (wasApple) apple.stop()
                cache.pinCurrent(selected.cached?.fileId)
                native.repeatMode = engineRepeatMode()
                native.setMediaItems(run.map(::mediaItem), before.size, at)
                native.playWhenReady = playing
                if (playing) native.prepare()
            }
        } finally { switching = false }
        store.checkpoint(song.uid, at.coerceAtLeast(0L))
    }

    /** A queue edit updates the upcoming run, not the current source or its buffer. */
    private fun syncUpcoming() {
        val song = current ?: return
        val index = indexOfCurrent()
        val activeIndex = run.indexOfFirst { it.song.uid == song.uid }
        if (index < 0 || activeIndex < 0 || !appleActive && currentNativeStream() == null) return
        val after = traversal.subList(index + 1, traversal.size).asSequence().map { stream(it) }
            .takeWhile { it.usesAppleEngine == appleActive }.toList()
        val previous = run.subList(activeIndex + 1, run.size)
        var kept = 0
        while (kept < minOf(previous.size, after.size) && previous[kept].matches(after[kept])) kept++
        if (kept < previous.size || kept < after.size) {
            val from = activeIndex + 1 + kept
            val added = after.subList(kept, after.size).map(::withRequest)
            run = run.take(from) + added
            if (appleActive) apple.replaceUpcoming(run.subList(activeIndex + 1, run.size).map { it.song })
            else {
                native.replaceMediaItems(from, native.mediaItemCount, added.map(::mediaItem))
                previous.subList(kept, previous.size).forEach { it.requestId?.let(requests::remove) }
            }
        }
        updateEngineRepeat()
    }

    private fun engineRepeatMode() = when {
        repeat == Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
        repeat == Player.REPEAT_MODE_ALL && run.size == traversal.size && run.indices.all { run[it].song.uid == traversal[it].uid } -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    private fun updateEngineRepeat() {
        if (appleActive) apple.setRepeatMode(engineRepeatMode()) else native.repeatMode = engineRepeatMode()
    }

    private fun retryCachedStream(): Boolean {
        val stream = currentNativeStream() ?: return false
        val cached = stream.cached ?: return false
        cache.invalidate(cached.fileId)
        cache.pinCurrent(null)
        val request = stream.song.uid to stream.requestedQuality
        if (request == cacheRecovery) return false
        cacheRecovery = request
        val online = stream.copy(quality = stream.requestedQuality, cached = null, requestId = null)
        if (online.usesAppleEngine) start(stream.song, position(), wantsPlay(), online, recovery = true)
        else replaceNativeStream(online)
        publish()
        return true
    }

    private fun retryChangedAudio(): Boolean {
        val stream = currentNativeStream() ?: return false
        if (stream.song.track.source == Source.Apple || stream.offline != null || stream.cached != null) return false
        val key = stream.song.uid to stream.quality
        if (audioRecovery == key) return false
        audioRecovery = key
        // A new media item/period discards the old extractor, not just its HTTP connection.
        replaceNativeStream(stream)
        publish()
        return true
    }

    private fun replaceNativeStream(replacement: Stream) {
        val index = native.currentMediaItemIndex
        val request = run[index].requestId
        val selected = withRequest(replacement)
        val at = position()
        val playing = wantsPlay()
        switching = true
        try {
            clearNativeFormat()
            clearPublishedAudio()
            cache.pinCurrent(selected.cached?.fileId)
            run = run.toMutableList().apply { set(index, selected) }
            native.replaceMediaItem(index, mediaItem(selected))
            native.seekTo(index, at)
            native.playWhenReady = playing
            native.prepare()
            request?.let(requests::remove)
        } finally { switching = false }
    }

    private fun retryNativeQuality(): Boolean {
        val currentStream = currentNativeStream() ?: return false
        val source = currentStream.song.track.source
        if (!wantsPlay() || source == Source.Apple || currentStream.offline != null) return false
        val quality = currentStream.quality
        val next = if (source == Source.QQ) when (quality) {
            "RS", "RA360", "DTS", "DTSX", "DAEAC3", "DA", "DA448", "DA1500" -> "SQ"
            "SQ" -> "HQ"
            "HQ" -> "AAC192"
            "AAC192", "WEB", "AAC48", "OGG192", "OGG96" -> "MP3128"
            else -> null
        } else {
            val choices = Prefs.NETEASE_QUALITIES
            val index = choices.indexOfFirst { it.first == quality }
            if (index >= 0) choices.getOrNull(index + 1)?.first else null
        }
        if (next == null) return false
        replaceNativeStream(currentStream.copy(quality = next, cached = null, requestId = null))
        publish()
        return true
    }

    private fun clearEngines() {
        switching = true
        current = null
        cache.cancelPending()
        cache.pinCurrent(null)
        cacheRecovery = null
        clearNativeFormat()
        requests.clear()
        run = emptyList()
        apple.stop()
        appleActive = false
        native.pause()
        native.stop()
        native.clearMediaItems()
        switching = false
    }

    private fun Long.toUsOrUnset(): Long = if (this == C.TIME_UNSET || this <= 0L) C.TIME_UNSET else Util.msToUs(this)

    private companion object {
        const val PREVIOUS_THRESHOLD_MS = 4_000L
        const val TAG = "FantasticPlayback"
        val HOSTED_SPATIAL = setOf("joc2", "jocbin", "ec3")
    }
}
