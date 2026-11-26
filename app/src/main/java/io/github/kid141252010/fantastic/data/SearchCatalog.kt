package io.github.kid141252010.fantastic.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

/** A search cursor belongs to one source, kind, query and storefront; it never stores a CDN play URL. */
class SearchCatalog(private val apple: AppleCatalogApi, private val netease: NeteaseApi, private val qq: QqApi) {
    enum class Kind(val id: String, val title: String, val appleType: AppleCatalogApi.Entity) {
        Song("song", "歌曲", AppleCatalogApi.Entity.Song),
        Artist("artist", "艺人", AppleCatalogApi.Entity.Artist),
        Album("album", "专辑", AppleCatalogApi.Entity.Album),
        Playlist("playlist", "歌单", AppleCatalogApi.Entity.Playlist),
        Video("music-video", "音乐视频", AppleCatalogApi.Entity.MusicVideo),
    }
    data class Cursor(val source: Source, val kind: Kind, val query: String, val offset: Int, val next: String? = null, val storefront: String)
    data class Page(val items: List<CatalogHit>, val next: Cursor? = null)
    data class Suggestion(val title: String, val query: String? = null, val hit: CatalogHit? = null)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun search(source: Source, kind: Kind, query: String, storefront: String): Page = load(Cursor(source, kind, query.trim(), 0, storefront = storefront))

    data class Group(val key: String, val title: String, val kind: Kind?, val items: List<CatalogHit>, val next: Cursor? = null)
    data class GroupFailure(val kind: Kind, val message: String)
    data class GroupedPage(val groups: List<Group>, val failures: List<GroupFailure>)
    private data class GroupOutcome(val kind: Kind, val page: Page? = null, val error: Exception? = null)

    suspend fun searchAll(source: Source, query: String, storefront: String): GroupedPage {
        val term = query.trim()
        require(term.isNotEmpty())
        if (source == Source.Apple) {
            val response = apple.search(term, listOf(
                AppleCatalogApi.Entity.Song, AppleCatalogApi.Entity.Album, AppleCatalogApi.Entity.MusicVideo,
                AppleCatalogApi.Entity.Playlist, AppleCatalogApi.Entity.Artist,
            ), limit = 21, storefront = storefront)
            val country = response.response.requestStorefront ?: storefront
            val groups = response.groups.mapNotNull { group ->
                if (group.key == "top") {
                    val hits = group.page.data.map { appleHit(it, country) }.distinctBy { "${it.kind}:${it.id}" }.take(6)
                    return@mapNotNull hits.takeIf { it.isNotEmpty() }?.let { Group("top", "最佳结果", null, it) }
                }
                val kind = appleKind(group.key) ?: return@mapNotNull null
                val hits = group.page.data.filter { appleKind(it.type) == kind }.map { appleHit(it, country) }
                    .distinctBy { "${it.kind}:${it.id}" }
                if (hits.isEmpty()) return@mapNotNull null
                val next = group.page.next?.takeIf(String::isNotBlank)?.let {
                    Cursor(source, kind, term, hits.size, next = it, storefront = country)
                }
                Group(kind.id, kind.title, kind, hits, next)
            }
            return GroupedPage(groups, emptyList())
        }

        val kinds = Kind.entries.filter { source != Source.Netease || it != Kind.Video }
        val outcomes = supervisorScope {
            kinds.map { kind -> async {
                try {
                    GroupOutcome(kind, page = search(source, kind, term, storefront))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GroupOutcome(kind, error = e)
                }
            } }.awaitAll()
        }
        val groups = mutableListOf<Group>()
        val failures = mutableListOf<GroupFailure>()
        for (outcome in outcomes) {
            val page = outcome.page
            if (outcome.error != null) {
                failures += GroupFailure(outcome.kind, outcome.error.message ?: "请检查 Fantastic 服务器连接")
            } else if (page != null && page.items.isNotEmpty()) {
                val items = page.items.distinctBy { "${it.kind}:${it.id}" }
                groups += Group(outcome.kind.id, outcome.kind.title, outcome.kind, items, page.next)
            }
        }
        return GroupedPage(groups, failures)
    }

    suspend fun more(cursor: Cursor): Page = load(cursor)

