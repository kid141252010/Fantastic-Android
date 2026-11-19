package io.github.kid141252010.fantastic.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

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

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

const val ARTWORK_KEY = "now-playing-artwork"

/**
 * 封面在迷你播放条、全屏封面、歌词页小封面之间的共享元素过渡：
 * 同一时刻只有一处是可见的，切换时封面从旧位置连续地飞到新位置。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArtwork(visibility: AnimatedVisibilityScope?): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    if (visibility == null) return this
    return with(shared) {
        this@sharedArtwork.sharedElement(
            rememberSharedContentState(ARTWORK_KEY),
            visibility,
            boundsTransform = ArtworkBounds,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val ArtworkBounds = BoundsTransform { _, _ -> tween(Motion.Enter, easing = Motion.Emphasized) }

/**
 * 类液态玻璃：背后内容高斯模糊 + 半透明底色 + 上亮下暗的细描边 + 顶部一道柔和高光。
 * API 31 以下 haze 自动退化为半透明底色。
 */
@Composable
fun Glass(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val haze = LocalHaze.current
    val tint = if (dark) Color(0xFF262628).copy(alpha = 0.62f) else Color(0xFFF5F5F7).copy(alpha = 0.66f)
    val style = HazeStyle(
        backgroundColor = if (dark) Color.Black else Color.White,
        tint = HazeTint(tint),
        blurRadius = 24.dp,
        noiseFactor = 0.08f,
        fallbackTint = HazeTint(tint.copy(alpha = 0.94f)),
    )
    val rim = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.14f))
        else listOf(Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.1f)),
    )
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.10f else 0.35f),
        0.45f to Color.Transparent,
    )
    Box(
        modifier
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape)
            .then(if (haze != null) Modifier.hazeChild(haze, style) else Modifier)
            .drawWithContent {
                if (haze == null) drawRect(tint.copy(alpha = 0.94f))
                drawRect(sheen, topLeft = Offset.Zero)
                drawContent()
            }
            .border(0.6.dp, rim, shape),
        content = content,
    )
}
