package io.github.kid141252010.fantastic.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.playback.QueueEntry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

/** Queue pane geometry. */
@Composable
internal fun QueuePane(
    state: PlayerState,
    player: PlayerConnection,
    controlsInset: Dp,
    modifier: Modifier = Modifier,
    contentFade: Modifier,
    onScrollToStartChanged: (Boolean) -> Unit,
    header: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollBackward }.distinctUntilChanged().collect(onScrollToStartChanged)
    }
    var rows by remember(state.upNext, state.queueRevision) { mutableStateOf(state.upNext) }
    var draggedIndex by remember(state.queueRevision) { mutableIntStateOf(-1) }
    var dragOffset by remember(state.queueRevision) { mutableFloatStateOf(0f) }
    var clearRevision by remember { mutableStateOf<Long?>(null) }
    val canReorder = state.canEditQueue
    Column(modifier.fillMaxSize()) {
        header()
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 32.dp).then(contentFade),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QueueModeButton(PlayerSymbol.ModeShuffle, "随机播放", state.shuffle, state.canShuffle,
                Modifier.weight(1f), onClick = player::toggleShuffle)
            val repeatDescription = when (state.repeatMode) {
                Player.REPEAT_MODE_ALL -> "全部循环"
                Player.REPEAT_MODE_ONE -> "单曲循环"
                else -> "循环播放已关闭"
            }
            QueueModeButton(if (state.repeatMode == Player.REPEAT_MODE_ONE) PlayerSymbol.ModeRepeatOne else PlayerSymbol.ModeRepeat,
                repeatDescription, state.repeatMode != Player.REPEAT_MODE_OFF, state.canRepeat,
                Modifier.weight(1f), onClick = player::cycleRepeat)
            QueueModeButton(PlayerSymbol.ModeAutoplay, "自动播放不可用：Fantastic 未提供推荐队列", false, false,
                Modifier.weight(1f), unavailable = true)
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp).then(contentFade),
            verticalAlignment = Alignment.CenterVertically) {
            Text("继续播放", Modifier.weight(1f).padding(vertical = 14.dp),
                color = Color.White.copy(alpha = .94f),
                style = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium).withUserFontWeight(FontWeightRole.Title))
            if (rows.isNotEmpty() && state.canEditQueue) {
                TextButton(onClick = { clearRevision = state.queueRevision }) {
                    Text("清除队列", color = Color.White.copy(alpha = .65f), fontSize = 13.sp)
                }
            }
        }
        clearRevision?.let { revision ->
            AlertDialog(onDismissRequest = { clearRevision = null },
                title = { Text("清除队列") }, text = { Text("你确定要清除队列吗？") },
                confirmButton = { TextButton(onClick = { player.clearUpcoming(revision); clearRevision = null }) { Text("清除") } },
                dismissButton = { TextButton(onClick = { clearRevision = null }) { Text("取消") } })
        }
        // The active group heading stays in a separate sticky container.
        // No history section is fabricated from playlist indices before the current item.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().queueBottomFade(controlsInset).then(contentFade),
            contentPadding = PaddingValues(bottom = controlsInset + 16.dp),
        ) {
            if (rows.isEmpty()) item {
                Text("没有待播歌曲", Modifier.padding(horizontal = 32.dp),
                    color = Color.White.copy(alpha = .45f), fontSize = 17.sp)
            }
            items(rows, key = { it.occurrence }) { entry ->
                val position = rows.indexOf(entry)
                val actions = buildList {
                    if (state.canEditQueue) add(CustomAccessibilityAction("从待播队列移除") {
                        player.removeQueue(entry.index, state.queueRevision); true
                    })
                    if (canReorder && position > 0) add(CustomAccessibilityAction("上移") {
                        player.moveQueue(entry.index, rows[position - 1].index, state.queueRevision); true
                    })
                    if (canReorder && position < rows.lastIndex) add(CustomAccessibilityAction("下移") {
                        player.moveQueue(entry.index, rows[position + 1].index, state.queueRevision); true
                    })
                }
                val handle = if (canReorder) Modifier.pointerInput(entry.index, state.queueRevision) {
                    detectDragGestures(
                        onDragStart = { draggedIndex = entry.index; dragOffset = 0f },
                        onDragCancel = { draggedIndex = -1; dragOffset = 0f; rows = state.upNext },
                        onDragEnd = {
                            val destination = rows.indexOfFirst { it.index == draggedIndex }
                            val target = state.upNext.getOrNull(destination)
                            if (target != null) player.moveQueue(draggedIndex, target.index, state.queueRevision)
                            draggedIndex = -1
                            dragOffset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val active = visible.firstOrNull { it.key == entry.occurrence }
                            if (active != null) {
                                val center = active.offset + active.size / 2f + dragOffset
                                val target = visible.firstOrNull { center >= it.offset && center < it.offset + it.size }
                                val from = rows.indexOfFirst { it.index == entry.index }
                                val to = rows.indexOfFirst { it.occurrence == target?.key }
                                if (from >= 0 && to >= 0 && from != to) {
                                    rows = rows.toMutableList().apply { add(to, removeAt(from)) }
                                    dragOffset -= (target!!.offset - active.offset)
                                }
                            }
                        },
                    )
                } else Modifier
                QueueRow(entry, state.canSelectQueue, state.canEditQueue,
                    Modifier.zIndex(if (draggedIndex == entry.index) 1f else 0f)
                        .graphicsLayer { translationY = if (draggedIndex == entry.index) dragOffset else 0f }
                        .semantics { customActions = actions },
                    handle = handle,
                    reorderEnabled = canReorder,
                    onSelect = { player.selectQueue(entry.index, state.queueRevision) },
                    onRemove = { player.removeQueue(entry.index, state.queueRevision) })
            }
        }
    }
}

