package io.github.kid141252010.fantastic.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryEntry
import io.github.kid141252010.fantastic.data.LibraryFolder
import io.github.kid141252010.fantastic.data.LibraryPlaylist
import io.github.kid141252010.fantastic.data.LibraryPlaylistTrack
import io.github.kid141252010.fantastic.data.MusicLibrarySnapshot
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.MediaActionContent
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.Collator

/** Offline catalog library, favorites, local playlists and nested playlist folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(app: FantasticApp, showLibrarySources: Boolean, rootNavigationRequest: Int, backEnabled: Boolean,
    onOpen: (CatalogHit) -> Unit, onOpenAlbumArtist: (CatalogHit, Int) -> Unit) {
    val snapshot by app.library.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf<LibraryCategory?>(null) }
    var favoritesOnly by rememberSaveable(category) { mutableStateOf(false) }
    var newestFirst by rememberSaveable(category) {
        mutableStateOf(when (category) {
            LibraryCategory.Songs -> app.prefs.librarySongsNewestFirst
            LibraryCategory.Albums -> app.prefs.libraryAlbumsNewestFirst
            LibraryCategory.Artists -> app.prefs.libraryArtistsNewestFirst
            else -> false
        })
    }
    var favoriteSongs by rememberSaveable { mutableStateOf(false) }
    val pageState = rememberSaveableStateHolder()
    var folderId by rememberSaveable { mutableStateOf("") }
    var playlistId by rememberSaveable { mutableStateOf("") }
    var libraryAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    fun openLibraryHit(hit: CatalogHit) {
        if (hit.kind == "album") libraryAlbum = "${hit.source.name}:${hit.kind}:${hit.id}"
        else onOpen(hit)
    }
    var dialog by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var deletingFolder by remember { mutableStateOf(false) }
    var deletingPlaylist by remember { mutableStateOf(false) }
    var menuEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var handledRootNavigationRequest by rememberSaveable { mutableStateOf(rootNavigationRequest) }
    val latestBackEnabled by rememberUpdatedState(backEnabled)
    val latestRootRequest by rememberUpdatedState(rootNavigationRequest)
    val latestOnOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(rootNavigationRequest) {
        if (handledRootNavigationRequest != rootNavigationRequest) {
            category = null
            libraryAlbum = null
            favoriteSongs = false
            folderId = ""
            playlistId = ""
            overflowOpen = false
            dialog = ""
            nameInput = ""
            importText = null
            deletingFolder = false
            deletingPlaylist = false
            menuEntry = null
            message = ""
            handledRootNavigationRequest = rootNavigationRequest
        }
    }
    val currentCategory = category
    if (currentCategory == LibraryCategory.Downloads) {
        DownloadedMusicScreen(app, backEnabled = backEnabled, onBack = { category = null }, onOpenCatalog = onOpen)
        return
    }
    val albums = remember(snapshot.items) { libraryAlbums(snapshot.items) }
    val albumEntry = libraryAlbum?.let { key -> albums.firstOrNull { it.stableKey == key } }
    if (albumEntry != null) {
        val savedTracks = remember(albumEntry, snapshot.items) { libraryAlbumTracks(albumEntry, snapshot.items) }
        val artistAlbumKey = albumEntry.stableKey
        val artistRootRequest = rootNavigationRequest
        pageState.SaveableStateProvider(albumEntry.stableKey) {
            LibraryAlbumScreen(app, albumEntry, savedTracks, showLibrarySources, backEnabled = backEnabled,
                onBack = { libraryAlbum = null }, onOpenCatalog = onOpen,
                onOpenArtist = { hit ->
                    if (libraryAlbum == artistAlbumKey) onOpenAlbumArtist(hit, artistRootRequest)
                })
        }
        return
    }
    LaunchedEffect(libraryAlbum, snapshot.loading) {
        if (!snapshot.loading) libraryAlbum = null
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = app.library.exportJson()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Cannot open the selected file")
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            message = if (result.isSuccess) "资料库已导出" else "导出失败：${result.exceptionOrNull()?.message.orEmpty()}"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input -> readBounded(input, MAX_IMPORT_BYTES).toString(Charsets.UTF_8) }
                        ?: error("Cannot open the selected file")
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) {
                importText = result.getOrThrow()
                message = ""
            } else message = "读取失败：${result.exceptionOrNull()?.message.orEmpty()}"
        }
    }

    val currentFolder = snapshot.folders.firstOrNull { it.id == folderId }
    val selectedPlaylist = snapshot.playlists.firstOrNull { it.id == playlistId }
    val sheetOpen = overflowOpen || dialog.isNotEmpty() || importText != null || menuEntry != null ||
        (deletingFolder && currentFolder != null) || (deletingPlaylist && selectedPlaylist != null)
    LaunchedEffect(snapshot.loading, folderId, playlistId, snapshot.folders, snapshot.playlists) {
        if (!snapshot.loading && folderId.isNotEmpty() && snapshot.folders.none { it.id == folderId }) folderId = ""
        if (!snapshot.loading && playlistId.isNotEmpty() && snapshot.playlists.none { it.id == playlistId }) playlistId = ""
    }
    fun goBack() {
        when {
            selectedPlaylist != null -> playlistId = ""
            favoriteSongs -> favoriteSongs = false
            currentFolder != null -> folderId = currentFolder.parentId
            else -> category = null
        }
    }
    val isHome = currentCategory == null && selectedPlaylist == null
    BackHandler(enabled = backEnabled && !sheetOpen && !isHome, onBack = ::goBack)

    fun applyImport(replace: Boolean) {
        val text = importText ?: return
        importText = null
        scope.launch {
            message = try {
                val report = withContext(Dispatchers.IO) { app.library.importJson(text, replace) }
                "已提交：${report.items} 个项目、${report.playlists} 个歌单、${report.folders} 个文件夹；跳过 ${report.skipped} 项。"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                "导入失败：${error.message.orEmpty()}"
            }
        }
    }
    fun dismissSheet() {
        when {
            overflowOpen -> overflowOpen = false
            importText != null -> importText = null
            deletingFolder -> deletingFolder = false
            deletingPlaylist -> deletingPlaylist = false
            dialog.isNotEmpty() -> { dialog = ""; nameInput = "" }
            else -> menuEntry = null
        }
    }
    BackHandler(enabled = backEnabled && sheetOpen, onBack = ::dismissSheet)


    val title = selectedPlaylist?.name ?: currentFolder?.name ?: when {
        favoriteSongs -> "收藏的歌曲"
        currentCategory != null -> currentCategory.title
        else -> "资料库"
    }
    val playlistBrowser = currentCategory == LibraryCategory.Playlists && selectedPlaylist == null && !favoriteSongs
    val showSort = currentCategory != null && selectedPlaylist == null && !favoriteSongs
    val routeKey = when {
        selectedPlaylist != null -> "playlist:${selectedPlaylist.id}"
        currentFolder != null -> "folder:${currentFolder.id}"
        favoriteSongs -> "favorite-songs"
        else -> currentCategory?.name ?: "home"
    }
    val notice: (@Composable () -> Unit)? = if (snapshot.persistenceError || message.isNotBlank()) {
        {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.persistenceError) LibraryNotice("本地资料库读取或保存遇到问题。", isError = true)
                if (message.isNotBlank()) LibraryNotice(message)
            }
        }
    } else null
    val locale = LocalConfiguration.current.locales[0]
    val collator = remember(locale) { Collator.getInstance(locale) }
    val visibleItems = remember(snapshot.items, albums, currentCategory, folderId, favoriteSongs, favoritesOnly, newestFirst, collator) {
        val entries = if (currentCategory == LibraryCategory.Albums) albums else snapshot.items
        val kind = if (favoriteSongs) "song" else currentCategory?.kind
        val filtered = entries.filter { entry ->
            entry.hit.kind == kind &&
                (!favoriteSongs && !favoritesOnly || entry.isFavorite) &&
                (kind != "playlist" || entry.folderId == folderId)
        }
        if (newestFirst || favoriteSongs) filtered.sortedByDescending { if (favoriteSongs) it.favoriteAt else it.addedAt }
        else filtered.sortedWith { left, right -> collator.compare(left.hit.title, right.hit.title) }
    }

    val actions: @Composable RowScope.() -> Unit = {
        if (showSort) LibrarySortMenu(favoritesOnly, newestFirst,
            onFavorites = { favoritesOnly = !favoritesOnly }, onSort = {
                newestFirst = it
                when (currentCategory) {
                    LibraryCategory.Songs -> app.prefs.librarySongsNewestFirst = it
                    LibraryCategory.Albums -> app.prefs.libraryAlbumsNewestFirst = it
                    LibraryCategory.Artists -> app.prefs.libraryArtistsNewestFirst = it
                    else -> Unit
                }
            })
        if (isHome || playlistBrowser) {
            IconButton(onClick = { dialog = "playlist" }, enabled = !snapshot.loading) {
                Icon(Icons.Rounded.Add, contentDescription = "新建歌单")
            }
        }
        if (selectedPlaylist != null) {
            val favorite = selectedPlaylist.favoriteAt > 0
            IconButton(onClick = { app.library.togglePlaylistFavorite(selectedPlaylist.id) }) {
                Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "取消收藏歌单" else "收藏歌单")
            }
        }
        if (isHome || playlistBrowser || selectedPlaylist != null) Box {
            IconButton(onClick = { overflowOpen = true }, enabled = !snapshot.loading) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                if (isHome) {
                    DropdownMenuItem(
                        text = { Text("导入资料库") },
                        leadingIcon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        onClick = { overflowOpen = false; importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    )
                    DropdownMenuItem(
                        text = { Text("导出资料库") },
                        leadingIcon = { Icon(Icons.Rounded.ImportExport, contentDescription = null) },
                        onClick = { overflowOpen = false; exportLauncher.launch("fantastic-library.json") },
                    )
                }
                if (isHome || playlistBrowser) DropdownMenuItem(
                    text = { Text(if (currentFolder == null) "新建文件夹" else "新建子文件夹") },
                    leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    onClick = { overflowOpen = false; dialog = "folder" },
                )
                if (selectedPlaylist != null || currentFolder != null) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            overflowOpen = false
                            dialog = if (selectedPlaylist != null) "rename-playlist" else "rename-folder"
                            nameInput = selectedPlaylist?.name ?: currentFolder?.name.orEmpty()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("移动到文件夹") },
                        onClick = {
                            overflowOpen = false
                            dialog = if (selectedPlaylist != null) "move-playlist" else "move-folder"
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (selectedPlaylist != null) "删除歌单" else "删除文件夹") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            if (selectedPlaylist != null) deletingPlaylist = true else deletingFolder = true
                        },
                    )
                }
            }
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!isHome) TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { Md3BackButton(::goBack) }, actions = actions, windowInsets = WindowInsets(0, 0, 0, 0))
        },
    ) { innerPadding ->
        pageState.SaveableStateProvider(routeKey) {
            val body = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())
            when {
                isHome -> LibraryHome(
                    snapshot, albums, body, onCategory = { category = it }, onCatalog = ::openLibraryHit,
                    onCatalogOptions = { menuEntry = it },
                    onPlaylist = { playlistId = it.id }, notice = notice, actions = actions,
                    showLibrarySources = showLibrarySources,
                )
                currentCategory == LibraryCategory.Albums -> LibraryAlbumsGrid(
                    visibleItems, favoritesOnly, snapshot.loading, body, ::openLibraryHit,
                    onCatalogOptions = { menuEntry = it }, notice = notice,
                    showLibrarySources = showLibrarySources,
                )
                else -> {
                    val player by app.player.state.collectAsStateWithLifecycle()
                    LazyColumn(
                        modifier = body,
                        contentPadding = PaddingValues(bottom = LocalBarsPadding.current.calculateBottomPadding() + 16.dp),
                    ) {
                        if (notice != null) item("notice") {
                            Box(Modifier.padding(horizontal = Dimens.Page, vertical = 8.dp)) { notice() }
                        }
                        if (snapshot.loading) item("loading") { LibraryLoading() }
                        else if (selectedPlaylist != null) {
                            item("playlist-header") { LibraryPlaylistHeader(app, selectedPlaylist) }
                            if (selectedPlaylist.tracks.isEmpty()) item("empty") {
                                Md3EmptyState("歌单为空", "在歌曲的更多菜单中选择“添加到歌单”。")
                            } else item("tracks-heading") { Md3MediaSectionHeader("曲目") }
                            itemsIndexed(selectedPlaylist.tracks, key = { _, track -> track.uid }) { index, track ->
                                PlaylistTrackRow(
                                    track = track,
                                    playing = track.hit.kind == "song" && player.mediaId == "${track.hit.source.mediaPrefix}:${track.hit.id}",
                                    canMoveUp = index > 0,
                                    canMoveDown = index < selectedPlaylist.tracks.lastIndex,
                                    onPlay = { playPlaylistFrom(app, selectedPlaylist, track.uid) },
                                    onOpen = { onOpen(track.hit) },
                                    onUp = { app.library.movePlaylistTrack(selectedPlaylist.id, index, index - 1) },
                                    onDown = { app.library.movePlaylistTrack(selectedPlaylist.id, index, index + 1) },
                                    onRemove = { app.library.removeFromPlaylist(selectedPlaylist.id, track.uid) },
                                )
                            }
                        } else {
                            val playlists = if (playlistBrowser) snapshot.playlists.filter {
                                it.folderId == folderId && (!favoritesOnly || it.favoriteAt > 0)
                            }.let { lists ->
                                if (newestFirst) lists.sortedByDescending { it.createdAt }
                                else lists.sortedWith { left, right -> collator.compare(left.name, right.name) }
                            } else emptyList()
                            val folders = if (playlistBrowser) snapshot.folders.filter { it.parentId == folderId }
                                .sortedWith { left, right -> collator.compare(left.name, right.name) } else emptyList()
                            val showFavoritePlaylist = playlistBrowser && folderId.isEmpty() && snapshot.items.any {
                                it.hit.kind == "song" && it.isFavorite
                            }
                            if (showFavoritePlaylist) item("favorite-songs") {
                                Md3MediaRow(
                                    "收藏的歌曲",
                                    onClick = { favoriteSongs = true },
                                    leading = { Icon(Icons.Rounded.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                )
                            }
                            items(folders, key = { "folder:${it.id}" }) { folder ->
                                FolderRow(folder) { folderId = folder.id }
                            }
                            items(playlists, key = { "local:${it.id}" }) { playlist ->
                                PlaylistRow(playlist) { playlistId = playlist.id }
                            }
                            if (!showFavoritePlaylist && playlists.isEmpty() && folders.isEmpty() && visibleItems.isEmpty()) item("empty") {
                                if (favoriteSongs) Md3EmptyState("未添加歌曲", "喜爱的歌曲将在这里显示。", icon = LibraryCategory.Songs.icon)
                                else LibraryEmptyCategory(currentCategory, favoritesOnly, onCreate = { dialog = "playlist" })
                            }
                            items(visibleItems, key = { it.stableKey }) { entry ->
                                LibraryItemRow(
                                    entry = entry,
                                    showLibrarySources = showLibrarySources,
                                    playing = entry.hit.kind == "song" && player.mediaId == "${entry.hit.source.mediaPrefix}:${entry.hit.id}",
                                    onClick = {
                                        if (entry.hit.kind == "song") playLibraryHit(app, entry, visibleItems)
                                        else openLibraryHit(entry.hit)
                                    },
                                    onOptions = { menuEntry = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when {
        importText != null -> AlertDialog(
            onDismissRequest = ::dismissSheet,
            title = { Text("导入资料库") },
            text = { Text("选择合并现有资料库，或替换当前资料库。替换会删除本机现有项目。") },
            dismissButton = { TextButton(onClick = ::dismissSheet) { Text("取消") } },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { applyImport(replace = false) }) { Text("合并") }
                    TextButton(
                        onClick = { applyImport(replace = true) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("替换") }
                }
            },
        )
        deletingFolder && currentFolder != null -> AlertDialog(
            onDismissRequest = ::dismissSheet,
            title = { Text("删除文件夹？") },
            text = { Text("将同时移除这个文件夹下的子文件夹、本地歌单和已保存的目录歌单，仅影响本机资料库；不会删除线上歌单或独立保存的歌曲。") },
            dismissButton = { TextButton(onClick = ::dismissSheet) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    app.library.deleteFolder(currentFolder.id)
                    folderId = currentFolder.parentId
                    deletingFolder = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            },
        )
        deletingPlaylist && selectedPlaylist != null -> AlertDialog(
            onDismissRequest = ::dismissSheet,
            title = { Text("删除歌单？") },
            text = { Text("歌单中的歌曲快照也会一并移除。") },
            dismissButton = { TextButton(onClick = ::dismissSheet) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    app.library.deletePlaylist(selectedPlaylist.id)
                    playlistId = ""
                    deletingPlaylist = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            },
        )
        dialog == "playlist" || dialog == "folder" || dialog == "rename-playlist" || dialog == "rename-folder" -> {
            val renaming = dialog.startsWith("rename-")
            val makingPlaylist = dialog == "playlist"
            AlertDialog(
                onDismissRequest = ::dismissSheet,
                title = { Text(if (renaming) "重命名" else if (makingPlaylist) "新建本地歌单" else "新建文件夹") },
                text = {
                    OutlinedTextField(value = nameInput, onValueChange = { nameInput = it.take(200) },
                        label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                dismissButton = { TextButton(onClick = ::dismissSheet) { Text("取消") } },
                confirmButton = {
                    TextButton(enabled = nameInput.isNotBlank(), onClick = {
                        when (dialog) {
                            "playlist" -> app.library.createPlaylist(nameInput, folderId)
                            "folder" -> app.library.createFolder(nameInput, folderId)
                            "rename-playlist" -> selectedPlaylist?.let { app.library.renamePlaylist(it.id, nameInput) }
                            "rename-folder" -> currentFolder?.let { app.library.renameFolder(it.id, nameInput) }
                        }
                        nameInput = ""
                        dialog = ""
                    }) { Text(if (renaming) "保存" else "创建") }
                },
            )
        }
        dialog == "move-playlist" || dialog == "move-folder" -> {
            val movingFolder = dialog == "move-folder"
            val foldersById = remember(snapshot.folders) { snapshot.folders.associateBy { it.id } }
            val destinations = snapshot.folders.filter { !movingFolder || canMoveFolder(foldersById, folderId, it.id) }
                .sortedBy { it.name.lowercase() }
            fun moveTo(destination: String) {
                if (movingFolder) currentFolder?.let { app.library.moveFolder(it.id, destination) }
                else selectedPlaylist?.let { app.library.movePlaylist(it.id, destination) }
                dialog = ""
            }
            AlertDialog(
                onDismissRequest = ::dismissSheet,
                title = { Text("移动到文件夹") },
                text = {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item(key = "root") { TextButton(onClick = { moveTo("") }) { Text("资料库顶层") } }
                        items(destinations, key = { it.id }) { folder ->
                            TextButton(onClick = { moveTo(folder.id) }) { Text(folderLabel(foldersById, folder)) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = ::dismissSheet) { Text("取消") } },
            )
        }
        menuEntry != null -> {
            val entry = menuEntry!!
            val menuRootRequest = remember(entry) { rootNavigationRequest }
            val songs = when (entry.hit.kind) {
                "song" -> listOf(asTrack(entry.hit))
                "album" -> remember(entry, snapshot.items) { libraryAlbumTracks(entry, snapshot.items) }
                else -> emptyList()
            }
            GlassSheet(entry.hit.title, onDismissRequest = ::dismissSheet, showHeader = false) {
                MediaActionContent(entry.hit, app.library, songs,
                    onPlay = if (songs.isNotEmpty()) ({ app.player.playQueue(songs, 0); dismissSheet() }) else null,
                    onView = { dismissSheet(); openLibraryHit(entry.hit) },
                    onOpenRelated = if (entry.hit.kind == "song") ({ related ->
                        if (menuEntry?.stableKey == entry.stableKey && latestBackEnabled && latestRootRequest == menuRootRequest) {
                            dismissSheet()
                            latestOnOpen(related)
                        }
                    }) else null,
                    onDismiss = ::dismissSheet)
            }
        }
    }
}

@Composable
private fun LibraryNotice(message: String, isError: Boolean = false) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LibraryLoading() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text("正在读取资料库…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LibrarySortMenu(favoritesOnly: Boolean, newestFirst: Boolean, onFavorites: () -> Unit, onSort: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "排序与筛选") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("按标题排序") },
                modifier = Modifier.semantics { selected = !newestFirst },
                trailingIcon = if (!newestFirst) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                onClick = { expanded = false; onSort(false) },
            )
            DropdownMenuItem(
                text = { Text("按最近添加排序") },
                modifier = Modifier.semantics { selected = newestFirst },
                trailingIcon = if (newestFirst) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                onClick = { expanded = false; onSort(true) },
            )
            DropdownMenuItem(
                text = { Text("仅显示收藏") },
                modifier = Modifier.semantics { selected = favoritesOnly },
                trailingIcon = if (favoritesOnly) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                onClick = { expanded = false; onFavorites() },
            )
        }
    }
}

@Composable
private fun LibraryEmptyCategory(category: LibraryCategory?, favoritesOnly: Boolean, onCreate: () -> Unit) {
    val label = category?.title ?: "项目"
    when {
        favoritesOnly -> Md3EmptyState("没有收藏的$label", "在更多菜单中可收藏$label。")
        category == LibraryCategory.Playlists -> Md3EmptyState("还没有歌单", "创建歌单来整理喜欢的歌曲。", icon = category.icon) {
            FilledTonalButton(onClick = onCreate) { Text("新建歌单") }
        }
        else -> Md3EmptyState("还没有$label", "在搜索中打开${label}，再从更多菜单加入资料库。",
            icon = category?.icon ?: Icons.Rounded.LibraryMusic)
    }
}

@Composable
private fun LibraryItemRow(entry: LibraryEntry, playing: Boolean, showLibrarySources: Boolean, onClick: () -> Unit, onOptions: () -> Unit) {
    val hit = entry.hit
    Md3MediaRow(
        title = hit.title,
        subtitle = hit.source.librarySubtitle(hit.subtitle, showSource = hit.kind != "song" || showLibrarySources),
        playing = playing,
        onClick = onClick,
        leading = {
            Md3MediaArtwork(hit.artwork ?: hit.track?.artwork(120), Modifier.size(48.dp),
                shape = if (hit.kind == "artist") CircleShape else MaterialTheme.shapes.small)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isFavorite) Icon(Icons.Rounded.Favorite, contentDescription = "已收藏", tint = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onOptions) { Icon(Icons.Rounded.MoreVert, "${hit.title}更多操作") }
            }
        },
    )
}

@Composable
private fun PlaylistRow(playlist: LibraryPlaylist, onClick: () -> Unit) {
    Md3MediaRow(
        title = playlist.name,
        subtitle = playlist.caption(),
        onClick = onClick,
        leading = {
            Md3MediaArtwork(playlist.coverUrl(), Modifier.size(48.dp), shape = MaterialTheme.shapes.small,
                icon = Icons.AutoMirrored.Rounded.QueueMusic)
        },
        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
    )
}

@Composable
private fun FolderRow(folder: LibraryFolder, onClick: () -> Unit) {
    Md3MediaRow(
        title = folder.name,
        onClick = onClick,
        leading = { Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
    )
}

@Composable
private fun LibraryPlaylistHeader(app: FantasticApp, playlist: LibraryPlaylist) {
    val playable = remember(playlist.tracks) { playlist.tracks.any { it.hit.kind == "song" } }
    val cover = remember(playlist.tracks) { playlist.coverUrl() }
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 12.dp), Alignment.Center) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Md3MediaArtwork(cover, Modifier.widthIn(max = 224.dp).fillMaxWidth(.7f).aspectRatio(1f),
                    shape = MaterialTheme.shapes.large, icon = Icons.AutoMirrored.Rounded.QueueMusic)
                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(playlist.caption(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playlist.description.isNotBlank()) Text(playlist.description, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { playPlaylist(app, playlist, shuffle = false) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text("播放", Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = { playPlaylist(app, playlist, shuffle = true) }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Text("随机播放", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: LibraryPlaylistTrack,
    playing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Md3MediaRow(
        title = track.hit.title,
        subtitle = track.hit.source.librarySubtitle(track.hit.subtitle, showSource = true),
        playing = playing,
        onClick = if (track.hit.kind == "song") onPlay else onOpen,
        leading = { Md3MediaArtwork(track.hit.artwork ?: track.hit.track?.artwork(120), Modifier.size(48.dp), shape = MaterialTheme.shapes.small) },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "${track.hit.title}更多操作") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("查看详情") }, onClick = { menuOpen = false; onOpen() })
                    DropdownMenuItem(text = { Text("上移") }, enabled = canMoveUp, onClick = { menuOpen = false; onUp() })
                    DropdownMenuItem(text = { Text("下移") }, enabled = canMoveDown, onClick = { menuOpen = false; onDown() })
                    DropdownMenuItem(text = { Text("从歌单移除") }, onClick = { menuOpen = false; onRemove() })
                }
            }
        },
    )
}

private fun playLibraryHit(app: FantasticApp, entry: LibraryEntry, visibleItems: List<LibraryEntry>) {
    val songs = visibleItems.filter { it.hit.kind == "song" }
    val index = songs.indexOf(entry)
    if (index >= 0) app.player.playQueue(songs.map { asTrack(it.hit) }, index)
}

private fun playPlaylist(app: FantasticApp, playlist: LibraryPlaylist, shuffle: Boolean) {
    val songs = playlist.tracks.filter { it.hit.kind == "song" }
    if (songs.isNotEmpty()) app.player.playQueue(songs.map { asTrack(it.hit) }, 0, shuffle = shuffle)
}

private fun playPlaylistFrom(app: FantasticApp, playlist: LibraryPlaylist, uid: String) {
    val songs = playlist.tracks.filter { it.hit.kind == "song" }
    val selected = songs.indexOfFirst { it.uid == uid }
    if (selected >= 0) app.player.playQueue(songs.map { asTrack(it.hit) }, selected)
}

private fun asTrack(hit: CatalogHit): Track = hit.track?.takeIf { it.source == hit.source && it.id == hit.id }
    ?: Track(hit.id, hit.source, hit.title, hit.subtitle, "", hit.artwork, 0, country = hit.country)

internal fun Source.librarySubtitle(subtitle: String, showSource: Boolean): String = when {
    !showSource -> subtitle
    subtitle.isBlank() -> libraryName()
    else -> "$subtitle · ${libraryName()}"
}

internal fun Source.libraryName(): String = when (this) {
    Source.Apple -> "Apple Music"
    Source.Netease -> "网易云音乐"
    Source.QQ -> "QQ音乐"
}

private fun canMoveFolder(folders: Map<String, LibraryFolder>, id: String, target: String): Boolean {
    val seen = mutableSetOf<String>()
    var folder = folders[target]
    while (folder != null && seen.add(folder.id)) {
        if (folder.id == id) return false
        folder = folders[folder.parentId]
    }
    return true
}

private fun folderLabel(folders: Map<String, LibraryFolder>, folder: LibraryFolder): String {
    val path = mutableListOf(folder.name)
    val seen = mutableSetOf(folder.id)
    var ancestor = folders[folder.parentId]
    while (ancestor != null && seen.add(ancestor.id)) {
        path.add(ancestor.name)
        ancestor = folders[ancestor.parentId]
    }
    return path.asReversed().joinToString(" / ")
}

private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "资料库文件过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
