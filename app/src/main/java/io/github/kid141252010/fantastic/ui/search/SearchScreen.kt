package io.github.kid141252010.fantastic.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.ui.browse.AppleDiscoveryScreen
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleStorefront
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.LibraryKey
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.ui.library.searchLibrary
import io.github.kid141252010.fantastic.ui.library.librarySubtitle
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.OfflineDownload
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.ui.AmSongMoreButton
import io.github.kid141252010.fantastic.ui.AmSongRow
import io.github.kid141252010.fantastic.ui.AmSongRowStyle
import io.github.kid141252010.fantastic.ui.amSecondaryLabelColor
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader
import io.github.kid141252010.fantastic.ui.queueSwipeActionLabel
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.MediaActionContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val searchTabs = listOf(
    null to "综合", SearchCatalog.Kind.Artist to "艺人", SearchCatalog.Kind.Album to "专辑",
    SearchCatalog.Kind.Song to "歌曲", SearchCatalog.Kind.Playlist to "歌单", SearchCatalog.Kind.Video to "音乐视频",
)
private val sources = listOf(Source.Apple to "Apple Music", Source.QQ to "QQ 音乐", Source.Netease to "网易云")

private class SearchResults(
    initialItems: List<CatalogHit> = emptyList(),
    initialNext: SearchCatalog.Cursor? = null,
    initiallyLoaded: Boolean = false,
) {
    var items by mutableStateOf(initialItems)
    var groups by mutableStateOf<List<SearchCatalog.Group>>(emptyList())
    var failures by mutableStateOf<List<SearchCatalog.GroupFailure>>(emptyList())
    var next by mutableStateOf(initialNext)
    var suggestions by mutableStateOf<List<SearchCatalog.Suggestion>>(emptyList())
    var busy by mutableStateOf(false)
    var loaded by mutableStateOf(initiallyLoaded)
    var error by mutableStateOf<String?>(null)
    var pageRequest by mutableIntStateOf(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: FantasticApp, rootNavigationRequest: Int, onOpen: (CatalogHit) -> Unit,
    onOpenLibraryAlbum: (CatalogHit) -> Unit = onOpen,
    showLibrarySources: Boolean = app.prefs.showLibrarySources) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(Source.Apple) }
    var librarySelected by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf<SearchCatalog.Kind?>(null) }
    var editing by remember { mutableStateOf(false) }
    val term = query.trim()
    val selectedKind = kind
    val storefront by app.prefs.storefrontState.collectAsStateWithLifecycle()
    val regions by app.appleRegions.state.collectAsStateWithLifecycle()
    val librarySnapshot by app.library.snapshot.collectAsStateWithLifecycle()
    val requestStorefront = if (source == Source.Apple) storefront else ""
    val server = SourceStatus.normalizeBaseUrl(app.prefs.baseUrl)
    val regionEntry = regions.entries[server]
    val storefronts = regionEntry?.storefronts
    val wrapperRegions = regionEntry?.wrapperRegions
    val sortedStorefronts = remember(storefronts, wrapperRegions) {
        storefronts.orEmpty().sortedWith(compareByDescending<AppleStorefront> { it.id in wrapperRegions.orEmpty() }.thenBy { it.id })
    }
    var regionsOpen by remember { mutableStateOf(false) }
    val search = remember(app) { SearchCatalog(app.apple, app.netease, app.qq) }
    val overview = remember(term, source, librarySelected, app.prefs.baseUrl, requestStorefront) { SearchResults() }
    val category = remember(term, source, librarySelected, selectedKind, app.prefs.baseUrl, requestStorefront) {
        val seed = selectedKind?.let { selected -> overview.groups.firstOrNull { it.kind == selected } }
        SearchResults(seed?.items.orEmpty(), seed?.next, seed != null)
    }
    val result = if (selectedKind == null) overview else category
    val player by app.player.state.collectAsStateWithLifecycle()
    val downloadRecords by app.downloads.entries.collectAsStateWithLifecycle()
    val favoriteKeys = remember(librarySnapshot.items) {
        librarySnapshot.items.asSequence().filter { it.isFavorite }.mapTo(HashSet()) { it.key }
    }
    val downloadsByTrack = remember(downloadRecords) { downloadRecords.associateBy(OfflineDownload::key) }
    val focus = LocalFocusManager.current
    var handledRootNavigationRequest by rememberSaveable { mutableStateOf(rootNavigationRequest) }
    LaunchedEffect(rootNavigationRequest) {
        if (handledRootNavigationRequest != rootNavigationRequest) {
            kind = null
            editing = false
            focus.clearFocus()
            handledRootNavigationRequest = rootNavigationRequest
        }
    }

    LaunchedEffect(result, result.pageRequest, selectedKind, librarySelected, librarySnapshot.items, librarySnapshot.loading) {
        if (term.isEmpty()) return@LaunchedEffect
        if (!librarySelected && result.loaded && result.pageRequest == 0) return@LaunchedEffect
        if (!librarySelected && result.loaded && selectedKind != null && result.next == null) return@LaunchedEffect
        if (librarySelected && librarySnapshot.loading) return@LaunchedEffect
        if (result.pageRequest == 0) delay(if (librarySelected) 250 else 330)
        result.busy = true
        result.error = null
        try {
            if (librarySelected) {
                val groups = withContext(Dispatchers.Default) { searchLibrary(librarySnapshot, term) }.mapNotNull { group ->
                    val kind = when (group.kind) {
                        "artist" -> SearchCatalog.Kind.Artist
                        "album" -> SearchCatalog.Kind.Album
                        "song" -> SearchCatalog.Kind.Song
                        else -> null
                    } ?: return@mapNotNull null
                    SearchCatalog.Group(group.kind, group.title, kind, group.items)
                }
                result.groups = groups
                result.failures = emptyList()
                if (selectedKind != null) {
                    result.items = groups.firstOrNull { it.kind == selectedKind }?.items.orEmpty()
                    result.next = null
                }
                result.loaded = true
            } else if (selectedKind == null) {
                val grouped = search.searchAll(source, term, requestStorefront)
                result.groups = grouped.groups
                result.failures = grouped.failures
                result.loaded = true
            } else if (!result.loaded) {
                val page = search.search(source, selectedKind, term, requestStorefront)
                result.items = page.items.distinctBy { "${it.kind}:${it.id}" }
                result.next = page.next
                result.loaded = true
            } else {
                val cursor = result.next ?: return@LaunchedEffect
                val page = search.more(cursor)
                result.items = (result.items + page.items).distinctBy { "${it.kind}:${it.id}" }
                result.next = page.next
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.error = e.message ?: "请检查 Fantastic 服务器连接"
        } finally {
            result.busy = false
        }
    }

    LaunchedEffect(result, editing, librarySelected) {
        if (librarySelected || !editing || term.isEmpty()) return@LaunchedEffect
        delay(220)
        try {
            result.suggestions = search.suggestions(source, term, requestStorefront)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            result.suggestions = emptyList()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (!editing && term.isEmpty()) {
            Text(
                "搜索",
                Modifier.padding(horizontal = Dimens.Page, vertical = 12.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 8.dp),
            shape = SearchBarDefaults.inputFieldShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { focus.clearFocus() },
                expanded = editing,
                onExpandedChange = { if (!it) focus.clearFocus() },
                modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused }
                    .semantics { contentDescription = "搜索关键词" },
                placeholder = { Text("艺人、歌曲、专辑及更多内容", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    if (editing || query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; kind = null; focus.clearFocus() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回搜索")
                        }
                    } else Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; kind = null }) {
                                Icon(Icons.Rounded.Close, "清除搜索")
                            }
                        }
                        if (source == Source.Apple && !librarySelected) {
                            Box {
                                TextButton(
                                    onClick = { focus.clearFocus(); regionsOpen = true },
                                    modifier = Modifier.semantics { contentDescription = "Apple Music 搜索地区：${storefront.uppercase()}" },
                                ) {
                                    Text(storefront.uppercase())
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = regionsOpen, onDismissRequest = { regionsOpen = false }) {
                                    if (sortedStorefronts.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(if (regions.loading || regions.refreshingServer == server) "正在读取地区…" else "地区列表不可用，请到设置刷新") },
                                            onClick = {}, enabled = false,
                                        )
                                    } else {
                                        if (wrapperRegions == null) {
                                            DropdownMenuItem(text = { Text("wrapper 支持状态未知") }, onClick = {}, enabled = false)
                                        }
                                        if (storefronts?.none { it.id == storefront } == true) {
                                            DropdownMenuItem(text = { Text("当前地区不在地区列表中") }, onClick = {}, enabled = false)
                                        }
                                        sortedStorefronts.forEach { region ->
                                            DropdownMenuItem(
                                                text = { Text("${region.id.uppercase()} · ${region.name}" + if (region.id in wrapperRegions.orEmpty()) " · wrapper 支持" else "") },
                                                modifier = Modifier.semantics { selected = region.id == storefront },
                                                trailingIcon = if (region.id == storefront) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                                                onClick = {
                                                    regionsOpen = false
                                                    if (region.id != app.prefs.storefront) app.prefs.storefront = region.id
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Page, vertical = 4.dp)) {
            sources.forEachIndexed { index, (choice, title) ->
                SegmentedButton(
                    selected = !librarySelected && source == choice,
                    onClick = {
                        if (choice == Source.Netease && kind == SearchCatalog.Kind.Video) kind = null
                        regionsOpen = false
                        librarySelected = false
                        source = choice
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, sources.size + 1),
                    icon = {},
                ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            SegmentedButton(
                selected = librarySelected,
                onClick = {
                    if (kind in setOf(SearchCatalog.Kind.Playlist, SearchCatalog.Kind.Video)) kind = null
                    regionsOpen = false
                    librarySelected = true
                },
                shape = SegmentedButtonDefaults.itemShape(sources.size, sources.size + 1),
                icon = {},
            ) { Text("资料库", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (term.isNotEmpty()) {
            val tabs = remember(source, librarySelected) {
                searchTabs.filter { (librarySelected || source != Source.Netease || it.first != SearchCatalog.Kind.Video) &&
                    (!librarySelected || it.first in setOf(null, SearchCatalog.Kind.Artist, SearchCatalog.Kind.Album, SearchCatalog.Kind.Song)) }
            }
            val selected = tabs.indexOfFirst { it.first == kind }.coerceAtLeast(0)
            SecondaryScrollableTabRow(selectedTabIndex = selected, edgePadding = Dimens.Page) {
                tabs.forEach { (filter, label) ->
                    Tab(
                        selected = filter == kind,
                        onClick = { kind = filter; focus.clearFocus() },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
        if (term.isEmpty() && !editing && source == Source.Apple && !librarySelected) {
            Box(Modifier.weight(1f)) {
                key(storefront) {
                    AppleDiscoveryScreen(app, radio = false, rootNavigationRequest = rootNavigationRequest, onOpen = onOpen, searchLanding = true)
                }
            }
            return@Column
        }
        key(result) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = LocalBarsPadding.current) {
                if (librarySelected && librarySnapshot.loading) item("library-loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (librarySelected && term.isEmpty()) item("library-prompt") {
                    Md3EmptyState("搜索资料库", "输入关键词查找已保存的歌曲、艺人和专辑。", icon = Icons.Rounded.Search)
                }
                if (!librarySelected && editing && result.suggestions.isNotEmpty()) {
                    item("suggestions") { Md3MediaSectionHeader("搜索建议") }
                    items(result.suggestions, key = { "suggestion:${it.hit?.source}:${it.hit?.kind}:${it.hit?.id}:${it.query}" }) { suggestion ->
                        val hit = suggestion.hit
                        if (hit?.track != null) {
                            SearchHitRow(app, hit, player, requestStorefront, focus, onOpen, favoriteKeys, downloadsByTrack,
                                librarySelected = librarySelected, onOpenLibraryAlbum = onOpenLibraryAlbum,
                            showLibrarySources = showLibrarySources)
                        } else {
                            Md3MediaRow(
                                title = suggestion.title,
                                subtitle = hit?.let {
                                    listOf(searchKindLabel(it.kind), it.subtitle).filter(String::isNotBlank).distinct().joinToString(" · ")
                                }.orEmpty(),
                                leading = {
                                    if (hit == null) Icon(Icons.Rounded.Search, contentDescription = null)
                                    else Md3MediaArtwork(hit.artwork, Modifier.size(48.dp),
                                        shape = if (hit.kind == "artist") CircleShape else MaterialTheme.shapes.small)
                                },
                                trailing = if (hit == null) null else ({ Icon(Icons.Rounded.ChevronRight, contentDescription = null) }),
                                onClick = {
                                    if (source == Source.Apple && requestStorefront != app.prefs.storefront) return@Md3MediaRow
                                    focus.clearFocus()
                                    if (hit != null) onOpen(hit) else suggestion.query?.let { query = it }
                                },
                            )
                        }
                    }
                }
                if (kind == null) {
                    result.groups.forEach { group ->
                        val previewLimit = when (group.kind) {
                            SearchCatalog.Kind.Song -> 9
                            SearchCatalog.Kind.Video -> 4
                            else -> 6
                        }
                        item(key = "group-title:${group.key}") {
                            Md3MediaSectionHeader(group.title) {
                                if (group.kind != null && (group.items.size > previewLimit || group.next != null)) {
                                    TextButton(onClick = { kind = group.kind; focus.clearFocus() }) { Text("查看全部") }
                                }
                            }
                        }
                        items(group.items.take(previewLimit), key = { "${group.key}:${it.source}:${it.kind}:${it.id}" }) { hit ->
                            SearchHitRow(app, hit, player, requestStorefront, focus, onOpen, favoriteKeys, downloadsByTrack,
                                librarySelected = librarySelected, onOpenLibraryAlbum = onOpenLibraryAlbum,
                            showLibrarySources = showLibrarySources)
                        }
                    }
                    items(result.failures, key = { "failure:${it.kind.id}" }) { failure ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page), verticalAlignment = Alignment.CenterVertically) {
                            Text("${failure.kind.title}搜索失败：${failure.message}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(enabled = !result.busy, onClick = { result.pageRequest++ }) { Text("重试") }
                        }
                    }
                } else {
                    items(result.items, key = { "${it.source}:${it.kind}:${it.id}" }) { hit ->
                        SearchHitRow(app, hit, player, requestStorefront, focus, onOpen, favoriteKeys, downloadsByTrack,
                            librarySelected = librarySelected, onOpenLibraryAlbum = onOpenLibraryAlbum,
                            showLibrarySources = showLibrarySources)
                    }
                }
                if (librarySelected && librarySnapshot.persistenceError && result.loaded && result.groups.isEmpty()) item("library-error") {
                    Md3EmptyState("资料库暂不可用", "本地资料库读取遇到问题。", icon = Icons.Rounded.ErrorOutline)
                }
                val noResults = if (kind == null) result.groups.isEmpty() && result.failures.isEmpty() else result.items.isEmpty()
                val error = result.error
                if (result.busy) item("loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (error != null) item("error") {
                    Md3EmptyState("搜索失败", error, icon = Icons.Rounded.ErrorOutline) {
                        FilledTonalButton(onClick = { result.pageRequest++ }) { Text("重试") }
                    }
                } else if (result.loaded && noResults && !(librarySelected && librarySnapshot.persistenceError)) item("empty") {
                    Md3EmptyState("没有搜索结果", "试试其他关键词或音乐来源。", icon = Icons.Rounded.Search)
                } else if (kind != null && result.next != null) item("more") {
                    FilledTonalButton(onClick = { result.pageRequest++ }, modifier = Modifier.padding(Dimens.Page)) {
                        Text("加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    app: FantasticApp,
    hit: CatalogHit,
    player: PlayerState,
    storefront: String,
    focus: FocusManager,
    onOpen: (CatalogHit) -> Unit,
    favoriteKeys: Set<LibraryKey>,
    downloadsByTrack: Map<String, OfflineDownload>,
    librarySelected: Boolean = false,
    onOpenLibraryAlbum: (CatalogHit) -> Unit = onOpen,
    showLibrarySources: Boolean = false,
) {
    val track = hit.track
    var actionsOpen by remember(hit.source, hit.kind, hit.id) { mutableStateOf(false) }
    val latestHit by rememberUpdatedState(hit)
    val latestStorefront by rememberUpdatedState(storefront)
    val queueLabel = if (track != null) queueSwipeActionLabel(player.canEditQueue, player.canAddToQueueSection) else null
    val latestOnOpen by rememberUpdatedState(onOpen)
    val subtitle = if (track != null) {
        if (librarySelected) track.source.librarySubtitle(track.artist, showLibrarySources) else track.artist
    } else {
        val type = searchKindLabel(hit.kind)
        val details = if (librarySelected) hit.source.librarySubtitle(hit.subtitle, showLibrarySources) else hit.subtitle
        listOf(type, details).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
    if (track != null) {
        val libraryKey = LibraryKey(hit.source, "song", hit.id)
        val country = if (track.source == Source.Apple)
            track.country.normalizedAppleCountry() ?: storefront.normalizedAppleCountry() else null
        val download = downloadsByTrack[track.mediaId]?.takeIf { country == null || it.storefront == country }
        val downloadLabel = when (download?.status) {
            DownloadStatus.Queued -> "等待下载"
            DownloadStatus.Downloading -> download?.percent?.let { "正在下载 $it%" } ?: "正在下载"
            DownloadStatus.Ready -> "已下载"
            DownloadStatus.Failed -> "下载失败"
            DownloadStatus.Cancelled -> "已取消"
            null -> null
        }
        AmSongRow(
            title = hit.title,
            subtitle = subtitle,
            style = AmSongRowStyle.Search,
            artworkUrl = hit.artwork ?: track.artwork(120),
            favorite = libraryKey in favoriteKeys,
            playing = player.mediaId == track.mediaId,
            onClick = {
                if (!librarySelected && hit.source == Source.Apple && storefront != app.prefs.storefront) return@AmSongRow
                focus.clearFocus()
                app.player.play(track)
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    downloadLabel?.let { Text(it, color = amSecondaryLabelColor(), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    AmSongMoreButton(onClick = {
                        if (!librarySelected && hit.source == Source.Apple && storefront != app.prefs.storefront) return@AmSongMoreButton
                        focus.clearFocus(); actionsOpen = true
                    }, enabled = librarySelected || hit.source != Source.Apple || storefront == app.prefs.storefront)
                }
            },
            queueSwipeLabel = queueLabel,
            onQueueSwipe = {
                val latest = latestHit
                if (librarySelected || latest.source != Source.Apple || latestStorefront == app.prefs.storefront) {
                    latest.track?.let { app.player.insertQueueItemsFromSwipe(listOf(it)) }
                }
            },
        )
    } else {
        Md3MediaRow(
            title = hit.title,
            subtitle = subtitle,
            onClick = {
                if (!librarySelected && hit.source == Source.Apple && storefront != app.prefs.storefront) return@Md3MediaRow
                focus.clearFocus()
                if (librarySelected && hit.kind == "album") onOpenLibraryAlbum(hit) else onOpen(hit)
            },
            leading = {
                Md3MediaArtwork(hit.artwork, Modifier.size(48.dp),
                    shape = if (hit.kind == "artist") CircleShape else MaterialTheme.shapes.small)
            },
            trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        )
    }
    if (track != null && actionsOpen && (librarySelected || hit.source != Source.Apple || storefront == app.prefs.storefront)) {
        GlassSheet(hit.title, onDismissRequest = { actionsOpen = false }, showHeader = false) {
            MediaActionContent(hit, app.library, listOf(track),
                onPlay = {
                    actionsOpen = false
                    if (hit.source != Source.Apple || librarySelected || storefront == app.prefs.storefront) app.player.play(track)
                },
                onView = {
                    actionsOpen = false
                    if (hit.source != Source.Apple || librarySelected || storefront == app.prefs.storefront) onOpen(hit)
                },
                onOpenRelated = { related ->
                    if (actionsOpen && latestHit == hit && hit.kind == "song" &&
                        (librarySelected || hit.source != Source.Apple || storefront == app.prefs.storefront)) {
                        actionsOpen = false
                        latestOnOpen(related)
                    }
                },
                onDismiss = { actionsOpen = false })
        }
    }
}

private fun searchKindLabel(kind: String): String = when (kind.lowercase()) {
    "song", "songs" -> "歌曲"
    "album", "albums" -> "专辑"
    "artist", "artists" -> "艺人"
    "playlist", "playlists" -> "歌单"
    "music-video", "music-videos", "mv" -> "音乐视频"
    else -> "详情"
}


