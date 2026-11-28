package io.github.kid141252010.fantastic.ui.library
import androidx.compose.foundation.background

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.LibraryAlbumMetadata
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.libraryAlbumMetadata
import io.github.kid141252010.fantastic.data.librarySongHit
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.data.albumMetadataRevision
import io.github.kid141252010.fantastic.data.matchesAlbum
import io.github.kid141252010.fantastic.data.LibraryKey
import io.github.kid141252010.fantastic.data.libraryAlbumArtists
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.DownloadStatusMark
import io.github.kid141252010.fantastic.ui.rememberCollectionDownloads
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.queueSwipeActionLabel
import io.github.kid141252010.fantastic.ui.MediaActionContent
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight
import io.github.kid141252010.fantastic.ui.AmCollectionHeader
import io.github.kid141252010.fantastic.ui.AmSongMoreButton
import io.github.kid141252010.fantastic.ui.AmSongRow
import io.github.kid141252010.fantastic.ui.AmSongRowStyle
import io.github.kid141252010.fantastic.ui.CollectionDownloads
import io.github.kid141252010.fantastic.ui.amPageBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryAlbumScreen(
    app: FantasticApp,
    albumEntry: LibraryEntry,
    savedTracks: List<Track>,
    showLibrarySources: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    onOpenCatalog: (CatalogHit) -> Unit,
    onOpenArtist: (CatalogHit) -> Unit,
) {
    val album = albumEntry.hit
    val server = app.prefs.baseUrl
    val selectedStorefront by app.prefs.storefrontState.collectAsStateWithLifecycle()
    val storefront = if (album.source == Source.Apple) album.country.normalizedAppleCountry() ?: selectedStorefront.normalizedAppleCountry() else null
    val snapshot by app.library.snapshot.collectAsStateWithLifecycle()
    val revision = snapshot.albumMetadataRevision(album.source, album.id)
    val cachedMetadata = snapshot.albumMetadata.firstOrNull { it.matchesAlbum(album.source, album.id, storefront) }
    var retry by rememberSaveable(album.source, album.id, server, storefront, revision) { mutableStateOf(0) }
    var metadata by remember(album.source, album.id, server, storefront, revision) { mutableStateOf(cachedMetadata) }
    var loadingMetadata by remember(album.source, album.id, server, storefront, revision) { mutableStateOf(cachedMetadata == null) }
    var metadataError by remember(album.source, album.id, server, storefront, revision) { mutableStateOf<Exception?>(null) }
    var artistOpenCommit by remember(album.source, album.id, server, storefront, revision) { mutableStateOf<((CatalogHit) -> Unit)?>(null) }
    var artistOpenError by remember(album.source, album.id, server, storefront, revision) { mutableStateOf<String?>(null) }
    var resolvedArtists by remember(album.source, album.id, server, storefront, revision) { mutableStateOf<List<CatalogHit>?>(null) }
    val artistOpening = artistOpenCommit != null
    LaunchedEffect(album.source, album.id, server, storefront, revision, retry) {
        metadataError = null
        val restored = app.library.snapshot.first { !it.loading }
        val cached = restored.albumMetadata.firstOrNull { it.matchesAlbum(album.source, album.id, storefront) }
        if (cached != null) {
            metadata = cached
            loadingMetadata = false
            return@LaunchedEffect
        }
        app.library.updateSongMetadata(albumEntry.albumTracks.map(SearchCatalog::trackHit))
        loadingMetadata = true
        try {
            val loaded = app.libraryAlbumMetadata(album.source, album.id, storefront)
            metadata = loaded
            if (album.source == Source.Apple) {
                loaded.country?.let { app.library.updateAppleCountry("album", album.id, it) }
                val savedIds = savedTracks.filter { it.source == Source.Apple && it.albumId == album.id }.mapTo(HashSet()) { it.id }
                loaded.tracks.filter { it.id in savedIds && it.albumId == album.id }.forEach { track ->
                    track.country?.let { app.library.updateAppleCountry("song", track.id, it) }
                }
            }
            val patches = app.library.snapshot.value.items.asSequence()
                .filter { it.hit.kind == "song" && it.hit.source == album.source }
                .mapNotNull { it.hit.track }
                .filter { it.albumId == album.id && (it.trackNumber <= 0 || (it.source != Source.Netease && it.discNumber <= 0)) }
                .toList().map { app.librarySongHit(SearchCatalog.trackHit(it), loaded) }
            app.library.updateSongMetadata(patches)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            metadataError = error
        } finally {
            loadingMetadata = false
        }
    }
    val displayTracks = remember(savedTracks, metadata?.tracks, albumEntry.albumTracks) {
        orderLibraryAlbumTracks(savedTracks, metadata?.tracks ?: albumEntry.albumTracks)
    }
    val player by app.player.state.collectAsStateWithLifecycle()
    val downloads = rememberCollectionDownloads(app, displayTracks, selectedStorefront)
    val favoriteKeys = remember(snapshot.items) {
        snapshot.items.asSequence().filter { it.hit.kind == "song" && it.isFavorite }.map { it.key }.toSet()
    }
    val hasMultipleDiscs = remember(displayTracks) { displayTracks.any { it.discNumber > 1 } }
    var menuTrackId by remember(albumEntry.stableKey) { mutableStateOf<String?>(null) }
    val menuTrack = displayTracks.firstOrNull { it.id == menuTrackId }
    val latestBackEnabled by rememberUpdatedState(backEnabled)
    val latestAlbum by rememberUpdatedState(album)
    val latestMenuTrackId by rememberUpdatedState(menuTrackId)
    val latestMenuTrack by rememberUpdatedState(menuTrack)
    val latestAlbumKey by rememberUpdatedState(albumEntry.stableKey)
    val latestOnOpenCatalog by rememberUpdatedState(onOpenCatalog)
    LaunchedEffect(album.source, album.id, server, storefront, revision, artistOpenCommit, loadingMetadata, backEnabled, menuTrackId) {
        if (artistOpenCommit == null) return@LaunchedEffect
        if (!backEnabled || menuTrackId != null) {
            artistOpenCommit = null
            return@LaunchedEffect
        }
        if (loadingMetadata) return@LaunchedEffect
        try {
            val artists = resolvedArtists ?: metadata?.artists ?: app.libraryAlbumArtists(album.source, album.id, storefront)
            ensureActive()
            val currentAlbum = latestAlbum
            val currentCountry = if (currentAlbum.source == Source.Apple)
                currentAlbum.country.normalizedAppleCountry() ?: app.prefs.storefront.normalizedAppleCountry() else null
            if (!latestBackEnabled || latestMenuTrackId != null || currentAlbum.source != album.source || currentAlbum.id != album.id ||
                app.prefs.baseUrl != server || currentCountry != storefront ||
                app.library.snapshot.value.albumMetadataRevision(album.source, album.id) != revision) {
                artistOpenCommit = null
                return@LaunchedEffect
            }
            resolvedArtists = artists
            val target = artists.firstOrNull()
            val commit = artistOpenCommit
            artistOpenCommit = null
            if (target == null) artistOpenError = "暂无可跳转的艺人信息" else commit?.invoke(target)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            artistOpenCommit = null
            artistOpenError = "无法读取艺人信息：${error.message.orEmpty()}"
        }
    }
    BackHandler(enabled = backEnabled && menuTrack == null, onBack = onBack)
    Column(Modifier.fillMaxSize().background(amPageBackground())) {
        TopAppBar(title = { Text("专辑") }, navigationIcon = { Md3BackButton(onBack) }, windowInsets = WindowInsets(0, 0, 0, 0),
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = amPageBackground()))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).background(amPageBackground()),
            contentPadding = PaddingValues(bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp),
        ) {
            item("header") {
                val artistName = album.subtitle.ifBlank { metadata?.albumArtist.orEmpty() }
                val subtitle = album.source.librarySubtitle(artistName, showLibrarySources)
                val artistStatus = if (artistOpening) "正在读取艺人信息…" else artistOpenError
                val allReady = downloads.tracks.isNotEmpty() && downloads.unavailableCount == 0 && downloads.readyCount == downloads.tracks.size
                AmCollectionHeader(
                    artworkUrl = album.artwork,
                    title = album.title,
                    subtitle = subtitle,
                    caption = "${savedTracks.size} 首已保存 · ${downloads.readyCount} 首已下载",
                    description = artistStatus.orEmpty(),
                    busy = artistOpening,
                    onSubtitle = if (artistName.isNotBlank() && backEnabled && menuTrackId == null) ({
                        artistOpenError = null
                        artistOpenCommit = onOpenArtist
                    }) else null,
                    onPlay = if (displayTracks.isNotEmpty()) ({ app.player.playQueue(displayTracks, 0) }) else null,
                    onShuffle = if (displayTracks.isNotEmpty()) ({ app.player.playQueue(displayTracks, 0, shuffle = true) }) else null,
                    onAction = if (downloads.tracks.isNotEmpty() && !allReady) ({ app.downloads.enqueue(downloads.tracks) }) else null,
                    actionLabel = "下载已保存曲目",
                    actionReady = allReady,
                )
            }
            if (snapshot.persistenceError) item("library_persistence_error") {
                Text("本地资料库保存失败，重新启动后可能需要再次读取专辑信息。", Modifier.padding(horizontal = Dimens.Page, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (savedTracks.isEmpty()) item("empty") { Md3EmptyState("没有已保存的曲目", "", icon = Icons.Rounded.Album) }
            itemsIndexed(displayTracks, key = { _, track -> track.mediaId }) { index, track ->
                val number = track.trackNumber.takeIf { it > 0 }?.let {
                    if (hasMultipleDiscs && track.discNumber > 0) "${track.discNumber}-$it" else it.toString()
                }
                AmSongRow(
                    title = track.title,
                    subtitle = track.source.librarySubtitle(track.artist, showLibrarySources),
                    style = AmSongRowStyle.AlbumNumbered,
                    artworkUrl = null,
                    trackNumber = number,
                    favorite = LibraryKey(track.source, "song", track.id) in favoriteKeys,
                    playing = player.mediaId == track.mediaId,
                    queueSwipeLabel = queueSwipeActionLabel(player.canEditQueue, player.canAddToQueueSection),
                    onQueueSwipe = { app.player.insertQueueItemsFromSwipe(listOf(track)) },
                    onClick = { app.player.playQueue(displayTracks, index) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadStatusMark(downloads.status(track))
                            AmSongMoreButton(onClick = { menuTrackId = track.id })
                        }
                    },
                )
            }
            when {
                loadingMetadata -> item("complete_album_loading") {
                    Text("正在读取完整专辑信息…", Modifier.padding(horizontal = Dimens.Page, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                metadataError != null -> item("complete_album_error") {
                    Column(Modifier.padding(horizontal = Dimens.Page, vertical = 12.dp)) {
                        Text("无法读取完整专辑信息：${metadataError?.message.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                }
                metadata?.trackCount == null -> item("complete_album_unknown") {
                    Column(Modifier.padding(horizontal = Dimens.Page, vertical = 12.dp)) {
                        Text("暂无法确认完整专辑曲目数", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                }
                album.id.isNotBlank() && album.id != "0" && metadata!!.trackCount!! > savedTracks.size -> item("show_complete_album") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page)) {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text("显示完整专辑", Modifier.fillMaxWidth().clickable { onOpenCatalog(album.copy(country = metadata?.country ?: album.country)) }.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal).withUserFontWeight())
                    }
                }
            }
        }
    }
    if (menuTrack != null) {
        val hit = SearchCatalog.trackHit(menuTrack)
        val dismiss = { menuTrackId = null }
        val menuAlbumKey = remember(menuTrack) { albumEntry.stableKey }
        GlassSheet(hit.title, onDismissRequest = dismiss, showHeader = false) {
            MediaActionContent(hit, app.library, listOf(menuTrack),
                onView = { dismiss(); onOpenCatalog(hit) },
                onOpenRelated = { related ->
                    if (menuTrackId == menuTrack.id && latestMenuTrack == menuTrack && latestAlbumKey == menuAlbumKey && latestBackEnabled) {
                        dismiss()
                        latestOnOpenCatalog(related)
                    }
                }, onDismiss = dismiss)
        }
    }
}
