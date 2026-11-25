package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.reportedAudioDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class ApplePlaybackProfile(val network: String, val quality: String, val spatial: String)

internal fun ApplePlaybackProfile.requestsLossless(explicitQuality: String?): Boolean = when (explicitQuality) {
    null -> quality == "lossless" || quality == "hires"
    "alac", "lossless", "hires" -> true
    else -> false
}

internal fun applePlaybackNetwork(context: Context): String? {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    val unmetered = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    val cellular = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    return if (unmetered || !cellular) "wifi" else "cellular"
}

internal fun applePlaybackProfile(context: Context, prefs: Prefs, offlineNetwork: String? = null): ApplePlaybackProfile {
    val network = applePlaybackNetwork(context) ?: if (offlineNetwork == "cellular") "cellular" else "wifi"
    val quality = if (network == "cellular") prefs.appleCellularQuality else prefs.appleWifiQuality
    return ApplePlaybackProfile(network, quality, prefs.appleSpatial)
}

internal data class AppleCacheSource(
    val occurrence: Int,
    val requestSerial: Long,
    val profile: ApplePlaybackProfile,
    val group: String,
    val codecs: String,
    val quality: String,
    val mode: String,
    val durationMs: Long,
    val server: String,
    val storefront: String,
)

private val AppleNativeTrack.usesAlacOutput: Boolean get() = alacCookie != null && !widevine

