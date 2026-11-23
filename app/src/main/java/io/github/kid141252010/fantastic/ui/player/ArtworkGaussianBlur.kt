package io.github.kid141252010.fantastic.ui.player

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.floor

/** Separable approximation of Toolkit blur at radius 25, with clamped edges. */
internal class ArtworkGaussianBlur(private val width: Int, private val height: Int) {
    private val pixels = IntArray(width * height)
    private val scratch = IntArray(width * height)
    private val firstSums = LongArray(2 * (maxOf(width, height) + SecondWidth + ThirdWidth - 2))
    private val secondSums = LongArray(2 * (maxOf(width, height) + ThirdWidth - 1))

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
            val start = line * lineStride
            sumClampedPixels(input, start, stride, lineLength)
            sumSecondBox(lineLength + ThirdWidth - 1)

            var rb = 0L
            var ag = 0L
            for (i in 0 until 2 * ThirdWidth step 2) {
                rb += secondSums[i]
                ag += secondSums[i + 1]
            }
            var index = start
            val end = 2 * (lineLength - 1)
            for (i in 0 until end step 2) {
                output[index] = roundedPixel(rb, ag)
                val next = i + 2 * ThirdWidth
                rb += secondSums[next] - secondSums[i]
                ag += secondSums[next + 1] - secondSums[i + 1]
                index += stride
            }
            output[index] = roundedPixel(rb, ag)
        }
    }

    private fun sumClampedPixels(input: IntArray, start: Int, stride: Int, length: Int) {
        // Keep a halo for the later boxes so only the source, not intermediate sums, is clamped.
        val last = length - 1
        val first = input[start]
        var rb = packPair(first) * FirstWidth
        var ag = packPair(first ushr 8) * FirstWidth
        val end = 2 * (length + 2 * Halo - 1)
        var entering = FirstWidth / 2 + 1 - Halo
        for (i in 0 until end step 2) {
            firstSums[i] = rb
            firstSums[i + 1] = ag
            val next = input[start + entering.coerceIn(0, last) * stride]
            val previous = input[start + (entering - FirstWidth).coerceIn(0, last) * stride]
            rb += packPair(next) - packPair(previous)
            ag += packPair(next ushr 8) - packPair(previous ushr 8)
            entering++
        }
        firstSums[end] = rb
        firstSums[end + 1] = ag
    }

    private fun sumSecondBox(length: Int) {
        var rb = 0L
        var ag = 0L
        for (i in 0 until 2 * SecondWidth step 2) {
            rb += firstSums[i]
            ag += firstSums[i + 1]
        }
        val end = 2 * (length - 1)
        for (i in 0 until end step 2) {
            secondSums[i] = rb
            secondSums[i + 1] = ag
            val next = i + 2 * SecondWidth
            rb += firstSums[next] - firstSums[i]
            ag += firstSums[next + 1] - firstSums[i + 1]
        }
        secondSums[end] = rb
        secondSums[end + 1] = ag
    }

    // Two 32-bit lanes per sum, with no carries between channels. Round only after each axis.
    private fun packPair(pixel: Int): Long = ((pixel and 0xff0000).toLong() shl 16) or (pixel and 255).toLong()

    private fun roundedPixel(rb: Long, ag: Long): Int {
        val r = ((rb ushr 32).toInt() + BoxArea / 2) / BoxArea
        val b = (rb.toInt() + BoxArea / 2) / BoxArea
        val a = ((ag ushr 32).toInt() + BoxArea / 2) / BoxArea
        val g = (ag.toInt() + BoxArea / 2) / BoxArea
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        const val Radius = 25
        // Fit the normalized, truncated radius-25 kernel against hard edges.
        private const val FirstWidth = 15
        private const val SecondWidth = 21
        private const val ThirdWidth = 23
        private const val Halo = SecondWidth / 2 + ThirdWidth / 2
        private const val BoxArea = FirstWidth * SecondWidth * ThirdWidth
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
