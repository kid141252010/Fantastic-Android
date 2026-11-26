package io.github.kid141252010.fantastic.data

import android.util.Log
import io.github.kid141252010.fantastic.FantasticApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

data class LibraryAlbumMetadata(
    val source: Source,
    val albumId: String,
    val albumArtist: String,
    val tracks: List<Track>,
    val trackCount: Int?,
    val country: String? = null,
    val artists: List<CatalogHit>? = null,
)

private val libraryAlbumJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

internal fun LibraryAlbumMetadata.matchesAlbum(source: Source, albumId: String, country: String?): Boolean =
    this.source == source && this.albumId == albumId && (source != Source.Apple || country != null && this.country == country)

private fun FantasticApp.albumRequestCountry(source: Source, country: String?): String? = if (source == Source.Apple) {
    country.normalizedAppleCountry() ?: prefs.storefront.normalizedAppleCountry()
        ?: throw IllegalArgumentException("缺少有效 Apple Music 国家")
} else null

private fun validAlbumArtistId(source: Source, id: String): Boolean =
    id.isNotBlank() && id != "0" && validCatalogId(source, "artist", id)

internal fun AppleCatalogResource.albumArtistHits(fallbackCountry: String?): List<CatalogHit> {
    val artists = relationships["artists"]?.data.orEmpty()
    fun valid(artist: AppleCatalogResource): Boolean = artist.type == "artists" && validAlbumArtistId(Source.Apple, artist.id)
    if (artists.firstOrNull()?.let(::valid) != true) return emptyList()
    val albumCountry = catalogCountry(fallbackCountry)
    return artists.asSequence().filter(::valid).distinctBy { it.id }.map { artist ->
        CatalogHit(Source.Apple, "artist", artist.id, artist.name.ifBlank { artist.id }, artwork = artist.artworkUrl(160),
            country = artist.catalogCountry(null) ?: albumCountry)
    }.toList()
}

internal fun albumArtistHits(source: Source, artists: List<NeteaseArtistCredit>): List<CatalogHit> {
    if (artists.firstOrNull()?.let { validAlbumArtistId(source, it.mid) } != true) return emptyList()
    return artists.asSequence().filter { validAlbumArtistId(source, it.mid) }.distinctBy { it.mid }.map { artist ->
        CatalogHit(source, "artist", artist.mid, artist.name.ifBlank { artist.mid }, artwork = artist.avatar.takeIf { it.isNotBlank() }
            ?.replace("{w}", "500")?.replace("{h}", "500")?.replace("{c}", "bb")?.replace("{f}", "jpg"))
    }.toList()
}

