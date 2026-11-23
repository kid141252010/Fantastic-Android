package io.github.kid141252010.fantastic.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/** Native facade for Fantastic's complete QQ search/detail adapter; all account tokens remain server-side. */
class QqApi(private val api: FantasticApi) {
    data class Page(val items: List<JsonObject>, val total: Long, val more: Boolean)
    data class SearchResults(val songs: List<Track>, val groups: Map<String, Page>)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val publicCatalogClient = api.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun search(query: String, type: String, limit: Int = 30, offset: Int = 0): Page {
        require(type in setOf("song", "album", "artist", "playlist", "mv")) { "Invalid QQ search kind" }
        val pageSize = limit.coerceIn(1, 100)
        val pageOffset = offset.coerceIn(0, 10_000)
        val response = getJson("/qq/search?${params(mapOf("q" to query, "type" to type, "limit" to pageSize.toString(), "offset" to pageOffset.toString()))}")
        val key = when (type) { "song" -> "songs"; "album" -> "albums"; "artist" -> "artists"; "playlist" -> "playlists"; else -> "mvs" }
        return Page((response[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            response.long("total"), response.bool("more") || response.bool("hasMore"))
    }

    suspend fun searchAll(query: String): SearchResults {
        val kinds = listOf("song" to 30, "artist" to 20, "album" to 20, "playlist" to 20, "mv" to 20)
        val groups = linkedMapOf<String, Page>()
        for ((kind, limit) in kinds) groups[kind] = search(query, kind, limit)
        val songs = groups["song"]?.items.orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement<QqSong>(it).toTrack() }.getOrNull() }
        return SearchResults(songs, groups)
    }

    suspend fun suggest(query: String): JsonObject = getJson("/qq/suggest?${params(mapOf("q" to query))}")
    suspend fun song(id: String): JsonObject = getJson("/qq/song/${mid(id)}")
    suspend fun album(id: String): JsonObject = getJson("/qq/album/${mid(id)}")
    suspend fun artist(id: String): JsonObject = getJson("/qq/artist/${mid(id)}")
    suspend fun artistSongs(id: String, limit: Int = 50, offset: Int = 0): JsonObject =
        getJson("/qq/artist/${mid(id)}/songs?${page(limit, offset)}")
    suspend fun artistAlbums(id: String, limit: Int = 30, offset: Int = 0): JsonObject =
        getJson("/qq/artist/${mid(id)}/albums?${page(limit, offset)}")
    suspend fun artistVideos(id: String, limit: Int = 12, offset: Int = 0): JsonObject =
        getJson("/qq/artist/${mid(id)}/mvs?${page(limit, offset)}")
    suspend fun playlist(id: String, limit: Int = 100, offset: Int = 0): JsonObject =
        getJson("/qq/playlist/${mid(id)}?${page(limit, offset)}")
    suspend fun musicVideo(id: String): JsonObject = getJson("/qq/mv/${mid(id)}")
    fun playUrl(id: String, quality: String): String = api.qqPlayUrl(mid(id), quality)
    fun downloadUrl(id: String, quality: String): String = api.url("/qq/song/${mid(id)}/download?quality=${enc(quality)}")

    /** Public QQ metadata only. The caller uses the verified MID for all Fantastic requests. */
    internal suspend fun numericDetail(kind: String, id: String): JsonObject? {
        require(isNumericCatalogId(id)) { "Invalid QQ numeric ID" }
        val builder = HttpUrl.Builder().scheme("https").host("c.y.qq.com").addPathSegments("v8/fcg-bin")
        when (kind) {
            "song" -> builder.addPathSegment("fcg_play_single_song.fcg").addQueryParameter("songid", id)
            "album" -> builder.addPathSegment("fcg_v8_album_info_cp.fcg").addQueryParameter("albumid", id)
                .addQueryParameter("song_begin", "0").addQueryParameter("song_num", "1")
            "artist" -> {
                if (id.toIntOrNull() == null) return null
                builder.addPathSegment("fcg_v8_singer_album.fcg").addQueryParameter("singerid", id)
                    .addQueryParameter("begin", "0").addQueryParameter("num", "1")
            }
            else -> throw IllegalArgumentException("Invalid QQ ID lookup kind")
        }
        val body = try {
            publicCatalogClient.readCatalogBody(Request.Builder().url(builder.addQueryParameter("format", "json").build()).build(),
                maxBytes = 1_048_576)
        } catch (e: IOException) {
            if (e.message == "HTTP 404") return null
            throw e
        }
        val response = json.parseToJsonElement(body) as? JsonObject ?: throw IOException("QQ 音乐 ID 响应格式无效")
        val code = response.text("code")?.toIntOrNull() ?: throw IOException("QQ 音乐 ID 响应缺少状态码")
        if (code != 0) {
            val message = response.text("message").orEmpty()
            if (kind == "album" && "getAlbumInfo failed" in message && missingAlbumCode.containsMatchIn(message)) return null
            throw IOException("QQ 音乐 ID 查询失败（code=$code）")
        }
        val data = if (kind == "song") {
            val songs = response["data"] as? JsonArray ?: throw IOException("QQ 音乐 ID 响应缺少歌曲列表")
            songs.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { song -> song.text("id") == id } } ?: return null
        } else {
            response["data"] as? JsonObject ?: throw IOException("QQ 音乐 ID 响应缺少资源")
        }
        val singer = kind == "artist"
        if (data.text(if (singer) "singer_id" else "id") != id) return null
        val mid = data.text(if (singer) "singer_mid" else "mid")
        if (singer && mid == "") return null
        val name = data[if (singer) "singer_name" else "name"] as? JsonPrimitive
        if (mid == null || !isResolvedMid(mid) || name?.isString != true || name.contentOrNull.isNullOrBlank()) {
            throw IOException("QQ 音乐 ID 响应缺少有效的 MID 或名称")
        }
        return data
    }

    private suspend fun getJson(path: String): JsonObject {
        val body = api.client.readCatalogBody(Request.Builder().url(api.url(path)).build())
        if (body.isBlank()) throw IOException("QQ 音乐响应为空")
        return json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Invalid QQ response")
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun page(limit: Int, offset: Int) = params(mapOf("limit" to limit.coerceIn(1, 100).toString(), "offset" to offset.coerceIn(0, 10_000).toString()))
    private fun mid(value: String): String = value.takeIf(mediaId::matches) ?: throw IllegalArgumentException("Invalid QQ media id")
    private fun params(items: Map<String, String>) = items.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun JsonObject.long(key: String): Long = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true

    internal companion object {
        private val mediaId = Regex("[0-9A-Za-z]{1,32}")
        private val missingAlbumCode = Regex("\\bret=36\\b")

        fun isResolvedMid(value: String): Boolean = mediaId.matches(value) && value.any { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
