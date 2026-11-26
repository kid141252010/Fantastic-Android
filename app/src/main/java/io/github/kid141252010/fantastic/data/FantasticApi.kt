package io.github.kid141252010.fantastic.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class QqLyricsDocument(val endpoint: HttpUrl, val payload: String)
/** 只调用 Fantastic 服务端的接口。 */
class FantasticApi(private val prefs: Prefs) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun url(path: String) = prefs.baseUrl + path

    private suspend fun <T> get(path: String, de: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path)).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) throw IOException("HTTP ${r.code}：响应为空")
                json.decodeFromString(de, body)
            }
        }

    /** /amp 偶尔返回 200 但响应体为空或不是 JSON，和网页端 ampFetch 一样重试最多 3 次。 */
    private suspend fun <T> getAmp(path: String, de: DeserializationStrategy<T>): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return get(path, de)
            } catch (e: IOException) {
                last = e
            } catch (e: SerializationException) {
                last = e
            }
            delay(300L * (attempt + 1))
        }
        throw last!!
    }

    suspend fun qqSearch(query: String): List<Track> =
        get("/qq/search?type=song&q=${enc(query)}", QqSearch.serializer()).songs.map { it.toTrack() }

    suspend fun amSearch(query: String): List<Track> {
        val storefront = prefs.storefront
        return getAmp(
            "/amp/v1/catalog/$storefront/search?types=songs&limit=25&term=${enc(query)}",
            AmSearch.serializer(),
        ).results.songs?.data.orEmpty().map { it.toTrack(storefront) }
    }

    /** QQ 歌词候选（QRC / LRC 等，均已转成 TTML），保留原始请求地址供注释补充请求使用。 */
    internal suspend fun qqLyrics(mid: String): QqLyricsDocument = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/qq/song/$mid/lyrics")).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val payload = r.body?.string().orEmpty().ifBlank { throw IOException("歌词响应为空") }
            QqLyricsDocument(req.url, payload)
        }
    }

    internal suspend fun qqQrcAnnotations(
        document: QqLyricsDocument,
        rawRoma: Boolean,
        rawTrans: Boolean,
    ): Map<String, JsonElement> = coroutineScope {
        val requests = buildList {
            if (rawRoma) add("rawRomaText" to "qrcroma")
            if (rawTrans) add("rawTransText" to "qrctrans")
        }
        val results = requests.map { (field, kind) ->
            field to async { fetchOptionalQqRaw(document.endpoint, kind) }
        }.map { (field, deferred) -> field to deferred.await() }
        buildMap {
            for ((field, value) in results) if (value != null) put(field, JsonPrimitive(value))
        }
    }

    private suspend fun fetchOptionalQqRaw(endpoint: HttpUrl, kind: String): String? =
        withTimeoutOrNull(2500L) {
            try {
                fetchQqRaw(endpoint.newBuilder()
                    .addPathSegment("raw")
                    .addQueryParameter("kind", kind)
                    .build())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null
            }
        }

    private suspend fun fetchQqRaw(url: HttpUrl): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { current ->
                        if (!current.isSuccessful) return@use null
                        val body = current.body ?: return@use null
                        val source = body.source()
                        if (source.request(1_048_577L)) return@use null
                        source.readUtf8().takeIf { it.isNotBlank() }
                    }
                    if (!continuation.isCancelled) continuation.resume(value)
                } catch (e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        })
    }

    /** QQ 播放地址：服务端 302 到 QQ CDN，ExoPlayer 会跟随跳转。 */
    fun qqPlayUrl(mid: String, quality: String) = url("/qq/song/$mid/play?quality=$quality")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
