package io.github.kid141252010.fantastic.playback

import android.util.Log
import io.github.kid141252010.fantastic.BuildConfig
import io.github.kid141252010.fantastic.data.NeteaseApi
import io.github.kid141252010.fantastic.data.NeteaseAudio
import io.github.kid141252010.fantastic.data.NeteasePlayFailure
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val AUDIO_LANES = 3
private const val TAG = "FantasticAudio"

internal enum class AudioStage(val label: String) {
    Download("Fantastic 下载入口"), Play("Fantastic 播放入口"), Cdn("CDN 音源"),
}

internal class AudioHttpFailure(val stage: AudioStage, val status: Int, val total: Long? = null) :
    IOException("${stage.label}请求失败（HTTP $status）") {
    val shouldResolveAgain get() = stage == AudioStage.Cdn && status in setOf(401, 403, 404, 410)
    val rangeUnsupported get() = status == 400 || status == 405 || status == 501 || status == 416 && total == null
}

internal class AudioRangeRejected : IOException("音源不支持可核验的分段读取")
internal class AudioChanged : IOException("音源内容已变化，需重新载入")

internal data class AudioRange(val first: Long, val last: Long? = null) {
    init { require(first >= 0 && (last == null || last >= first)) }
    val length get() = checkNotNull(last) - first + 1
}

internal data class AudioAddress(val url: HttpUrl, val source: Source, val stage: AudioStage) {
    fun validate() {
        val host = url.host
        fun matches(domain: String) = host == domain || host.endsWith(".$domain")
        val allowed = when (source) {
            Source.QQ -> matches("qqmusic.qq.com") || matches("music.tc.qq.com")
            Source.Netease -> matches("music.126.net") || matches("music.163.com")
            Source.Apple -> false
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            stage == AudioStage.Cdn && (!allowed || !url.isHttps || url.port != 443)
        ) throw IOException("音源重定向地址不受信任")
    }
}

internal data class AudioObject(val address: AudioAddress, val size: Long, val etag: String)

internal data class NativeSong(val source: Source, val id: String, val quality: String, val server: String) {
    fun route(stage: AudioStage): AudioAddress {
        require(stage != AudioStage.Cdn && source != Source.Apple && source.validSongId(id))
        val base = SourceStatus.normalizeBaseUrl(server)?.toHttpUrl() ?: throw IOException("服务器地址无效")
        val url = base.newBuilder().addPathSegment(source.mediaPrefix).addPathSegment("song").addPathSegment(id)
            .addPathSegment(if (stage == AudioStage.Play) "play" else "download").addQueryParameter("quality", quality).build()
        return AudioAddress(url, source, stage)
    }
}

internal fun NeteaseAudio.cdnAddress(): AudioAddress = AudioAddress(
    url.toHttpUrlOrNull() ?: throw IOException("网易云音源地址无效"), Source.Netease, AudioStage.Cdn,
).also { it.validate() }

internal suspend fun NeteaseApi.resolveAudio(song: NativeSong): NeteaseAudio =
    resolvePlay(song.id, song.quality, song.server).also {
        if (it.quality.isBlank()) throw IOException("网易云播放入口未返回实际音质")
    }

internal fun IOException.audioRetryable(): Boolean = when (this) {
    is AudioHttpFailure -> status == 502 || status == 503 || status == 504
    is NeteasePlayFailure -> status == null || status == 502 || status == 503 || status == 504
    is AudioReadFailure -> true
    else -> false
}

internal class NativeAudioHttp(client: OkHttpClient) {
    private val ids = AtomicLong()
    private val bulkSlots = Semaphore(AUDIO_LANES)
    // Playback never has to wait for the foreground service or cache worker to free an HTTP slot.
    private val playback = mediaClient(client)
    private val bulk = mediaClient(client)

    fun transfer(song: NativeSong, downloading: Boolean): Transfer =
        Transfer(song.source, song.id, song.quality, if (BuildConfig.DEBUG) ids.incrementAndGet() else 0, downloading)

    internal inner class Transfer(
        private val source: Source,
        private val id: String,
        private val quality: String,
        private val number: Long,
        private val downloading: Boolean,
    ) {
        suspend fun <T> background(block: suspend () -> T): T = bulkSlots.withPermit { block() }

        suspend fun open(initial: AudioAddress, range: AudioRange? = null, etag: String? = null): AudioBody {
            var address = initial
            repeat(5) {
                address.validate()
                val current = address
                val request = Request.Builder().url(current.url)
                    .header("User-Agent", "Fantastic-Android").header("Accept-Encoding", "identity")
                    .apply {
                        if (range != null) header("Range", "bytes=${range.first}-${range.last ?: ""}")
                        if (etag != null) header("If-Range", etag)
                        if (BuildConfig.DEBUG) tag(AudioTrace::class.java, AudioTrace(
                            "${if (downloading) "download" else "play"}#$number $source:$id $quality ${current.stage} ${current.url.host} " +
                                (range?.let { "${it.first}-${it.last ?: "end"}" } ?: "full"),
                        ))
                    }.build()
                val call = (if (downloading) bulk else playback).newCall(request)
                val response = call.awaitHeaders(current.stage)
                if (response.code in redirectCodes && etag == null) {
                    address = response.use {
                        val next = it.header("Location")?.let(current.url::resolve) ?: throw IOException("音源重定向缺少地址")
                        val sameOrigin = next.scheme == current.url.scheme && next.host == current.url.host && next.port == current.url.port
                        AudioAddress(next, source, if (sameOrigin) current.stage else AudioStage.Cdn)
                    }
                } else {
                    try {
                        if (response.code != 200 && response.code != 206) {
                            val size = if (response.code == 416) response.header("Content-Range")
                                ?.let { unsatisfiedRange.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() } else null
                            throw AudioHttpFailure(current.stage, response.code, size)
                        }
                        if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
                            throw IOException("音源未返回原始字节")
                        }
                        val body = response.body ?: throw IOException("音源响应为空")
                        val type = body.contentType()
                        if (type?.type == "text" || type?.subtype?.endsWith("json") == true) throw IOException("音源未返回音频")
                        return AudioBody(call, response, current)
                    } catch (error: IOException) {
                        call.cancel()
                        response.close()
                        throw error
                    }
                }
            }
            throw IOException("音源重定向次数过多")
        }

