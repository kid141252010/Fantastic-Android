package io.github.kid141252010.fantastic.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

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

private fun TextStyle.withLocalAmFont(context: Context): TextStyle {
    val weight = fontWeight?.weight ?: 400
    val face = LocalAmFonts.typeface(context, fontSize.value, weight) ?: return this
    return copy(fontFamily = FontFamily(face), fontWeight = FontWeight(LocalAmFonts.resolvedWeight(weight)))
}

@Composable
internal fun TextStyle.withLocalAmFont(): TextStyle {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(this, context, configuration) { withLocalAmFont(context) }
}

@Composable
fun FantasticTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val typography = remember(context.assets, configuration.locales, configuration.fontScale, configuration.densityDpi) {
        val defaults = Typography()
        defaults.copy(
            displayLarge = defaults.displayLarge.withLocalAmFont(context),
            displayMedium = defaults.displayMedium.withLocalAmFont(context),
            displaySmall = defaults.displaySmall.withLocalAmFont(context),
            headlineLarge = defaults.headlineLarge.withLocalAmFont(context),
            headlineMedium = defaults.headlineMedium.withLocalAmFont(context),
            headlineSmall = defaults.headlineSmall.withLocalAmFont(context),
            titleLarge = defaults.titleLarge.withLocalAmFont(context),
            titleMedium = defaults.titleMedium.withLocalAmFont(context),
            titleSmall = defaults.titleSmall.withLocalAmFont(context),
            bodyLarge = defaults.bodyLarge.withLocalAmFont(context),
            bodyMedium = defaults.bodyMedium.withLocalAmFont(context),
            bodySmall = defaults.bodySmall.withLocalAmFont(context),
            labelLarge = defaults.labelLarge.withLocalAmFont(context),
            labelMedium = defaults.labelMedium.withLocalAmFont(context),
            labelSmall = defaults.labelSmall.withLocalAmFont(context),
        )
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = typography, content = content)
}

/**
 * 标签栏选中色：Android 12+ 用 Material You 动态取色（录屏里 Apple Music 跟随系统强调色，是蓝色），
 * 低版本用 Apple 蓝。
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
