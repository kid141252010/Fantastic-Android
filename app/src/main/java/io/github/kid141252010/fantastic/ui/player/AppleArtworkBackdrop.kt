package io.github.kid141252010.fantastic.ui.player

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import io.github.kid141252010.fantastic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun rememberPrewarmedArtwork(artwork: String?, fallback: String?, playerSize: IntSize): PrewarmedArtwork? {
    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val densityDpi = context.resources.configuration.densityDpi
    val downscale = if (densityDpi >= 420) 24f else 16f
    val saturation = 2.5f
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val blackScrim = if (dark) .50f else .30f
    val whiteScrim = if (dark) .05f else .10f
    val fallbackArtwork = fallback?.takeIf { it.isNotBlank() && it != artwork }
    var prewarmed by remember(artwork, fallbackArtwork, playerSize) { mutableStateOf<PrewarmedArtwork?>(null) }
    LaunchedEffect(artwork, fallbackArtwork, playerSize, imageLoader, downscale, saturation, blackScrim, whiteScrim) {
        if (artwork == null) return@LaunchedEffect
        suspend fun load(url: String): Bitmap? {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(playerSize.width.coerceAtLeast(1))
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            return ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
        }
        val requested = load(artwork)
        val source = requested ?: fallbackArtwork?.let { load(it) }
        if (BuildConfig.DEBUG && source != null) {
            Log.d("FantasticMotion", "Artwork host=${playerSize.width}px decoded=${source.width}x${source.height} fallback=${requested == null}")
        }
        val frame = source?.let { bitmap ->
            withContext(Dispatchers.Default) {
                ArtworkBuffers().render(bitmap, playerSize.width.toFloat(), playerSize.height.toFloat(), 0L,
                    downscale, saturation, blackScrim, whiteScrim)
            }
        }
        if (isActive) prewarmed = PrewarmedArtwork(artwork, source, frame, downscale, saturation, blackScrim, whiteScrim)
    }
    return prewarmed
}

internal class PrewarmedArtwork(
    val artwork: String,
    val source: Bitmap?,
    val frame: Bitmap?,
    val downscale: Float,
    val saturation: Float,
    val blackScrim: Float,
    val whiteScrim: Float,
) {
    var elapsedNanos: Long = 0L
}

/** Layered artwork backdrop rendered without owning Coil's bitmap. */
@Composable
internal fun AppleArtworkBackdrop(
    artwork: String?,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
    prewarmedArtwork: PrewarmedArtwork? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val buffers = remember { ArtworkBuffers() }
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val reducedEffects = !motionEnabled
    val movingArtwork = motionEnabled && animationsEnabled

    var current by remember { mutableStateOf(prewarmedArtwork?.source) }
    var previous by remember { mutableStateOf<PreviousFrame?>(null) }
    var elapsedNanos by remember { mutableLongStateOf(prewarmedArtwork?.elapsedNanos ?: 0L) }
    var fadeNanos by remember { mutableLongStateOf(FADE_NANOS) }

    LaunchedEffect(artwork, prewarmedArtwork) {
        // The host shares the decoded artwork between the backdrop and the cover.
        if (artwork != null && prewarmedArtwork == null) return@LaunchedEffect
        val bitmap = prewarmedArtwork?.source
        if (prewarmedArtwork != null && bitmap != null && isActive) {
            if (current !== bitmap) {
                buffers.snapshot()?.let { old -> previous = old }
                elapsedNanos = prewarmedArtwork.elapsedNanos
                current = bitmap
            }
        } else if (isActive) {
            current = null
            previous = null
            elapsedNanos = 0L
        }
    }

    // Pause and resume animation with the visible lifecycle.
    LaunchedEffect(current, lifecycle, movingArtwork) {
        fadeNanos = if (current == null || !animationsEnabled || previous == null) FADE_NANOS else 0L
        if (current == null || !movingArtwork) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        val delta = (now - last).coerceAtLeast(0L)
                        elapsedNanos += delta
                        prewarmedArtwork?.takeIf { it.source === current }?.elapsedNanos = elapsedNanos
                        fadeNanos = (fadeNanos + delta).coerceAtMost(FADE_NANOS)
                        if (fadeNanos >= FADE_NANOS) previous = null
                    }
                    last = now
                }
            }
        }
    }

    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val blackScrim = if (dark) .50f else .30f
    val whiteScrim = if (dark) .05f else .10f
    val densityDpi = context.resources.configuration.densityDpi
    val downscale = if (reducedEffects) 72f else if (densityDpi >= 420) 24f else 16f
    val saturation = if (reducedEffects) 3.5f else 2.5f

    Canvas(modifier) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.drawColor(Color.Black.toArgb())
            val bitmap = current
            if (bitmap == null || size.width <= 0f || size.height <= 0f) return@drawIntoCanvas

            val cachedFrame = prewarmedArtwork?.takeIf {
                it.artwork == artwork && it.source === bitmap && elapsedNanos == 0L &&
                    it.downscale == downscale && it.saturation == saturation &&
                    it.blackScrim == blackScrim && it.whiteScrim == whiteScrim
            }?.frame
            val frame = cachedFrame ?: buffers.render(bitmap, size.width, size.height, elapsedNanos, downscale,
                saturation, blackScrim, whiteScrim)
            val fade = (fadeNanos.toFloat() / FADE_NANOS).coerceIn(0f, 1f)
            previous?.let { old ->
                if (fade < 1f) buffers.drawFrame(canvas, old.bitmap, size.width, size.height, downscale,
                    1f - fade, old.blurred, previous = true)
            }
            buffers.drawFrame(canvas, frame, size.width, size.height, downscale, fade, blurred = Build.VERSION.SDK_INT < 31)
        }
    }
}

