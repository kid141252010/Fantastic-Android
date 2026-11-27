package io.github.kid141252010.fantastic.ui.player

import android.content.res.ColorStateList
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.accessibility.AccessibilityManager
import android.widget.ProgressBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.core.view.ViewCompat
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.theme.LocalFontWeights
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val PlayerControlsHeight = .345f

private enum class CoverSlot { DragRegion, MetadataProbe, Metadata, Artwork, Controls }

private enum class LyricsControlsMode { Shown, HiddenByScroll, HiddenByTimeout }

private val ControlsFadeIn = fadeIn(tween(500, easing = Easing { it * it }))
private val ControlsFadeOut = fadeOut(tween(500, easing = Easing { 1f - (1f - it) * (1f - it) }))
private val ControlsMaskFade = tween<Float>(500,
    easing = Easing(AccelerateDecelerateInterpolator()::getInterpolation))

/** The square fits between the 24dp guides and the title's −44dp barrier. */
@Composable
internal fun CoverPane(
    state: PlayerState,
    artwork: @Composable (Modifier) -> Unit,
    marquee: Boolean,
    drag: Modifier,
    favorite: Boolean,
    onFavorite: (() -> Unit)?,
    onMore: () -> Unit,
    onOpenEntities: (() -> Unit)?,
    controls: @Composable (Modifier) -> Unit,
    contentFade: Modifier,
) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val wide = width >= 600.dp.roundToPx()
        val controlsHeight = (height * PlayerControlsHeight).roundToInt()
        val controlsTop = height - controlsHeight
        val dragRegion = subcompose(CoverSlot.DragRegion) { Box(Modifier.fillMaxSize().then(drag)) }.single()
            .measure(Constraints.fixed(width, controlsTop))
        val metadataBottom = (controlsTop - 16.dp.roundToPx()).coerceAtLeast(0)
        val artworkMaxWidth = (width - 2 * 24.dp.roundToPx()).coerceAtLeast(0)
        val barrier = 44.dp.roundToPx()

        // Metadata is always two single-line labels. The wide layout ties its width to the square,
        // so read their height before solving the square and measuring the actual metadata/buttons.
        val wideArtwork = if (wide) {
            val labels = subcompose(CoverSlot.MetadataProbe) {
                PlayerMetadataText(state.title, state.artist, compact = false, marquee = false,
                    modifier = Modifier.clearAndSetSemantics {})
            }.single().measure(Constraints(maxWidth = artworkMaxWidth, maxHeight = metadataBottom))
            minOf(artworkMaxWidth, (metadataBottom - labels.height - barrier).coerceAtLeast(0))
        } else 0
        val metadataWidth = if (wide) wideArtwork else artworkMaxWidth
        val metadata = subcompose(CoverSlot.Metadata) {
            Row(Modifier.fillMaxWidth().then(drag).then(contentFade).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerMetadataText(state.title, state.artist, compact = false, marquee = marquee,
                    modifier = Modifier.weight(1f), onOpenEntities = onOpenEntities)
                TrackHeaderActions(favorite, onFavorite, onMore, compact = false)
            }
        }.single().measure(Constraints(minWidth = metadataWidth, maxWidth = metadataWidth, maxHeight = metadataBottom))
        val metadataTop = metadataBottom - metadata.height
        val artworkBottom = (metadataTop - barrier).coerceAtLeast(0)
        val square = if (wide) wideArtwork else minOf(artworkMaxWidth, artworkBottom)
        val artworkSlot = if (square > 0) subcompose(CoverSlot.Artwork) {
            artwork(Modifier.fillMaxSize())
        }.single().measure(Constraints.fixed(square, square)) else null
        val transportWidth = if (wide && square > 0) square else width
        val transport = subcompose(CoverSlot.Controls) { controls(Modifier.fillMaxSize()) }.single()
            .measure(Constraints.fixed(transportWidth, controlsHeight))
        layout(width, height) {
            dragRegion.placeRelative(0, 0)
            artworkSlot?.placeRelative((width - square) / 2, ((artworkBottom - square) / 2).coerceAtLeast(0))
            metadata.placeRelative((width - metadataWidth) / 2, metadataTop)
            transport.placeRelative((width - transportWidth) / 2, controlsTop)
        }
    }
}

@Composable
internal fun CurrentTrackHeader(
    state: PlayerState,
    artwork: @Composable (Modifier) -> Unit,
    marquee: Boolean,
    favorite: Boolean,
    onFavorite: (() -> Unit)?,
    onMore: () -> Unit,
    onOpenEntities: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentFade: Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork(Modifier.size(60.dp))
        PlayerMetadataText(state.title, state.artist, compact = true, marquee = marquee,
            modifier = Modifier.weight(1f).padding(start = 8.dp).then(contentFade), onOpenEntities = onOpenEntities)
        TrackHeaderActions(favorite, onFavorite, onMore, compact = true, modifier = contentFade)
    }
}

@Composable
private fun TrackHeaderActions(
    favorite: Boolean,
    onFavorite: (() -> Unit)?,
    onMore: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val vertical = if (compact) 16.dp else 0.dp
    HeaderAction(
        PlayerSymbol.Star, if (favorite) "取消喜爱" else "喜爱",
        PaddingValues(start = if (compact) 16.dp else 8.dp, end = 8.dp, top = vertical, bottom = vertical),
        modifier = modifier,
        tint = if (onFavorite != null) PlayerPrimary else Color(0x14FFFFFF), checked = favorite, enabled = onFavorite != null,
    ) { onFavorite?.invoke() }
    HeaderAction(
        PlayerSymbol.More, "更多选项",
        PaddingValues(end = 16.dp, top = vertical, bottom = vertical), modifier = modifier,
        tint = if (compact) PlayerSecondary else PlayerPrimary, onClick = onMore,
    )
}

