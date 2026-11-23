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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.MusicLibrary
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.lyrics.LyricShareLine
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Original fragment fade for cover, lyrics and queue pane switches. */
private val PlayerFragmentMotion = tween<Float>(500, easing = CubicBezierEasing(.25f, .1f, .25f, 1f))
enum class PlayerPane { Cover, Lyrics, Queue }

internal sealed interface PlayerPanel {
    data object Track : PlayerPanel
    data object Entities : PlayerPanel
    data object Quality : PlayerPanel
    data class Share(val lines: List<LyricShareLine>, val startIndex: Int = 0) : PlayerPanel
}

private fun String.isMissingCatalogId() = isBlank() || this == "0"

@Composable
internal fun NowPlayingContent(
    state: PlayerState,
    player: PlayerConnection,
    lyrics: LyricsController,
    library: MusicLibrary,
    apple: AppleCatalogApi,
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
    onOpenSettings: () -> Unit,
    artworkSlot: @Composable (PlayerPane, Modifier) -> Unit,
    onArtworkUnobscuredChange: (Boolean) -> Unit,
    onQueueAtTopChanged: (Boolean) -> Unit,
) {
    var panel by remember(state.mediaId) { mutableStateOf<PlayerPanel?>(null) }
    SideEffect { onArtworkUnobscuredChange(panel == null) }
    var shareSelection by remember(state.mediaId, panel) {
        val share = panel as? PlayerPanel.Share
        mutableStateOf(if (share != null && share.startIndex in share.lines.indices) setOf(share.startIndex) else emptySet())
    }
    val libraryState by library.snapshot.collectAsStateWithLifecycle()
    val rawTrack = state.currentTrack
    var menuTrack by remember(rawTrack) { mutableStateOf(rawTrack) }
    val trackMenuOpen = panel == PlayerPanel.Track
    LaunchedEffect(trackMenuOpen, rawTrack, apple) {
        if (!trackMenuOpen || rawTrack?.source != Source.Apple) return@LaunchedEffect
        val current = menuTrack ?: return@LaunchedEffect
        val missingArtist = current.artistId.isMissingCatalogId()
        val missingAlbum = current.albumId.isMissingCatalogId()
        if (!missingArtist && !missingAlbum) return@LaunchedEffect

        try {
            val details = apple.entity(AppleCatalogApi.Entity.Song, rawTrack.id).toTrack()
            if (details?.source != Source.Apple || details.id != rawTrack.id) return@LaunchedEffect
            ensureActive()
            menuTrack = current.copy(
                artist = current.artist.ifBlank { details.artist },
                album = current.album.ifBlank { details.album },
                artworkTemplate = current.artworkTemplate?.takeIf { it.isNotBlank() } ?: details.artworkTemplate,
                durationMs = if (current.durationMs > 0) current.durationMs else details.durationMs,
                artistId = if (missingArtist) details.artistId else current.artistId,
                albumId = if (missingAlbum) details.albumId else current.albumId,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("FantasticCatalog", "Apple song relationship lookup failed for ${rawTrack.id}", error)
        }
    }
    val hit = remember(menuTrack) {
        menuTrack?.let { CatalogHit(it.source, "song", it.id, it.title, it.artist, it.artwork(500), it, detailOnly = false) }
    }
    val libraryEntry = hit?.let { song -> libraryState.items.firstOrNull {
        it.key.source == song.source && it.key.kind == song.kind && it.key.id == song.id
    } }
    val favorite = libraryEntry?.isFavorite == true
    val toggleFavorite: (() -> Unit)? = hit?.let { { library.toggleFavorite(it) } }
    val context = LocalContext.current
    val entities = remember(menuTrack) {
        buildList {
            menuTrack?.let { song ->
                if (!song.artistId.isMissingCatalogId()) add(CatalogHit(
                    song.source, "artist", song.artistId, song.artist, artwork = null, detailOnly = true))
                if (!song.albumId.isMissingCatalogId()) add(CatalogHit(
                    song.source, "album", song.albumId, song.album, artwork = song.artwork(500), detailOnly = true))
            }
        }
    }
    val openEntities: () -> Unit = {
        if (entities.size == 1) { panel = null; onOpenCatalog(entities.single()) }
        else if (entities.isNotEmpty()) panel = PlayerPanel.Entities
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
    val showTrackMenu = { panel = PlayerPanel.Track }


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
                                onQuality = { if (interactive) panel = PlayerPanel.Quality },
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
                                onOpenEntities = openEntities.takeIf { interactive && entities.isNotEmpty() },
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
                                onOpenEntities = openEntities.takeIf { interactive && entities.isNotEmpty() },
                                controls = controls,
                                contentFade = contentFade,
                            )
                            PlayerPane.Lyrics -> LyricsPlayerPane(
                                lyrics, state.mediaId, state.isPlaying, lyricsStatus, lyricsError, inset, interactive,
                                translation, pronunciation,
                                annotations.translation, annotations.pronunciation, onTranslationChange, onPronunciationChange,
                                onShare = { lines, index -> panel = PlayerPanel.Share(lines, index) },
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
            onDismiss = { panel = null },
            artwork = state.artworkUri,
            shareSelection = shareSelection,
            onShareSelection = { shareSelection = it },
            trackPanel = { palette ->
                state.error?.let { error -> androidx.compose.material3.Text(error, Modifier.padding(16.dp), color = palette.label) }
                hit?.let { song ->
                    io.github.kid141252010.fantastic.ui.MediaActionContent(song, library, listOfNotNull(menuTrack), nowPlaying = true,
                        hasLyrics = lyricsStatus == LyricsController.Status.Ready,
                        onEntities = openEntities.takeIf { entities.isNotEmpty() },
                        onQuality = { panel = PlayerPanel.Quality },
                        onShareLyrics = if (lyricsStatus == LyricsController.Status.Ready) ({
                            panel = PlayerPanel.Share(lyrics.shareLines().map { LyricShareLine(it, null) })
                        }) else null,
                        pronunciation = pronunciation,
                        onPronunciation = if (pane == PlayerPane.Lyrics && annotations.pronunciation) ({ onPronunciationChange(!pronunciation); panel = null }) else null,
                        translation = translation,
                        onTranslation = if (pane == PlayerPane.Lyrics && annotations.translation) ({ onTranslationChange(!translation); panel = null }) else null,
                        onDismiss = { panel = null })
                }
            },
            entityPanel = { palette ->
                CatalogActionContent(entities, palette, onOpen = { entity -> panel = null; onOpenCatalog(entity) })
            },
            qualityState = state,
            onOpenSettings = { panel = null; onOpenSettings() }
        )
    }
}


