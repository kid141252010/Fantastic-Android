package io.github.kid141252010.fantastic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native client for Fantastic's server-side NetEase adapter. Account credentials stay on the server. */
class NeteaseApi(private val api: FantasticApi, @Suppress("unused") private val prefs: Prefs) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val playClient by lazy { api.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    suspend fun capabilities(server: String = prefs.baseUrl): NeteaseCapabilities = get("/ncm/capabilities", NeteaseCapabilities.serializer(), server).let { response ->
        response.copy(qualities = response.qualities.filter { it.quality in Prefs.NETEASE_QUALITY_IDS })
    }

    suspend fun search(
        query: String,
        kind: NeteaseSearchKind = NeteaseSearchKind.Song,
        limit: Int = 30,
        offset: Int = 0,
    ): NeteaseSearchResult {
        val (pageLimit, pageOffset) = page(limit, offset)
        return get(
            "/ncm/search?q=${enc(query)}&kind=${kind.wireValue}&limit=$pageLimit&offset=$pageOffset",
            NeteaseSearchResult.serializer(),
        )
    }

    suspend fun suggest(query: String): NeteaseSuggestions =
        get("/ncm/suggest?q=${enc(query)}", NeteaseSuggestions.serializer())

    suspend fun song(id: String): NeteaseSongInfo {
        val data = getJson("/ncm/song/${validId(id)}") as? JsonObject
            ?: throw IOException("Invalid Netease song response")
        return NeteaseSongInfo(
            song = json.decodeFromJsonElement(NeteaseSong.serializer(), data),
            meta = data["meta"],
        )
    }