@Composable
internal fun LyricsPlayerPane(
    lyrics: LyricsController,
    mediaId: String?,
    playing: Boolean,
    status: LyricsController.Status,
    error: String?,
    controlsHeight: Dp,
    interactive: Boolean,
    translation: Boolean,
    pronunciation: Boolean,
    translationAvailable: Boolean,
    pronunciationAvailable: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onSheetDragBy: (Float) -> Boolean,
    onSheetScrollEnd: (Float, Float) -> Boolean,
    header: @Composable () -> Unit,
    controls: @Composable (Modifier) -> Unit,
    contentFade: Modifier,
) {
    val lyricFontWeight = LocalFontWeights.current.lyrics
    var controlsMode by remember(mediaId) { mutableStateOf(LyricsControlsMode.Shown) }
    var popupExpanded by remember(mediaId) { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var headerHeight by remember { mutableIntStateOf(0) }
    val touchExploration = touchExplorationActive()
    val controlsVisible = controlsMode == LyricsControlsMode.Shown || status != LyricsController.Status.Ready || touchExploration
    val controlsTransition = updateTransition(controlsVisible, label = "Lyrics controls")
    val controlsMaskVisibility by controlsTransition.animateFloat(
        transitionSpec = { ControlsMaskFade }, label = "Lyrics bottom fade",
    ) { visible -> if (visible) 1f else 0f }
    val onLyricsScroll: (() -> Unit)? = remember(interactive, touchExploration, mediaId) {
        if (interactive && !touchExploration) ({ controlsMode = LyricsControlsMode.HiddenByScroll }) else null
    }
    val onControlsTap: (() -> Unit)? = remember(interactive, mediaId) {
        if (interactive) ({ controlsMode = LyricsControlsMode.Shown }) else null
    }
    LaunchedEffect(playing, controlsMode, controlsVisible, pointerDown, popupExpanded, interactive, status, mediaId, touchExploration) {
        if ((playing || controlsMode == LyricsControlsMode.HiddenByScroll) &&
            controlsMode != LyricsControlsMode.HiddenByTimeout && interactive && !pointerDown && !popupExpanded &&
            status == LyricsController.Status.Ready && !touchExploration) {
            delay(3000)
            controlsMode = LyricsControlsMode.HiddenByTimeout
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        // Pause the auto-hide timer while the user is touching the lyrics, buttons or scrubber.
        awaitPointerEventScope {
            while (true) pointerDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
        }
    }) {
        val density = LocalDensity.current
        val controlInsetPx = with(density) { controlsHeight.roundToPx() }
        val tapTargetY = with(density) { (maxHeight * .425f).roundToPx() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.onSizeChanged { headerHeight = it.height }) { header() }
            Box(Modifier.weight(1f).fillMaxWidth().then(contentFade)) {
                when (status) {
                    LyricsController.Status.Ready -> AndroidView(
                        factory = lyrics::createView,
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            view.setFontWeight(lyricFontWeight)
                            view.onSheetDragBy = onSheetDragBy
                            view.onSheetScrollEnd = onSheetScrollEnd
                            if (view.isNestedScrollingEnabled != interactive) {
                                if (!interactive) view.stopNestedScroll(ViewCompat.TYPE_TOUCH)
                                view.isNestedScrollingEnabled = interactive
                            }
                            view.setControlsInset(controlInsetPx, controlsMaskVisibility)
                            view.onUserScroll = onLyricsScroll
                            // Only timeout-hidden transport takes priority over lower-zone lyric touches.
                            // After user scrolling, the same tap may reveal transport without blocking the row.
                            view.setControlsTapTarget((tapTargetY - headerHeight).coerceAtLeast(0),
                                controlsMode == LyricsControlsMode.HiddenByTimeout && !controlsVisible,
                                onControlsTap)
                        },
                    )
                    else -> Box(
                        Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = controlsHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (status) {
                            LyricsController.Status.Loading -> AndroidView(
                                factory = { context -> ProgressBar(context).apply {
                                    isIndeterminate = true
                                    indeterminateTintList = ColorStateList.valueOf(0x4DFFFFFF)
                                    contentDescription = "正在加载歌词"
                                } },
                                modifier = Modifier.size(42.dp),
                            )
                            LyricsController.Status.Failed -> Text(
                                error ?: "歌词加载失败", color = PlayerSecondary, fontSize = 17.sp,
                                textAlign = TextAlign.Center,
                            )
                            else -> Text(
                                "歌词不可用", color = PlayerSecondary,
                                style = LocalTextStyle.current.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                    .withUserFontWeight(FontWeightRole.Title), textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        controlsTransition.AnimatedVisibility(visible = { it },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(controlsHeight),
            enter = ControlsFadeIn, exit = ControlsFadeOut) {
            controls(Modifier.fillMaxSize().panelTouchBoundary())
        }
        controlsTransition.AnimatedVisibility(visible = { it && (translationAvailable || pronunciationAvailable) },
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = controlsHeight + 27.dp).then(contentFade),
            enter = ControlsFadeIn, exit = ControlsFadeOut) {
            LyricsAnnotationLauncher(
                translation = translation,
                pronunciation = pronunciation,
                translationAvailable = translationAvailable,
                pronunciationAvailable = pronunciationAvailable,
                onTranslationChange = onTranslationChange,
                onPronunciationChange = onPronunciationChange,
                expanded = popupExpanded && interactive,
                onExpandedChange = { if (interactive) popupExpanded = it },
            )
        }
    }
}

@Composable
private fun touchExplorationActive(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var active by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { active = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return active
}
