package io.github.kid141252010.fantastic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    suspend fun amSearch(query: String): List<Track> =
        getAmp(
            "/amp/v1/catalog/${prefs.storefront}/search?types=songs&limit=25&term=${enc(query)}",
            AmSearch.serializer(),
        ).results.songs?.data.orEmpty().map { it.toTrack() }

    /** QQ 歌词候选（QRC / LRC 等，均已转成 TTML），原样交给歌词页解析 */
    suspend fun qqLyricsJson(mid: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/qq/song/$mid/lyrics")).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty().ifBlank { throw IOException("歌词响应为空") }
        }
    }

    /** QQ 播放地址：服务端 302 到 QQ CDN，ExoPlayer 会跟随跳转。 */
    fun qqPlayUrl(mid: String, quality: String) = url("/qq/song/$mid/play?quality=$quality")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
