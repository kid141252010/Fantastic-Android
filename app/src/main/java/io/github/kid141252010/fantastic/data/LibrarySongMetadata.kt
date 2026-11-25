package io.github.kid141252010.fantastic.data

import android.util.Log
import io.github.kid141252010.fantastic.FantasticApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

internal data class LibraryAlbumMetadata(
    val source: Source,
    val albumId: String,
    val albumArtist: String,
    val tracks: List<Track>,
    val trackCount: Int?,
)

private val libraryAlbumJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** A complete provider response is reference metadata, never a request to add its members. */
internal suspend fun FantasticApp.libraryAlbumMetadata(source: Source, albumId: String): LibraryAlbumMetadata = withContext(Dispatchers.IO) {
    require(albumId.isNotBlank() && albumId != "0") { "缺少有效专辑 ID" }
    when (source) {
        Source.Apple -> {
            val storefront = prefs.storefront
            val album = apple.entity(AppleCatalogApi.Entity.Album, albumId, storefront = storefront,
                params = mapOf("include" to "tracks,artists,record-labels"))
            require(album.id == albumId && album.type == "albums") { "服务器返回了其他专辑" }
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
            val tracks = resources.mapNotNull { it.toTrack() }.filter { it.albumId.isBlank() || it.albumId == albumId }.map { song ->
                song.copy(albumId = albumId, album = song.album.ifBlank { album.name },
                    artist = song.artist.ifBlank { album.artist }, albumArtist = song.albumArtist.ifBlank { album.artist },
                    artworkTemplate = song.artworkTemplate ?: (album.artwork?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("{c}", "bb"))
            }
            LibraryAlbumMetadata(source, albumId, album.artist, tracks,
                (album.attributes["trackCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 })
        }
        Source.QQ -> {
            val response = qq.album(albumId)
            val album = libraryAlbumJson.decodeFromJsonElement<NeteaseAlbum>(response.getValue("album"))
            require(album.mid.isBlank() || album.mid == albumId) { "服务器返回了其他专辑" }
            val artist = album.artists.joinToString(" / ") { it.name }
            val songs = (response["songs"] as? JsonArray).orEmpty().map { libraryAlbumJson.decodeFromJsonElement<QqSong>(it).toTrack() }
            val tracks = songs.filter { it.albumId.isBlank() || it.albumId == albumId }.map { song ->
                song.copy(albumId = albumId, album = song.album.ifBlank { album.name },
                    artist = song.artist.ifBlank { artist }, albumArtist = song.albumArtist.ifBlank { artist },
                    artworkTemplate = song.artworkTemplate?.takeIf { it.isNotBlank() } ?: album.cover.ifBlank { null })
            }
            LibraryAlbumMetadata(source, albumId, artist, tracks, album.size.libraryTrackCount(songs.size))
        }
        Source.Netease -> {
            val response = netease.album(albumId)
            val tracks = response.toLibraryTracks(albumId).filter { it.albumId == albumId }
            LibraryAlbumMetadata(source, albumId, response.album.artists.joinToString(" / ") { it.name }, tracks,
                response.album.size.libraryTrackCount(response.songs.size))
        }
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

private fun Long.libraryTrackCount(returned: Int): Int? =
    takeIf { it in 0..Int.MAX_VALUE.toLong() && (it != 0L || returned == 0) }?.toInt()

/** Preserve input positives and accept order only from the same source, song and album. */
internal suspend fun FantasticApp.librarySongHit(hit: CatalogHit, albumMetadata: LibraryAlbumMetadata? = null): CatalogHit {
    var track = hit.track ?: return hit
    if (hit.kind != "song" || hit.source != track.source || hit.id != track.id) return hit

    fun applyAlbum(metadata: LibraryAlbumMetadata?) {
        if (metadata == null || metadata.source != track.source || metadata.albumId != track.albumId || track.albumId.isBlank() || track.albumId == "0") return
        val reference = metadata.tracks.firstOrNull { it.source == track.source && it.albumId == track.albumId && it.id == track.id }
        track = track.copy(
            album = track.album.ifBlank { reference?.album.orEmpty() },
            albumArtist = track.albumArtist.ifBlank { reference?.albumArtist.orEmpty().ifBlank { metadata.albumArtist } },
            discNumber = track.discNumber.takeIf { it > 0 } ?: reference?.discNumber ?: 0,
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: reference?.trackNumber ?: 0,
        )
    }

    applyAlbum(albumMetadata)
    val knownAlbum = track.albumId.isNotBlank() && track.albumId != "0"
    val knownOrder = track.trackNumber > 0 && (track.source == Source.Netease || track.discNumber > 0)
    if (track.albumArtist.isNotBlank() && knownAlbum && knownOrder) return if (track == hit.track) hit else hit.copy(track = track)

    val details = if (track.source != Source.Netease || !knownAlbum) lookupLibraryMetadata(track) {
        when (track.source) {
            Source.Apple -> apple.entity(AppleCatalogApi.Entity.Song, track.id).toTrack()
            Source.QQ -> qq.songTrack(track.id)
            Source.Netease -> netease.song(track.id).song.toTrack()
        }
    }?.takeIf { it.id == track.id && it.source == track.source } else null
    val albumId = track.albumId.takeUnless { it.isBlank() || it == "0" }
        ?: details?.albumId?.takeUnless { it.isBlank() || it == "0" }.orEmpty()
    val sameAlbum = details?.takeIf { it.albumId.isBlank() || it.albumId == "0" || it.albumId == albumId }
    track = track.copy(
        album = track.album.ifBlank { sameAlbum?.album.orEmpty() },
        albumId = albumId,
        albumArtist = track.albumArtist.ifBlank { sameAlbum?.albumArtist.orEmpty() },
        discNumber = track.discNumber.takeIf { it > 0 } ?: sameAlbum?.discNumber ?: 0,
        trackNumber = track.trackNumber.takeIf { it > 0 } ?: sameAlbum?.trackNumber ?: 0,
    )
    val metadata = albumMetadata?.takeIf { it.source == track.source && it.albumId == albumId }
        ?: if (albumId.isNotBlank() && (track.albumArtist.isBlank() || (track.source == Source.Netease && track.trackNumber <= 0))) {
            lookupLibraryMetadata(track) { libraryAlbumMetadata(track.source, albumId) }
        } else null
    applyAlbum(metadata)
    return if (track == hit.track) hit else hit.copy(track = track)
}

private suspend fun <T> lookupLibraryMetadata(track: Track, lookup: suspend () -> T): T? = try {
    lookup()
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    Log.w("FantasticCatalog", "${track.source} library metadata lookup failed for ${track.id}", error)
    null
}
