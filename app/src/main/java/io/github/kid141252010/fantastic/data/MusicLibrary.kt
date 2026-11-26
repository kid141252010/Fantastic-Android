package io.github.kid141252010.fantastic.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

private val APPLE_ID_REGEX = Regex("[0-9]{1,20}")
private val APPLE_PLAYLIST_REGEX = Regex("pl\\.[A-Za-z0-9._-]{1,100}")
private val QQ_ID_REGEX = Regex("[A-Za-z0-9]{1,32}")
private val NCM_ID_REGEX = Regex("[1-9][0-9]{0,18}")

internal fun validCatalogId(source: Source, kind: String, id: String): Boolean = when (source) {
    Source.Apple -> if (kind == "playlist") APPLE_PLAYLIST_REGEX.matches(id) else APPLE_ID_REGEX.matches(id)
    Source.QQ -> QQ_ID_REGEX.matches(id)
    Source.Netease -> NCM_ID_REGEX.matches(id) && id.toLongOrNull() != null
}

/** Source-scoped catalog identity; provider IDs are not globally unique. */
data class LibraryKey(val source: Source, val kind: String, val id: String)

data class LibraryEntry(
    val hit: CatalogHit,
    val addedAt: Long,
    val favoriteAt: Long = 0,
    val folderId: String = "",
    val metadata: JsonObject? = null,
    val albumTracks: List<Track> = emptyList(),
) {
    val key: LibraryKey get() = LibraryKey(hit.source, hit.kind, hit.id)
    val isFavorite: Boolean get() = favoriteAt > 0
}

data class LibraryPlaylistTrack(val uid: String, val addedAt: Long, val hit: CatalogHit, val metadata: JsonObject? = null)
data class LibraryPlaylist(
    val id: String,
    val name: String,
    val description: String,
    val folderId: String,
    val favoriteAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val tracks: List<LibraryPlaylistTrack>,
)
data class LibraryFolder(val id: String, val name: String, val parentId: String, val createdAt: Long, val updatedAt: Long)
data class MusicLibrarySnapshot(
    val items: List<LibraryEntry> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val loading: Boolean = true,
    val persistenceError: Boolean = false,
    val albumMetadata: List<LibraryAlbumMetadata> = emptyList(),
    val albumMetadataRevisions: Map<LibraryKey, Long> = emptyMap(),
)

internal fun MusicLibrarySnapshot.albumMetadataRevision(source: Source, albumId: String): Long =
    albumMetadataRevisions[LibraryKey(source, "album", albumId)] ?: 0L

data class LibraryImportReport(val items: Int, val playlists: Int, val folders: Int, val skipped: Int)

/**
 * Small, offline-first catalog library backed by an atomic private app file. Exports use the
 * Fantastic web client's versioned am-hook-library v1 JSON format, with source-aware extensions.
 * Mutations are serialized off the main thread; [snapshot] publishes immutable snapshots.
 */
