package io.github.kid141252010.fantastic.ui.library

import io.github.kid141252010.fantastic.data.fillRelationCountriesFrom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.LibraryKey
import io.github.kid141252010.fantastic.data.LibraryPlaylist
import io.github.kid141252010.fantastic.data.MusicLibrarySnapshot
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.compatibleLibraryCountries
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight

internal enum class LibraryCategory(val title: String, val kind: String?, val icon: ImageVector) {
    Playlists("播放列表", "playlist", Icons.AutoMirrored.Rounded.QueueMusic),
    Artists("艺人", "artist", Icons.Rounded.Person),
    Albums("专辑", "album", Icons.Rounded.Album),
    Songs("歌曲", "song", Icons.Rounded.MusicNote),
    Downloads("已下载", null, Icons.Rounded.Download),
    Videos("音乐视频", "music-video", Icons.Rounded.VideoLibrary),
}

/** Album entries include saved songs with a known album ID. The projection never alters the snapshot. */
internal fun libraryAlbums(entries: List<LibraryEntry>): List<LibraryEntry> {
    val albums = LinkedHashMap<LibraryKey, LibraryEntry>()
    for (entry in entries) if (entry.hit.kind == "album") albums[entry.key] = entry
    val authoritativeArtists = HashSet<LibraryKey>()
    val memberCountries = entries.asSequence().filter { it.hit.kind == "song" }.mapNotNull { it.hit.track }
        .filter { it.source == Source.Apple && it.albumId.isNotBlank() && it.albumId != "0" }
        .groupBy { LibraryKey(it.source, "album", it.albumId) }
        .mapValues { (_, tracks) -> tracks.mapNotNull { it.country.normalizedAppleCountry() }.toSet().singleOrNull() }
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
            LibraryEntry(CatalogHit(track.source, "album", track.albumId, track.album, artist, artwork, country = memberCountries[key]), entry.addedAt)
        } else {
            album.copy(hit = album.hit.copy(subtitle = artist, artwork = artwork), addedAt = maxOf(album.addedAt, entry.addedAt))
        }
    }
    return albums.values.map { entry ->
        if (entry.hit.source != Source.Apple || entry.hit.country != null) entry
        else entry.copy(hit = entry.hit.copy(country = memberCountries[entry.key]))
    }
}

internal fun libraryAlbumTracks(album: LibraryEntry, entries: List<LibraryEntry>): List<Track> {
    val saved = entries.asSequence().filter { it.hit.kind == "song" }.mapNotNull { it.hit.track }
        .filter { it.source == album.hit.source && it.albumId == album.hit.id }.toList()
    return orderLibraryAlbumTracks(saved, album.albumTracks)
}

