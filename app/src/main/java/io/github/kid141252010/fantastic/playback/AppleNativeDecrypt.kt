package io.github.kid141252010.fantastic.playback

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaDrm
import android.net.Uri
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.mp4.PsshAtomUtil
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.SourceStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

internal data class AppleNativeTrack(
    val adamId: String,
    val variant: AppleNativeVariant,
    val mediaUrl: HttpUrl,
    val playlist: AppleNativePlaylist,
    val initBytes: ByteArray,
    val alacCookie: ByteArray?,
    val alacFrameLength: Int,
    val alacChannels: Int,
    val alacBits: Int,
    val widevineManifest: ByteArray? = null,
    val audioQualityNotice: String? = null,
    val outputRateCap: Int = Int.MAX_VALUE,
) {
    val widevine: Boolean get() = widevineManifest != null

    // Keep key requests on the server that resolved this track, even if prefs change.
    internal var templateServer: HttpUrl? = null
}

internal data class AppleNativeVariant(
    val url: HttpUrl,
    val groupId: String,
    val codecs: String,
    val bandwidth: Long,
    val channels: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val quality: String? = null,
)

internal data class AppleNativeInit(
    val bytes: ByteArray,
    val alacCookie: ByteArray?,
    val alacFrameLength: Int,
    val alacChannels: Int,
    val alacBits: Int,
)

internal data class AppleNativeSegment(
    val uri: HttpUrl,
    val rangeStart: Long,
    val rangeLength: Long,
    val durationMs: Long,
    val keyUri: String?,
)

internal data class AppleNativePlaylist(
    val init: AppleNativeSegment?,
    val segments: List<AppleNativeSegment>,
    val targetDurationMs: Long,
    val totalDurationMs: Long,
)

internal data class AppleNativeFetchedSegment(
    val bytes: ByteArray,
    val sampleRanges: List<IntRange>,
    val durationMs: Long,
)

