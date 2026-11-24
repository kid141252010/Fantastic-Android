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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultDataSource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class ApplePlaybackProfile(val network: String, val quality: String, val spatial: String)

internal fun applePlaybackNetwork(context: Context): String? {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val cellular = manager.getNetworkCapabilities(network)
        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    return if (cellular) "cellular" else "wifi"
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
                    activeCacheSource = null
                    _state.value = _state.value.copy(
                        buffering = false,
                        isPlaying = false,
                        playWhenReady = false,
                        error = error.message?.take(320) ?: "Apple 播放失败",
                    )
                }
            }
        })
    }

    internal fun prewarm(server: String) = Unit

    fun position(): Long {
        val output = alacOutput
        if (output != null) return output.positionMs()
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
        if (normalized == null) {
            publishError("服务器地址无效")
            return@onMain
        }
        val previous = _state.value
        this.songs = if (quality == null || selected.quality == quality) songs.toList()
        else songs.toMutableList().apply { this[index] = selected.copy(quality = quality) }
        this.server = normalized
        this.storefront = storefront.lowercase().takeIf { it.matches(STORE_FRONT) } ?: "us"
        this.profile = applePlaybackProfile(appContext, prefs)
        this.userRequestedQuality = quality ?: selected.quality
        this.occurrence = index
        this.lastPositionMs = startPositionMs.coerceAtLeast(0L)
        this.requestSerial++
        val serial = requestSerial
        this.startedAt = SystemClock.elapsedRealtime()
        prepareJob?.cancel()
        stopOutput()
        activeCacheSource = null
        currentNative = null
        player.stop()
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
            bufferedPositionMs = 0L,
            seekGeneration = previous.seekGeneration + 1,
        )
        if (playWhenReady) beginPrepare(serial, selected, startPositionMs)
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
        val output = alacOutput
        if (output != null) {
            val serial = ++requestSerial
            prepareJob?.cancel()
            stopOutput()
            beginPrepare(serial, songs.getOrNull(occurrence) ?: return@onMain, target)
        } else {
            player.seekTo(target)
        }
        _state.value = state.copy(ended = false, seekGeneration = state.seekGeneration + 1)
    }

    fun skipTo(index: Int): Boolean = false

    fun setPlaying(playing: Boolean) = onMain {
        if (!playing) {
            pause()
            return@onMain
        }
        val song = songs.getOrNull(occurrence) ?: return@onMain
        if (alacOutput != null || player.mediaItemCount > 0) {
            alacOutput?.resume()
            player.play()
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
        prepareJob?.cancel()
        stopOutput()
        player.stop()
        activeCacheSource = null
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
        val updated = song.copy(quality = group)
        songs = songs.toMutableList().apply { this[occurrence] = updated }
        userRequestedQuality = group
        val serial = ++requestSerial
        prepareJob?.cancel()
        stopOutput()
        player.stop()
        activeCacheSource = null
        _state.value = _state.value.copy(
            queue = songs.map { it.track },
            buffering = true,
            isPlaying = false,
            playWhenReady = true,
            quality = null,
            qualityLabel = null,
            audioDetails = null,
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
        prepareJob = scope.launch(Dispatchers.IO) {
            try {
                val track = decryptor.resolve(
                    server = server,
                    adamId = song.track.id,
                    requestedQuality = userRequestedQuality,
                    profile = profile,
                    explicit = userRequestedQuality != null,
                )
                if (serial != requestSerial || closed.get()) return@launch
                onMain {
                    if (serial != requestSerial || closed.get()) return@onMain
                    currentNative = track
                    if (track.widevine || !track.variant.codecs.contains("alac", ignoreCase = true)) {
                        startMedia3(serial, song, track, startPositionMs)
                    } else {
                        startAlac(serial, song, track, startPositionMs)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (serial == requestSerial && !closed.get()) {
                    onMain { if (serial == requestSerial) publishError(error.message ?: "Apple 播放失败") }
                }
            }
        }
    }

    private fun startMedia3(serial: Long, song: QueuedSong, track: AppleNativeTrack, startPositionMs: Long) {
        val itemUri = if (track.widevine) track.mediaUrl.toString() else "apple-native://playlist"
        val itemBuilder = MediaItem.Builder()
            .setMediaId("apple:${song.track.id}")
            .setUri(itemUri)
        val sourceFactory = if (track.widevine) {
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(30_000)
            val callback = AppleNativeWidevine(api, server, song.track.id).also {
                it.setKeyUris(track.playlist.segments.mapNotNull { segment -> segment.keyUri }.distinct())
            }
            val drm = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(AppleNativeWidevine.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(callback)
            HlsMediaSource.Factory(DefaultDataSource.Factory(appContext, http))
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
        player.setMediaSource(source)
        player.prepare()
        player.seekTo(startPositionMs)
        player.playWhenReady = true
        updateFormatState(song, track)
        schedulePoll()
    }


    private fun startAlac(serial: Long, song: QueuedSong, track: AppleNativeTrack, startPositionMs: Long) {
        updateFormatState(song, track)
        val output = AlacOutput(
            track = track,
            decryptor = decryptor,
            startPositionMs = startPositionMs,
            onFinished = { onMain { if (serial == requestSerial) markEnded() } },
            onError = { error -> onMain { if (serial == requestSerial) publishError(error.message ?: "Apple ALAC 播放失败") } },
        )
        alacOutput = output
        output.start()
        schedulePoll()
    }

    private fun updateFormatState(song: QueuedSong, track: AppleNativeTrack) {
        val old = _state.value
        val group = track.variant.groupId
        val quality = codecGroup(group)
        val duration = track.playlist.totalDurationMs.takeIf { it > 0 } ?: song.track.durationMs
        val label = qualityLabel(track)
        val details = reportedAudioDetails(
            codec = track.variant.codecs,
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
            buffering = true,
            error = null,
        )
    }

    private fun updateFromPlayer() {
        if (closed.get() || alacOutput != null || currentNative == null) return
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
        if (ended) activeCacheSource = null else if (playing) publishCache("mse")
        if (ended) stopPoller() else schedulePoll()
    }

    private fun schedulePoll() {
        if (poller != null || closed.get()) return
        val task = object : Runnable {
            override fun run() {
                if (poller !== this) return
                poller = null
                if (closed.get()) return
                if (server != prefs.baseUrl && SourceStatus.normalizeBaseUrl(prefs.baseUrl) != server) {
                    stopOutput()
                    player.stop()
                    publishError("服务器已更改，点按播放重新连接")
                    return
                }
                if (alacOutput != null) {
                    lastPositionMs = alacOutput?.positionMs() ?: lastPositionMs
                    val old = _state.value
                    val playing = alacOutput?.isPlaying() == true
                    _state.value = old.copy(
                        isPlaying = playing,
                        playWhenReady = playing || old.playWhenReady,
                        buffering = !playing && !old.ended,
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
            Log.i("FantasticPlayback", "native-apple id=${song.track.id} group=${source.group} codecs=${source.codecs} mode=$mode")
        }
    }

    private fun markEnded() {
        activeCacheSource = null
        stopPoller()
        _state.value = _state.value.copy(isPlaying = false, buffering = false, ended = true, playWhenReady = true)
    }

    private fun publishError(message: String) {
        activeCacheSource = null
        stopOutput()
        _state.value = _state.value.copy(
            connected = true,
            isPlaying = false,
            playWhenReady = false,
            buffering = false,
            error = message,
        )
    }

    private fun qualityLabel(track: AppleNativeTrack): String {
        val group = track.variant.groupId.lowercase()
        val codecs = track.variant.codecs.lowercase()
        return when {
            "alac" in codecs || "alac" in group -> if (track.variant.sampleRate > 48_000) "Hi-Res 无损" else "无损"
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
            "alac" in name -> "alac"
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
    private val onFinished: () -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var paused = false
    @Volatile private var closed = false
    @Volatile private var audioTrack: AudioTrack? = null
    private var job: Job? = null
    private var basePositionMs = startPositionMs
    private var sampleRate = track.variant.sampleRate

    fun start() {
        job = scope.launch { run() }
    }

    fun pause() {
        paused = true
        audioTrack?.pause()
    }

    fun resume() {
        paused = false
        audioTrack?.play()
    }

    fun isPlaying(): Boolean = !paused && !closed && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun positionMs(): Long {
        val rate = sampleRate.coerceAtLeast(1)
        val frames = audioTrack?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
        return (basePositionMs + frames * 1_000L / rate).coerceAtMost(track.playlist.totalDurationMs)
    }

    override fun close() {
        closed = true
        job?.cancel()
        audioTrack?.let { output ->
            runCatching { output.pause() }
            runCatching { output.flush() }
            runCatching { output.release() }
        }
        audioTrack = null
        scope.cancel()
    }

    private suspend fun run() {
        val cookie = track.alacCookie ?: throw IOException("Apple ALAC 配置缺失")
        val decoder = AlacDecoder.open(cookie.copyOfRange(4, cookie.size))
        if (decoder == 0L) throw IOException("Apple ALAC 解码器初始化失败")
        try {
            sampleRate = AlacDecoder.sampleRate(decoder).takeIf { it > 0 } ?: track.variant.sampleRate
            val channels = AlacDecoder.channels(decoder).takeIf { it > 0 } ?: track.alacChannels
            val bits = AlacDecoder.bits(decoder).takeIf { it > 0 } ?: track.alacBits
            val encoding = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_FLOAT
            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                else -> throw IOException("Apple ALAC 声道数不可播放")
            }
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBuffer == AudioTrack.ERROR_BAD_VALUE) throw IOException("Apple ALAC 音频格式不可播放")
            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(32 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = output
            output.play()
            var segmentIndex = track.playlist.segments.indexForPosition(startPositionMs)
            var segmentOffsetMs = track.playlist.segments.take(segmentIndex).sumOf { it.durationMs }
            basePositionMs = segmentOffsetMs
            var pending = coroutineScope {
                async { decryptor.fetchSegment(track, track.playlist.segments[segmentIndex]) }
            }
            while (!closed && segmentIndex < track.playlist.segments.size) {
                val fetched = pending.await()
                val nextIndex = segmentIndex + 1
                pending = if (nextIndex < track.playlist.segments.size) {
                    coroutineScope { async { decryptor.fetchSegment(track, track.playlist.segments[nextIndex]) } }
                } else pending
                var skipFrames = if (segmentIndex == track.playlist.segments.indexForPosition(startPositionMs)) {
                    ((startPositionMs - segmentOffsetMs) * sampleRate / 1_000L).coerceAtLeast(0L)
                } else 0L
                for (range in fetched.sampleRanges) {
                    if (closed) break
                    val packet = fetched.bytes.copyOfRange(range.first, range.last + 1)
                    val repaired = decryptor.repairAlacPacket(packet, track.alacFrameLength, channels, bits)
                    val pcm = AlacDecoder.decode(decoder, repaired)
                    if (pcm.isEmpty()) throw IOException("Apple ALAC 分片解码失败")
                    val bytesPerFrame = channels * if (bits == 16) 2 else 4
                    if (skipFrames > 0L) {
                        val discard = (skipFrames * bytesPerFrame).coerceAtMost(pcm.size.toLong()).toInt()
                        skipFrames -= discard / bytesPerFrame
                        if (discard >= pcm.size) continue
                        output.write(pcm, discard, pcm.size - discard)
                    } else {
                        while (paused && !closed) delay(20L)
                        output.write(pcm, 0, pcm.size)
                    }
                }
                segmentOffsetMs += fetched.durationMs
                basePositionMs = startPositionMs.takeIf { segmentIndex == track.playlist.segments.indexForPosition(it) } ?: basePositionMs
                segmentIndex = nextIndex
            }
            if (!closed) onFinished()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed) onError(error)
        } finally {
            AlacDecoder.close(decoder)
            audioTrack?.let { runCatching { it.release() } }
            audioTrack = null
        }
    }

    private fun List<AppleNativeSegment>.indexForPosition(positionMs: Long): Int {
        var elapsed = 0L
        forEachIndexed { index, segment ->
            if (positionMs < elapsed + segment.durationMs) return index
            elapsed += segment.durationMs
        }
        return (lastIndex).coerceAtLeast(0)
    }
}
