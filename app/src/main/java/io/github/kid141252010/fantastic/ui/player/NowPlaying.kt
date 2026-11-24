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
import io.github.kid141252010.fantastic.data.librarySongHit
import io.github.kid141252010.fantastic.data.toTrack
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

private fun String.isMissingCatalogId() = isBlank() || this == "0"

@Composable
internal fun NowPlayingContent(
    state: PlayerState,
    player: PlayerConnection,
    lyrics: LyricsController,
    library: MusicLibrary,
    apple: AppleCatalogApi,
    qq: io.github.kid141252010.fantastic.data.QqApi,
    netease: io.github.kid141252010.fantastic.data.NeteaseApi,
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
    val libraryState by library.snapshot.collectAsStateWithLifecycle()
    val rawTrack = state.currentTrack
    var menuTrack by remember(rawTrack) { mutableStateOf(rawTrack) }
    val context = LocalContext.current
    val app = context.applicationContext as FantasticApp
    val trackMenuOpen = panel == PlayerPanel.Track || panel == PlayerPanel.Entities
    LaunchedEffect(trackMenuOpen, rawTrack, apple, qq, netease) {
        if (!trackMenuOpen || rawTrack == null) return@LaunchedEffect
        val current = menuTrack ?: return@LaunchedEffect
        if (!current.artistId.isMissingCatalogId() && !current.albumId.isMissingCatalogId() &&
            !current.artistArtworkTemplate.isNullOrBlank()) return@LaunchedEffect

        suspend fun <T> lookup(action: suspend () -> T): T? = try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("FantasticCatalog", "${rawTrack.source} entity lookup failed for ${rawTrack.id}", error)
            null
        }

        val details = lookup {
            when (rawTrack.source) {
                Source.Apple -> apple.entity(AppleCatalogApi.Entity.Song, rawTrack.id).toTrack()
                Source.QQ -> qq.songTrack(rawTrack.id)
                Source.Netease -> netease.song(rawTrack.id).song.toTrack()
            }
        }?.takeIf { it.source == rawTrack.source && it.id == rawTrack.id }
        val artistId = current.artistId.takeUnless { it.isMissingCatalogId() }
            ?: details?.artistId.orEmpty()
        val artwork = current.artistArtworkTemplate?.takeIf { it.isNotBlank() }
            ?: details?.takeIf { it.artistId == artistId }?.artistArtworkTemplate?.takeIf { it.isNotBlank() }
        val updated = current.copy(
            artist = current.artist.ifBlank { details?.artist.orEmpty() },
            album = current.album.ifBlank { details?.album.orEmpty() },
            artworkTemplate = current.artworkTemplate?.takeIf { it.isNotBlank() } ?: details?.artworkTemplate,
            durationMs = current.durationMs.takeIf { it > 0 } ?: details?.durationMs ?: 0,
            artistId = artistId,
            albumId = current.albumId.takeUnless { it.isMissingCatalogId() } ?: details?.albumId.orEmpty(),
            artistArtworkTemplate = artwork,
            albumArtist = current.albumArtist.ifBlank { details?.albumArtist.orEmpty() },
            discNumber = current.discNumber.takeIf { it > 0 } ?: details?.discNumber ?: 0,
            trackNumber = current.trackNumber.takeIf { it > 0 } ?: details?.trackNumber ?: 0,
        )
        ensureActive()
        if (player.state.value.mediaId != rawTrack.mediaId) return@LaunchedEffect
        menuTrack = updated
        if (artwork == null && !artistId.isMissingCatalogId()) {
            val avatar = lookup {
                when (rawTrack.source) {
                    Source.Apple -> apple.entity(AppleCatalogApi.Entity.Artist, artistId).artworkUrl(500)
                    Source.QQ -> null
                    Source.Netease -> netease.artist(artistId).artist.takeIf { it.mid == artistId }?.avatar
                }
            }?.takeIf { it.isNotBlank() }
            ensureActive()
            if (avatar != null && player.state.value.mediaId == rawTrack.mediaId) {
                menuTrack = updated.copy(artistArtworkTemplate = avatar)
            }
        }
    }
    val hit = remember(menuTrack) {
        menuTrack?.let { CatalogHit(it.source, "song", it.id, it.title, it.artist, it.artwork(500), it, detailOnly = false) }
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
    val entities = remember(menuTrack) {
        buildList {
            menuTrack?.let { song ->
                if (!song.artistId.isMissingCatalogId()) add(CatalogHit(
                    song.source, "artist", song.artistId, song.artist, artwork = song.artistArtwork(500), detailOnly = true))
                if (!song.albumId.isMissingCatalogId()) add(CatalogHit(
                    song.source, "album", song.albumId, song.album, song.albumArtist, artwork = song.artwork(500), detailOnly = true))
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
            trackPanel = { palette ->
                state.error?.let { error -> androidx.compose.material3.Text(error, Modifier.padding(16.dp), color = palette.label) }
                hit?.let { song ->
                    io.github.kid141252010.fantastic.ui.MediaActionContent(song, library, listOfNotNull(menuTrack), nowPlaying = true,
                        hasLyrics = lyricsStatus == LyricsController.Status.Ready,
                        onQuality = { panel = PlayerPanel.Quality },
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