@androidx.annotation.OptIn(UnstableApi::class)
internal class AppleNativeDecrypt(
    private val api: FantasticApi,
    private val assetLoader: (String) -> ByteArray,
) {
    private val cdn = api.client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
    private val ownedTemplates = HashSet<TemplateKey>()
    @Volatile private var closed = false

    suspend fun resolve(
        server: String,
        adamId: String,
        requestedQuality: String?,
        profile: ApplePlaybackProfile,
        explicit: Boolean = requestedQuality != null,
    ): AppleNativeTrack {
        val base = applePreparation("resolve") { appleServer(server) }
        val master = applePreparation("resolve") {
            val song = api.client.appleGet(base.endpoint("parse", "song", adamId), PLAYLIST_ERROR) { it.string() }
            val masterUrl = appleData(song, PLAYLIST_ERROR).requiredString("masterUrl", PLAYLIST_ERROR)
            masterUrl.toHttpUrlOrNull() ?: throw IOException("Apple 播放列表地址无效")
        }
        val widevine = !master.encodedPath.endsWith(".m3u8", ignoreCase = true)
        val temariAvailable = Temari.isAvailable()
        val alacAvailable = AlacDecoder.isAvailable()
        Log.i("FantasticPlayback", "apple-resolve id=${adamId.appleLogToken()} requestedQuality=${requestedQuality?.appleLogToken() ?: "auto"} " +
            "network=${profile.network.appleLogToken()} profile=${profile.quality.appleLogToken()} spatial=${profile.spatial.appleLogToken()} explicit=$explicit " +
            "widevine=$widevine temari=$temariAvailable alac=$alacAvailable")
        val wanted = requestedQuality?.takeIf { explicit }
        val requestsLossless = profile.requestsLossless(wanted)
        val automaticLossless = !explicit && requestsLossless
        if (widevine) {
            return applePreparation("widevine_prepare") {
                if (wanted != null && wanted != "aac" && wanted != "audio-stereo-256") {
                    throw ApplePreparationException("widevine_quality", "aac_only", "该曲目仅提供 AAC-LC，没有所选音质")
                }
                if (!MediaDrm.isCryptoSchemeSupported(AppleNativeWidevine.WIDEVINE_UUID)) {
                    throw ApplePreparationException("widevine_capability", "widevine_unavailable", "该设备没有 Widevine，不能播放这首仅 AAC-LC 的歌曲")
                }
                val text = api.client.appleGet(base.endpoint("mv", "webplayback", adamId), PLAYLIST_ERROR) { it.string() }
                val rawUrl = appleData(text, PLAYLIST_ERROR).requiredString("m3u8", PLAYLIST_ERROR)
                val url = httpsUrl(rawUrl.toHttpUrlOrNull()).newBuilder().fragment("adamId=$adamId").build()
                val variant = AppleNativeVariant(url, "audio-stereo-256", "mp4a.40.2", 256_000, 2, 0, 0)
                logCandidate(adamId, variant, null)
                if (automaticLossless) {
                    prepareLosslessFallback(base, adamId, variant, Int.MAX_VALUE, true, "widevine_quality", "aac_only")
                } else {
                    prepareVariant(base, adamId, variant, Int.MAX_VALUE, widevine = true)
                }
            }
        }

        if (!temariAvailable) {
            throw ApplePreparationException("fairplay_capability", "temari_unavailable", "Apple FairPlay 解密组件不可用")
        }
        val variants = applePreparation("master_playlist") {
            val (url, text) = fetchCdnPlaylist(master, "主播放列表")
            parseMaster(text, url)
        }
        val candidates = if (wanted != null) explicitCandidates(variants, wanted) else profileCandidates(variants, profile)
        var firstAlacFailure: ApplePreparationException? = null
        for ((index, candidate) in candidates.withIndex()) {
            val (variant, maxRate) = candidate
            val codec = variant.audioCodec()
            val unavailable = when {
                codec == AudioCodec.OTHER -> ApplePreparationException("codec_capability", "unknown_codec", "Apple 音频编码不受支持")
                codec == AudioCodec.ALAC && !alacAvailable -> ApplePreparationException("alac_capability", "alac_unavailable", "Apple ALAC 解码组件不可用")
                codec == AudioCodec.EAC3 && !supportsEac3(variant) -> ApplePreparationException("eac3_capability", "eac3_unavailable", "该设备不能解码杜比全景声")
                else -> null
            }
            logCandidate(adamId, variant, unavailable?.reason)
            try {
                if (unavailable != null) throw unavailable
                if (automaticLossless && (codec == AudioCodec.AAC || codec == AudioCodec.HE)) {
                    val reason = firstAlacFailure?.reason ?: missingAlacReason(variants)
                    val stage = firstAlacFailure?.stage ?: qualitySelectionStage(reason)
                    if (firstAlacFailure != null) throw firstAlacFailure
                    return prepareLosslessFallback(base, adamId, variant, maxRate, false, stage, reason)
                }
                return prepareVariant(base, adamId, variant, maxRate, widevine = false)
            } catch (failure: ApplePreparationException) {
                if (codec == AudioCodec.ALAC && firstAlacFailure == null) firstAlacFailure = failure
                val next = candidates.getOrNull(index + 1)?.first
                if (automaticLossless && codec != AudioCodec.ALAC && firstAlacFailure != null) throw firstAlacFailure
                if (explicit || next == null || codec != AudioCodec.ALAC && unavailable == null) throw failure
                Log.i("FantasticPlayback", "apple-fallback id=${adamId.appleLogToken()} from=${variant.groupId.appleLogToken()} " +
                    "to=${next.groupId.appleLogToken()} stage=${failure.stage.appleLogToken()} reason=${failure.reason.appleLogToken()}")
            }
        }
        val reason = if (requestsLossless) missingAlacReason(variants) else "no_candidate"
        val stage = qualitySelectionStage(reason)
        throw ApplePreparationException(stage, reason, applePlaybackFailureText(stage, reason))
    }

    private suspend fun prepareLosslessFallback(
        base: HttpUrl,
        adamId: String,
        variant: AppleNativeVariant,
        maxRate: Int,
        widevine: Boolean,
        stage: String,
        reason: String,
    ): AppleNativeTrack {
        val codec = if (variant.qualityKind() == QualityKind.HE) "HE-AAC" else "AAC"
        val notice = "已回退 $codec（${applePlaybackFailureText(stage, reason)}）"
        return prepareVariant(base, adamId, variant, maxRate, widevine, audioQualityNotice = notice).also {
            Log.i("FantasticPlayback", "apple-lossless-fallback id=${adamId.appleLogToken()} group=${variant.groupId.appleLogToken()} " +
                "stage=${stage.appleLogToken()} reason=${reason.appleLogToken()}")
        }
    }

    private suspend fun prepareVariant(
        base: HttpUrl,
        adamId: String,
        variant: AppleNativeVariant,
        maxRate: Int,
        widevine: Boolean,
        audioQualityNotice: String? = null,
    ): AppleNativeTrack {
        val (mediaUrl, text) = applePreparation("media_playlist") { fetchCdnPlaylist(variant.url, "媒体播放列表") }
        val media = applePreparation("media_playlist") { parseMedia(text, mediaUrl, widevine) }
        val rawInitBytes = applePreparation("init_segment") {
            val initSegment = media.init ?: throw IOException("Apple 播放列表缺少初始化分片")
            fetchRange(initSegment)
        }
        val alac = variant.audioCodec() == AudioCodec.ALAC
        val init = if (alac) {
            applePreparation("alac_cookie") { AppleMp4(rawInitBytes).alacInit() }
        } else AppleNativeInit(rawInitBytes, null, 0, 0, 0)
        val selected = if (alac) {
            val rate = applePreparation("alac_capability") { playableAlacRate(init) }
            variant.copy(sampleRate = rate, bitDepth = init.alacBits, channels = init.alacChannels)
        } else variant
        val outputRateCap = if (alac && selected.sampleRate > maxRate) maxRate else Int.MAX_VALUE
        return applePreparation(if (widevine) "widevine_manifest" else "init_container") {
            val rewrittenInitBytes = rawInitBytes.copyOf().also {
                if (!widevine) AppleMp4(it).clearInitBoxes()
            }

            val mediaAdamId = ADAM_ID_IN_FILE.find(mediaUrl.pathSegments.lastOrNull().orEmpty())?.groupValues?.get(1)
                ?: mediaUrl.fragment?.split('&')?.firstOrNull { it.startsWith("adamId=") }?.substringAfter('=')
                ?: variant.url.fragment?.takeIf { it.startsWith("adamId=") }?.substringAfter('=')
                ?: adamId
            AppleNativeTrack(
                mediaAdamId, selected, mediaUrl, media, rewrittenInitBytes, init.alacCookie,
                init.alacFrameLength, init.alacChannels, init.alacBits,
                widevineManifest = if (widevine) media3WidevineManifest(text) else null,
                audioQualityNotice = audioQualityNotice,
                outputRateCap = outputRateCap,
            ).also { it.templateServer = base }
        }
    }

    private fun logCandidate(adamId: String, variant: AppleNativeVariant, skip: String?) {
        Log.i("FantasticPlayback", "apple-candidate id=${adamId.appleLogToken()} group=${variant.groupId.appleLogToken()} " +
            "codecs=${variant.codecs.appleLogToken()} category=${variant.audioCodec().name.lowercase()} " +
            "action=${if (skip == null) "attempt" else "skip"} reason=${skip ?: "selected_order"}")
    }

    suspend fun fetchPlaylist(uri: HttpUrl, kind: String = "播放列表"): String = fetchCdnPlaylist(uri, kind).second

    private suspend fun fetchCdnPlaylist(uri: HttpUrl, kind: String): Pair<HttpUrl, String> {
        val request = Request.Builder().url(httpsUrl(uri)).build()
        return requestCdn(request, PLAYLIST_ERROR) { response ->
            val text = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Apple ${kind}为空")
            response.request.url to text
        }
    }

    suspend fun fetchSegment(track: AppleNativeTrack, segment: AppleNativeSegment): AppleNativeFetchedSegment {
        val bytes = applePreparation("segment_fetch") { fetchRange(segment) }
        val fragment = applePreparation("segment_container") { AppleMp4(bytes) }
        val samples = applePreparation("segment_container") { fragment.sampleRanges() }
        if (!track.widevine) {
            val context = currentCoroutineContext()
            val keyUri = segment.keyUri
            if (keyUri != null) {
                if (!keyUri.startsWith("skd://")) throw IOException("Apple 分片密钥格式不受支持")
                val server = track.templateServer ?: appleServer(api.url(""))
                val prefetch = isPrefetchKey(keyUri)
                val primary = if (prefetch) TemplateKey(null, "", PREFETCH_URI) else TemplateKey(server, track.adamId, keyUri)
                val alternate = if (prefetch) TemplateKey(server, track.adamId, keyUri) else TemplateKey(null, "", PREFETCH_URI)
                val encrypted = bytes.copyOf()
                var plain = decryptSamples(primary, encrypted, samples, context)
                val channels = track.alacChannels
                if (channels in 1..2 && samples.isNotEmpty() && !looksLikeAlac(plain, samples.first().first, channels)) {
                    val fallback = runCatching { decryptSamples(alternate, encrypted, samples, context) }.getOrNull()
                    if (fallback != null && looksLikeAlac(fallback, samples.first().first, channels)) {
                        Log.i("FantasticPlayback", "apple-decrypt-retry id=${track.adamId.appleLogToken()} prefetch=$prefetch")
                        plain = fallback
                    }
                }
                plain.copyInto(bytes)
            }
            if (track.alacCookie != null) {
                for (range in samples) {
                    repairPacket(bytes, range.first, range.last + 1 - range.first, track.alacFrameLength, track.alacChannels, track.alacBits)
                }
            }
            applePreparation("segment_container") { fragment.clearFragmentBoxes() }
        }
        return AppleNativeFetchedSegment(bytes, samples, segment.durationMs)
    }

    private suspend fun decryptSamples(
        key: TemplateKey,
        encrypted: ByteArray,
        samples: List<IntRange>,
        context: CoroutineContext,
    ): ByteArray {
        val template = applePreparation("fairplay_template") { acquireTemplate(key) }
        val out = encrypted.copyOf()
        synchronized(template) {
            if (closed) throw IOException("Apple 解密模板已关闭")
            for (range in samples) {
                context.ensureActive()
                val end = range.last + 1
                val clear = Temari.decrypt(template.handle, out.copyOfRange(range.first, end))
                if (clear.size != end - range.first) {
                    throw ApplePreparationException("fairplay_decrypt", "length_mismatch", "Apple 分片解密失败")
                }
                clear.copyInto(out, range.first)
            }
        }
        return out
    }

    // These operations preserve size and update the owned network buffer in place.
    fun rewriteInit(input: ByteArray): ByteArray = input.also { AppleMp4(it).clearInitBoxes() }

    fun rewriteFragment(input: ByteArray, sampleRanges: List<IntRange>): ByteArray = input.also {
        val fragment = AppleMp4(it)
        fragment.validateSamples(sampleRanges)
        fragment.clearFragmentBoxes()
    }

    fun extractAlacCookie(init: ByteArray): AppleNativeInit = AppleMp4(init).alacInit()
    fun sampleDurations(fragment: ByteArray): List<Long> = AppleMp4(fragment).sampleDurations()


    fun close() = synchronized(templates) {
        closed = true
        for (key in ownedTemplates) {
            val template = templates.getValue(key)
            template.references--
            if (template.references == 0) {
                templates.remove(key)
                synchronized(template) { Temari.close(template.handle) }
            }
        }
        ownedTemplates.clear()
    }

    private suspend fun acquireTemplate(key: TemplateKey): SharedTemplate = templateRequests.withLock {
        synchronized(templates) {
            if (closed) throw IOException("Apple 解密模板已关闭")
            templates[key]?.let { template ->
                if (ownedTemplates.add(key)) template.references++
                return@withLock template
            }
        }
        if (!Temari.isAvailable()) throw IOException("Apple FairPlay 解密组件不可用")
        val json = if (key.server == null) {
            assetLoader("apple/prefetch-template.json")
        } else {
            val url = key.server.endpoint("key").newBuilder()
                .addQueryParameter("adamId", key.adamId)
                .addQueryParameter("uri", key.uri)
                .build()
            api.client.appleGet(url, TEMPLATE_ERROR) { it.bytes() }
        }
        currentCoroutineContext().ensureActive()
        synchronized(templates) {
            if (closed) throw IOException("Apple 解密模板已关闭")
            val handle = Temari.open(json)
            if (handle == 0L) throw IOException("Apple 解密模板无效")
            SharedTemplate(handle).also {
                templates[key] = it
                ownedTemplates.add(key)
            }
        }
    }

    private suspend fun fetchRange(segment: AppleNativeSegment): ByteArray {
        val ranged = segment.rangeLength >= 0
        val length = segment.rangeLength
        val start = segment.rangeStart
        val request = Request.Builder().url(httpsUrl(segment.uri)).apply {
            if (ranged) {
                if (start < 0 || length !in 1..Int.MAX_VALUE.toLong() || start > Long.MAX_VALUE - length) {
                    throw IOException("Apple 分片字节范围无效")
                }
                header("Range", "bytes=$start-${start + length - 1}")
            }
        }.build()
        var attempt = 0
        while (true) {
            try {
                return requestCdn(request, SEGMENT_ERROR) { response ->
                    val body = response.body ?: throw IOException("Apple 分片响应为空")
                    if (!ranged) return@requestCdn body.bytes()
                    val source = body.source()
                    val bodyLength = body.contentLength()
                    if (response.code == 200 && (bodyLength > length || bodyLength < 0 && source.request(length + 1))) {
                        // Do not allocate a second copy of the entire track when the CDN ignores Range.
                        source.skip(start)
                        return@requestCdn source.readByteArray(length)
                    }
                    if (bodyLength >= 0 && bodyLength != length || source.request(length + 1)) {
                        throw IOException("Apple 分片长度不符：期望 $length 字节")
                    }
                    source.readByteArray(length)
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= 3) throw error
                delay(500L shl attempt)
                attempt++
            }
        }
    }

    private suspend fun <T> requestCdn(request: Request, error: String, decode: (Response) -> T): T {
        fun checked(response: Response): T {
            if (!response.isSuccessful) throw IOException("${error}：HTTP ${response.code}")
            return decode(response)
        }
        val first = cdn.appleResponse(request) { response ->
            if (response.code == 403) null else CdnValue(checked(response))
        }
        if (first != null) return first.value
        return cdn.appleResponse(request.newBuilder().header("User-Agent", MOBILE_USER_AGENT).build(), ::checked)
    }

    private data class CdnValue<T>(val value: T)
    private data class TemplateKey(val server: HttpUrl?, val adamId: String, val uri: String)
    private class SharedTemplate(val handle: Long, var references: Int = 1)

    private companion object {
        val templateRequests = Mutex()
        val templates = HashMap<TemplateKey, SharedTemplate>()
    }
}

