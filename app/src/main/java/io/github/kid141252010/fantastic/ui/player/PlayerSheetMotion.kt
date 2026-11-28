package io.github.kid141252010.fantastic.ui.player

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.AppleMotionArtwork
import io.github.kid141252010.fantastic.data.AppleMotionVariant
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.MusicLibrary
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.CircularTouchScope
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class PlayerSheetDetent { Expanded, Collapsed, Hidden }
private enum class SheetDragOrigin { Full, Mini }

internal data class PlayerSheetFrame(
    val detent: PlayerSheetDetent,
    val offsetPx: Float,
    val expandedTopPx: Float,
    val collapsedTopPx: Float,
    val hiddenTopPx: Float,
    val progress: Float,
) {
    val contentAlpha: Float
        get() = if (progress > 0f) (1f + .5f * ln(progress)).coerceIn(0f, 1f) else 0f

    val miniAlpha: Float
        get() = exp(-300f * progress)
}

private const val PlayerSheetHideFriction = .1f
private const val PlayerSheetDefaultSignificantVelocity = 500f

// Quintic deceleration for settling between player positions.
internal val PlayerSheetSettleEasing: Easing = Easing { fraction ->
    val t = fraction - 1f
    t * t * t * t * t + 1f
}

private fun playerSheetReleaseVelocity(velocity: Float, configuration: ViewConfiguration): Float {
    val speed = abs(velocity)
    if (speed < configuration.scaledMinimumFlingVelocity) return 0f
    return velocity.coerceIn(
        -configuration.scaledMaximumFlingVelocity.toFloat(),
        configuration.scaledMaximumFlingVelocity.toFloat(),
    )
}

/** Choose the release position from velocity, drag distance, and content bounds. */
internal fun playerSheetReleaseTarget(
    topPx: Float,
    xVelocity: Float,
    yVelocity: Float,
    expandedTopPx: Float,
    collapsedTopPx: Float,
    hiddenTopPx: Float,
    sheetRangePx: Float,
    fitToContents: Boolean = true,
    hideable: Boolean = true,
    significantVelocity: Float = PlayerSheetDefaultSignificantVelocity,
): PlayerSheetDetent {
    if (yVelocity < 0f) return PlayerSheetDetent.Expanded

    val top = topPx.coerceIn(expandedTopPx, hiddenTopPx)
    val range = sheetRangePx.coerceAtLeast(1f)
    val shouldHide = hideable && top >= collapsedTopPx &&
        abs(top + yVelocity * PlayerSheetHideFriction - collapsedTopPx) / range > .5f
    if (shouldHide) {
        if (abs(xVelocity) < abs(yVelocity) && yVelocity > significantVelocity) {
            return PlayerSheetDetent.Hidden
        }
        return if (top > (expandedTopPx + hiddenTopPx) / 2f) {
            PlayerSheetDetent.Hidden
        } else if (fitToContents) {
            PlayerSheetDetent.Expanded
        } else {
            PlayerSheetDetent.Collapsed
        }
    }

    if (yVelocity == 0f || abs(xVelocity) > abs(yVelocity)) {
        return if (abs(top - expandedTopPx) < abs(top - collapsedTopPx)) {
            PlayerSheetDetent.Expanded
        } else {
            PlayerSheetDetent.Collapsed
        }
    }
    return PlayerSheetDetent.Collapsed
}

private fun playerSheetAxisSettleDuration(
    distance: Float,
    velocity: Float,
    parentSizePx: Int,
    rangePx: Float,
): Int {
    if (distance == 0f) return 0
    val size = parentSizePx.coerceAtLeast(1)
    val halfSize = (size / 2).toFloat()
    val ratio = (abs(distance) / size).coerceAtMost(1f)
    val influencedDistance = halfSize + halfSize * sin((ratio - .5f) * .47123894f)
    val speed = abs(velocity)
    val duration = if (speed > 0f) {
        (abs(influencedDistance / speed) * 1000f).roundToInt() * 4
    } else {
        ((abs(distance) / rangePx.coerceAtLeast(1f) + 1f) * 256f).toInt()
    }
    return duration.coerceAtMost(600)
}

// Weight each axis by distance or velocity and cap settling at 600ms.
internal fun playerSheetSettleDuration(
    distanceX: Float,
    distanceY: Float,
    velocityX: Float,
    velocityY: Float,
    parentWidthPx: Int,
    verticalDistanceReferencePx: Int,
    verticalDragRangePx: Float,
): Int {
    val totalDistance = abs(distanceX) + abs(distanceY)
    if (totalDistance == 0f) return 0
    val totalVelocity = abs(velocityX) + abs(velocityY)
    val weightX = if (velocityX != 0f && totalVelocity > 0f) {
        abs(velocityX) / totalVelocity
    } else {
        abs(distanceX) / totalDistance
    }
    val weightY = if (velocityY != 0f && totalVelocity > 0f) {
        abs(velocityY) / totalVelocity
    } else {
        abs(distanceY) / totalDistance
    }
    val durationX = playerSheetAxisSettleDuration(
        distanceX, velocityX, parentWidthPx, parentWidthPx.toFloat(),
    )
    val durationY = playerSheetAxisSettleDuration(
        distanceY, velocityY, verticalDistanceReferencePx, verticalDragRangePx,
    )
    return (durationX * weightX + durationY * weightY).toInt().coerceAtMost(600)
}

private val SheetCorner = 16.dp

