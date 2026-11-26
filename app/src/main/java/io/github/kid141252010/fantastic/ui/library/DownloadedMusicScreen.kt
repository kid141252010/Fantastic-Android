package io.github.kid141252010.fantastic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.MediaActionContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadedMusicScreen(app: FantasticApp, backEnabled: Boolean, onBack: () -> Unit,
    onOpenCatalog: (CatalogHit) -> Unit) {
    val records by app.downloads.entries.collectAsStateWithLifecycle()
    val ready = remember(records) { records.filter { it.status == DownloadStatus.Ready } }
    val bottom = LocalBarsPadding.current.calculateBottomPadding()
    var menuFileId by remember { mutableStateOf<String?>(null) }
    val menuRecord = records.firstOrNull { it.fileId == menuFileId && it.status == DownloadStatus.Ready }
    val latestBackEnabled by rememberUpdatedState(backEnabled)
    val latestOnOpenCatalog by rememberUpdatedState(onOpenCatalog)
    LaunchedEffect(menuFileId, menuRecord) { if (menuRecord == null) menuFileId = null }
    fun handleBack() { if (menuFileId != null) menuFileId = null else onBack() }
    BackHandler(enabled = backEnabled, onBack = ::handleBack)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("已下载") },
                navigationIcon = { Md3BackButton(::handleBack) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()), contentPadding = PaddingValues(
            bottom = bottom + 16.dp,
        )) {
            if (records.isEmpty()) item {
                Md3EmptyState("没有下载的歌曲", "在歌曲、专辑或播放列表的更多菜单选择“下载”。")
            }
            items(records, key = { it.key }) { record ->
                val source = record.track.source.libraryName()
                val state = when (record.status) {
                    DownloadStatus.Queued -> "等待下载"
                    DownloadStatus.Downloading -> record.percent?.let { "正在下载 $it%" } ?: "正在下载"
                    DownloadStatus.Ready -> "已下载"
                    DownloadStatus.Failed -> record.error ?: "下载失败"
                    DownloadStatus.Cancelled -> "已取消"
                }
                Md3MediaRow(
                    title = record.track.title,
                    subtitle = listOf(source, record.track.artist, state).filter { it.isNotBlank() }.joinToString(" · "),
                    leading = { Md3MediaArtwork(record.track.artwork(120), Modifier.size(48.dp), shape = MaterialTheme.shapes.small) },
                    onClick = if (record.status == DownloadStatus.Ready) ({
                        val index = ready.indexOf(record)
                        if (index >= 0) app.player.playQueue(ready.map { it.track }, index)
                    }) else null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (record.status) {
                                DownloadStatus.Ready -> IconButton(onClick = { menuFileId = record.fileId }) {
                                    Icon(Icons.Rounded.MoreHoriz, "更多操作")
                                }
                                DownloadStatus.Queued, DownloadStatus.Downloading -> IconButton(onClick = { app.downloads.cancel(record.key) }) {
                                    Icon(Icons.Rounded.Close, "取消下载")
                                }
                                DownloadStatus.Failed, DownloadStatus.Cancelled -> {
                                    IconButton(onClick = { app.downloads.retry(record.key) }) { Icon(Icons.Rounded.Refresh, "重试下载") }
                                    IconButton(onClick = { app.downloads.remove(record.key) }) { Icon(Icons.Rounded.Delete, "移除记录") }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
    menuRecord?.let { record -> key(record.fileId) {
        val song = record.track
        val hit = SearchCatalog.trackHit(song).let { original ->
            if (song.source == Source.Apple && song.country.normalizedAppleCountry() == null)
                original.copy(country = record.storefront.normalizedAppleCountry()) else original
        }
        fun openFromRecord(target: CatalogHit) {
            val current = app.downloads.entries.value.firstOrNull { it.fileId == record.fileId && it.status == DownloadStatus.Ready }
            if (menuFileId == record.fileId && current?.track == song && latestBackEnabled) {
                menuFileId = null
                latestOnOpenCatalog(target)
            }
        }
        GlassSheet(hit.title, onDismissRequest = { menuFileId = null }, showHeader = false) {
            MediaActionContent(hit, app.library, listOf(song),
                onView = { openFromRecord(hit) }, onOpenRelated = ::openFromRecord,
                onDismiss = { menuFileId = null })
        }
    } }
}