class MusicLibrary(context: Context) {
    private val file = File(context.applicationContext.filesDir, "music-library.json")
    private val atomicFile = AtomicFile(file)
    private val commands = Channel<(MusicLibrarySnapshot) -> MusicLibrarySnapshot>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(MusicLibrarySnapshot())
    val snapshot: StateFlow<MusicLibrarySnapshot> = mutableSnapshot.asStateFlow()
    internal val albumMetadataMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            val stored = readStoredSnapshot()
            var current = repairSongOrderFromAlbumTracks(stored)
            if (current !== stored) {
                try {
                    atomicWrite(encodeSnapshot(current))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    current = current.copy(persistenceError = true)
                }
            }
            mutableSnapshot.value = current
            for (command in commands) {
                val next = try {
                    command(current).copy(loading = false, persistenceError = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    current.copy(loading = false, persistenceError = true)
                }
                if (next != current) {
                    current = next
                    mutableSnapshot.value = current
                    try {
                        atomicWrite(encodeSnapshot(current))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        current = current.copy(persistenceError = true)
                        mutableSnapshot.value = current
                    }
                }
            }
        }
    }

    /** Add or refresh a catalog record without changing its favorite state or original add time. */
    fun add(hit: CatalogHit, albumTracks: List<Track> = emptyList()) = save(hit, albumTracks, toggleFavorite = false)

    /** Favorite is an offline library action: favoriting also adds the item to the library. */
    fun toggleFavorite(hit: CatalogHit, albumTracks: List<Track> = emptyList()) = save(hit, albumTracks, toggleFavorite = true)

    private fun save(hit: CatalogHit, albumTracks: List<Track>, toggleFavorite: Boolean) {
        val clean = normalizeHit(hit) ?: return
        commands.trySend { state ->
            val key = LibraryKey(clean.source, clean.kind, clean.id)
            val index = state.items.indexOfFirst { it.key == key }
            val old = state.items.getOrNull(index)
            if (old == null && state.items.size >= MAX_ITEMS) return@trySend state
            val tracks = if (clean.kind == "album" && albumTracks.isNotEmpty()) {
                val incoming = deduplicateTracks(albumTracks.asSequence().filter { it.source == clean.source && it.albumId == clean.id }
                    .take(MAX_ITEMS).mapNotNull { normalizeHit(SearchCatalog.trackHit(it))?.track }.toList())
                mergeAlbumTracks(incoming, old?.albumTracks.orEmpty())
            } else old?.albumTracks.orEmpty()
            val favoriteAt = if (toggleFavorite) {
                if (old?.isFavorite == true) 0 else now()
            } else old?.favoriteAt ?: 0
            var refreshed = mergeSongMetadata(if (hit.track == null && old != null) clean.copy(track = null) else clean, old?.hit)
            if (refreshed.kind == "song") {
                val track = refreshed.track
                val reference = state.items.firstOrNull {
                    it.hit.kind == "album" && it.hit.source == refreshed.source && it.hit.id == track?.albumId
                }?.albumTracks?.firstOrNull { it.id == refreshed.id && it.source == refreshed.source && it.albumId == track?.albumId && compatibleLibraryCountries(refreshed.country, it.country) }
                if (reference != null) refreshed = mergeSongMetadata(refreshed, SearchCatalog.trackHit(reference))
            }
            val entry = LibraryEntry(refreshed, old?.addedAt ?: now(), favoriteAt, old?.folderId.orEmpty(), old?.metadata, tracks)
            val next = state.copy(items = if (index < 0) state.items + entry else state.items.toMutableList().apply { this[index] = entry })
            val updated = if (clean.kind == "album") repairSongOrderFromReferences(next, tracks) else next
            val albumId = appleAlbumId(refreshed)
            val invalidate = albumId != null && when (clean.kind) {
                "song" -> old == null || appleAlbumId(old.hit) != albumId
                "album" -> !toggleFavorite || old == null
                else -> false
            }
            if (albumId != null && invalidate) invalidateAppleAlbumMetadata(updated, setOf(albumId)) else updated
        }
    }

    private fun appleAlbumId(hit: CatalogHit): String? {
        if (hit.source != Source.Apple) return null
        val id = when (hit.kind) { "song" -> hit.track?.albumId; "album" -> hit.id; else -> null } ?: return null
        return id.takeIf { it != "0" && validCatalogId(Source.Apple, "album", it) }
    }

    private fun invalidateAppleAlbumMetadata(state: MusicLibrarySnapshot, albumIds: Set<String>): MusicLibrarySnapshot {
        if (albumIds.isEmpty()) return state
        val revisions = state.albumMetadataRevisions.toMutableMap()
        albumIds.forEach { id ->
            val key = LibraryKey(Source.Apple, "album", id)
            revisions[key] = (revisions[key] ?: 0L) + 1L
        }
        return state.copy(albumMetadata = state.albumMetadata.filterNot { it.source == Source.Apple && it.albumId in albumIds },
            albumMetadataRevisions = revisions)
    }

    /** Incoming values win, except unknown fields never erase an existing song snapshot. */
    private fun mergeSongMetadata(incoming: CatalogHit, existing: CatalogHit?): CatalogHit {
        if (existing == null || incoming.source != existing.source || incoming.kind != existing.kind || incoming.id != existing.id) return incoming
        val country = if (incoming.source == Source.Apple) incoming.country.normalizedAppleCountry() ?: existing.country.normalizedAppleCountry() else null
        val result = incoming.copy(country = country, track = incoming.track?.copy(country = country))
        if (incoming.kind != "song" || !compatibleLibraryCountries(incoming.country, existing.country)) return result
        val old = existing.track ?: return result
        val track = incoming.track ?: return result.copy(track = old.copy(country = country))
        val albumChanged = track.albumId.isNotBlank() && track.albumId != "0" && old.albumId.isNotBlank() && old.albumId != "0" && track.albumId != old.albumId
        val artistId = track.artistId.takeUnless { it.isBlank() || it == "0" } ?: old.artistId
        val albumId = track.albumId.takeUnless { it.isBlank() || it == "0" } ?: old.albumId
        val merged = track.copy(
            title = track.title.ifBlank { old.title },
            artist = track.artist.ifBlank { old.artist },
            album = track.album.ifBlank { if (albumChanged) "" else old.album },
            artworkTemplate = track.artworkTemplate?.takeIf { it.isNotBlank() } ?: old.artworkTemplate.takeUnless { albumChanged },
            durationMs = track.durationMs.takeIf { it > 0 } ?: old.durationMs,
            artistId = artistId,
            albumId = albumId,
            artistCountry = if (artistId == track.artistId) track.artistCountry else old.artistCountry,
            albumCountry = if (albumId == track.albumId) track.albumCountry else old.albumCountry,
            artistArtworkTemplate = track.artistArtworkTemplate?.takeIf { it.isNotBlank() }
                ?: old.artistArtworkTemplate.takeIf { artistId == old.artistId },
            albumArtist = track.albumArtist.ifBlank { if (albumChanged) "" else old.albumArtist },
            discNumber = track.discNumber.takeIf { it > 0 } ?: if (albumChanged) 0 else old.discNumber,
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: if (albumChanged) 0 else old.trackNumber,
            country = country,
        ).fillRelationCountriesFrom(old).fillRelationCountriesFrom(track)
        return result.copy(
            subtitle = incoming.subtitle.ifBlank { existing.subtitle },
            artwork = incoming.artwork ?: existing.artwork.takeUnless { albumChanged },
            track = merged,
        )
    }

    private fun mergeAlbumTracks(incoming: List<Track>, existing: List<Track>): List<Track> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming
        val references = existing.associateBy { Triple(it.source, it.albumId, it.id) }
        return incoming.map { track ->
            val old = references[Triple(track.source, track.albumId, track.id)] ?: return@map track
            if (!compatibleLibraryCountries(track.country, old.country)) return@map track
            track.copy(
                discNumber = track.discNumber.takeIf { it > 0 } ?: old.discNumber,
                trackNumber = track.trackNumber.takeIf { it > 0 } ?: old.trackNumber,
                country = track.country ?: old.country,
            ).fillRelationCountriesFrom(old)
        }
    }

    private fun deduplicateTracks(tracks: List<Track>): List<Track> {
        val first = LinkedHashMap<Triple<Source, String, String>, Track>()
        tracks.forEach { track ->
            val key = Triple(track.source, track.albumId, track.id)
            val old = first[key]
            first[key] = if (old == null) track else old.copy(country = old.country ?: track.country).fillRelationCountriesFrom(track)
        }
        return first.values.toList()
    }

    private fun repairSongOrderFromAlbumTracks(state: MusicLibrarySnapshot): MusicLibrarySnapshot =
        repairSongOrderFromReferences(state, state.items.asSequence().filter { it.hit.kind == "album" }
            .flatMap { entry -> entry.albumTracks.asSequence().filter { it.source == entry.hit.source && it.albumId == entry.hit.id } }.toList() +
            state.albumMetadata.flatMap { it.tracks })

    private fun repairSongOrderFromReferences(state: MusicLibrarySnapshot, tracks: List<Track>): MusicLibrarySnapshot {
        if (tracks.isEmpty()) return state
        val references = tracks.groupBy { Triple(it.source, it.albumId, it.id) }
        var changed = false
        val items = state.items.map { entry ->
            val track = entry.hit.track
            val reference = if (entry.hit.kind == "song" && track != null && track.albumId.isNotBlank() && track.albumId != "0")
                references[Triple(track.source, track.albumId, track.id)]?.firstOrNull { compatibleLibraryCountries(track.country, it.country) } else null
            if (reference == null || !compatibleLibraryCountries(track?.country, reference.country)) entry else {
                val hit = mergeSongMetadata(entry.hit, SearchCatalog.trackHit(reference))
                if (hit == entry.hit) entry else entry.copy(hit = hit).also { changed = true }
            }
        }
        return if (changed) state.copy(items = items) else state
    }

    /** Fill only existing members; queued execution rechecks current identity and album ownership. */
    internal fun updateSongMetadata(hits: List<CatalogHit>) {
        if (hits.isEmpty()) return
        commands.trySend { state ->
            val patches = hits.asSequence().filter { it.kind == "song" }.mapNotNull(::normalizeHit)
                .associateBy { LibraryKey(it.source, it.kind, it.id) }
            var changed = false
            val items = state.items.map { entry ->
                val patch = patches[entry.key]
                val albumId = entry.hit.track?.albumId
                val patchAlbumId = patch?.track?.albumId
                if (patch == null || albumId.isNullOrBlank() || albumId == "0" || albumId != patchAlbumId || !compatibleLibraryCountries(entry.hit.country, patch.country)) entry else {
                    val hit = mergeSongMetadata(entry.hit, patch)
                    if (hit == entry.hit) entry else entry.copy(hit = hit).also { changed = true }
                }
            }
            if (changed) state.copy(items = items) else state
        }
    }

    /** Reference responses only fill country, never replace a previously known storefront. */
    internal fun updateAppleCountry(kind: String, id: String, country: String) {
        val clean = country.normalizedAppleCountry() ?: return
        if (kind !in KINDS || !validCatalogId(Source.Apple, kind, id)) return
        fun patch(hit: CatalogHit): CatalogHit = if (hit.source == Source.Apple && hit.kind == kind && hit.id == id && hit.country == null)
            hit.copy(country = clean, track = hit.track?.copy(country = hit.track.country ?: clean)) else hit
        fun reference(track: Track): Track = if (kind == "song" && track.source == Source.Apple && track.id == id && track.country == null) track.copy(country = clean) else track
        commands.trySend { state -> state.copy(
            items = state.items.map { it.copy(hit = patch(it.hit), albumTracks = it.albumTracks.map(::reference)) },
            playlists = state.playlists.map { list -> list.copy(tracks = list.tracks.map { it.copy(hit = patch(it.hit)) }) },
        ) }
    }

    internal suspend fun recordAlbumMetadata(metadata: LibraryAlbumMetadata, expectedRevision: Long): Boolean {
        val result = CompletableDeferred<Boolean>()
        if (commands.trySend { state ->
            try {
                if (state.albumMetadataRevision(metadata.source, metadata.albumId) != expectedRevision) {
                    result.complete(false)
                    return@trySend state
                }
                val clean = normalizeAlbumMetadata(metadata, state.items)
                if (clean == null) {
                    result.complete(false)
                    return@trySend state
                }
                val key = albumMetadataKey(clean)
                val old = state.albumMetadata.firstOrNull { albumMetadataKey(it) == key }
                val updated = if (clean.artists == null) clean.copy(artists = old?.artists) else clean
                val next = state.copy(albumMetadata = boundedAlbumMetadata(state.albumMetadata.filterNot { albumMetadataKey(it) == key } + updated))
                result.complete(next.albumMetadata.any { albumMetadataKey(it) == key })
                next
            } catch (error: Exception) {
                result.complete(false)
                throw error
            }
        }.isFailure) return false
        return result.await()
    }

    internal suspend fun recordAlbumArtists(source: Source, albumId: String, country: String?, artists: List<CatalogHit>, expectedRevision: Long): Boolean {
        val result = CompletableDeferred<Boolean>()
        if (commands.trySend { state ->
            try {
                val index = state.albumMetadata.indexOfFirst { it.matchesAlbum(source, albumId, country) }
                if (index < 0 || state.albumMetadataRevision(source, albumId) != expectedRevision) {
                    result.complete(false)
                    return@trySend state
                }
                val clean = normalizeAlbumMetadata(state.albumMetadata[index].copy(artists = artists), state.items)
                if (clean == null) {
                    result.complete(false)
                    return@trySend state
                }
                val records = state.albumMetadata.toMutableList().apply { this[index] = clean }
                val next = state.copy(albumMetadata = boundedAlbumMetadata(records))
                result.complete(next.albumMetadata.any { it.matchesAlbum(source, albumId, country) })
                next
            } catch (error: Exception) {
                result.complete(false)
                throw error
            }
        }.isFailure) return false
        return result.await()
    }

    private fun albumMetadataKey(metadata: LibraryAlbumMetadata): Triple<Source, String, String?> =
        Triple(metadata.source, metadata.albumId, if (metadata.source == Source.Apple) metadata.country.normalizedAppleCountry() else null)

    private fun validAlbumArtist(source: Source, hit: CatalogHit): Boolean =
        hit.source == source && hit.kind == "artist" && hit.id.isNotBlank() && hit.id != "0" && validCatalogId(source, "artist", hit.id)

    private fun normalizeAlbumArtists(source: Source, artists: List<CatalogHit>?): List<CatalogHit>? {
        if (artists == null) return null
        if (artists.firstOrNull()?.let { validAlbumArtist(source, it) } != true) return emptyList()
        return artists.asSequence().take(MAX_ITEMS).filter { validAlbumArtist(source, it) }
            .mapNotNull(::normalizeHit).distinctBy { it.id }.toList()
    }

    private fun savedAppleSongs(items: List<LibraryEntry>): Map<Pair<String, String>, String?> = buildMap {
        items.forEach { entry ->
            val track = entry.hit.track
            if (entry.hit.source == Source.Apple && entry.hit.kind == "song" && track != null &&
                track.source == Source.Apple && track.albumId != "0" && validCatalogId(Source.Apple, "album", track.albumId)) {
                put(track.albumId to entry.hit.id, entry.hit.country.normalizedAppleCountry() ?: track.country.normalizedAppleCountry())
            }
        }
    }

    private fun savedAppleTracks(metadata: LibraryAlbumMetadata, members: Map<Pair<String, String>, String?>): List<Track> =
        metadata.tracks.filter { track ->
            val key = track.albumId to track.id
            track.source == Source.Apple && track.albumId == metadata.albumId && members.containsKey(key) &&
                compatibleLibraryCountries(metadata.country, members[key]) && compatibleLibraryCountries(metadata.country, track.country) &&
                compatibleLibraryCountries(members[key], track.country)
        }

    private fun normalizeAlbumMetadata(metadata: LibraryAlbumMetadata, items: List<LibraryEntry>): LibraryAlbumMetadata? {
        if (metadata.albumId == "0" || !validCatalogId(metadata.source, "album", metadata.albumId) || metadata.trackCount == null || metadata.trackCount !in 0..100_000) return null
        val country = if (metadata.source == Source.Apple) metadata.country.normalizedAppleCountry() ?: return null else null
        val references = if (metadata.source == Source.Apple) savedAppleTracks(metadata, savedAppleSongs(items)) else metadata.tracks
        val tracks = references.asSequence().filter { it.source == metadata.source && it.albumId == metadata.albumId }
            .mapNotNull { normalizeHit(SearchCatalog.trackHit(it))?.track }.distinctBy { it.id }.take(MAX_ITEMS).toList()
        return metadata.copy(albumArtist = metadata.albumArtist.clean(300), tracks = tracks, country = country,
            artists = normalizeAlbumArtists(metadata.source, metadata.artists))
    }

    private fun boundedAlbumMetadata(records: List<LibraryAlbumMetadata>): List<LibraryAlbumMetadata> {
        val apple = records.filter { it.source == Source.Apple }
        require(apple.size <= MAX_ITEMS && apple.sumOf { it.tracks.size.toLong() + it.artists.orEmpty().size } <= MAX_TOTAL_TRACKS) {
            "Apple album metadata exceeds storage limits"
        }
        val other = records.filter { it.source != Source.Apple }
        var first = 0
        var references = other.sumOf { it.tracks.size.toLong() + it.artists.orEmpty().size }
        while (other.size - first > MAX_ITEMS || references > MAX_TOTAL_TRACKS) {
            val removed = other[first++]
            references -= removed.tracks.size + removed.artists.orEmpty().size
        }
        if (first == 0) return records
        var discard = first
        return records.filter { it.source == Source.Apple || discard-- <= 0 }
    }

    /** Add a playable song (or a catalog music video snapshot) once to a local playlist. */
    fun addToPlaylist(id: String, hit: CatalogHit) {
        val clean = normalizeHit(hit) ?: return
        if (clean.kind != "song" && clean.kind != "music-video") return
        commands.trySend { state ->
            val index = state.playlists.indexOfFirst { it.id == id }
            if (index < 0) return@trySend state
            val list = state.playlists[index]
            val key = LibraryKey(clean.source, clean.kind, clean.id)
            val duplicate = list.tracks.indexOfFirst { LibraryKey(it.hit.source, it.hit.kind, it.hit.id) == key }
            if (duplicate >= 0) {
                val old = list.tracks[duplicate]
                val country = clean.country ?: old.hit.country
                val refreshed = old.hit.copy(country = country, track = old.hit.track?.copy(country = country))
                if (refreshed == old.hit) return@trySend state
                val tracks = list.tracks.toMutableList().apply { this[duplicate] = old.copy(hit = refreshed) }
                return@trySend state.copy(playlists = state.playlists.toMutableList().apply { this[index] = list.copy(tracks = tracks) })
            }
            if (list.tracks.size >= MAX_TRACKS_PER_PLAYLIST || state.playlists.sumOf { it.tracks.size } >= MAX_TOTAL_TRACKS) return@trySend state
            val updated = list.copy(tracks = list.tracks + LibraryPlaylistTrack(newUid(), now(), clean), updatedAt = now())
            state.copy(playlists = state.playlists.toMutableList().also { it[index] = updated })
        }
    }

    fun remove(hit: CatalogHit) {
        val key = LibraryKey(hit.source, hit.kind, hit.id)
        commands.trySend { state -> state.copy(items = state.items.filterNot { it.key == key }) }
    }

    fun createPlaylist(name: String, folderId: String = ""): String {
        val id = newId("p.")
        val cleanName = name.trim().take(200)
        if (cleanName.isEmpty()) return ""
        commands.trySend { state ->
            if (state.playlists.size >= MAX_PLAYLISTS) return@trySend state
            val time = now()
            val safeFolder = folderId.takeIf { candidate -> state.folders.any { it.id == candidate } }.orEmpty()
            state.copy(playlists = state.playlists + LibraryPlaylist(id, cleanName, "", safeFolder, 0, time, time, emptyList()))
        }
        return id
    }

    fun deletePlaylist(id: String) {
        commands.trySend { state -> state.copy(playlists = state.playlists.filterNot { it.id == id }) }
    }

    fun togglePlaylistFavorite(id: String) {
        commands.trySend { state ->
            val time = now()
            state.copy(playlists = state.playlists.map { if (it.id == id) it.copy(favoriteAt = if (it.favoriteAt > 0) 0 else time) else it })
        }
    }

    fun removeFromPlaylist(id: String, uid: String) {
        commands.trySend { state ->
            state.copy(playlists = state.playlists.map { list ->
                if (list.id != id) list else list.copy(tracks = list.tracks.filterNot { it.uid == uid }, updatedAt = now())
            })
        }
    }

    fun movePlaylistTrack(id: String, from: Int, to: Int) {
        commands.trySend { state ->
            val lists = state.playlists.toMutableList()
            val index = lists.indexOfFirst { it.id == id }
            if (index < 0) return@trySend state
            val list = lists[index]
            if (from !in list.tracks.indices || to !in list.tracks.indices || from == to) return@trySend state
            val tracks = list.tracks.toMutableList()
            val moved = tracks.removeAt(from)
            tracks.add(to, moved)
            lists[index] = list.copy(tracks = tracks, updatedAt = now())
            state.copy(playlists = lists)
        }
    }

    fun createFolder(name: String, parentId: String = ""): String {
        val id = newId("f.")
        val cleanName = name.trim().take(200)
        if (cleanName.isEmpty()) return ""
        commands.trySend { state ->
            if (state.folders.size >= MAX_FOLDERS) return@trySend state
            val time = now()
            val safeParent = parentId.takeIf { candidate -> state.folders.any { it.id == candidate } }.orEmpty()
            state.copy(folders = state.folders + LibraryFolder(id, cleanName, safeParent, time, time))
        }
        return id
    }

    /** Delete a folder subtree and its local/catalog playlists, matching the upstream behavior. */
    fun deleteFolder(id: String) {
        commands.trySend { state ->
            if (state.folders.none { it.id == id }) return@trySend state
            val removed = mutableSetOf(id)
            var changed = true
            while (changed) {
                changed = false
                state.folders.forEach { if (it.parentId in removed && removed.add(it.id)) changed = true }
            }
            val deletedLists = state.playlists.filterNot { it.folderId in removed }
            state.copy(
                folders = state.folders.filterNot { it.id in removed },
                playlists = deletedLists,
                items = state.items.filterNot { it.hit.kind == "playlist" && it.folderId in removed },
            )
        }
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().take(200)
        if (clean.isBlank()) return
        commands.trySend { state -> state.copy(playlists = state.playlists.map { if (it.id == id) it.copy(name = clean, updatedAt = now()) else it }) }
    }

    fun renameFolder(id: String, name: String) {
        val clean = name.trim().take(200)
        if (clean.isBlank()) return
        commands.trySend { state -> state.copy(folders = state.folders.map { if (it.id == id) it.copy(name = clean, updatedAt = now()) else it }) }
    }

    fun movePlaylist(id: String, folderId: String) {
        commands.trySend { state ->
            if (folderId.isNotBlank() && state.folders.none { it.id == folderId }) return@trySend state
            state.copy(playlists = state.playlists.map { if (it.id == id) it.copy(folderId = folderId, updatedAt = now()) else it })
        }
    }

    /** Invalid destinations and moves into a descendant are rejected without modifying the tree. */
    fun moveFolder(id: String, parentId: String) {
        commands.trySend { state ->
            val folders = state.folders.associateBy { it.id }
            if (id !in folders || (parentId.isNotBlank() && parentId !in folders)) return@trySend state
            var ancestor = folders[parentId]
            val seen = mutableSetOf<String>()
            while (ancestor != null && seen.add(ancestor.id)) {
                if (ancestor.id == id) return@trySend state
                ancestor = folders[ancestor.parentId]
            }
            state.copy(folders = state.folders.map { if (it.id == id) it.copy(parentId = parentId, updatedAt = now()) else it })
        }
    }

    fun moveCatalogPlaylist(hit: CatalogHit, folderId: String) {
        if (hit.kind != "playlist") return
        val key = LibraryKey(hit.source, hit.kind, hit.id)
        commands.trySend { state ->
            if (folderId.isNotBlank() && state.folders.none { it.id == folderId }) return@trySend state
            state.copy(items = state.items.map { if (it.key == key) it.copy(folderId = folderId) else it })
        }
    }

    /** Serialize a point-in-time snapshot for the SAF CreateDocument flow. */
    fun exportJson(): String = encodeSnapshot(snapshot.value.copy(loading = false))

    /** Parse and validate before enqueuing an import. Replace is destructive, so the caller owns confirmation. */
    fun importJson(text: String, replace: Boolean = false): LibraryImportReport {
        val parsed = parseImport(text)
        commands.trySend { state -> mergeImport(state, parsed, replace) }
        return LibraryImportReport(parsed.items.size, parsed.playlists.size, parsed.folders.size, parsed.skipped)
    }


    private fun readStoredSnapshot(): MusicLibrarySnapshot {
        return try {
            if (!file.exists() && !File(file.path + ".bak").exists()) MusicLibrarySnapshot(loading = false)
            else {
                val text = atomicFile.openRead().use { input ->
                    require(input.channel.size() <= MAX_FILE_BYTES) { "Stored library is too large" }
                    input.bufferedReader(Charsets.UTF_8).readText()
                }
                val parsed = parseImport(text)
                repairFolders(MusicLibrarySnapshot(parsed.items, parsed.playlists, parsed.folders, loading = false, albumMetadata = parsed.albumMetadata))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MusicLibrarySnapshot(loading = false, persistenceError = true)
        }
    }

    private fun atomicWrite(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Library exceeds storage limit" }
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun now() = System.currentTimeMillis()

    private data class Imported(
        val items: List<LibraryEntry>,
        val playlists: List<LibraryPlaylist>,
        val folders: List<LibraryFolder>,
        val skipped: Int = 0,
        val albumMetadata: List<LibraryAlbumMetadata> = emptyList(),
    )

    private fun mergeImport(current: MusicLibrarySnapshot, data: Imported, replace: Boolean): MusicLibrarySnapshot {
        val oldItems = if (replace) emptyList() else current.items
        val itemMap = oldItems.associateBy { it.key }.toMutableMap()
        data.items.forEach { incoming ->
            val old = itemMap[incoming.key]
            itemMap[incoming.key] = if (old == null) incoming else incoming.copy(
                hit = mergeSongMetadata(incoming.hit, old.hit),
                albumTracks = if (incoming.hit.kind == "album") mergeAlbumTracks(incoming.albumTracks, old.albumTracks) else incoming.albumTracks,
                addedAt = minOf(incoming.addedAt, old.addedAt),
                favoriteAt = maxOf(incoming.favoriteAt, old.favoriteAt),
                folderId = incoming.folderId.ifBlank { old.folderId },
            )
        }
        val oldFolders = if (replace) emptyList() else current.folders
        val folderMap = oldFolders.associateBy { it.id }.toMutableMap()
        data.folders.forEach { incoming ->
            if (folderMap[incoming.id]?.updatedAt?.let { it >= incoming.updatedAt } != true) folderMap[incoming.id] = incoming
        }
        val oldPlaylists = if (replace) emptyList() else current.playlists
        val playlistMap = oldPlaylists.associateBy { it.id }.toMutableMap()
        data.playlists.forEach { incoming ->
            val old = playlistMap[incoming.id]
            val winner = if (old == null || old.updatedAt < incoming.updatedAt) incoming else old
            val other = if (winner === incoming) old else incoming
            playlistMap[incoming.id] = fillPlaylistCountries(winner, other)
        }
        require(itemMap.size <= MAX_ITEMS && folderMap.size <= MAX_FOLDERS && playlistMap.size <= MAX_PLAYLISTS &&
            playlistMap.values.sumOf { it.tracks.size } <= MAX_TOTAL_TRACKS) { "Merged library exceeds storage limits" }
        val items = itemMap.values.toList()
        val previous = current.items.associateBy { it.key }
        val invalidated = if (replace) buildSet {
            current.items.mapNotNullTo(this) { appleAlbumId(it.hit) }
            items.mapNotNullTo(this) { appleAlbumId(it.hit) }
            current.albumMetadata.filter { it.source == Source.Apple }.mapTo(this) { it.albumId }
            data.albumMetadata.filter { it.source == Source.Apple }.mapTo(this) { it.albumId }
        } else items.mapNotNull { entry ->
            val albumId = appleAlbumId(entry.hit) ?: return@mapNotNull null
            val old = previous[entry.key]
            albumId.takeIf { old == null || (entry.hit.kind == "song" && appleAlbumId(old.hit) != albumId) }
        }.toSet()
        val metadata = LinkedHashMap<Triple<Source, String, String?>, LibraryAlbumMetadata>()
        if (!replace) current.albumMetadata.forEach { metadata[albumMetadataKey(it)] = it }
        data.albumMetadata.forEach { incoming ->
            val key = albumMetadataKey(incoming)
            val old = metadata[key]
            if (old?.source == Source.Apple) return@forEach
            metadata.remove(key)
            metadata[key] = if (incoming.artists == null) incoming.copy(artists = old?.artists) else incoming
        }
        val records = metadata.values.filterNot { !replace && it.source == Source.Apple && it.albumId in invalidated }
            .mapNotNull { normalizeAlbumMetadata(it, items) }
        val next = repairSongOrderFromAlbumTracks(repairFolders(MusicLibrarySnapshot(items, playlistMap.values.toList(), folderMap.values.toList(),
            loading = false, albumMetadata = boundedAlbumMetadata(records), albumMetadataRevisions = current.albumMetadataRevisions)))
        val revised = invalidateAppleAlbumMetadata(next, invalidated)
        return if (replace) revised.copy(albumMetadata = next.albumMetadata) else revised
    }

    private fun parseImport(text: String): Imported {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Library file is too large" }
        requireBoundedJsonDepth(text)
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IllegalArgumentException("Invalid library JSON") }
        require(root.string("format") == FORMAT) { "Not a Fantastic library file" }
        val version = root.int("version") ?: throw IllegalArgumentException("Invalid library version")
        require(version in 0..FORMAT_VERSION) { "Library file is from a newer version" }
        for (field in listOf("items", "playlists", "folders")) {
            require(root[field] == null || root[field] is JsonArray) { "Invalid $field array" }
        }
        val now = System.currentTimeMillis()
        val rawItems = root.array("items").take(MAX_ITEMS)
        val rawLists = root.array("playlists").take(MAX_PLAYLISTS)
        val rawFolders = root.array("folders").take(MAX_FOLDERS)
        val itemMap = LinkedHashMap<LibraryKey, LibraryEntry>()
        rawItems.mapNotNull { raw -> raw.objectOrNull()?.let { parseEntry(it, now) } }.forEach { entry ->
            val old = itemMap[entry.key]
            itemMap[entry.key] = if (old == null) entry else old.copy(hit = fillHitCountry(old.hit, entry.hit),
                albumTracks = mergeAlbumTracks(old.albumTracks, entry.albumTracks))
        }
        val items = itemMap.values.toList()
        val folders = rawFolders.mapNotNull { raw -> raw.objectOrNull()?.let { parseFolder(it, now) } }
            .distinctBy { it.id }
        var totalTracks = 0
        val playlistMap = LinkedHashMap<String, LibraryPlaylist>()
        rawLists.mapNotNull { raw ->
            raw.objectOrNull()?.let { obj ->
                val list = parsePlaylist(obj, now, MAX_TOTAL_TRACKS - totalTracks)
                totalTracks += list?.tracks?.size ?: 0
                list
            }
        }.forEach { list -> playlistMap[list.id] = playlistMap[list.id]?.let { fillPlaylistCountries(it, list) } ?: list }
        val playlists = playlistMap.values.toList()
        val metadata = LinkedHashMap<Triple<Source, String, String?>, LibraryAlbumMetadata>()
        root.array("albumMetadata").forEach { raw -> raw.objectOrNull()?.let { parseAlbumMetadata(it, items) }?.let {
            val key = albumMetadataKey(it)
            val old = metadata.remove(key)
            metadata[key] = if (it.artists == null) it.copy(artists = old?.artists) else it
        } }
        val albumMetadata = boundedAlbumMetadata(metadata.values.toList())
        val skipped = root.array("items").size - items.size + root.array("playlists").size - playlists.size +
            root.array("folders").size - folders.size + root.array("playlists").sumOf { raw ->
                raw.objectOrNull()?.array("tracks")?.size ?: 0
            } - playlists.sumOf { it.tracks.size }
        return repairFolders(MusicLibrarySnapshot(items, playlists, folders, loading = false, albumMetadata = albumMetadata)).let {
            Imported(it.items, it.playlists, it.folders, skipped.coerceAtLeast(0), it.albumMetadata)
        }
    }

    private fun parseEntry(raw: JsonObject, fallbackTime: Long): LibraryEntry? {
        val hit = parseHit(raw) ?: return null
        val albumTracks = if (hit.kind == "album") deduplicateTracks(raw.array("albumTracks").asSequence().take(MAX_ITEMS)
            .mapNotNull { it.objectOrNull()?.let(::parseHit)?.track }
            .filter { it.source == hit.source && it.albumId == hit.id }.toList()) else emptyList()
        return LibraryEntry(hit, raw.long("addedAt")?.validTime() ?: fallbackTime,
            raw.long("favorite")?.validTime() ?: raw.long("favoriteAt")?.validTime() ?: 0,
            safeFolderRef(raw.string("folderId").orEmpty()), cleanMetadata(raw), albumTracks)
    }

    private fun parseAlbumMetadata(raw: JsonObject, items: List<LibraryEntry>): LibraryAlbumMetadata? {
        val source = Source.entries.firstOrNull { it.mediaPrefix == raw.string("source") } ?: return null
        val id = raw.string("albumId") ?: return null
        val count = raw.int("trackCount") ?: return null
        val country = if (source == Source.Apple) {
            if (raw.string("countrySource") != "catalog") return null
            raw.string("country").normalizedAppleCountry() ?: return null
        } else null
        if (raw["tracks"] !is JsonArray || raw["albumArtist"] !is JsonPrimitive) return null
        val tracks = raw.array("tracks").asSequence().take(MAX_ITEMS).mapNotNull { it.objectOrNull()?.let(::parseHit)?.track }.toList()
        val artists = (raw["artists"] as? JsonArray)?.let { values ->
            val first = values.firstOrNull()?.objectOrNull()?.let(::parseHit)
            if (first == null || !validAlbumArtist(source, first)) emptyList() else
                listOf(first) + values.asSequence().drop(1).take(MAX_ITEMS - 1).mapNotNull { it.objectOrNull()?.let(::parseHit) }
                    .filter { validAlbumArtist(source, it) }.distinctBy { it.id }.filter { it.id != first.id }.toList()
        }
        return normalizeAlbumMetadata(LibraryAlbumMetadata(source, id, raw.string("albumArtist").clean(300), tracks, count, country, artists), items)
    }

    private fun fillHitCountry(hit: CatalogHit, reference: CatalogHit): CatalogHit {
        if (hit.source != Source.Apple || hit.source != reference.source || hit.kind != reference.kind || hit.id != reference.id) return hit
        val country = hit.country ?: reference.country
        val track = hit.track?.let { song ->
            val merged = reference.track?.let(song::fillRelationCountriesFrom) ?: song
            if (hit.country == null && reference.country != null) merged.copy(country = reference.country) else merged
        }
        return if (country == hit.country && track == hit.track) hit else hit.copy(country = country, track = track)
    }

    private fun fillPlaylistCountries(winner: LibraryPlaylist, other: LibraryPlaylist?): LibraryPlaylist {
        if (other == null) return winner
        val references = other.tracks.associateBy { it.uid }
        var changed = false
        val tracks = winner.tracks.map { row ->
            val reference = references[row.uid] ?: return@map row
            val hit = fillHitCountry(row.hit, reference.hit)
            if (hit == row.hit) row else row.copy(hit = hit).also { changed = true }
        }
        return if (changed) winner.copy(tracks = tracks) else winner
    }

    private fun parsePlaylist(raw: JsonObject, now: Long, availableTracks: Int): LibraryPlaylist? {
        val created = raw.long("createdAt")?.validTime() ?: now
        val validId = raw.string("id")?.takeIf(::validPlaylistId) ?: newId("p.")
        val seenUids = mutableSetOf<String>()
        val tracks = mutableListOf<LibraryPlaylistTrack>()
        var available = availableTracks.coerceAtLeast(0).coerceAtMost(MAX_TRACKS_PER_PLAYLIST)
        for (element in raw.array("tracks").take(MAX_TRACKS_PER_PLAYLIST)) {
            if (available <= 0) break
            val obj = element.objectOrNull() ?: continue
            val hit = parseHit(obj) ?: continue
            if (hit.kind != "song" && hit.kind != "music-video") continue
            val supplied = obj.string("uid").orEmpty()
            val uid = supplied.takeIf { it.matches(UID_REGEX) && seenUids.add(it) } ?: newUid().also(seenUids::add)
            tracks += LibraryPlaylistTrack(uid, obj.long("addedAt")?.validTime() ?: created, hit, cleanMetadata(obj))
            available--
        }
        return LibraryPlaylist(validId, raw.string("name").clean(200), raw.string("description").clean(4000),
            safeFolderRef(raw.string("folderId").orEmpty()), raw.long("favorite")?.validTime() ?: 0,
            created, maxOf(created, raw.long("updatedAt")?.validTime() ?: created), tracks)
    }

    private fun parseFolder(raw: JsonObject, now: Long): LibraryFolder? {
        val id = raw.string("id")?.takeIf(::validFolderId) ?: return null
        val created = raw.long("createdAt")?.validTime() ?: now
        return LibraryFolder(id, raw.string("name").clean(200), safeFolderRef(raw.string("parentId").orEmpty()), created,
            maxOf(created, raw.long("updatedAt")?.validTime() ?: created))
    }

    private fun parseHit(raw: JsonObject): CatalogHit? {
        val extension = raw["fantastic"]?.objectOrNull()
        val kind = (extension?.string("kind") ?: raw.string("kind"))?.takeIf { it in KINDS } ?: return null
        val id = (extension?.string("id") ?: raw.string("id"))?.clean(128)?.takeIf { it.matches(ID_REGEX) } ?: return null
        val sourceText = extension?.string("source") ?: raw.string("source")
        val source = if (sourceText.isNullOrBlank()) Source.Apple else
            Source.entries.firstOrNull { it.mediaPrefix == sourceText || it.name == sourceText } ?: return null
        if (!validCatalogId(source, kind, id)) return null
        val title = (extension?.string("title") ?: raw.string("name") ?: raw.string("title") ?: id).clean(300).ifBlank { id }
        val subtitle = (extension?.string("subtitle") ?: raw.string("artist") ?: raw.string("curator")).clean(300)
        val art = cleanArtwork(extension?.string("artwork") ?: raw.artworkString())
        val explicit = extension?.boolean("explicit") ?: raw.boolean("explicit") ?: false
        val attributes = extension?.get("attributes") ?: raw["attributes"]
        val boundedAttributes = attributes?.takeIf { it.toString().length <= MAX_ATTRIBUTES_CHARS }
        val track = (extension?.get("track") ?: raw["track"])?.objectOrNull()?.let { parseTrack(it, source, id, title, subtitle, art) }
            ?: if (kind == "song") fallbackTrack(source, id, title, subtitle, raw.string("album").clean(300), art,
                (extension?.long("durationMs") ?: raw.long("duration"))?.takeIf { it in 1..MAX_DURATION_MS } ?: 0).copy(
                    albumId = (extension?.string("albumId") ?: raw.string("albumId")).clean(128)
                        .takeIf { validCatalogId(source, "album", it) }.orEmpty(),
                ) else null
        val country = if (source == Source.Apple) {
            val supplied = (extension?.string("country") ?: raw.string("country")).normalizedAppleCountry()
            val marked = (extension?.string("countrySource") ?: raw.string("countrySource")) == "catalog"
            val type = when (kind) { "song" -> "songs"; "music-video" -> "music-videos"; else -> "${kind}s" }
            val resourceHref = extension?.string("href") ?: raw.string("href")
            val href = resourceHref?.takeUnless { it.removePrefix("/").startsWith("https://") }
            val url = extension?.string("url") ?: raw.string("url") ?: (attributes as? JsonObject)?.string("url")
                ?: resourceHref?.removePrefix("/")?.takeIf { it.startsWith("https://music.apple.com/") }
            track?.country?.takeIf { kind == "song" } ?: supplied.takeIf { marked } ?: appleResourceCountry(type, id, href, url, null)
                ?: supplied.takeUnless { it == "us" }
        } else null
        return CatalogHit(source, kind, id, title, subtitle, art, track?.copy(country = country), explicit,
            extension?.boolean("detailOnly") ?: raw.boolean("detailOnly") ?: true, boundedAttributes, country)
    }

    private fun parseTrack(raw: JsonObject, expectedSource: Source, expectedId: String, title: String, subtitle: String, artwork: String?): Track? {
        val source = raw.string("source")?.let { value -> Source.entries.firstOrNull { it.mediaPrefix == value || it.name == value } ?: return null } ?: expectedSource
        val id = raw.string("id")?.clean(128)?.let { it.takeIf { it.matches(ID_REGEX) } ?: return null } ?: expectedId
        if (source != expectedSource || id != expectedId) return null
        val artistId = raw.string("artistId").clean(128).takeIf { validCatalogId(source, "artist", it) }.orEmpty()
        val albumId = raw.string("albumId").clean(128).takeIf { validCatalogId(source, "album", it) }.orEmpty()
        return Track(
            id = id, source = source, title = raw.string("title").clean(300).ifBlank { title },
            artist = raw.string("artist").clean(300).ifBlank { subtitle }, album = raw.string("album").clean(300),
            artworkTemplate = cleanArtwork(raw.string("artworkTemplate")) ?: artwork,
            durationMs = raw.long("durationMs")?.takeIf { it in 0..MAX_DURATION_MS } ?: 0,
            artistId = artistId,
            albumId = albumId,
            artistArtworkTemplate = cleanArtwork(raw.string("artistArtworkTemplate")),
            albumArtist = raw.string("albumArtist").clean(300),
            discNumber = raw.int("discNumber")?.coerceAtLeast(0) ?: 0,
            trackNumber = raw.int("trackNumber")?.coerceAtLeast(0) ?: 0,
            country = if (source == Source.Apple) raw.string("country").normalizedAppleCountry() else null,
            artistCountry = if (source == Source.Apple && artistId != "0" && validCatalogId(source, "artist", artistId)) raw.string("artistCountry").normalizedAppleCountry() else null,
            albumCountry = if (source == Source.Apple && albumId != "0" && validCatalogId(source, "album", albumId)) raw.string("albumCountry").normalizedAppleCountry() else null,
        )
    }

    private fun normalizeHit(hit: CatalogHit): CatalogHit? {
        if (hit.kind !in KINDS || !validCatalogId(hit.source, hit.kind, hit.id)) return null
        val title = hit.title.clean(300).ifBlank { hit.id }
        val subtitle = hit.subtitle.clean(300)
        val country = if (hit.source == Source.Apple) hit.country.normalizedAppleCountry() ?: hit.track?.takeIf { it.source == hit.source && it.id == hit.id }?.country.normalizedAppleCountry() else null
        val track = hit.track?.takeIf { it.source == hit.source && it.id == hit.id }?.let {
            val artistId = it.artistId.clean(128).takeIf { id -> validCatalogId(it.source, "artist", id) }.orEmpty()
            val albumId = it.albumId.clean(128).takeIf { id -> validCatalogId(it.source, "album", id) }.orEmpty()
            Track(
                id = it.id, source = it.source, title = it.title.clean(300).ifBlank { title }, artist = it.artist.clean(300),
                album = it.album.clean(300), artworkTemplate = cleanArtwork(it.artworkTemplate),
                durationMs = it.durationMs.coerceIn(0, MAX_DURATION_MS),
                artistId = artistId,
                albumId = albumId,
                artistArtworkTemplate = cleanArtwork(it.artistArtworkTemplate), albumArtist = it.albumArtist.clean(300),
                discNumber = it.discNumber.coerceAtLeast(0), trackNumber = it.trackNumber.coerceAtLeast(0),
                country = country,
                artistCountry = if (it.source == Source.Apple && artistId != "0" && validCatalogId(it.source, "artist", artistId)) it.artistCountry.normalizedAppleCountry() else null,
                albumCountry = if (it.source == Source.Apple && albumId != "0" && validCatalogId(it.source, "album", albumId)) it.albumCountry.normalizedAppleCountry() else null,
            )
        }
        val attrs = hit.attributes?.takeIf { it.toString().length <= MAX_ATTRIBUTES_CHARS }
        return CatalogHit(hit.source, hit.kind, hit.id, title, subtitle, cleanArtwork(hit.artwork), track,
            hit.explicit, hit.detailOnly, attrs, country)
    }

    private fun fallbackTrack(source: Source, id: String, title: String, artist: String, album: String, artwork: String?, durationMs: Long) =
        Track(id, source, title, artist, album, artwork, durationMs)

    private fun repairFolders(state: MusicLibrarySnapshot): MusicLibrarySnapshot {
        val folders = state.folders.associateBy { it.id }.toMutableMap()
        folders.replaceAll { _, folder -> if (folder.parentId.isNotBlank() && folder.parentId !in folders) folder.copy(parentId = "") else folder }
        for (folder in folders.values.toList()) {
            val seen = mutableSetOf(folder.id)
            var parent = folders[folder.parentId]
            while (parent != null) {
                if (!seen.add(parent.id)) {
                    folders[parent.id] = parent.copy(parentId = "")
                    break
                }
                parent = folders[parent.parentId]
            }
        }
        val folderIds = folders.keys
        val playlists = state.playlists.map { if (it.folderId.isNotBlank() && it.folderId !in folderIds) it.copy(folderId = "") else it }
        val items = state.items.map { if (it.folderId.isNotBlank() && it.folderId !in folderIds) it.copy(folderId = "") else it }
        return state.copy(folders = folders.values.toList(), playlists = playlists, items = items)
    }

    private fun encodeSnapshot(state: MusicLibrarySnapshot): String = buildJsonObject {
        put("format", FORMAT)
        put("version", FORMAT_VERSION)
        put("exportedAt", java.time.Instant.now().toString())
        put("items", buildJsonArray {
            state.items.forEach { entry ->
                val hit = encodeHit(entry.hit, entry.addedAt, entry.favoriteAt, entry.folderId, metadata = entry.metadata)
                add(if (entry.albumTracks.isEmpty()) hit else buildJsonObject {
                    hit.forEach { (key, value) -> put(key, value) }
                    put("albumTracks", buildJsonArray {
                        entry.albumTracks.forEach { add(encodeHit(SearchCatalog.trackHit(it))) }
                    })
                })
            }
        })
        put("playlists", buildJsonArray {
            state.playlists.forEach { playlist ->
                add(buildJsonObject {
                    put("id", playlist.id); put("name", playlist.name); put("description", playlist.description)
                    put("folderId", playlist.folderId); put("favorite", playlist.favoriteAt)
                    put("createdAt", playlist.createdAt); put("updatedAt", playlist.updatedAt)
                    put("tracks", buildJsonArray {
                        playlist.tracks.forEach { track -> add(encodeHit(track.hit, track.addedAt, uid = track.uid, metadata = track.metadata)) }
                    })
                })
            }
        })
        put("folders", buildJsonArray {
            state.folders.forEach { folder -> add(buildJsonObject {
                put("id", folder.id); put("name", folder.name); put("parentId", folder.parentId)
                put("createdAt", folder.createdAt); put("updatedAt", folder.updatedAt)
            }) }
        })
        put("albumMetadata", buildJsonArray {
            val appleMembers = savedAppleSongs(state.items)
            state.albumMetadata.forEach { metadata -> add(buildJsonObject {
                put("source", metadata.source.mediaPrefix); put("albumId", metadata.albumId)
                put("albumArtist", metadata.albumArtist); put("trackCount", metadata.trackCount)
                if (metadata.source == Source.Apple) {
                    put("country", metadata.country); put("countrySource", "catalog")
                }
                metadata.artists?.let { artists -> put("artists", buildJsonArray { artists.forEach { add(encodeHit(it)) } }) }
                val tracks = if (metadata.source == Source.Apple) savedAppleTracks(metadata, appleMembers) else metadata.tracks
                put("tracks", buildJsonArray { tracks.forEach { add(encodeHit(SearchCatalog.trackHit(it))) } })
            }) }
        })
    }.toString()

    private fun encodeHit(hit: CatalogHit, addedAt: Long? = null, favoriteAt: Long? = null, folderId: String? = null, uid: String? = null, metadata: JsonObject? = null): JsonObject = buildJsonObject {
        metadata?.forEach { (name, value) -> if (name != "country" && name != "countrySource") put(name, value) }
        put("kind", hit.kind); put("id", hit.id); put("source", hit.source.mediaPrefix)
        val country = if (hit.source == Source.Apple) hit.country.normalizedAppleCountry() ?: hit.track?.country.normalizedAppleCountry() else null
        if (country != null) { put("country", country); put("countrySource", "catalog") }
        put("name", hit.title); put("title", hit.title); put("subtitle", hit.subtitle)
        put("artist", hit.subtitle); put("artwork", hit.artwork ?: ""); put("explicit", hit.explicit); put("detailOnly", hit.detailOnly)
        if (hit.kind == "playlist") put("curator", hit.subtitle)
        hit.track?.let { track ->
            put("album", track.album); put("duration", track.durationMs); put("durationMs", track.durationMs)
            put("track", buildJsonObject {
                put("id", track.id); put("source", track.source.mediaPrefix); put("title", track.title)
                put("artist", track.artist); put("album", track.album); put("artworkTemplate", track.artworkTemplate ?: "")
                put("durationMs", track.durationMs); put("artistId", track.artistId); put("albumId", track.albumId)
                put("artistArtworkTemplate", track.artistArtworkTemplate ?: ""); put("albumArtist", track.albumArtist)
                put("discNumber", track.discNumber); put("trackNumber", track.trackNumber)
                if (country != null) put("country", country)
                if (track.source == Source.Apple && track.artistId != "0" && validCatalogId(track.source, "artist", track.artistId))
                    track.artistCountry.normalizedAppleCountry()?.let { put("artistCountry", it) }
                if (track.source == Source.Apple && track.albumId != "0" && validCatalogId(track.source, "album", track.albumId))
                    track.albumCountry.normalizedAppleCountry()?.let { put("albumCountry", it) }
            })
        }
        hit.attributes?.let { put("attributes", it) }
        if (addedAt != null) put("addedAt", addedAt)
        if (favoriteAt != null) put("favorite", favoriteAt)
        if (!folderId.isNullOrBlank()) put("folderId", folderId)
        if (uid != null) put("uid", uid)
    }


    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNullSafe()
    private fun JsonObject.int(name: String): Int? = long(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()
    private fun JsonObject.artworkString(): String? = string("artwork") ?: (this["artwork"] as? JsonObject)?.string("url")
    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject
    private fun String?.clean(max: Int): String = this.orEmpty().trim().take(max)
    private fun Long.validTime(): Long = takeIf { it > 0 } ?: 0
    private fun cleanArtwork(value: String?): String? = value.clean(600).takeIf { it.startsWith("https://") && !it.any { c -> c.isWhitespace() || c == '<' || c == '>' || c == '\\' } }
    private fun safeFolderRef(value: String): String = value.takeIf(::validFolderId).orEmpty()

    private fun JsonPrimitive.longOrNullSafe(): Long? = longOrNull

    private fun cleanMetadata(raw: JsonObject): JsonObject = buildJsonObject {
        for (name in listOf("album", "albumId", "releaseDate", "bgColor")) {
            raw.string(name)?.takeIf { it.length <= 300 }?.let { put(name, it) }
        }
        for (name in listOf("href", "albumHref", "artistHref")) {
            raw.string(name)?.takeIf(::safePagePath)?.let { put(name, it) }
        }
        raw.long("duration")?.takeIf { it in 0..MAX_DURATION_MS }?.let { put("duration", it) }
        raw.long("trackCount")?.takeIf { it in 0..100_000 }?.let { put("trackCount", it) }
        put("artists", buildJsonArray {
            raw.array("artists").take(20).forEach { value ->
                val artist = value.objectOrNull() ?: return@forEach
                val name = artist.string("name").clean(300)
                if (name.isNotBlank()) add(buildJsonObject {
                    put("name", name)
                    artist.string("href")?.takeIf(::safePagePath)?.let { put("href", it) }
                })
            }
        })
    }

    private fun safePagePath(value: String): Boolean = value.length <= 600 &&
        value.none { it.isWhitespace() || it in "\"'<>\\" } &&
        (value.startsWith("/https://music.apple.com/") || value.startsWith("/https://y.qq.com/n/ryqq/") ||
            value.matches(Regex("/ncm-(song|album|artist|playlist)/[1-9][0-9]{0,18}/?")))

    private fun requireBoundedJsonDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 48) { "Library JSON is too deeply nested" } }
                '}', ']' -> depth--
            }
        }
    }

    private companion object {
        const val FORMAT = "am-hook-library"
        const val FORMAT_VERSION = 1
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
        const val MAX_ITEMS = 10_000
        const val MAX_PLAYLISTS = 500
        const val MAX_FOLDERS = 1_000
        const val MAX_TRACKS_PER_PLAYLIST = 10_000
        const val MAX_TOTAL_TRACKS = 10_000
        const val MAX_ATTRIBUTES_CHARS = 4_096
        const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
        val KINDS = setOf("song", "album", "artist", "playlist", "music-video")
        val ID_REGEX = Regex("[A-Za-z0-9._-]{1,128}")
        val UID_REGEX = Regex("[A-Za-z0-9_-]{4,40}")
        val FOLDER_REGEX = Regex("f\\.[A-Za-z0-9_-]{1,64}")
        val PLAYLIST_REGEX = Regex("p\\.[A-Za-z0-9_-]{1,64}")
        fun validFolderId(value: String) = FOLDER_REGEX.matches(value)
        fun validPlaylistId(value: String) = PLAYLIST_REGEX.matches(value)
        fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "")
        fun newUid() = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
