package io.github.kid141252010.fantastic.ui.theme

import android.annotation.TargetApi
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import android.util.Log
import android.util.LongSparseArray
import android.util.TypedValue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.roundToInt

/** Optional assets supplied only by the developer's ignored local font directory. */
internal object LocalAmFonts {
    private const val directory = "am-fonts"
    private const val pingFangCollection = "pingfang_ui.ttc"
    private var availableFonts: Set<String>? = null
    private val typefaces = LongSparseArray<Typeface?>()
    private var pingFangData: ByteBuffer? = null
    private val pingFangFamilies = LongSparseArray<FontFamily>()
    private val fallbackFiles = arrayOf(
        "sf_armenian.ttf", "sf_georgian.ttf", "sf_hebrew.ttf", "sf_bangla.ttf",
        "sf_devanagari.ttf", "sf_gujarati.ttf", "sf_gurmukhi.ttf", "sf_kannada.ttf",
        "sf_malayalam.ttf", "sf_odia.ttf", "sf_tamil.ttf", "sf_telugu.ttf", "sf_arabic.ttf",
    )

    fun resolvedWeight(weight: Int): Int = when (weight) {
        500 -> 510
        600 -> 590
        900 -> 1000
        else -> weight
    }

    @Synchronized
    fun typeface(context: Context, sizeSp: Float, weight: Int, rounded: Boolean = false): Typeface? {
        val assets = context.applicationContext.assets
        val files = availableFonts ?: try {
            (assets.list(directory)?.toSet() ?: emptySet()).also { availableFonts = it }
        } catch (error: IOException) {
            Log.w("LocalAmFonts", "Cannot list optional local fonts; using Android fonts.", error)
            emptySet<String>().also { availableFonts = it }
        }
        val primary = if (rounded) "sf_pro_rounded_android_ui.ttf" else "sf_pro_android_ui.ttf"
        if (primary !in files) return null

        val metrics = context.resources.displayMetrics
        val opticalSize = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics) /
            metrics.density).roundToInt().coerceAtLeast(1)
        val resolvedWeight = resolvedWeight(weight)
        val locale = context.resources.configuration.locales[0]
        val nastaliq = locale.language == "ur"
        val pingFangIndex = if (Build.VERSION.SDK_INT >= 29 && pingFangCollection in files) pingFangFaceIndex(locale) else -1
        val key = (opticalSize.toLong() shl 15) or (resolvedWeight.toLong() shl 5) or
            ((pingFangIndex + 1L) shl 2) or (if (rounded) 1L else 0L) or (if (nastaliq) 2L else 0L)
        val cached = typefaces.indexOfKey(key)
        if (cached >= 0) return typefaces.valueAt(cached)

        val variations = "'wght' $resolvedWeight, 'opsz' $opticalSize"
        val typeface = try {
            if (Build.VERSION.SDK_INT >= 29) {
                buildFallbackChain(assets, files, primary, variations, resolvedWeight, rounded, nastaliq, pingFangIndex)
            } else {
                Typeface.Builder(assets, "$directory/$primary")
                    .setWeight(resolvedWeight)
                    .setFontVariationSettings(variations)
                    .setFallback("sans-serif")
                    .build()
            }
        } catch (error: IOException) {
            Log.w("LocalAmFonts", "Cannot load local font $primary; using Android fonts.", error)
            null
        } catch (error: RuntimeException) {
            Log.w("LocalAmFonts", "Invalid local font $primary; using Android fonts.", error)
            null
        }
        typefaces.put(key, typeface)
        return typeface
    }

    // Faces in the locally supplied PingFang UI collection in TTC order.
    private fun pingFangFaceIndex(locale: Locale): Int = when {
        locale.language == "ja" -> 4
        locale.language != "zh" || locale.script == "Hans" -> 0
        locale.country == "HK" -> 2
        locale.country == "MO" -> 3
        locale.country == "TW" || locale.script == "Hant" -> 1
        else -> 0
    }

    @TargetApi(29)
    private fun pingFangFamily(assets: AssetManager, index: Int, weight: Int): FontFamily {
        val cjkWeight = weight.coerceIn(100, 900)
        val key = (index.toLong() shl 10) or cjkWeight.toLong()
        pingFangFamilies[key]?.let { return it }
        val buffer = pingFangData ?: assets.openFd("$directory/$pingFangCollection").use { descriptor ->
            descriptor.createInputStream().use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
            }
        }.also { pingFangData = it }
        val font = Font.Builder(buffer)
            .setTtcIndex(index)
            .setWeight(cjkWeight)
            .setFontVariationSettings("'wght' $cjkWeight")
            .build()
        return FontFamily.Builder(font).build().also { pingFangFamilies.put(key, it) }
    }

    @TargetApi(29)
    private fun buildFallbackChain(
        assets: AssetManager, files: Set<String>, primary: String, variations: String,
        weight: Int, rounded: Boolean, nastaliq: Boolean, pingFangIndex: Int,
    ): Typeface {
        fun family(file: String, variable: Boolean = true): FontFamily {
            val builder = Font.Builder(assets, "$directory/$file")
            if (variable) builder.setWeight(weight).setFontVariationSettings(variations)
            return FontFamily.Builder(builder.build()).build()
        }

        val builder = Typeface.CustomFallbackBuilder(family(primary))
        if (pingFangIndex >= 0) builder.addCustomFallback(pingFangFamily(assets, pingFangIndex, weight))
        for (file in fallbackFiles) {
            if (nastaliq && file == "sf_arabic.ttf") continue
            val selected = if (rounded) when (file) {
                "sf_armenian.ttf" -> "sf_armenian_rounded.ttf"
                "sf_georgian.ttf" -> "sf_georgian_rounded.ttf"
                "sf_hebrew.ttf" -> "sf_hebrew_rounded.ttf"
                "sf_arabic.ttf" -> "sf_arabic_rounded.ttf"
                else -> file
            } else file
            if (selected in files) builder.addCustomFallback(family(selected))
        }
        if (nastaliq) {
            val selected = if (weight == 700) "noto_nastaliq_urdu_ui_bold.ttf" else "noto_nastaliq_urdu_ui.ttf"
            if (selected in files) builder.addCustomFallback(family(selected, variable = false))
        }
        return builder.setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif")
            .build()
    }
}