/** A complete provider response is reference metadata, never a request to add its members. */
internal suspend fun FantasticApp.libraryAlbumMetadata(source: Source, albumId: String, country: String?): LibraryAlbumMetadata = withContext(Dispatchers.IO) {
    require(albumId.isNotBlank() && albumId != "0") { "缺少有效专辑 ID" }
    val requestCountry = albumRequestCountry(source, country)
    suspend fun load(): LibraryAlbumMetadata = when (source) {
        Source.Apple -> {
            val album = apple.entity(AppleCatalogApi.Entity.Album, albumId, storefront = requestCountry!!,
                params = mapOf("include" to "tracks,artists,record-labels"))
            require(album.id == albumId && album.type == "albums") { "服务器返回了其他专辑" }
            val albumCountry = album.catalogCountry(requestCountry)
            require(albumCountry == requestCountry) { "服务器返回了其他国家的专辑" }
            val resources = album.relationships["tracks"]?.data.orEmpty().toMutableList()
            var next = album.relationships["tracks"]?.next?.takeIf { it.isNotBlank() }
            val visited = mutableSetOf<String>()
            while (next != null) {
                check(visited.add(next)) { "服务器重复返回同一分页链接，无法继续加载。" }
                val page = apple.trackPage(next)
                val following = page.next?.takeIf { it.isNotBlank() }
                check(following !in visited) { "服务器分页没有前进，请稍后重试。" }
                resources += page.data
                next = following
            }
            val tracks = resources.mapNotNull { it.toTrack(albumCountry) }.filter { it.albumId.isBlank() || it.albumId == albumId }.map { song ->
                song.copy(albumId = albumId, album = song.album.ifBlank { album.name },
                    albumCountry = if (song.albumId == albumId && song.albumId != "0") song.albumCountry ?: album.catalogCountry(null)
                        else album.catalogCountry(null),
                    artist = song.artist.ifBlank { album.artist }, albumArtist = song.albumArtist.ifBlank { album.artist },
                    artworkTemplate = song.artworkTemplate ?: (album.artwork?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("{c}", "bb"))
            }
            LibraryAlbumMetadata(source, albumId, album.artist, tracks,
                (album.attributes["trackCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..100_000 }, albumCountry,
                album.albumArtistHits(requestCountry))
        }
        Source.QQ -> {
            val response = qq.album(albumId)
            val album = libraryAlbumJson.decodeFromJsonElement<NeteaseAlbum>(response.getValue("album"))
            require(album.mid == albumId) { "服务器返回了其他专辑" }
            val artist = album.artists.joinToString(" / ") { it.name }
            val songs = (response["songs"] as? JsonArray).orEmpty().map { libraryAlbumJson.decodeFromJsonElement<QqSong>(it).toTrack() }
            val tracks = songs.filter { it.albumId.isBlank() || it.albumId == albumId }.map { song ->
                song.copy(albumId = albumId, album = song.album.ifBlank { album.name },
                    artist = song.artist.ifBlank { artist }, albumArtist = song.albumArtist.ifBlank { artist },
                    artworkTemplate = song.artworkTemplate?.takeIf { it.isNotBlank() } ?: album.cover.ifBlank { null })
            }
            LibraryAlbumMetadata(source, albumId, artist, tracks, album.size.libraryTrackCount(songs.size),
                artists = albumArtistHits(source, album.artists))
        }
        Source.Netease -> {
            val response = netease.album(albumId)
            require(response.album.mid == albumId) { "服务器返回了其他专辑" }
            val tracks = response.toLibraryTracks(albumId).filter { it.albumId == albumId }
            LibraryAlbumMetadata(source, albumId, response.album.artists.joinToString(" / ") { it.name }, tracks,
                response.album.size.libraryTrackCount(response.songs.size), artists = albumArtistHits(source, response.album.artists))
        }
    }
    library.albumMetadataMutex.withLock {
        var expectedRevision: Long
        var loaded: LibraryAlbumMetadata
        do {
            val state = library.snapshot.first { !it.loading }
            state.albumMetadata.firstOrNull { it.matchesAlbum(source, albumId, requestCountry) }?.let { return@withLock it }
            expectedRevision = state.albumMetadataRevision(source, albumId)
            loaded = load()
            if (library.recordAlbumMetadata(loaded, expectedRevision)) {
                library.snapshot.first { current ->
                    current.albumMetadataRevision(source, albumId) != expectedRevision || current.albumMetadata.any {
                        it.matchesAlbum(source, albumId, requestCountry) && it.trackCount == loaded.trackCount
                    }
                }
            }
        } while (library.snapshot.value.albumMetadataRevision(source, albumId) != expectedRevision)
        loaded
    }
}

/** Older reference records acquire identities only when the album artist is requested. */
internal suspend fun FantasticApp.libraryAlbumArtists(source: Source, albumId: String, country: String?): List<CatalogHit> = withContext(Dispatchers.IO) {
    require(albumId.isNotBlank() && albumId != "0" && validCatalogId(source, "album", albumId)) { "缺少有效专辑 ID" }
    val requestCountry = albumRequestCountry(source, country)
    library.albumMetadataMutex.withLock {
        val state = library.snapshot.first { !it.loading }
        state.albumMetadata.firstOrNull { it.matchesAlbum(source, albumId, requestCountry) }?.artists?.let { return@withLock it }
        val expectedRevision = state.albumMetadataRevision(source, albumId)
        val artists = when (source) {
            Source.Apple -> {
                val album = apple.entity(AppleCatalogApi.Entity.Album, albumId, storefront = requestCountry!!,
                    params = mapOf("include" to "artists"))
                require(album.id == albumId && album.type == "albums") { "服务器返回了其他专辑" }
                require(album.catalogCountry(requestCountry) == requestCountry) { "服务器返回了其他国家的专辑" }
                album.albumArtistHits(requestCountry)
            }
            Source.QQ -> {
                val response = qq.album(albumId)
                val album = libraryAlbumJson.decodeFromJsonElement<NeteaseAlbum>(response.getValue("album"))
                require(album.mid == albumId) { "服务器返回了其他专辑" }
                albumArtistHits(source, album.artists)
            }
            Source.Netease -> {
                val album = netease.album(albumId).album
                require(album.mid == albumId) { "服务器返回了其他专辑" }
                albumArtistHits(source, album.artists)
            }
        }
        if (library.snapshot.value.albumMetadataRevision(source, albumId) != expectedRevision) throw CancellationException("专辑资料库版本已变化")
        if (library.recordAlbumArtists(source, albumId, requestCountry, artists, expectedRevision)) {
            library.snapshot.first { current ->
                current.albumMetadataRevision(source, albumId) != expectedRevision || current.albumMetadata.any {
                    it.matchesAlbum(source, albumId, requestCountry) && it.artists != null
                }
            }
        }
        if (library.snapshot.value.albumMetadataRevision(source, albumId) != expectedRevision) throw CancellationException("专辑资料库版本已变化")
        artists
    }
}

/** The server preserves whole-album array order; it does not expose Netease disc numbers. */
internal fun NeteaseAlbumPage.toLibraryTracks(expectedAlbumId: String): List<Track> {
    require(expectedAlbumId.isNotBlank() && expectedAlbumId != "0" && album.mid == expectedAlbumId) { "服务器返回了其他专辑" }
    val complete = album.size == songs.size.toLong()
    val occurrences = songs.groupingBy { it.id }.eachCount()
    val artist = album.artists.joinToString(" / ") { it.name }
    return songs.mapIndexed { index, song ->
        val track = song.toTrack()
        if (track.albumId.isNotBlank() && track.albumId != expectedAlbumId) track else track.copy(
            albumId = expectedAlbumId,
            album = track.album.ifBlank { album.name },
            artist = track.artist.ifBlank { artist },
            albumArtist = track.albumArtist.ifBlank { artist },
            artworkTemplate = track.artworkTemplate ?: album.cover.ifBlank { null },
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: if (complete && track.id.isNotBlank() && occurrences[track.id] == 1) index + 1 else 0,
        )
    }
}

internal fun Long?.libraryTrackCount(returned: Int): Int? =
    this?.takeIf { it in 0..100_000L && (it != 0L || returned == 0) }?.toInt()

/** Preserve input positives and accept order only from the same source, song and album. */
internal suspend fun FantasticApp.librarySongHit(hit: CatalogHit, albumMetadata: LibraryAlbumMetadata? = null): CatalogHit {
    var track = hit.track ?: return hit
    if (hit.kind != "song" || hit.source != track.source || hit.id != track.id) return hit
    track = track.copy(country = if (track.source == Source.Apple) track.country.normalizedAppleCountry() ?: hit.country.normalizedAppleCountry() else null)

    fun applyAlbum(metadata: LibraryAlbumMetadata?) {
        if (metadata == null || metadata.source != track.source || metadata.albumId != track.albumId || track.albumId.isBlank() || track.albumId == "0" || !compatibleLibraryCountries(track.country, metadata.country)) return
        val reference = metadata.tracks.firstOrNull { it.source == track.source && it.albumId == track.albumId && it.id == track.id && compatibleLibraryCountries(track.country, it.country) } ?: return
        track = track.copy(
            album = track.album.ifBlank { reference.album },
            albumArtist = track.albumArtist.ifBlank { reference.albumArtist.ifBlank { metadata.albumArtist } },
            discNumber = track.discNumber.takeIf { it > 0 } ?: reference.discNumber,
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: reference.trackNumber,
            country = track.country ?: reference.country,
        ).fillRelationCountriesFrom(reference)
    }

    applyAlbum(albumMetadata)
    val knownAlbum = track.albumId.isNotBlank() && track.albumId != "0"
    val knownOrder = track.trackNumber > 0 && (track.source == Source.Netease || track.discNumber > 0)
    if (track.albumArtist.isNotBlank() && knownAlbum && knownOrder && (track.source != Source.Apple || track.country != null)) return hit.copy(track = track, country = track.country)

    val details = if (track.source != Source.Netease || !knownAlbum) lookupLibraryMetadata(track) {
        when (track.source) {
            Source.Apple -> {
                val storefront = track.country.normalizedAppleCountry() ?: prefs.storefront
                apple.entity(AppleCatalogApi.Entity.Song, track.id, storefront = storefront).toTrack(storefront)
            }
            Source.QQ -> qq.songTrack(track.id)
            Source.Netease -> netease.song(track.id).song.toTrack()
        }
    }?.takeIf { it.id == track.id && it.source == track.source && compatibleLibraryCountries(track.country, it.country) } else null
    val albumId = track.albumId.takeUnless { it.isBlank() || it == "0" }
        ?: details?.albumId?.takeUnless { it.isBlank() || it == "0" }.orEmpty()
    val sameAlbum = details?.takeIf { albumId.isNotBlank() && albumId != "0" && it.albumId == albumId }
    track = track.copy(
        album = track.album.ifBlank { sameAlbum?.album.orEmpty() },
        albumId = albumId,
        albumCountry = if (track.albumId == albumId) track.albumCountry else details?.albumCountry,
        albumArtist = track.albumArtist.ifBlank { sameAlbum?.albumArtist.orEmpty() },
        discNumber = track.discNumber.takeIf { it > 0 } ?: sameAlbum?.discNumber ?: 0,
        trackNumber = track.trackNumber.takeIf { it > 0 } ?: sameAlbum?.trackNumber ?: 0,
        country = details?.country ?: track.country,
    ).let { merged -> details?.let(merged::fillRelationCountriesFrom) ?: merged }
    val metadata = albumMetadata?.takeIf { it.source == track.source && it.albumId == albumId }
        ?: if (albumId.isNotBlank() && (track.albumArtist.isBlank() || (track.source == Source.Netease && track.trackNumber <= 0))) {
            lookupLibraryMetadata(track) { libraryAlbumMetadata(track.source, albumId, track.country) }
        } else null
    applyAlbum(metadata)
    return hit.copy(track = track, country = track.country)
}

private suspend fun <T> lookupLibraryMetadata(track: Track, lookup: suspend () -> T): T? = try {
    lookup()
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    Log.w("FantasticCatalog", "${track.source} library metadata lookup failed for ${track.id}", error)
    null
}

internal fun compatibleLibraryCountries(first: String?, second: String?): Boolean =
    first.normalizedAppleCountry() == null || second.normalizedAppleCountry() == null || first.normalizedAppleCountry() == second.normalizedAppleCountry()
