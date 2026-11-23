package io.github.kid141252010.fantastic.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

private const val BlackScrim = 0x59000000
private const val WhiteScrim = 0x0AFFFFFF
private val MaskColors = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE)
private val TintAlphas = intArrayOf(Color.TRANSPARENT, 0xCCFFFFFF.toInt(), 0xF2FFFFFF.toInt(), Color.WHITE, Color.WHITE)
// The last color holds from 40% of the tint layer to the sheet bottom.
private val TintStops = floatArrayOf(0f, .4f * .7f, .4f * .9f, .4f, 1f)

@Composable
internal fun PlayerMotionBackdrop(
    frame: State<Bitmap?>,
    videoBottomPx: Float,
    barrierMarginPx: Float,
    modifier: Modifier = Modifier,
) {
    val buffers = remember { MotionBackdropBuffers() }
    DisposableEffect(buffers) {
        onDispose { buffers.close() }
    }
    Spacer(modifier.drawWithCache {
        if (size.width <= 0f || videoBottomPx <= 0f || videoBottomPx > size.height || barrierMarginPx <= 0f) {
            onDrawBehind {}
        } else {
            val geometry = MotionBackdropGeometry(size.width, size.height, videoBottomPx, barrierMarginPx)
            onDrawBehind {
                // Reading at draw time avoids recomposing the sheet and never captures a retired frame.
                val sample = frame.value ?: return@onDrawBehind
                drawIntoCanvas { buffers.draw(it.nativeCanvas, sample, geometry) }
            }
        }
    })
}

// The blur rectangle is flipped vertically around its midpoint, as in view_motion_container.
private class MotionBackdropGeometry(val width: Float, bottom: Float, val videoBottom: Float, val overlap: Float) {
    val height = bottom - videoBottom + overlap
    val left = -.125f * width
    val tileWidth = 1.25f * width
    private val halfOverlap = overlap * .5f
    private val arc = width * width / (8f * halfOverlap) + halfOverlap * .5f
    private val radius = halfOverlap + arc
    val mask = RadialGradient(width * .5f, height - halfOverlap + arc, radius, MaskColors,
        floatArrayOf(0f, arc / radius, 1f), Shader.TileMode.CLAMP)
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = mask
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val tint = if (bottom > videoBottom) Paint().apply {
        shader = LinearGradient(0f, videoBottom, 0f, bottom, TintAlphas, TintStops, Shader.TileMode.CLAMP)
    } else null
    private var tintColor = Color.TRANSPARENT
    private val sheetBottom = bottom

    fun drawTint(canvas: Canvas, color: Int) {
        if (tint == null) return
        if (tintColor != color) {
            tint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            tintColor = color
        }
        canvas.drawRect(0f, videoBottom, width, sheetBottom, tint)
    }
}

private class MotionBackdropBuffers {
    private var strip: MotionStrip? = null
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val source = Rect()
    private var lastFrame: Bitmap? = null

    fun draw(canvas: Canvas, frame: Bitmap, geometry: MotionBackdropGeometry) {
        val width = frame.width
        val height = (frame.height / 8).coerceAtLeast(2)
        val hardwareBlur = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated
        val buffer = strip?.takeIf { it.width == width && it.height == height && it.hardwareBlur == hardwareBlur }
            ?: MotionStrip(width, height, hardwareBlur).also {
                close()
                strip = it
            }
        if (lastFrame !== frame) {
            source.set(0, frame.height - height, width, frame.height)
            buffer.update(frame, source, cropPaint)
            lastFrame = frame
        }
        val save = canvas.save()
        canvas.translate(0f, geometry.videoBottom - geometry.overlap + geometry.height)
        canvas.scale(1f, -1f)
        canvas.clipRect(0f, 0f, geometry.width, geometry.height)
        buffer.drawBlur(canvas, geometry)
        canvas.restoreToCount(save)
        geometry.drawTint(canvas, buffer.tintColor)
    }

    fun close() {
        strip?.close()
        strip = null
        lastFrame = null
    }
}