private fun PlayerSheetDetent.top(frame: PlayerSheetFrame): Float = when (this) {
    PlayerSheetDetent.Expanded -> frame.expandedTopPx
    PlayerSheetDetent.Collapsed -> frame.collapsedTopPx
    PlayerSheetDetent.Hidden -> frame.hiddenTopPx
}

private val PlayerSheetDetent.slide: Float
    get() = when (this) {
        PlayerSheetDetent.Expanded -> 1f
        PlayerSheetDetent.Collapsed -> 0f
        PlayerSheetDetent.Hidden -> -1f
    }

private fun PlayerSheetFrame.topAt(slide: Float): Float = if (slide >= 0f) {
    expandedTopPx + (1f - slide) * (collapsedTopPx - expandedTopPx)
} else {
    collapsedTopPx - slide * (hiddenTopPx - collapsedTopPx)
}

private fun PlayerSheetFrame.slideAt(top: Float, target: PlayerSheetDetent): Float {
    val openRange = collapsedTopPx - expandedTopPx
    val hideRange = hiddenTopPx - collapsedTopPx
    return when {
        top < collapsedTopPx -> ((collapsedTopPx - top) / openRange).coerceAtMost(1f)
        top > collapsedTopPx -> ((collapsedTopPx - top) / hideRange).coerceAtLeast(-1f)
        openRange == 0f && target == PlayerSheetDetent.Expanded -> target.slide
        hideRange == 0f && target == PlayerSheetDetent.Hidden -> target.slide
        else -> 0f
    }
}

private val PaneArtworkEasing = CubicBezierEasing(.2f, .06f, 0f, 1f)
private val PaneArtworkBounds = tween<Rect>(300, easing = PaneArtworkEasing)
private val PaneArtworkStyle = tween<ArtworkStyle>(300, easing = PaneArtworkEasing)
private const val TallArtworkAspectRatio = .75f

private data class ArtworkStyle(val cover: Float, val queue: Float, val immersive: Float)

private val ArtworkStyleConverter = TwoWayConverter(
    convertToVector = { style: ArtworkStyle -> AnimationVector3D(style.cover, style.queue, style.immersive) },
    convertFromVector = { ArtworkStyle(it.v1, it.v2, it.v3) },
)

private data class ArtworkSlotBounds(val coordinates: LayoutCoordinates, val rootSize: IntSize, val rect: Rect)

private fun ArtworkSlotBounds?.rectIn(rootSize: IntSize): Rect? =
    this?.takeIf { it.rootSize == rootSize && it.coordinates.isAttached }?.rect

private fun LayoutCoordinates.artworkSlotIn(root: LayoutCoordinates?): ArtworkSlotBounds? {
    if (root == null || !root.isAttached || !isAttached || size.width <= 0 || size.height <= 0) return null
    val topLeft = root.localPositionOf(this, Offset.Zero)
    val right = topLeft.x + size.width
    val bottom = topLeft.y + size.height
    if (!topLeft.x.isFinite() || !topLeft.y.isFinite() || !right.isFinite() || !bottom.isFinite() ||
        right <= topLeft.x || bottom <= topLeft.y) return null
    return ArtworkSlotBounds(this, root.size, Rect(topLeft.x, topLeft.y, right, bottom))
}

