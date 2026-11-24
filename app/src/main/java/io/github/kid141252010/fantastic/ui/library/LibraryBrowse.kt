package io.github.kid141252010.fantastic.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.LibraryKey
import io.github.kid141252010.fantastic.data.LibraryPlaylist
import io.github.kid141252010.fantastic.data.MusicLibrarySnapshot
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader

internal enum class LibraryCategory(val title: String, val kind: String?, val icon: ImageVector) {
    Playlists("歌单", "playlist", Icons.AutoMirrored.Rounded.QueueMusic),
    Artists("艺人", "artist", Icons.Rounded.Person),
    Albums("专辑", "album", Icons.Rounded.Album),
    Songs("歌曲", "song", Icons.Rounded.MusicNote),
    Videos("音乐视频", "music-video", Icons.Rounded.VideoLibrary),
    Downloads("已下载", null, Icons.Rounded.Download),
}

/** Album entries include saved songs with a known album ID. The projection never alters the snapshot. */
internal fun libraryAlbums(entries: List<LibraryEntry>): List<LibraryEntry> {
    val albums = LinkedHashMap<LibraryKey, LibraryEntry>()
    for (entry in entries) if (entry.hit.kind == "album") albums[entry.key] = entry
    val authoritativeArtists = HashSet<LibraryKey>()
    for (entry in entries) {
        if (entry.hit.kind != "song") continue
        val track = entry.hit.track ?: continue
        if (track.albumId.isBlank() || track.albumId == "0" || track.album.isBlank()) continue
        val key = LibraryKey(track.source, "album", track.albumId)
        val album = albums[key]
        val artwork = album?.hit?.artwork ?: track.artwork(500) ?: entry.hit.artwork
        val artist = if (track.albumArtist.isNotBlank() && authoritativeArtists.add(key)) track.albumArtist
            else album?.hit?.subtitle?.ifBlank { track.artist } ?: track.artist
        albums[key] = if (album == null) {
            LibraryEntry(CatalogHit(track.source, "album", track.albumId, track.album, artist, artwork), entry.addedAt)
        } else {
            album.copy(hit = album.hit.copy(subtitle = artist, artwork = artwork), addedAt = maxOf(album.addedAt, entry.addedAt))
        }
    }
    return albums.values.toList()
}

internal fun libraryAlbumTracks(album: LibraryEntry, entries: List<LibraryEntry>): List<Track> {
    val saved = entries.asSequence().filter { it.hit.kind == "song" }.mapNotNull { it.hit.track }
        .filter { it.source == album.hit.source && it.albumId == album.hit.id }.toList()
    if (saved.isEmpty()) return emptyList()
    val sourceOrder = HashMap<String, Int>(album.albumTracks.size)
    album.albumTracks.forEachIndexed { index, track -> sourceOrder[track.id] = index }
    val tracks = saved.map { track ->
        val reference = sourceOrder[track.id]?.let(album.albumTracks::get)
        if (reference == null) track else track.copy(
            albumArtist = track.albumArtist.ifBlank { reference.albumArtist },
            discNumber = track.discNumber.takeIf { it > 0 } ?: reference.discNumber,
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: reference.trackNumber,
        )
    }
    if (tracks.none { it.discNumber > 0 || it.trackNumber > 0 }) return tracks
    return tracks.sortedWith(compareBy<Track> { it.discNumber <= 0 }.thenBy { it.discNumber }
        .thenBy { it.trackNumber <= 0 }.thenBy { it.trackNumber }
        .thenBy { sourceOrder[it.id] ?: Int.MAX_VALUE })
}

internal val LibraryEntry.stableKey: String get() = "${hit.source.name}:${hit.kind}:${hit.id}"

internal fun LibraryPlaylist.coverUrl(): String? = tracks.firstNotNullOfOrNull { item ->
    item.hit.track?.artwork(480) ?: item.hit.artwork
}

internal fun LibraryPlaylist.caption(): String = if (tracks.any { it.hit.kind != "song" }) "${tracks.size} 项" else "${tracks.size} 首歌曲"

private sealed interface RecentCollection {
    val time: Long
    val key: String

    data class Catalog(val entry: LibraryEntry) : RecentCollection {
        override val time get() = entry.addedAt
        override val key get() = entry.stableKey
    }

    data class Local(val playlist: LibraryPlaylist) : RecentCollection {
        override val time get() = playlist.createdAt
        override val key get() = "local:${playlist.id}"
    }
}