internal fun String.appleLogToken(): String = takeIf { length in 1..80 && APPLE_LOG_TOKEN.matches(this) } ?: "-"

private val APPLE_LOG_TOKEN = Regex("[A-Za-z0-9.,_-]+")

internal class ApplePreparationException(
    val stage: String,
    val reason: String,
    message: String,
    cause: Exception? = null,
) : IOException(message, cause)

internal fun applePlaybackFailureText(stage: String, reason: String): String = when (stage) {
    "widevine_quality" -> "本次服务器只提供 AAC-LC 播放入口"
    "quality_selection" -> if (reason == "no_alac_variant") "当前主清单没有 ALAC 音轨" else "该曲目没有所选音质"
    "alac_capability" -> if (reason == "alac_unavailable") "设备缺少 ALAC 解码组件" else "Apple 播放失败（alac_capability）"
    "master_playlist" -> "无法读取 Apple 主播放列表"
    "media_playlist" -> "无法读取 Apple 媒体播放列表"
    "init_segment" -> "无法读取 Apple 初始化分片"
    "alac_cookie" -> "ALAC 初始化配置或解码器不可用"
    "alac_bit_depth" -> "ALAC 位深不受支持"
    "alac_channels" -> "ALAC 声道不受支持"
    "alac_rate" -> if (reason == "rate_above_quality_limit") "ALAC 采样率超出所选音质" else "ALAC 采样率无效"
    "alac_buffer" -> "设备不支持此 ALAC 输出格式"
    "fairplay_capability" -> "Apple 解密组件不可用"
    "fairplay_template" -> "Apple 授权会话准备失败"
    "fairplay_decrypt" -> "Apple 音频解密失败"
    "segment_fetch" -> "Apple 音频分片读取失败"
    "segment_container", "init_container" -> "Apple 音频封装不可用"
    "fragment" -> "Apple 音频分片处理失败"
    "alac_decode" -> "ALAC 分片解码失败"
    "audio_output" -> "设备无法输出 ALAC 音频"
    "widevine_capability" -> "设备无法播放 Widevine AAC-LC"
    "media3" -> "Apple 播放失败（${reason.appleLogToken()}）"
    else -> "Apple 播放失败（${stage.appleLogToken()}）"
}

private inline fun <T> applePreparation(stage: String, operation: () -> T): T {
    try {
        return operation()
    } catch (failure: ApplePreparationException) {
        throw failure
    } catch (failure: IOException) {
        throw ApplePreparationException(stage, failure.javaClass.simpleName, failure.message ?: PLAYLIST_ERROR, failure)
    } catch (failure: IllegalArgumentException) {
        throw ApplePreparationException(stage, failure.javaClass.simpleName, failure.message ?: PLAYLIST_ERROR, failure)
    }
}

