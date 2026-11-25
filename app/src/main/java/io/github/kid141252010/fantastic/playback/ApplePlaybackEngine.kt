package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.reportedAudioDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class ApplePlaybackProfile(val network: String, val quality: String, val spatial: String)

internal fun String.appleLogToken(): String = takeIf { length in 1..80 && APPLE_LOG_TOKEN.matches(this) } ?: "-"

private val APPLE_LOG_TOKEN = Regex("[A-Za-z0-9.,_-]+")

internal fun applePlaybackNetwork(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val cellular = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
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

@UnstableApi
internal class ApplePlaybackEngine(context: Context, private val prefs: Prefs, private val api: FantasticApi) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _cacheSource = MutableStateFlow<AppleCacheSource?>(null)
    internal val cacheSource: StateFlow<AppleCacheSource?> = _cacheSource.asStateFlow()
    internal var activeCacheSource: AppleCacheSource?
        get() = _cacheSource.value
        private set(value) { _cacheSource.value = value }
    internal var completedLoops: Long = 0L
        private set

    @Volatile private var closed = false
    @Volatile private var requestSerial = 0L
    @Volatile private var activeSerial = -1L
    private var songs = emptyList<QueuedSong>()
    private var occurrence = -1
    private var mediaPlayer: ExoPlayer? = null
    private var requestJob: Job? = null
    private var serverMonitorJob: Job? = null
    private var nativeDecrypt: AppleNativeDecrypt? = null
    private var activeTrack: AppleResolvedTrack? = null
    private var activeProfile = ApplePlaybackProfile("wifi", "hires", "auto")
    private var activeServer = ""
    private var configuredServer = ""
    private var activeStorefront = "us"
    private var requestedQuality: String? = null
    private var pendingPositionMs = 0L
    private var timelineInstalled = false
    private var pendingSeekBeforeTimeline = false
    @Volatile private var alacOutput: AudioTrack? = null
    @Volatile private var alacHandle = 0L
    @Volatile private var alacSampleRate = 0
    @Volatile private var alacChannels = 0
    @Volatile private var alacStartFrame = 0L
    @Volatile private var alacStartPositionMs = 0L
    @Volatile private var alacSubmittedSerial = -1L
    private var loggedOutputSerial = -1L

    internal val currentUid: Int get() = songs.getOrNull(occurrence)?.uid ?: -1

    internal fun prewarm(server: String) {
        if (!closed) SourceStatus.normalizeBaseUrl(server)
    }

    fun position(): Long {
        val track = activeTrack ?: return pendingPositionMs
        if (activeSerial != requestSerial) return pendingPositionMs
        val currentSong = songs.getOrNull(occurrence) ?: return pendingPositionMs
        if (_state.value.queueIndex != occurrence || _state.value.mediaId != "apple:${currentSong.track.id}") return pendingPositionMs
        val duration = track.media.durationMs.takeIf { it > 0L }
            ?: songs.getOrNull(occurrence)?.track?.durationMs?.takeIf { it > 0L }
        val position = if (track.variant.groupId.contains("alac", true)) {
            val output = alacOutput ?: return pendingPositionMs
            val rate = alacSampleRate.takeIf { it > 0 } ?: return pendingPositionMs
            val frames = output.playbackHeadPosition.toLong() and 0xffff_ffffL
            alacStartPositionMs + (frames - alacStartFrame).coerceAtLeast(0L) * 1000L / rate
        } else {
            if (!timelineInstalled) return pendingPositionMs
            (mediaPlayer?.currentPosition ?: pendingPositionMs).coerceAtLeast(0L)
        }
        return position.coerceAtMost(duration ?: Long.MAX_VALUE)
    }

    fun playQueue(
        songs: List<QueuedSong>, index: Int, startPositionMs: Long = 0L,
        playWhenReady: Boolean = true, quality: String? = null,
        server: String = prefs.baseUrl, storefront: String = prefs.storefront,
    ) = onMain {
        if (closed) return@onMain
        val selected = songs.getOrNull(index) ?: return@onMain
        require(songs.all { it.track.source == Source.Apple })
        val previous = _state.value
        val serial = ++requestSerial
        cancelActive(releasePlayer = false)
        this.songs = if (quality == null || selected.quality == quality) songs.toList()
            else songs.toMutableList().apply { this[index] = selected.copy(quality = quality) }
        occurrence = index
        requestedQuality = quality ?: selected.quality
        activeStorefront = storefront.lowercase().takeIf(STORE_FRONT::matches) ?: "us"
        activeServer = server
        configuredServer = prefs.baseUrl
        pendingPositionMs = startPositionMs.coerceIn(0L, selected.track.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        timelineInstalled = false
        pendingSeekBeforeTimeline = false
        loggedOutputSerial = -1L
        _state.value = previous.copy(
            connected = false,
            mediaId = "apple:${selected.track.id}",
            title = selected.track.title,
            artist = selected.track.artist,
            artworkUri = selected.track.artwork(600),
            isPlaying = false,
            buffering = playWhenReady,
            durationMs = selected.track.durationMs,
            hasNext = index < this.songs.lastIndex || previous.repeatMode == Player.REPEAT_MODE_ALL,
            hasPrevious = index > 0,
            error = null,
            seekGeneration = previous.seekGeneration + 1,
            queue = this.songs.map { it.track },
            queueIndex = index,
            currentTrack = selected.track,
            quality = null,
            playWhenReady = playWhenReady,
            ended = false,
            bufferedPositionMs = pendingPositionMs,
            qualityLabel = null,
            audioDetails = null,
        )
        if (!playWhenReady) return@onMain
        val normalized = SourceStatus.normalizeBaseUrl(server) ?: run {
            publishError("服务器地址无效")
            return@onMain
        }
        activeServer = normalized
        activeProfile = applePlaybackProfile(appContext, prefs)
        val profile = activeProfile
        val choice = requestedQuality ?: profile.quality
        requestJob = scope.launch {
            try {
                val decrypt = AppleNativeDecrypt(appContext, api, normalized)
                val resolved = decrypt.prepare(selected.track.id, choice, profile)
                if (closed || serial != requestSerial) return@launch
                nativeDecrypt = decrypt
                activeTrack = resolved
                activeSerial = serial
                val duration = resolved.media.durationMs.takeIf { it > 0L } ?: selected.track.durationMs
                _state.value = _state.value.copy(connected = true, buffering = true, durationMs = duration)
                startServerMonitor(serial)
                if (resolved.variant.groupId.contains("alac", true)) {
                    releaseMediaPlayer()
                    playAlac(resolved, decrypt, serial, pendingPositionMs)
                } else {
                    startMedia3(resolved, decrypt, serial, normalized)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!closed && serial == requestSerial) publishError(error.message?.takeIf(String::isNotBlank) ?: "Apple 播放失败")
            }
        }
    }

    fun replaceUpcoming(tail: List<QueuedSong>) = onMain {
        if (occurrence !in songs.indices) return@onMain
        songs = songs.take(occurrence + 1) + tail
        _state.value = _state.value.copy(
            queue = songs.map { it.track },
            hasNext = tail.isNotEmpty() || _state.value.repeatMode == Player.REPEAT_MODE_ALL,
        )
    }

    fun pause() = onMain {
        val previous = _state.value
        if (!previous.playWhenReady) return@onMain
        pendingPositionMs = position()
        activeCacheSource = null
        mediaPlayer?.pause()
        runCatching { alacOutput?.pause() }
        _state.value = previous.copy(playWhenReady = false, isPlaying = false, buffering = false,
            bufferedPositionMs = pendingPositionMs)
    }

    fun seekTo(ms: Long) = onMain {
        val state = _state.value
        if (state.queueIndex !in state.queue.indices) return@onMain
        val duration = state.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        pendingPositionMs = ms.coerceIn(0L, duration)
        activeCacheSource = null
        _state.value = state.copy(ended = false, seekGeneration = state.seekGeneration + 1,
            bufferedPositionMs = pendingPositionMs)
        val track = activeTrack ?: return@onMain
        if (activeSerial != requestSerial) return@onMain
        if (track.variant.groupId.contains("alac", true)) {
            val serial = requestSerial
            requestJob?.cancel()
            releaseAlacOutput()
            requestJob = scope.launch {
                try {
                    playAlac(track, nativeDecrypt ?: return@launch, serial, pendingPositionMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isCurrent(serial)) publishError(error.message?.takeIf(String::isNotBlank) ?: "ALAC 播放失败")
                }
            }
        } else {
            val player = mediaPlayer
            if (player != null && timelineInstalled) player.seekTo(pendingPositionMs)
            else pendingSeekBeforeTimeline = player != null
        }
    }

    /** Queue advancement is owned by FantasticSessionPlayer; never consume its prepared UP transition. */
    fun skipTo(index: Int): Boolean = false

    fun setPlaying(playing: Boolean) = onMain {
        val state = _state.value
        if (!playing) {
            pause()
        } else if (state.queueIndex in state.queue.indices && !state.playWhenReady) {
            if (state.ended || state.error != null) {
                val scopeServer = activeServer.takeIf { it.isNotBlank() } ?: prefs.baseUrl
                playQueue(songs, state.queueIndex, if (state.ended) 0L else pendingPositionMs,
                    true, requestedQuality, scopeServer, activeStorefront)
            } else {
                _state.value = state.copy(playWhenReady = true, ended = false)
                mediaPlayer?.play()
                val output = alacOutput
                if (output != null) {
                    output.play()
                    if (alacSubmittedSerial == requestSerial) publishOutputStarted(requestSerial, "alac")
                }
                if (mediaPlayer == null && output == null && requestJob == null) {
                    playQueue(songs, state.queueIndex, pendingPositionMs, true, requestedQuality, prefs.baseUrl, prefs.storefront)
                }
            }
        }
    }

    fun stop() = onMain {
        val repeatMode = _state.value.repeatMode
        ++requestSerial
        cancelActive(releasePlayer = true)
        songs = emptyList()
        occurrence = -1
        requestedQuality = null
        pendingPositionMs = 0L
        _state.value = PlayerState(connected = false, repeatMode = repeatMode)
    }

    fun setRepeatMode(mode: Int) = onMain {
        if (_state.value.repeatMode != mode) _state.value = _state.value.copy(repeatMode = mode)
    }

    fun selectQuality(group: String) = onMain {
        if (group !in CACHE_QUALITIES || occurrence !in songs.indices) return@onMain
        val resumeAt = position()
        val next = songs.toMutableList().apply { this[occurrence] = this[occurrence].copy(quality = group) }
        val server = activeServer.takeIf(String::isNotBlank) ?: prefs.baseUrl
        playQueue(next, occurrence, resumeAt, _state.value.playWhenReady, group, server, activeStorefront)
    }

    fun close() = onMain {
        if (closed) return@onMain
        closed = true
        ++requestSerial
        cancelActive(releasePlayer = true)
        songs = emptyList()
        occurrence = -1
        _state.value = PlayerState()
        scope.cancel()
    }

    private fun startMedia3(track: AppleResolvedTrack, decrypt: AppleNativeDecrypt, serial: Long, server: String) {
        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId("apple:${songs.getOrNull(occurrence)?.track?.id.orEmpty()}")
            .setUri(Uri.parse("apple-native://$serial/playlist.m3u8"))
        val drmManager = if (track.variant.widevine) appleWidevineSessionManager(api, server, track) else null
        if (drmManager != null) {
            mediaItemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri("$server/mv/license").setMultiSession(false).build())
        }
        val sourceFactory = HlsMediaSource.Factory(DataSource.Factory {
            AppleNativeDataSource(appContext, decrypt, track, serial)
        }).setAllowChunklessPreparation(false)
        if (drmManager != null) sourceFactory.setDrmSessionManagerProvider { drmManager }
        val source = sourceFactory.createMediaSource(mediaItemBuilder.build())
        val player = ensureMediaPlayer()
        timelineInstalled = false
        pendingSeekBeforeTimeline = false
        player.setMediaSource(source, pendingPositionMs)
        player.prepare()
        player.playWhenReady = true
        _state.value = _state.value.copy(connected = true, buffering = true, playWhenReady = true)
    }

    private fun ensureMediaPlayer(): ExoPlayer {
        mediaPlayer?.let { return it }
        val player = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(mediaListener)
        mediaPlayer = player
        return player
    }

    private val mediaListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = onMain {
            val serial = activeSerial
            if (!isCurrent(serial)) return@onMain
            val player = mediaPlayer ?: return@onMain
            if (isPlaying) publishOutputStarted(serial, "mse") else activeCacheSource = null
            _state.value = _state.value.copy(isPlaying = isPlaying, playWhenReady = player.playWhenReady,
                buffering = player.playbackState == Player.STATE_BUFFERING)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = onMain {
            val serial = activeSerial
            if (isCurrent(serial)) _state.value = _state.value.copy(playWhenReady = playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) = onMain {
            val serial = activeSerial
            if (!isCurrent(serial)) return@onMain
            val player = mediaPlayer ?: return@onMain
            val ended = playbackState == Player.STATE_ENDED
            if (ended) activeCacheSource = null
            _state.value = _state.value.copy(
                connected = true,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                buffering = playbackState == Player.STATE_BUFFERING,
                ended = ended,
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            )
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = onMain {
            val serial = activeSerial
            val player = mediaPlayer ?: return@onMain
            if (!isCurrent(serial) || timeline.isEmpty) return@onMain
            timelineInstalled = true
            if (pendingSeekBeforeTimeline) {
                pendingSeekBeforeTimeline = false
                player.seekTo(pendingPositionMs)
            }
            pendingPositionMs = player.currentPosition.coerceAtLeast(0L)
        }

        override fun onPlayerError(error: PlaybackException) = onMain {
            val serial = activeSerial
            if (!isCurrent(serial)) return@onMain
            val message = if (activeTrack?.variant?.widevine == true) AppleWidevineCallback.LICENSE_REJECTED
                else error.message?.takeIf(String::isNotBlank) ?: "Apple 播放失败"
            publishError(message)
        }
    }

    private suspend fun playAlac(track: AppleResolvedTrack, decrypt: AppleNativeDecrypt, serial: Long, startAtMs: Long) {
        val cookie = track.alacCookie ?: throw IOException("该曲目没有所选音质")
        val handle = AlacDecoder.openSafe(cookie)
        if (handle == 0L) throw IOException("该曲目没有所选音质")
        val channels = AlacDecoder.channels(handle)
        val sampleRate = AlacDecoder.sampleRate(handle)
        val bits = AlacDecoder.bits(handle)
        if (channels !in 1..8 || sampleRate <= 0 || bits !in setOf(16, 20, 24, 32)) {
            AlacDecoder.closeSafe(handle)
            throw IOException("ALAC 解码器初始化失败")
        }
        val built = buildAlacOutput(sampleRate, channels, bits)
            ?: run { AlacDecoder.closeSafe(handle); throw IOException("ALAC AudioTrack 创建失败") }
        val output = built.first
        val floatOutput = built.second
        if (!isCurrent(serial)) {
            runCatching { output.release() }
            AlacDecoder.closeSafe(handle)
            throw CancellationException()
        }
        alacOutput = output
        alacHandle = handle
        alacSampleRate = sampleRate
        alacChannels = channels
        alacStartFrame = output.playbackHeadPosition.toLong() and 0xffff_ffffL
        alacSubmittedSerial = -1L
        val bytesPerOutputSample = if (floatOutput) 4 else 2
        val maximumBlock = track.alacConfig?.maxBlock ?: 65535
        val direct = ByteBuffer.allocateDirect(maximumBlock * channels * bytesPerOutputSample).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (!_state.value.playWhenReady) awaitPlayWhenReady(serial)
            output.play()
            val segments = track.media.segments
            val startIndex = segments.indexOfLast { it.startMs <= startAtMs }.coerceAtLeast(0)
            var skipFrames = ((startAtMs - segments[startIndex].startMs).coerceAtLeast(0L) * sampleRate / 1000L)
            supervisorScope {
                var upcoming: Deferred<ByteArray>? = null
                try {
                    var index = startIndex
                    var current = decrypt.fetchSegment(track, index)
                    while (index < segments.size) {
                        if (!isCurrent(serial)) throw CancellationException()
                        if (upcoming == null && index + 1 < segments.size) {
                            val nextIndex = index + 1
                            upcoming = async(Dispatchers.IO) { decrypt.fetchSegment(track, nextIndex) }
                        }
                        val ranges = decrypt.sampleRanges(current)
                        for (range in ranges) {
                            awaitPlayWhenReady(serial)
                            val packet = current.copyOfRange(range.start, range.end)
                            val pcm = withContext(Dispatchers.Default) { AlacDecoder.decodeSafe(handle, packet) }
                            if (pcm.isEmpty()) throw IOException("ALAC 分片解码失败")
                            val frameBytes = channels * if (bits == 16) 2 else 4
                            val frames = pcm.size / frameBytes
                            if (frames <= 0) throw IOException("ALAC 分片解码失败")
                            val skipped = minOf(skipFrames, frames.toLong()).toInt()
                            skipFrames -= skipped
                            if (skipped < frames) {
                                val written = withContext(Dispatchers.IO) {
                                    writeAlacPcm(output, pcm, skipped, frames - skipped, channels, bits, floatOutput, direct)
                                }
                                if (written > 0 && alacSubmittedSerial != serial) {
                                    alacSubmittedSerial = serial
                                    withContext(Dispatchers.Main.immediate) {
                                        if (isCurrent(serial)) publishOutputStarted(serial, "alac")
                                    }
                                }
                            }
                        }
                        val next = upcoming ?: break
                        current = next.await()
                        upcoming = null
                        index++
                    }
                } finally {
                    upcoming?.cancel()
                }
            }
            if (isCurrent(serial)) onMain {
                activeCacheSource = null
                _state.value = _state.value.copy(isPlaying = false, buffering = false, ended = true,
                    bufferedPositionMs = _state.value.durationMs)
            }
        } finally {
            if (alacOutput === output) alacOutput = null
            if (alacHandle == handle) alacHandle = 0L
            alacSampleRate = 0
            alacChannels = 0
            runCatching { output.stop() }
            runCatching { output.release() }
            AlacDecoder.closeSafe(handle)
        }
    }

    private suspend fun awaitPlayWhenReady(serial: Long) {
        while (true) {
            if (!isCurrent(serial)) throw CancellationException()
            if (_state.value.playWhenReady) return
            delay(40L)
        }
    }

    private fun buildAlacOutput(sampleRate: Int, channels: Int, bits: Int): Pair<AudioTrack, Boolean>? {
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> return null
        }
        fun build(encoding: Int, floating: Boolean): Pair<AudioTrack, Boolean>? {
            val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minimum == AudioTrack.ERROR_BAD_VALUE || minimum == AudioTrack.ERROR || minimum <= 0) return null
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate)
                        .setChannelMask(channelMask).build())
                    .setBufferSizeInBytes(minimum.coerceAtLeast(32 * 1024))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return null
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return null
            }
            return track to floating
        }
        if (bits > 16) build(AudioFormat.ENCODING_PCM_FLOAT, true)?.let { return it }
        return build(AudioFormat.ENCODING_PCM_16BIT, false)
    }

    private fun writeAlacPcm(
        output: AudioTrack,
        pcm: ByteArray,
        skipFrames: Int,
        frames: Int,
        channels: Int,
        bits: Int,
        floatOutput: Boolean,
        direct: ByteBuffer,
    ): Int {
        direct.clear()
        if (floatOutput) {
            val start = skipFrames * channels * 4
            direct.put(pcm, start, frames * channels * 4)
        } else if (bits == 16) {
            val start = skipFrames * channels * 2
            direct.put(pcm, start, frames * channels * 2)
        } else {
            val source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            source.position(skipFrames * channels * 4)
            repeat(frames * channels) {
                val sample = (source.float.coerceIn(-1.0f, 1.0f) * 32768.0f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                direct.putShort(sample.toShort())
            }
        }
        direct.flip()
        var writtenBytes = 0
        while (direct.hasRemaining()) {
            val before = direct.position()
            val result = output.write(direct, direct.remaining(), AudioTrack.WRITE_BLOCKING)
            if (result < 0) throw IOException("ALAC AudioTrack 写入失败")
            val advanced = direct.position() - before
            if (advanced <= 0) throw IOException("ALAC AudioTrack 未提交 PCM")
            writtenBytes += advanced
        }
        return writtenBytes
    }

    private fun publishOutputStarted(serial: Long, mode: String) {
        if (!isCurrent(serial)) return
        val track = activeTrack ?: return
        val song = songs.getOrNull(occurrence) ?: return
        if (_state.value.queueIndex != occurrence || _state.value.mediaId != "apple:${song.track.id}") return
        val variant = track.variant
        val group = variant.groupId
        val quality = codecGroup(group)
        val codecs = variant.codecs
        val reportedCodec = if (group.contains("alac", true) && !codecs.contains("alac", true)) "alac" else codecs
        val duration = track.media.durationMs.takeIf { it > 0L } ?: song.track.durationMs
        val details = reportedAudioDetails(
            codec = reportedCodec,
            sampleRate = variant.sampleRate,
            bitDepth = variant.bitDepth,
            channels = variant.channels,
            bitrate = variant.bandwidth,
            bitrateLabel = "变体带宽",
        )
        val label = qualityLabel(group, variant.sampleRate)
        _state.value = _state.value.copy(
            connected = true,
            mediaId = "apple:${song.track.id}",
            title = song.track.title,
            artist = song.track.artist,
            artworkUri = song.track.artwork(600),
            isPlaying = true,
            playWhenReady = true,
            buffering = false,
            ended = false,
            durationMs = duration,
            quality = quality,
            qualityLabel = label,
            audioDetails = details,
        )
        val cache = if (duration > 0L && quality != null && quality in CACHE_QUALITIES && CACHE_GROUP.matches(group) && codecs.isNotBlank()) {
            AppleCacheSource(occurrence, serial, activeProfile, group, codecs, quality, mode, duration, activeServer, activeStorefront)
        } else null
        activeCacheSource = cache
        if (loggedOutputSerial != serial) {
            loggedOutputSerial = serial
            Log.i("FantasticPlayback", "native-apple id=${song.track.id} group=$group codecs=$codecs mode=$mode")
        }
    }

    private fun qualityLabel(group: String, sampleRate: Int): String {
        val name = group.lowercase()
        return when {
            "alac" in name -> if (sampleRate > 48_000) "Hi-Res 无损" else "无损"
            "atmos" in name && "downmix" !in name && "binaural" in name -> "空间音频"
            "atmos" in name && "downmix" !in name -> "杜比全景声"
            else -> "AAC"
        }
    }

    private fun startServerMonitor(serial: Long) {
        serverMonitorJob?.cancel()
        val initial = SourceStatus.normalizeBaseUrl(configuredServer)
        serverMonitorJob = scope.launch {
            while (isCurrent(serial)) {
                delay(500L)
                if (SourceStatus.normalizeBaseUrl(prefs.baseUrl) != initial) {
                    if (isCurrent(serial)) {
                        ++requestSerial
                        activeServer = ""
                        cancelActive(releasePlayer = true)
                        _state.value = _state.value.copy(connected = false, isPlaying = false, playWhenReady = false,
                            buffering = false, error = "服务器已更改，点按播放重新连接")
                    }
                    return@launch
                }
            }
        }
    }

    private fun publishError(message: String) {
        activeCacheSource = null
        cancelActive(releasePlayer = true)
        activeSerial = -1L
        _state.value = _state.value.copy(connected = false, isPlaying = false, playWhenReady = false,
            buffering = false, error = message)
    }

    private fun cancelActive(releasePlayer: Boolean) {
        requestJob?.cancel()
        requestJob = null
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        activeCacheSource = null
        activeSerial = -1L
        nativeDecrypt = null
        activeTrack = null
        timelineInstalled = false
        pendingSeekBeforeTimeline = false
        val player = mediaPlayer
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            if (releasePlayer) {
                player.removeListener(mediaListener)
                player.release()
                mediaPlayer = null
            }
        }
        releaseAlacOutput()
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        player.removeListener(mediaListener)
        player.release()
        mediaPlayer = null
    }

    private fun releaseAlacOutput() {
        val output = alacOutput
        alacOutput = null
        val handle = alacHandle
        alacHandle = 0L
        alacSampleRate = 0
        alacChannels = 0
        alacSubmittedSerial = -1L
        if (output != null) {
            runCatching { output.stop() }
            runCatching { output.release() }
        }
        AlacDecoder.closeSafe(handle)
    }

    private fun isCurrent(serial: Long): Boolean = !closed && serial > 0L && serial == requestSerial && serial == activeSerial

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
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

    private companion object {
        val STORE_FRONT = Regex("[a-z]{2}")
        val CACHE_GROUP = Regex("[A-Za-z0-9._-]{1,128}")
        val CACHE_QUALITIES = Prefs.APPLE_QUALITIES.mapTo(hashSetOf()) { it.first }
    }
}
