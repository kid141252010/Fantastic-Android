package io.github.kid141252010.fantastic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.ui.library.MusicLibraryScreen
import io.github.kid141252010.fantastic.ui.browse.AppleDiscoveryScreen
import io.github.kid141252010.fantastic.ui.player.AppleMediaScreen
import io.github.kid141252010.fantastic.ui.player.PlayerPane
import io.github.kid141252010.fantastic.ui.player.PlayerSheetHost
import io.github.kid141252010.fantastic.ui.search.CatalogDetails
import io.github.kid141252010.fantastic.ui.search.SearchScreen
import io.github.kid141252010.fantastic.ui.settings.SettingsScreen
import io.github.kid141252010.fantastic.ui.theme.TabAccent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.exp
enum class Tab(val label: String, val icon: ImageVector) {
    Home("主页", Icons.Rounded.Home),
    New("新发现", Icons.Rounded.GridView),
    Radio("广播", Icons.Rounded.Radio),
    Library("资料库", Icons.Rounded.LibraryMusic),
    Search("搜索", Icons.Rounded.Search),
}

/**
 * 外壳：页面铺满全屏（内容一直延伸到屏幕底部），底部悬浮玻璃迷你播放条 + 玻璃标签栏，
 * 玻璃取样页面内容做模糊；迷你、全屏播放器与主底栏由同一 sheet progress 驱动。
 */
