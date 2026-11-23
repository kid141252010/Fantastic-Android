package io.github.kid141252010.fantastic.ui.player

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/** Separable equivalent of Toolkit blur at radius 25, with clamped edges. */
internal class ArtworkGaussianBlur(private val width: Int, private val height: Int) {
    private val pixels = IntArray(width * height)
    private val scratch = IntArray(width * height)

    fun apply(source: Bitmap, output: Bitmap) {
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        filter(pixels, scratch, horizontal = true)
        filter(scratch, pixels, horizontal = false)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun filter(input: IntArray, output: IntArray, horizontal: Boolean) {
        val stride = if (horizontal) 1 else width
        val lineLength = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val lineStride = if (horizontal) width else 1
        for (line in 0 until lines) {
            val lineStart = line * lineStride
            for (position in 0 until lineLength) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (offset in -Radius..Radius) {
                    val pixel = input[lineStart + (position + offset).coerceIn(0, lineLength - 1) * stride]
                    val weight = weights[offset + Radius]
                    a += (pixel ushr 24) * weight
                    r += (pixel ushr 16 and 255) * weight
                    g += (pixel ushr 8 and 255) * weight
                    b += (pixel and 255) * weight
                }
                output[lineStart + position * stride] = (a.roundToInt().coerceIn(0, 255) shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
            }
        }
    }

    companion object {
        const val Radius = 25
        private val weights = FloatArray(Radius * 2 + 1) { index ->
            val x = (index - Radius).toFloat()
            val sigma = Radius * .4f + .6f
            exp(-(x * x) / (2f * sigma * sigma))
        }.apply {
            val total = sum()
            for (i in indices) this[i] /= total
        }

        /** Fold two bilinear resize taps into one axis of the 2 × 2 palette mean. */
        fun paletteWeights(length: Int, convolve: Boolean): FloatArray {
            val axis = FloatArray(length)
            for (tap in 0..1) {
                val center = (tap + .5f) * (length / 2f) - .5f
                val left = floor(center).toInt()
                val rightWeight = (center - left) * .5f
                val leftWeight = .5f - rightWeight
                if (convolve) {
                    for (offset in -Radius..Radius) {
                        val weight = weights[offset + Radius]
                        axis[(left + offset).coerceIn(0, length - 1)] += leftWeight * weight
                        axis[(left + offset + 1).coerceIn(0, length - 1)] += rightWeight * weight
                    }
                } else {
                    axis[left] += leftWeight
                    axis[(left + 1).coerceAtMost(length - 1)] += rightWeight
                }
            }
            return axis
        }
    }
}