private const val FADE_NANOS = 1_000_000_000L

private data class PreviousFrame(val bitmap: Bitmap, val blurred: Boolean)

private class ArtworkBuffers {
    private var layerWidth = 0
    private var layerHeight = 0
    private var raw: Bitmap? = null
    private var blurred: Bitmap? = null
    private var rawCanvas: AndroidCanvas? = null
    private var gaussianBlur: ArtworkGaussianBlur? = null
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outputNodes = arrayOfNulls<RenderNode>(2)
    private val matrices = arrayOf(Matrix(), Matrix(), Matrix())
    private val destination = RectF()
    private val colorMatrix = ColorMatrix()
    private var lastSaturation = Float.NaN
    private var effectRadius = Float.NaN
    private var effect: RenderEffect? = null

    fun snapshot(): PreviousFrame? {
        val bitmap = if (Build.VERSION.SDK_INT < 31) blurred else raw
        return bitmap?.let { PreviousFrame(it.copy(Bitmap.Config.ARGB_8888, false), Build.VERSION.SDK_INT < 31) }
    }

    fun render(
        source: Bitmap,
        width: Float,
        height: Float,
        elapsedNanos: Long,
        downscale: Float,
        saturation: Float,
        blackScrim: Float,
        whiteScrim: Float,
    ): Bitmap {
        val targetWidth = (width * 1.3f / downscale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * 1.3f / downscale).roundToInt().coerceAtLeast(1)
        ensure(targetWidth, targetHeight)
        if (lastSaturation != saturation) {
            colorMatrix.setSaturation(saturation)
            imagePaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            lastSaturation = saturation
        }
        val canvas = rawCanvas!!
        canvas.drawColor(Color.Transparent.toArgb(), android.graphics.PorterDuff.Mode.CLEAR)
        val maxSide = max(targetWidth, targetHeight).toFloat()
        val renderedSide = (maxSide * 1.3f).roundToInt().toFloat()
        val scale = renderedSide / source.height.coerceAtLeast(1)
        val cx = renderedSide * .5f
        val baseX = -(renderedSide - targetWidth) * .5f
        val baseY = -(renderedSide - targetHeight) * .5f
        val widthF = targetWidth.toFloat()
        val heightF = targetHeight.toFloat()
        val t = elapsedNanos.toDouble()
        val angle1 = (-(t % 120_000_000_000L) / 120_000_000_000.0 * 360.0).toFloat()
        val angle2 = ((t % 90_000_000_000L) / 90_000_000_000.0 * 360.0).toFloat()
        val angle3 = ((t % 70_000_000_000L) / 70_000_000_000.0 * 360.0).toFloat()

        transform(matrices[0], scale, angle1, cx, baseX, baseY)
        canvas.drawBitmap(source, matrices[0], imagePaint)
        transform(matrices[1], scale, angle2, cx, baseX - .95f * widthF, baseY - .7f * heightF)
        canvas.drawBitmap(source, matrices[1], imagePaint)
        val third = matrices[2]
        transform(third, scale, angle3, cx, baseX - .5f * widthF, baseY + .7f * heightF)
        third.postRotate(angle3, widthF * .5f, heightF * .5f)
        canvas.drawBitmap(source, third, imagePaint)

        // Apply the light/dark black scrim first, then the white scrim.
        canvas.drawColor(Color.Black.copy(alpha = blackScrim).toArgb())
        canvas.drawColor(Color.White.copy(alpha = whiteScrim).toArgb())
        val output = blurred ?: return raw!!
        gaussianBlur!!.apply(raw!!, output)
        return output
    }

    fun drawFrame(canvas: AndroidCanvas, frame: Bitmap, width: Float, height: Float, scale: Float, alpha: Float,
        blurred: Boolean, previous: Boolean = false) {
        if (alpha <= 0f) return
        val scaledWidth = frame.width * scale
        val scaledHeight = frame.height * scale
        destination.set((width - scaledWidth) * .5f, (height - scaledHeight) * .5f,
            (width + scaledWidth) * .5f, (height + scaledHeight) * .5f)
        outputPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        if (Build.VERSION.SDK_INT >= 31 && !blurred) {
            val radius = 25f * scale
            if (radius != effectRadius) {
                effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
                effectRadius = radius
            }
            val index = if (previous) 0 else 1
            val node = outputNodes[index] ?: RenderNode(if (previous) "FantasticArtworkPrevious" else "FantasticArtworkCurrent").apply {
                setClipToBounds(false)
            }.also { outputNodes[index] = it }
            node.setPosition(0, 0, width.roundToInt(), height.roundToInt())
            node.setRenderEffect(effect)
            val recording = node.beginRecording()
            recording.drawBitmap(frame, null, destination, outputPaint)
            node.endRecording()
            canvas.drawRenderNode(node)
        } else {
            canvas.drawBitmap(frame, null, destination, outputPaint)
        }
        outputPaint.alpha = 255
    }

    private fun ensure(width: Int, height: Int) {
        if (width == layerWidth && height == layerHeight) return
        layerWidth = width
        layerHeight = height
        raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rawCanvas = AndroidCanvas(raw!!)
        if (Build.VERSION.SDK_INT < 31) {
            blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            gaussianBlur = ArtworkGaussianBlur(width, height)
        }
    }

    private fun transform(matrix: Matrix, scale: Float, angle: Float, center: Float, x: Float, y: Float) {
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postRotate(angle, center, center)
        matrix.postTranslate(x, y)
    }
}
