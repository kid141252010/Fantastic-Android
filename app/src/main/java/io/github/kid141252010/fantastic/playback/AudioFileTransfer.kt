package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.NeteaseApi
import io.github.kid141252010.fantastic.data.NeteaseAudio
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

internal data class FullAudioRequest(
    val track: Track,
    val server: String,
    val storefront: String,
    val quality: String,
    val maxBytes: Long,
    val appleGroup: String? = null,
    val appleCodecs: String? = null,
    val resolvedNetease: NeteaseAudio? = null,
    val strict: Boolean = false,
    val expectedDurationMs: Long = 0,
    val requestedNeteaseQuality: String? = null,
)

internal data class AudioFileInfo(
    val sizeBytes: Long,
    val durationMs: Long?,
    val mime: String?,
    val sampleRate: Int?,
    val quality: String,
)

/** Transfers one complete source file into the caller-owned temporary file. */
internal class AudioFileTransfer(private val context: Context, private val api: FantasticApi) {
    private val client = api.client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val netease = NeteaseApi(api, Prefs(context))

    suspend fun transfer(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo {
        require(request.maxBytes > 0) { "缓存上限无效" }
        withContext(Dispatchers.IO) { target.parentFile?.mkdirs() }
        val result = when (request.track.source) {
            Source.Apple -> AppleOfflineExporter(context).export(request, target, reserve, progress)
            Source.Netease -> transferNetease(request, target, reserve, progress)
            Source.QQ -> transferDirect(request, target, reserve, progress)
        }
        val effectiveDuration = maxOf(request.track.durationMs, request.expectedDurationMs).takeIf { it > 0 } ?: 0
        val inspected = verifyAudio(target, effectiveDuration, request.strict, request.quality)
        return inspected.copy(quality = result.quality.ifBlank { request.quality })
    }

    private suspend fun transferNetease(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo {
        val captured = request.resolvedNetease
        if (captured == null) return transferDirect(request, target, reserve, progress)
        netease.validateCdnUrl(captured.url)
        try {
            readInto(captured.url.toHttpUrlOrNull() ?: throw IOException("Invalid Netease audio URL"), target,
                request.maxBytes, reserve, progress, captured.sizeBytes, allowRedirect = false)
        } catch (error: ExpiredCdnException) {
            val refreshed = netease.resolvePlay(
                request.track.id,
                request.requestedNeteaseQuality ?: captured.quality,
                request.server,
            )
            if (refreshed.quality != captured.quality) throw IOException("音质已变化，未保存为完整歌曲")
            netease.validateCdnUrl(refreshed.url)
            readInto(refreshed.url.toHttpUrlOrNull() ?: throw IOException("Invalid Netease audio URL"), target,
                request.maxBytes, reserve, progress, refreshed.sizeBytes, allowRedirect = false)
        }
        return AudioFileInfo(target.length(), null, null, null, captured.quality)
    }

    private suspend fun transferDirect(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo {
        val endpoint = request.server.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment(request.track.source.mediaPrefix)
            ?.addPathSegment("song")
            ?.addPathSegment(request.track.id)
            ?.addPathSegment("download")
            ?.addQueryParameter("quality", request.quality)
            ?.build() ?: throw IOException("服务器地址无效")
        val redirect = readInto(endpoint, target, request.maxBytes, reserve, progress, null, allowRedirect = true)
        if (redirect != null) {
            validateRedirect(redirect, request.track.source)
            readInto(redirect, target, request.maxBytes, reserve, progress, null, allowRedirect = false)
        }
        return AudioFileInfo(target.length(), null, null, null, request.quality)
    }

    private suspend fun readInto(
        url: HttpUrl,
        target: File,
        maxBytes: Long,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
        expectedLength: Long?,
        allowRedirect: Boolean,
    ): HttpUrl? {
        val finished = CompletableDeferred<Unit>()
        val call = client.newCall(Request.Builder().url(url).build())
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, error: IOException) {
                            try {
                                if (continuation.isActive) continuation.resumeWithException(IOException("下载网络连接失败", error))
                            } finally { finished.complete(Unit) }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val redirect = response.use { current ->
                                    if (!continuation.isActive) return
                                    if (allowRedirect && current.isRedirect) {
                                        current.header("Location")?.let(url::resolve) ?: throw IOException("下载重定向没有地址")
                                    } else {
                                        saveBody(current, target, maxBytes, expectedLength, reserve, progress) { continuation.isActive }
                                        null
                                    }
                                }
                                if (continuation.isActive) continuation.resume(redirect)
                            } catch (error: Throwable) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            } finally { finished.complete(Unit) }
                        }
                    })
                } catch (error: Exception) {
                    finished.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } finally {
            // The caller can delete the temporary file after the callback has closed the writer.
            withContext(NonCancellable) { finished.await() }
        }
    }

    private fun saveBody(
        response: Response,
        target: File,
        maxBytes: Long,
        expectedLength: Long?,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
        active: () -> Boolean,
    ) {
        if (response.code != 200) {
            if (response.code == 401 || response.code == 403 || response.code == 404) throw ExpiredCdnException(response.code)
            throw IOException("音源未返回完整文件（HTTP ${response.code}）")
        }
        val body = response.body ?: throw IOException("没有下载文件")
        val length = body.contentLength()
        val expected = expectedLength?.takeIf { it > 0 }
        if (length > maxBytes || expected != null && expected > maxBytes) throw IOException("下载文件过大")
        if (expected != null && length >= 0 && length != expected) throw IOException("下载文件长度不一致")
        val knownSize = expected ?: length
        if (knownSize > 0) reserve(knownSize)
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        var lastProgress = -1
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                while (active()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val nextSize = written + count
                    if (nextSize > maxBytes || knownSize > 0 && nextSize > knownSize) throw IOException("下载文件长度不一致")
                    if (knownSize <= 0) reserve(nextSize)
                    output.write(buffer, 0, count)
                    written = nextSize
                    if (knownSize > 0) {
                        val percent = (written * 100 / knownSize).toInt().coerceIn(0, 99)
                        if (percent != lastProgress) {
                            lastProgress = percent
                            progress(percent)
                        }
                    }
                }
            }
        }
        if (written <= 0 || knownSize >= 0 && written != knownSize) throw IOException("下载文件不完整")
    }

    private fun validateRedirect(url: HttpUrl, source: Source) {
        val host = url.host
        fun domain(suffix: String) = host == suffix || host.endsWith(".$suffix")
        val allowed = when (source) {
            Source.QQ -> domain("qqmusic.qq.com") || domain("music.tc.qq.com")
            Source.Netease -> domain("music.126.net") || domain("music.163.com")
            Source.Apple -> false
        }
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || !allowed) {
            throw IOException("下载服务返回未受信任的音源地址")
        }
    }

    private fun verifyAudio(file: File, expectedDurationMs: Long, strict: Boolean, quality: String): AudioFileInfo {
        val extractor = MediaExtractor()
        val format = try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).asSequence().map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (error: Exception) {
            null
        } finally {
            extractor.release()
        }
        if (format == null) {
            if (strict || !looksLikeAudio(file)) throw IOException("下载文件不是可识别的音频")
            return AudioFileInfo(file.length(), null, null, null, quality)
        }
        val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1_000L)
        } else null
        if (strict && (durationMs == null || durationMs <= 0)) throw IOException("下载文件没有完整时长")
        if (expectedDurationMs > 10_000 && durationMs != null && durationMs + 5_000 < expectedDurationMs) {
            throw IOException("音源仅返回试听片段，未保存为完整歌曲")
        }
        val mime = format.getString(MediaFormat.KEY_MIME)
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
        } else null
        return AudioFileInfo(file.length(), durationMs, mime, sampleRate, quality)
    }

    private fun looksLikeAudio(file: File): Boolean {
        val header = ByteArray(16)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            if (read < 4) return false
        }
        fun ascii(offset: Int, value: String) = offset >= 0 && offset + value.length <= header.size &&
            header.copyOfRange(offset, offset + value.length).contentEquals(value.toByteArray())
        val sync = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
        return ascii(0, "fLaC") || ascii(0, "OggS") || ascii(0, "ID3") || ascii(4, "ftyp") ||
            sync == 0x0b77 || sync == 0xac40 || sync == 0xac41 || ascii(0, "RIFF") ||
            ascii(0, "FORM") || sync == 0x7ffe || sync == 0xfe7f
    }

    private class ExpiredCdnException(code: Int) : IOException("签名音源已过期（HTTP $code）")
}
