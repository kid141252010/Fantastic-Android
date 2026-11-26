package io.github.kid141252010.fantastic.data

import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Native catalogue access through the configured Fantastic server, never Apple credentials or keys.
 * Contracts (upstream f1ecbdd0654ec1efc6d8248cfe14925fddcc88cd):
 * https://github.com/kid141252010/Fantastic/blob/f1ecbdd0654ec1efc6d8248cfe14925fddcc88cd/src/lib.rs
 * See also web/amp.mjs, web/views/{search-results,home,browse,categories,album,artist,playlist}.mjs
 * and src/handlers.rs::LyricsQuery in the pinned .work/fantastic-upstream checkout.
 *
 * Storefront defaults are read from Prefs on each call; explicit storefronts never change the selection.
 * Pass a language returned by [catalogLanguage] to catalogue methods. A null language leaves Apple's
 * storefront default in effect. Attribute/editorial JSON is retained, including unfamiliar resource types.
 */
class AppleCatalogApi(
    private val api: FantasticApi,
    private val prefs: Prefs,
    motionArtworkIndex: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val motionArtworkFile = AtomicFile(motionArtworkIndex)
    private val motionArtworkMutex = Mutex()
    private var loadedMotionArtwork: LinkedHashMap<String, AppleMotionArtwork>? = null
    private val lyricsClient: OkHttpClient = api.client.newBuilder()
        .readTimeout(api.client.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var amllEndpoint: Pair<String, HttpUrl>? = null

    enum class Entity(val route: String) {
        Song("songs"), Album("albums"), Artist("artists"), Playlist("playlists"),
        MusicVideo("music-videos"), UploadedVideo("uploaded-videos"), Station("stations"),
    }

    enum class Editorial(val route: String) {
        Grouping("groupings"), Room("rooms"), MultiRoom("multirooms"),
    }

    /** The full proxy response remains available for charts, metadata and unrecognized API fields. */
    suspend fun request(
        path: String,
        params: Map<String, String> = emptyMap(),
        language: String? = null,
    ): AppleCatalogResponse = request(proxyUrl(path, params, language))

    private suspend fun request(url: HttpUrl): AppleCatalogResponse {
        val body = requestJson(url)
        return json.decodeFromJsonElement<AppleCatalogResponse>(body).copy(
            raw = body,
            requestStorefront = REQUEST_COUNTRY.find(url.encodedPath)?.groupValues?.get(1).normalizedAppleCountry(),
        )
    }

    suspend fun search(
        term: String,
        types: List<Entity> = SEARCH_TYPES,
        limit: Int = 21,
        storefront: String = prefs.storefront,
        language: String? = null,
    ): AppleSearchResult {
        require(term.isNotBlank()) { "Search term is empty" }
        require(types.isNotEmpty() && limit > 0)
        return searchResult(request("${catalog(storefront)}/search", SEARCH_PAGE_PARAMS + mapOf(
            "term" to term, "types" to types.joinToString(",") { it.route }, "limit" to limit.toString(),
        ), language))
    }

    /** Search next URLs omit essential parameters; upstream search-results.mjs restores these too. */
    suspend fun searchPage(next: String, language: String? = null): AppleSearchResult =
        searchResult(request(next, SEARCH_PAGE_PARAMS, language))

    suspend fun suggestions(
        term: String,
        storefront: String = prefs.storefront,
        language: String? = null,
    ): List<AppleSearchSuggestion> {
        require(term.isNotBlank()) { "Search term is empty" }
        val response = request("${catalog(storefront)}/search/suggestions", SUGGESTION_PARAMS + ("term" to term), language)
        return (response.results["suggestions"] as? JsonArray).orEmpty().map {
            json.decodeFromJsonElement<AppleSearchSuggestion>(it)
        }
    }

    /** Songs include album, artist, credit and video relationships; collections include tracks/views. */
    suspend fun entity(
        type: Entity,
        id: String,
        storefront: String = prefs.storefront,
        language: String? = null,
        params: Map<String, String> = emptyMap(),
    ): AppleCatalogResource = entity(type, id, storefront, prefs.baseUrl, language, params)

    private suspend fun entity(
        type: Entity,
        id: String,
        storefront: String,
        baseUrl: String,
        language: String? = null,
        params: Map<String, String> = emptyMap(),
    ): AppleCatalogResource {
        val path = "${catalog(storefront)}/${type.route}/${segment(id)}"
        val defaults = entityParams(type)
        val query = if (params.isEmpty()) defaults else defaults + params
        val response = request(proxyUrl(path, query, language, baseUrl))
        return response.data.firstOrNull { it.id == id }
            ?: throw IOException("Apple Music resource not found")
    }

    internal suspend fun songCredits(id: String, storefront: String): List<SongCreditSection> {
        val path = "${catalog(storefront)}/${Entity.Song.route}/${segment(id)}"
        val response = try {
            request(path, mapOf("include" to "credits", "extend[role-categories]" to "titleInTitleCase,title"))
        } catch (error: AppleCatalogHttpException) {
            if (error.statusCode != 400) throw error
            request(path)
        }
        val song = response.data.firstOrNull { it.id == id && it.type == Entity.Song.route }
            ?: throw IOException("Apple Music resource not found")
        val sections = song.relationships["credits"]?.data.orEmpty().mapNotNull { category ->
            if (category.type != "role-categories") return@mapNotNull null
            val artists = category.relationships["credit-artists"]?.data ?: return@mapNotNull null
            val entries = buildSet {
                for (artist in artists) {
                    if (artist.type != "credit-artists") continue
                    val name = artist.attributes.nonBlankString("name")?.trim() ?: continue
                    val roles = (artist.attributes["roleNames"] as? JsonArray)?.mapNotNullTo(LinkedHashSet()) { value ->
                        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                    }?.toList().orEmpty()
                    add(SongCredit(name, roles))
                }
            }
            if (entries.isEmpty()) return@mapNotNull null
            val title = category.attributes.nonBlankString("titleInTitleCase")
                ?: category.attributes.nonBlankString("title")
            SongCreditSection(title?.trim(), entries.toList())
        }
        if (sections.isNotEmpty()) return sections
        val composer = song.attributes.nonBlankString("composerName") ?: return emptyList()
        return listOf(SongCreditSection(null, listOf(SongCredit(composer, listOf("作曲")))))
    }

    /** Batch lookup; API limits remain type-specific and are not silently truncated. */
    suspend fun entities(
        type: Entity,
        ids: List<String>,
        storefront: String = prefs.storefront,
        language: String? = null,
        params: Map<String, String> = emptyMap(),
    ): AppleCatalogResponse {
        require(ids.isNotEmpty())
        ids.forEach { segment(it) }
        return request("${catalog(storefront)}/${type.route}", entityParams(type) + params + ("ids" to ids.joinToString(",")), language)
    }

    /** Follow an opaque catalogue/editorial next URL through this server, not its own origin. */
    suspend fun page(
        next: String,
        language: String? = null,
        params: Map<String, String> = emptyMap(),
    ): AppleCatalogResponse = request(next, PAGE_PARAMS + params, language)

    /** Track pagination for either album or playlist relationships. Does not fetch all pages eagerly. */
    suspend fun trackPage(next: String, language: String? = null): AppleCatalogResponse =
        page(next, language, TRACK_PARAMS + ("extend" to "extendedAssetUrls"))

    suspend fun browse(storefront: String = prefs.storefront, language: String? = null): AppleCatalogResponse =
        grouping("music", storefront, language)

    /** Public editorial radio catalogue. Personal station authentication/playback belongs to the player. */
    suspend fun radio(storefront: String = prefs.storefront, language: String? = null): AppleCatalogResponse =
        grouping("radio", storefront, language)

    private suspend fun grouping(name: String, storefront: String, language: String?): AppleCatalogResponse =
        request("/v1/editorial/${country(storefront)}/groupings", GROUPING_PARAMS + ("name" to name), language)

    suspend fun editorial(
        type: Editorial,
        id: String,
        storefront: String = prefs.storefront,
        language: String? = null,
    ): AppleCatalogResponse {
        val params = when (type) {
            Editorial.Grouping -> GROUPING_PARAMS + ("include[groupings]" to "curator")
            Editorial.Room -> ROOM_PARAMS
            Editorial.MultiRoom -> MULTIROOM_PARAMS
        }
        return request("/v1/editorial/${country(storefront)}/${type.route}/${segment(id)}", params, language)
    }

    suspend fun curator(
        id: String,
        storefront: String = prefs.storefront,
        language: String? = null,
    ): AppleCatalogResponse = request(catalog(storefront), CURATOR_PARAMS + mapOf(
        "ids[curators]" to segment(id), "ids[apple-curators]" to id,
    ), language)

    /** Includes cityCharts and dailyGlobalTopCharts in results, without flattening away chart metadata. */
    suspend fun charts(
        genre: String = "34",
        limit: Int = 50,
        storefront: String = prefs.storefront,
        language: String? = null,
    ): AppleCatalogResponse {
        require(limit > 0)
        return request("${catalog(storefront)}/charts", CHART_PARAMS + mapOf("genre" to genre, "limit" to limit.toString()), language)
    }

    suspend fun genres(storefront: String = prefs.storefront, language: String? = null): AppleCatalogResponse =
        request("${catalog(storefront)}/genres", language = language)

    /** Search landing shelves include activities, apple-curators and editorial-items. */
    suspend fun categories(storefront: String = prefs.storefront, language: String? = null): AppleCatalogResponse =
        request("/v1/recommendations/${country(storefront)}", mapOf(
            "name" to "search-landing", "platform" to "web", "omit[resource]" to "autos",
            "extend" to "editorialArtwork", "art[url]" to "c,f",
            "types" to "activities,apple-curators,editorial-items",
        ), language)

    /** All storefront pages, following Apple's next URLs as in upstream i18n.js. */
    suspend fun storefronts(): List<AppleStorefront> {
        val result = linkedMapOf<String, AppleStorefront>()
        val seen = hashSetOf<String>()
        var next: String? = "/v1/storefronts"
        while (next != null) {
            val path = next
            if (!seen.add(path)) throw IOException("Repeated Apple storefront page")
            val response = request(path)
            for (resource in response.data) {
                val tags = resource.attributes.strings("supportedLanguageTags")
                if (tags.isEmpty()) continue
                val id = resource.id.lowercase(Locale.ROOT)
                result[id] = AppleStorefront(id, resource.name, tags,
                    resource.attributes.text("defaultLanguageTag") ?: tags.first())
            }
            next = response.next?.takeIf { it.isNotBlank() }
        }
        if (result.isEmpty()) throw IOException("Apple storefront response is empty")
        return result.values.toList()
    }

    /** Unsupported selections resolve to the storefront default, not an unrelated device locale. */
    suspend fun catalogLanguage(preferred: String? = null, storefront: String = prefs.storefront): String? {
        val cc = country(storefront)
        val info = storefronts().firstOrNull { it.id == cc } ?: return null
        return preferred?.takeIf { it in info.supportedLanguageTags } ?: info.defaultLanguageTag
    }

    /** Raw TTML, null only for the upstream's documented no-lyrics (404) response. */
    suspend fun songTtml(id: String, options: AppleLyricsOptions): String? {
        require(id.isNotEmpty() && id.all { it in '0'..'9' }) { "Invalid Apple song ID" }
        require(isValidAppleLyricsTag(options.language) && isValidAppleLyricsTag(options.script)) {
            "Invalid lyrics language/script tag"
        }
        val url = api.url("/lyrics/$id").toHttpUrl().newBuilder()
            .addQueryParameter("language", options.language)
            .addQueryParameter("script", options.script)
            .build()
        val response = fetch(url, lyricsClient)
        if (response.code == 404) return null
        if (response.code !in 200..299) throw httpFailure(response)
        if (response.body.isBlank()) throw IOException("Apple lyrics response is empty")
        return response.body
    }

    suspend fun playerMotionArtwork(track: Track): AppleMotionArtwork? {
        if (track.source != Source.Apple) return null

        val baseUrl = prefs.baseUrl
        val storefront = track.country.normalizedAppleCountry() ?: prefs.storefront
        val knownAlbumId = track.albumId.takeIf(String::isMotionCatalogId)
        val songKey = if (knownAlbumId == null) {
            if (!track.id.isMotionCatalogId()) return null
            val key = motionArtworkKey(baseUrl, storefront, "song", track.id)
            readMotionArtwork(key)?.let { return it }
            key
        } else null
        val albumId = knownAlbumId ?: run {
            val song = entity(Entity.Song, track.id, storefront, baseUrl = baseUrl)
            song.relationships["albums"]?.data?.firstOrNull { it.id.isMotionCatalogId() }?.id
        } ?: return null
        val albumKey = motionArtworkKey(baseUrl, storefront, "album", albumId)
        val cachedAlbum = readMotionArtwork(albumKey)
        if (cachedAlbum != null && songKey == null) return cachedAlbum

        val artwork = cachedAlbum ?: run {
            val album = entity(Entity.Album, albumId, storefront, baseUrl = baseUrl)
            val editorial = album.attributes["editorialVideo"] as? JsonObject ?: return null
            val tall = editorial.motionVariant(MOTION_TALL_KEYS, previewHeight = 1440)
            val square = editorial.motionVariant(MOTION_SQUARE_KEYS, previewHeight = 1080)
            if (tall == null && square == null) return null
            AppleMotionArtwork(tall, square)
        }
        saveMotionArtwork(baseUrl, albumKey, songKey, artwork)
        return artwork
    }

    private suspend fun readMotionArtwork(key: String): AppleMotionArtwork? = withContext(Dispatchers.IO) {
        motionArtworkMutex.withLock { motionArtworkEntries()[key] }
    }

    private suspend fun saveMotionArtwork(
        baseUrl: String,
        albumKey: String,
        songKey: String?,
        artwork: AppleMotionArtwork,
    ) = withContext(Dispatchers.IO) {
        motionArtworkMutex.withLock {
            ensureActive()
            if (prefs.baseUrl != baseUrl) return@withLock
            val entries = motionArtworkEntries()
            entries.putRecentMotionArtwork(albumKey, artwork)
            if (songKey != null) entries.putRecentMotionArtwork(songKey, artwork)
            try {
                val stream = motionArtworkFile.startWrite()
                try {
                    val snapshot = entries.map { (key, value) -> CachedMotionArtwork(key, value) }
                    stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                    motionArtworkFile.finishWrite(stream)
                } catch (error: Exception) {
                    motionArtworkFile.failWrite(stream)
                    throw error
                }
            } catch (error: Exception) {
                Log.w("FantasticMotion", "Could not save artwork index", error)
            }
        }
    }

    // Both callers hold the index mutex on IO.
    private fun motionArtworkEntries(): LinkedHashMap<String, AppleMotionArtwork> {
        loadedMotionArtwork?.let { return it }
        val entries = LinkedHashMap<String, AppleMotionArtwork>()
        try {
            val snapshot = motionArtworkFile.openRead().use {
                json.decodeFromString<List<CachedMotionArtwork>>(it.reader(Charsets.UTF_8).readText())
            }
            for (entry in snapshot) {
                val tall = entry.artwork.tall?.validatedCachedVariant()
                val square = entry.artwork.square?.validatedCachedVariant()
                if (tall != null || square != null) {
                    entries.putRecentMotionArtwork(entry.key, AppleMotionArtwork(tall, square))
                }
            }
        } catch (error: FileNotFoundException) {
            if (motionArtworkFile.baseFile.exists()) Log.w("FantasticMotion", "Could not open artwork index", error)
        } catch (error: Exception) {
            Log.w("FantasticMotion", "Could not read artwork index (${error.javaClass.simpleName})")
        }
        loadedMotionArtwork = entries
        return entries
    }

    /** AMLL provenance only; the native lyric surface continues to render the returned TTML. */
    suspend fun amllSongTtml(id: String): String? {
        require(id.isNotEmpty() && id.all { it in '0'..'9' }) { "Invalid Apple song ID" }
        val url = amllLyricsEndpoint().newBuilder().addQueryParameter("appleMusicId", id).build()
        val response = fetch(url, lyricsClient)
        if (response.code == 404 || response.code == 204) return null
        if (response.code !in 200..299) throw IOException("AMLL HTTP ${response.code}")
        val body = try {
            json.parseToJsonElement(response.body) as? JsonObject
        } catch (error: SerializationException) {
            throw IOException("AMLL lyrics response is not JSON", error)
        } ?: throw IOException("AMLL lyrics response is not an object")
        return (body["data"] as? JsonObject)?.text("lyrics")?.takeIf { it.isNotBlank() }
    }

    private suspend fun amllLyricsEndpoint(): HttpUrl {
        val hostUrl = api.url("/assets/host.js")
        amllEndpoint?.let { (server, endpoint) -> if (server == hostUrl) return endpoint }
        val response = fetch(hostUrl.toHttpUrl(), lyricsClient)
        if (response.code !in 200..299) throw httpFailure(response)
        val config = HOST_CONFIG.find(response.body)?.groupValues?.get(1)
            ?: throw IOException("Fantastic lyrics host configuration is invalid")
        val host = try {
            json.parseToJsonElement(config) as? JsonObject
        } catch (error: SerializationException) {
            throw IOException("Fantastic lyrics host configuration is not JSON", error)
        } ?: throw IOException("Fantastic lyrics host configuration is not an object")
        val base = host.text("amllApi")?.takeIf { it.isNotBlank() } ?: "https://api.amll.dev"
        val endpoint = base.toHttpUrl().newBuilder().addPathSegments("v1/lyrics/get").build()
        amllEndpoint = hostUrl to endpoint
        return endpoint
    }

    private fun searchResult(response: AppleCatalogResponse): AppleSearchResult {
        val order = ((response.meta["results"] as? JsonObject)?.get("order") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val keys = (order + response.results.keys).distinct()
        val groups = keys.mapNotNull { key ->
            val value = response.results[key] as? JsonObject ?: return@mapNotNull null
            if (value["data"] !is JsonArray) return@mapNotNull null
            AppleSearchGroup(key, json.decodeFromJsonElement<AppleCatalogPage>(value))
        }
        return AppleSearchResult(groups, response)
    }

    /** Reject external or traversing next links; all requests use FantasticApi's configured base URL. */
    private fun proxyUrl(
        path: String,
        params: Map<String, String>,
        language: String?,
        baseUrl: String = prefs.baseUrl,
    ): HttpUrl {
        val relative = if (path.startsWith("/amp/v1/")) path.removePrefix("/amp") else path
        val route = relative.substringBefore('?')
        require(!relative.contains('#') && !route.contains("..") && ROUTE.matches(route)) {
            "Invalid Apple catalogue path"
        }
        val builder = "$baseUrl/amp$relative".toHttpUrl().newBuilder()
        params.forEach { (key, value) -> if (value.isNotEmpty()) builder.setQueryParameter(key, value) }
        language?.takeIf { it.isNotBlank() }?.let { builder.setQueryParameter("l", it) }
        return builder.build()
    }

    private suspend fun requestJson(initialUrl: HttpUrl): JsonObject {
        var url = initialUrl
        var attempt = 0
        var languageFallback = false
        var serverFallback = false
        while (true) {
            val response = try {
                fetch(url)
            } catch (error: IOException) {
                if (attempt >= 3) throw error
                delay(500L shl attempt++)
                continue
            }
            if (response.code == 400 && !languageFallback && url.queryParameter("l") != null) {
                languageFallback = true
                url = url.newBuilder().removeAllQueryParameters("l").build()
                continue
            }
            if (response.code in RETRY_STATUS && attempt < 3) {
                val retryAfter = response.retryAfter?.toLongOrNull()?.coerceIn(0, 32)?.times(1000L) ?: 0L
                delay(maxOf(500L shl attempt++, retryAfter))
                continue
            }
            // amp.mjs gives remaining 5xx one last delayed request after ampFetch's retries.
            if (response.code >= 500 && !serverFallback) {
                serverFallback = true
                delay(600)
                continue
            }
            if (response.code !in 200..299) throw httpFailure(response)
            try {
                return json.parseToJsonElement(response.body) as? JsonObject
                    ?: throw IOException("Apple catalogue response is not an object")
            } catch (error: SerializationException) {
                if (attempt >= 3) throw IOException("Apple catalogue response is not JSON", error)
            } catch (error: IOException) {
                if (attempt >= 3) throw error
            }
            delay(500L shl attempt++)
        }
    }

    private fun httpFailure(response: HttpBody): AppleCatalogHttpException {
        val message = try {
            (json.parseToJsonElement(response.body) as? JsonObject)?.text("msg")
                ?.substringBefore('\n')?.substringBefore('\r')?.trim()?.take(320)?.takeIf { it.isNotEmpty() }
        } catch (_: SerializationException) {
            null
        }
        return AppleCatalogHttpException(response.code, message)
    }

    private data class HttpBody(val code: Int, val body: String, val retryAfter: String?)

    private suspend fun fetch(url: HttpUrl, client: OkHttpClient = api.client): HttpBody = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { HttpBody(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
                    continuation.resume(body)
                } catch (error: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun catalog(storefront: String) = "/v1/catalog/${country(storefront)}"

    private fun country(value: String): String {
        val cc = value.lowercase(Locale.ROOT)
        require(cc.length == 2 && cc.all { it in 'a'..'z' }) { "Invalid Apple storefront" }
        return cc
    }

    private fun segment(value: String): String {
        require(value.isNotEmpty() && !value.contains("..") && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.'
        }) { "Invalid Apple resource ID" }
        return value
    }
    private fun entityParams(type: Entity): Map<String, String> = when (type) {
        Entity.Song -> SONG_PARAMS
        Entity.Album -> ALBUM_PARAMS
        Entity.Artist -> ARTIST_PARAMS
        Entity.Playlist -> PLAYLIST_PARAMS
        Entity.MusicVideo -> mapOf("include" to "albums,artists")
        Entity.UploadedVideo -> emptyMap()
        Entity.Station -> mapOf("include" to "events,radio-show")
    }

    companion object {
        private val MOTION_TALL_KEYS = listOf("motionDetailTall", "motionTallVideo3x4")
        private val MOTION_SQUARE_KEYS = listOf(
            "motionDetailSquare", "motionSquareVideo1x1", "motionArtistSquare1x1",
        )
        private val HOST_CONFIG = Regex("""(?m)^\s*window\.FANTASTIC_HOST\s*=\s*(\{.*\});?\s*$""")
        private val ROUTE = Regex("/v1/(?:catalog/[a-z]{2}(?:/[A-Za-z0-9_.,:/-]+)?|editorial/[a-z]{2}/[A-Za-z0-9_.,:/-]+|recommendations/[a-z]{2}|storefronts)")
        private val REQUEST_COUNTRY = Regex("/amp/v1/(?:catalog|editorial|recommendations)/([a-z]{2})(?:/|$)")
        private val RETRY_STATUS = setOf(429, 502, 503, 504)
        private val SEARCH_TYPES = listOf(Entity.Song, Entity.Album, Entity.MusicVideo, Entity.Playlist, Entity.Artist)
        private val SEARCH_PAGE_PARAMS = mapOf(
            "limit" to "21", "platform" to "web", "with" to "serverBubbles", "omit[resource]" to "autos",
            "include[songs]" to "artists", "relate[songs]" to "albums",
        )
        private val PAGE_PARAMS = mapOf("art[url]" to "c,f", "extend" to "editorialArtwork,plainEditorialNotes", "omit[resource:artists]" to "autos")
        private val TRACK_PARAMS = mapOf(
            "include[songs]" to "artists", "include[music-videos]" to "artists",
            "fields[artists]" to "name,artwork,url",
            "fields[songs]" to "name,artistName,curatorName,composerName,artwork,playParams,contentRating,albumName,albumArtistName,discNumber,trackNumber,url,durationInMillis,audioTraits,extendedAssetUrls",
            "fields[albums]" to "name,artwork,playParams,url",
        )
        private val SONG_PARAMS = mapOf(
            "include" to "albums,artists,credits,music-videos", "include[artists]" to "albums",
            "fields[artists]" to "name,url,artwork", "extend" to "extendedAssetUrls",
        )
        private val ALBUM_PARAMS = TRACK_PARAMS + mapOf(
            "fields[albums]" to "name,artistName,artwork,playParams,url,trackCount",
            "platform" to "web", "extend" to "offers,editorialArtwork,editorialVideo,extendedAssetUrls",
            "art[url]" to "f", "include" to "record-labels,artists", "meta[albums:tracks]" to "popularity",
            "include[songs]" to "artists,composers,albums", "include[music-videos]" to "artists",
            "include[playlists]" to "curator", "fields[artists]" to "name,url,artwork",
            "fields[record-labels]" to "name,url", "fields[curators]" to "name",
            "views" to "appears-on,more-by-artist,related-videos,other-versions,you-might-also-like,audio-extras,video-extras",
        )
        private val ARTIST_PARAMS = mapOf(
            "platform" to "web", "art[url]" to "c,f",
            "extend" to "artistBio,isGroup,origin,bornOrFormed,editorialArtwork,editorialVideo,extendedAssetUrls,hero,plainEditorialNotes",
            "include" to "record-labels,artists", "include[songs]" to "artists,albums",
            "extend[playlists]" to "trackCount", "include[music-videos]" to "artists",
            "meta[albums:tracks]" to "popularity", "limit[artists:top-songs]" to "24", "omit[resource]" to "autos",
            "views" to "appears-on-albums,compilation-albums,featured-albums,featured-on-albums,featured-release,full-albums,latest-release,live-albums,more-to-see,playlists,similar-artists,singles,music-videos,top-songs",
        )
        private val PLAYLIST_PARAMS = TRACK_PARAMS + mapOf(
            "platform" to "web", "extend" to "offers,editorialArtwork,editorialVideo,trackCount", "art[url]" to "f",
            "include" to "tracks,curator", "omit[resource]" to "autos", "limit[tracks]" to "300",
            "fields[curators]" to "name,url", "fields[apple-curators]" to "name,url",
            "limit[view.more-by-curator]" to "15", "limit[view.featured-artists]" to "15", "views" to "more-by-curator,featured-artists",
        )
        private val SUGGESTION_PARAMS = mapOf(
            "platform" to "web", "kinds" to "terms,topResults",
            "types" to "activities,artists,albums,editorial-items,music-movies,music-videos,playlists,record-labels,songs,stations,tv-episodes",
            "fields[albums]" to "artistName,artwork,contentRating,name,playParams,url", "fields[artists]" to "url,name,artwork",
            "limit[results:terms]" to "5", "limit[results:topResults]" to "10", "omit[resource]" to "autos",
            "art[url]" to "c,f", "with" to "naturalLanguage",
        )
        private val GROUPING_PARAMS = mapOf(
            "platform" to "web", "omit[resource:artists]" to "autos", "relate[songs]" to "albums",
            "include[albums]" to "artists", "include[songs]" to "artists", "include[music-videos]" to "artists",
            "include[stations]" to "events,radio-show", "extend[station-events]" to "editorialVideo",
            "fields[artists]" to "name,url,artwork,editorialArtwork,genreNames,plainEditorialNotes",
            "fields[albums]" to "artistName,artistUrl,artwork,contentRating,editorialArtwork,plainEditorialNotes,name,playParams,releaseDate,url,trackCount",
            "tabs" to "nonsubscriber", "art[url]" to "c,f", "extend" to "editorialArtwork,artistUrl,plainEditorialNotes",
        )
        private val ROOM_PARAMS = mapOf(
            "platform" to "web", "omit[resource:artists]" to "autos", "relate[songs]" to "albums",
            "include[albums]" to "artists", "include[songs]" to "artists",
            "fields[albums]" to "artistName,artistUrl,artwork,contentRating,editorialArtwork,editorialNotes,name,playParams,releaseDate,url,trackCount",
            "extend[albums]" to "artistUrl", "art[url]" to "c,f", "extend" to "editorialArtwork,plainEditorialNotes",
        )
        private val MULTIROOM_PARAMS = mapOf(
            "platform" to "web", "omit[resource:artists]" to "autos", "include[songs]" to "artists",
            "relate[songs]" to "albums", "art[url]" to "c,f", "extend" to "editorialArtwork,uber,lockupStyle,plainEditorialNotes",
        )
        private val CURATOR_PARAMS = mapOf(
            "platform" to "web", "art[url]" to "c,f", "include" to "grouping,playlists",
            "extend[apple-curators]" to "playlistCount", "extend[curators]" to "playlistCount",
            "extend" to "editorialArtwork,plainEditorialNotes", "omit[resource:artists]" to "autos",
            "include[songs]" to "artists", "relate[songs]" to "albums",
            "fields[albums]" to "artistName,artistUrl,artwork,contentRating,editorialArtwork,name,playParams,releaseDate,url,trackCount",
        )
        private val CHART_PARAMS = mapOf(
            "platform" to "web", "types" to "albums,songs,music-videos,playlists", "with" to "cityCharts,dailyGlobalTopCharts",
            "include" to "tracks", "include[songs]" to "artists", "relate[songs]" to "albums",
            "include[albums]" to "artists", "include[music-videos]" to "artists", "fields[artists]" to "name,url",
            "fields[albums]" to "artistName,artistUrl,artwork,contentRating,editorialArtwork,name,playParams,releaseDate,url,trackCount",
            "fields[playlists]" to "artistName,artistUrl,artwork,contentRating,editorialArtwork,name,playParams,releaseDate,url,curatorName",
            "omit[resource]" to "autos", "art[url]" to "c,f", "extend" to "artistUrl",
        )
    }
}

@Serializable
data class AppleCatalogResource(
    val id: String,
    val type: String,
    val href: String? = null,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val relationships: Map<String, AppleCatalogPage> = emptyMap(),
    val views: Map<String, AppleCatalogPage> = emptyMap(),
    val meta: JsonObject = JsonObject(emptyMap()),
) {
    val name: String get() = attributes.text("name") ?: attributes.text("title") ?: id
    val artist: String get() = attributes.text("artistName") ?: attributes.text("curatorName") ?: ""
    val album: String get() = attributes.text("albumName") ?: ""
    val url: String? get() = attributes.text("url")
    val artwork: JsonObject? get() = attributes["artwork"] as? JsonObject
    val durationMs: Long get() = (attributes["durationInMillis"] as? JsonPrimitive)?.longOrNull ?: 0
    val explicit: Boolean get() = attributes.text("contentRating") == "explicit"

    /** Non-song catalogue entries must remain navigable entities, not fake playable audio tracks. */
    fun toTrack(fallbackCountry: String? = null): Track? = if (type == "songs") Track(
        id, Source.Apple, name, artist, album,
        artwork?.text("url")?.replace("{c}", "bb"), durationMs,
        artistId = relationships["artists"]?.data?.firstOrNull()?.id.orEmpty(),
        albumId = relationships["albums"]?.data?.firstOrNull()?.id.orEmpty(),
        artistArtworkTemplate = relationships["artists"]?.data?.firstOrNull()?.artwork?.text("url")?.replace("{c}", "bb"),
        albumArtist = attributes.text("albumArtistName").orEmpty().ifBlank { relationships["albums"]?.data?.firstOrNull()?.artist.orEmpty() },
        discNumber = attributes.text("discNumber")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        trackNumber = attributes.text("trackNumber")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        country = catalogCountry(fallbackCountry),
    ) else null

    fun artworkUrl(width: Int, height: Int = width, crop: String = "bb", format: String = "jpg"): String? =
        artwork?.text("url")?.expandArtworkUrl(width, height, crop, format)
}

internal fun AppleCatalogResource.catalogCountry(fallbackCountry: String?): String? =
    appleResourceCountry(type, id, href, url, fallbackCountry)

/** Only matching catalogue identity or an official same-resource URL is country evidence. */
internal fun appleResourceCountry(type: String, id: String, href: String?, url: String?, fallbackCountry: String?): String? {
    val resourceUri = try { href?.let(URI::create) } catch (_: IllegalArgumentException) { null }
    if (resourceUri != null && (resourceUri.rawAuthority == null ||
            resourceUri.scheme == "https" && resourceUri.rawAuthority == "api.music.apple.com")) {
        val parts = resourceUri.path.orEmpty().split('/')
        if (parts.size == 6 && parts[1] == "v1" && parts[2] == "catalog" && parts[4] == type && parts[5] == id) {
            parts[3].normalizedAppleCountry()?.let { return it }
        }
    }
    fallbackCountry.normalizedAppleCountry()?.let { return it }
    val official = try { url?.let(URI::create) } catch (_: IllegalArgumentException) { null } ?: return null
    if (official.scheme != "https" || !official.rawAuthority.equals("music.apple.com", ignoreCase = true)) return null
    val parts = official.path.orEmpty().split('/').filter(String::isNotEmpty)
    if (parts.size !in 3..4) return null
    val country = parts[0].normalizedAppleCountry() ?: return null
    val kind = when (type) {
        "songs" -> "song"
        "albums" -> "album"
        "artists" -> "artist"
        "playlists" -> "playlist"
        "music-videos" -> "music-video"
        "stations" -> "station"
        else -> return null
    }
    if (parts[1] == kind && parts.last() == id) return country
    if (type == "songs" && parts[1] == "album" &&
        url?.toHttpUrl()?.queryParameter("i") == id) return country
    return null
}

@Serializable
data class AppleCatalogPage(
    val data: List<AppleCatalogResource> = emptyList(),
    val next: String? = null,
    val href: String? = null,
    val name: String? = null,
    val meta: JsonObject = JsonObject(emptyMap()),
    val attributes: JsonObject = JsonObject(emptyMap()),
) {
    fun tracks(fallbackCountry: String? = null): List<Track> = data.mapNotNull { it.toTrack(fallbackCountry) }
}

@Serializable
data class AppleCatalogResponse(
    val data: List<AppleCatalogResource> = emptyList(),
    val next: String? = null,
    val results: JsonObject = JsonObject(emptyMap()),
    val meta: JsonObject = JsonObject(emptyMap()),
    @kotlinx.serialization.Transient val raw: JsonObject = JsonObject(emptyMap()),
    @kotlinx.serialization.Transient val requestStorefront: String? = null,
) {
    /** Charts use results.<type>[index], unlike search's results.<group>. */
    fun charts(type: String): List<AppleCatalogPage> =
        (results[type] as? JsonArray).orEmpty().map { CHART_JSON.decodeFromJsonElement<AppleCatalogPage>(it) }

    companion object {
        private val CHART_JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class AppleMotionArtwork(val tall: AppleMotionVariant?, val square: AppleMotionVariant?)

@Serializable
data class AppleMotionVariant(val hlsMasterUrl: String, val previewArtworkUri: String?)

@Serializable
private data class CachedMotionArtwork(val key: String, val artwork: AppleMotionArtwork)

private const val MotionArtworkIndexCapacity = 128

private fun motionArtworkKey(baseUrl: String, storefront: String, type: String, id: String): String {
    val input = "$baseUrl\n$storefront\n$type\n$id".toByteArray(Charsets.UTF_8)
    return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(input), Base64.NO_WRAP)
}

private fun LinkedHashMap<String, AppleMotionArtwork>.putRecentMotionArtwork(key: String, artwork: AppleMotionArtwork) {
    remove(key)
    put(key, artwork)
    if (size > MotionArtworkIndexCapacity) remove(keys.first())
}

private fun AppleMotionVariant.validatedCachedVariant(): AppleMotionVariant? {
    if (!hlsMasterUrl.isTrustedMotionHlsUrl()) {
        Log.w("FantasticMotion", "Rejected cached Apple motion video")
        return null
    }
    return if (previewArtworkUri != null && !previewArtworkUri.isTrustedMotionPreviewUrl()) {
        copy(previewArtworkUri = null)
    } else this
}

data class AppleSearchResult(val groups: List<AppleSearchGroup>, val response: AppleCatalogResponse)
data class AppleSearchGroup(val key: String, val page: AppleCatalogPage)

@Serializable
data class AppleSearchSuggestion(
    val kind: String,
    val displayTerm: String? = null,
    val searchTerm: String? = null,
    val content: AppleCatalogResource? = null,
)

data class AppleStorefront(
    val id: String,
    val name: String,
    val supportedLanguageTags: List<String>,
    val defaultLanguageTag: String,
)

data class AppleLyricsOptions(val language: String = "zh-Hans-CN", val script: String = "zh-Latn") {
    companion object {
        val DEFAULT = AppleLyricsOptions()
    }
}

internal fun isValidAppleLyricsTag(tag: String): Boolean = tag.length in 1..35 &&
    tag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

class AppleCatalogHttpException(val statusCode: Int, val serverMessage: String? = null) : IOException(
    if (serverMessage.isNullOrBlank()) "Apple Music HTTP $statusCode" else "Apple Music HTTP $statusCode：$serverMessage",
)

private fun String.expandArtworkUrl(width: Int, height: Int, crop: String, format: String): String =
    replace("{w}", width.toString()).replace("{h}", height.toString())
        .replace("{c}", crop).replace("{f}", format)

private fun String.isMotionCatalogId(): Boolean = isNotEmpty() && this != "0" && all { it in '0'..'9' }

private fun JsonObject.motionVariant(keys: List<String>, previewHeight: Int): AppleMotionVariant? {
    for (key in keys) {
        val entry = this[key] ?: continue
        val candidate = entry as? JsonObject
        val video = candidate?.nonBlankString("video")
        if (video == null || !video.isTrustedMotionHlsUrl()) {
            Log.w("FantasticMotion", "Rejected Apple motion video for $key")
            continue
        }
        val preview = (candidate["previewFrame"] as? JsonObject)?.nonBlankString("url")
            ?.expandArtworkUrl(1080, previewHeight, "bb", "jpg")
            ?.takeIf(String::isTrustedMotionPreviewUrl)
        return AppleMotionVariant(video, preview)
    }
    return null
}

private fun JsonObject.nonBlankString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content

private fun String.isTrustedMotionHlsUrl(): Boolean =
    trustedMotionUri("mvod.itunes.apple.com")?.path?.endsWith(".m3u8") == true

private fun String.isTrustedMotionPreviewUrl(): Boolean =
    trustedMotionUri("mzstatic.com") != null

private fun String.trustedMotionUri(domain: String): URI? {
    val uri = try {
        URI.create(this)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    // A bare authority excludes credentials and even explicitly empty/default ports.
    return uri.takeIf {
        uri.scheme == "https" && uri.rawAuthority.equals(host, ignoreCase = true) &&
            (host == domain || host.endsWith(".$domain"))
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