/** In-process Apple media adapter. The session layer keeps ownership of queue traversal. */
@OptIn(UnstableApi::class)
internal class ApplePlaybackEngine(
    context: Context,
    private val prefs: Prefs,
    private val api: FantasticApi,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decryptor = AppleNativeDecrypt(api) { path ->
        appContext.assets.open(path).use { it.readBytes() }
    }
    private val closed = AtomicBoolean(false)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _cacheSource = MutableStateFlow<AppleCacheSource?>(null)
    internal val cacheSource: StateFlow<AppleCacheSource?> = _cacheSource.asStateFlow()
    internal var activeCacheSource: AppleCacheSource?
        get() = _cacheSource.value
        private set(value) { _cacheSource.value = value }
    internal var completedLoops: Long = 0L
        private set

    private val player = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private var songs = emptyList<QueuedSong>()
    private var occurrence = -1
    private var currentNative: AppleNativeTrack? = null
    private var alacOutput: AlacOutput? = null
    private var server = ""
    private var storefront = "us"
    private var profile = ApplePlaybackProfile("wifi", "hires", "auto")
    private var requestSerial = 0L
    private var lastPositionMs = 0L
    private var poller: Runnable? = null
    private var prepareJob: Job? = null
    private var startedAt = 0L
    private var userRequestedQuality: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                onMain { updateFromPlayer() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onMain { updateFromPlayer() }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onMain {
                    val track = currentNative ?: return@onMain
                    if (closed.get() || track.usesAlacOutput || player.mediaItemCount == 0 || player.playerError !== error) return@onMain
                    val mode = if (track.widevine) "widevine-mse" else "mse"
                    val reason = error.errorCodeName
                    Log.w("FantasticPlayback", "apple-error id=${track.adamId.appleLogToken()} " +
                        "occurrence=${songs.getOrNull(occurrence)?.uid ?: -1} request=$requestSerial " +
                        "group=${track.variant.groupId.appleLogToken()} mode=$mode stage=media3 reason=${reason.appleLogToken()}")
                    publishError(applePlaybackFailureText("media3", reason))
                }
            }
        })
    }

    internal fun prewarm(server: String) = Unit

    fun position(): Long {
        val output = alacOutput
        if (output != null) return output.positionMs()
        if (player.mediaItemCount == 0) return lastPositionMs
        return player.currentPosition.takeIf { it >= 0L } ?: lastPositionMs
    }

    fun playQueue(
        songs: List<QueuedSong>, index: Int, startPositionMs: Long = 0L,
        playWhenReady: Boolean = true, quality: String? = null,
        server: String = prefs.baseUrl, storefront: String = prefs.storefront,
    ) = onMain {
        if (closed.get()) return@onMain
        val selected = songs.getOrNull(index) ?: return@onMain
        require(songs.all { it.track.source == Source.Apple })
        val normalized = SourceStatus.normalizeBaseUrl(server)
        val previous = _state.value
        this.songs = if (quality == null || selected.quality == quality) songs.toList()
        else songs.toMutableList().apply { this[index] = selected.copy(quality = quality) }
        this.server = normalized ?: server
        this.storefront = storefront.lowercase().takeIf { it.matches(STORE_FRONT) } ?: "us"
        this.profile = applePlaybackProfile(appContext, prefs)
        this.userRequestedQuality = quality ?: selected.quality
        this.occurrence = index
        this.lastPositionMs = startPositionMs.coerceAtLeast(0L)
        val serial = ++requestSerial
        this.startedAt = SystemClock.elapsedRealtime()
        clearPlayback()
        _state.value = previous.copy(
            connected = true,
            mediaId = "apple:${selected.track.id}",
            title = selected.track.title,
            artist = selected.track.artist,
            artworkUri = selected.track.artwork(600),
            isPlaying = false,
            buffering = playWhenReady,
            durationMs = selected.track.durationMs,
            hasNext = index < this.songs.lastIndex,
            hasPrevious = index > 0,
            queue = this.songs.map { it.track },
            queueIndex = index,
            playWhenReady = playWhenReady,
            ended = false,
            error = null,
            quality = null,
            qualityLabel = null,
            audioDetails = null,
            audioQualityNotice = null,
            bufferedPositionMs = 0L,
            seekGeneration = previous.seekGeneration + 1,
        )
        if (normalized == null) publishError("服务器地址无效")
        else if (playWhenReady) beginPrepare(serial, selected, lastPositionMs)
    }

    fun replaceUpcoming(tail: List<QueuedSong>) = onMain {
        val current = songs.getOrNull(occurrence) ?: return@onMain
        songs = songs.take(occurrence + 1) + tail
        _state.value = _state.value.copy(queue = songs.map { it.track }, hasNext = tail.isNotEmpty())
        if (current.uid != songs.getOrNull(occurrence)?.uid) return@onMain
    }

    fun pause() = onMain {
        if (!_state.value.playWhenReady) return@onMain
        lastPositionMs = position()
        activeCacheSource = null
        alacOutput?.pause()
        player.pause()
        stopPoller()
        _state.value = _state.value.copy(playWhenReady = false, isPlaying = false, buffering = false)
    }

    fun seekTo(ms: Long) = onMain {
        val state = _state.value
        if (state.queueIndex !in state.queue.indices) return@onMain
        val target = ms.coerceIn(0L, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        lastPositionMs = target
        activeCacheSource = null
        if (alacOutput != null || player.mediaItemCount == 0) {
            val song = songs.getOrNull(occurrence) ?: return@onMain
            val serial = ++requestSerial
            clearPlayback()
            _state.value = state.copy(
                ended = false,
                seekGeneration = state.seekGeneration + 1,
                bufferedPositionMs = target,
                quality = null,
                qualityLabel = null,
                audioDetails = null,
                audioQualityNotice = null,
                error = null,
                isPlaying = false,
                buffering = state.playWhenReady,
            )
            if (state.playWhenReady) beginPrepare(serial, song, target)
        } else {
            player.seekTo(target)
            _state.value = _state.value.copy(ended = false, seekGeneration = state.seekGeneration + 1)
        }
    }

    fun skipTo(index: Int): Boolean = false

    fun setPlaying(playing: Boolean) = onMain {
        if (!playing) {
            pause()
            return@onMain
        }
        val song = songs.getOrNull(occurrence) ?: return@onMain
        if (alacOutput != null || player.mediaItemCount > 0) {
            alacOutput?.resume() ?: player.play()
            _state.value = _state.value.copy(playWhenReady = true, ended = false)
            schedulePoll()
        } else {
            playQueue(songs, occurrence, if (_state.value.ended) 0L else position(), true, userRequestedQuality, server, storefront)
        }
        if (song.track.id != _state.value.mediaId?.removePrefix("apple:")) {
            playQueue(songs, occurrence, 0L, true, userRequestedQuality, server, storefront)
        }
    }

    fun stop() = onMain {
        requestSerial++
        clearPlayback()
        songs = emptyList()
        occurrence = -1
        _state.value = PlayerState(connected = false, repeatMode = _state.value.repeatMode)
    }

    fun setRepeatMode(mode: Int) = onMain {
        if (_state.value.repeatMode != mode) _state.value = _state.value.copy(repeatMode = mode)
    }

    fun selectQuality(group: String) = onMain {
        if (group !in CACHE_QUALITIES) return@onMain
        val song = songs.getOrNull(occurrence) ?: return@onMain
        lastPositionMs = position()
        val updated = song.copy(quality = group)
        songs = songs.toMutableList().apply { this[occurrence] = updated }
        userRequestedQuality = group
        val serial = ++requestSerial
        clearPlayback()
        _state.value = _state.value.copy(
            queue = songs.map { it.track },
            buffering = true,
            isPlaying = false,
            playWhenReady = true,
            ended = false,
            bufferedPositionMs = lastPositionMs,
            quality = null,
            qualityLabel = null,
            audioDetails = null,
            audioQualityNotice = null,
            error = null,
        )
        beginPrepare(serial, updated, lastPositionMs)
    }

    fun close() = onMain {
        if (!closed.compareAndSet(false, true)) return@onMain
        prepareJob?.cancel()
        stopOutput()
        stopPoller()
        player.release()
        decryptor.close()
        scope.cancel()
        _state.value = PlayerState()
    }

    private fun beginPrepare(serial: Long, song: QueuedSong, startPositionMs: Long) {
        val requestServer = server
        val requestProfile = profile
        val requestedQuality = userRequestedQuality
        prepareJob = scope.launch {
            try {
                val track = withContext(Dispatchers.IO) {
                    decryptor.resolve(
                        server = requestServer,
                        adamId = song.track.id,
                        requestedQuality = requestedQuality,
                        profile = requestProfile,
                    )
                }
                if (serial != requestSerial || closed.get()) return@launch
                currentNative = track
                if (track.usesAlacOutput) {
                    startAlac(serial, song, track, startPositionMs)
                } else {
                    startMedia3(song, track, startPositionMs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (serial != requestSerial || closed.get()) return@launch
                val failure = error as? ApplePreparationException
                val stage = failure?.stage ?: "prepare"
                val reason = failure?.reason ?: error.javaClass.simpleName
                Log.w("FantasticPlayback", "apple-error id=${song.track.id.appleLogToken()} occurrence=${song.uid} request=$serial " +
                    "stage=${stage.appleLogToken()} reason=${reason.appleLogToken()}")
                publishError(applePlaybackFailureText(stage, reason))
            }
        }
    }

    private fun startMedia3(song: QueuedSong, track: AppleNativeTrack, startPositionMs: Long) {
        val itemUri = if (track.widevine) track.mediaUrl.toString() else "apple-native://playlist"
        val itemBuilder = MediaItem.Builder()
            .setMediaId("apple:${song.track.id}")
            .setUri(itemUri)
        val widevineManifest = track.widevineManifest
        val sourceFactory = if (widevineManifest != null) {
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(30_000)
            val callback = AppleNativeWidevine(api, server, song.track.id).also {
                it.setKeyUris(track.playlist.segments.mapNotNull { segment -> segment.keyUri }.distinct())
            }
            val drm = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(AppleNativeWidevine.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(callback)
            HlsMediaSource.Factory(HlsDataSourceFactory { dataType ->
                if (dataType == C.DATA_TYPE_MANIFEST) ByteArrayDataSource(widevineManifest)
                else http.createDataSource()
            })
                .setDrmSessionManagerProvider(DrmSessionManagerProvider { drm })
        } else {
            HlsMediaSource.Factory(AppleNativeDataSourceFactory(track, decryptor))
        }
        if (track.widevine) {
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(AppleNativeWidevine.WIDEVINE_UUID).build(),
            )
        }
        val source = sourceFactory.createMediaSource(itemBuilder.build())
        player.playWhenReady = _state.value.playWhenReady
        player.setMediaSource(source, startPositionMs)
        updateFormatState(song, track)
        player.prepare()
        schedulePoll()
    }

    private fun startAlac(serial: Long, song: QueuedSong, track: AppleNativeTrack, startPositionMs: Long) {
        val state = _state.value
        _state.value = state.copy(durationMs = track.playlist.totalDurationMs, buffering = state.playWhenReady)
        val output = AlacOutput(
            track = track,
            decryptor = decryptor,
            startPositionMs = startPositionMs,
            onFirstPcm = { segmentIndex, frames ->
                onMain {
                    if (serial != requestSerial || closed.get()) return@onMain
                    updateFormatState(song, track, buffering = false)
                    Log.i("FantasticPlayback", "apple-alac-first-pcm id=${song.track.id.appleLogToken()} " +
                        "occurrence=${song.uid} request=$serial segment=$segmentIndex frames=$frames")
                }
            },
            onFinished = { onMain { if (serial == requestSerial && !closed.get()) markEnded() } },
            onError = { stage, reason ->
                onMain {
                    if (serial != requestSerial || closed.get()) return@onMain
                    Log.w("FantasticPlayback", "apple-error id=${song.track.id.appleLogToken()} occurrence=${song.uid} request=$serial " +
                        "group=${track.variant.groupId.appleLogToken()} mode=alac stage=${stage.appleLogToken()} reason=${reason.appleLogToken()}")
                    publishError(applePlaybackFailureText(stage, reason))
                }
            },
        )
        alacOutput = output
        if (!_state.value.playWhenReady) output.pause()
        output.start()
        schedulePoll()
    }

    private fun updateFormatState(song: QueuedSong, track: AppleNativeTrack, buffering: Boolean = true) {
        val old = _state.value
        val alac = track.usesAlacOutput
        val quality = if (alac) "alac" else codecGroup(track.variant.groupId)
        val duration = track.playlist.totalDurationMs.takeIf { it > 0 } ?: song.track.durationMs
        val label = qualityLabel(track)
        val details = reportedAudioDetails(
            codec = if (alac) "alac" else track.variant.codecs,
            sampleRate = track.variant.sampleRate,
            bitDepth = track.variant.bitDepth,
            channels = track.variant.channels.toString(),
            bitrate = track.variant.bandwidth,
            bitrateLabel = "变体带宽",
        )
        _state.value = old.copy(
            connected = true,
            durationMs = duration,
            quality = quality,
            qualityLabel = label,
            audioDetails = details,
            audioQualityNotice = track.audioQualityNotice,
            buffering = buffering,
            error = null,
        )
    }

    private fun updateFromPlayer() {
        val track = currentNative ?: return
        if (closed.get() || track.usesAlacOutput || player.mediaItemCount == 0) return
        val state = _state.value
        val playing = player.isPlaying && player.playWhenReady
        val buffering = player.playbackState == Player.STATE_BUFFERING
        val ended = player.playbackState == Player.STATE_ENDED
        lastPositionMs = player.currentPosition.coerceAtLeast(0L)
        _state.value = state.copy(
            isPlaying = playing,
            playWhenReady = player.playWhenReady,
            buffering = buffering,
            ended = ended,
            durationMs = player.duration.takeIf { it > 0 } ?: state.durationMs,
            bufferedPositionMs = player.bufferedPosition,
        )
        if (ended) activeCacheSource = null else if (playing) publishCache(if (track.widevine) "widevine-mse" else "mse")
        if (ended) stopPoller()
    }

    private fun schedulePoll() {
        if (poller != null || closed.get() || !_state.value.playWhenReady) return
        val task = object : Runnable {
            override fun run() {
                if (poller !== this) return
                poller = null
                if (closed.get()) return
                if (server != prefs.baseUrl && SourceStatus.normalizeBaseUrl(prefs.baseUrl) != server) {
                    publishError("服务器已更改，点按播放重新连接")
                    return
                }
                val output = alacOutput
                if (output != null) {
                    lastPositionMs = output.positionMs()
                    val old = _state.value
                    val playing = old.playWhenReady && old.quality == "alac" && output.isPlaying()
                    _state.value = old.copy(
                        isPlaying = playing,
                        buffering = old.playWhenReady && !playing && !old.ended,
                        bufferedPositionMs = lastPositionMs,
                    )
                    if (playing) publishCache("alac")
                } else updateFromPlayer()
                if (!_state.value.ended && (_state.value.playWhenReady || _state.value.isPlaying)) {
                    poller = this
                    main.postDelayed(this, 250L)
                }
            }
        }
        poller = task
        main.postDelayed(task, 100L)
    }

    private fun stopPoller() {
        poller?.let(main::removeCallbacks)
        poller = null
    }

    private fun stopOutput() {
        alacOutput?.close()
        alacOutput = null
        activeCacheSource = null
    }

    private fun clearPlayback() {
        prepareJob?.cancel()
        prepareJob = null
        currentNative = null
        stopPoller()
        stopOutput()
        player.stop()
        player.clearMediaItems()
    }

    private fun publishCache(mode: String) {
        val native = currentNative ?: return
        val state = _state.value
        val song = songs.getOrNull(occurrence) ?: return
        if (!state.isPlaying || state.durationMs <= 0L || state.quality == null) return
        val source = AppleCacheSource(
            occurrence = song.uid,
            requestSerial = requestSerial,
            profile = profile,
            group = native.variant.groupId,
            codecs = native.variant.codecs,
            quality = state.quality,
            mode = mode,
            durationMs = state.durationMs,
            server = server,
            storefront = storefront,
        )
        if (activeCacheSource != source) {
            activeCacheSource = source
            val format = if (mode == "alac") null else player.currentTracks.groups.firstNotNullOfOrNull { group ->
                if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) null
                else (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
            }
            val actualCodecs = if (mode == "alac") "alac" else format?.codecs
            Log.i("FantasticPlayback", "native-apple id=${song.track.id.appleLogToken()} group=${source.group.appleLogToken()} " +
                "occurrence=${source.occurrence} request=${source.requestSerial} " +
                "codecs=${actualCodecs?.appleLogToken() ?: "-"} mode=$mode declaredCodecs=${source.codecs.appleLogToken()} " +
                "mime=${format?.sampleMimeType?.substringAfter('/')?.appleLogToken() ?: "-"}")
        }
    }

    private fun markEnded() {
        activeCacheSource = null
        stopPoller()
        _state.value = _state.value.copy(isPlaying = false, buffering = false, ended = true, playWhenReady = true)
    }

    private fun publishError(message: String) {
        lastPositionMs = position()
        requestSerial++
        clearPlayback()
        _state.value = _state.value.copy(
            connected = true,
            isPlaying = false,
            playWhenReady = false,
            buffering = false,
            error = message,
            audioQualityNotice = null,
        )
    }

    private fun qualityLabel(track: AppleNativeTrack): String {
        val group = track.variant.groupId.lowercase()
        val codecs = track.variant.codecs.lowercase()
        return when {
            track.usesAlacOutput -> if (track.variant.sampleRate > 48_000) "Hi-Res 无损" else "无损"
            "atmos" in group || "e-ac-3" in codecs || "ec-3" in codecs ->
                if ("binaural" in group) "空间音频" else "杜比全景声"
            else -> "AAC"
        }
    }

    private fun codecGroup(group: String): String? {
        val name = group.lowercase()
        val capped = "stereo-128" in name
        val he = "he-" in name
        return when {
            name.isEmpty() -> null
            "binaural" in name -> if (he) "he-binaural" else if (capped) "aac-binaural-128" else "aac-binaural"
            "downmix" in name -> if (he) "he-downmix" else if (capped) "aac-downmix-128" else "aac-downmix"
            "atmos" in name -> "atmos"
            he -> "aac-he"
            "stereo-" in name -> if (capped) "aac-128" else "aac"
            else -> group.take(64)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        val STORE_FRONT = Regex("[a-z]{2}")
        val CACHE_QUALITIES = Prefs.APPLE_QUALITIES.mapTo(hashSetOf()) { it.first }
    }
}

private class AlacOutput(
    private val track: AppleNativeTrack,
    private val decryptor: AppleNativeDecrypt,
    private val startPositionMs: Long,
    private val onFirstPcm: (segmentIndex: Int, frames: Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (stage: String, reason: String) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportedPositionMs = AtomicLong(startPositionMs)
    private val playing = AtomicBoolean(false)
    @Volatile private var paused = false
    @Volatile private var closed = false
    private var playbackFramesBase = 0L
    private var lastPlaybackHead = 0L
    private var job: Job? = null

    fun start() {
        job = scope.launch { run() }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun isPlaying(): Boolean = playing.get() && !paused && !closed

    fun positionMs(): Long = reportedPositionMs.get()

    override fun close() {
        closed = true
        playing.set(false)
        job?.cancel()
        scope.cancel()
    }

    private suspend fun run() {
        var decoder = 0L
        var output: AudioTrack? = null
        var stage = "alac_cookie"
        try {
            val cookie = track.alacCookie ?: throw IOException("Apple ALAC 配置缺失")
            decoder = AlacDecoder.open(cookie.copyOfRange(4, cookie.size))
            if (decoder == 0L) throw IOException("Apple ALAC 解码器初始化失败")
            stage = "audio_output"
            val sampleRate = AlacDecoder.sampleRate(decoder).takeIf { it > 0 } ?: track.variant.sampleRate
            val channels = AlacDecoder.channels(decoder).takeIf { it > 0 } ?: track.alacChannels
            val bits = AlacDecoder.bits(decoder).takeIf { it > 0 } ?: track.alacBits
            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                else -> throw IOException("Apple ALAC 声道数不可播放")
            }
            val opened = openAlacTrack(sampleRate, channelMask, bits, track.outputRateCap)
            val trackOutput = opened.track
            val encoding = opened.encoding
            val outputRate = opened.sampleRate
            output = trackOutput
            Log.i("FantasticPlayback", "apple-alac-output id=${track.adamId.appleLogToken()} bits=$bits " +
                "sampleRate=$sampleRate outputRate=$outputRate channels=$channels encoding=$encoding " +
                "pcm16=${encoding == AudioFormat.ENCODING_PCM_16BIT && bits != 16}")
            val bytesPerFrame = channels * if (bits == 16) 2 else 4
            supervisorScope {
                val segments = track.playlist.segments
                var segmentIndex = segments.indexForPosition(startPositionMs)
                val segmentOffsetMs = segments.take(segmentIndex).sumOf { it.durationMs }
                var skipFrames = ((startPositionMs - segmentOffsetMs) * sampleRate / 1_000L).coerceAtLeast(0L)
                var firstPcm = true
                var pending: Deferred<AppleNativeFetchedSegment>? = null
                try {
                    stage = "fragment"
                    var fetched = decryptor.fetchSegment(track, segments[segmentIndex])
                    while (!closed) {
                        val nextIndex = segmentIndex + 1
                        // Only wait for the next fetch after writing the current fragment.
                        pending = segments.getOrNull(nextIndex)?.let { next ->
                            async { decryptor.fetchSegment(track, next) }
                        }
                        for (range in fetched.sampleRanges) {
                            if (closed) break
                            stage = "audio_output"
                            while (paused && !closed) {
                                if (trackOutput.playState == AudioTrack.PLAYSTATE_PLAYING) trackOutput.pause()
                                playing.set(false)
                                publishPosition(trackOutput, outputRate)
                                delay(20L)
                            }
                            if (closed) break
                            if (trackOutput.playState != AudioTrack.PLAYSTATE_PLAYING) trackOutput.play()
                            stage = "alac_decode"
                            val packet = fetched.bytes.copyOfRange(range.first, range.last + 1)
                            val pcm = AlacDecoder.decode(decoder, packet)
                            if (pcm.isEmpty()) throw ApplePreparationException("alac_decode", "empty_pcm", "ALAC 分片解码失败")
                            val offset = if (skipFrames > 0L) {
                                val discard = (skipFrames * bytesPerFrame).coerceAtMost(pcm.size.toLong()).toInt()
                                skipFrames -= discard / bytesPerFrame
                                discard
                            } else 0
                            stage = "audio_output"
                            val frames = (pcm.size - offset) / bytesPerFrame
                            if (frames > 0) {
                                writeFully(trackOutput, pcm, offset, pcm.size - offset, encoding, bits, channels, sampleRate, outputRate)
                                if (firstPcm) {
                                    firstPcm = false
                                    onFirstPcm(segmentIndex, frames)
                                }
                                if (!playing.get()) playing.set(true)
                            }
                            publishPosition(trackOutput, outputRate)
                        }
                        if (closed) break
                        stage = "fragment"
                        fetched = pending?.await() ?: break
                        pending = null
                        segmentIndex = nextIndex
                        skipFrames = 0L
                    }
                } finally {
                    pending?.cancel()
                }
            }
            if (!closed) onFinished()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed) {
                val failure = error as? ApplePreparationException
                onError(failure?.stage ?: stage, failure?.reason ?: error.javaClass.simpleName)
            }
        } finally {
            playing.set(false)
            output?.let { trackOutput ->
                runCatching { trackOutput.pause() }
                runCatching { trackOutput.flush() }
                runCatching { trackOutput.release() }
            }
            if (decoder != 0L) AlacDecoder.close(decoder)
        }
    }

    private fun publishPosition(output: AudioTrack, sampleRate: Int) {
        val head = output.playbackHeadPosition.toLong() and 0xffffffffL
        if (head < lastPlaybackHead) playbackFramesBase += 1L shl 32
        lastPlaybackHead = head
        val rate = sampleRate.coerceAtLeast(1)
        reportedPositionMs.set(
            (startPositionMs + (playbackFramesBase + head) * 1_000L / rate)
                .coerceAtMost(track.playlist.totalDurationMs.coerceAtLeast(startPositionMs)),
        )
    }

    private fun writeFully(
        output: AudioTrack,
        pcm: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
        bits: Int,
        channels: Int,
        nativeRate: Int,
        outputRate: Int,
    ) {
        val frameBytes = channels * if (bits == 16) 2 else 4
        val frames = length / frameBytes
        if (frames <= 0) return
        val input = ByteBuffer.wrap(pcm, offset, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(frames * channels)
        if (bits == 16) {
            for (index in samples.indices) samples[index] = input.short / 32768f
        } else {
            for (index in samples.indices) samples[index] = input.float
        }
        val played = if (nativeRate == outputRate) samples else resample(samples, channels, nativeRate, outputRate)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val buffer = ByteBuffer.allocate(played.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            played.forEach(buffer::putFloat)
            buffer.flip()
            while (buffer.hasRemaining()) {
                val count = output.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
                if (count <= 0) throw ApplePreparationException("audio_output", "write_$count", "设备无法输出 ALAC 音频")
            }
            return
        }
        val converted = ByteArray(played.size * 2)
        var index = 0
        for (sample in played) {
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            converted[index] = value.toByte()
            converted[index + 1] = (value shr 8).toByte()
            index += 2
        }
        writePcm16(output, converted, 0, converted.size)
    }

    private fun writePcm16(output: AudioTrack, pcm: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val count = output.write(pcm, offset + written, length - written, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) throw ApplePreparationException("audio_output", "write_$count", "设备无法输出 ALAC 音频")
            written += count
        }
    }

    private fun resample(input: FloatArray, channels: Int, fromRate: Int, toRate: Int): FloatArray {
        val inFrames = input.size / channels
        if (inFrames <= 1 || fromRate == toRate) return input
        val outFrames = ((inFrames.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outFrames * channels)
        for (frame in 0 until outFrames) {
            val source = frame.toDouble() * fromRate / toRate
            val left = source.toInt().coerceIn(0, inFrames - 1)
            val right = (left + 1).coerceAtMost(inFrames - 1)
            val fraction = (source - left).toFloat()
            for (channel in 0 until channels) {
                val start = input[left * channels + channel]
                val end = input[right * channels + channel]
                out[frame * channels + channel] = start + (end - start) * fraction
            }
        }
        return out
    }

    private fun List<AppleNativeSegment>.indexForPosition(positionMs: Long): Int {
        var elapsed = 0L
        forEachIndexed { index, segment ->
            if (positionMs < elapsed + segment.durationMs) return index
            elapsed += segment.durationMs
        }
        return lastIndex.coerceAtLeast(0)
    }
}

private class OpenedAlacTrack(val track: AudioTrack, val encoding: Int, val sampleRate: Int)

private fun openAlacTrack(nativeRate: Int, channelMask: Int, bits: Int, rateCap: Int = Int.MAX_VALUE): OpenedAlacTrack {
    val nativeEncodings = if (bits == 16) intArrayOf(AudioFormat.ENCODING_PCM_16BIT) else intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
    val attempts = buildList {
        if (nativeRate in 1..rateCap) for (encoding in nativeEncodings) add(nativeRate to encoding)
        for (rate in intArrayOf(48_000, 44_100, rateCap)) if (rate in 1..rateCap && rate != nativeRate) add(rate to AudioFormat.ENCODING_PCM_16BIT)
    }
    for ((rate, encoding) in attempts) {
        if (rate <= 0) continue
        val minBuffer = AudioTrack.getMinBufferSize(rate, channelMask, encoding)
        if (minBuffer <= 0) continue
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(32 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: IllegalArgumentException) {
            continue
        }
        if (track.state == AudioTrack.STATE_INITIALIZED) return OpenedAlacTrack(track, encoding, rate)
        track.release()
    }
    throw ApplePreparationException("alac_buffer", "buffer_unavailable", "设备不支持此 ALAC 输出格式")
}
