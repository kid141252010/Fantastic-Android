package io.github.kid141252010.fantastic.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
)

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            var current = readStoredSnapshot()
            mutableSnapshot.value = current.copy(loading = false)
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
                albumTracks.asSequence().filter { it.source == clean.source && it.albumId == clean.id }
                    .distinctBy { it.id }.take(MAX_ITEMS).mapNotNull { normalizeHit(SearchCatalog.trackHit(it))?.track }.toList()
            } else old?.albumTracks.orEmpty()
            val favoriteAt = if (toggleFavorite) {
                if (old?.isFavorite == true) 0 else now()
            } else old?.favoriteAt ?: 0
            val entry = LibraryEntry(clean, old?.addedAt ?: now(), favoriteAt, old?.folderId.orEmpty(), old?.metadata, tracks)
            state.copy(items = if (index < 0) state.items + entry else state.items.toMutableList().apply { this[index] = entry })
        }
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
            if (list.tracks.any { LibraryKey(it.hit.source, it.hit.kind, it.hit.id) == key }) return@trySend state
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
                repairFolders(MusicLibrarySnapshot(parsed.items, parsed.playlists, parsed.folders, loading = false))
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
    )

    private fun mergeImport(current: MusicLibrarySnapshot, data: Imported, replace: Boolean): MusicLibrarySnapshot {
        val oldItems = if (replace) emptyList() else current.items
        val itemMap = oldItems.associateBy { it.key }.toMutableMap()
        data.items.forEach { incoming ->
            val old = itemMap[incoming.key]
            itemMap[incoming.key] = if (old == null) incoming else incoming.copy(
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
            if (playlistMap[incoming.id]?.updatedAt?.let { it >= incoming.updatedAt } != true) playlistMap[incoming.id] = incoming
        }
        require(itemMap.size <= MAX_ITEMS && folderMap.size <= MAX_FOLDERS && playlistMap.size <= MAX_PLAYLISTS &&
            playlistMap.values.sumOf { it.tracks.size } <= MAX_TOTAL_TRACKS) { "Merged library exceeds storage limits" }
        return repairFolders(MusicLibrarySnapshot(itemMap.values.toList(), playlistMap.values.toList(), folderMap.values.toList(), loading = false))
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
        val items = rawItems.mapNotNull { raw -> raw.objectOrNull()?.let { parseEntry(it, now) } }
            .distinctBy { it.key }
        val folders = rawFolders.mapNotNull { raw -> raw.objectOrNull()?.let { parseFolder(it, now) } }
            .distinctBy { it.id }
        var totalTracks = 0
        val playlists = rawLists.mapNotNull { raw ->
            raw.objectOrNull()?.let { obj ->
                val list = parsePlaylist(obj, now, MAX_TOTAL_TRACKS - totalTracks)
                totalTracks += list?.tracks?.size ?: 0
                list
            }
        }.distinctBy { it.id }
        val skipped = root.array("items").size - items.size + root.array("playlists").size - playlists.size +
            root.array("folders").size - folders.size + root.array("playlists").sumOf { raw ->
                raw.objectOrNull()?.array("tracks")?.size ?: 0
            } - playlists.sumOf { it.tracks.size }
        return repairFolders(MusicLibrarySnapshot(items, playlists, folders, loading = false)).let {
            Imported(it.items, it.playlists, it.folders, skipped.coerceAtLeast(0))
        }
    }

    private fun parseEntry(raw: JsonObject, fallbackTime: Long): LibraryEntry? {
        val hit = parseHit(raw) ?: return null
        val albumTracks = if (hit.kind == "album") raw.array("albumTracks").asSequence().take(MAX_ITEMS)
            .mapNotNull { it.objectOrNull()?.let(::parseHit)?.track }
            .filter { it.source == hit.source && it.albumId == hit.id }.distinctBy { it.id }.toList() else emptyList()
        return LibraryEntry(hit, raw.long("addedAt")?.validTime() ?: fallbackTime,
            raw.long("favorite")?.validTime() ?: raw.long("favoriteAt")?.validTime() ?: 0,
            safeFolderRef(raw.string("folderId").orEmpty()), cleanMetadata(raw), albumTracks)
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
        return CatalogHit(source, kind, id, title, subtitle, art, track, explicit,
            extension?.boolean("detailOnly") ?: raw.boolean("detailOnly") ?: true, boundedAttributes)
    }

    private fun parseTrack(raw: JsonObject, expectedSource: Source, expectedId: String, title: String, subtitle: String, artwork: String?): Track? {
        val source = raw.string("source")?.let { value -> Source.entries.firstOrNull { it.mediaPrefix == value || it.name == value } } ?: expectedSource
        val id = raw.string("id")?.clean(128)?.takeIf { it.matches(ID_REGEX) } ?: expectedId
        if (source != expectedSource || id != expectedId) return null
        return Track(
            id = id, source = source, title = raw.string("title").clean(300).ifBlank { title },
            artist = raw.string("artist").clean(300).ifBlank { subtitle }, album = raw.string("album").clean(300),
            artworkTemplate = cleanArtwork(raw.string("artworkTemplate")) ?: artwork,
            durationMs = raw.long("durationMs")?.takeIf { it in 0..MAX_DURATION_MS } ?: 0,
            artistId = raw.string("artistId").clean(128).takeIf { validCatalogId(source, "artist", it) }.orEmpty(),
            albumId = raw.string("albumId").clean(128).takeIf { validCatalogId(source, "album", it) }.orEmpty(),
            artistArtworkTemplate = cleanArtwork(raw.string("artistArtworkTemplate")),
            albumArtist = raw.string("albumArtist").clean(300),
            discNumber = raw.int("discNumber")?.coerceAtLeast(0) ?: 0,
            trackNumber = raw.int("trackNumber")?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun normalizeHit(hit: CatalogHit): CatalogHit? {
        if (hit.kind !in KINDS || !validCatalogId(hit.source, hit.kind, hit.id)) return null
        val title = hit.title.clean(300).ifBlank { hit.id }
        val subtitle = hit.subtitle.clean(300)
        val track = hit.track?.takeIf { it.source == hit.source && it.id == hit.id }?.let {
            Track(
                id = it.id, source = it.source, title = it.title.clean(300).ifBlank { title }, artist = it.artist.clean(300),
                album = it.album.clean(300), artworkTemplate = cleanArtwork(it.artworkTemplate),
                durationMs = it.durationMs.coerceIn(0, MAX_DURATION_MS),
                artistId = it.artistId.clean(128).takeIf { id -> validCatalogId(it.source, "artist", id) }.orEmpty(),
                albumId = it.albumId.clean(128).takeIf { id -> validCatalogId(it.source, "album", id) }.orEmpty(),
                artistArtworkTemplate = cleanArtwork(it.artistArtworkTemplate), albumArtist = it.albumArtist.clean(300),
                discNumber = it.discNumber.coerceAtLeast(0), trackNumber = it.trackNumber.coerceAtLeast(0),
            )
        }
        val attrs = hit.attributes?.takeIf { it.toString().length <= MAX_ATTRIBUTES_CHARS }
        return CatalogHit(hit.source, hit.kind, hit.id, title, subtitle, cleanArtwork(hit.artwork), track,
            hit.explicit, hit.detailOnly, attrs)
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
    }.toString()

    private fun encodeHit(hit: CatalogHit, addedAt: Long? = null, favoriteAt: Long? = null, folderId: String? = null, uid: String? = null, metadata: JsonObject? = null): JsonObject = buildJsonObject {
        metadata?.forEach { (name, value) -> put(name, value) }
        put("kind", hit.kind); put("id", hit.id); put("source", hit.source.mediaPrefix)
        put("country", metadata?.string("country") ?: "us"); put("name", hit.title); put("title", hit.title); put("subtitle", hit.subtitle)
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
        raw.string("country")?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.let { put("country", it.lowercase()) }
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
        val APPLE_ID_REGEX = Regex("[0-9]{1,20}")
        val APPLE_PLAYLIST_REGEX = Regex("pl\\.[A-Za-z0-9._-]{1,100}")
        val QQ_ID_REGEX = Regex("[A-Za-z0-9]{1,32}")
        val NCM_ID_REGEX = Regex("[1-9][0-9]{0,18}")
        fun validCatalogId(source: Source, kind: String, id: String): Boolean = when (source) {
            Source.Apple -> if (kind == "playlist") APPLE_PLAYLIST_REGEX.matches(id) else APPLE_ID_REGEX.matches(id)
            Source.QQ -> QQ_ID_REGEX.matches(id)
            Source.Netease -> NCM_ID_REGEX.matches(id) && id.toLongOrNull() != null
        }
        fun validFolderId(value: String) = FOLDER_REGEX.matches(value)
        fun validPlaylistId(value: String) = PLAYLIST_REGEX.matches(value)
        fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "")
        fun newUid() = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
