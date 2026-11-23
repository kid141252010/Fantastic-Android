package io.github.kid141252010.fantastic.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/** 只关闭圆形控件的矩形补命中，不改变 Material 的布局占位。 */
@Composable
internal fun CircularTouchScope(content: @Composable () -> Unit) {
    val configuration = LocalViewConfiguration.current
    val circularConfiguration = remember(configuration) {
        object : ViewConfiguration by configuration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides circularConfiguration, content = content)
}

@Composable
internal fun CircularIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    CircularTouchScope {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/**
 * 统一的尺寸与动效。数值来自 Apple Music 安卓版录屏（1080×2400，按 2.625 换算 dp），
 * 动效曲线与时长用 Material 3 的 emphasized 规范。
 */
object Dimens {
    /** 普通页面左右边距，迷你播放条、标签栏也对齐到这里 */
    val Page = 20.dp
    /** 全屏播放页左右边距：封面、歌词、进度条、按钮统一对齐 */
    val Player = 32.dp
    val MiniPlayerHeight = 48.dp
    val TabBarHeight = 60.dp
    /** 迷你播放条与标签栏之间、标签栏与屏幕底边之间的间距 */
    val BarGap = 8.dp
    val BarBottom = 12.dp
}

object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val Enter = 400
    const val Exit = 250
}

/** 页面底部要给悬浮的迷你播放条 + 标签栏留出的空间（含手势条），列表用作 contentPadding */
val LocalBarsPadding = compositionLocalOf { PaddingValues(0.dp) }

/** 背后内容的模糊源，玻璃条从这里取样 */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** 当前窗口内操作面板的可见性；外壳据此隐藏底层导航与迷你播放条。 */
val LocalGlassSheetVisibility = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * 播放条、菜单与展开控件共用的 Material 玻璃材质。
 * API 31+ 在当前窗口的 LocalHaze 源上模糊；无源或低版本使用不透明底色，保证可读性。
 */
@Composable
fun Glass(
    shape: Shape = MaterialTheme.shapes.extraLarge,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surfaceContainerHigh
    val haze = LocalHaze.current
    val style = remember(surface) {
        HazeStyle(
            backgroundColor = surface,
            tint = HazeTint(surface.copy(alpha = 0.8f)),
            blurRadius = 24.dp,
            noiseFactor = 0.02f,
            fallbackTint = HazeTint(surface),
        )
    }
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(
            modifier
                .shadow(8.dp, shape)
                .clip(shape)
                .then(
                    if (haze != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.hazeChild(haze, style)
                    else Modifier.background(surface),
                )
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.8f), shape),
            content = content,
        )
    }
}
