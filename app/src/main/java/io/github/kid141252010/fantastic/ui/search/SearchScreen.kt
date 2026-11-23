package io.github.kid141252010.fantastic.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.kid141252010.fantastic.ui.browse.AppleDiscoveryScreen
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleStorefrontState
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryKey
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader
import io.github.kid141252010.fantastic.ui.MediaActionContent
import io.github.kid141252010.fantastic.ui.SheetAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

private val searchTabs = listOf(
    null to "综合", SearchCatalog.Kind.Artist to "艺人", SearchCatalog.Kind.Album to "专辑",
    SearchCatalog.Kind.Song to "歌曲", SearchCatalog.Kind.Playlist to "歌单", SearchCatalog.Kind.Video to "音乐视频",
)
private val sources = listOf(Source.Apple to "Apple Music", Source.QQ to "QQ 音乐", Source.Netease to "网易云")
private val sourceNames = sources.toMap()
private val idKinds = listOf(SearchCatalog.Kind.Artist, SearchCatalog.Kind.Album, SearchCatalog.Kind.Song)

private val CatalogHit.libraryKey get() = LibraryKey(source, kind, id)

private class SearchResults(
    initialItems: List<CatalogHit> = emptyList(),
    initialNext: SearchCatalog.Cursor? = null,
    initiallyLoaded: Boolean = false,
) {
    var items by mutableStateOf(initialItems)
    var groups by mutableStateOf<List<SearchCatalog.Group>>(emptyList())
    var failures by mutableStateOf<List<SearchCatalog.GroupFailure>>(emptyList())
    var next by mutableStateOf(initialNext)
    var busy by mutableStateOf(false)
    var loaded by mutableStateOf(initiallyLoaded)
    var error by mutableStateOf<String?>(null)
    var pageRequest by mutableIntStateOf(0)
    var handledRequest = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: FantasticApp, visible: Boolean, onOpen: (CatalogHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(Source.Apple) }
    var kind by rememberSaveable { mutableStateOf<SearchCatalog.Kind?>(null) }
    var editing by remember { mutableStateOf(false) }
    var choosingStorefront by remember { mutableStateOf(false) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    val storefronts = app.appleStorefronts
    val catalogSettings by storefronts.state.collectAsStateWithLifecycle()
    val storefront = catalogSettings.selected
    val baseUrl = catalogSettings.baseUrl
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val term = query.trim()
    val idQuery = SearchCatalog.isIdQuery(term)
    val supportsVideo = idQuery || source != Source.Netease
    val selectedKind = kind.takeUnless { it == SearchCatalog.Kind.Video && !supportsVideo }
    val search = remember(app) { SearchCatalog(app.apple, app.netease, app.qq) }
    val overviews = remember(term, baseUrl, storefront) { Source.entries.associateWith { SearchResults() } }
    val results = remember(overviews, selectedKind) {
        if (selectedKind == null) overviews else Source.entries.associateWith { provider ->
            val seed = overviews.getValue(provider).groups.firstOrNull { it.kind == selectedKind }
            SearchResults(seed?.items.orEmpty(), seed?.next, seed != null)
        }
    }
    val orderedSources = remember(idQuery, source) {
        if (idQuery) listOf(source) + sources.map { it.first }.filter { it != source } else listOf(source)
    }
    val resultSources = remember(orderedSources, selectedKind) {
        orderedSources.filterNot { it == Source.Netease && selectedKind == SearchCatalog.Kind.Video }
    }
    val exact = remember(overviews) { mutableStateMapOf<Pair<Source, SearchCatalog.Kind>, SearchCatalog.ExactLookup>() }
    var lookingUpId by remember(overviews) { mutableStateOf(idQuery) }
    var suggestions by remember(overviews, source) { mutableStateOf(emptyList<SearchCatalog.Suggestion>()) }
    val player by app.player.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    LaunchedEffect(visible, source, baseUrl, catalogSettings.fetchedAtMs, refreshRequest, lifecycle) {
        if (!visible || source != Source.Apple) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var remaining = storefronts.state.value.millisUntilStale()
            while (remaining > 0) {
                delay(remaining)
                remaining = storefronts.state.value.millisUntilStale()
            }
            storefronts.refreshIfStale()
        }
    }

    if (choosingStorefront && visible && source == Source.Apple) {
        StorefrontPicker(catalogSettings, onDismiss = { choosingStorefront = false }, onRetry = { refreshRequest++ }) { id ->
            storefronts.select(baseUrl, id)
            choosingStorefront = false
        }
    }

    LaunchedEffect(overviews) {
        if (!idQuery) return@LaunchedEffect
        delay(330)
        search.exactMatches(term, storefront).collect { lookup -> exact[lookup.source to lookup.kind] = lookup }
        lookingUpId = false
    }

    if (term.isNotEmpty()) for (provider in resultSources) key(provider) {
        SearchResultsRequest(search, results.getValue(provider), provider, selectedKind, term, storefront)
    }

    LaunchedEffect(overviews, source, editing) {
        if (!editing || term.isEmpty()) return@LaunchedEffect
        delay(220)
        try {
            suggestions = search.suggestions(source, term, storefront)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            suggestions = emptyList()
        }
    }

    val exactHits = if (!idQuery) emptyList() else buildList {
        for (provider in orderedSources) for (type in idKinds) {
            if (selectedKind == null || type == selectedKind) exact[provider to type]?.hit?.let(::add)
        }
    }
    val pinned = exactHits.mapTo(hashSetOf()) { it.libraryKey }
    val shownSuggestions = if (editing) suggestions.filter { it.hit?.libraryKey !in pinned } else emptyList()

    Column(Modifier.fillMaxSize().imePadding()) {
        if (!editing && term.isEmpty()) {
            Text(
                "搜索",
                Modifier.padding(horizontal = Dimens.Page, vertical = 12.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                Modifier.weight(1f),
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
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = ""; kind = null }) {
                                Icon(Icons.Rounded.Close, "清除搜索")
                            }
                        }
                    } else null,
                )
            }
            if (source == Source.Apple) {
                val code = storefront.uppercase(Locale.ROOT)
                FilledTonalButton(
                    onClick = { focus.clearFocus(); choosingStorefront = true },
                    modifier = Modifier.semantics { contentDescription = "Apple Music 搜索地区 $code" },
                ) { Text(code) }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 4.dp)) {
            sources.forEachIndexed { index, (choice, title) ->
                SegmentedButton(
                    selected = source == choice,
                    onClick = {
                        if (!idQuery && choice == Source.Netease && kind == SearchCatalog.Kind.Video) kind = null
                        source = choice
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                    icon = {},
                ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (term.isNotEmpty()) {
            val tabs = if (supportsVideo) searchTabs else searchTabs.filter { it.first != SearchCatalog.Kind.Video }
            val selected = tabs.indexOfFirst { it.first == selectedKind }.coerceAtLeast(0)
            SecondaryScrollableTabRow(selectedTabIndex = selected, edgePadding = Dimens.Page) {
                tabs.forEach { (filter, label) ->
                    Tab(
                        selected = filter == selectedKind,
                        onClick = { kind = filter; focus.clearFocus() },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
        if (term.isEmpty() && !editing && source == Source.Apple) {
            Box(Modifier.weight(1f)) {
                AppleDiscoveryScreen(app, radio = false, onOpen = onOpen, searchLanding = true)
            }
            return@Column
        }
        key(results, source) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = LocalBarsPadding.current) {
                if (exactHits.isNotEmpty() || (lookingUpId && (selectedKind == null || selectedKind in idKinds))) {
                    item("exact-title") {
                        Md3MediaSectionHeader(if (exactHits.isEmpty()) "正在查询平台 ID…" else "ID 精确匹配")
                    }
                    items(exactHits, key = { "exact:${it.source}:${it.kind}:${it.id}" }) { hit ->
                        SearchHitRow(app, hit, player.mediaId, focus, onOpen, exact = true)
                    }
                }
                if (shownSuggestions.isNotEmpty()) {
                    item("suggestions") { Md3MediaSectionHeader("搜索建议") }
                    items(shownSuggestions, key = { "suggestion:${it.hit?.source}:${it.hit?.kind}:${it.hit?.id}:${it.query}" }) { suggestion ->
                        Md3MediaRow(
                            title = suggestion.title,
                            subtitle = suggestion.hit?.subtitle.orEmpty(),
                            leading = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            onClick = {
                                focus.clearFocus()
                                val hit = suggestion.hit
                                if (hit?.track != null) app.player.play(hit.track)
                                else if (hit != null) onOpen(hit)
                                else suggestion.query?.let { query = it }
                            },
                        )
                    }
                }
                var hasRows = exactHits.isNotEmpty() || shownSuggestions.isNotEmpty()
                var hasErrors = false
                var loading = lookingUpId
                for (provider in resultSources) {
                    val result = results.getValue(provider)
                    val label = sourceNames.getValue(provider)
                    val prefix = provider.mediaPrefix
                    val error = result.error
                    loading = loading || (term.isNotEmpty() && !result.loaded && error == null) ||
                        (selectedKind == null && result.busy)
                    if (selectedKind == null) {
                        val groups = if (idQuery) result.groups.sortedBy { group -> searchTabs.indexOfFirst { it.first == group.kind } }
                        else result.groups
                        for (group in groups) {
                            val rows = group.items.filterNot { it.libraryKey in pinned }
                            if (rows.isEmpty() && group.next == null) continue
                            hasRows = true
                            val limit = when (group.kind) {
                                SearchCatalog.Kind.Song -> 9
                                SearchCatalog.Kind.Video -> 4
                                else -> 6
                            }
                            item("$prefix:group:${group.key}") {
                                Md3MediaSectionHeader(if (idQuery) "$label · ${group.title}" else group.title) {
                                    if (group.kind != null && (rows.size > limit || group.next != null)) {
                                        TextButton(onClick = { source = provider; kind = group.kind; focus.clearFocus() }) { Text("查看全部") }
                                    }
                                }
                            }
                            items(rows.take(limit), key = { "$prefix:${group.key}:${it.kind}:${it.id}" }) { hit ->
                                SearchHitRow(app, hit, player.mediaId, focus, onOpen)
                            }
                        }
                        if (result.failures.isNotEmpty()) hasErrors = true
                        items(result.failures, key = { "$prefix:failure:${it.kind.id}" }) { failure ->
                            val group = if (idQuery) "$label · ${failure.kind.title}" else failure.kind.title
                            SearchFailureRow("${group}搜索失败：${failure.message}", busy = result.busy) { result.pageRequest++ }
                        }
                    } else {
                        val rows = result.items.filterNot { it.libraryKey in pinned }
                        if (rows.isNotEmpty() || result.next != null) {
                            hasRows = true
                            if (idQuery) item("$prefix:heading") { Md3MediaSectionHeader(label) }
                            items(rows, key = { "$prefix:${it.kind}:${it.id}" }) { hit ->
                                SearchHitRow(app, hit, player.mediaId, focus, onOpen)
                            }
                        }
                        if (error == null && result.next != null) item("$prefix:more") {
                            FilledTonalButton(onClick = { result.pageRequest++ }, enabled = !result.busy, modifier = Modifier.padding(Dimens.Page)) {
                                Text(if (result.busy) "正在加载…" else "加载更多")
                            }
                        }
                    }
                    if (error != null) {
                        hasErrors = true
                        item("$prefix:error") {
                            if (idQuery) SearchFailureRow("${label}搜索失败：$error", busy = result.busy) { result.pageRequest++ }
                            else Md3EmptyState("搜索失败", error, icon = Icons.Rounded.ErrorOutline) {
                                FilledTonalButton(onClick = { result.pageRequest++ }) { Text("重试") }
                            }
                        }
                    }
                }
                if (idQuery) for (provider in orderedSources) {
                    val messages = idKinds.mapNotNull { exact[provider to it]?.error }.distinct()
                    if (messages.isEmpty()) continue
                    hasErrors = true
                    item("id-error:${provider.mediaPrefix}") {
                        Text("${sourceNames.getValue(provider)} ID 查询失败：${messages.joinToString("；")}",
                            Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (loading) item("loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (term.isNotEmpty() && !hasRows && !hasErrors) item("empty") {
                    Md3EmptyState("没有搜索结果", "试试其他关键词或音乐来源。", icon = Icons.Rounded.Search)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsRequest(
    search: SearchCatalog,
    result: SearchResults,
    source: Source,
    kind: SearchCatalog.Kind?,
    term: String,
    storefront: String,
) {
    LaunchedEffect(result, result.pageRequest) {
        val request = result.pageRequest
        if (request == result.handledRequest && (result.loaded || result.error != null)) return@LaunchedEffect
        if (request == 0) delay(330)
        result.busy = true
        result.error = null
        try {
            if (kind == null) {
                val grouped = search.searchAll(source, term, storefront)
                val failedKinds = grouped.failures.mapTo(hashSetOf()) { it.kind }
                result.groups = grouped.groups + result.groups.filter { it.kind in failedKinds }
                result.failures = grouped.failures
            } else {
                val page = if (result.loaded) search.more(result.next ?: return@LaunchedEffect)
                    else search.search(source, kind, term, storefront)
                result.items = (result.items + page.items).distinctBy { it.libraryKey }
                result.next = page.next
            }
            result.loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.error = e.message ?: "请检查 Fantastic 服务器连接"
        } finally {
            result.busy = false
        }
        result.handledRequest = request
    }
}

@Composable
private fun SearchFailureRow(message: String, busy: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(enabled = !busy, onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun StorefrontPicker(
    state: AppleStorefrontState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    GlassSheet(title = "Apple Music 地区", onDismissRequest = onDismiss, scrollable = false) {
        LazyColumn(Modifier.fillMaxWidth()) {
            val error = state.error
            if (state.refreshing || (state.available.isEmpty() && error == null)) item("loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (error != null) item("error") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            items(state.available, key = { it.id }) { entry ->
                SheetAction("${entry.name}（${entry.id.uppercase(Locale.ROOT)}）", selected = entry.id == state.selected,
                    onClick = { onSelect(entry.id) })
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    app: FantasticApp,
    hit: CatalogHit,
    playingMediaId: String?,
    focus: FocusManager,
    onOpen: (CatalogHit) -> Unit,
    exact: Boolean = false,
) {
    val track = hit.track
    var actionsOpen by remember(hit.source, hit.kind, hit.id) { mutableStateOf(false) }
    val subtitle = when {
        exact -> listOf(sourceNames.getValue(hit.source), searchKindLabel(hit.kind), hit.subtitle)
            .filter { it.isNotBlank() }.joinToString(" · ")
        track != null -> track.artist
        else -> listOf(searchKindLabel(hit.kind), hit.subtitle).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
    Md3MediaRow(
        title = hit.title,
        subtitle = subtitle,
        playing = track != null && playingMediaId == "${track.source.mediaPrefix}:${track.id}",
        onClick = {
            focus.clearFocus()
            if (track != null) app.player.play(track) else onOpen(hit)
        },
        leading = {
            Md3MediaArtwork(
                hit.artwork ?: track?.artwork(120), Modifier.size(48.dp),
                shape = if (hit.kind == "artist") CircleShape else MaterialTheme.shapes.small,
            )
        },
        trailing = {
            if (track == null) Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            else IconButton(onClick = { focus.clearFocus(); actionsOpen = true }) {
                Icon(Icons.Rounded.MoreHoriz, "${hit.title}更多操作")
            }
        },
    )
    if (track != null && actionsOpen) {
        GlassSheet(hit.title, onDismissRequest = { actionsOpen = false }, showHeader = false) {
            MediaActionContent(hit, app.library, listOf(track),
                onPlay = { actionsOpen = false; app.player.play(track) },
                onView = { actionsOpen = false; onOpen(hit) },
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