@Composable
private fun PlayerSheetArtwork(
    artwork: Bitmap?,
    variant: AppleMotionVariant?,
    immersive: Boolean,
    videoAspectRatio: Float,
    trackPlaying: Boolean,
    hostSize: IntSize,
    pane: PlayerPane,
    fullTarget: Rect?,
    mini: Rect?,
    progress: Float,
    chromeVisible: Boolean,
    miniInteractive: Boolean,
    unobscured: Boolean,
    createDragModifier: (() -> SheetDragOrigin?) -> Modifier,
    onOpen: () -> Unit,
    onCover: () -> Unit,
    captureMotionFrame: Boolean,
    onMotionFrame: (Bitmap?) -> Unit,
    onMotionError: (String) -> Unit,
) {
    val fullBounds = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val fullStyle = remember { Animatable(ArtworkStyle(0f, 0f, 0f), ArtworkStyleConverter) }
    var measuredPane by remember { mutableStateOf<PlayerPane?>(null) }
    var measuredImmersive by remember { mutableStateOf(false) }
    var measuredSize by remember { mutableStateOf(hostSize) }
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val displayCutout = WindowInsets.displayCutout
    val insets = remember(statusBars, navigationBars, displayCutout) {
        statusBars.union(navigationBars).union(displayCutout)
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insetPixels = IntRect(
        insets.getLeft(density, direction), insets.getTop(density),
        insets.getRight(density, direction), insets.getBottom(density),
    )
    var measuredInsets by remember { mutableStateOf(insetPixels) }
    LaunchedEffect(pane, fullTarget, hostSize, insetPixels, immersive) {
        val target = fullTarget ?: return@LaunchedEffect
        val style = when (pane) {
            PlayerPane.Cover -> if (immersive) ArtworkStyle(0f, 0f, 1f) else ArtworkStyle(1f, 0f, 0f)
            PlayerPane.Lyrics -> ArtworkStyle(0f, 0f, 0f)
            PlayerPane.Queue -> ArtworkStyle(0f, 1f, 0f)
        }
        val animate = measuredPane != null && (measuredPane != pane || measuredImmersive != immersive) &&
            measuredSize == hostSize && measuredInsets == insetPixels
        measuredPane = pane
        measuredImmersive = immersive
        measuredSize = hostSize
        measuredInsets = insetPixels
        if (animate) {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) { fullStyle.animateTo(style, PaneArtworkStyle) }
                fullBounds.animateTo(target, PaneArtworkBounds)
            }
        } else {
            fullBounds.snapTo(target)
            fullStyle.snapTo(style)
        }
    }

    val full = fullBounds.value
    val artworkProgress = if (full.width > 0f && full.height > 0f) progress else 0f
    val visible = hostSize.width > 0 && hostSize.height > 0 && (progress > 0f || chromeVisible)
    val bounds = if (visible && mini != null) when (artworkProgress) {
        0f -> mini
        1f -> full
        else -> lerp(mini, full, artworkProgress)
    } else null
    val videoBaseWidthPx = with(density) {
        val width = if (videoAspectRatio == TallArtworkAspectRatio) hostSize.width else hostSize.width - 48.dp.roundToPx()
        width.coerceAtLeast(1)
    }
    val style = fullStyle.value
    val radius = (5f + (5f * style.cover - 5f * style.immersive) * artworkProgress).dp
    val coverElevation = if (trackPlaying) 15f else 8f
    val elevation = ((coverElevation * style.cover + 2f * style.queue) * artworkProgress).dp
    val artworkShape = remember(radius) { RoundedCornerShape(radius) }
    val immersiveWeight = style.immersive * artworkProgress
    val immersiveLayer = immersiveWeight > 0f
    val originState = rememberUpdatedState<SheetDragOrigin?>(
        when {
            bounds == null || !chromeVisible || !unobscured -> null
            progress == 0f && miniInteractive -> SheetDragOrigin.Mini
            progress > 0f && !immersiveLayer -> SheetDragOrigin.Full
            else -> null
        },
    )
    val artworkDragModifier = remember { createDragModifier { originState.value } }
    val bottomFade = if (immersiveLayer) Modifier.drawWithCache {
        val mask = Brush.verticalGradient(
            colors = listOf(Color.White, Color.White.copy(alpha = 1f - immersiveWeight)),
            startY = .75f * size.height,
            endY = size.height,
        )
        // Shade the art before its fade so the backdrop below it keeps its own colors.
        val shade = Color(0xB3171717)
        val legibility = Brush.verticalGradient(
            0f to shade.copy(alpha = shade.alpha * .2f),
            .2f to shade.copy(alpha = 0f),
            1f to shade,
            endY = hostSize.height.toFloat(),
        )
        onDrawWithContent {
            drawContent()
            drawRect(legibility, alpha = immersiveWeight)
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    } else Modifier
    val left = bounds?.left?.roundToInt() ?: 0
    val top = bounds?.top?.roundToInt() ?: 0
    val showMotion = bounds != null && artworkProgress > 0f && pane == PlayerPane.Cover
    val click = when {
        bounds == null || !chromeVisible || !unobscured -> Modifier.clearAndSetSemantics {}
        progress == 0f -> Modifier.semantics { contentDescription = "打开正在播放" }
            .clickable(role = Role.Button, onClick = onOpen)
        pane != PlayerPane.Cover -> Modifier.semantics { contentDescription = "专辑封面" }
            .clickable(role = Role.Button, onClick = onCover)
        else -> Modifier
    }

    CircularTouchScope {
        Layout(
            modifier = Modifier.zIndex(if (immersiveLayer) 2f else 4f).absoluteOffset { IntOffset(left, top) }
                .then(artworkDragModifier).then(click)
                .graphicsLayer {
                    translationX = (bounds?.left ?: 0f) - left
                    translationY = (bounds?.top ?: 0f) - top
                    shape = artworkShape
                    clip = true
                    shadowElevation = elevation.toPx()
                    compositingStrategy = if (immersiveLayer) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }.then(bottomFade),
            content = {
                PlayerMotionArtwork(
                    artwork = artwork,
                    variant = variant,
                    videoAspectRatio = videoAspectRatio,
                    videoBaseWidthPx = videoBaseWidthPx,
                    playing = showMotion && unobscured,
                    modifier = Modifier.fillMaxSize(),
                    stroke = artworkProgress == 1f && pane != PlayerPane.Cover,
                    showMotion = showMotion,
                    captureMotionFrame = captureMotionFrame,
                    onMotionFrame = onMotionFrame,
                    onMotionError = onMotionError,
                )
            },
        ) { measurables, _ ->
            if (bounds == null) {
                layout(0, 0) {}
            } else {
                val width = bounds.width.roundToInt().coerceAtLeast(1)
                val height = bounds.height.roundToInt().coerceAtLeast(1)
                val image = measurables.single().measure(Constraints.fixed(width, height))
                layout(width, height) { image.place(0, 0) }
            }
        }
    }
}

