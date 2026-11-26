package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import android.util.Base64
import android.util.Log
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

internal data class AppleVariant(
    val url: String,
    val groupId: String,
    val codecs: String,
    val bandwidth: Long,
    val channels: String?,
    val sampleRate: Int,
    val bitDepth: Int,
    val quality: String? = null,
    val widevine: Boolean = false,
)

internal data class AppleByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

internal data class AppleMediaSegment(
    val range: AppleByteRange,
    val startMs: Long,
    val durationMs: Long,
    val keyUri: String?,
    val keyKind: KeyKind?,
)

internal enum class KeyKind { PREFETCH, TRACK, WIDEVINE }

internal data class AppleMediaPlaylist(
    val playlistUrl: HttpUrl,
    val resourceUrl: HttpUrl,
    val adamId: String,
    val init: AppleByteRange,
    val segments: List<AppleMediaSegment>,
    val durationMs: Long,
    val totalBytes: Long,
    val sourceText: String,
)

internal data class AppleSampleRange(val start: Int, val end: Int) {
    val size: Int get() = end - start
}

internal data class AppleAlacConfig(val channels: Int, val bits: Int, val maxBlock: Int)

internal data class AppleResolvedTrack(
    val variant: AppleVariant,
    val media: AppleMediaPlaylist,
    val initBytes: ByteArray,
    val alacCookie: ByteArray?,
    val alacConfig: AppleAlacConfig?,
    val widevineUri: String?,
    val widevinePssh: ByteArray?,
)

internal const val APPLE_ALAC_SEGMENT_BUFFER_BYTES = 8L * 1024 * 1024

internal data class AppleResolvedSource(val track: AppleResolvedTrack, val decrypt: AppleNativeDecrypt, val key: PlaybackCacheKey)

private data class AppleSegmentKey(
    val resourceUrl: HttpUrl,
    val adamId: String,
    val groupId: String,
    val range: AppleByteRange,
    val keyKind: KeyKind?,
    val keyUri: String?,
)

internal enum class AppleSegmentPriority { Playback, Background }

private enum class AppleTicketState { Queued, Active, Finished }

private class AppleSegmentTicket(var priority: AppleSegmentPriority, val job: Long) {
    var state = AppleTicketState.Queued
    var enqueued = false
    val granted = CompletableDeferred<Unit>()
    val queuedAt = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
    var admittedAt = 0L
}

/** Process-wide producer admission; an active background job never consumes the playback reserve. */
private object AppleSegmentAdmission {
    private val lock = Any()
    private val playback = ArrayDeque<AppleSegmentTicket>()
    private val background = ArrayDeque<AppleSegmentTicket>()
    private val jobs = AtomicLong()
    private var active = 0
    private var backgroundActive = 0

    fun ticket(priority: AppleSegmentPriority) = AppleSegmentTicket(priority, if (BuildConfig.DEBUG) jobs.incrementAndGet() else 0L)

    suspend fun acquire(ticket: AppleSegmentTicket) {
        currentCoroutineContext().ensureActive()
        val grants = synchronized(lock) {
            check(ticket.state == AppleTicketState.Queued && !ticket.enqueued)
            ticket.enqueued = true
            queue(ticket.priority).addLast(ticket)
            drain()
        }
        notify(grants)
        ticket.granted.await()
        currentCoroutineContext().ensureActive()
    }

    fun promote(ticket: AppleSegmentTicket) {
        val grants = synchronized(lock) {
            if (ticket.state == AppleTicketState.Queued && ticket.priority == AppleSegmentPriority.Background) {
                if (ticket.enqueued) background.remove(ticket)
                ticket.priority = AppleSegmentPriority.Playback
                if (ticket.enqueued) playback.addLast(ticket)
            }
            drain()
        }
        notify(grants)
    }

    fun release(ticket: AppleSegmentTicket) {
        val grants = synchronized(lock) {
            when (ticket.state) {
                AppleTicketState.Queued -> if (ticket.enqueued) queue(ticket.priority).remove(ticket)
                AppleTicketState.Active -> {
                    active--
                    if (ticket.priority == AppleSegmentPriority.Background) backgroundActive--
                }
                AppleTicketState.Finished -> return
            }
            ticket.state = AppleTicketState.Finished
            ticket.enqueued = false
            drain()
        }
        notify(grants)
    }

    fun trace(ticket: AppleSegmentTicket, track: AppleResolvedTrack, index: Int, event: String, bytes: Int, status: String?) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val state = synchronized(lock) { Triple(ticket.priority, active, backgroundActive) }
        val queueMs = (ticket.admittedAt.takeIf { it > 0L } ?: now) - ticket.queuedAt
        val elapsedMs = if (ticket.admittedAt > 0L) now - ticket.admittedAt else 0L
        Log.d("FantasticPlayback", "apple-segment-$event job=${ticket.job} id=${track.media.adamId.appleLogToken()} " +
            "group=${track.variant.groupId.appleLogToken()} index=$index priority=${state.first} " +
            "active=${state.second} background=${state.third} queueMs=$queueMs elapsedMs=$elapsedMs bytes=$bytes" +
            (if (status == null) "" else " status=$status"))
    }

    private fun queue(priority: AppleSegmentPriority) = if (priority == AppleSegmentPriority.Playback) playback else background

    // All callers hold lock. Complete notifications only after leaving it.
    private fun drain(): List<AppleSegmentTicket> {
        var grants: MutableList<AppleSegmentTicket>? = null
        while (active < AUDIO_LANES + 1) {
            val ticket = when {
                playback.isNotEmpty() -> playback.removeFirst()
                backgroundActive < AUDIO_LANES && background.isNotEmpty() -> background.removeFirst()
                else -> break
            }
            ticket.enqueued = false
            ticket.state = AppleTicketState.Active
            active++
            if (ticket.priority == AppleSegmentPriority.Background) backgroundActive++
            if (grants == null) grants = ArrayList()
            grants.add(ticket)
        }
        return grants ?: emptyList()
    }

    private fun notify(grants: List<AppleSegmentTicket>) {
        for (ticket in grants) {
            if (BuildConfig.DEBUG) ticket.admittedAt = SystemClock.elapsedRealtime()
            ticket.granted.complete(Unit)
        }
    }
}

private class AppleSegmentFlight(val ticket: AppleSegmentTicket) {
    val result = CompletableDeferred<ByteArray>()
}

private enum class AppleRangeMode { Unknown, Verified, Serial, Failed }
private data class AppleRangeIdentity(val mode: AppleRangeMode, val total: Long = 0L, val etag: String? = null)
private data class AppleRangeReply(val code: Int, val body: ByteArray, val contentRange: String?, val etag: String?)
private val appleStrongTag = Regex("\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"")
private val appleContentRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)

/** Only tagged media requests emit wire events; never log URLs, headers or exception messages. */
private class AppleSegmentHttpTrace(private val job: Long, private val index: Int) : EventListener() {
    private var code = 0
    private fun trace(event: String, bytes: Long = 0L) {
        if (BuildConfig.DEBUG) Log.d("FantasticPlayback", "apple-segment-http event=$event job=$job index=$index code=$code bytes=$bytes")
    }
    override fun requestHeadersEnd(call: Call, request: Request) { code = 0; trace("headers") }
    override fun responseHeadersEnd(call: Call, response: Response) { code = response.code; trace("status") }
    override fun responseBodyEnd(call: Call, byteCount: Long) { trace("end", byteCount) }
    override fun callFailed(call: Call, ioe: IOException) { trace("failed") }
}