    internal suspend fun songCredits(id: String): List<SongCreditSection> {
        val invalid = "Invalid Netease song credits response"
        val root = getJson(
            "/api/song/lyric?id=${validId(id)}&lv=1&tv=0",
            server = "https://music.163.com",
        ) as? JsonObject ?: throw IOException(invalid)
        val code = root["code"] as? JsonPrimitive
        if (code == null || code.isString || code.intOrNull != 200) throw IOException(invalid)

        val field = root["lrc"] ?: return emptyList()
        val lrc = field as? JsonObject ?: throw IOException(invalid)
        val lyric = lrc["lyric"] ?: return emptyList()
        val text = (lyric as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IOException(invalid)
        val entries = lrcSongCredits(text)
        return if (entries.isEmpty()) emptyList() else listOf(SongCreditSection("网易云音乐歌词署名", entries))
    }

    suspend fun album(id: String): NeteaseAlbumPage =
        get("/ncm/album/${validId(id)}", NeteaseAlbumPage.serializer())

    /** Initial artist page; songs and albums are the upstream defaults (50 and 30). */
    suspend fun artist(id: String): NeteaseArtistPage =
        get("/ncm/artist/${validId(id)}", NeteaseArtistPage.serializer())

    suspend fun artistSongs(id: String, limit: Int = 50, offset: Int = 0): NeteaseSongPage {
        val (pageLimit, pageOffset) = page(limit, offset)
        return get(
            "/ncm/artist/${validId(id)}/songs?limit=$pageLimit&offset=$pageOffset",
            NeteaseSongPage.serializer(),
        )
    }

    suspend fun artistAlbums(id: String, limit: Int = 30, offset: Int = 0): NeteaseAlbumList {
        val (pageLimit, pageOffset) = page(limit, offset)
        return get(
            "/ncm/artist/${validId(id)}/albums?limit=$pageLimit&offset=$pageOffset",
            NeteaseAlbumList.serializer(),
        )
    }

    suspend fun playlist(id: String, limit: Int = 100, offset: Int = 0): NeteasePlaylistPage {
        val (pageLimit, pageOffset) = page(limit, offset)
        return get(
            "/ncm/playlist/${validId(id)}?limit=$pageLimit&offset=$pageOffset",
            NeteasePlaylistPage.serializer(),
        )
    }

    suspend fun qualities(id: String): NeteaseQualities =
        get("/ncm/song/${validId(id)}/qualities", NeteaseQualities.serializer()).let { response ->
            response.copy(qualities = response.qualities.filter { it.quality in Prefs.NETEASE_QUALITY_IDS })
        }

    /** Resolve just before playback. The returned signed CDN URL is only held in this result. */
    suspend fun resolvePlay(id: String, quality: String = "standard", server: String = prefs.baseUrl): NeteaseAudio {
        val base = SourceStatus.normalizeBaseUrl(server) ?: throw IOException("服务器地址无效")
        val request = Request.Builder().url("$base/ncm/song/${validId(id)}/play?quality=${validQuality(quality)}").build()
        val payload = readPlay(request)
        val audio = try {
            json.decodeFromString(NeteaseAudio.serializer(), payload)
        } catch (error: SerializationException) {
            throw IOException("网易云播放入口响应格式无效", error)
        }
        validateCdnUrl(audio.url)
        return audio
    }

    private suspend fun readPlay(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = playClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(NeteasePlayFailure(null, e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val payload = response.use {
                        if (!it.isSuccessful) throw NeteasePlayFailure(it.code)
                        val body = it.body?.source() ?: throw IOException("网易云播放入口响应为空")
                        if (body.request(65_537L)) throw IOException("网易云播放入口响应过大")
                        body.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(payload)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    /** Candidate text and optional original translation/pronunciation text; no account data is returned. */
    suspend fun lyrics(id: String, includeOptionalRaw: Boolean = true): NeteaseLyrics {
        val checkedId = validId(id)
        val result = get("/ncm/song/$checkedId/lyrics", NeteaseLyrics.serializer())
        if (!includeOptionalRaw) return result.copy(rawLrc = result.candidates.any { it.source == "lrc" })

        val trans = if (result.rawTrans) optionalRaw(checkedId, "trans") else null
        val roma = if (result.rawRoma) optionalRaw(checkedId, "roma") else null
        val yroma = if (result.rawRoma) optionalRaw(checkedId, "yroma") else null
        val romaKind = when {
            !roma.isNullOrEmpty() -> "roma"
            !yroma.isNullOrEmpty() -> "yroma"
            else -> if (result.rawRoma) "roma" else null
        }
        return result.copy(
            rawLrc = result.candidates.any { it.source == "lrc" },
            rawTransText = trans,
            rawRomaText = roma,
            rawYromaText = yroma,
            rawRomaKind = romaKind,
        )
    }


    /** Fantastic download endpoint (a server-side 302); this is not a signed CDN URL. */
    fun downloadUrl(id: String, quality: String = "standard"): String =
        api.url("/ncm/song/${validId(id)}/download?quality=${validQuality(quality)}")

    private suspend fun optionalRaw(id: String, kind: String): String? = try {
        val body = request("/ncm/song/$id/lyrics/raw?kind=$kind")
        body.takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        if (e.message == "HTTP 404") null else null
    }

    private suspend fun <T> get(
        path: String,
        strategy: kotlinx.serialization.DeserializationStrategy<T>,
        server: String = prefs.baseUrl,
    ): T = try {
        json.decodeFromString(strategy, request(path, server))
    } catch (e: SerializationException) {
        throw IOException("Invalid Netease response", e)
    }

    private suspend fun getJson(path: String, server: String = prefs.baseUrl): JsonElement = try {
        json.parseToJsonElement(request(path, server))
    } catch (e: SerializationException) {
        throw IOException("Invalid Netease response", e)
    }

    private suspend fun request(path: String, server: String = prefs.baseUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(server.trimEnd('/') + path).get().build()
        api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    internal fun validateCdnUrl(value: String) {
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            throw IOException("Invalid Netease audio URL")
        }
        val host = uri.host?.lowercase().orEmpty()
        val allowedHost = host == "music.126.net" || host == "music.163.com" ||
            host.endsWith(".music.126.net") || host.endsWith(".music.163.com")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || !allowedHost) {
            throw IOException("Invalid Netease audio URL")
        }
    }

    private fun validId(id: String): String {
        if (!ID_PATTERN.matches(id) || id.length > 19 ||
            (id.length == 19 && id > MAX_ID)) {
            throw IllegalArgumentException("Invalid Netease ID")
        }
        return id
    }

    private fun validQuality(quality: String): String {
        if (quality !in Prefs.NETEASE_QUALITY_IDS) throw IllegalArgumentException("Invalid Netease quality")
        return quality
    }

    private fun page(limit: Int, offset: Int): Pair<Int, Int> =
        limit.coerceIn(1, 100) to offset.coerceIn(0, 10_000)

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        val ID_PATTERN = Regex("[1-9][0-9]{0,18}")
        const val MAX_ID = "9223372036854775807"
    }
}

internal class NeteasePlayFailure(val status: Int?, cause: IOException? = null) : IOException(
    if (status == null) "网易云播放入口连接失败" else "网易云播放入口请求失败（HTTP $status）", cause,
)

@Serializable
enum class NeteaseSearchKind(val wireValue: String) {
    @kotlinx.serialization.SerialName("song") Song("song"),
    @kotlinx.serialization.SerialName("album") Album("album"),
    @kotlinx.serialization.SerialName("artist") Artist("artist"),
    @kotlinx.serialization.SerialName("playlist") Playlist("playlist"),
}

@Serializable
data class NeteaseCapabilities(
    val enabled: Boolean = false,
    val qualities: List<NeteaseQualityCapability> = emptyList(),
)

@Serializable
data class NeteaseQualityCapability(
    val quality: String = "",
    val defaultCodec: String = "",
    val contentType: String = "",
    val playbackClass: String = "",
    val sizeField: String? = null,
    val displayName: String = "",
)

@Serializable
data class NeteaseArtistCredit(val name: String = "", val mid: String = "", val avatar: String = "")

@Serializable
data class NeteaseSong(
    val id: String = "",
    val name: String = "",
    val artists: List<NeteaseArtistCredit> = emptyList(),
    val album: String = "",
    val albumMid: String = "",
    val durationMs: Long = 0,
    val cover: String = "",
    val vip: Boolean = false,
    val ttml: Boolean = false,
    val mv: String = "",
)

@Serializable
data class NeteaseSongInfo(
    val song: NeteaseSong,
    val meta: JsonElement? = null,
)

@Serializable
data class NeteaseAlbumSummary(
    val mid: String = "",
    val name: String = "",
    val cover: String = "",
    val publishTime: Long = 0,
    val size: Long = 0,
    val artists: List<NeteaseArtistCredit> = emptyList(),
)

@Serializable
data class NeteaseArtistSummary(
    val mid: String = "",
    val name: String = "",
    val avatar: String = "",
    val albumSize: Long = 0,
    val mvSize: Long = 0,
)

@Serializable
data class NeteasePlaylistSummary(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    val trackCount: Long = 0,
    val playCount: Long = 0,
    val creator: String = "",
    val description: String = "",
)

@Serializable
data class NeteaseSearchResult(
    val songs: List<NeteaseSong> = emptyList(),
    val albums: List<NeteaseAlbumSummary> = emptyList(),
    val artists: List<NeteaseArtistSummary> = emptyList(),
    val playlists: List<NeteasePlaylistSummary> = emptyList(),
    val total: Long = 0,
)

@Serializable
data class NeteaseSuggestSong(val mid: String = "", val name: String = "", val artist: String = "")

@Serializable
data class NeteaseSuggestAlbum(
    val mid: String = "",
    val name: String = "",
    val artist: String = "",
    val cover: String = "",
)

@Serializable
data class NeteaseSuggestions(
    val songs: List<NeteaseSuggestSong> = emptyList(),
    val artists: List<NeteaseArtistSummary> = emptyList(),
    val albums: List<NeteaseSuggestAlbum> = emptyList(),
)

@Serializable
data class NeteaseAlbum(
    val mid: String = "",
    val name: String = "",
    val cover: String = "",
    val publishTime: Long = 0,
    val size: Long = 0,
    val subType: String = "",
    val language: String = "",
    val description: String = "",
    val artists: List<NeteaseArtistCredit> = emptyList(),
)

@Serializable
data class NeteaseAlbumPage(val album: NeteaseAlbum, val songs: List<NeteaseSong> = emptyList())

@Serializable
data class NeteaseSongPage(
    val songs: List<NeteaseSong> = emptyList(),
    val total: Long = 0,
    val more: Boolean = false,
)

@Serializable
data class NeteaseArtist(
    val mid: String = "",
    val name: String = "",
    val avatar: String = "",
    val cover: String = "",
    val briefDesc: String = "",
    val description: String = "",
    val transNames: List<String> = emptyList(),
    val birthday: Long = 0,
    val musicSize: Long = 0,
    val albumSize: Long = 0,
    val mvSize: Long = 0,
)

@Serializable
data class NeteaseArtistPage(
    val artist: NeteaseArtist,
    val songs: List<NeteaseSong> = emptyList(),
    val totalSongs: Long = 0,
    val moreSongs: Boolean = false,
    val albums: List<NeteaseAlbumSummary> = emptyList(),
    val moreAlbums: Boolean = false,
    val mvs: List<JsonElement> = emptyList(),
    val moreMvs: Boolean = false,
)

@Serializable
data class NeteaseAlbumList(val albums: List<NeteaseAlbumSummary> = emptyList(), val more: Boolean = false)

@Serializable
data class NeteasePlaylist(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    val trackCount: Long = 0,
    val description: String = "",
    val creator: String = "",
    val updateTime: Long = 0,
)

@Serializable
data class NeteasePlaylistPage(
    val playlist: NeteasePlaylist? = null,
    val songs: List<NeteaseSong> = emptyList(),
    val more: Boolean = false,
)

@Serializable
data class NeteaseQualities(val qualities: List<NeteaseQuality> = emptyList())

@Serializable
data class NeteaseQuality(
    val quality: String = "",
    val available: Boolean = false,
    val sizeBytes: Long? = null,
    val format: String = "",
    val playback: String = "",
    val codec: String = "",
    val contentType: String = "",
)

@Serializable
data class NeteaseAudio(
    val url: String = "",
    val quality: String = "",
    val bitrate: Long = 0,
    val format: String = "",
    val codec: String = "",
    val contentType: String = "",
    val sizeBytes: Long? = null,
    val expiresIn: Long? = null,
)

@Serializable
data class NeteaseLyricCandidate(val source: String = "", val text: String = "")

@Serializable
data class NeteaseLyrics(
    val candidates: List<NeteaseLyricCandidate> = emptyList(),
    val rawYrc: Boolean = false,
    val rawLrc: Boolean = false,
    val rawTrans: Boolean = false,
    val rawRoma: Boolean = false,
    val rawTransText: String? = null,
    val rawRomaText: String? = null,
    val rawYromaText: String? = null,
    val rawRomaKind: String? = null,
)