internal fun orderLibraryAlbumTracks(savedTracks: List<Track>, referenceTracks: List<Track>): List<Track> {
    if (savedTracks.isEmpty()) return savedTracks
    val sourceOrder = HashMap<Triple<Source, String, String>, Int>(referenceTracks.size)
    referenceTracks.forEachIndexed { index, track -> sourceOrder.putIfAbsent(Triple(track.source, track.albumId, track.id), index) }
    val tracks = savedTracks.map { track ->
        val reference = sourceOrder[Triple(track.source, track.albumId, track.id)]?.let(referenceTracks::get)
            ?.takeIf { compatibleLibraryCountries(track.country, it.country) }
        if (reference == null) track else track.copy(
            albumArtist = track.albumArtist.ifBlank { reference.albumArtist },
            discNumber = track.discNumber.takeIf { it > 0 } ?: reference.discNumber,
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: reference.trackNumber,
            country = track.country ?: reference.country,
        ).fillRelationCountriesFrom(reference)
    }
    val compatibleOrder = tracks.associate { track ->
        val key = Triple(track.source, track.albumId, track.id)
        key to sourceOrder[key]?.takeIf { compatibleLibraryCountries(track.country, referenceTracks[it].country) }
    }
    if (tracks.none { it.discNumber > 0 || it.trackNumber > 0 || compatibleOrder[Triple(it.source, it.albumId, it.id)] != null }) return tracks
    return tracks.sortedWith(compareBy<Track> { it.discNumber <= 0 }.thenBy { it.discNumber }
        .thenBy { it.trackNumber <= 0 }.thenBy { it.trackNumber }
        .thenBy { compatibleOrder[Triple(it.source, it.albumId, it.id)] ?: Int.MAX_VALUE })
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
    actions: @Composable RowScope.() -> Unit,
    showLibrarySources: Boolean,
) {
    val showVideos = remember(snapshot.items) { snapshot.items.any { it.hit.kind == "music-video" } }
    val categories = remember(showVideos) {
        LibraryCategory.entries.filter { it != LibraryCategory.Videos || showVideos }
    }
    val recent = remember(albums, snapshot.items, snapshot.playlists) {
        buildList<RecentCollection> {
            albums.forEach { add(RecentCollection.Catalog(it)) }
            snapshot.items.forEach { if (it.hit.kind == "playlist") add(RecentCollection.Catalog(it)) }
            snapshot.playlists.forEach { add(RecentCollection.Local(it)) }
        }.sortedByDescending { it.time }
    }
    val gridState = rememberLazyGridState()
    val floatingActions by remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
    val configuration = LocalConfiguration.current
    val horizontalPadding = if (configuration.screenWidthDp < 360) 16.dp else Dimens.Page
    val categoryTextPadding = if (configuration.screenWidthDp >= 600) 12.dp else 10.dp
    val dividerColor = if (isSystemInDarkTheme()) Color(0xA6545458) else Color(0x4A3C3C43)
    Box(modifier.fillMaxSize()) {
        LibraryCoverGrid(Modifier, state = gridState, topPadding = 0.dp, horizontalPadding = horizontalPadding) {
            item(key = "library_title_key", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("资料库", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold).withUserFontWeight(FontWeightRole.Title))
                    if (!floatingActions) actions()
                }
            }
            item(key = "library-categories", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth()) {
                    categories.forEach { category ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onCategory(category) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(category.icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(5.dp))
                            Column(Modifier.weight(1f)) {
                                Text(category.title, Modifier.padding(vertical = categoryTextPadding),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp).withUserFontWeight())
                                HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
                            }
                        }
                    }
                }
            }
            if (notice != null) item(key = "notice", span = { GridItemSpan(maxLineSpan) }) { notice() }
            item(key = "recent-title", span = { GridItemSpan(maxLineSpan) }) {
                Text("最近添加", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
            }
            if (snapshot.loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LibraryLoading() }
            else if (recent.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Md3EmptyState("还没有最近添加的专辑或歌单", "加入专辑或新建歌单后，就能从这里打开。")
            }
            items(recent, key = { it.key }) { collection ->
                when (collection) {
                    is RecentCollection.Catalog -> CatalogCoverCard(collection.entry, onCatalog, onCatalogOptions, showLibrarySources)
                    is RecentCollection.Local -> {
                        val playlist = collection.playlist
                        LibraryCoverCard(playlist.name, playlist.caption(), playlist.coverUrl(), Icons.AutoMirrored.Rounded.QueueMusic) {
                            onPlaylist(playlist)
                        }
                    }
                }
            }
        }
        if (floatingActions) Row(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically, content = actions)
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
    showLibrarySources: Boolean,
) {
    LibraryCoverGrid(modifier) {
        if (notice != null) item(key = "notice", span = { GridItemSpan(maxLineSpan) }) { notice() }
        if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LibraryLoading() }
        else if (albums.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            if (favoritesOnly) Md3EmptyState("没有收藏的专辑", "可以从专辑的更多菜单收藏。", icon = Icons.Rounded.Album)
            else Md3EmptyState("还没有专辑", "把喜欢的专辑加入资料库。", icon = Icons.Rounded.Album)
        }
        items(albums, key = { it.stableKey }) { CatalogCoverCard(it, onCatalog, onCatalogOptions, showLibrarySources) }
    }
}

@Composable
private fun LibraryCoverGrid(
    modifier: Modifier,
    state: LazyGridState = rememberLazyGridState(),
    topPadding: Dp = 8.dp,
    horizontalPadding: Dp = Dimens.Page,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = topPadding,
            bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun CatalogCoverCard(entry: LibraryEntry, onCatalog: (CatalogHit) -> Unit, onOptions: (LibraryEntry) -> Unit, showLibrarySources: Boolean) {
    val hit = entry.hit
    val icon = if (hit.kind == "album") Icons.Rounded.Album else Icons.AutoMirrored.Rounded.QueueMusic
    val subtitle = if (hit.kind == "album") hit.source.librarySubtitle(hit.subtitle, showLibrarySources)
        else hit.subtitle.ifBlank { hit.source.libraryName() }
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
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOptions != null) IconButton(onClick = onOptions) {
                Icon(Icons.Rounded.MoreHoriz, "$title 更多操作")
            }
        }
    }
}