        suspend fun openRange(audio: AudioObject, bytes: AudioRange): AudioBody {
            val body = open(audio.address, bytes, audio.etag)
            try {
                if (body.etag != audio.etag) throw AudioChanged()
                if (body.status != 206) {
                    if (body.length >= 0 && body.length != audio.size) throw AudioChanged()
                    throw AudioRangeRejected()
                }
                if (body.totalFor(bytes) != audio.size) throw AudioChanged()
                return body
            } catch (error: IOException) {
                body.cancel()
                body.close()
                throw error
            }
        }

        fun note(event: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "transfer#$number $source:$id $quality $event")
        }
    }

    private companion object {
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        val unsatisfiedRange = Regex("bytes \\*/([0-9]+)", RegexOption.IGNORE_CASE)

        fun mediaClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool())
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) eventListenerFactory { call -> call.request().tag(AudioTrace::class.java) ?: EventListener.NONE } }
            .build()
    }
}

internal class AudioBody(private val call: Call, private val response: Response, val address: AudioAddress) : Closeable {
    private val body = checkNotNull(response.body)
    val status get() = response.code
    val length = body.contentLength()
    val mime get() = response.header("Content-Type")
    val etag = response.header("ETag")?.takeIf { strongTag.matches(it) }

    fun totalFor(range: AudioRange): Long {
        val fields = contentRange.matchEntire(response.header("Content-Range").orEmpty())?.groupValues
            ?: throw AudioRangeRejected()
        val start = fields[1].toLongOrNull()
        val end = fields[2].toLongOrNull()
        val size = fields[3].toLongOrNull()
        if (status != 206 || start == null || end == null || size == null || start >= size || end < start ||
            start != range.first || end != minOf(range.last ?: Long.MAX_VALUE, size - 1) || length >= 0 && length != end - start + 1
        ) throw AudioRangeRejected()
        return size
    }

    /** The caller cannot reuse a file until the canceled body reader has stopped writing. */
    suspend fun <T> read(block: suspend (InputStream, Job) -> T): T = coroutineScope {
        val owner = coroutineContext.job
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { if (!owner.isActive) call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { block(body.byteStream(), owner) }
        } catch (error: Exception) {
            call.cancel()
            throw error
        } finally {
            cancellation.cancel()
        }
    }

    fun cancel() = call.cancel()
    override fun close() = response.close()

    private companion object {
        val strongTag = Regex("\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"")
        val contentRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
    }
}

private suspend fun Call.awaitHeaders(stage: AudioStage): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(AudioReadFailure(stage, e))
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, unopened, _ -> call.cancel(); unopened.close() }
        }
    })
}

internal class AudioReadFailure(stage: AudioStage, cause: IOException) : IOException("${stage.label}连接中断", cause)

internal fun InputStream.readAudio(buffer: ByteArray, offset: Int, count: Int, stage: AudioStage): Int = try {
    read(buffer, offset, count)
} catch (error: IOException) {
    throw AudioReadFailure(stage, error)
}

internal suspend fun AudioBody.readExact(size: Int, exhaust: Boolean = true): ByteArray = read { input, owner ->
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        owner.ensureActive()
        val count = input.readAudio(bytes, offset, size - offset, address.stage)
        if (count <= 0) throw AudioReadFailure(address.stage, EOFException("音源片段提前结束"))
        offset += count
    }
    if (exhaust && input.readAudio(ByteArray(1), 0, 1, address.stage) != -1) throw AudioRangeRejected()
    bytes
}

/** Wire events, not coroutine start times. Never include path, query, response body or credentials. */
private class AudioTrace(private val label: String) : EventListener() {
    private var link = ""
    override fun connectionAcquired(call: Call, connection: Connection) {
        link = "${connection.protocol()} conn=${System.identityHashCode(connection)}"
    }
    override fun requestHeadersEnd(call: Call, request: Request) { Log.d(TAG, "$label start $link") }
    override fun responseHeadersEnd(call: Call, response: Response) { Log.d(TAG, "$label status=${response.code} $link") }
    override fun responseBodyEnd(call: Call, byteCount: Long) { Log.d(TAG, "$label end bytes=$byteCount $link") }
    override fun callFailed(call: Call, ioe: IOException) { Log.d(TAG, "$label ${if (call.isCanceled()) "cancel" else "failed"} $link") }
}
