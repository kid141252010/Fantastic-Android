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
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.data.reportedAudioDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

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
    val resolvedAlac: AppleResolvedSource? = null,
)

@UnstableApi
internal class ApplePlaybackEngine(
    context: Context,
    private val prefs: Prefs,
    private val api: FantasticApi,
    private val cache: PlaybackCache,
) {
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
    private var alacJob: Job? = null
    private val alacRunMutex = Mutex()
    private var serverMonitorJob: Job? = null
    private sealed interface AlacSource {
        data class Online(val source: AppleResolvedSource) : AlacSource
        data class Cached(val entry: PlaybackCacheEntry) : AlacSource
    }
    private var activeAlacSource: AlacSource? = null
    private var activeTrack: AppleResolvedTrack? = null
    private var activeProfile = ApplePlaybackProfile("wifi", "hires", "auto")
    private var activeServer = ""
    private var configuredServer = ""
    private var activeStorefront = ""
    private var requestedQuality: String? = null
    private var pendingPositionMs = 0L
    private var timelineInstalled = false
    private data class PendingSeek(
        val requestSerial: Long,
        val seekGeneration: Int,
        val targetMs: Long,
    )
    private var pendingSeek: PendingSeek? = null
    private val mediaWindow = Timeline.Window()
    private class AlacRun(
        val serial: Long,
        val generation: Int,
        val output: AudioTrack,
        val handle: Long,
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        val cacheFileId: String?,
        val startPositionMs: Long,
        val headBase: Long,
    ) {
        var submittedFrames = 0L
        var playedFrames = 0L
        var lastHead = headBase
        var outputStarted = false
    }

    private var alacRun: AlacRun? = null
    private var loggedOutputSerial = -1L
    private var pendingSeekLogGeneration: Int? = null

    internal val currentUid: Int get() = songs.getOrNull(occurrence)?.uid ?: -1

    internal fun prewarm(server: String) {
        if (!closed) SourceStatus.normalizeBaseUrl(server)
    }

    fun position(): Long {
        if (pendingSeekFor(requestSerial) != null) return pendingPositionMs
        if (activeSerial != requestSerial) return pendingPositionMs
        val currentSong = songs.getOrNull(occurrence) ?: return pendingPositionMs
        if (_state.value.queueIndex != occurrence || _state.value.mediaId != "apple:${currentSong.track.id}") return pendingPositionMs
        val duration = _state.value.durationMs.takeIf { it > 0L } ?: currentSong.track.durationMs.takeIf { it > 0L }
        val position = if (activeAlacSource != null) {
            val run = alacRun?.takeIf { isCurrentAlac(it.serial, it.generation) && it.outputStarted }
                ?: return pendingPositionMs
            val head = run.output.playbackHeadPosition.toLong() and 0xffff_ffffL
            val advanced = (head - run.lastHead) and 0xffff_ffffL
            run.lastHead = head
            run.playedFrames = (run.playedFrames + advanced).coerceAtMost(run.submittedFrames)
            run.startPositionMs + run.playedFrames * 1000L / run.sampleRate
        } else {
            if (!timelineInstalled || !hasRealMedia3Timeline(activeSerial)) return pendingPositionMs
            (mediaPlayer?.currentPosition ?: pendingPositionMs).coerceAtLeast(0L)
        }
        return position.coerceAtMost(duration ?: Long.MAX_VALUE)
    }

    fun playQueue(
        songs: List<QueuedSong>, index: Int, startPositionMs: Long = 0L,
        playWhenReady: Boolean = true, quality: String? = null,
        server: String, storefront: String?,
        skipCachedAlac: Boolean = false,
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
        activeStorefront = selected.track.country.normalizedAppleCountry() ?: storefront.normalizedAppleCountry().orEmpty()
        if (activeStorefront.isEmpty()) {
            publishError("Apple Music 国家无效")
            return@onMain
        }
        activeServer = server
        configuredServer = prefs.baseUrl
        pendingPositionMs = startPositionMs.coerceIn(0L, selected.track.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        timelineInstalled = false
        pendingSeek = null
        loggedOutputSerial = -1L
        pendingSeekLogGeneration = null
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
        val key = PlaybackCacheKey.from(server, activeStorefront, Source.Apple, selected.track.id)
        val profile = applePlaybackProfile(appContext, prefs, key?.let(cache::appleNetwork))
        activeProfile = profile
        val choice = requestedQuality ?: profile.quality
        val cached = if (skipCachedAlac || key == null) null else cache.find(key, choice, profile)?.takeIf { it.isCompleteAppleAlac }
        if (cached != null) {
            activeServer = cached.key.server
            activeSerial = serial
            val source = AlacSource.Cached(cached)
            activeAlacSource = source
            _state.value = _state.value.copy(connected = true, durationMs = cached.durationMs)
            releaseMediaPlayer()
            startServerMonitor(serial)
            startAlac(source, serial)
            return@onMain
        }
        if (!playWhenReady) return@onMain
        val normalized = SourceStatus.normalizeBaseUrl(server) ?: run {
            publishError("服务器地址无效")
            return@onMain
        }
        activeServer = normalized
        requestJob = scope.launch {
            try {
                val decrypt = AppleNativeDecrypt(appContext, api, normalized)
                val resolved = decrypt.prepare(selected.track.id, choice, profile)
                if (closed || serial != requestSerial) return@launch
                activeTrack = resolved
                activeSerial = serial
                val duration = resolved.media.durationMs.takeIf { it > 0L } ?: selected.track.durationMs
                _state.value = _state.value.copy(connected = true, buffering = _state.value.playWhenReady, durationMs = duration)
                startServerMonitor(serial)
                if (resolved.variant.groupId.contains("alac", true) && !resolved.variant.widevine) {
                    releaseMediaPlayer()
                    val source = AlacSource.Online(AppleResolvedSource(resolved, decrypt, checkNotNull(key)))
                    startAlac(source, serial)
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
        mediaPlayer?.pause()
        runCatching { alacRun?.output?.pause() }
        pendingPositionMs = position()
        activeCacheSource = null
        _state.value = previous.copy(playWhenReady = false, isPlaying = false, buffering = false,
            bufferedPositionMs = pendingPositionMs)
    }

    fun seekTo(ms: Long) = onMain {
        val state = _state.value
        if (state.queueIndex !in state.queue.indices) return@onMain
        val duration = state.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        pendingPositionMs = ms.coerceIn(0L, duration)
        val seek = PendingSeek(requestSerial, state.seekGeneration + 1, pendingPositionMs)
        pendingSeek = seek
        activeCacheSource = null
        val source = activeAlacSource
        val alac = source != null
        val readyTimeline = !alac && timelineInstalled && hasRealMedia3Timeline(activeSerial)
        _state.value = state.copy(ended = false, seekGeneration = seek.seekGeneration,
            isPlaying = if (readyTimeline) state.isPlaying else false,
            buffering = if (readyTimeline) state.buffering else state.playWhenReady,
            bufferedPositionMs = pendingPositionMs)
        pendingSeekLogGeneration = _state.value.seekGeneration
        val id = songs.getOrNull(occurrence)?.track?.id.orEmpty().appleLogToken()
        Log.i("FantasticPlayback", "apple-seek id=$id occurrence=$currentUid request=$requestSerial " +
            "seek=${_state.value.seekGeneration} targetMs=$pendingPositionMs")
        if (activeSerial != requestSerial) return@onMain
        if (source != null) {
            // Generation invalidation above must precede cancellation and the old run's finally.
            startAlac(source, requestSerial)
        } else {
            publishMedia3State(requestSerial)
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
                _state.value = state.copy(playWhenReady = true, buffering = true, ended = false)
                if (isCurrent(activeSerial)) {
                    mediaPlayer?.play()
                    if (mediaPlayer != null) publishMedia3State(activeSerial)
                }
                val run = alacRun?.takeIf { isCurrentAlac(it.serial, it.generation) }
                if (run != null) {
                    run.output.play()
                    publishAlacState(run)
                }
                if (run == null && (mediaPlayer == null || !isCurrent(activeSerial)) &&
                    requestJob?.isActive != true && alacJob?.isActive != true) {
                    val source = activeAlacSource
                    if (source != null && isCurrent(requestSerial)) {
                        startAlac(source, requestSerial)
                    } else {
                        playQueue(songs, state.queueIndex, pendingPositionMs, true, requestedQuality,
                            activeServer.takeIf(String::isNotBlank) ?: prefs.baseUrl, activeStorefront)
                    }
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
        if (finishAtResolvedEnd(track)) return
        val playWhenReady = _state.value.playWhenReady
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
        val startPositionMs = if (pendingSeekFor(serial) != null) 0L else pendingPositionMs
        _state.value = _state.value.copy(connected = true, isPlaying = false, buffering = playWhenReady)
        player.playWhenReady = playWhenReady
        player.setMediaSource(source, startPositionMs)
        player.prepare()
        publishMedia3State(serial)
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
        override fun onIsPlayingChanged(isPlaying: Boolean) = onMain { publishMedia3State(activeSerial) }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = onMain {
            publishMedia3State(activeSerial)
        }

        override fun onPlaybackStateChanged(playbackState: Int) = onMain { publishMedia3State(activeSerial) }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = onMain { publishMedia3State(activeSerial) }

        override fun onPlayerError(error: PlaybackException) = onMain {
            val serial = activeSerial
            val player = mediaPlayer ?: return@onMain
            if (!isCurrent(serial) || player.currentMediaItem?.localConfiguration?.uri?.host?.toLongOrNull() != serial) return@onMain
            pendingPositionMs = position()
            val message = if (activeTrack?.variant?.widevine == true) AppleWidevineCallback.LICENSE_REJECTED
                else error.message?.takeIf(String::isNotBlank) ?: "Apple 播放失败"
            publishError(message)
        }
    }

    private fun hasRealMedia3Timeline(serial: Long): Boolean {
        val player = mediaPlayer ?: return false
        if (!isCurrent(serial) || player.currentMediaItem?.localConfiguration?.uri?.host?.toLongOrNull() != serial) return false
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        return index in 0 until timeline.windowCount && !timeline.getWindow(index, mediaWindow).isPlaceholder
    }

    private fun publishMedia3State(serial: Long) {
        val player = mediaPlayer ?: return
        if (!isCurrent(serial) || player.currentMediaItem?.localConfiguration?.uri?.host?.toLongOrNull() != serial) return
        if (!hasRealMedia3Timeline(serial)) {
            timelineInstalled = false
            activeCacheSource = null
            val state = _state.value
            _state.value = state.copy(isPlaying = false, buffering = state.playWhenReady,
                bufferedPositionMs = pendingPositionMs)
            return
        }
        timelineInstalled = true
        val seek = pendingSeekFor(serial)
        if (seek != null) {
            pendingSeek = null
            player.seekTo(seek.targetMs)
        }
        pendingPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (player.playbackState == Player.STATE_READY) logSeekOutput(serial, "mse")
        if (player.isPlaying) publishOutputStarted(serial, "mse") else activeCacheSource = null
        _state.value = _state.value.copy(
            connected = true,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(pendingPositionMs),
        )
    }

    private fun finishAtResolvedEnd(track: AppleResolvedTrack): Boolean {
        val duration = track.media.durationMs.takeIf { it > 0L } ?: _state.value.durationMs.takeIf { it > 0L }
        pendingPositionMs = pendingPositionMs.coerceIn(0L, duration ?: Long.MAX_VALUE)
        if (duration == null || pendingPositionMs < duration || track.media.segments.isEmpty()) return false
        activeCacheSource = null
        _state.value = _state.value.copy(isPlaying = false, buffering = false, ended = true,
            bufferedPositionMs = pendingPositionMs)
        return true
    }

    private fun startAlac(source: AlacSource, serial: Long) {
        val generation = _state.value.seekGeneration
        if (!isCurrentAlac(serial, generation)) return
        val target = pendingSeekFor(serial)?.targetMs ?: pendingPositionMs
        pendingSeek = null
        activeAlacSource = source
        cancelAlacRun()
        alacJob = scope.launch {
            alacRunMutex.withLock {
                if (!isCurrentAlac(serial, generation)) return@withLock
                try {
                    playAlac(source, serial, generation, target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isCurrentAlac(serial, generation)) {
                        if (error is AppleAlacCacheException && source is AlacSource.Cached) {
                            val target = pendingPositionMs
                            val playing = _state.value.playWhenReady
                            cache.invalidate(source.entry.fileId)
                            cache.pinCurrent(null)
                            Log.w("FantasticPlayback", "apple-cache-invalid id=${source.entry.key.trackId.appleLogToken()} " +
                                "occurrence=$currentUid request=$serial file=${source.entry.fileId} reason=${error.message}")
                            playQueue(songs, occurrence, target, playing, requestedQuality,
                                activeServer, activeStorefront, skipCachedAlac = true)
                        } else publishError(error.message?.takeIf(String::isNotBlank) ?: "ALAC 播放失败")
                    }
                }
            }
        }
    }

    private suspend fun playAlac(
        source: AlacSource, serial: Long, generation: Int, startAtMs: Long,
    ) = supervisorScope {
        var reader: AppleAlacReader? = null
        var handle = 0L
        var output: AudioTrack? = null
        var run: AlacRun? = null
        var monitor: Job? = null
        fun decodeFailure(message: String): Nothing {
            if (source is AlacSource.Cached) throw AppleAlacCacheException("ALAC 缓存格式无效", IOException(message))
            throw IOException(message)
        }
        try {
            awaitPlayWhenReady(serial, generation)
            val opened = when (source) {
                is AlacSource.Online -> AppleAlacStreamReader(source.source, this)
                is AlacSource.Cached -> {
                    val lease = cache.acquire(source.entry.fileId) ?: throw AppleAlacCacheException("ALAC 缓存读取失败")
                    AppleAlacFileReader.open(lease.file, lease)
                }
            }
            reader = opened
            if (!isCurrentAlac(serial, generation)) throw CancellationException()
            if (source is AlacSource.Cached) {
                val expected = maxOf(source.entry.durationMs, songs.getOrNull(occurrence)?.track?.durationMs ?: 0L)
                if (expected > 10_000L && opened.durationMs + 5_000L < expected) {
                    throw AppleAlacCacheException("ALAC 缓存格式无效", IOException("音源仅返回试听片段，未保存为完整歌曲"))
                }
                Log.i("FantasticPlayback", "apple-route id=${source.entry.key.trackId.appleLogToken()} " +
                    "occurrence=$currentUid request=$serial path=cache-alac file=${source.entry.fileId}")
            }
            val duration = opened.durationMs.takeIf { it > 0L }
            val target = startAtMs.coerceIn(0L, duration ?: Long.MAX_VALUE)
            pendingPositionMs = target
            if (duration != null) _state.value = _state.value.copy(durationMs = duration)
            if (duration != null && target == duration) {
                activeCacheSource = null
                _state.value = _state.value.copy(isPlaying = false, buffering = false, ended = true,
                    bufferedPositionMs = target)
                return@supervisorScope
            }
            handle = AlacDecoder.openSafe(opened.cookie)
            if (handle == 0L) decodeFailure("该曲目没有所选音质")
            val channels = AlacDecoder.channels(handle)
            val sampleRate = AlacDecoder.sampleRate(handle)
            val bits = AlacDecoder.bits(handle)
            if (channels !in 1..8 || sampleRate <= 0 || bits != 16 && bits != 20 && bits != 24 && bits != 32) {
                decodeFailure("ALAC 解码器初始化失败")
            }
            val firstPacketTimeUs = opened.seekTo(target)
            awaitPlayWhenReady(serial, generation)
            val built = buildAlacOutput(sampleRate, channels, bits) ?: throw IOException("ALAC AudioTrack 创建失败")
            val createdOutput = built.first
            output = createdOutput
            val floatOutput = built.second
            val cacheFileId = (source as? AlacSource.Cached)?.entry?.fileId
            val currentRun = AlacRun(serial, generation, createdOutput, handle, sampleRate, channels, bits, cacheFileId,
                target, createdOutput.playbackHeadPosition.toLong() and 0xffff_ffffL)
            run = currentRun
            alacRun = currentRun
            if (cacheFileId != null) cache.pinCurrent(cacheFileId)
            val bytesPerOutputSample = if (floatOutput) 4 else 2
            val direct = ByteBuffer.allocateDirect(opened.maxBlock * channels * bytesPerOutputSample).order(ByteOrder.LITTLE_ENDIAN)
            var skipFrames = maxOf(0L, target * 1000L - firstPacketTimeUs) * sampleRate / 1_000_000L
            createdOutput.play()
            monitor = launch {
                while (isCurrentAlac(serial, generation)) {
                    publishAlacState(currentRun)
                    delay(100L)
                }
            }
            while (true) {
                awaitPlayWhenReady(serial, generation)
                val packet = opened.readPacket() ?: break
                val decoderHandle = currentRun.handle
                val pcm = withContext(Dispatchers.Default) { AlacDecoder.decodeSafe(decoderHandle, packet) }
                val frameBytes = channels * if (bits == 16) 2 else 4
                if (pcm.isEmpty() || pcm.size % frameBytes != 0 || pcm.size / frameBytes > opened.maxBlock) {
                    decodeFailure("ALAC 分片解码失败")
                }
                val frames = pcm.size / frameBytes
                val skipped = minOf(skipFrames, frames.toLong()).toInt()
                skipFrames -= skipped
                if (skipped < frames) {
                    writeAlacPcm(currentRun, pcm, skipped, frames - skipped, bits, floatOutput, direct)
                }
            }
            if (currentRun.submittedFrames == 0L) decodeFailure("ALAC 音频轨道为空")
            // Submission is not playback: retain the output until its real head drains.
            while (true) {
                awaitPlayWhenReady(serial, generation)
                position()
                if (currentRun.playedFrames >= currentRun.submittedFrames) break
                delay(100L)
            }
            pendingPositionMs = position()
            activeCacheSource = null
            _state.value = _state.value.copy(isPlaying = false, buffering = false, ended = true,
                bufferedPositionMs = pendingPositionMs)
        } finally {
            monitor?.cancel()
            try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (run != null && alacRun === run) {
                        if (isCurrentAlac(serial, generation)) pendingPositionMs = position()
                        alacRun = null
                        if (run.cacheFileId != null) cache.pinCurrent(null)
                    }
                    runCatching { output?.pause() }
                    runCatching { output?.flush() }
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    try { reader?.close() }
                    finally { monitor?.cancelAndJoin() }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { output?.stop() }
                    runCatching { output?.release() }
                    AlacDecoder.closeSafe(handle)
                }
            }
        }
    }

    private fun publishAlacState(run: AlacRun) {
        if (alacRun !== run || !isCurrentAlac(run.serial, run.generation)) return
        val state = _state.value
        if (state.ended) return
        val absolutePosition = position()
        val playing = state.playWhenReady && run.outputStarted && run.submittedFrames > run.playedFrames
        val buffering = state.playWhenReady && !playing
        if (state.isPlaying == playing && state.buffering == buffering) return
        if (playing) {
            publishOutputStarted(run.serial, "alac")
        } else {
            pendingPositionMs = absolutePosition
            activeCacheSource = null
            _state.value = state.copy(isPlaying = false, buffering = buffering, bufferedPositionMs = absolutePosition)
        }
    }

    private suspend fun awaitPlayWhenReady(serial: Long, generation: Int) {
        while (true) {
            coroutineContext.ensureActive()
            if (!isCurrentAlac(serial, generation)) throw CancellationException()
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

    private suspend fun writeAlacPcm(
        run: AlacRun,
        pcm: ByteArray,
        skipFrames: Int,
        frames: Int,
        bits: Int,
        floatOutput: Boolean,
        direct: ByteBuffer,
    ): Int {
        val channels = run.channels
        withContext(Dispatchers.Default) {
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
        }
        val frameBytes = channels * if (floatOutput) 4 else 2
        var writtenBytes = 0
        while (direct.hasRemaining()) {
            awaitPlayWhenReady(run.serial, run.generation)
            val result = run.output.write(direct, direct.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (result < 0) throw IOException("ALAC AudioTrack 写入失败")
            if (result == 0) {
                delay(10L)
                continue
            }
            writtenBytes += result
            run.submittedFrames += result / frameBytes
            if (!run.outputStarted || !_state.value.isPlaying) {
                run.outputStarted = true
                publishAlacState(run)
            }
        }
        return writtenBytes
    }

    private fun publishOutputStarted(serial: Long, mode: String) {
        if (!isCurrent(serial) || !_state.value.playWhenReady) return
        val song = songs.getOrNull(occurrence) ?: return
        if (_state.value.queueIndex != occurrence || _state.value.mediaId != "apple:${song.track.id}") return
        val run = alacRun?.takeIf { mode == "alac" && it.outputStarted && isCurrentAlac(it.serial, it.generation) }
        if (mode == "alac" && run == null) return
        val variant = activeTrack?.variant
        val cached = (activeAlacSource as? AlacSource.Cached)?.entry
        val group = cached?.appleGroup ?: variant?.groupId ?: return
        val quality = codecGroup(group)
        val codecs = if (cached != null) "alac" else variant?.codecs.orEmpty()
        val reportedCodec = if (group.contains("alac", true)) "alac" else codecs
        val duration = _state.value.durationMs.takeIf { it > 0L } ?: song.track.durationMs
        val rate = run?.sampleRate ?: variant?.sampleRate ?: 0
        val details = reportedAudioDetails(
            codec = reportedCodec,
            sampleRate = rate,
            bitDepth = run?.bits ?: variant?.bitDepth ?: 0,
            channels = run?.channels?.toString() ?: variant?.channels,
            bitrate = variant?.bandwidth ?: 0L,
            bitrateLabel = "变体带宽",
        )
        _state.value = _state.value.copy(
            connected = true,
            mediaId = "apple:${song.track.id}",
            title = song.track.title,
            artist = song.track.artist,
            artworkUri = song.track.artwork(600),
            isPlaying = true,
            buffering = false,
            ended = false,
            durationMs = duration,
            quality = quality,
            qualityLabel = qualityLabel(group, rate),
            audioDetails = details,
        )
        activeCacheSource = if (cached == null && duration > 0L && quality != null && quality in CACHE_QUALITIES &&
            CACHE_GROUP.matches(group) && codecs.isNotBlank()) {
            AppleCacheSource(currentUid, serial, activeProfile, group, codecs, quality, mode, duration, activeServer, activeStorefront,
                (activeAlacSource as? AlacSource.Online)?.source)
        } else null
        logSeekOutput(serial, mode)
        if (loggedOutputSerial != serial) {
            loggedOutputSerial = serial
            if (cached != null) cache.markPlayed(cached.key)
            Log.i("FantasticPlayback", "native-apple id=${song.track.id.appleLogToken()} group=${group.appleLogToken()} " +
                "codecs=${codecs.appleLogToken()} mode=$mode rate=$rate bits=${run?.bits ?: variant?.bitDepth ?: 0} " +
                "channels=${run?.channels ?: variant?.channels} file=${run?.cacheFileId ?: "-"}")
        }
    }

    private fun logSeekOutput(serial: Long, mode: String) {
        val generation = pendingSeekLogGeneration ?: return
        if (!isCurrent(serial) || generation != _state.value.seekGeneration) return
        pendingSeekLogGeneration = null
        val id = songs.getOrNull(occurrence)?.track?.id.orEmpty().appleLogToken()
        Log.i("FantasticPlayback", "apple-seek-output id=$id occurrence=$currentUid request=$serial " +
            "seek=$generation mode=$mode positionMs=${position()}")
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
        (activeAlacSource as? AlacSource.Online)?.source?.decrypt?.clearSegmentCache()
        activeAlacSource = null
        activeTrack = null
        timelineInstalled = false
        pendingSeek = null
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
        cancelAlacRun()
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        player.removeListener(mediaListener)
        player.release()
        mediaPlayer = null
    }

    private fun cancelAlacRun() {
        val run = alacRun
        alacRun = null
        if (run != null) {
            runCatching { run.output.pause() }
            runCatching { run.output.flush() }
            if (run.cacheFileId != null) cache.pinCurrent(null)
        }
        alacJob?.cancel()
        alacJob = null
    }

    private fun pendingSeekFor(serial: Long): PendingSeek? = pendingSeek?.takeIf {
        it.requestSerial == serial && serial == requestSerial && it.seekGeneration == _state.value.seekGeneration
    }

    private fun isCurrent(serial: Long): Boolean = !closed && serial > 0L && serial == requestSerial && serial == activeSerial

    private fun isCurrentAlac(serial: Long, generation: Int): Boolean =
        isCurrent(serial) && _state.value.seekGeneration == generation

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
        val CACHE_GROUP = Regex("[A-Za-z0-9._-]{1,128}")
        val CACHE_QUALITIES = Prefs.APPLE_QUALITIES.mapTo(hashSetOf()) { it.first }
    }
}