private const val PLAYLIST_ERROR = "Apple 播放列表获取失败"
private const val SEGMENT_ERROR = "Apple 分片获取失败"
private const val TEMPLATE_ERROR = "Apple 解密模板获取失败"
private const val PREFETCH_URI = "skd://itunes.apple.com/P000000000/s1/e1"
private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
private val ADAM_ID_IN_FILE = Regex("_A(\\d+)[._]")
private val ALAC_GROUP_RATE = Regex("alac-stereo-(\\d+)", RegexOption.IGNORE_CASE)
private val HLS_ATTRIBUTE = Regex("""([A-Z0-9_-]+)\s*=\s*("[^"]*"|[^,]*)""")

private fun appleServer(server: String): HttpUrl = SourceStatus.normalizeBaseUrl(server)?.toHttpUrlOrNull()
    ?: throw IOException("服务器地址无效")

private fun HttpUrl.endpoint(vararg path: String): HttpUrl = newBuilder().apply {
    path.forEach { addPathSegment(it) }
}.build()

private fun httpsUrl(url: HttpUrl?): HttpUrl = url?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
    ?: throw IOException("Apple CDN 地址必须使用 HTTPS")

private suspend fun <T> OkHttpClient.appleResponse(request: Request, decode: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use {
                        if (!continuation.isActive) return
                        decode(it)
                    }
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                    return
                }
                continuation.resume(value)
            }
        })
    }

private suspend fun <T> OkHttpClient.appleGet(url: HttpUrl, error: String, decode: (okhttp3.ResponseBody) -> T): T =
    appleResponse(Request.Builder().url(url).build()) { response ->
        if (!response.isSuccessful) throw IOException("${error}：HTTP ${response.code}")
        decode(response.body ?: throw IOException("${error}：响应为空"))
    }

private fun appleData(text: String, error: String): JSONObject = try {
    val json = JSONObject(text)
    if (json.optInt("code", Int.MIN_VALUE) != 0) throw IOException(error)
    json.optJSONObject("data") ?: throw IOException(error)
} catch (cause: JSONException) {
    throw IOException(error, cause)
}

private fun JSONObject.requiredString(key: String, error: String): String =
    (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: throw IOException(error)

private fun attributes(line: String): Map<String, String> = HLS_ATTRIBUTE.findAll(line.substringAfter(':'))
    .associate { it.groupValues[1] to it.groupValues[2].trim().removeSurrounding("\"") }

/** Convert Apple's legacy CENC key tag into the form Media3's HLS parser reads. */
internal fun media3WidevineManifest(text: String): ByteArray = buildString(text.length) {
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("#EXT-X-KEY:")) {
            appendLine(raw)
            continue
        }
        val fields = attributes(line)
        val uri = fields["URI"]
        val method = fields["METHOD"]
        if (uri == null || !uri.startsWith("data:", ignoreCase = true) || method == "NONE") {
            appendLine(raw)
            continue
        }
        val pssh = Base64.getEncoder().encodeToString(appleWidevinePssh(uri))
        append("#EXT-X-KEY:METHOD=")
        append(if (method == "ISO-23001-7") "SAMPLE-AES-CTR" else method)
        append(",URI=\"data:application/octet-stream;base64,").append(pssh).append('"')
        append(",KEYFORMAT=\"").append(WIDEVINE_KEYFORMAT).append('"')
        fields["KEYFORMATVERSIONS"]?.let { append(",KEYFORMATVERSIONS=\"").append(it).append('"') }
        fields["IV"]?.let { append(",IV=").append(it) }
        appendLine()
    }
}.toByteArray(Charsets.UTF_8)

private fun parseMaster(text: String, base: HttpUrl): List<AppleNativeVariant> {
    val groups = HashMap<String, Map<String, String>>()
    val streams = ArrayList<Pair<Map<String, String>, String>>()
    var pending: Map<String, String>? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-MEDIA:") -> {
                val group = attributes(line)
                if (group["TYPE"] == "AUDIO") (group["GROUP-ID"] ?: group["GROUP_ID"])?.let { groups[it] = group }
            }
            line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line)
            line.isNotEmpty() && !line.startsWith('#') -> {
                pending?.let { streams.add(it to line) }
                pending = null
            }
        }
    }
    if (streams.isEmpty()) throw IOException("Apple 主播放列表没有音质变体")
    return streams.map { (stream, url) ->
        val groupId = stream["GROUP-ID"] ?: stream["GROUP_ID"] ?: stream["AUDIO"].orEmpty()
        val group = groups[stream["AUDIO"] ?: groupId]
        fun field(name: String): String? = stream[name] ?: group?.get(name)
        val declaredCodecs = field("CODECS").orEmpty()
        val codecs = if (groupId.contains("alac", ignoreCase = true) && !declaredCodecs.contains("alac", ignoreCase = true)) {
            "alac"
        } else declaredCodecs
        AppleNativeVariant(
            httpsUrl(base.resolve(url)), groupId, codecs,
            field("AVERAGE-BANDWIDTH")?.toLongOrNull() ?: field("BANDWIDTH")?.toLongOrNull() ?: 0,
            field("CHANNELS")?.substringBefore('/')?.toIntOrNull() ?: 0,
            field("SAMPLE-RATE")?.toIntOrNull() ?: 0,
            field("BIT-DEPTH")?.toIntOrNull() ?: 0,
            field("QUALITY"),
        )
    }
}

private data class ByteRange(val start: Long, val length: Long)

private fun parseRange(raw: String, previousEnd: Long?): ByteRange {
    val value = raw.trim().removeSurrounding("\"")
    val separator = value.indexOf('@')
    val length = (if (separator < 0) value else value.substring(0, separator)).trim().toLongOrNull()
    val start = if (separator < 0) previousEnd else value.substring(separator + 1).trim().toLongOrNull()
    if (start == null || start < 0 || length == null || length <= 0 || start > Long.MAX_VALUE - length) {
        throw IOException("Apple 播放列表字节范围无效")
    }
    return ByteRange(start, length)
}

private fun durationMs(value: String): Long {
    val seconds = value.trim().toDoubleOrNull()
    if (seconds == null || !seconds.isFinite() || seconds < 0 || seconds >= Long.MAX_VALUE / 1000.0) {
        throw IOException("Apple 播放列表分片时长无效")
    }
    return (seconds * 1000).roundToLong()
}

