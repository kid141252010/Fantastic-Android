package io.github.kid141252010.fantastic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.LibraryPlaylist
import io.github.kid141252010.fantastic.data.LibraryPlaylistTrack
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.validCatalogId
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.compatibleLibraryCountries
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.OfflineDownload
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.ui.AmCollectionHeader
import io.github.kid141252010.fantastic.ui.AmSongMoreButton
import io.github.kid141252010.fantastic.ui.AmSongRow
import io.github.kid141252010.fantastic.ui.AmSongRowStyle
import io.github.kid141252010.fantastic.ui.DownloadStatusMark
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.MediaActionContent
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.amPageBackground
import io.github.kid141252010.fantastic.ui.queueSwipeActionLabel
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadedMusicScreen(
    app: FantasticApp,
    showLibrarySources: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    onOpenCatalog: (CatalogHit) -> Unit,
) {
    val records by app.downloads.entries.collectAsStateWithLifecycle()
    val ready = remember(records) { records.filter { it.status == DownloadStatus.Ready } }
    val tasks = remember(records) { records.filter { it.status != DownloadStatus.Ready } }
    val snapshot by app.library.snapshot.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    val favoriteMediaIds = remember(snapshot.items) {
        snapshot.items.asSequence().filter { it.hit.kind == "song" && it.isFavorite }
            .map { trackFromLibraryHit(it.hit).mediaId }.toSet()
    }
    val albums = remember(ready) {
        val groups = LinkedHashMap<DownloadedGroupKey, MutableList<OfflineDownload>>()
        ready.forEach { record ->
            val track = record.track
            val id = track.albumId.takeIf { it.isNotBlank() && it != "0" && validCatalogId(track.source, "album", it) }
                ?: return@forEach
            val country = if (track.source == Source.Apple) record.storefront.normalizedAppleCountry() ?: return@forEach else null
            if (track.source == Source.Apple && !compatibleLibraryCountries(track.country, country)) return@forEach
            groups.getOrPut(DownloadedGroupKey(track.source, id, country)) { mutableListOf() }.add(record)
        }
        groups.map { (key, members) ->
            val first = members.first().track
            DownloadedAlbum(key.source, key.id, key.country, first.album.ifBlank { key.id }, first.albumArtist.ifBlank { first.artist },
                first.artwork(500), members.toList())
        }
    }
    val artists = remember(ready) {
        val groups = LinkedHashMap<DownloadedGroupKey, MutableList<OfflineDownload>>()
        ready.forEach { record ->
            val track = record.track
            val id = track.artistId.takeIf { it.isNotBlank() && it != "0" && validCatalogId(track.source, "artist", it) }
                ?: return@forEach
            val country = if (track.source == Source.Apple) record.storefront.normalizedAppleCountry() ?: return@forEach else null
            if (track.source == Source.Apple && !compatibleLibraryCountries(track.country, country)) return@forEach
            groups.getOrPut(DownloadedGroupKey(track.source, id, country)) { mutableListOf() }.add(record)
        }
        groups.map { (key, members) ->
            val first = members.first().track
            DownloadedArtist(key.source, key.id, key.country, first.artist.ifBlank { key.id }, first.artistArtwork(500), members.toList())
        }
    }
    val readyByMediaId = remember(ready) { ready.associateBy { it.track.mediaId } }
    val playlists = remember(snapshot.playlists, readyByMediaId) {
        snapshot.playlists.mapNotNull { playlist ->
            val members = playlist.tracks.mapNotNull { member ->
                if (member.hit.kind != "song") return@mapNotNull null
                val track = trackFromLibraryHit(member.hit)
                val record = readyByMediaId[track.mediaId] ?: return@mapNotNull null
                if (track.source == Source.Apple && !compatibleLibraryCountries(track.country, record.storefront)) return@mapNotNull null
                DownloadedPlaylistMember(member.uid, record)
            }
            if (members.isEmpty()) null else {
                val allReady = playlist.tracks.asSequence().filter { it.hit.kind == "song" }.all { member ->
                    val track = trackFromLibraryHit(member.hit)
                    val record = readyByMediaId[track.mediaId] ?: return@all false
                    track.source != Source.Apple || compatibleLibraryCountries(track.country, record.storefront)
                }
                DownloadedPlaylist(playlist, members, allReady)
            }
        }
    }
    var page by rememberSaveable { mutableStateOf(DownloadPage.Home.name) }
    var selectedSource by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedCountry by rememberSaveable { mutableStateOf("") }
    var selectedPlaylistId by rememberSaveable { mutableStateOf("") }
    val stateHolder = rememberSaveableStateHolder()
    var menuFileId by rememberSaveable { mutableStateOf<String?>(null) }
    val menuRecord = records.firstOrNull { it.fileId == menuFileId && it.status == DownloadStatus.Ready }
    val latestBackEnabled by rememberUpdatedState(backEnabled)
    val latestOnOpenCatalog by rememberUpdatedState(onOpenCatalog)
    LaunchedEffect(menuFileId, menuRecord) { if (menuRecord == null) menuFileId = null }
    val selectedAlbum = albums.firstOrNull {
        it.source.name == selectedSource && it.id == selectedId && it.country.orEmpty() == selectedCountry
    }
    val selectedArtist = artists.firstOrNull {
        it.source.name == selectedSource && it.id == selectedId && it.country.orEmpty() == selectedCountry
    }
    val selectedPlaylist = playlists.firstOrNull { it.playlist.id == selectedPlaylistId }
    LaunchedEffect(page, selectedAlbum, selectedArtist, selectedPlaylist) {
        when {
            page == DownloadPage.Album.name && selectedAlbum == null -> page = DownloadPage.Albums.name
            page == DownloadPage.Artist.name && selectedArtist == null -> page = DownloadPage.Artists.name
            page == DownloadPage.Playlist.name && selectedPlaylist == null -> page = DownloadPage.Playlists.name
        }
    }
    val hasActiveTasks = tasks.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
    val taskTitle = if (hasActiveTasks) "正在下载" else "下载任务"
    fun handleBack() {
        if (menuFileId != null) {
            menuFileId = null
            return
        }
        page = when (page) {
            DownloadPage.Home.name -> { onBack(); return }
            DownloadPage.Songs.name, DownloadPage.Albums.name, DownloadPage.Artists.name,
            DownloadPage.Playlists.name, DownloadPage.Tasks.name -> DownloadPage.Home.name
            DownloadPage.Album.name -> DownloadPage.Albums.name
            DownloadPage.Artist.name -> DownloadPage.Artists.name
            DownloadPage.Playlist.name -> DownloadPage.Playlists.name
            else -> DownloadPage.Home.name
        }
    }
    BackHandler(enabled = backEnabled, onBack = ::handleBack)
    val albumCategory = remember(albums) { albums }
    val artistCategory = remember(artists) { artists }
    val playlistCategory = remember(playlists) { playlists }
    val categories = remember(albumCategory, artistCategory, playlistCategory) {
        buildList {
            if (playlistCategory.isNotEmpty()) add(LibraryCategory.Playlists)
            if (artistCategory.isNotEmpty()) add(LibraryCategory.Artists)
            if (albumCategory.isNotEmpty()) add(LibraryCategory.Albums)
            add(LibraryCategory.Songs)
        }
    }
    val pageTitle = when (page) {
        DownloadPage.Home.name -> "已下载"
        DownloadPage.Songs.name -> "歌曲"
        DownloadPage.Albums.name -> "专辑"
        DownloadPage.Artists.name -> "艺人"
        DownloadPage.Playlists.name -> "播放列表"
        DownloadPage.Tasks.name -> taskTitle
        DownloadPage.Album.name -> selectedAlbum?.title ?: "专辑"
        DownloadPage.Artist.name -> selectedArtist?.title ?: "艺人"
        DownloadPage.Playlist.name -> selectedPlaylist?.playlist?.name ?: "播放列表"
        else -> "已下载"
    }
    val routeKey = when (page) {
        DownloadPage.Album.name -> "download-album:$selectedSource:$selectedId:$selectedCountry"
        DownloadPage.Artist.name -> "download-artist:$selectedSource:$selectedId:$selectedCountry"
        DownloadPage.Playlist.name -> "download-playlist:$selectedPlaylistId"
        else -> "download:$page"
    }
    val horizontalPadding = if (LocalConfiguration.current.screenWidthDp < 360) 16.dp else io.github.kid141252010.fantastic.ui.Dimens.Page
    Scaffold(
        containerColor = amPageBackground(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = { Text(pageTitle, maxLines = 1) }, navigationIcon = { Md3BackButton(::handleBack) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = amPageBackground()))
        },
    ) { inner ->
        stateHolder.SaveableStateProvider(routeKey) {
            val body = Modifier.fillMaxSize().background(amPageBackground()).padding(top = inner.calculateTopPadding())
            when (page) {
                DownloadPage.Home.name -> LibraryCoverGrid(body, topPadding = 8.dp, horizontalPadding = horizontalPadding) {
                    item(key = "download-categories", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            categories.forEach { category ->
                                LibraryCategoryRow(category) {
                                    page = when (category) {
                                        LibraryCategory.Songs -> DownloadPage.Songs.name
                                        LibraryCategory.Albums -> DownloadPage.Albums.name
                                        LibraryCategory.Artists -> DownloadPage.Artists.name
                                        LibraryCategory.Playlists -> DownloadPage.Playlists.name
                                        else -> DownloadPage.Songs.name
                                    }
                                }
                            }
                            if (tasks.isNotEmpty()) LibraryCategoryRow(taskTitle, LibraryCategory.Downloads.icon) {
                                page = DownloadPage.Tasks.name
                            }
                        }
                    }
                    if (snapshot.persistenceError) item(key = "library-persistence-error", span = { GridItemSpan(maxLineSpan) }) {
                        Text("本地资料库读取或保存遇到问题。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (albums.isNotEmpty()) item(key = "downloaded-albums-title", span = { GridItemSpan(maxLineSpan) }) {
                        Text("已下载专辑", Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    }
                    items(albums, key = { it.stableKey }) { album ->
                        LibraryCoverCard(
                            title = album.title,
                            subtitle = album.source.librarySubtitle(album.artist, showLibrarySources),
                            artwork = album.artwork,
                            icon = LibraryCategory.Albums.icon,
                            onClick = {
                                selectedSource = album.source.name
                                selectedId = album.id
                                selectedCountry = album.country.orEmpty()
                                page = DownloadPage.Album.name
                            },
                        )
                    }
                }
                DownloadPage.Albums.name -> LibraryCoverGrid(body) {
                    if (albums.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Md3EmptyState("没有已下载的专辑", "已下载歌曲中的专辑会显示在这里。", icon = LibraryCategory.Albums.icon)
                    }
                    items(albums, key = { it.stableKey }) { album ->
                        LibraryCoverCard(album.title, album.source.librarySubtitle(album.artist, showLibrarySources),
                            album.artwork, LibraryCategory.Albums.icon, onClick = {
                                selectedSource = album.source.name
                                selectedId = album.id
                                selectedCountry = album.country.orEmpty()
                                page = DownloadPage.Album.name
                            })
                    }
                }
                DownloadPage.Artists.name -> LazyColumn(body, contentPadding = PaddingValues(
                    bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp,
                )) {
                    if (artists.isEmpty()) item(key = "empty") { Md3EmptyState("没有已下载的艺人", "已下载歌曲中的艺人会显示在这里。", icon = LibraryCategory.Artists.icon) }
                    items(artists, key = { it.stableKey }) { artist ->
                        Md3MediaRow(
                            title = artist.title,
                            subtitle = artist.source.librarySubtitle("${artist.records.size} 首已下载", showLibrarySources),
                            leading = { Md3MediaArtwork(artist.artwork, Modifier.size(48.dp), shape = androidx.compose.foundation.shape.CircleShape) },
                            onClick = {
                                selectedSource = artist.source.name
                                selectedId = artist.id
                                selectedCountry = artist.country.orEmpty()
                                page = DownloadPage.Artist.name
                            },
                        )
                    }
                }
                DownloadPage.Playlists.name -> LazyColumn(body, contentPadding = PaddingValues(
                    bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp,
                )) {
                    if (playlists.isEmpty()) item(key = "empty") { Md3EmptyState("没有含已下载歌曲的播放列表", "本地播放列表中的已下载歌曲会显示在这里。", icon = LibraryCategory.Playlists.icon) }
                    items(playlists, key = { it.playlist.id }) { downloadedPlaylist ->
                        Md3MediaRow(
                            title = downloadedPlaylist.playlist.name,
                            subtitle = "${downloadedPlaylist.members.size} 首已下载",
                            leading = { Md3MediaArtwork(downloadedPlaylist.playlist.coverUrl(), Modifier.size(48.dp), icon = LibraryCategory.Playlists.icon) },
                            onClick = {
                                selectedPlaylistId = downloadedPlaylist.playlist.id
                                page = DownloadPage.Playlist.name
                            },
                        )
                    }
                }
                DownloadPage.Tasks.name -> LazyColumn(body, contentPadding = PaddingValues(
                    bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp,
                )) {
                    if (tasks.isEmpty()) item(key = "empty") { Md3EmptyState("没有下载任务", "等待中的任务或失败、取消的任务会显示在这里。") }
                    items(tasks, key = { it.key }) { record ->
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
                            leading = { Md3MediaArtwork(record.track.artwork(120), Modifier.size(48.dp)) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when (record.status) {
                                        DownloadStatus.Queued, DownloadStatus.Downloading -> IconButton(onClick = { app.downloads.cancel(record.key) }) {
                                            Icon(Icons.Rounded.Close, "取消下载")
                                        }
                                        DownloadStatus.Failed, DownloadStatus.Cancelled -> {
                                            IconButton(onClick = { app.downloads.retry(record.key) }) { Icon(Icons.Rounded.Refresh, "重试下载") }
                                            IconButton(onClick = { app.downloads.remove(record.key) }) { Icon(Icons.Rounded.Delete, "移除记录") }
                                        }
                                        DownloadStatus.Ready -> Unit
                                    }
                                }
                            },
                        )
                    }
                }
                else -> {
                    val collectionRecords = when (page) {
                        DownloadPage.Album.name -> selectedAlbum?.records.orEmpty()
                        DownloadPage.Artist.name -> selectedArtist?.records.orEmpty()
                        DownloadPage.Playlist.name -> selectedPlaylist?.members?.map { it.record }.orEmpty()
                        else -> ready
                    }
                    val collectionTracks = remember(page, collectionRecords, ready) {
                        val records = if (page == DownloadPage.Album.name) selectedAlbum?.records.orEmpty()
                            .let { albumRecords ->
                                val sorted = orderLibraryAlbumTracks(albumRecords.map { it.track }, emptyList())
                                val byId = albumRecords.associateBy { it.track.mediaId }
                                sorted.mapNotNull { byId[it.mediaId] }
                            }
                        else if (page == DownloadPage.Songs.name) ready else collectionRecords
                        records.map { it.track }
                    }
                    val collectionEntries = remember(page, collectionRecords, selectedPlaylist, collectionTracks) {
                        when (page) {
                            DownloadPage.Playlist.name -> selectedPlaylist?.members?.map { it.uid to it.record }.orEmpty()
                            DownloadPage.Album.name -> {
                                val byId = collectionRecords.associateBy { it.track.mediaId }
                                collectionTracks.mapNotNull { track -> byId[track.mediaId]?.let { it.fileId to it } }
                            }
                            else -> collectionRecords.map { it.fileId to it }
                        }
                    }
                    val hasMultipleDiscs = remember(collectionTracks) { collectionTracks.any { it.discNumber > 1 } }
                    LazyColumn(body, contentPadding = PaddingValues(bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp)) {
                        when (page) {
                            DownloadPage.Songs.name -> if (ready.isEmpty()) item(key = "empty") {
                                Md3EmptyState("没有下载的歌曲", "在歌曲、专辑或播放列表的更多菜单选择“下载”。", icon = LibraryCategory.Songs.icon)
                            }
                            DownloadPage.Album.name -> item(key = "collection-header") {
                                val album = selectedAlbum
                                if (album != null) AmCollectionHeader(
                                    artworkUrl = album.artwork,
                                    title = album.title,
                                    subtitle = album.source.librarySubtitle(album.artist, showLibrarySources),
                                    caption = "${album.records.size} 首已下载",
                                    description = "",
                                    busy = false,
                                    onPlay = { if (collectionTracks.isNotEmpty()) app.player.playQueue(collectionTracks, 0) },
                                    onShuffle = { if (collectionTracks.isNotEmpty()) app.player.playQueue(collectionTracks, 0, shuffle = true) },
                                )
                            }
                            DownloadPage.Artist.name -> item(key = "collection-header") {
                                val artist = selectedArtist
                                if (artist != null) AmCollectionHeader(
                                    artworkUrl = artist.artwork,
                                    title = artist.title,
                                    subtitle = if (showLibrarySources) artist.source.libraryName() else "",
                                    caption = "${artist.records.size} 首已下载",
                                    description = "",
                                    busy = false,
                                    onPlay = { if (collectionTracks.isNotEmpty()) app.player.playQueue(collectionTracks, 0) },
                                    onShuffle = { if (collectionTracks.isNotEmpty()) app.player.playQueue(collectionTracks, 0, shuffle = true) },
                                )
                            }
                            DownloadPage.Playlist.name -> item(key = "collection-header") {
                                val downloadedPlaylist = selectedPlaylist
                                if (downloadedPlaylist != null) AmCollectionHeader(
                                    artworkUrl = downloadedPlaylist.playlist.coverUrl(),
                                    title = downloadedPlaylist.playlist.name,
                                    subtitle = "",
                                    caption = "${downloadedPlaylist.members.size} 首已下载",
                                    description = downloadedPlaylist.playlist.description,
                                    busy = false,
                                    onPlay = { if (collectionTracks.isNotEmpty()) app.player.playQueue(collectionTracks, 0) },
                                    onShuffle = { if (collectionTracks.isNotEmpty()) app.player.playQueue(collectionTracks, 0, shuffle = true) },
                                    actionReady = downloadedPlaylist.allReady,
                                )
                            }
                        }
                        if (collectionTracks.isEmpty() && page != DownloadPage.Songs.name) item(key = "empty") {
                            Md3EmptyState("没有已下载的歌曲", "此集合中目前没有可用的下载歌曲。")
                        }
                        itemsIndexed(collectionEntries, key = { _, entry -> entry.first }) { index, entry ->
                            val record = entry.second
                            val number = record.track.trackNumber.takeIf { it > 0 }?.let {
                                if (hasMultipleDiscs && record.track.discNumber > 0) "${record.track.discNumber}-$it" else it.toString()
                            }
                            DownloadedTrackRow(
                                record = record,
                                style = when (page) {
                                    DownloadPage.Album.name -> AmSongRowStyle.AlbumNumbered
                                    DownloadPage.Songs.name -> AmSongRowStyle.Library
                                    else -> AmSongRowStyle.Collection
                                },
                                showLibrarySources = showLibrarySources,
                                favorite = record.track.mediaId in favoriteMediaIds,
                                playing = player.mediaId == record.track.mediaId,
                                queueSwipeLabel = queueSwipeActionLabel(player.canEditQueue, player.canAddToQueueSection),
                                onQueueSwipe = { app.player.insertQueueItemsFromSwipe(listOf(record.track)) },
                                onClick = { app.player.playQueue(collectionTracks, index) },
                                onMore = { menuFileId = record.fileId },
                                trackNumber = number,
                            )
                        }
                    }
                }
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

@Composable
private fun DownloadedTrackRow(
    record: OfflineDownload,
    style: AmSongRowStyle,
    showLibrarySources: Boolean,
    favorite: Boolean,
    playing: Boolean,
    queueSwipeLabel: String?,
    onQueueSwipe: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit,
    trackNumber: String?,
) {
    val track = record.track
    AmSongRow(
        title = track.title,
        subtitle = track.source.librarySubtitle(track.artist, showLibrarySources),
        style = style,
        artworkUrl = if (style == AmSongRowStyle.AlbumNumbered) null else track.artwork(120),
        trackNumber = trackNumber,
        favorite = favorite,
        playing = playing,
        onClick = onClick,
        queueSwipeLabel = queueSwipeLabel,
        onQueueSwipe = onQueueSwipe,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadStatusMark(record.status)
                AmSongMoreButton(onClick = onMore)
            }
        },
    )
}