@Composable
internal fun PlayerSheetHost(
    state: PlayerState,
    player: PlayerConnection,
    lyrics: LyricsController,
    library: MusicLibrary,
    apple: AppleCatalogApi,
    pane: PlayerPane,
    onPaneChange: (PlayerPane) -> Unit,
    translation: Boolean,
    pronunciation: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onOpenCatalog: (CatalogHit) -> Unit,
    relationNavigationAllowed: () -> Boolean,
    onOpenSettings: () -> Unit,
    initiallyExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    chromeVisible: Boolean,
    onBackOwnershipChange: (Boolean) -> Unit,
    bottomBar: @Composable (PlayerSheetFrame, Boolean) -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val viewConfiguration = remember(context) { ViewConfiguration.get(context) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bottomPeekPx = with(density) { LocalBarsPadding.current.calculateBottomPadding().toPx() }
    val hasTrack = state.mediaId != null
    val initialDetent = if (initiallyExpanded) PlayerSheetDetent.Expanded else PlayerSheetDetent.Collapsed
    var slide by remember { mutableFloatStateOf(initialDetent.slide) }
    var currentDetent by remember { mutableStateOf(initialDetent) }
    var targetDetent by remember { mutableStateOf(initialDetent) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    var activeDrag by remember { mutableStateOf<Int?>(null) }
    var hadTrack by remember { mutableStateOf(false) }
    var queueAtTop by remember { mutableStateOf(true) }
    val dragAnimation = remember { Animatable(0f) }

    val sheetWidthPx = constraints.maxWidth
    val sheetHeightPx = constraints.maxHeight
    val hostSize = IntSize(sheetWidthPx, sheetHeightPx)
    val collapsedTopPx = (sheetHeightPx - bottomPeekPx).coerceAtLeast(0f)
    val hiddenTopPx = sheetHeightPx.toFloat()
    val frame = PlayerSheetFrame(
        detent = currentDetent,
        offsetPx = if (slide >= 0f) (1f - slide) * collapsedTopPx else
            collapsedTopPx - slide * (hiddenTopPx - collapsedTopPx),
        expandedTopPx = 0f,
        collapsedTopPx = collapsedTopPx,
        hiddenTopPx = hiddenTopPx,
        progress = if (hasTrack) slide.coerceIn(0f, 1f) else 0f,
    )

    val renderFrame = rememberUpdatedState(frame)
    val contentFade = remember { Modifier.graphicsLayer { alpha = renderFrame.value.contentAlpha } }
    var hostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var sheetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var miniSlot by remember { mutableStateOf<ArtworkSlotBounds?>(null) }
    var tallSlot by remember { mutableStateOf<ArtworkSlotBounds?>(null) }
    val paneSlots = remember { mutableStateMapOf<PlayerPane, ArtworkSlotBounds>() }
    val fullArtworkSlot: @Composable (PlayerPane, Modifier) -> Unit = remember {
        { target, modifier ->
            Box(modifier.clearAndSetSemantics {}.onGloballyPositioned { coordinates ->
                coordinates.artworkSlotIn(sheetCoordinates)?.let { paneSlots[target] = it }
            })
        }
    }
    val miniArtworkSlot: @Composable (Modifier) -> Unit = remember {
        { modifier ->
            Box(modifier.clearAndSetSemantics {}.onGloballyPositioned { coordinates ->
                miniSlot = coordinates.artworkSlotIn(hostCoordinates)
            })
        }
    }
    var artworkUnobscured by remember(state.mediaId) { mutableStateOf(true) }
    val track = state.currentTrack
    val playerArtworkUri = if (hasTrack) remember(track, state.artworkUri, hostSize.width) {
        track?.artwork(hostSize.width.coerceAtLeast(1))?.takeIf { it.isNotBlank() } ?: state.artworkUri
    } else null
    val prewarmedArtwork = rememberPrewarmedArtwork(playerArtworkUri, state.artworkUri, hostSize)
    var motionArtwork by remember(track, apple) { mutableStateOf<AppleMotionArtwork?>(null) }
    var motionArtworkLoaded by remember(track, apple) { mutableStateOf(false) }
    var failedMotionUrls by remember(track, apple) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(track, apple, pane) {
        if (pane == PlayerPane.Cover && track?.source == Source.Apple && !motionArtworkLoaded) {
            try {
                val artwork = apple.playerMotionArtwork(track)
                ensureActive()
                motionArtwork = artwork
                motionArtworkLoaded = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("FantasticMotion", "Apple motion lookup failed for ${track.id}", error)
            }
        }
    }
    val tallWindow = sheetWidthPx > 0 && sheetHeightPx > 0 && sheetWidthPx < with(density) { 600.dp.roundToPx() } &&
        sheetWidthPx.toFloat() / sheetHeightPx in .40f..TallArtworkAspectRatio
    val tall = motionArtwork?.tall?.takeUnless { it.hlsMasterUrl in failedMotionUrls }
    val selectedTall = tallWindow && tall != null
    val variant = if (selectedTall) tall else motionArtwork?.square?.takeUnless { it.hlsMasterUrl in failedMotionUrls }
    val immersiveCover = pane == PlayerPane.Cover && selectedTall
    val selectedUrl = variant?.hlsMasterUrl
    val motionFrame = remember(track, apple, selectedUrl) { mutableStateOf<Bitmap?>(null) }
    val onMotionFrame = remember(motionFrame) {
        { sample: Bitmap? ->
            val previous = motionFrame.value
            if (previous !== sample) {
                motionFrame.value = sample
                previous?.recycle()
            }
        }
    }
    DisposableEffect(motionFrame) { onDispose { onMotionFrame(null) } }
    val currentTrack by rememberUpdatedState(track)
    val currentApple by rememberUpdatedState(apple)
    val currentUrl by rememberUpdatedState(selectedUrl)
    val onMotionError = remember(track, apple, selectedUrl) {
        { url: String ->
            if (track == currentTrack && apple === currentApple && url == selectedUrl && url == currentUrl &&
                url !in failedMotionUrls) {
                failedMotionUrls = failedMotionUrls + url
            }
        }
    }

    val notifyExpanded by rememberUpdatedState(onExpandedChange)
    val trackPresent by rememberUpdatedState(hasTrack)
    val trackPresentNow = rememberUpdatedState(hasTrack)
    val chromeVisibleNow = rememberUpdatedState(chromeVisible)
    fun cancelSettlement() {
        settleGeneration++
        settleJob?.cancel()
        settleJob = null
        activeDrag = null
    }

    fun animateToDetent(
        target: PlayerSheetDetent,
        xVelocity: Float = 0f,
        yVelocity: Float = 0f,
        dragOrigin: SheetDragOrigin? = null,
    ) {
        if (!trackPresent) return
        if (activeDrag == null && currentDetent == target && slide == target.slide) {
            if (targetDetent != target || settleJob?.isActive == true) {
                cancelSettlement()
                targetDetent = target
            }
            notifyExpanded(target == PlayerSheetDetent.Expanded)
            return
        }
        cancelSettlement()
        targetDetent = target
        val generation = settleGeneration
        val currentFrame = renderFrame.value
        val start = currentFrame.topAt(slide)
        val targetTop = target.top(currentFrame)

        fun finish() {
            if (!trackPresent || generation != settleGeneration) return
            slide = target.slide
            currentDetent = target
            targetDetent = target
            settleJob = null
            notifyExpanded(target == PlayerSheetDetent.Expanded)
        }

        if (abs(targetTop - start) < .5f) {
            finish()
            return
        }
        val duration = playerSheetSettleDuration(
            distanceX = 0f,
            distanceY = targetTop - start,
            velocityX = xVelocity,
            velocityY = yVelocity,
            parentWidthPx = sheetWidthPx,
            verticalDistanceReferencePx = if (dragOrigin == SheetDragOrigin.Mini) sheetWidthPx else sheetHeightPx,
            verticalDragRangePx = if (dragOrigin == SheetDragOrigin.Mini) currentFrame.collapsedTopPx else sheetHeightPx.toFloat(),
        )
        settleJob = scope.launch {
            dragAnimation.snapTo(start)
            dragAnimation.animateTo(targetTop, tween(duration, easing = PlayerSheetSettleEasing)) {
                if (trackPresent && generation == settleGeneration) {
                    slide = renderFrame.value.slideAt(value, target)
                }
            }
            finish()
        }
    }
    val currentOpenCatalog by rememberUpdatedState(onOpenCatalog)
    val shellRelationNavigationAllowed by rememberUpdatedState(relationNavigationAllowed)
    val canCommitRelationNavigation by rememberUpdatedState<() -> Boolean> {
        shellRelationNavigationAllowed() && trackPresent &&
            currentDetent == PlayerSheetDetent.Expanded && targetDetent == PlayerSheetDetent.Expanded &&
            activeDrag == null && settleJob?.isActive != true
    }
    val collapse by rememberUpdatedState { animateToDetent(PlayerSheetDetent.Collapsed) }
    val openCatalogFromPlayer: (CatalogHit) -> Unit = remember {
        { target ->
            currentOpenCatalog(target)
            collapse()
        }
    }

    val open by rememberUpdatedState<() -> Unit> {
        if (trackPresent && chromeVisible && (targetDetent != PlayerSheetDetent.Expanded || activeDrag != null)) {
            notifyExpanded(true)
            animateToDetent(PlayerSheetDetent.Expanded)
        }
    }
    val openFromMini: () -> Unit = remember { { open() } }
    val sheetAtDetent = slide == PlayerSheetDetent.Expanded.slide ||
        slide == PlayerSheetDetent.Collapsed.slide || slide == PlayerSheetDetent.Hidden.slide
    fun chromeInteractiveNow(): Boolean = chromeVisibleNow.value &&
        slide == PlayerSheetDetent.Collapsed.slide &&
        currentDetent == PlayerSheetDetent.Collapsed &&
        targetDetent == PlayerSheetDetent.Collapsed &&
        activeDrag == null && settleJob?.isActive != true
    val chromeInteractive = chromeInteractiveNow()
    val playerOwnsBack = trackPresent && chromeVisible &&
        (!sheetAtDetent || activeDrag != null || settleJob?.isActive == true ||
            targetDetent == PlayerSheetDetent.Expanded)
    val notifyBackOwnership by rememberUpdatedState(onBackOwnershipChange)
    SideEffect { onBackOwnershipChange(playerOwnsBack) }
    DisposableEffect(Unit) { onDispose { notifyBackOwnership(false) } }
    BackHandler(enabled = playerOwnsBack) {
        animateToDetent(PlayerSheetDetent.Collapsed)
    }

    fun release(xVelocity: Float, yVelocity: Float, origin: SheetDragOrigin) {
        val releaseX = playerSheetReleaseVelocity(xVelocity, viewConfiguration)
        val releaseY = playerSheetReleaseVelocity(yVelocity, viewConfiguration)
        val currentFrame = renderFrame.value
        val target = playerSheetReleaseTarget(
            topPx = currentFrame.topAt(slide),
            xVelocity = releaseX,
            yVelocity = releaseY,
            expandedTopPx = currentFrame.expandedTopPx,
            collapsedTopPx = currentFrame.collapsedTopPx,
            hiddenTopPx = currentFrame.hiddenTopPx,
            sheetRangePx = currentFrame.hiddenTopPx - currentFrame.collapsedTopPx,
            hideable = origin == SheetDragOrigin.Full,
        )
        animateToDetent(target, releaseX, releaseY, origin)
    }

    val topNowState = rememberUpdatedState<() -> Float> { renderFrame.value.topAt(slide) }
    val fullVisibleNowState = rememberUpdatedState<() -> Boolean> {
        trackPresentNow.value && chromeVisibleNow.value && slide > 0f
    }
    val partialSlideNowState = rememberUpdatedState<() -> Boolean> {
        slide != PlayerSheetDetent.Expanded.slide &&
            slide != PlayerSheetDetent.Collapsed.slide &&
            slide != PlayerSheetDetent.Hidden.slide
    }
    val touchSlopState = rememberUpdatedState(viewConfiguration.scaledTouchSlop)
    val nestedPane = rememberUpdatedState(pane)
    val childAtTopState = rememberUpdatedState<() -> Boolean> {
        when (pane) {
            PlayerPane.Lyrics -> lyrics.attachedView?.let { it.hasSheetTouch && !it.canScrollVertically(-1) } == true
            PlayerPane.Queue -> queueAtTop
            PlayerPane.Cover -> false
        }
    }
    fun miniDragCanStart(): Boolean = trackPresentNow.value && chromeInteractiveNow() &&
        renderFrame.value.collapsedTopPx > renderFrame.value.expandedTopPx
    val miniDragCanStartNow = rememberUpdatedState(::miniDragCanStart)
    val startDragState = rememberUpdatedState<(SheetDragOrigin) -> Int?> { origin ->
        if (activeDrag != null) {
            null
        } else {
            val canStart = when (origin) {
                SheetDragOrigin.Full -> fullVisibleNowState.value()
                SheetDragOrigin.Mini -> miniDragCanStartNow.value()
            }
            if (!canStart) null else {
                cancelSettlement()
                settleGeneration.also { activeDrag = it }
            }
        }
    }
    val dragByState = rememberUpdatedState<(Float, SheetDragOrigin) -> Float> { delta, origin ->
        val currentFrame = renderFrame.value
        val from = currentFrame.topAt(slide)
        val maximumTop = if (origin == SheetDragOrigin.Mini) {
            currentFrame.collapsedTopPx
        } else {
            currentFrame.hiddenTopPx
        }
        val to = (from + delta).coerceIn(currentFrame.expandedTopPx, maximumTop)
        slide = currentFrame.slideAt(to, currentDetent)
        to - from
    }
    val releaseSheetState = rememberUpdatedState<(Float, Float, SheetDragOrigin) -> Unit>(::release)
    val finishDragState = rememberUpdatedState<(Int, Velocity, SheetDragOrigin) -> Boolean> {
        token, velocity, origin ->
        if (activeDrag != token) {
            false
        } else {
            activeDrag = null
            releaseSheetState.value(velocity.x, velocity.y, origin)
            true
        }
    }

    val sheetNestedScroll = remember {
        object : NestedScrollConnection {
            private var drag: Int? = null
            private var dragPane: PlayerPane? = null
            private var lyricsEdgeSteps = 0
            private var nestedDownDistance = 0f
            private var skipPostFling = false
            private var preFlingGeneration = -1

            fun clearDrag() {
                drag = null
                dragPane = null
                lyricsEdgeSteps = 0
                nestedDownDistance = 0f
                skipPostFling = false
                preFlingGeneration = settleGeneration
                activeDrag = null
            }

            private fun move(delta: Float): Offset {
                if (drag != activeDrag) {
                    drag = null
                    dragPane = null
                }
                if (drag == null) {
                    if (activeDrag != null) return Offset.Zero
                    drag = startDragState.value(SheetDragOrigin.Full) ?: return Offset.Zero
                    dragPane = nestedPane.value
                    skipPostFling = false
                }
                return Offset(0f, dragByState.value(delta, SheetDragOrigin.Full))
            }

            fun moveLyrics(delta: Float): Boolean {
                if (dragPane != PlayerPane.Lyrics || drag == null || drag != activeDrag) return false
                if (delta != 0f) dragByState.value(delta, SheetDragOrigin.Full)
                return true
            }

            fun releaseLyrics(velocityX: Float, velocityY: Float): Boolean {
                lyricsEdgeSteps = 0
                if (dragPane != PlayerPane.Lyrics) return false
                val token = drag
                drag = null
                dragPane = null
                return token != null && finishDragState.value(token, Velocity(velocityX, velocityY), SheetDragOrigin.Full)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!fullVisibleNowState.value()) return Offset.Zero
                if (nestedPane.value == PlayerPane.Lyrics) {
                    val pullsFromTop = available.y >= touchSlopState.value && lyricsEdgeSteps == 3 && childAtTopState.value()
                    return if (pullsFromTop) move(available.y) else Offset.Zero
                }
                if (nestedPane.value != PlayerPane.Queue) return Offset.Zero
                val current = topNowState.value()
                if (available.y < 0f && current > 0f) {
                    nestedDownDistance = 0f
                    return move(available.y)
                }
                if (available.y <= 0f || current > 0f) nestedDownDistance = 0f
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (nestedPane.value == PlayerPane.Lyrics) {
                    // Hand off after three unconsumed touch moves at the upper edge.
                    lyricsEdgeSteps = if (consumed.y == 0f && available.y > 0f && childAtTopState.value()) {
                        (lyricsEdgeSteps + 1).coerceAtMost(3)
                    } else 0
                    return Offset.Zero
                }
                if (nestedPane.value != PlayerPane.Queue) return Offset.Zero
                if (available.y <= 0f || !childAtTopState.value()) {
                    nestedDownDistance = 0f
                    return Offset.Zero
                }
                nestedDownDistance += available.y
                if (nestedDownDistance < touchSlopState.value) return Offset.Zero
                nestedDownDistance = 0f
                return move(available.y)
            }

            private fun settle(available: Velocity): Boolean {
                val token = drag
                drag = null
                dragPane = null
                nestedDownDistance = 0f
                if (token != null && finishDragState.value(token, available, SheetDragOrigin.Full)) return true
                if (activeDrag != null || settleJob?.isActive == true ||
                    !fullVisibleNowState.value() || !childAtTopState.value() || !partialSlideNowState.value()) return false
                releaseSheetState.value(available.x, available.y, SheetDragOrigin.Full)
                return true
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (nestedPane.value != PlayerPane.Queue) return Velocity.Zero
                val generation = settleGeneration
                val settled = settle(available)
                preFlingGeneration = generation
                skipPostFling = settled
                return if (settled) available else Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (nestedPane.value != PlayerPane.Queue) return Velocity.Zero
                val canSettle = !skipPostFling && preFlingGeneration == settleGeneration
                skipPostFling = false
                if (!canSettle) return Velocity.Zero
                return if (settle(available)) available else Velocity.Zero
            }
        }
    }
    val moveLyricsDrag = remember(sheetNestedScroll) { sheetNestedScroll::moveLyrics }
    val releaseLyricsDrag = remember(sheetNestedScroll) { sheetNestedScroll::releaseLyrics }

    LaunchedEffect(hasTrack) {
        if (hasTrack) {
            hadTrack = true
        } else if (hadTrack) {
            hadTrack = false
            cancelSettlement()
            sheetNestedScroll.clearDrag()
            queueAtTop = true
            miniSlot = null
            tallSlot = null
            paneSlots.clear()
            sheetCoordinates = null
            slide = PlayerSheetDetent.Collapsed.slide
            currentDetent = PlayerSheetDetent.Collapsed
            targetDetent = PlayerSheetDetent.Collapsed
            notifyExpanded(false)
        }
    }

    fun directDragModifier(originAtDown: () -> SheetDragOrigin?): Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = originAtDown()
            if (origin == null) return@awaitEachGesture
            var velocityTracker = VelocityTracker()
            velocityTracker.addPointerInputChange(down)
            var trackedPointerId = down.id
            var drag: Int? = null
            var velocity = Velocity.Zero
            try {
                val active = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    if (origin == SheetDragOrigin.Mini && change.id != trackedPointerId) {
                        velocityTracker = VelocityTracker()
                        trackedPointerId = change.id
                    }
                    velocityTracker.addPointerInputChange(change)
                    val canStart = when (origin) {
                        SheetDragOrigin.Full -> overSlop > 0f ||
                            (topNowState.value() > 0f && overSlop < 0f)
                        SheetDragOrigin.Mini -> overSlop < 0f && miniDragCanStartNow.value()
                    }
                    if (canStart) {
                        val token = startDragState.value(origin)
                        if (token != null) {
                            drag = token
                            dragByState.value(overSlop, origin)
                            change.consume()
                        }
                    }
                } ?: return@awaitEachGesture
                if (drag == null) return@awaitEachGesture
                if (origin == SheetDragOrigin.Full) trackedPointerId = active.id
                val activeEnded = active.changedToUpIgnoreConsumed() || !active.pressed
                if (activeEnded) {
                    if (origin == SheetDragOrigin.Full) {
                        velocity = velocityTracker.calculateVelocity()
                        if (active.changedToUpIgnoreConsumed() && !active.isConsumed) active.consume()
                    } else if (active.changedToUpIgnoreConsumed() && !active.isConsumed) {
                        velocity = velocityTracker.calculateVelocity()
                        active.consume()
                    }
                } else {
                    while (drag == activeDrag) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == trackedPointerId } ?: break
                        velocityTracker.addPointerInputChange(change)
                        if (change.changedToUpIgnoreConsumed()) {
                            if (!change.isConsumed) {
                                velocity = velocityTracker.calculateVelocity()
                                change.consume()
                            }
                            break
                        }
                        if (!change.pressed) {
                            if (origin == SheetDragOrigin.Full) {
                                velocity = velocityTracker.calculateVelocity()
                            }
                            break
                        }
                        if (drag != activeDrag) break
                        val delta = change.positionChangeIgnoreConsumed().y
                        if (delta != 0f) {
                            dragByState.value(delta, origin)
                            change.consume()
                        }
                    }
                }
            } finally {
                drag?.let { finishDragState.value(it, velocity, origin) }
            }
        }
    }
    val fullDragModifier = remember { directDragModifier { SheetDragOrigin.Full } }
    val miniDragModifier = remember {
        directDragModifier { if (miniDragCanStartNow.value()) SheetDragOrigin.Mini else null }
    }
    val createDragModifier = remember { ::directDragModifier }

    val fullVisible = frame.progress > 0f
    val tallTarget = if (immersiveCover) tallSlot.rectIn(hostSize) else null
    val motionBackdropBottom = tallTarget?.bottom?.takeIf {
        frame.progress == 1f && artworkUnobscured && state.playWhenReady
    }
    val sheetModifier = remember {
        Modifier.fillMaxSize().graphicsLayer {
            val current = renderFrame.value
            // The native lyric View stays measured, but has no visible or touchable area at rest.
            translationY = if (current.progress > 0f) current.offsetPx else current.hiddenTopPx
            val pullCorner = (current.offsetPx / 6f).coerceAtLeast(0f).toDp()
            val corner = maxOf(SheetCorner, pullCorner * (1f - current.progress))
            shape = RoundedCornerShape(topStart = corner, topEnd = corner)
            clip = true
        }.drawWithContent {
            if (renderFrame.value.progress > 0f) drawContent()
        }
    }
    val hostModifier = remember { Modifier.fillMaxSize().onGloballyPositioned { hostCoordinates = it } }

    Box(
        modifier = hostModifier,
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        if (hasTrack) {
            Box(sheetModifier.zIndex(1f).drawWithContent {
                drawRect(Color.Black, alpha = renderFrame.value.progress)
                drawContent()
            }) {
                AppleArtworkBackdrop(
                    playerArtworkUri,
                    Modifier.fillMaxSize().then(contentFade),
                    motionEnabled = fullVisible,
                    prewarmedArtwork = prewarmedArtwork,
                )
                if (motionBackdropBottom != null) {
                    PlayerMotionBackdrop(
                        frame = motionFrame,
                        videoBottomPx = motionBackdropBottom,
                        barrierMarginPx = with(density) { 150.dp.toPx() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                sheetModifier.zIndex(3f).onGloballyPositioned { sheetCoordinates = it }
                    .then(if (fullVisible) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                if (immersiveCover) {
                    val tallHeight = with(density) { (sheetWidthPx / TallArtworkAspectRatio).roundToInt().toDp() }
                    Box(Modifier.align(AbsoluteAlignment.TopLeft).fillMaxWidth().height(tallHeight)
                        .clearAndSetSemantics {}.onGloballyPositioned { coordinates ->
                            coordinates.artworkSlotIn(sheetCoordinates)?.let { tallSlot = it }
                        })
                    val statusInset = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density)
                    if (statusInset > 0) {
                        val statusHeight = with(density) { statusInset.toDp() }
                        val shade = if (isSystemInDarkTheme()) Color.Black else Color.White
                        Box(Modifier.align(AbsoluteAlignment.TopLeft).fillMaxWidth().height(statusHeight)
                            .graphicsLayer { alpha = .60f * renderFrame.value.progress }
                            .drawWithCache {
                                val gradient = Brush.verticalGradient(listOf(shade, shade.copy(alpha = 0f)))
                                onDrawBehind { drawRect(gradient) }
                            })
                    }
                }
                NowPlayingContent(
                    state = state,
                    player = player,
                    lyrics = lyrics,
                    library = library,
                    fullVisible = fullVisible,
                    contentFade = contentFade,
                    sheetDragModifier = fullDragModifier,
                    sheetNestedScroll = sheetNestedScroll,
                    onLyricsDragBy = moveLyricsDrag,
                    onLyricsScrollEnd = releaseLyricsDrag,
                    pane = pane,
                    onPaneChange = onPaneChange,
                    translation = translation,
                    pronunciation = pronunciation,
                    onTranslationChange = onTranslationChange,
                    onPronunciationChange = onPronunciationChange,
                    onOpenCatalog = openCatalogFromPlayer,
                    canCommitRelationNavigation = { canCommitRelationNavigation() },
                    onOpenSettings = onOpenSettings,
                    artworkSlot = fullArtworkSlot,
                    onArtworkUnobscuredChange = { artworkUnobscured = it },
                    onQueueAtTopChanged = { queueAtTop = it },
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(bottom = Dimens.BarBottom)
                .zIndex(if (fullVisible) 0f else 1f)
                .graphicsLayer { alpha = if (chromeVisible) 1f else 0f }
                .then(if (chromeVisible) Modifier else Modifier.clearAndSetSemantics {}),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hasTrack) Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MiniPlayer(
                    state = state,
                    player = player,
                    sheetFrame = renderFrame,
                    artworkSlot = miniArtworkSlot,
                    dragModifier = miniDragModifier,
                    enabled = chromeInteractive,
                    onOpen = openFromMini,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(Dimens.BarGap))
            }
            Box(Modifier.padding(horizontal = Dimens.Page)) { bottomBar(frame, chromeInteractive) }
        }
        if (hasTrack) PlayerSheetArtwork(
            artwork = prewarmedArtwork?.source,
            variant = variant,
            immersive = immersiveCover,
            videoAspectRatio = if (selectedTall) TallArtworkAspectRatio else 1f,
            trackPlaying = state.isPlaying,
            hostSize = hostSize,
            pane = pane,
            fullTarget = if (immersiveCover) tallTarget else paneSlots[pane].rectIn(hostSize),
            mini = miniSlot.rectIn(hostSize),
            progress = frame.progress,
            chromeVisible = chromeVisible,
            miniInteractive = chromeInteractive,
            unobscured = artworkUnobscured,
            createDragModifier = createDragModifier,
            onOpen = openFromMini,
            onCover = { onPaneChange(PlayerPane.Cover) },
            captureMotionFrame = motionBackdropBottom != null,
            onMotionFrame = onMotionFrame,
            onMotionError = onMotionError,
        )
    }
}