    private suspend fun load(cursor: Cursor): Page {
        require(cursor.query.isNotEmpty() && cursor.offset in 0..10_000)
        val source = cursor.source
        val kind = cursor.kind
        val limit = if (source == Source.QQ && kind != Kind.Song) 20 else 30
        if (source == Source.Apple) {
            val response = if (cursor.next == null) apple.search(cursor.query, listOf(kind.appleType), limit, storefront = cursor.storefront)
            else apple.searchPage(cursor.next)
            val page = response.groups.firstOrNull { appleKind(it.key) == kind }?.page
                ?: response.groups.firstOrNull { group -> group.page.data.any { appleKind(it.type) == kind } }?.page
            val country = response.response.requestStorefront ?: cursor.storefront
            val items = page?.data.orEmpty().filter { appleKind(it.type) == kind }.map { appleHit(it, country) }
            val next = page?.next?.takeIf { it.isNotBlank() }
            if (next != null && (next == cursor.next || items.isEmpty())) throw IOException("搜索分页没有前进")
            return Page(items, next?.let { cursor.copy(offset = cursor.offset + items.size, next = it, storefront = country) })
        }
        val items: List<CatalogHit>
        val rawCount: Int
        val hasMore: Boolean
        if (source == Source.Netease) {
            require(kind != Kind.Video) { "网易云不提供音乐视频接口" }
            val page = netease.search(cursor.query, NeteaseSearchKind.entries.first { it.wireValue == kind.id }, limit, cursor.offset)
            items = when (kind) {
                Kind.Song -> page.songs.map { trackHit(it.toTrack()) }
                Kind.Artist -> page.artists.map { CatalogHit(source, kind.id, it.mid, it.name, artwork = it.avatar) }
                Kind.Album -> page.albums.map { CatalogHit(source, kind.id, it.mid, it.name, it.artists.joinToString(" / ") { a -> a.name }, it.cover) }
                Kind.Playlist -> page.playlists.map { CatalogHit(source, kind.id, it.id, it.name, it.creator, it.cover) }
                Kind.Video -> emptyList()
            }
            rawCount = when (kind) {
                Kind.Song -> page.songs.size
                Kind.Artist -> page.artists.size
                Kind.Album -> page.albums.size
                Kind.Playlist -> page.playlists.size
                Kind.Video -> 0
            }
            hasMore = cursor.offset + rawCount < page.total
        } else {
            val page = qq.search(cursor.query, if (kind == Kind.Video) "mv" else kind.id, limit, cursor.offset)
            items = page.items.mapNotNull { qqHit(kind, it) }
            rawCount = page.items.size
            hasMore = page.more || cursor.offset + rawCount < page.total
        }
        val offset = cursor.offset + rawCount
        return Page(items, if (rawCount > 0 && hasMore && offset <= 10_000) cursor.copy(offset = offset) else null)
    }

    suspend fun suggestions(source: Source, query: String, storefront: String): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val values = when (source) {
            Source.Apple -> apple.suggestions(query, storefront = storefront).mapNotNull { item ->
                item.content?.let { resource -> Suggestion(resource.name, hit = appleHit(resource, storefront)) }
                    ?: item.searchTerm?.let { Suggestion(item.displayTerm ?: it, query = it) }
            }
            Source.Netease -> netease.suggest(query).let { result ->
                result.songs.map { song ->
                    val track = Track(song.mid, source, song.name, song.artist, "", null, 0L)
                    Suggestion(song.name, hit = trackHit(track))
                } +
                    result.artists.map { Suggestion(it.name, hit = CatalogHit(source, "artist", it.mid, it.name, artwork = it.avatar)) } +
                    result.albums.map { Suggestion(it.name, hit = CatalogHit(source, "album", it.mid, it.name, it.artist, it.cover)) }
            }
            Source.QQ -> qq.suggest(query).let { response ->
                listOf(Kind.Song to "songs", Kind.Artist to "artists", Kind.Album to "albums").flatMap { (kind, key) ->
                    (response[key] as? JsonArray).orEmpty().mapNotNull { raw ->
                        (raw as? JsonObject)?.let { qqHit(kind, it, suggestion = true) }?.let { Suggestion(it.title, hit = it) }
                    }
                }
            }
        }
        return values.distinctBy { it.hit?.let { hit -> "${hit.kind}:${hit.id}" } ?: "query:${it.query}" }.take(8)
    }

    private fun qqHit(kind: Kind, raw: JsonObject, suggestion: Boolean = false): CatalogHit? {
        val id = raw.text(if (kind == Kind.Video) "vid" else if (kind == Kind.Song && !suggestion || kind == Kind.Playlist) "id" else "mid")
            .ifBlank { raw.text("id").ifBlank { raw.text("mid") } }
        if (id.isEmpty()) return null
        val artists = (raw["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }.joinToString(" / ")
        val title = raw.text("name").ifBlank { id }
        if (kind == Kind.Song) {
            if (!suggestion) return trackHit(json.decodeFromJsonElement<QqSong>(raw).toTrack())
            val artist = raw.text("artist").ifBlank { artists }
            val album = raw.text("album")
            val artwork = raw.text("cover").replace("{w}", "500").replace("{h}", "500").takeIf { it.isNotBlank() }
            return trackHit(Track(id, Source.QQ, title, artist, album, artwork, raw.text("durationMs").toLongOrNull() ?: 0L))
        }
        val art = raw.text(if (kind == Kind.Artist) "avatar" else "cover").replace("{w}", "500").replace("{h}", "500")
        return CatalogHit(Source.QQ, kind.id, id, title,
            raw.text("artist").ifBlank { raw.text("creator").ifBlank { artists } }, art.takeIf { it.isNotBlank() }, attributes = raw)
    }

    private fun appleKind(key: String): Kind? = when (key) {
        "song", "songs" -> Kind.Song
        "artist", "artists" -> Kind.Artist
        "album", "albums" -> Kind.Album
        "playlist", "playlists" -> Kind.Playlist
        "music_video", "music-videos", "mv", "mvs" -> Kind.Video
        else -> null
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        fun trackHit(track: Track) = CatalogHit(track.source, "song", track.id, track.title, track.artist, track.artwork(500), track,
            detailOnly = false, country = if (track.source == Source.Apple) track.country.normalizedAppleCountry() else null)
        fun appleHit(resource: AppleCatalogResource, fallbackCountry: String?): CatalogHit {
            val country = resource.catalogCountry(fallbackCountry)
            return CatalogHit(Source.Apple,
                when (resource.type) { "songs" -> "song"; "artists" -> "artist"; "albums" -> "album"; "playlists" -> "playlist";
                    "music-videos" -> "music-video"; "stations" -> "station"; "uploaded-videos" -> "post"; else -> resource.type },
                resource.id, resource.name, resource.artist, resource.artworkUrl(500),
                resource.toTrack(country).takeIf { resource.type == "songs" }, resource.explicit,
                resource.type != "songs", resource.attributes, country = country)
        }
    }
}
