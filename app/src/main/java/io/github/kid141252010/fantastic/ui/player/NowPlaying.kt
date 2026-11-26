package io.github.kid141252010.fantastic.ui.player

import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.kid141252010.fantastic.data.SongRelations
import io.github.kid141252010.fantastic.data.songRelations
import io.github.kid141252010.fantastic.data.resolveSongRelations
import io.github.kid141252010.fantastic.data.validCatalogId
import io.github.kid141252010.fantastic.data.catalogCountry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.MusicLibrary
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.librarySongHit
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.playback.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Original fragment fade for cover, lyrics and queue pane switches. */
private val PlayerFragmentMotion = tween<Float>(500, easing = CubicBezierEasing(.25f, .1f, .25f, 1f))
enum class PlayerPane { Cover, Lyrics, Queue }

internal sealed interface PlayerPanel {
    data object Track : PlayerPanel
    data object Entities : PlayerPanel
    data object Quality : PlayerPanel
}

@Composable
internal fun NowPlayingContent(
    state: PlayerState,
    player: PlayerConnection,
    lyrics: LyricsController,
    library: MusicLibrary,
    fullVisible: Boolean,
    contentFade: Modifier,
    sheetDragModifier: Modifier,
    sheetNestedScroll: NestedScrollConnection,
    onLyricsDragBy: (Float) -> Boolean,
    onLyricsScrollEnd: (Float, Float) -> Boolean,
    pane: PlayerPane,
    onPaneChange: (PlayerPane) -> Unit,
    translation: Boolean,
    pronunciation: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onOpenCatalog: (CatalogHit) -> Unit,
    canCommitRelationNavigation: () -> Boolean,
    onOpenSettings: () -> Unit,
    artworkSlot: @Composable (PlayerPane, Modifier) -> Unit,
    onArtworkUnobscuredChange: (Boolean) -> Unit,
    onQueueAtTopChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FantasticApp
    val rawTrack = state.currentTrack
    val storefront by app.prefs.storefrontState.collectAsStateWithLifecycle()
    val server = app.prefs.baseUrl
    val requestCountry = if (rawTrack?.source == Source.Apple) rawTrack.country.normalizedAppleCountry()
        ?: storefront.normalizedAppleCountry() else null
    val occurrence = state.trackOccurrence
    val identity = remember(rawTrack, occurrence, server, requestCountry, pane) { Any() }
    var panel by remember(identity) { mutableStateOf<PlayerPanel?>(null) }
    var panelEpoch by remember(identity) { mutableStateOf(0) }
    fun changePanel(next: PlayerPanel?) { panelEpoch++; panel = next }
    val commitAllowed by rememberUpdatedState(canCommitRelationNavigation)
    val latestPane by rememberUpdatedState(pane)
    val latestOpenCatalog by rememberUpdatedState(onOpenCatalog)
    fun identityCurrent(): Boolean = rawTrack != null && player.state.value.currentTrack == rawTrack &&
        player.state.value.trackOccurrence == occurrence && app.prefs.baseUrl == server && latestPane == pane &&
        (rawTrack.source != Source.Apple ||
            (rawTrack.country.normalizedAppleCountry() ?: app.prefs.storefront.normalizedAppleCountry()) == requestCountry) && commitAllowed()
    fun panelCurrent(epoch: Int): Boolean = panel == PlayerPanel.Entities && panelEpoch == epoch && identityCurrent()
    fun commit(entity: CatalogHit, epoch: Int? = null) {
        if (!identityCurrent() || epoch != null && !panelCurrent(epoch)) return
        changePanel(null)
        latestOpenCatalog(entity)
    }
    SideEffect { onArtworkUnobscuredChange(panel == null) }
    val libraryState by library.snapshot.collectAsStateWithLifecycle()
    val hit = remember(rawTrack) { rawTrack?.let(SearchCatalog::trackHit) }
    var resolvedRelations by remember(identity) { mutableStateOf<SongRelations?>(null) }
    var relationError by remember(identity) { mutableStateOf<String?>(null) }
    var relationAttempt by remember(identity) { mutableStateOf(0) }
    var artistArtwork by remember(identity) { mutableStateOf(emptyMap<CatalogHit, String>()) }
    val attemptedArtwork = remember(identity) { mutableSetOf<CatalogHit>() }
    val relations = resolvedRelations ?: rawTrack?.songRelations(requestCountry) ?: SongRelations()
    val entities = remember(relations, artistArtwork) {
        listOfNotNull(relations.artist, relations.album).map { entity ->
            artistArtwork[entity]?.let { entity.copy(artwork = it) } ?: entity
        }
    }
    val validSong = rawTrack?.let { it.id.isNotBlank() && it.id != "0" && validCatalogId(it.source, "song", it.id) } == true
    val relationSurfaceActive = commitAllowed()
    LaunchedEffect(relationSurfaceActive) {
        if (!relationSurfaceActive && panel == PlayerPanel.Entities) changePanel(null)
    }
    LaunchedEffect(identity, panel, panelEpoch, relationAttempt, relationSurfaceActive) {
        if (panel != PlayerPanel.Entities || !relationSurfaceActive || rawTrack == null || !validSong ||
            resolvedRelations != null || relationError != null || entities.isNotEmpty()) return@LaunchedEffect
        val epoch = panelEpoch
        try {
            val result = app.resolveSongRelations(rawTrack, requestCountry)
            ensureActive()
            if (!panelCurrent(epoch)) return@LaunchedEffect
            resolvedRelations = result
            val targets = listOfNotNull(result.artist, result.album)
            if (targets.size == 1) commit(targets.single(), epoch)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            ensureActive()
            if (panelCurrent(epoch)) relationError = failure.message ?: "读取失败"
        }
    }
    LaunchedEffect(identity, panel, panelEpoch, relations, relationSurfaceActive) {
        if (panel != PlayerPanel.Entities || !relationSurfaceActive) return@LaunchedEffect
        val epoch = panelEpoch
        val artist = relations.artist?.takeIf { it.artwork.isNullOrBlank() && attemptedArtwork.add(it) } ?: return@LaunchedEffect
        try {
            val avatar = when (artist.source) {
                Source.Apple -> {
                    val country = artist.country.normalizedAppleCountry() ?: return@LaunchedEffect
                    val resource = app.apple.entity(AppleCatalogApi.Entity.Artist, artist.id, storefront = country)
                    check(resource.id == artist.id && resource.type == "artists") { "服务器返回了其他艺人。" }
                    check(resource.catalogCountry(null)?.let { it == country } != false) { "服务器返回了其他国家的艺人。" }
                    resource.artworkUrl(500)
                }
                Source.Netease -> app.netease.artist(artist.id).artist.let {
                    check(it.mid == artist.id) { "服务器返回了其他艺人。" }; it.avatar
                }
                Source.QQ -> null
            }?.takeIf { it.isNotBlank() }
            ensureActive()
            if (avatar != null && panelCurrent(epoch)) artistArtwork = artistArtwork + (artist to avatar)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            Log.w("FantasticCatalog", "${artist.source} artist artwork failed for ${artist.id}", failure)
        }
    }
    val libraryEntry = hit?.let { song -> libraryState.items.firstOrNull {
        it.key.source == song.source && it.key.kind == song.kind && it.key.id == song.id
    } }
    val favorite = libraryEntry?.isFavorite == true
    val scope = rememberCoroutineScope()
    val toggleFavorite: (() -> Unit)? = hit?.let { song ->
        {
            if (libraryEntry?.isFavorite == true) library.toggleFavorite(libraryEntry.hit)
            else scope.launch { library.toggleFavorite(app.librarySongHit(song)) }
        }
    }
    val openEntities: () -> Unit = {
        if (identityCurrent()) {
            if (entities.size == 1) commit(entities.single()) else changePanel(PlayerPanel.Entities)
        }
    }
    val lyricsStatus by lyrics.status.collectAsStateWithLifecycle()
    val lyricsError by lyrics.error.collectAsStateWithLifecycle()
    val annotations by lyrics.annotations.collectAsStateWithLifecycle()
    val lyricMediaId by lyrics.currentLyricMediaId.collectAsStateWithLifecycle()
    LaunchedEffect(state.mediaId, lyricMediaId, lyricsStatus, pane) {
        if (pane == PlayerPane.Lyrics && lyricMediaId == state.mediaId && lyricsStatus == LyricsController.Status.Missing) {
            onPaneChange(PlayerPane.Cover)
        }
    }
    LaunchedEffect(translation, pronunciation) { lyrics.showAnnotations(translation, pronunciation) }

    fun togglePane(target: PlayerPane) = onPaneChange(if (pane == target) PlayerPane.Cover else target)
    fun route() {
        val shown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        if (!shown) context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
    val showTrackMenu = { changePanel(PlayerPanel.Track) }


    Box(Modifier.fillMaxSize().nestedScroll(sheetNestedScroll)) {
        Box(
            Modifier.fillMaxSize().panelTouchBoundary()
                .then(if (panel != null) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
                Box(Modifier.fillMaxWidth().height(32.dp).then(sheetDragModifier))
                AnimatedContent(
                    targetState = pane,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn(PlayerFragmentMotion) togetherWith fadeOut(PlayerFragmentMotion) },
                    label = "player-pane",
                ) { target ->
                    val activePane = target == pane
                    val interactive = fullVisible && activePane && panel == null
                    BoxWithConstraints(Modifier.fillMaxSize().padding(top = if (target == PlayerPane.Queue) 32.5.dp else 32.dp)
                        .then(if (!activePane) Modifier.clearAndSetSemantics {} else Modifier)) {
                        val inset = maxHeight * PlayerControlsHeight
                        val controls: @Composable (Modifier) -> Unit = { modifier ->
                            TransportControls(
                                state, player, target == PlayerPane.Lyrics, target == PlayerPane.Queue,
                                toggleLyrics = { if (interactive) togglePane(PlayerPane.Lyrics) },
                                toggleQueue = { if (interactive) togglePane(PlayerPane.Queue) },
                                onQuality = { if (interactive) changePanel(PlayerPanel.Quality) },
                                onRoute = { if (interactive) route() },
                                modifier = modifier.then(contentFade),
                            )
                        }
                        val header: @Composable () -> Unit = {
                            CurrentTrackHeader(
                                state = state,
                                artwork = { modifier -> artworkSlot(target, modifier) },
                                marquee = interactive,
                                favorite = favorite,
                                onFavorite = toggleFavorite,
                                onMore = showTrackMenu,
                                onOpenEntities = openEntities.takeIf { interactive && rawTrack != null },
                                modifier = Modifier.then(sheetDragModifier),
                                contentFade = contentFade,
                            )
                        }
                        when (target) {
                            PlayerPane.Cover -> CoverPane(
                                state = state,
                                artwork = { modifier -> artworkSlot(target, modifier) },
                                marquee = interactive,
                                drag = sheetDragModifier,
                                favorite = favorite,
                                onFavorite = toggleFavorite,
                                onMore = showTrackMenu,
                                onOpenEntities = openEntities.takeIf { interactive && rawTrack != null },
                                controls = controls,
                                contentFade = contentFade,
                            )
                            PlayerPane.Lyrics -> LyricsPlayerPane(
                                lyrics, state.mediaId, state.isPlaying, lyricsStatus, lyricsError, inset, interactive,
                                translation, pronunciation,
                                annotations.translation, annotations.pronunciation, onTranslationChange, onPronunciationChange,
                                onSheetDragBy = onLyricsDragBy,
                                onSheetScrollEnd = onLyricsScrollEnd,
                                header = header, controls = controls,
                                contentFade = contentFade,
                            )
                            PlayerPane.Queue -> {
                                QueuePane(state, player, inset, Modifier.fillMaxSize(), contentFade,
                                    onScrollToStartChanged = onQueueAtTopChanged, header = header)
                                controls(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(inset).panelTouchBoundary())
                            }
                        }
                    }
                }
            }
        }
        PlayerPanels(
            panel = panel,
            onDismiss = { changePanel(null) },
            trackPanel = { palette ->
                state.error?.let { error -> androidx.compose.material3.Text(error, Modifier.padding(16.dp), color = palette.label) }
                hit?.let { song ->
                    io.github.kid141252010.fantastic.ui.MediaActionContent(song, library, listOfNotNull(rawTrack), nowPlaying = true,
                        hasLyrics = lyricsStatus == LyricsController.Status.Ready,
                        onQuality = { changePanel(PlayerPanel.Quality) },
                        pronunciation = pronunciation,
                        onPronunciation = if (pane == PlayerPane.Lyrics && annotations.pronunciation) ({ onPronunciationChange(!pronunciation); changePanel(null) }) else null,
                        translation = translation,
                        onTranslation = if (pane == PlayerPane.Lyrics && annotations.translation) ({ onTranslationChange(!translation); changePanel(null) }) else null,
                        onDismiss = { changePanel(null) })
                }
            },
            entityPanel = { palette ->
                when {
                    entities.isNotEmpty() -> {
                        val epoch = panelEpoch
                        CatalogActionContent(entities, palette, onOpen = { entity -> commit(entity, epoch) })
                    }
                    relationError != null -> {
                        Text("无法读取艺人和专辑：$relationError", Modifier.padding(16.dp), color = palette.label)
                        TextButton(onClick = { if (identityCurrent()) { relationError = null; relationAttempt++ } }) {
                            Text("重试", color = palette.label)
                        }
                    }
                    resolvedRelations == null && validSong -> Text("正在读取艺人和专辑…", Modifier.padding(16.dp), color = palette.label)
                    else -> Text("暂无可跳转的艺人或专辑", Modifier.padding(16.dp), color = palette.label)
                }
            },
            qualityState = state,
            onOpenSettings = { changePanel(null); onOpenSettings() }
        )
    }
}