private fun parseMedia(text: String, base: HttpUrl, widevine: Boolean): AppleNativePlaylist {
    var init: AppleNativeSegment? = null
    val segments = ArrayList<AppleNativeSegment>()
    var previous: AppleNativeSegment? = null
    var keyUri: String? = null
    var duration: Long? = null
    var rangeText: String? = null
    var total = 0L
    var target = 0L
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-TARGETDURATION:") -> target = durationMs(line.substringAfter(':'))
            line.startsWith("#EXT-X-KEY:") -> {
                val fields = attributes(line)
                val uri = fields["URI"]?.takeIf { it.isNotBlank() }
                val format = fields["KEYFORMAT"]
                if (fields["METHOD"] == "NONE" || uri == null) {
                    keyUri = null
                } else if (uri.startsWith("data:", ignoreCase = true)) {
                    if (format != null && !format.equals(WIDEVINE_KEYFORMAT, true)) throw IOException("Apple HLS 密钥格式不受支持")
                    if (widevine) keyUri = uri
                } else {
                    keyUri = uri
                }
            }
            line.startsWith("#EXT-X-MAP:") -> {
                val fields = attributes(line)
                val uri = httpsUrl(fields["URI"]?.let(base::resolve))
                val range = fields["BYTERANGE"]?.let { parseRange(it, 0) }
                val map = AppleNativeSegment(uri, range?.start ?: 0, range?.length ?: -1, 0, null)
                if (init != null && init != map) throw IOException("Apple 播放列表包含多个初始化分片")
                init = map
                previous = map
            }
            line.startsWith("#EXTINF:") -> duration = durationMs(line.substringAfter(':').substringBefore(','))
            line.startsWith("#EXT-X-BYTERANGE:") -> rangeText = line.substringAfter(':')
            line.isNotEmpty() && !line.startsWith('#') -> {
                val uri = httpsUrl(base.resolve(line))
                val previousEnd = previous?.takeIf { it.uri == uri && it.rangeLength >= 0 }?.let { it.rangeStart + it.rangeLength }
                val range = rangeText?.let { parseRange(it, previousEnd) }
                val time = duration ?: throw IOException("Apple 播放列表缺少分片时长")
                val segment = AppleNativeSegment(uri, range?.start ?: 0, range?.length ?: -1, time, keyUri)
                if (total > Long.MAX_VALUE - time) throw IOException("Apple 播放列表时长无效")
                total += time
                segments.add(segment)
                previous = segment
                duration = null
                rangeText = null
            }
        }
    }
    if (segments.isEmpty() || duration != null || rangeText != null) throw IOException("Apple 播放列表没有完整的媒体分片")
    return AppleNativePlaylist(init, segments, target.takeIf { it > 0 } ?: segments.maxOf { it.durationMs }, total)
}

private enum class AudioCodec { AAC, HE, ALAC, EAC3, OTHER }
private enum class QualityKind { AAC, HE, ALAC, ATMOS, OTHER }

private fun AppleNativeVariant.audioCodec(): AudioCodec = when {
    codecs.contains("alac", true) || groupId.contains("alac", true) -> AudioCodec.ALAC
    codecs.contains("ec-3", true) || codecs.contains("ec3", true) || codecs.contains("eac3", true) -> AudioCodec.EAC3
    codecs.contains("mp4a.40.5", true) || codecs.contains("mp4a.40.29", true) -> AudioCodec.HE
    codecs.contains("mp4a", true) -> AudioCodec.AAC
    else -> AudioCodec.OTHER
}

private fun AppleNativeVariant.qualityKind(): QualityKind {
    val group = groupId.lowercase()
    if ("binaural" in group || "downmix" in group) return QualityKind.OTHER
    return when (audioCodec()) {
        AudioCodec.AAC -> if ("he-" in group) QualityKind.HE else QualityKind.AAC
        AudioCodec.HE -> QualityKind.HE
        AudioCodec.ALAC -> QualityKind.ALAC
        AudioCodec.EAC3 -> if ("atmos" in group) QualityKind.ATMOS else QualityKind.OTHER
        AudioCodec.OTHER -> QualityKind.OTHER
    }
}

private fun AppleNativeVariant.alacSelectionRate(): Int = sampleRate.takeIf { it > 0 }
    ?: ALAC_GROUP_RATE.find(groupId)?.groupValues?.get(1)?.toIntOrNull() ?: 0

private fun missingAlacReason(variants: List<AppleNativeVariant>): String =
    if (variants.any { it.qualityKind() == QualityKind.ALAC }) "rate_above_quality_limit" else "no_alac_variant"

private fun qualitySelectionStage(reason: String): String =
    if (reason == "rate_above_quality_limit") "alac_rate" else "quality_selection"

private fun explicitCandidates(variants: List<AppleNativeVariant>, wanted: String): List<Pair<AppleNativeVariant, Int>> {
    if (wanted == "lossless" || wanted == "hires") {
        val maxRate = if (wanted == "lossless") 48_000 else 192_000
        return cappedAlac(variants, maxRate).map { it to maxRate }
    }
    val exact = variants.filter {
        it.groupId == wanted || it.quality == wanted && (wanted != "alac" || it.audioCodec() == AudioCodec.ALAC)
    }
    val matching = exact.ifEmpty { variants.filter { it.matchesQuality(wanted) } }
    return matching.sortedByDescending {
        if (it.sampleRate > 0) it.sampleRate * 100L + it.bitDepth else it.bandwidth
    }.map { it to Int.MAX_VALUE }
}

private fun AppleNativeVariant.matchesQuality(wanted: String): Boolean {
    val group = groupId.lowercase()
    val alac = audioCodec() == AudioCodec.ALAC
    val atmos = "atmos" in group
    val he = "he-" in group
    val binaural = "binaural" in group
    val downmix = "downmix" in group
    val low = "128" in group
    return when (wanted) {
        "alac" -> alac
        "atmos" -> atmos && !downmix && !binaural
        "aac" -> "stereo-" in group && !alac && !he && !binaural && !downmix && !atmos
        "aac-128" -> "stereo-128" in group && !alac && !binaural && !downmix && !he
        "aac-he" -> "he-stereo-" in group && !binaural && !downmix
        "aac-binaural" -> binaural && !low && !he
        "aac-binaural-128" -> binaural && low && !he
        "he-binaural" -> binaural && he
        "aac-downmix" -> downmix && !low && !he
        "aac-downmix-128" -> downmix && low && !he
        "he-downmix" -> downmix && he
        else -> false
    }
}

private fun profileCandidates(variants: List<AppleNativeVariant>, profile: ApplePlaybackProfile): List<Pair<AppleNativeVariant, Int>> {
    val aac = QualityKind.AAC to Int.MAX_VALUE
    val he = QualityKind.HE to Int.MAX_VALUE
    val lossless = QualityKind.ALAC to 48_000
    val ladder = when (profile.quality) {
        "lossless" -> listOf(lossless, aac, he)
        "high" -> listOf(aac, he, lossless)
        "efficient" -> listOf(he, aac, lossless)
        else -> listOf(QualityKind.ALAC to 192_000, aac, he)
    }
    val steps = if (profile.quality != "efficient" && profile.spatial == "always") {
        listOf(QualityKind.ATMOS to Int.MAX_VALUE) + ladder
    } else ladder
    val classified = variants.groupBy { it.qualityKind() }
    return steps.flatMap { (kind, maxRate) ->
        classified[kind].orEmpty()
            .filter { kind != QualityKind.ALAC || it.alacSelectionRate() <= maxRate || cappedAlac(classified[kind].orEmpty(), maxRate).contains(it) }
            .sortedByDescending { if (kind == QualityKind.ALAC) it.alacSelectionRate() * 100L + it.bitDepth else it.bandwidth }
            .map { it to maxRate }
    }
}

