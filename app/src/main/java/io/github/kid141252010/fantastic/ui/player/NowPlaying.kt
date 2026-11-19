package io.github.kid141252010.fantastic.ui.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.kid141252010.fantastic.FantasticApp
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
    val prefs = (LocalContext.current.applicationContext as FantasticApp).prefs
    // 记住上次停留的页面（封面 / 歌词 / 待播清单），重开播放页、重启应用都回到这里
    var page by rememberSaveable { mutableStateOf(Page.from(prefs.playerPage)) }
    fun open(target: Page) {
        page = if (page == target) Page.Cover else target
        prefs.playerPage = page.key
    }
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
            .background(Color(0xFF1C1C1E)),
    ) {
        // 下拉收起只放在顶部（把手、歌词/清单页的小封面标题栏），中间的歌词和清单自己滑动，不和它抢手势
        val dismissDrag = Modifier.pointerInput(Unit) {
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
        }
        Backdrop(state.artworkUri)
        // 吞掉落在空白处的点击，防止点穿到下面的页面（在内容之下，不包住歌词）
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {})
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.Player),
        ) {
            // 顶部把手
            Box(Modifier.fillMaxWidth().then(dismissDrag).padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(36.dp, 5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.35f))
                        .clickable(onClick = onClose),
                )
            }
            // 歌词页播放中 3 秒没操作：控制区淡出，歌词一直铺到底（Apple Music）；任何触摸都让它回来
            var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
            var controlsShown by remember { mutableStateOf(true) }
            val autoHide = page == Page.Lyrics && state.isPlaying
            LaunchedEffect(autoHide, lastTouch) {
                controlsShown = true
                if (autoHide) {
                    delay(IdleHideMs)
                    controlsShown = false
                }
            }
            var controlsHeight by remember { mutableIntStateOf(0) }
            // 歌词底部渐隐的终点（像素）：控制区显示时停在控制区上沿，隐藏后到屏幕底部
            val lyricsBottom by animateFloatAsState(
                if (controlsShown) controlsHeight.toFloat() else 0f,
                tween(if (controlsShown) Motion.Enter else 600, easing = Motion.Emphasized), label = "lyricsBottom",
            )
            val controlsAlpha by animateFloatAsState(
                if (controlsShown) 1f else 0f,
                tween(if (controlsShown) Motion.Enter else 600, easing = Motion.Emphasized), label = "controlsAlpha",
            )
            val controlsPad = with(density) { controlsHeight.toDp() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // 只观察不消费：Initial 阶段先于歌词 WebView 看到触摸
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                val t = System.currentTimeMillis()
                                if (t - lastTouch > 250) lastTouch = t
                            }
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn(tween(Motion.Enter, easing = Motion.Emphasized)) togetherWith
                            fadeOut(tween(Motion.Exit, easing = Motion.Emphasized))
                    },
                    label = "page",
                ) { shown ->
                    // 歌词切换时封面在大图与小图之间过渡；刚展开时由外层动画负责从迷你条飞过来
                    val art = if (transition.isRunning) this else visibility
                    when (shown) {
                        Page.Lyrics -> Column(Modifier.fillMaxSize()) {
                            Header(state, art, dismissDrag)
                            // 歌词铺到底，控制区盖在上面；底部渐隐跟着控制区走
                            LyricsPane(lyrics, lyricsStatus, lyricsError, { lyricsBottom }, Modifier.weight(1f).fillMaxWidth())
                        }
                        Page.Queue -> Column(Modifier.fillMaxSize().padding(bottom = controlsPad)) {
                            Header(state, art, dismissDrag)
                            QueuePane(state, player, Modifier.weight(1f).fillMaxWidth())
                        }
                        Page.Cover -> Column(
                            Modifier.fillMaxSize().padding(bottom = controlsPad).then(dismissDrag),
                            verticalArrangement = Arrangement.Center,
                        ) {
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
                if (controlsShown || controlsAlpha > 0.01f) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = controlsAlpha }
                            .onSizeChanged { controlsHeight = it.height },
                    ) {
                        Spacer(Modifier.height(16.dp))
                        Controls(state, player)
                        // 底部开关和 Apple Music 一样排在上一首 / 下一首正下方：歌词（左）、待播清单（右）
                        Row(
                            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                                PageToggle(Icons.Rounded.FormatQuote, "歌词", page == Page.Lyrics, lyricsAvailable) { open(Page.Lyrics) }
                            }
                            Spacer(Modifier.width(80.dp))
                            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                                PageToggle(Icons.AutoMirrored.Rounded.QueueMusic, "待播清单", page == Page.Queue, state.queue.isNotEmpty()) {
                                    open(Page.Queue)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val IdleHideMs = 3000L

enum class Page(val key: String) {
    Cover("cover"), Lyrics("lyrics"), Queue("queue");

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: Cover
    }
}

/** 32dp 的图标按钮，没有 IconButton 的 48dp 留白，这样左右边缘能和封面对齐；选中时白底黑图标（Apple Music） */
@Composable
private fun PageToggle(icon: ImageVector, label: String, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Color.White.copy(alpha = 0.85f) else Color.Transparent, label = "toggleBg")
    val tint by animateColorAsState(
        when {
            selected -> Color.Black.copy(alpha = 0.75f)
            available -> Color.White.copy(alpha = 0.6f)
            else -> Color.White.copy(alpha = 0.25f)
        },
        label = "toggleTint",
    )
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = available || selected, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** 待播清单：当前曲目高亮，点一首直接跳过去 */
@Composable
private fun QueuePane(state: PlayerState, player: PlayerConnection, modifier: Modifier) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.queueIndex.coerceAtLeast(0))
    Column(modifier) {
        Text(
            "待播清单", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize().edgeFade(), state = listState) {
            itemsIndexed(state.queue, key = { i, e -> "$i:${e.id}" }) { i, entry ->
                val current = i == state.queueIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { player.skipTo(i) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(entry.artworkUri, 44.dp, 4.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            entry.title, color = Color.White.copy(alpha = if (current) 1f else 0.85f), fontSize = 15.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.artist, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                            contentDescription = "正在播放", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 上下边缘渐隐：在离屏层里用 DstIn 乘一条透明→不透明→透明的渐变。
 * 不用网页里的 mask-image（新版 WebView 在播放页里会整块画不出来），由原生对 WebView 整体做。
 */
fun Modifier.edgeFade(top: Float = 0.1f, bottom: Float = 0.12f, bottomInset: () -> Float = { 0f }): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        // bottomInset：底部被控制区盖住的高度（像素），渐隐在它上沿结束，下面完全透明
        val end = (1f - bottomInset() / size.height.coerceAtLeast(1f)).coerceIn(top + bottom, 1f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                top to Color.Black,
                end - bottom to Color.Black,
                end to Color.Transparent,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
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
private fun Header(state: PlayerState, art: AnimatedVisibilityScope, dismissDrag: Modifier) {
    Row(Modifier.fillMaxWidth().then(dismissDrag).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    bottomInset: () -> Float,
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
                    }.edgeFade(bottomInset = bottomInset),
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