@Composable
private fun QueueModeButton(
    symbol: PlayerSymbol,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    unavailable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier.height(38.dp).clip(RoundedCornerShape(80.dp))
        .background(Color.White.copy(alpha = .08f))
        .clickable(enabled = enabled && onClick != null, role = Role.Button) { onClick?.invoke() }
        .semantics {
            contentDescription = description
            stateDescription = if (unavailable) "不可用" else if (selected) "已开启" else "已关闭"
            this.selected = selected
            if (!enabled || unavailable) disabled()
        }, contentAlignment = Alignment.Center) {
        QueueModeGlyph(symbol, selected, enabled && !unavailable, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    entry: QueueEntry,
    selectable: Boolean,
    editable: Boolean,
    modifier: Modifier,
    handle: Modifier,
    reorderEnabled: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val remove by rememberUpdatedState(onRemove)
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) remove()
        false
    })
    SwipeToDismissBox(state = dismiss, modifier = modifier,
        enableDismissFromStartToEnd = editable, enableDismissFromEndToStart = editable,
        backgroundContent = {
            val direction = dismiss.dismissDirection
            val width = if (direction == SwipeToDismissBoxValue.Settled) 0.dp else
                with(LocalDensity.current) { kotlin.math.abs(dismiss.requireOffset()).toDp() }
            Box(Modifier.fillMaxSize(), contentAlignment =
                if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Box(Modifier.width(width).height(60.dp).clipToBounds()
                    .background(Color(0xFF0088FF)).padding(horizontal = 16.dp),
                    contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                    Icon(Icons.Default.Delete, "移除", tint = Color.White)
                }
            }
        }) {
        Row(Modifier.fillMaxWidth().height(60.dp).clickable(enabled = selectable, onClick = onSelect).padding(start = 32.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(entry.track.artwork(96), null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(entry.track.title, color = Color.White.copy(alpha = .94f), fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.track.artist, color = Color.White.copy(alpha = .45f), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(handle.padding(top = 16.dp, bottom = 16.dp, end = 32.dp)
                .semantics {
                    contentDescription = if (reorderEnabled) "拖动以调整顺序" else "不能调整顺序"
                    if (!reorderEnabled) disabled()
                }) {
                Canvas(Modifier.size(28.dp)) {
                    val unit = size.width / 28f
                    for (line in 0..3) {
                        val y = 8 + 4 * line
                        drawLine(Color.White.copy(alpha = .18f),
                        Offset(6 * unit, y * unit), Offset(22 * unit, y * unit), 2 * unit, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private fun Modifier.queueBottomFade(controlsInset: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        // 16dp fade followed by transparent controls overlap.
        val end = (size.height - controlsInset.toPx()).coerceAtLeast(0f)
        val start = (end - 16.dp.toPx()).coerceAtLeast(0f)
        val mask = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = start, endY = end.coerceAtLeast(start + 1f))
        onDrawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }
