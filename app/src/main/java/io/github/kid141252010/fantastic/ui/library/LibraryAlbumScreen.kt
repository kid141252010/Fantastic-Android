package io.github.kid141252010.fantastic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.OfflineDownload
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.MediaActionContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryAlbumScreen(
    app: FantasticApp,
    albumEntry: LibraryEntry,
    savedTracks: List<Track>,
    onBack: () -> Unit,
    onOpenSong: (CatalogHit) -> Unit,
) {
    val album = albumEntry.hit
    val player by app.player.state.collectAsStateWithLifecycle()
    val downloads by app.downloads.entries.collectAsStateWithLifecycle()
    val downloadsByTrack = remember(downloads) { downloads.associateBy(OfflineDownload::key) }
    val readyCount = savedTracks.count { downloadsByTrack[it.mediaId]?.status == DownloadStatus.Ready }
    val hasMultipleDiscs = remember(savedTracks) { savedTracks.any { it.discNumber > 1 } }
    var menuTrackId by remember { mutableStateOf<String?>(null) }
    val menuTrack = savedTracks.firstOrNull { it.id == menuTrackId }
    BackHandler(enabled = menuTrack == null, onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("专辑") }, navigationIcon = { Md3BackButton(onBack) }, windowInsets = WindowInsets(0, 0, 0, 0))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp),
        ) {
            item("header") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Md3MediaArtwork(album.artwork, Modifier.widthIn(max = 260.dp).fillMaxWidth(.72f).aspectRatio(1f),
                        shape = MaterialTheme.shapes.large, icon = Icons.Rounded.Album)
                    Text(album.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    if (album.subtitle.isNotBlank()) Text(album.subtitle, textAlign = TextAlign.Center)
                    Text("${savedTracks.size} 首已保存 · $readyCount 首已下载", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (savedTracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { app.player.playQueue(savedTracks, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("播放", Modifier.padding(start = 8.dp))
                        }
                        FilledTonalIconButton(onClick = { app.downloads.enqueue(savedTracks) }, enabled = readyCount < savedTracks.size) {
                            Icon(Icons.Rounded.Download, "下载已保存曲目")
                        }
                    }
                }
            }
            if (savedTracks.isEmpty()) item("empty") { Md3EmptyState("没有已保存的曲目", "", icon = Icons.Rounded.Album) }
            itemsIndexed(savedTracks, key = { _, track -> track.mediaId }) { index, track ->
                val download = downloadsByTrack[track.mediaId]
                val number = track.trackNumber.takeIf { it > 0 }?.let {
                    if (hasMultipleDiscs && track.discNumber > 0) "${track.discNumber}-$it" else it.toString()
                }
                Md3MediaRow(
                    title = track.title,
                    subtitle = track.artist,
                    playing = player.mediaId == track.mediaId,
                    onClick = { app.player.playQueue(savedTracks, index) },
                    leading = {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            if (number != null) Text(number, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (download?.status) {
                                DownloadStatus.Ready -> Icon(Icons.Rounded.CheckCircle, "已下载", Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                DownloadStatus.Queued, DownloadStatus.Downloading -> CircularProgressIndicator(
                                    Modifier.size(16.dp).semantics {
                                        contentDescription = if (download.status == DownloadStatus.Queued) "等待下载" else "正在下载"
                                    }, strokeWidth = 2.dp,
                                )
                                else -> Unit
                            }
                            IconButton(onClick = { menuTrackId = track.id }) { Icon(Icons.Rounded.MoreHoriz, "${track.title}更多操作") }
                        }
                    },
                )
            }
        }
    }
    if (menuTrack != null) {
        val hit = SearchCatalog.trackHit(menuTrack)
        val dismiss = { menuTrackId = null }
        GlassSheet(hit.title, onDismissRequest = dismiss, showHeader = false) {
            MediaActionContent(hit, app.library, listOf(menuTrack),
                onView = { dismiss(); onOpenSong(hit) }, onDismiss = dismiss)
        }
    }
}