private class MotionStrip(val width: Int, val height: Int, val hardwareBlur: Boolean) {
    private val bounds = Rect(0, 0, width, height)
    private val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val cropCanvas = Canvas(raw)
    private val gpu = if (hardwareBlur && Build.VERSION.SDK_INT >= 31) GpuMotionBlur(width, height) else null
    private val blurred = if (hardwareBlur) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val blurCanvas = blurred?.let(::Canvas)
    private val gaussian = if (hardwareBlur) null else ArtworkGaussianBlur(width, height)
    private val matrix = Matrix()
    private val tile = blurred?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.MIRROR) }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var lastGeometry: MotionBackdropGeometry? = null
    private val pixels = IntArray(width * height)
    private val xWeights = ArtworkGaussianBlur.paletteWeights(width, convolve = hardwareBlur)
    private val yWeights = ArtworkGaussianBlur.paletteWeights(height, convolve = hardwareBlur)
    var tintColor: Int = Color.TRANSPARENT
        private set

    fun update(frame: Bitmap, source: Rect, paint: Paint) {
        cropCanvas.drawBitmap(frame, source, bounds, paint)
        val output = blurred
        if (output != null) {
            gaussian!!.apply(raw, output)
            blurCanvas!!.drawColor(BlackScrim)
            blurCanvas.drawColor(WhiteScrim)
            tintColor = palette(output)
        } else if (Build.VERSION.SDK_INT >= 31) {
            gpu!!.record(raw, bounds)
            // Evaluate just the four palette taps on the CPU. Readback would stall HWUI every frame.
            tintColor = palette(raw)
        }
    }

    private fun palette(bitmap: Bitmap): Int {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var red = 0f
        var green = 0f
        var blue = 0f
        for (y in 0 until height) {
            val row = y * width
            val yWeight = yWeights[y]
            for (x in 0 until width) {
                val pixel = pixels[row + x]
                val weight = xWeights[x] * yWeight
                red += (pixel ushr 16 and 255) * weight
                green += (pixel ushr 8 and 255) * weight
                blue += (pixel and 255) * weight
            }
        }
        return Color.rgb(tintChannel(red), tintChannel(green), tintChannel(blue))
    }

    private fun tintChannel(value: Float): Int {
        if (!hardwareBlur) return value.roundToInt().coerceIn(0, 255)
        val blackAlpha = BlackScrim ushr 24
        val whiteAlpha = WhiteScrim ushr 24
        val channel = value * ((255 - blackAlpha) * (255 - whiteAlpha) / (255f * 255f)) + whiteAlpha
        return channel.roundToInt().coerceIn(0, 255)
    }

    fun drawBlur(canvas: Canvas, geometry: MotionBackdropGeometry) {
        if (gpu != null && Build.VERSION.SDK_INT >= 31) {
            gpu.draw(canvas, geometry)
        } else {
            if (lastGeometry !== geometry) {
                matrix.setScale(geometry.tileWidth / width, geometry.height / height)
                matrix.postTranslate(geometry.left, -geometry.overlap)
                tile!!.setLocalMatrix(matrix)
                tilePaint.shader = ComposeShader(geometry.mask, tile, PorterDuff.Mode.SRC_IN)
                lastGeometry = geometry
            }
            canvas.drawRect(0f, 0f, geometry.width, geometry.height, tilePaint)
        }
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= 31) gpu?.close()
        tilePaint.shader = null
        cropCanvas.setBitmap(null)
        blurCanvas?.setBitmap(null)
        raw.recycle()
        blurred?.recycle()
    }
}

@RequiresApi(31)
private class GpuMotionBlur(private val width: Int, private val height: Int) {
    private val node = RenderNode("FantasticMotionBlur").apply {
        setPosition(0, 0, width, height)
        val radius = ArtworkGaussianBlur.Radius.toFloat()
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    fun record(bitmap: Bitmap, bounds: Rect) {
        val recording = node.beginRecording()
        recording.drawBitmap(bitmap, null, bounds, null)
        node.endRecording()
    }

    fun draw(canvas: Canvas, geometry: MotionBackdropGeometry) {
        val save = canvas.saveLayer(0f, 0f, geometry.width, geometry.height, null)
        val tileEnd = geometry.height - geometry.overlap
        val sx = geometry.tileWidth / width
        val sy = geometry.height / height
        // Drawing the blurred strip twice gives the same vertical seam as MIRROR tiling.
        drawTile(canvas, geometry, 0f, tileEnd, -geometry.overlap, sx, sy)
        drawTile(canvas, geometry, tileEnd, geometry.height, geometry.height + tileEnd, sx, -sy)
        canvas.drawColor(BlackScrim)
        canvas.drawColor(WhiteScrim)
        canvas.drawRect(0f, 0f, geometry.width, geometry.height, geometry.maskPaint)
        canvas.restoreToCount(save)
    }

    private fun drawTile(canvas: Canvas, geometry: MotionBackdropGeometry, clipTop: Float, clipBottom: Float,
        top: Float, sx: Float, sy: Float) {
        if (clipTop >= clipBottom) return
        val save = canvas.save()
        canvas.clipRect(0f, clipTop, geometry.width, clipBottom)
        canvas.translate(geometry.left, top)
        canvas.scale(sx, sy)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
    }

    fun close() = node.discardDisplayList()
}