@Composable
internal fun LibraryHome(
    snapshot: MusicLibrarySnapshot,
    albums: List<LibraryEntry>,
    modifier: Modifier,
    onCategory: (LibraryCategory) -> Unit,
    onCatalog: (CatalogHit) -> Unit,
    onCatalogOptions: (LibraryEntry) -> Unit,
    onPlaylist: (LibraryPlaylist) -> Unit,
    notice: (@Composable () -> Unit)?,
) {
    val showVideos = remember(snapshot.items) { snapshot.items.any { it.hit.kind == "music-video" } }
    val recent = remember(albums, snapshot.items, snapshot.playlists) {
        buildList<RecentCollection> {
            albums.forEach { add(RecentCollection.Catalog(it)) }
            snapshot.items.forEach { if (it.hit.kind == "playlist") add(RecentCollection.Catalog(it)) }
            snapshot.playlists.forEach { add(RecentCollection.Local(it)) }
        }.sortedByDescending { it.time }
    }
    LibraryCoverGrid(modifier) {
        item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    for (category in LibraryCategory.entries) {
                        if (category == LibraryCategory.Videos && !showVideos) continue
                        Md3MediaRow(
                            title = category.title,
                            onClick = { onCategory(category) },
                            leading = { Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        )
                    }
                }
            }
        }
        if (notice != null) item(key = "notice", span = { GridItemSpan(maxLineSpan) }) { notice() }
        item(key = "recent-title", span = { GridItemSpan(maxLineSpan) }) {
            Md3MediaSectionHeader("最近添加", modifier = Modifier)
        }
        if (snapshot.loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LibraryLoading() }
        else if (recent.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Md3EmptyState("还没有最近添加的专辑或歌单", "加入专辑或新建歌单后，就能从这里打开。")
        }
        items(recent, key = { it.key }) { collection ->
            when (collection) {
                is RecentCollection.Catalog -> CatalogCoverCard(collection.entry, onCatalog, onCatalogOptions)
                is RecentCollection.Local -> {
                    val playlist = collection.playlist
                    LibraryCoverCard(playlist.name, playlist.caption(), playlist.coverUrl(), Icons.AutoMirrored.Rounded.QueueMusic) {
                        onPlaylist(playlist)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryAlbumsGrid(
    albums: List<LibraryEntry>,
    favoritesOnly: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onCatalog: (CatalogHit) -> Unit,
    onCatalogOptions: (LibraryEntry) -> Unit,
    notice: (@Composable () -> Unit)?,
) {
    LibraryCoverGrid(modifier) {
        if (notice != null) item(key = "notice", span = { GridItemSpan(maxLineSpan) }) { notice() }
        if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LibraryLoading() }
        else if (albums.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            if (favoritesOnly) Md3EmptyState("没有收藏的专辑", "可以从专辑的更多菜单收藏。", icon = Icons.Rounded.Album)
            else Md3EmptyState("还没有专辑", "把喜欢的专辑加入资料库。", icon = Icons.Rounded.Album)
        }
        items(albums, key = { it.stableKey }) { CatalogCoverCard(it, onCatalog, onCatalogOptions) }
    }
}

@Composable
private fun LibraryCoverGrid(modifier: Modifier, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.Page, end = Dimens.Page, top = 8.dp,
            bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun CatalogCoverCard(entry: LibraryEntry, onCatalog: (CatalogHit) -> Unit, onOptions: (LibraryEntry) -> Unit) {
    val hit = entry.hit
    val icon = if (hit.kind == "album") Icons.Rounded.Album else Icons.AutoMirrored.Rounded.QueueMusic
    val subtitle = hit.subtitle.ifBlank { hit.source.libraryName() }
    LibraryCoverCard(hit.title, subtitle, hit.artwork, icon, onOptions = { onOptions(entry) }) { onCatalog(hit) }
}

@Composable
private fun LibraryCoverCard(
    title: String,
    subtitle: String,
    artwork: String?,
    icon: ImageVector,
    onOptions: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Md3MediaArtwork(artwork, Modifier.fillMaxWidth().aspectRatio(1f), icon = icon)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = if (onOptions == null) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOptions != null) IconButton(onClick = onOptions) {
                Icon(Icons.Rounded.MoreHoriz, "$title 更多操作")
            }
        }
    }
}
