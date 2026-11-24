package io.github.kid141252010.fantastic.data

import android.util.Log
import io.github.kid141252010.fantastic.FantasticApp
import kotlinx.coroutines.CancellationException

/** Catalog song results may not include their album's artist or the source track numbers. */
internal suspend fun FantasticApp.librarySongHit(hit: CatalogHit): CatalogHit {
    val track = hit.track ?: return hit
    if (hit.kind != "song" || (track.albumArtist.isNotBlank() && track.albumId.isNotBlank() &&
            (track.trackNumber > 0 || track.source == Source.Netease))) return hit

    val details = lookupLibraryMetadata(track) {
        when (track.source) {
            Source.Apple -> apple.entity(AppleCatalogApi.Entity.Song, track.id).toTrack()
            Source.QQ -> qq.songTrack(track.id)
            Source.Netease -> netease.song(track.id).song.toTrack()
        }
    }?.takeIf { it.id == track.id && it.source == track.source }
    val albumId = track.albumId.takeUnless { it.isBlank() || it == "0" } ?: details?.albumId.orEmpty()
    val sameAlbum = details?.takeIf { it.albumId.isBlank() || it.albumId == albumId }
    val albumArtist = track.albumArtist.ifBlank { sameAlbum?.albumArtist.orEmpty() }.ifBlank {
        if (albumId.isBlank() || albumId == "0") "" else lookupLibraryMetadata(track) {
            when (track.source) {
                Source.Apple -> apple.entity(AppleCatalogApi.Entity.Album, albumId).artist
                Source.QQ -> qq.albumArtists(albumId)
                Source.Netease -> netease.album(albumId).album.artists.joinToString(" / ") { it.name }
            }
        }.orEmpty()
    }
    return hit.copy(track = track.copy(
        album = track.album.ifBlank { sameAlbum?.album.orEmpty() },
        albumId = albumId,
        albumArtist = albumArtist,
        discNumber = track.discNumber.takeIf { it > 0 } ?: sameAlbum?.discNumber ?: 0,
        trackNumber = track.trackNumber.takeIf { it > 0 } ?: sameAlbum?.trackNumber ?: 0,
    ))
}

private suspend fun <T> lookupLibraryMetadata(track: Track, lookup: suspend () -> T): T? = try {
    lookup()
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    Log.w("FantasticCatalog", "${track.source} library metadata lookup failed for ${track.id}", error)
    null
}