private fun cappedAlac(variants: List<AppleNativeVariant>, maxRate: Int): List<AppleNativeVariant> {
    val within = variants.filter { it.alacSelectionRate() in 1..maxRate }
    if (within.isNotEmpty()) return within.sortedByDescending { it.alacSelectionRate() * 100L + it.bitDepth }
    return listOfNotNull(variants.filter { it.alacSelectionRate() > maxRate }.minByOrNull { it.alacSelectionRate() }).ifEmpty { variants }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun supportsEac3(variant: AppleNativeVariant): Boolean = try {
    val mime = if (variant.matchesQuality("atmos")) MimeTypes.AUDIO_E_AC3_JOC else MimeTypes.AUDIO_E_AC3
    val format = Format.Builder()
        .setSampleMimeType(mime)
        .setCodecs(variant.codecs)
        .setChannelCount(variant.channels.takeIf { it > 0 } ?: Format.NO_VALUE)
        .setSampleRate(variant.sampleRate.takeIf { it > 0 } ?: Format.NO_VALUE)
        .build()
    MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
        .any { it.isFormatSupported(format) }
} catch (_: MediaCodecUtil.DecoderQueryException) {
    false
}

private fun playableAlacRate(init: AppleNativeInit): Int {
    val cookie = init.alacCookie ?: throw ApplePreparationException("alac_cookie", "cookie_missing", "Apple ALAC 配置缺失或无效")
    val handle = AlacDecoder.open(cookie.copyOfRange(4, cookie.size))
    if (handle == 0L) throw ApplePreparationException("alac_cookie", "decoder_open", "Apple ALAC 解码器初始化失败")
    try {
        val mask = when (AlacDecoder.channels(handle)) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> throw ApplePreparationException("alac_channels", "channel_mask", "Apple ALAC 声道数不可播放")
        }
        val rate = AlacDecoder.sampleRate(handle)
        if (rate <= 0) throw ApplePreparationException("alac_rate", "invalid_rate", "Apple ALAC 采样率无效")
        val bits = AlacDecoder.bits(handle)
        if (bits != 16 && bits != 20 && bits != 24 && bits != 32) {
            throw ApplePreparationException("alac_bit_depth", "unsupported_bits", "Apple ALAC PCM 位深不受支持")
        }
        if (!alacOutputAvailable(rate, mask, bits)) {
            throw ApplePreparationException("alac_buffer", "buffer_unavailable", "设备不支持此 ALAC 输出格式")
        }
        return rate
    } finally {
        AlacDecoder.close(handle)
    }
}

internal fun alacOutputAvailable(sampleRate: Int, channelMask: Int, bits: Int): Boolean {
    val encodings = if (bits == 16) intArrayOf(AudioFormat.ENCODING_PCM_16BIT) else intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
    return intArrayOf(sampleRate, 48_000, 44_100).distinct().any { rate ->
        rate > 0 && encodings.any { AudioTrack.getMinBufferSize(rate, channelMask, it) > 0 }
    }
}

private fun repairPacket(bytes: ByteArray, offset: Int, size: Int, frameLength: Int, channels: Int, bits: Int) {
    if (size < 3 || frameLength <= 0 || channels !in 1..2 || bits != 16 && bits != 20 && bits != 24 && bits != 32) return
    val header = (bytes.u8(offset) shl 16) or (bytes.u8(offset + 1) shl 8) or bytes.u8(offset + 2)
    val element = header ushr 21
    if (channels == 1 && element != 0 && element != 3 || channels == 2 && element != 1) return
    // Complete uncompressed element: no unused header bits, partial-frame flag or shifted bytes.
    if (header and 0x01fffe != 2) return
    val end = 23L + frameLength.toLong() * channels * bits
    val available = size.toLong() * 8 - end
    if (available !in 3L..10L) return
    for (bit in end until end + 3) {
        val index = offset + (bit / 8).toInt()
        bytes[index] = (bytes.u8(index) or (0x80 ushr (bit % 8).toInt())).toByte()
    }
}

private fun isPrefetchKey(uri: String): Boolean =
    uri.equals(PREFETCH_URI, ignoreCase = true) || uri.contains("P000000000/s1/e1", ignoreCase = true)

