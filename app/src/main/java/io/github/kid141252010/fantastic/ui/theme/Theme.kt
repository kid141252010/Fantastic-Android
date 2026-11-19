package io.github.kid141252010.fantastic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

@Composable
fun FantasticTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
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
