package io.github.kid141252010.fantastic.ui.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.Artwork
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.Motion
import io.github.kid141252010.fantastic.ui.formatTime
import io.github.kid141252010.fantastic.ui.sharedArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 全屏播放页从迷你条位置上滑并淡入（录屏约 300ms，用 MD3 emphasized decelerate） */
val nowPlayingEnter: EnterTransition =
    slideInVertically(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate)) { it } +
        fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate), initialAlpha = 0.3f)

val nowPlayingExit: ExitTransition =
    slideOutVertically(tween(Motion.Exit + 50, easing = Motion.EmphasizedAccelerate)) { it } +
        fadeOut(tween(Motion.Exit + 50, easing = Motion.EmphasizedAccelerate), targetAlpha = 0.3f)

private val SheetCorner = 28.dp

/**
 * 全屏播放页（Apple Music 安卓版）：左右统一 [Dimens.Player] 边距；
 * 封面与迷你条、歌词页小封面之间共享元素过渡；整页下拉跟手，超过阈值收起，否则回弹。
 */
@Composable
fun NowPlaying(
    state: PlayerState,
    player: PlayerConnection,
    lyrics: LyricsController,
    visibility: AnimatedVisibilityScope,
    onClose: () -> Unit,
) {
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val lyricsStatus by lyrics.status.collectAsStateWithLifecycle()
    val lyricsError by lyrics.error.collectAsStateWithLifecycle()
    // 加载中、失败也能打开歌词，直接看到状态和原因
    val lyricsAvailable = lyricsStatus != LyricsController.Status.None

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { Animatable(0f) }
    val dismissPx = with(density) { 140.dp.toPx() }
    // 展开过程中顶部圆角 28dp → 0，下拉时随距离重新变圆
    val enterCorner by visibility.transition.animateDp(
        transitionSpec = { tween(Motion.Enter, easing = Motion.EmphasizedDecelerate) },
        label = "corner",
    ) { if (it == EnterExitState.Visible) 0.dp else SheetCorner }
    val dragCorner = with(density) { (drag.value / 6f).toDp() }.coerceAtMost(SheetCorner)
    val corner = maxOf(enterCorner, dragCorner)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = drag.value
                shape = RoundedCornerShape(topStart = corner, topEnd = corner)
                clip = true
            }
            .background(Color(0xFF1C1C1E))
            // 吞掉点击，防止点穿到下面的页面
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (drag.value > dismissPx) onClose() else drag.animateTo(0f, spring(dampingRatio = 0.8f))
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                }
            },
    ) {
        Backdrop(state.artworkUri)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.Player),
        ) {
            // 顶部把手
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(36.dp, 5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.35f))
                        .clickable(onClick = onClose),
                )
            }
            AnimatedContent(
                targetState = showLyrics,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(Motion.Enter, easing = Motion.Emphasized)) togetherWith
                        fadeOut(tween(Motion.Exit, easing = Motion.Emphasized))
                },
                label = "lyrics",
            ) { lyricsMode ->
                // 歌词切换时封面在大图与小图之间过渡；刚展开时由外层动画负责从迷你条飞过来
                val art = if (transition.isRunning) this else visibility
                if (lyricsMode) {
                    Column(Modifier.fillMaxSize()) {
                        Header(state, art)
                        LyricsPane(lyrics, lyricsStatus, lyricsError, Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        AsyncImage(
                            model = state.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .sharedArtwork(art)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.1f)),
                        )
                        Spacer(Modifier.height(28.dp))
                        Text(
                            state.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Controls(state, player)
            // 底部开关：歌词（左）、待播清单（右，后续阶段）
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val bg by animateColorAsState(if (showLyrics) Color.White.copy(alpha = 0.85f) else Color.Transparent, label = "lyricsBg")
                val tint by animateColorAsState(
                    when {
                        showLyrics -> Color.Black.copy(alpha = 0.75f)
                        lyricsAvailable -> Color.White.copy(alpha = 0.6f)
                        else -> Color.White.copy(alpha = 0.25f)
                    },
                    label = "lyricsTint",
                )
                IconButton(onClick = { showLyrics = !showLyrics }, enabled = lyricsAvailable || showLyrics) {
                    Icon(
                        Icons.Rounded.FormatQuote,
                        contentDescription = "歌词",
                        tint = tint,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(4.dp),
                    )
                }
                IconButton(onClick = {}, enabled = false) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "待播清单",
                        tint = Color.White.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}

/** 背景：放大模糊的封面 + 暗色渐变（API 31+ 才有 blur，低版本只剩渐变） */
@Composable
private fun Backdrop(artwork: String?) {
    AsyncImage(
        model = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }.blur(80.dp),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.6f))),
        ),
    )
}

/** 歌词模式头部：60dp 小封面 + 歌名 / 艺人，左边与歌词、进度条对齐 */
@Composable
private fun Header(state: PlayerState, art: AnimatedVisibilityScope) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(state.artworkUri, 60.dp, 6.dp, Modifier.sharedArtwork(art))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                state.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LyricsPane(
    lyrics: LyricsController,
    status: LyricsController.Status,
    error: String?,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (status) {
            LyricsController.Status.Missing, LyricsController.Status.Failed, LyricsController.Status.None ->
                Text(
                    when (status) {
                        LyricsController.Status.Failed -> error ?: "歌词加载失败"
                        LyricsController.Status.Missing -> "暂无歌词"
                        else -> "这首歌没有歌词"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                )
            else -> {
                AndroidView(
                    factory = { lyrics.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    // 页面左右各多出 Dimens.Player，让放大的当前行不被裁；页面 CSS 留同样的内边距，文字仍和封面对齐
                    modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                        val extra = Dimens.Player.roundToPx() * 2
                        val width = constraints.maxWidth + extra
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
                    },
                )
                if (status == LyricsController.Status.Loading) {
                    Text("正在加载歌词…", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                }
            }
        }
    }
}

/** 进度条（4dp，拖动时加粗到 8dp）+ 时间 + 播放控制 */
@Composable
private fun Controls(state: PlayerState, player: PlayerConnection) {
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.mediaId, state.isPlaying) {
        while (true) {
            if (!dragging) position = player.position()
            delay(250)
        }
    }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f)
    val barHeight by animateDpAsState(if (dragging) 8.dp else 4.dp, label = "bar")

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    player.seekTo((f * duration).toLong())
                    position = (f * duration).toLong()
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragValue = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        player.seekTo((dragValue * duration).toLong())
                        position = (dragValue * duration).toLong()
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { change, amount ->
                    change.consume()
                    dragValue = (dragValue + amount / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(Color.White.copy(alpha = if (dragging) 1f else 0.75f)))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val shown = if (dragging) (dragValue * duration).toLong() else position
        Text(formatTime(shown), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        Text("-" + formatTime(state.durationMs - shown), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = player::previous, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.FastRewind, "上一首", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        // 播放中按钮带深色圆底（录屏）
        val playBg by animateColorAsState(if (state.isPlaying) Color.Black.copy(alpha = 0.28f) else Color.Transparent, label = "playBg")
        IconButton(
            onClick = player::togglePlay,
            modifier = Modifier.size(80.dp).clip(CircleShape).background(playBg),
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) "暂停" else "播放",
                tint = Color.White, modifier = Modifier.size(52.dp),
            )
        }
        IconButton(onClick = player::next, enabled = state.hasNext, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.FastForward, "下一首", tint = Color.White, modifier = Modifier.size(44.dp))
        }
    }
}
