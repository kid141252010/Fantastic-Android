package io.github.kid141252010.fantastic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/** Native facade for Fantastic's complete QQ search/detail adapter; all account tokens remain server-side. */
class QqApi(private val api: FantasticApi) {
    data class Page(val items: List<JsonObject>, val total: Long, val more: Boolean)
    data class SearchResults(val songs: List<Track>, val groups: Map<String, Page>)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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

    internal suspend fun songTrack(id: String): Track = json.decodeFromJsonElement<QqSong>(song(id)).toTrack()

    internal suspend fun songCredits(id: String): List<SongCreditSection> {
        val songMid = mid(id)
        val payload = buildJsonObject {
            put("song_detail", buildJsonObject {
                put("module", "music.pf_song_detail_svr")
                put("method", "get_song_detail")
                put("param", buildJsonObject {
                    put("song_mid", songMid)
                    put("song_type", 0)
                })
            })
        }
        val url = HttpUrl.Builder().scheme("https").host("u.y.qq.com")
            .addPathSegments("cgi-bin/musicu.fcg")
            .addQueryParameter("data", payload.toString())
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val invalid = "Invalid QQ song credits response"
        val root = try {
            getJson(request)
        } catch (error: InvalidJsonObject) {
            throw IOException(invalid, error)
        }
        val detail = root["song_detail"] as? JsonObject ?: throw IOException(invalid)
        val data = detail["data"] as? JsonObject ?: throw IOException(invalid)
        val returnedMid = ((data["track_info"] as? JsonObject)?.get("mid") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        if (!root.hasSuccessCode() || !detail.hasSuccessCode() || returnedMid != songMid) {
            throw IOException(invalid)
        }
        val field = data["info"] ?: return emptyList()
        val info = field as? JsonArray ?: throw IOException(invalid)
        val entries = buildSet {
            for (item in info) {
                val row = item as? JsonObject ?: continue
                val label = (row["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val role = songCreditRole(label) ?: continue
                val people = row["content"] as? JsonArray ?: continue
                val roles = listOf(role)
                for (person in people) {
                    val name = ((person as? JsonObject)?.get("value") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    add(SongCredit(name, roles))
                }
            }
        }
        return if (entries.isEmpty()) emptyList() else listOf(SongCreditSection(null, entries.toList()))
    }

    suspend fun album(id: String): JsonObject = getJson("/qq/album/${mid(id)}")

    internal suspend fun albumArtists(id: String): String =
        json.decodeFromJsonElement<NeteaseAlbum>(album(id).getValue("album")).artists.joinToString(" / ") { it.name }

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

    private suspend fun getJson(path: String): JsonObject = getJson(Request.Builder().url(api.url(path)).build())

    private suspend fun getJson(request: Request): JsonObject = withContext(Dispatchers.IO) {
        api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("HTTP ${response.code}：响应为空")
            json.parseToJsonElement(body) as? JsonObject ?: throw InvalidJsonObject()
        }
    }

    private class InvalidJsonObject : IOException("Invalid QQ response")

    private fun JsonObject.hasSuccessCode(): Boolean =
        (this["code"] as? JsonPrimitive)?.let { !it.isString && it.intOrNull == 0 } == true

    private fun page(limit: Int, offset: Int) = params(mapOf("limit" to limit.coerceIn(1, 100).toString(), "offset" to offset.coerceIn(0, 10_000).toString()))
    private fun mid(value: String): String = value.takeIf { it.matches(Regex("[0-9A-Za-z]{1,32}")) } ?: throw IllegalArgumentException("Invalid QQ media id")
    private fun params(items: Map<String, String>) = items.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun JsonObject.long(key: String): Long = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
}