private fun looksLikeAlac(bytes: ByteArray, offset: Int, channels: Int): Boolean {
    if (offset < 0 || offset + 3 > bytes.size) return false
    val header = (bytes.u8(offset) shl 16) or (bytes.u8(offset + 1) shl 8) or bytes.u8(offset + 2)
    val element = header ushr 21
    return if (channels == 1) element == 0 || element == 3 else element == 1
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

private fun ByteArray.i32(index: Int): Int =
    (u8(index) shl 24) or (u8(index + 1) shl 16) or (u8(index + 2) shl 8) or u8(index + 3)

private fun ByteArray.u32(index: Int): Long = i32(index).toLong() and 0xffffffffL

private fun ByteArray.put32(index: Int, value: Int) {
    this[index] = (value ushr 24).toByte()
    this[index + 1] = (value ushr 16).toByte()
    this[index + 2] = (value ushr 8).toByte()
    this[index + 3] = value.toByte()
}

private fun fourCc(type: String): Int = (type[0].code shl 24) or (type[1].code shl 16) or (type[2].code shl 8) or type[3].code

private class AppleMp4(private val bytes: ByteArray) {
    private data class Box(val start: Int, val body: Int, val end: Int, val type: Int)
    private val roots = boxes(0, bytes.size)

    fun clearInitBoxes() {
        val moov = root(MOOV)
        for (box in roots) if (box.type == PSSH) free(box)
        for (box in children(moov)) if (box.type == PSSH) free(box)
        forEachSampleEntry(moov) { entry ->
            if (entry.type == ENCA || entry.type == ENCV) {
                val sinf = sampleEntryChildren(entry).firstOrNull { it.type == SINF } ?: invalid("缺少 sinf")
                bytes.put32(entry.start + 4, originalFormat(sinf))
                bytes.fill(0, sinf.body, sinf.end)
                free(sinf)
            }
        }
    }

    fun alacInit(): AppleNativeInit {
        forEachSampleEntry(root(MOOV)) { entry ->
            if (entry.type == ALAC || entry.type == ENCA) {
                val children = sampleEntryChildren(entry)
                val codec = if (entry.type == ENCA) children.firstOrNull { it.type == SINF }?.let(::originalFormat) else entry.type
                if (codec == ALAC) {
                    val alac = children.firstOrNull { it.type == ALAC } ?: invalid("缺少 ALAC 配置")
                    if (alac.end - alac.body < 28) invalid("ALAC 配置过短")
                    val config = alac.body + 4
                    val frameLength = bytes.u32(config)
                    val bitDepth = bytes.u8(config + 5)
                    val channels = bytes.u8(config + 9)
                    if (frameLength in 1L..65_535 && bitDepth in ALAC_BITS && channels in 1..8) {
                        return AppleNativeInit(bytes, bytes.copyOfRange(alac.body, alac.end), frameLength.toInt(), channels, bitDepth)
                    }
                }
            }
        }
        return AppleNativeInit(bytes, null, 0, 0, 0)
    }

    fun sampleRanges(): List<IntRange> {
        val samples = ArrayList<IntRange>()
        var hasMoof = false
        for (moof in roots) {
            if (moof.type != MOOF) continue
            hasMoof = true
            var data = moof.start.toLong()
            for (traf in children(moof)) {
                if (traf.type != TRAF) continue
                val fields = children(traf)
                val tfhd = fields.firstOrNull { it.type == TFHD } ?: invalid("缺少 tfhd")
                val flags = read32(tfhd, tfhd.body) and 0xffffff
                read32(tfhd, tfhd.body + 4) // track_ID
                if (flags and 0x01 != 0) invalid("不支持 tfhd base-data-offset")
                var defaultPosition = tfhd.body + 8
                if (flags and 0x02 != 0) defaultPosition += 4
                if (flags and 0x08 != 0) defaultPosition += 4
                val defaultSize = if (flags and 0x10 != 0) read32(tfhd, defaultPosition).toLong() and 0xffffffffL else 0L
                for (trun in fields) {
                    if (trun.type != TRUN) continue
                    val runFlags = read32(trun, trun.body) and 0xffffff
                    val count = read32(trun, trun.body + 4).toLong() and 0xffffffffL
                    var cursor = trun.body + 8
                    if (runFlags and 0x01 != 0) {
                        data = moof.start.toLong() + read32(trun, cursor)
                        cursor += 4
                    }
                    if (runFlags and 0x04 != 0) cursor += 4
                    val stride = Integer.bitCount(runFlags and 0x0f00) * 4
                    if (cursor.toLong() + count * stride > trun.end || count > bytes.size) invalid("trun 样本表越界")
                    val sizeOffset = if (runFlags and 0x100 != 0) 4 else 0
                    repeat(count.toInt()) {
                        val size = if (runFlags and 0x200 != 0) bytes.u32(cursor + sizeOffset) else defaultSize
                        if (size <= 0 || data < 0 || data + size > bytes.size) invalid("样本范围越界")
                        samples.add(data.toInt() until (data + size).toInt())
                        data += size
                        cursor += stride
                    }
                }
            }
        }
        if (!hasMoof) invalid("缺少 moof")
        if (samples.isEmpty()) invalid("没有样本")
        validateSamples(samples)
        return samples
    }
    fun sampleDurations(): List<Long> {
        val durations = ArrayList<Long>()
        var hasMoof = false
        for (moof in roots) {
            if (moof.type != MOOF) continue
            hasMoof = true
            for (traf in children(moof)) {
                if (traf.type != TRAF) continue
                val fields = children(traf)
                val tfhd = fields.firstOrNull { it.type == TFHD } ?: invalid("缺少 tfhd")
                val flags = read32(tfhd, tfhd.body) and 0xffffff
                if (flags and 0x01 != 0) invalid("不支持 tfhd base-data-offset")
                var cursor = tfhd.body + 8
                if (flags and 0x02 != 0) cursor += 4
                val defaultDuration = if (flags and 0x08 != 0) {
                    val value = read32(tfhd, cursor).toLong() and 0xffffffffL
                    cursor += 4
                    value
                } else 0L
                for (trun in fields) {
                    if (trun.type != TRUN) continue
                    val runFlags = read32(trun, trun.body) and 0xffffff
                    val count = read32(trun, trun.body + 4).toLong() and 0xffffffffL
                    var sampleCursor = trun.body + 8
                    if (runFlags and 0x01 != 0) sampleCursor += 4
                    if (runFlags and 0x04 != 0) sampleCursor += 4
                    val stride = Integer.bitCount(runFlags and 0x0f00) * 4
                    if (sampleCursor.toLong() + count * stride > trun.end || count > bytes.size) invalid("trun 样本表越界")
                    repeat(count.toInt()) {
                        durations.add(if (runFlags and 0x100 != 0) bytes.u32(sampleCursor) else defaultDuration)
                        sampleCursor += stride
                    }
                }
            }
        }
        if (!hasMoof) invalid("缺少 moof")
        if (durations.isEmpty()) invalid("没有样本")
        return durations
    }


    fun validateSamples(samples: List<IntRange>) {
        val mdats = roots.filter { it.type == MDAT }
        var ordered = samples
        var previousStart = -1
        for (sample in samples) {
            if (sample.first < previousStart) {
                ordered = samples.sortedBy { it.first }
                break
            }
            previousStart = sample.first
        }
        var previousEnd = -1
        var mdatIndex = 0
        for (sample in ordered) {
            while (mdatIndex < mdats.size && sample.first >= mdats[mdatIndex].end) mdatIndex++
            val mdat = mdats.getOrNull(mdatIndex)
            if (sample.isEmpty() || sample.first <= previousEnd || mdat == null || sample.first < mdat.body || sample.last >= mdat.end) {
                invalid("样本不在 mdat 内或相互重叠")
            }
            previousEnd = sample.last
        }
    }

    fun clearFragmentBoxes() {
        var hasMoof = false
        for (root in roots) {
            when (root.type) {
                PSSH -> free(root)
                MOOF -> {
                    hasMoof = true
                    for (box in children(root)) {
                        if (box.type == TRAF) {
                            for (child in children(box)) clearFragmentBox(child)
                        } else {
                            clearFragmentBox(box)
                        }
                    }
                }
            }
        }
        if (!hasMoof) invalid("缺少 moof")
    }

    private fun clearFragmentBox(box: Box) {
        when (box.type) {
            SENC, SAIZ, SAIO, PSSH -> free(box)
            SGPD, SBGP -> if (box.end - box.body >= 8) {
                val group = bytes.i32(box.body + 4)
                if (group == SEIG || group == SEAM) free(box)
            }
        }
    }

    private fun originalFormat(sinf: Box): Int {
        val frma = children(sinf).firstOrNull { it.type == FRMA } ?: invalid("缺少 frma")
        return read32(frma, frma.body)
    }

    private fun sampleEntryChildren(entry: Box): List<Box> {
        val header = if (entry.type == ENCV) {
            78
        } else {
            if (entry.end - entry.body < 10) invalid("音频 sample entry 过短")
            when ((bytes.u8(entry.body + 8) shl 8) or bytes.u8(entry.body + 9)) {
                0 -> 28
                1 -> 44
                2 -> 64
                else -> invalid("音频 sample entry 版本无效")
            }
        }
        return boxes(entry.body + header, entry.end)
    }

    private inline fun forEachSampleEntry(moov: Box, use: (Box) -> Unit) {
        for (trak in children(moov)) {
            if (trak.type != TRAK) continue
            val mdia = child(trak, MDIA) ?: continue
            val minf = child(mdia, MINF) ?: continue
            val stbl = child(minf, STBL) ?: continue
            val stsd = child(stbl, STSD) ?: continue
            val count = read32(stsd, stsd.body + 4)
            val entries = boxes(stsd.body + 8, stsd.end)
            if (entries.size != count) invalid("stsd 样本描述数量不符")
            for (entry in entries) use(entry)
        }
    }

    private fun root(type: Int): Box = roots.firstOrNull { it.type == type } ?: invalid("缺少 moov")
    private fun children(box: Box): List<Box> = boxes(box.body, box.end)
    private fun child(box: Box, type: Int): Box? = children(box).firstOrNull { it.type == type }
    private fun free(box: Box) = bytes.put32(box.start + 4, FREE)

    private fun read32(box: Box, at: Int): Int {
        if (at < box.body || box.end - at < 4) invalid("box 过短")
        return bytes.i32(at)
    }

    private fun boxes(start: Int, end: Int): List<Box> {
        if (start < 0 || start > end || end > bytes.size) invalid("box 范围无效")
        val boxes = ArrayList<Box>()
        var position = start
        while (position < end) {
            val remaining = end - position
            if (remaining < 8) invalid("box 头过短")
            var header = 8
            val size = when (val shortSize = bytes.u32(position)) {
                0L -> remaining.toLong()
                1L -> {
                    if (remaining < 16 || bytes.u32(position + 8) != 0L) invalid("扩展 box 长度无效")
                    header = 16
                    bytes.u32(position + 12)
                }
                else -> shortSize
            }
            if (size < header || size > remaining) invalid("box 长度无效")
            val next = position + size.toInt()
            boxes.add(Box(position, position + header, next, bytes.i32(position + 4)))
            position = next
        }
        return boxes
    }

    private fun invalid(message: String): Nothing = throw IOException("Apple MP4 无效：$message")

    companion object {
        private val MOOV = fourCc("moov")
        private val TRAK = fourCc("trak")
        private val MDIA = fourCc("mdia")
        private val MINF = fourCc("minf")
        private val STBL = fourCc("stbl")
        private val STSD = fourCc("stsd")
        private val ENCA = fourCc("enca")
        private val ENCV = fourCc("encv")
        private val ALAC = fourCc("alac")
        private val SINF = fourCc("sinf")
        private val FRMA = fourCc("frma")
        private val MOOF = fourCc("moof")
        private val MDAT = fourCc("mdat")
        private val TRAF = fourCc("traf")
        private val TFHD = fourCc("tfhd")
        private val TRUN = fourCc("trun")
        private val SENC = fourCc("senc")
        private val SAIZ = fourCc("saiz")
        private val SAIO = fourCc("saio")
        private val SGPD = fourCc("sgpd")
        private val SBGP = fourCc("sbgp")
        private val SEIG = fourCc("seig")
        private val SEAM = fourCc("seam")
        private val PSSH = fourCc("pssh")
        private val FREE = fourCc("free")
        private val ALAC_BITS = setOf(16, 20, 24, 32)
    }
}

@UnstableApi
internal class AppleNativeWidevine(
    private val api: FantasticApi,
    private val server: String,
    private val adamId: String,
) : MediaDrmCallback {
    @Volatile private var keyUris: List<String> = emptyList()

    internal fun setKeyUris(uris: List<String>) {
        keyUris = uris.filter { it.startsWith("data:", ignoreCase = true) }.distinct()
    }

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        throw callbackFailure(Uri.parse(request.defaultUrl), IOException(WIDEVINE_REJECTED))

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray = try {
        val uris = keyUris
        val licenseUrl = request.licenseServerUrl
        val data = request.data
        val uri = when {
            licenseUrl.startsWith("data:", ignoreCase = true) && (uris.isEmpty() || licenseUrl in uris) -> licenseUrl
            uris.size == 1 -> uris[0]
            else -> uris.singleOrNull { data.containsSequence(widevineRequestMarker(it)) }
                ?: throw IOException(WIDEVINE_REJECTED)
        }
        if (uuid != WIDEVINE_UUID) throw IOException(WIDEVINE_REJECTED)
        requestLicense(uri, data)
    } catch (error: IOException) {
        val endpoint = SourceStatus.normalizeBaseUrl(server)?.let { Uri.parse("$it/mv/license") } ?: Uri.EMPTY
        throw callbackFailure(endpoint, error)
    }

    internal fun requestLicense(uri: String, challenge: ByteArray): ByteArray {
        if (!uri.startsWith("data:", ignoreCase = true)) throw IOException(WIDEVINE_REJECTED)
        val url = appleServer(server).endpoint("mv", "license")
        val json = JSONObject().put("adamId", adamId).put("challenge", Base64.getEncoder().encodeToString(challenge)).put("uri", uri)
        val request = Request.Builder().url(url).post(json.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        try {
            api.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Apple 许可获取失败：HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException(WIDEVINE_REJECTED)
                val license = appleData(body, WIDEVINE_REJECTED).requiredString("license", WIDEVINE_REJECTED)
                return decodeBase64(license) ?: license.toByteArray(Charsets.UTF_8)
            }
        } catch (error: IOException) {
            if (error.message == WIDEVINE_REJECTED || error.message?.startsWith("Apple 许可获取失败：HTTP") == true) {
                throw error
            }
            throw IOException(WIDEVINE_REJECTED, error)
        }
    }

    private fun callbackFailure(uri: Uri, cause: IOException): MediaDrmCallbackException =
        MediaDrmCallbackException(DataSpec.Builder().setUri(uri).build(), uri, emptyMap(), 0, cause)

    companion object {
        val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        internal const val WIDEVINE_REJECTED = "这首歌的 Widevine 许可未被设备接受"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** Apple key URIs can contain just the KID. HLS consumers need a complete PSSH box. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun appleWidevinePssh(uri: String): ByteArray {
    val payload = widevinePayload(uri)
    val uuid = AppleNativeWidevine.WIDEVINE_UUID
    if (payload.isPssh()) {
        if (PsshAtomUtil.parseUuid(payload) != uuid) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
        return payload
    }
    if (payload.size != 16) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    // WidevinePsshData for AES-CTR with the KID and empty provider, content ID and policy.
    val data = ByteArray(26)
    data[0] = 8
    data[1] = 1
    data[2] = 18
    data[3] = 16
    payload.copyInto(data, 4)
    data[20] = 26
    data[22] = 34
    data[24] = 50
    return PsshAtomUtil.buildPsshAtom(uuid, data)
}

private fun ByteArray.isPssh(): Boolean = size >= 32 && i32(4) == fourCc("pssh")

@androidx.annotation.OptIn(UnstableApi::class)
private fun widevineRequestMarker(uri: String): ByteArray {
    val payload = widevinePayload(uri)
    if (!payload.isPssh()) return payload
    return PsshAtomUtil.parseSchemeSpecificData(payload, AppleNativeWidevine.WIDEVINE_UUID)
        ?.takeIf { it.isNotEmpty() } ?: throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun widevinePayload(uri: String): ByteArray = decodeBase64(Uri.decode(uri.substringAfter(',', "")))
    ?.takeIf { it.isNotEmpty() } ?: throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)

private fun decodeBase64(text: String): ByteArray? = try {
    Base64.getDecoder().decode(text)
} catch (_: IllegalArgumentException) {
    try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun appleWidevineKeyId(uri: String): ByteArray {
    val payload = widevinePayload(uri)
    if (payload.size == 16) return payload
    val data = if (payload.isPssh()) {
        PsshAtomUtil.parseSchemeSpecificData(payload, AppleNativeWidevine.WIDEVINE_UUID)
            ?: throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    } else payload
    var cursor = 0
    while (cursor < data.size) {
        var tag = 0
        var shift = 0
        while (cursor < data.size) {
            val value = data[cursor++].toInt() and 0xff
            tag = tag or ((value and 0x7f) shl shift)
            if (value and 0x80 == 0) break
            shift += 7
        }
        var length = 0
        shift = 0
        while (cursor < data.size) {
            val value = data[cursor++].toInt() and 0xff
            length = length or ((value and 0x7f) shl shift)
            if (value and 0x80 == 0) break
            shift += 7
        }
        if (length < 0 || cursor + length > data.size) break
        if (tag == 18 && length == 16) return data.copyOfRange(cursor, cursor + length)
        cursor += length
    }
    throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    for (start in 0..size - sequence.size) {
        var index = 0
        while (index < sequence.size && this[start + index] == sequence[index]) index++
        if (index == sequence.size) return true
    }
    return false
}