/** Resolves Apple HLS and performs length-preserving FairPlay sample decryption in-process. */
@UnstableApi
internal class AppleNativeDecrypt(
    context: Context,
    private val api: FantasticApi,
    server: String,
) {
    private val appContext = context.applicationContext
    private val server = SourceStatus.normalizeBaseUrl(server)?.toHttpUrlOrNull()
        ?: throw IOException("服务器地址无效")
    private val client = api.client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply {
            if (BuildConfig.DEBUG) eventListenerFactory { call ->
                call.request().tag(AppleSegmentHttpTrace::class.java) ?: EventListener.NONE
            }
        }
        .build()
    private val segmentLock = Any()
    private val inFlight = mutableMapOf<AppleSegmentKey, AppleSegmentFlight>()
    private val urgentSegments = mutableSetOf<AppleSegmentKey>()
    private val rangeLock = Any()
    private val rangeIdentities = mutableMapOf<HttpUrl, AppleRangeIdentity>()
    private val completedSegments = LinkedHashMap<AppleSegmentKey, ByteArray>(4, 0.75f, true)
    private var completedSegmentBytes = 0L
    private var retainSegments = true

    internal fun clearSegmentCache() = synchronized(segmentLock) {
        completedSegments.clear()
        completedSegmentBytes = 0L
        urgentSegments.clear()
        // Detached sources can finish their cache transfer, but no longer retain playback fragments.
        retainSegments = false
    }

    suspend fun prepare(
        adamId: String,
        wanted: String?,
        profile: ApplePlaybackProfile,
        requiredGroup: String? = null,
    ): AppleResolvedTrack {
        require(adamId.matches(Regex("\\d+"))) { "Apple 曲目 ID 无效" }
        val parseUrl = server.newBuilder().addPathSegment("parse").addPathSegment("song").addPathSegment(adamId).build()
        val parse = getJson(parseUrl, "Apple 播放列表获取失败")
        if (parse.optInt("code", -1) != 0) {
            throw IOException("Apple 播放列表获取失败：HTTP ${parse.optInt("code", 502)}")
        }
        val masterUrl = parse.optJSONObject("data")?.optString("masterUrl")?.takeIf(String::isNotBlank)
            ?: throw IOException("Apple 播放列表获取失败：HTTP ${parse.optInt("code", 502)}")
        val variants: List<AppleVariant>
        val masterMediaUrl: HttpUrl?
        if (masterUrl.toHttpUrlOrNull()?.encodedPath?.endsWith(".m3u8", ignoreCase = true) == true) {
            masterMediaUrl = requireHttps(masterUrl)
            val masterText = getText(masterMediaUrl, "Apple 播放列表获取失败")
            variants = parseMaster(masterText, masterMediaUrl)
            if (variants.isEmpty()) throw IOException("Apple 播放列表获取失败：HTTP 502")
        } else {
            masterMediaUrl = null
            val playbackUrl = server.newBuilder().addPathSegment("mv").addPathSegment("webplayback").addPathSegment(adamId).build()
            val playback = getJson(playbackUrl, "Apple 播放列表获取失败")
            if (playback.optInt("code", -1) != 0) throw IOException("Apple 播放列表获取失败：HTTP ${playback.optInt("code", 502)}")
            val mediaUrl = playback.optJSONObject("data")?.optString("m3u8")?.takeIf(String::isNotBlank)
                ?: throw IOException("Apple 播放列表获取失败：HTTP ${playback.optInt("code", 502)}")
            variants = listOf(AppleVariant(requireHttps(mediaUrl).newBuilder().fragment("adamId=$adamId").build().toString(),
                "audio-stereo-256", "mp4a.40.2", 256_000L, "2", 0, 0, widevine = true))
        }
        if (variants.singleOrNull()?.widevine == true && !android.media.MediaDrm.isCryptoSchemeSupported(androidx.media3.common.C.WIDEVINE_UUID)) {
            throw IOException("该设备没有 Widevine，不能播放这首仅 AAC-LC 的歌曲")
        }

        if (requiredGroup != null) {
            val exact = variants.filter { it.groupId == requiredGroup }
            val candidate = exact.firstOrNull { isPlayable(it) }
                ?: throw IOException("该曲目没有所选音质")
            return prepareCandidate(candidate, adamId)
        }

        val requestedProfile = when (wanted) {
            "hires", "lossless", "high", "efficient" -> profile.copy(quality = wanted)
            else -> profile
        }
        val explicit = wanted?.takeIf { it.isNotBlank() && it !in APPLE_PROFILE_QUALITIES }
        if (explicit != null) {
            val exact = variants.filter { it.groupId == explicit || it.quality == explicit }
            val choices = exact.ifEmpty { variants.filter { matchesWanted(it, explicit) } }.sortedWith(variantComparator(explicit))
            if (choices.isEmpty()) throw IOException("该曲目没有所选音质")
            var unsupportedAtmos = false
            for (candidate in choices) {
                if (!isPlayable(candidate)) {
                    if (explicit == "atmos" && candidate.groupId.contains("atmos", true)) unsupportedAtmos = true
                    continue
                }
                try {
                    return prepareCandidate(candidate, adamId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    if (!candidate.groupId.contains("alac", true)) throw error
                }
            }
            if (unsupportedAtmos || explicit == "atmos" && choices.any { it.groupId.contains("atmos", true) }) {
                throw IOException("该设备不能解码杜比全景声")
            }
            throw IOException("该曲目没有所选音质")
        }
        val ordered = selectOrder(variants, requestedProfile)
        var unsupportedAtmos = false
        for (candidate in ordered) {
            if (!isPlayable(candidate)) {
                if (candidate.groupId.contains("atmos", true)) unsupportedAtmos = true
                continue
            }
            try {
                return prepareCandidate(candidate, adamId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (candidate.groupId.contains("alac", true)) continue
                throw error
            }
        }
        if (unsupportedAtmos) throw IOException("该设备不能解码杜比全景声")
        throw IOException("Apple 播放列表获取失败：HTTP 502")
    }

    private suspend fun prepareCandidate(variant: AppleVariant, adamId: String): AppleResolvedTrack {
        val url = requireHttps(variant.url)
        val text = getText(url, "Apple 播放列表获取失败")
        val media = parseMediaPlaylist(text, url, adamId)
        val rawInit = fetchRange(media, media.init, "Apple 分片获取失败")
        val widevineUri = media.segments.asSequence().mapNotNull { it.keyUri }.firstOrNull { it.startsWith("data:") }
        val isWidevine = variant.widevine || widevineUri != null
        if (isWidevine) {
            if (!android.media.MediaDrm.isCryptoSchemeSupported(androidx.media3.common.C.WIDEVINE_UUID)) {
                throw IOException("该设备没有 Widevine，不能播放这首仅 AAC-LC 的歌曲")
            }
            val original = widevineUri ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
            val pssh = appleWidevinePssh(original) ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
            return AppleResolvedTrack(variant.copy(widevine = true), media, rawInit, null, null, original, pssh)
        }
        val alacCookie = if (variant.groupId.contains("alac", true)) findAlacCookie(rawInit) else null
        val alacConfig = alacCookie?.let(::parseAlacConfig)
        if (variant.groupId.contains("alac", true)) {
            val cookie = alacCookie ?: throw IOException("该曲目没有所选音质")
            val config = alacConfig ?: throw IOException("该曲目没有所选音质")
            val decoder = runCatching { AlacDecoder.open(cookie) }.getOrDefault(0L)
            if (decoder == 0L) throw IOException("该曲目没有所选音质")
            try {
                val channelMask = when (config.channels) {
                    1 -> AudioFormat.CHANNEL_OUT_MONO
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    6 -> AudioFormat.CHANNEL_OUT_5POINT1
                    else -> AudioFormat.CHANNEL_INVALID
                }
                val sampleRate = variant.sampleRate.takeIf { it > 0 } ?: readU32(cookie, 20).toInt()
                if (channelMask == AudioFormat.CHANNEL_INVALID || sampleRate <= 0 ||
                    AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT) == AudioTrack.ERROR_BAD_VALUE) {
                    throw IOException("该曲目没有所选音质")
                }
            } finally {
                AlacDecoder.closeSafe(decoder)
            }
        }
        val patched = patchInit(rawInit)
        return AppleResolvedTrack(variant, media, patched, alacCookie, alacConfig, null, null)
    }

    suspend fun fetchInit(track: AppleResolvedTrack): ByteArray = track.initBytes.copyOf()

    suspend fun fetchSegment(
        track: AppleResolvedTrack,
        index: Int,
        priority: AppleSegmentPriority = AppleSegmentPriority.Playback,
    ): ByteArray {
        if (index !in track.media.segments.indices) throw IOException("Apple 分片获取失败：HTTP 416")
        ensureRangeHealthy(track.media.resourceUrl)
        if (!track.variant.groupId.contains("alac", true) || track.variant.widevine) {
            return produceSegment(track, index, AppleSegmentAdmission.ticket(priority))
        }
        val key = segmentKey(track, index)
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureRangeHealthy(track.media.resourceUrl)
            var producer = false
            var cached: ByteArray? = null
            val flight = synchronized(segmentLock) {
                cached = completedSegments[key]
                if (cached != null) null else inFlight[key] ?: run {
                    val selected = if (key in urgentSegments) AppleSegmentPriority.Playback else priority
                    AppleSegmentFlight(AppleSegmentAdmission.ticket(selected)).also {
                        inFlight[key] = it
                        producer = true
                    }
                }
            }
            cached?.let { ensureRangeHealthy(track.media.resourceUrl); return it }
            val pending = checkNotNull(flight)
            if (!producer) {
                if (priority == AppleSegmentPriority.Playback) AppleSegmentAdmission.promote(pending.ticket)
                try {
                    val bytes = pending.result.await()
                    ensureRangeHealthy(track.media.resourceUrl)
                    return bytes
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val bytes = produceSegment(track, index, pending.ticket)
                synchronized(segmentLock) {
                    if (retainSegments && bytes.size <= APPLE_ALAC_SEGMENT_BUFFER_BYTES) {
                        completedSegments[key] = bytes
                        completedSegmentBytes += bytes.size
                        val entries = completedSegments.entries.iterator()
                        while (completedSegments.size > 4 || completedSegmentBytes > APPLE_ALAC_SEGMENT_BUFFER_BYTES) {
                            completedSegmentBytes -= entries.next().value.size
                            entries.remove()
                        }
                    }
                }
                pending.result.complete(bytes)
                return bytes
            } catch (error: Throwable) {
                // Detach this producer before waking surviving consumers; they may register a replacement.
                synchronized(segmentLock) { if (inFlight[key] === pending) inFlight.remove(key) }
                pending.result.completeExceptionally(error)
                throw error
            } finally {
                synchronized(segmentLock) { if (inFlight[key] === pending) inFlight.remove(key) }
            }
        }
    }

    internal fun prioritizeSegment(track: AppleResolvedTrack, index: Int) {
        val key = segmentKey(track, index)
        val flight = synchronized(segmentLock) {
            urgentSegments.add(key)
            inFlight[key]
        }
        // Never nest segmentLock with the admission lock.
        flight?.let { AppleSegmentAdmission.promote(it.ticket) }
    }

    internal fun releaseSegmentPriority(track: AppleResolvedTrack, index: Int) {
        val key = segmentKey(track, index)
        synchronized(segmentLock) { urgentSegments.remove(key) }
    }

    private fun segmentKey(track: AppleResolvedTrack, index: Int): AppleSegmentKey {
        val segment = track.media.segments.getOrNull(index) ?: throw IOException("Apple 分片获取失败：HTTP 416")
        return AppleSegmentKey(track.media.resourceUrl, track.media.adamId, track.variant.groupId,
            segment.range, segment.keyKind, segment.keyUri)
    }

    private suspend fun produceSegment(track: AppleResolvedTrack, index: Int, ticket: AppleSegmentTicket): ByteArray {
        var status = "failed"
        var size = 0
        try {
            AppleSegmentAdmission.acquire(ticket)
            AppleSegmentAdmission.trace(ticket, track, index, "start", 0, null)
            val bytes = fetchSegmentUncached(track, index, ticket.job)
            ensureRangeHealthy(track.media.resourceUrl)
            size = bytes.size
            status = "ready"
            return bytes
        } catch (cancelled: CancellationException) {
            status = "cancelled"
            throw cancelled
        } finally {
            AppleSegmentAdmission.release(ticket)
            AppleSegmentAdmission.trace(ticket, track, index, "end", size, status)
        }
    }

    internal fun segmentLookahead(track: AppleResolvedTrack, legacyLookahead: Int): Int = synchronized(rangeLock) {
        when (rangeIdentities[track.media.resourceUrl]?.mode ?: AppleRangeMode.Unknown) {
            AppleRangeMode.Verified -> AUDIO_LANES
            AppleRangeMode.Unknown, AppleRangeMode.Serial -> legacyLookahead
            AppleRangeMode.Failed -> throw IOException("Apple 分片获取失败：HTTP 502")
        }
    }

    internal fun segmentRangeMode(track: AppleResolvedTrack): String = synchronized(rangeLock) {
        (rangeIdentities[track.media.resourceUrl]?.mode ?: AppleRangeMode.Unknown).name
    }

    private fun ensureRangeHealthy(resource: HttpUrl) = synchronized(rangeLock) {
        if (rangeIdentities[resource]?.mode == AppleRangeMode.Failed) throw IOException("Apple 分片获取失败：HTTP 502")
    }

    private suspend fun fetchSegmentUncached(track: AppleResolvedTrack, index: Int, job: Long): ByteArray {
        val segment = track.media.segments.getOrNull(index) ?: throw IOException("Apple 分片获取失败：HTTP 416")
        val bytes = fetchRange(track.media, segment.range, "Apple 分片获取失败", segment = true, job = job, index = index)
        if (segment.keyKind == KeyKind.WIDEVINE) return bytes
        val handle = when (segment.keyKind) {
            KeyKind.PREFETCH -> prefetchHandle()
            KeyKind.TRACK -> templateHandle(track.media.adamId, segment.keyUri!!)
            else -> 0L
        }
        val samples = scanFragment(bytes, handle != 0L)
        for (sample in samples) {
            if (sample.start < 0 || sample.end > bytes.size || sample.end < sample.start) {
                throw IOException("Apple 分片获取失败：HTTP 502")
            }
            if (handle != 0L) {
                val encrypted = bytes.copyOfRange(sample.start, sample.end)
                val plain = Temari.decryptSafe(handle, encrypted)
                if (plain.size != encrypted.size) throw IOException("Apple 解密模板获取失败：HTTP 502")
                plain.copyInto(bytes, sample.start)
            }
            track.alacConfig?.let { repairAlacEnd(bytes, sample, it) }
        }
        return bytes
    }
    internal fun sampleRanges(fragment: ByteArray): List<AppleSampleRange> = scanFragment(fragment, sanitizeEncryption = false)


    fun generatedPlaylist(track: AppleResolvedTrack, requestId: Long): ByteArray {
        val base = "apple-native://$requestId"
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:")
            append(ceil((track.media.segments.maxOfOrNull { it.durationMs } ?: 1L) / 1000.0).toInt().coerceAtLeast(1))
            append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-MAP:URI=\"").append(base).append("/init\"\n")
            for (index in track.media.segments.indices) {
                append("#EXTINF:").append(track.media.segments[index].durationMs / 1000.0).append(",\n")
                append(base).append("/segment/").append(index).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }.toByteArray(Charsets.UTF_8)
    }
    fun generatedWidevinePlaylist(track: AppleResolvedTrack): ByteArray {
        val keyUri = track.widevineUri ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
        val pssh = track.widevinePssh ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
        val psshUri = "data:text/plain;base64," + Base64.encodeToString(pssh, Base64.NO_WRAP)
        val output = StringBuilder(track.media.sourceText.length + psshUri.length)
        for (raw in track.media.sourceText.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    if (attrs["URI"] == keyUri) {
                        output.append("#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"").append(psshUri)
                            .append("\",KEYFORMAT=\"").append(WIDEVINE_KEYFORMAT).append("\",KEYFORMATVERSIONS=\"1\"")
                        attrs["IV"]?.let { output.append(",IV=").append(it) }
                        output.append('\n')
                    } else output.append(raw).append('\n')
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    output.append(rewriteUriAttribute(raw, track.media.playlistUrl)).append('\n')
                }
                line.isNotEmpty() && !line.startsWith('#') -> {
                    val resolved = track.media.playlistUrl.resolve(line) ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
                    if (resolved.scheme != "https") throw IOException("Apple 媒体地址无效")
                    output.append(resolved).append('\n')
                }
                else -> output.append(raw).append('\n')
            }
        }
        return output.toString().toByteArray(Charsets.UTF_8)
    }

    private fun rewriteUriAttribute(line: String, base: HttpUrl): String {
        val match = Regex("URI=\\\"([^\\\"]+)\\\"").find(line) ?: return line
        val resolved = base.resolve(match.groupValues[1]) ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
        if (resolved.scheme != "https") throw IOException("Apple 媒体地址无效")
        return line.replaceRange(match.range, "URI=\"$resolved\"")
    }


    private suspend fun isPlayable(variant: AppleVariant): Boolean {
        if (variant.widevine) return android.media.MediaDrm.isCryptoSchemeSupported(androidx.media3.common.C.WIDEVINE_UUID)
        val group = variant.groupId.lowercase()
        val codec = variant.codecs.lowercase()
        if (group.contains("alac") || codec.contains("alac")) {
            // The init cookie is validated after fetching the selected candidate; the decoder also probes here when possible.
            return true
        }
        if (group.contains("atmos") || codec.contains("ec-3") || codec.contains("ec3")) {
            return hasEac3Decoder(variant)
        }
        return codec.contains("mp4a") || group.contains("stereo") || group.contains("binaural") || group.contains("downmix")
    }

    private fun hasEac3Decoder(variant: AppleVariant): Boolean = listOf(MimeTypes.AUDIO_E_AC3, "audio/eac3-joc").any { mime ->
        val format = Format.Builder().setSampleMimeType(mime)
            .setChannelCount(variant.channels?.substringBefore('/')?.toIntOrNull() ?: 2)
            .setSampleRate(variant.sampleRate.takeIf { it > 0 } ?: 48_000)
            .build()
        runCatching {
            MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
                .any { it.isFormatSupported(format) }
        }.getOrDefault(false)
    }

    private fun selectOrder(variants: List<AppleVariant>, profile: ApplePlaybackProfile): List<AppleVariant> {
        val categories = ArrayList<Pair<String, Int>>()
        if (profile.quality != "efficient" && profile.spatial == "always") categories += "atmos" to 0
        when (profile.quality) {
            "hires" -> categories += listOf("alac" to 192000, "aac" to 0, "he" to 0)
            "lossless" -> categories += listOf("alac" to 48000, "aac" to 0, "he" to 0)
            "high" -> categories += listOf("aac" to 0, "he" to 0, "alac" to 48000)
            "efficient" -> categories += listOf("he" to 0, "aac" to 0, "alac" to 48000)
            else -> categories += listOf("alac" to 192000, "aac" to 0, "he" to 0)
        }
        val ordered = LinkedHashSet<AppleVariant>()
        for ((kind, maxRate) in categories) {
            variants.asSequence()
                .filter { variantKind(it) == kind && (maxRate == 0 || it.sampleRate == 0 || it.sampleRate <= maxRate) }
                .sortedWith(variantComparator(kind))
                .forEach(ordered::add)
        }
        variants.asSequence().filterNot(ordered::contains).sortedByDescending { it.bandwidth }.forEach(ordered::add)
        return ordered.toList()
    }

    private fun matchesWanted(variant: AppleVariant, wanted: String): Boolean {
        val group = variant.groupId.lowercase()
        val alac = group.contains("alac")
        val atmos = group.contains("atmos") && !group.contains("downmix") && !group.contains("binaural")
        val binaural = group.contains("binaural")
        val downmix = group.contains("downmix")
        val he = group.contains("he-")
        val capped = group.contains("stereo-128") || group.contains("binaural-128") || group.contains("downmix-128")
        return when (wanted) {
            "alac" -> alac
            "atmos" -> atmos
            "aac" -> !alac && !atmos && !binaural && !downmix && !he && !capped
            "aac-128" -> capped && !alac && !binaural && !downmix && !he
            "aac-he" -> he && !binaural && !downmix
            "aac-binaural" -> binaural && !capped && !he
            "aac-binaural-128" -> binaural && capped && !he
            "he-binaural" -> binaural && he
            "aac-downmix" -> downmix && !capped && !he
            "aac-downmix-128" -> downmix && capped && !he
            "he-downmix" -> downmix && he
            else -> false
        }
    }

    private fun variantComparator(kind: String) = compareByDescending<AppleVariant> {
        if ((kind == "alac" || it.groupId.contains("alac", true)) && it.sampleRate > 0) it.sampleRate.toLong() * 100 + it.bitDepth
        else it.bandwidth
    }

    private fun variantKind(variant: AppleVariant): String {
        val group = variant.groupId.lowercase()
        return when {
            group.contains("alac") -> "alac"
            group.contains("atmos") && !group.contains("downmix") -> "atmos"
            group.contains("binaural") || group.contains("downmix") -> "other"
            group.contains("he-") -> "he"
            else -> "aac"
        }
    }
    private suspend fun getJson(url: HttpUrl, errorPrefix: String): JSONObject {
        val text = getApiText(url, errorPrefix)
        return try { JSONObject(text) } catch (error: Exception) { throw IOException("$errorPrefix：HTTP 502", error) }
    }

    private suspend fun getApiText(url: HttpUrl, errorPrefix: String): String {
        val firstRequest = Request.Builder().url(url).get().build()
        val first = apiRequest(firstRequest)
        if (first.first == 403) {
            val retry = apiRequest(firstRequest.newBuilder().header("User-Agent", APPLE_USER_AGENT).build())
            if (retry.first !in 200..299) throw IOException("$errorPrefix：HTTP ${retry.first}")
            return retry.second
        }
        if (first.first !in 200..299) throw IOException("$errorPrefix：HTTP ${first.first}")
        return first.second
    }

    private suspend fun getText(url: HttpUrl, errorPrefix: String): String {
        val first = request(url, null)
        if (first.first == 403) {
            val retry = request(url, APPLE_USER_AGENT)
            if (retry.first !in 200..299) throw IOException("$errorPrefix：HTTP ${retry.first}")
            return retry.second
        }
        if (first.first !in 200..299) throw IOException("$errorPrefix：HTTP ${first.first}")
        return first.second
    }

    private suspend fun request(url: HttpUrl, userAgent: String?): Pair<Int, String> {
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("Apple 媒体地址无效")
        val request = Request.Builder().url(url).get().apply { if (userAgent != null) header("User-Agent", userAgent) }.build()
        return withContext(Dispatchers.IO) {
            apiRequest(request)
        }
    }

    private suspend fun apiRequest(request: Request): Pair<Int, String> {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(error))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { value ->
                        if (cont.isActive) {
                            val finalUrl = value.request.url
                            val initialWasHttps = request.url.scheme == "https"
                            if ((initialWasHttps && finalUrl.scheme != "https") || finalUrl.username.isNotEmpty() || finalUrl.password.isNotEmpty()) {
                                cont.resumeWith(Result.failure(IOException("Apple 媒体地址无效")))
                            } else {
                                try { cont.resumeWith(Result.success(value.code to (value.body?.string().orEmpty()))) }
                                catch (error: Exception) { cont.resumeWith(Result.failure(error)) }
                            }
                        }
                    }
                }
            })
        }
    }

    private suspend fun fetchRange(
        playlist: AppleMediaPlaylist,
        range: AppleByteRange,
        errorPrefix: String,
        segment: Boolean = false,
        job: Long = 0L,
        index: Int = -1,
    ): ByteArray {
        val length = range.length
        if (length <= 0 || length > Int.MAX_VALUE || range.start < 0) throw IOException("$errorPrefix：HTTP 416")
        var last: IOException? = null
        repeat(4) { attempt ->
            try {
                val builder = Request.Builder().url(playlist.resourceUrl)
                    .header("Range", "bytes=${range.start}-${range.end}").get()
                if (segment) {
                    val identity = synchronized(rangeLock) { rangeIdentities[playlist.resourceUrl] }
                    if (identity?.mode == AppleRangeMode.Failed) throw IOException("$errorPrefix：HTTP 502")
                    if (identity?.mode == AppleRangeMode.Verified) builder.header("If-Range", checkNotNull(identity.etag))
                    if (BuildConfig.DEBUG) builder.tag(AppleSegmentHttpTrace::class.java, AppleSegmentHttpTrace(job, index))
                }
                val req = builder.build()
                val first = requestBytes(req, range, segment)
                val reply = if (first.code == 403) {
                    requestBytes(req.newBuilder().header("User-Agent", APPLE_USER_AGENT).build(), range, segment)
                } else first
                if (reply.code != 200 && reply.code != 206) throw IOException("$errorPrefix：HTTP ${reply.code}")
                val result = when {
                    reply.code == 200 && reply.body.size.toLong() > length -> {
                        val end = range.end + 1
                        if (end > reply.body.size) throw IOException("$errorPrefix：HTTP 502")
                        reply.body.copyOfRange(range.start.toInt(), end.toInt())
                    }
                    else -> reply.body
                }
                if (result.size.toLong() != length) throw IOException("$errorPrefix：HTTP 502")
                if (segment) acceptRangeIdentity(playlist.resourceUrl, rangeIdentity(range, reply.code, reply.contentRange, reply.etag))
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                last = error
                if (attempt < 3) kotlinx.coroutines.delay(500L * (1L shl attempt))
            }
        }
        throw last ?: IOException("$errorPrefix：HTTP 502")
    }

    private suspend fun requestBytes(request: Request, range: AppleByteRange, segment: Boolean): AppleRangeReply = withContext(Dispatchers.IO) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, error: IOException) { if (cont.isActive) cont.resumeWith(Result.failure(error)) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { value ->
                        if (!cont.isActive) return@use
                        try {
                            val url = value.request.url
                            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                                throw IOException("Apple 媒体地址无效")
                            }
                            val contentRange = value.header("Content-Range")
                            val etag = value.header("ETag")
                            val bytes = if (value.code == 200 || value.code == 206) {
                                // Reject a changed verified entity before aggregating any response body.
                                if (segment) validateRangeIdentity(request.url, rangeIdentity(range, value.code, contentRange, etag))
                                val body = value.body ?: throw IOException("Apple 分片获取失败：HTTP 502")
                                if (value.code == 206) {
                                    val expected = range.length.toInt()
                                    if (body.contentLength() >= 0 && body.contentLength() != range.length) {
                                        throw IOException("Apple 分片获取失败：HTTP 502")
                                    }
                                    val bytes = ByteArray(expected)
                                    val input = body.byteStream()
                                    var offset = 0
                                    while (offset < expected) {
                                        if (!cont.isActive) return@use
                                        val count = input.read(bytes, offset, expected - offset)
                                        if (count <= 0) throw IOException("Apple 分片获取失败：HTTP 502")
                                        offset += count
                                    }
                                    if (input.read() != -1) throw IOException("Apple 分片获取失败：HTTP 502")
                                    bytes
                                } else body.bytes() // Preserve the existing whole-object 200 compatibility path.
                            } else ByteArray(0) // In particular, do not buffer 403/error bodies before retrying.
                            if (cont.isActive) cont.resumeWith(Result.success(AppleRangeReply(value.code, bytes, contentRange, etag)))
                        } catch (error: Exception) {
                            if (cont.isActive) cont.resumeWith(Result.failure(error))
                        }
                    }
                }
            })
        }
    }

    private fun rangeIdentity(range: AppleByteRange, code: Int, contentRange: String?, etag: String?): AppleRangeIdentity? {
        if (code != 206 || etag == null || !appleStrongTag.matches(etag)) return null
        val fields = appleContentRange.matchEntire(contentRange.orEmpty())?.groupValues ?: return null
        val start = fields[1].toLongOrNull() ?: return null
        val end = fields[2].toLongOrNull() ?: return null
        val total = fields[3].toLongOrNull() ?: return null
        if (start != range.start || end != range.end || total <= end) return null
        return AppleRangeIdentity(AppleRangeMode.Verified, total, etag)
    }

    private fun validateRangeIdentity(resource: HttpUrl, reply: AppleRangeIdentity?) = synchronized(rangeLock) {
        val current = rangeIdentities[resource] ?: return@synchronized
        if (current.mode == AppleRangeMode.Failed) throw IOException("Apple 分片获取失败：HTTP 502")
        if (current.mode == AppleRangeMode.Verified && current != reply) {
            rangeIdentities[resource] = AppleRangeIdentity(AppleRangeMode.Failed)
            throw IOException("Apple 分片获取失败：HTTP 502")
        }
    }

    private fun acceptRangeIdentity(resource: HttpUrl, reply: AppleRangeIdentity?) = synchronized(rangeLock) {
        val current = rangeIdentities[resource]
        when (current?.mode ?: AppleRangeMode.Unknown) {
            AppleRangeMode.Unknown -> rangeIdentities[resource] = reply ?: AppleRangeIdentity(AppleRangeMode.Serial)
            AppleRangeMode.Serial -> Unit
            AppleRangeMode.Failed -> throw IOException("Apple 分片获取失败：HTTP 502")
            AppleRangeMode.Verified -> if (current != reply) {
                rangeIdentities[resource] = AppleRangeIdentity(AppleRangeMode.Failed)
                throw IOException("Apple 分片获取失败：HTTP 502")
            }
        }
    }

    private suspend fun templateHandle(adamId: String, uri: String): Long = templateMutex.withLock {
        val key = "$adamId\n$uri"
        templateHandles[key]?.let { return@withLock it }
        val endpoint = server.newBuilder().addPathSegment("key").addQueryParameter("adamId", adamId).addQueryParameter("uri", uri).build()
        val payload = getApiText(endpoint, "Apple 解密模板获取失败")
        if (payload.isBlank()) throw IOException("Apple 解密模板获取失败：HTTP 200")
        val handle = Temari.openSafe(payload.toByteArray(Charsets.UTF_8))
        if (handle == 0L) throw IOException("Apple 解密模板获取失败：HTTP 502")
        templateHandles[key] = handle
        handle
    }

    private suspend fun prefetchHandle(): Long = templateMutex.withLock {
        prefetchTemplateHandle?.let { return@withLock it }
        val bytes = appContext.assets.open("apple/prefetch-template.json").use { it.readBytes() }
        val handle = Temari.openSafe(bytes)
        if (handle == 0L) throw IOException("Apple 解密模板获取失败：HTTP 502")
        prefetchTemplateHandle = handle
        handle
    }

    private fun requireHttps(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw IOException("Apple 媒体地址无效")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("Apple 媒体地址无效")
        return url
    }

    companion object {
        private const val PREFETCH_URI = "skd://itunes.apple.com/P000000000/s1/e1"
        private val APPLE_PROFILE_QUALITIES = setOf("hires", "lossless", "high", "efficient")
        private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
        private const val APPLE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val templateMutex = Mutex()
        private val templateHandles = ConcurrentHashMap<String, Long>()
        @Volatile private var prefetchTemplateHandle: Long? = null

        private fun parseMaster(text: String, base: HttpUrl): List<AppleVariant> {
            val groups = HashMap<String, Map<String, String>>()
            val variants = ArrayList<AppleVariant>()
            var pending: Map<String, String>? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA:") -> {
                        val attrs = parseAttributes(line.substringAfter(':'))
                        if (attrs["TYPE"] == "AUDIO" && attrs["GROUP-ID"] != null) groups[attrs.getValue("GROUP-ID")] = attrs
                    }
                    line.startsWith("#EXT-X-STREAM-INF:") -> pending = parseAttributes(line.substringAfter(':'))
                    line.isNotEmpty() && !line.startsWith('#') && pending != null -> {
                        val attrs = pending!!
                        pending = null
                        val group = attrs["AUDIO"].orEmpty()
                        val audio = groups[group]
                        val target = base.resolve(line) ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
                        if (target.scheme != "https") throw IOException("Apple 媒体地址无效")
                        variants += AppleVariant(target.toString(), group, attrs["CODECS"].orEmpty(),
                            (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull() ?: 0L,
                            audio?.get("CHANNELS"), audio?.get("SAMPLE-RATE")?.toIntOrNull() ?: 0,
                            audio?.get("BIT-DEPTH")?.toIntOrNull() ?: 0, attrs["QUALITY"])
                    }
                }
            }
            return variants
        }

        private fun parseAttributes(value: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            var start = 0
            var quote = false
            for (index in value.indices) {
                when (value[index]) {
                    '"' -> quote = !quote
                    ',' -> if (!quote) {
                        parseAttribute(value.substring(start, index), result)
                        start = index + 1
                    }
                }
            }
            parseAttribute(value.substring(start), result)
            return result
        }

        private fun parseAttribute(raw: String, into: MutableMap<String, String>) {
            val equals = raw.indexOf('=')
            if (equals <= 0) return
            val key = raw.substring(0, equals).trim()
            val value = raw.substring(equals + 1).trim().removeSurrounding("\"")
            into[key] = value
        }

        private fun parseMediaPlaylist(text: String, playlistUrl: HttpUrl, adamIdHint: String): AppleMediaPlaylist {
            var init: AppleByteRange? = null
            var resourceUrl: HttpUrl? = null
            var currentKey: String? = null
            var currentKeyKind: KeyKind? = null
            var duration = 0.0
            var pendingDuration: Double? = null
            var next = 0L
            var totalBytes = 0L
            val segments = ArrayList<AppleMediaSegment>()
            val fileName = playlistUrl.encodedPath.substringAfterLast('/')
            val adamId = Regex("_A(\\d+)[._]").find(fileName)?.groupValues?.get(1)
                ?: playlistUrl.fragment?.substringAfter("adamId=", "")?.takeIf { it.matches(Regex("\\d+")) }
                ?: adamIdHint
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-KEY:") -> {
                        val attrs = parseAttributes(line.substringAfter(':'))
                        val method = attrs["METHOD"]
                        val uri = attrs["URI"]
                        val format = attrs["KEYFORMAT"].orEmpty().lowercase()
                        if (uri != null && uri.startsWith("data:") && (format.isEmpty() || format != WIDEVINE_KEYFORMAT)) continue
                        if (method == "NONE" || uri.isNullOrEmpty()) {
                            currentKey = null
                            currentKeyKind = null
                        } else {
                            currentKey = uri
                            currentKeyKind = when {
                                uri == PREFETCH_URI -> KeyKind.PREFETCH
                                uri.startsWith("data:") -> KeyKind.WIDEVINE
                                uri.startsWith("skd://") -> KeyKind.TRACK
                                else -> throw IOException("Apple 播放列表获取失败：HTTP 502")
                            }
                        }
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val attrs = parseAttributes(line.substringAfter(':'))
                        val uri = attrs["URI"] ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
                        val range = attrs["BYTERANGE"] ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
                        resourceUrl = playlistUrl.resolve(uri) ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
                        if (resourceUrl?.scheme != "https") throw IOException("Apple 媒体地址无效")
                        val parsed = parseRange(range, 0L)
                        init = parsed
                        next = parsed.end + 1
                    }
                    line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        val range = parseRange(line.substringAfter(':'), next)
                        next = range.end + 1
                        val seconds = pendingDuration ?: 0.0
                        val ms = (seconds * 1000).toLong().coerceAtLeast(0)
                        segments += AppleMediaSegment(range, duration.toLong(), ms, currentKey, currentKeyKind)
                        duration += ms
                        totalBytes = maxOf(totalBytes, range.end + 1)
                        pendingDuration = null
                    }
                }
            }
            val initial = init ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
            val url = resourceUrl ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
            if (segments.isEmpty()) throw IOException("Apple 播放列表获取失败：HTTP 502")
            return AppleMediaPlaylist(playlistUrl, url, adamId, initial, segments, duration.toLong(),
                maxOf(totalBytes, initial.end + 1), text)
        }

        private fun parseRange(value: String, fallbackOffset: Long): AppleByteRange {
            val parts = value.trim().removeSurrounding("\"").split('@', limit = 2)
            val length = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("Apple 播放列表获取失败：HTTP 502")
            val start = if (parts.size > 1) parts[1].toLongOrNull() else fallbackOffset
            if (start == null || start < 0 || Long.MAX_VALUE - start < length) throw IOException("Apple 播放列表获取失败：HTTP 502")
            return AppleByteRange(start, start + length - 1)
        }

        private data class Box(val type: String, val start: Int, val body: Int, val end: Int)

        private fun boxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<Box> {
            val out = ArrayList<Box>()
            var pos = start
            val limit = end.coerceAtMost(bytes.size)
            while (pos >= 0 && pos + 8 <= limit) {
                val size32 = readU32(bytes, pos)
                val header: Int
                val size: Long
                when (size32) {
                    0L -> { size = (limit - pos).toLong(); header = 8 }
                    1L -> {
                        if (pos + 16 > limit) break
                        size = readU64(bytes, pos + 8)
                        header = 16
                    }
                    else -> { size = size32; header = 8 }
                }
                if (size < header || size > Int.MAX_VALUE || pos.toLong() + size > limit) break
                out += Box(String(bytes, pos + 4, 4, Charsets.ISO_8859_1), pos, pos + header, pos + size.toInt())
                pos += size.toInt()
            }
            return out
        }

        private fun patchInit(source: ByteArray): ByteArray {
            val bytes = source.copyOf()
            val rename = ArrayList<Pair<Int, String>>()
            val wipe = ArrayList<IntRange>()
            for (moov in boxes(bytes).filter { it.type == "moov" }) {
                for (child in boxes(bytes, moov.body, moov.end)) {
                    if (child.type == "pssh") rename += child.start to "free"
                    if (child.type != "trak") continue
                    val mdia = findChild(bytes, child, "mdia") ?: continue
                    val minf = findChild(bytes, mdia, "minf") ?: continue
                    val stbl = findChild(bytes, minf, "stbl") ?: continue
                    val stsd = findChild(bytes, stbl, "stsd") ?: continue
                    for (entry in boxes(bytes, stsd.body + 8, stsd.end)) {
                        val fields = when (entry.type) { "enca" -> 28; "encv" -> 78; else -> continue }
                        val sinf = boxes(bytes, entry.body + fields, entry.end).firstOrNull { it.type == "sinf" } ?: continue
                        val frma = boxes(bytes, sinf.body, sinf.end).firstOrNull { it.type == "frma" } ?: continue
                        if (frma.body + 4 > frma.end) continue
                        val original = String(bytes, frma.body, 4, Charsets.ISO_8859_1)
                        rename += entry.start to original
                        rename += sinf.start to "free"
                        if (sinf.body < sinf.end) wipe += sinf.body until sinf.end
                    }
                }
            }
            rename.forEach { (offset, type) -> type.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, offset + 4) }
            wipe.forEach { range -> bytes.fill(0, range.first, range.last + 1) }
            return bytes
        }

        private fun scanFragment(bytes: ByteArray, sanitizeEncryption: Boolean): List<AppleSampleRange> {
            val samples = ArrayList<AppleSampleRange>()
            val rename = ArrayList<Int>()
            var foundMoof = false
            for (moof in boxes(bytes).filter { it.type == "moof" }) {
                foundMoof = true
                for (child in boxes(bytes, moof.body, moof.end)) {
                    if (sanitizeEncryption && child.type == "pssh") rename += child.start
                    if (child.type != "traf") continue
                    val traf = child
                    var defaultSize: Long? = null
                    var next = moof.start.toLong()
                    for (box in boxes(bytes, traf.body, traf.end)) {
                        if (sanitizeEncryption && (box.type == "senc" || box.type == "saiz" || box.type == "saio" ||
                                (box.type == "sgpd" || box.type == "sbgp") && box.body + 8 <= box.end &&
                                (String(bytes, box.body + 4, 4, Charsets.ISO_8859_1) == "seig" || String(bytes, box.body + 4, 4, Charsets.ISO_8859_1) == "seam"))) {
                            rename += box.start
                        }
                        if (box.type == "tfhd") {
                            if (box.body + 8 > box.end) throw IOException("Apple 分片获取失败：HTTP 502")
                            val flags = readU24(bytes, box.body + 1)
                            var cursor = box.body + 8
                            if (flags and 1 != 0) throw IOException("Apple 分片获取失败：HTTP 502")
                            if (flags and 2 != 0) cursor += 4
                            if (flags and 8 != 0) cursor += 4
                            if (flags and 16 != 0) {
                                if (cursor + 4 > box.end) throw IOException("Apple 分片获取失败：HTTP 502")
                                defaultSize = readU32(bytes, cursor)
                            }
                        } else if (box.type == "trun") {
                            if (box.body + 8 > box.end) throw IOException("Apple 分片获取失败：HTTP 502")
                            val flags = readU24(bytes, box.body + 1)
                            val count = readU32(bytes, box.body + 4)
                            if (count > bytes.size) throw IOException("Apple 分片获取失败：HTTP 502")
                            var cursor = box.body + 8
                            var pos = next
                            if (flags and 1 != 0) {
                                if (cursor + 4 > box.end) throw IOException("Apple 分片获取失败：HTTP 502")
                                pos = moof.start.toLong() + readU32(bytes, cursor).toInt().toLong()
                                cursor += 4
                            }
                            if (flags and 4 != 0) cursor += 4
                            repeat(count.toInt()) {
                                if (flags and 0x100 != 0) cursor += 4
                                val size = if (flags and 0x200 != 0) {
                                    if (cursor + 4 > box.end) throw IOException("Apple 分片获取失败：HTTP 502")
                                    readU32(bytes, cursor).also { cursor += 4 }
                                } else defaultSize ?: throw IOException("Apple 分片获取失败：HTTP 502")
                                if (flags and 0x400 != 0) cursor += 4
                                if (flags and 0x800 != 0) cursor += 4
                                if (cursor > box.end || size > Int.MAX_VALUE || pos < 0 || pos + size > bytes.size) throw IOException("Apple 分片获取失败：HTTP 502")
                                samples += AppleSampleRange(pos.toInt(), (pos + size).toInt())
                                pos += size
                            }
                            next = pos
                        }
                    }
                }
            }
            rename.forEach { bytes[it + 4] = 'f'.code.toByte(); bytes[it + 5] = 'r'.code.toByte(); bytes[it + 6] = 'e'.code.toByte(); bytes[it + 7] = 'e'.code.toByte() }
            if (!foundMoof) throw IOException("Apple 分片获取失败：HTTP 502")
            val sorted = samples.sortedBy { it.start }
            var previous = 0
            for (sample in sorted) {
                if (sample.start < previous || sample.end > bytes.size) throw IOException("Apple 分片获取失败：HTTP 502")
                previous = sample.end
            }
            return sorted
        }

        private fun findChild(bytes: ByteArray, box: Box, type: String) = boxes(bytes, box.body, box.end).firstOrNull { it.type == type }
        private fun readU24(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xff) shl 16) or ((bytes[at + 1].toInt() and 0xff) shl 8) or (bytes[at + 2].toInt() and 0xff)
        private fun readU32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        private fun readU64(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.BIG_ENDIAN).long

        private fun findAlacCookie(init: ByteArray): ByteArray? {
            for (moov in boxes(init).filter { it.type == "moov" }) for (trak in boxes(init, moov.body, moov.end).filter { it.type == "trak" }) {
                val mdia = findChild(init, trak, "mdia") ?: continue
                val minf = findChild(init, mdia, "minf") ?: continue
                val stbl = findChild(init, minf, "stbl") ?: continue
                val stsd = findChild(init, stbl, "stsd") ?: continue
                for (entry in boxes(init, stsd.body + 8, stsd.end)) {
                    if (entry.type !in setOf("alac", "enca")) continue
                    val alac = boxes(init, entry.body + 28, entry.end).firstOrNull { it.type == "alac" } ?: continue
                    if (alac.end - alac.body == 28 && alac.body + 28 <= alac.end) return init.copyOfRange(alac.body + 4, alac.end)
                }
            }
            return null
        }

        private fun parseAlacConfig(cookie: ByteArray): AppleAlacConfig? {
            if (cookie.size != 24 || cookie[4] != 0.toByte()) return null
            val maxBlock = readU32(cookie, 0).toInt()
            val bits = cookie[5].toInt() and 0xff
            val channels = cookie[9].toInt() and 0xff
            return AppleAlacConfig(channels, bits, maxBlock).takeIf { channels in 1..8 && bits in setOf(16, 20, 24, 32) && maxBlock in 1..65535 }
        }

        private fun repairAlacEnd(bytes: ByteArray, range: AppleSampleRange, config: AppleAlacConfig) {
            if (config.channels !in 1..2 || range.size < 3) return
            val packet = bytes.copyOfRange(range.start, range.end)
            val header = ((packet[0].toInt() and 0xff) shl 16) or ((packet[1].toInt() and 0xff) shl 8) or (packet[2].toInt() and 0xff)
            val element = header ushr 21
            if (!((element == 0 || element == 3) && config.channels == 1 || element == 1 && config.channels == 2) ||
                (header ushr 5 and 0xfff) != 0 || (header ushr 2 and 3) != 0 || (header ushr 1 and 1) != 1) return
            val partial = header ushr 4 and 1 != 0
            if (partial && packet.size < 7) return
            val samplesLong = if (partial) {
                val upper = ((packet[2].toLong() and 0xff) shl 24) or ((packet[3].toLong() and 0xff) shl 16) or
                    ((packet[4].toLong() and 0xff) shl 8) or (packet[5].toLong() and 0xff)
                ((upper shl 7) or ((packet[6].toLong() and 0xff) ushr 1))
            } else config.maxBlock.toLong()
            if (samplesLong !in 1L..config.maxBlock.toLong()) return
            val samples = samplesLong.toInt()
            val endBit = 23L + (if (partial) 32 else 0) + samples.toLong() * config.channels * config.bits
            val totalBits = packet.size.toLong() * 8
            if (totalBits - endBit !in 0..10 || endBit + 3 > totalBits) return
            var alreadyEnd = true
            for (bit in endBit until endBit + 3) if (packet[(bit / 8).toInt()].toInt() and (0x80 ushr (bit % 8).toInt()) == 0) alreadyEnd = false
            if (alreadyEnd) return
            for (bit in endBit until endBit + 3) packet[(bit / 8).toInt()] = (packet[(bit / 8).toInt()].toInt() or (0x80 ushr (bit % 8).toInt())).toByte()
            for (bit in endBit + 3 until totalBits) packet[(bit / 8).toInt()] = (packet[(bit / 8).toInt()].toInt() and (0x80 ushr (bit % 8).toInt()).inv()).toByte()
            packet.copyInto(bytes, range.start)
        }

        internal fun appleWidevinePssh(uri: String): ByteArray? {
            val comma = uri.indexOf(',')
            if (!uri.startsWith("data:") || comma < 0) return null
            val encoded = uri.substring(comma + 1)
            val raw = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                ?: runCatching { Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
                ?: return null
            val systemId = byteArrayOf(0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(), 0x79, 0xd6.toByte(), 0x4a, 0xce.toByte(), 0xa3.toByte(), 0xc8.toByte(), 0x27, 0xdc.toByte(), 0xd5.toByte(), 0x1d, 0x21, 0xed.toByte())
            if (raw.size >= 32 && readU32(raw, 0) == raw.size.toLong() && String(raw, 4, 4, Charsets.US_ASCII) == "pssh" &&
                raw.copyOfRange(12, 28).contentEquals(systemId)) return raw
            val kid = when {
                raw.size == 16 -> raw
                raw.size == 36 && raw[8] == '-'.code.toByte() -> raw.toString(Charsets.US_ASCII).replace("-", "").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray().takeIf { it.size == 16 }
                else -> null
            } ?: return null
            val size = 4 + 4 + 4 + 16 + 4 + 16 + 4
            return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).putInt(size).put("pssh".toByteArray(Charsets.US_ASCII))
                .putInt(0x01000000).put(systemId).putInt(1).put(kid).putInt(0).array()
        }
    }
}