private enum class DownloadPage { Home, Songs, Albums, Artists, Playlists, Album, Artist, Playlist, Tasks }

private data class DownloadedGroupKey(val source: Source, val id: String, val country: String?)

private data class DownloadedAlbum(
    val source: Source,
    val id: String,
    val country: String?,
    val title: String,
    val artist: String,
    val artwork: String?,
    val records: List<OfflineDownload>,
) {
    val stableKey: String get() = "${source.name}:$id:${country.orEmpty()}"
}

private data class DownloadedArtist(
    val source: Source,
    val id: String,
    val country: String?,
    val title: String,
    val artwork: String?,
    val records: List<OfflineDownload>,
) {
    val stableKey: String get() = "${source.name}:$id:${country.orEmpty()}"
}

private data class DownloadedPlaylistMember(val uid: String, val record: OfflineDownload)

private data class DownloadedPlaylist(
    val playlist: LibraryPlaylist,
    val members: List<DownloadedPlaylistMember>,
    val allReady: Boolean,
)

private fun trackFromLibraryHit(hit: CatalogHit): Track {
    val track = hit.track?.takeIf { it.source == hit.source && it.id == hit.id }
    return if (track != null) track.copy(country = track.country ?: hit.country)
    else Track(hit.id, hit.source, hit.title, hit.subtitle, "", hit.artwork, 0, country = hit.country)
}