@Composable
fun AppShell(app: FantasticApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var rootNavigationRequest by rememberSaveable { mutableStateOf(0) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var playerPane by rememberSaveable {
        mutableStateOf(PlayerPane.entries.firstOrNull { it.name == app.prefs.lastPlayerPane } ?: PlayerPane.Cover)
    }
    var lyricTranslation by rememberSaveable { mutableStateOf(app.prefs.lyricTranslation) }
    var lyricPronunciation by rememberSaveable { mutableStateOf(app.prefs.lyricPronunciation) }
    var showLibrarySources by rememberSaveable { mutableStateOf(app.prefs.showLibrarySources) }
    val playerConnection = app.player
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedPane = rememberUpdatedState(playerPane)
    DisposableEffect(lifecycleOwner, app.prefs, playerConnection) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> playerConnection.connectIfNeeded()
                Lifecycle.Event.ON_STOP -> {
                    app.prefs.lastPlayerPane = savedPane.value.name
                    playerConnection.disconnectIfIdle()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val storefront by app.prefs.storefrontState.collectAsStateWithLifecycle()
    var catalog by remember { mutableStateOf<CatalogHit?>(null) }
    var catalogStorefront by remember { mutableStateOf<String?>(null) }
    fun openCatalog(hit: CatalogHit) {
        catalogStorefront = if (hit.source == Source.Apple) hit.country.normalizedAppleCountry() ?: app.prefs.storefront else null
        catalog = hit
    }
    fun closeCatalog() {
        catalog = null
        catalogStorefront = null
    }
    val visibleCatalog = catalog?.takeIf { it.source != Source.Apple || it.country.normalizedAppleCountry() != null || catalogStorefront == storefront }
    LaunchedEffect(storefront, catalog, catalogStorefront) {
        if (catalog?.source == Source.Apple && catalog?.country.normalizedAppleCountry() == null && catalogStorefront != storefront) closeCatalog()
    }
    val appleMedia = visibleCatalog?.takeIf { it.source == Source.Apple && it.kind in setOf("music-video", "station", "post") }
    val glassSheetState = remember { mutableStateOf(false) }
    val glassSheetVisibility = remember { { visible: Boolean -> glassSheetState.value = visible } }
    val playerBackOwnership = remember { mutableStateOf(false) }
    val onPlayerBackOwnershipChange = remember { { ownsBack: Boolean -> playerBackOwnership.value = ownsBack } }
    val libraryBackEnabled = !playerBackOwnership.value && catalog == null && !settings
    val player by playerConnection.state.collectAsStateWithLifecycle()
    remember { app.lyrics }
    val haze = remember { HazeState() }
    val hasTrack = player.mediaId != null
    val playerExpanded = hasTrack && nowPlaying
    if (playerExpanded && playerPane == PlayerPane.Lyrics && !settings && appleMedia == null && !glassSheetState.value) {
        val view = LocalView.current
        LifecycleResumeEffect(view) {
            val previouslyKeptOn = view.keepScreenOn
            view.keepScreenOn = true
            onPauseOrDispose { view.keepScreenOn = previouslyKeptOn }
        }
    }
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val navBottomPx = with(LocalDensity.current) { nav.calculateBottomPadding().toPx() }
    val barsHeight = Dimens.BarBottom + Dimens.TabBarHeight +
        (if (hasTrack) Dimens.BarGap + Dimens.MiniPlayerHeight else 0.dp) + Dimens.BarGap
    val barsPadding = PaddingValues(bottom = nav.calculateBottomPadding() + barsHeight)

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalHaze provides haze,
            LocalBarsPadding provides barsPadding,
            LocalGlassSheetVisibility provides glassSheetVisibility,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().haze(haze).statusBarsPadding()
                        .then(if (playerExpanded || settings || visibleCatalog != null) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    when (tab) {
                        Tab.Search -> SearchScreen(app, rootNavigationRequest = rootNavigationRequest, onOpen = ::openCatalog)
                        Tab.Library -> MusicLibraryScreen(app, showLibrarySources, rootNavigationRequest,
                            backEnabled = libraryBackEnabled, onOpen = ::openCatalog)
                        Tab.New -> key(storefront) {
                            AppleDiscoveryScreen(app, radio = false, rootNavigationRequest = rootNavigationRequest, onOpen = ::openCatalog)
                        }
                        Tab.Radio -> key(storefront) {
                            AppleDiscoveryScreen(app, radio = true, rootNavigationRequest = rootNavigationRequest, onOpen = ::openCatalog)
                        }
                        Tab.Home -> Placeholder(tab, onSettings = { settings = true })
                    }
                }
                visibleCatalog?.takeIf { appleMedia == null }?.let { hit ->
                    CatalogDetails(app, hit.source, hit.kind, hit.id, hit.country ?: catalogStorefront,
                        modifier = Modifier.fillMaxSize().haze(haze),
                        interactive = !playerBackOwnership.value && !playerExpanded && !settings && !glassSheetState.value,
                        onClose = ::closeCatalog)
                }

                PlayerSheetHost(
                    state = player,
                    player = playerConnection,
                    lyrics = app.lyrics,
                    library = app.library,
                    apple = app.apple,
                    qq = app.qq,
                    netease = app.netease,
                    pane = playerPane,
                    onPaneChange = { playerPane = it },
                    translation = lyricTranslation,
                    pronunciation = lyricPronunciation,
                    onTranslationChange = { lyricTranslation = it; app.prefs.lyricTranslation = it },
                    onPronunciationChange = { lyricPronunciation = it; app.prefs.lyricPronunciation = it },
                    onOpenCatalog = ::openCatalog,
                    onOpenSettings = { settings = true },
                    initiallyExpanded = nowPlaying,
                    onExpandedChange = { isExpanded ->
                        nowPlaying = isExpanded
                        if (!isExpanded) app.prefs.lastPlayerPane = playerPane.name
                    },
                    chromeVisible = !glassSheetState.value,
                    onBackOwnershipChange = onPlayerBackOwnershipChange,
                    bottomBar = { frame, chromeInteractive ->
                        Box(
                            Modifier.fillMaxWidth().graphicsLayer {
                                translationY = (1f - exp(-20f * frame.progress)) * (size.height + navBottomPx)
                            },
                        ) {
                            TabBar(
                                selected = tab,
                                onSelect = {
                                    closeCatalog()
                                    settings = false
                                    tab = it
                                    rootNavigationRequest++
                                },
                                enabled = !glassSheetState.value && chromeInteractive,
                            )
                        }
                    },
                )

                if (settings) {
                    if (!glassSheetState.value) BackHandler { settings = false }
                    SettingsScreen(app, app.lyrics, showLibrarySources,
                        onShowLibrarySourcesChange = { showLibrarySources = it; app.prefs.showLibrarySources = it },
                        onClose = { settings = false })
                }
                appleMedia?.let { hit -> AppleMediaScreen(app, hit, onClose = ::closeCatalog) }
            }
        }
    }
}

/** 悬浮玻璃标签栏：选中项用系统强调色（Material You 动态取色，录屏里是蓝色） */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, enabled: Boolean = true) {
    Glass(CircleShape, Modifier.fillMaxWidth().height(Dimens.TabBarHeight)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val color = if (t == selected) TabAccent() else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 32.dp),
                        ) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(26.dp))
                    Text(t.label, color = color, fontSize = 10.sp, lineHeight = 12.sp)
                }
            }
        }
    }
}

/** Playback-driven placeholders for the pages in the migration roadmap. */
@Composable
private fun Placeholder(tab: Tab, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            LargeTitle(tab.label)
            CircularIconButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                Icon(Icons.Rounded.AccountCircle, contentDescription = "设置", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (tab == Tab.Home) {
            Text("在搜索中查找歌曲、专辑、艺人或歌单。", Modifier.padding(horizontal = Dimens.Page, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