/** JNI bridge; library load failure is logged once and never falls back to a WebView. */
internal object Temari {
    private val available: Boolean = try {
        System.loadLibrary("fantastic_temari")
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.e("FantasticPlayback", "Temari native library unavailable", error)
        false
    }

    external fun open(json: ByteArray): Long
    external fun decrypt(handle: Long, sample: ByteArray): ByteArray
    external fun close(handle: Long)

    fun openSafe(json: ByteArray): Long = if (available) runCatching { open(json) }.getOrDefault(0L) else 0L
    fun decryptSafe(handle: Long, sample: ByteArray): ByteArray =
        if (available) runCatching { decrypt(handle, sample) }.getOrDefault(ByteArray(0)) else ByteArray(0)
    fun closeSafe(handle: Long) { if (available && handle != 0L) runCatching { close(handle) } }
}

/** ALAC JNI bridge; decoder initialization failures never change the selected rendition. */
internal object AlacDecoder {
    private val available: Boolean = try {
        System.loadLibrary("fantastic_alac")
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.e("FantasticPlayback", "ALAC native library unavailable", error)
        false
    }

    external fun open(cookie: ByteArray): Long
    external fun decode(handle: Long, packet: ByteArray): ByteArray
    external fun close(handle: Long)
    external fun channels(handle: Long): Int
    external fun sampleRate(handle: Long): Int
    external fun bits(handle: Long): Int
    fun openSafe(cookie: ByteArray): Long = if (available) runCatching { open(cookie) }.getOrDefault(0L) else 0L
    fun decodeSafe(handle: Long, packet: ByteArray): ByteArray =
        if (available) runCatching { decode(handle, packet) }.getOrDefault(ByteArray(0)) else ByteArray(0)
    fun closeSafe(handle: Long) { if (available && handle != 0L) runCatching { close(handle) } }
}
