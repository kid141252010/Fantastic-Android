package io.github.kid141252010.fantastic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.OfflineDownload
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.playback.validSongId
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.appAccentColors
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight

/** A page-level projection of the existing manual-download records, not a download job. */
internal data class CollectionDownloads(
    val tracks: List<Track>,
    val records: Map<String, OfflineDownload>,
    val selectedStorefront: String?,
    val readyCount: Int,
    val activeCount: Int,
    val pendingCount: Int,
    val unavailableCount: Int,
) {
    fun status(track: Track): DownloadStatus? = matchingStatus(track, records, selectedStorefront)
}

private fun matchingStatus(
    track: Track,
    records: Map<String, OfflineDownload>,
    selectedStorefront: String?,
): DownloadStatus? {
    val record = records[track.mediaId] ?: return null
    if (track.source == Source.Apple && record.storefront !=
        (track.country.normalizedAppleCountry() ?: selectedStorefront)) return null
    return record.status
}

@Composable
internal fun rememberCollectionDownloads(
    app: FantasticApp,
    tracks: List<Track>,
    storefront: String = app.prefs.storefrontState.collectAsStateWithLifecycle().value,
): CollectionDownloads {
    val entries by app.downloads.entries.collectAsStateWithLifecycle()
    val selectedStorefront = storefront.normalizedAppleCountry()
    val records = remember(entries) { entries.associateBy(OfflineDownload::key) }
    val unique = remember(tracks) { tracks.distinctBy { it.mediaId } }
    val eligible = remember(unique, selectedStorefront) {
        unique.filter { track -> track.source.validSongId(track.id) &&
            (track.source != Source.Apple || track.country.normalizedAppleCountry() != null || selectedStorefront != null) }
    }
    val unavailable = unique.size - eligible.size
    return remember(eligible, records, selectedStorefront, unavailable) {
        var ready = 0
        var active = 0
        var pending = 0
        for (track in eligible) {
            when (matchingStatus(track, records, selectedStorefront)) {
                DownloadStatus.Ready -> ready++
                DownloadStatus.Queued, DownloadStatus.Downloading -> active++
                else -> pending++
            }
        }
        CollectionDownloads(eligible, records, selectedStorefront, ready, active, pending, unavailable)
    }
}

@Composable
internal fun CollectionHeader(
    title: String,
    artwork: @Composable (Modifier) -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    caption: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val phone = LocalConfiguration.current.screenWidthDp < 600
    Column(modifier.fillMaxWidth().padding(top = if (phone) 10.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val coverWidth = if (phone) (maxWidth - 144.dp).coerceAtLeast(0.dp) else minOf(maxWidth, 260.dp)
            artwork(Modifier.width(coverWidth).aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
        }
        Row(Modifier.padding(horizontal = Dimens.Page).padding(top = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            Text(title, Modifier.weight(1f, fill = false), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)
                    .withUserFontWeight(FontWeightRole.Title))
            titleTrailing?.invoke()
        }
        if (subtitle != null) Box(Modifier.padding(horizontal = Dimens.Page).padding(top = 8.dp)) { subtitle() }
        if (caption != null) Box(Modifier.padding(horizontal = Dimens.Page).padding(top = 8.dp)) { caption() }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) { actions() }
    }
}

@Composable
internal fun CollectionActions(
    downloads: CollectionDownloads,
    complete: Boolean,
    playEnabled: Boolean,
    downloadEnabled: Boolean,
    resolving: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    downloadLabel: String = "下载",
) {
    val colors = MaterialTheme.colorScheme
    val accent = appAccentColors()
    val allReady = downloads.tracks.isNotEmpty() && downloads.unavailableCount == 0 &&
        downloads.readyCount == downloads.tracks.size
    val canDownload = !resolving && downloadEnabled && (!complete || downloads.pendingCount > 0)
    val downloading = complete && !allReady && downloads.pendingCount == 0 && downloads.activeCount > 0
    val label = when {
        resolving -> "正在读取完整曲目列表"
        !downloadEnabled || !complete -> downloadLabel
        allReady -> "已下载"
        downloads.pendingCount > 0 -> downloadLabel
        downloading -> "正在下载"
        else -> downloadLabel
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val playWidth = (maxWidth - 128.dp).coerceIn(0.dp, 160.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                FilledTonalIconButton(onClick = onShuffle, enabled = playEnabled && !resolving,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.surfaceVariant, contentColor = colors.onSurface)) {
                    Icon(Icons.Rounded.Shuffle, "随机播放")
                }
                Button(onClick = onPlay, enabled = playEnabled && !resolving,
                    modifier = Modifier.width(playWidth).heightIn(min = 48.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface)) {
                    if (resolving) CircularProgressIndicator(Modifier.size(22.dp), color = accent.primary, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp))
                    Text("播放", Modifier.padding(start = 8.dp), fontSize = 18.sp)
                }
                FilledTonalIconButton(onClick = onDownload, enabled = canDownload,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = label },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.surfaceVariant, contentColor = colors.onSurface)) {
                    when {
                        resolving || (downloadEnabled && downloading) -> CircularProgressIndicator(
                            Modifier.size(22.dp), color = accent.primary, strokeWidth = 2.dp)
                        downloadEnabled && complete && allReady -> Icon(Icons.Rounded.Check, null)
                        else -> Icon(Icons.Rounded.Download, null)
                    }
                }
            }
        }
        if (downloads.unavailableCount > 0) Text("部分歌曲缺少有效 ID 或 Apple Music 地区，无法下载。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun DownloadStatusMark(status: DownloadStatus?, modifier: Modifier = Modifier) {
    when (status) {
        DownloadStatus.Ready -> Icon(Icons.Rounded.DownloadForOffline, "已下载", modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        DownloadStatus.Queued, DownloadStatus.Downloading -> CircularProgressIndicator(
            modifier.size(16.dp).semantics {
                contentDescription = if (status == DownloadStatus.Queued) "等待下载" else "正在下载"
            }, color = appAccentColors().primary, strokeWidth = 2.dp)
        else -> Unit
    }
}
