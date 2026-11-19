package io.github.kid141252010.fantastic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.Artwork

/** 底栏上方的迷你播放条。交互细节（上滑展开等）等录屏后再还原。 */
@Composable
fun MiniPlayer(state: PlayerState, player: PlayerConnection, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(state.artworkUri, 44.dp, 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(state.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.error != null) {
                Text("播放失败：${state.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            } else {
                Text(
                    state.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = player::togglePlay) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(onClick = player::next, enabled = state.hasNext) {
            Icon(Icons.Rounded.FastForward, contentDescription = "下一首", modifier = Modifier.size(28.dp))
        }
    }
}
