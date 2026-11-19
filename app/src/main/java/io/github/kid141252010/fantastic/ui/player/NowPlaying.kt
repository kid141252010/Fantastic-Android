package io.github.kid141252010.fantastic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.formatTime
import kotlinx.coroutines.delay

/** 全屏播放页（第一阶段版本）。歌词页（AMLL）在第二阶段接入。 */
@Composable
fun NowPlaying(state: PlayerState, player: PlayerConnection, onClose: () -> Unit) {
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.mediaId, state.isPlaying) {
        while (true) {
            if (!dragging) position = player.position()
            delay(250)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
            // 吞掉点击，防止点穿到下面的页面
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // 背景：放大模糊的封面 + 暗色渐变（API 31+ 才有 blur，低版本只剩渐变）
        AsyncImage(
            model = state.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(80.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.65f))),
            ),
        )
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部把手，点按收起
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 24.dp)
                    .width(40.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.4f))
                    .clickable(onClick = onClose),
            )
            Spacer(Modifier.weight(0.5f))
            AsyncImage(
                model = state.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            )
            Spacer(Modifier.weight(0.5f))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    state.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            val duration = state.durationMs.coerceAtLeast(1)
            Slider(
                value = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    player.seekTo((dragValue * duration).toLong())
                    position = (dragValue * duration).toLong()
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.8f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = if (dragging) (dragValue * duration).toLong() else position
                Text(formatTime(shown), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                Text("-" + formatTime(state.durationMs - shown), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::previous, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.FastRewind, "上一首", tint = Color.White, modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = player::togglePlay, modifier = Modifier.size(80.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "暂停" else "播放",
                        tint = Color.White, modifier = Modifier.size(60.dp),
                    )
                }
                IconButton(onClick = player::next, enabled = state.hasNext, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.FastForward, "下一首", tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.weight(0.4f))
        }
    }
}
