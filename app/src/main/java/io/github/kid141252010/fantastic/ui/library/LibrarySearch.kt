package io.github.kid141252010.fantastic.ui.library

import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.MusicLibrarySnapshot
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.validCatalogId
import java.text.Collator

internal data class LibrarySearchGroup(val kind: String, val title: String, val items: List<CatalogHit>)

/** Builds source-scoped local results without reading playlists, downloads, or reference-only tracks. */
internal fun searchLibrary(snapshot: MusicLibrarySnapshot, query: String): List<LibrarySearchGroup> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()

    val songs = snapshot.items.filter { entry -> entry.hit.kind == "song" }
    val artists = LinkedHashMap<Pair<Source, String>, CatalogHit>()
    snapshot.items.asSequence().filter { it.hit.kind == "artist" }.forEach { entry ->
        val hit = entry.hit
        artists.putIfAbsent(hit.source to hit.id, hit)
    }
    songs.forEach { entry ->
        val track = entry.hit.track ?: return@forEach
        if (track.artistId.isNotBlank() && track.artistId != "0" && validCatalogId(track.source, "artist", track.artistId)) {
            val key = track.source to track.artistId
            artists.putIfAbsent(key, CatalogHit(track.source, "artist", track.artistId,
                track.artist.ifBlank { entry.hit.subtitle.ifBlank { track.artistId } }, artwork = track.artistArtwork(240)))
        }
    }

    val albums = libraryAlbums(snapshot.items)
    val collator = Collator.getInstance()
    fun sorted(hits: List<CatalogHit>): List<CatalogHit> = hits.sortedWith { left, right ->
        val titleOrder = collator.compare(left.title, right.title)
        if (titleOrder != 0) titleOrder else compareValuesBy(left, right, { it.source.name }, { it.id })
    }
    fun CatalogHit.matches(vararg fields: String?): Boolean = fields.any { it?.contains(term, ignoreCase = true) == true }

    return listOf(
        LibrarySearchGroup("artist", "艺人", sorted(artists.values.filter { it.matches(it.title, it.subtitle) })),
        LibrarySearchGroup("album", "专辑", sorted(albums.asSequence().map { it.hit }
            .filter { it.matches(it.title, it.subtitle) }.toList())),
        LibrarySearchGroup("song", "歌曲", sorted(songs.asSequence().map(::librarySearchSongHit).filter { hit ->
            val track: Track? = hit.track
            hit.matches(hit.title, track?.artist, hit.subtitle, track?.album)
        }.toList())),
    ).filter { it.items.isNotEmpty() }
}

internal fun librarySearchSongHit(entry: LibraryEntry): CatalogHit {
    val hit = entry.hit
    val track = hit.track
    return if (track != null) hit else hit.copy(track = Track(
        id = hit.id,
        source = hit.source,
        title = hit.title,
        artist = hit.subtitle,
        album = "",
        artworkTemplate = hit.artwork,
        durationMs = 0,
        country = hit.country,
    ))
}
