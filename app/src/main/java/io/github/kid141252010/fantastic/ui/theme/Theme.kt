package io.github.kid141252010.fantastic.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.data.FontWeights
import io.github.kid141252010.fantastic.data.Prefs

/** Apple Music 的品牌红。 */
val AppleRed = Color(0xFFFA2D48)

private val Light = lightColorScheme(
    primary = AppleRed,
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF8A8A8E),
    outlineVariant = Color(0xFFE5E5EA),
)

private val Dark = darkColorScheme(
    primary = AppleRed,
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8D8D93),
    outlineVariant = Color(0xFF38383A),
)

internal enum class FontWeightRole { Body, Title }

internal val LocalFontWeights = staticCompositionLocalOf { FontWeights() }

private fun TextStyle.withLocalAmFont(context: Context, customWeight: Int = 0): TextStyle {
    val weight = if (customWeight == 0) fontWeight?.weight ?: 400 else customWeight
    val face = LocalAmFonts.typeface(context, fontSize.value, weight, exactWeight = customWeight != 0)
        ?: return if (customWeight == 0) this else copy(fontWeight = FontWeight(weight))
    val resolvedWeight = if (customWeight == 0) LocalAmFonts.resolvedWeight(weight) else weight
    return copy(fontFamily = FontFamily(face), fontWeight = FontWeight(resolvedWeight))
}

@Composable
internal fun TextStyle.withLocalAmFont(): TextStyle {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val weight = LocalFontWeights.current.body
    return remember(this, context, configuration, weight) { withLocalAmFont(context, weight) }
}

/** Apply an override after a screen's explicit weight without changing its default appearance. */
@Composable
internal fun TextStyle.withUserFontWeight(role: FontWeightRole = FontWeightRole.Body, defaultWeight: Int? = null): TextStyle {
    val weights = LocalFontWeights.current
    val weight = if (role == FontWeightRole.Title) weights.title else weights.body
    if (weights.body == 0 && weights.title == 0) return this
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(this, context, configuration, weight, defaultWeight) {
        val base = if (weight == 0 && defaultWeight != null) copy(fontWeight = FontWeight(defaultWeight)) else this
        base.withLocalAmFont(context, weight)
    }
}

@Composable
fun FantasticTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val weights by prefs.fontWeightsState.collectAsStateWithLifecycle()
    val typography = remember(context.assets, configuration.locales, configuration.fontScale,
        configuration.densityDpi, weights.body, weights.title) {
        val defaults = Typography()
        defaults.copy(
            displayLarge = defaults.displayLarge.withLocalAmFont(context, weights.title),
            displayMedium = defaults.displayMedium.withLocalAmFont(context, weights.title),
            displaySmall = defaults.displaySmall.withLocalAmFont(context, weights.title),
            headlineLarge = defaults.headlineLarge.withLocalAmFont(context, weights.title),
            headlineMedium = defaults.headlineMedium.withLocalAmFont(context, weights.title),
            headlineSmall = defaults.headlineSmall.withLocalAmFont(context, weights.title),
            titleLarge = defaults.titleLarge.withLocalAmFont(context, weights.title),
            titleMedium = defaults.titleMedium.withLocalAmFont(context, weights.title),
            titleSmall = defaults.titleSmall.withLocalAmFont(context, weights.title),
            bodyLarge = defaults.bodyLarge.withLocalAmFont(context, weights.body),
            bodyMedium = defaults.bodyMedium.withLocalAmFont(context, weights.body),
            bodySmall = defaults.bodySmall.withLocalAmFont(context, weights.body),
            labelLarge = defaults.labelLarge.withLocalAmFont(context, weights.body),
            labelMedium = defaults.labelMedium.withLocalAmFont(context, weights.body),
            labelSmall = defaults.labelSmall.withLocalAmFont(context, weights.body),
        )
    }
    CompositionLocalProvider(LocalFontWeights provides weights) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = typography, content = content)
    }
}

/**
 * 标签栏选中色：Android 12+ 用 Material You 动态取色，低版本用品牌蓝。
 */
@Composable
fun TabAccent(): Color {
    val dark = isSystemInDarkTheme()
    if (Build.VERSION.SDK_INT >= 31) {
        val context = LocalContext.current
        return (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    }
    return if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
}
