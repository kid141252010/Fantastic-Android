package io.github.kid141252010.fantastic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
