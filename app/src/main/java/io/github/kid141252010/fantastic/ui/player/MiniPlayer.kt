package io.github.kid141252010.fantastic.ui.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.Artwork
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.Glass
import io.github.kid141252010.fantastic.ui.sharedArtwork

/**
 * 悬浮在标签栏上方的玻璃胶囊迷你播放条（Apple Music 安卓版）。
 * 封面参与共享元素过渡：点开时飞到全屏播放页的封面位置。
 */
@Composable
fun MiniPlayer(
    state: PlayerState,
    player: PlayerConnection,
    visibility: AnimatedVisibilityScope?,
    onOpen: () -> Unit,
) {
    Glass(CircleShape, Modifier.fillMaxWidth().height(Dimens.MiniPlayerHeight)) {
        Row(
            Modifier.fillMaxSize().clickable(onClick = onOpen).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(state.artworkUri, 34.dp, 6.dp, Modifier.sharedArtwork(visibility))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    state.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (state.error != null) {
                    Text("播放失败：${state.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                } else {
                    Text(
                        state.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = player::togglePlay) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(onClick = player::next, enabled = state.hasNext) {
                Icon(Icons.Rounded.FastForward, contentDescription = "下一首", modifier = Modifier.size(28.dp))
            }
        }
    }
}
