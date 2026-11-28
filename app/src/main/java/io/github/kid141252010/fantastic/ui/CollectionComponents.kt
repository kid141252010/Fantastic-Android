package io.github.kid141252010.fantastic.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.OfflineDownload
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.playback.validSongId
import io.github.kid141252010.fantastic.ui.theme.appAccentColors

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
