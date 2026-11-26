package io.github.kid141252010.fantastic.ui.player

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.Glass

/**
 * 常驻 48dp Apple material capsule；sheet host 通过同一 frame 驱动透明度与封面测量。
 */
@Composable
internal fun MiniPlayer(
    state: PlayerState,
    player: PlayerConnection,
    sheetFrame: State<PlayerSheetFrame>,
    artworkSlot: @Composable (Modifier) -> Unit,
    dragModifier: Modifier,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val primary = if (dark) Color.White else Color.Black
    val secondary = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
    val density = LocalDensity.current
    Glass(
        shape = CircleShape,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().height(Dimens.MiniPlayerHeight)
            .graphicsLayer { alpha = sheetFrame.value.miniAlpha }
            .then(dragModifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "打开正在播放", onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            artworkSlot(Modifier.size(30.dp))
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.5f))) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(state.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.error != null) {
                        Text("播放失败：${state.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(state.artist, fontSize = 12.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.size(32.dp).clickable(enabled = enabled, role = Role.Button, onClick = player::togglePlay),
                contentAlignment = Alignment.Center) {
                val paused = !state.playWhenReady || state.ended || state.error != null
                val symbol = if (paused) PlayerSymbol.MiniPlay else PlayerSymbol.MiniPause
                PlayerGlyph(symbol, if (paused) "播放" else "暂停", primary, Modifier.size(symbol.intrinsicSize))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(32.dp).clickable(enabled = enabled && state.hasNext, role = Role.Button, onClick = player::next)
                .padding(1.dp), contentAlignment = Alignment.Center) {
                PlayerGlyph(PlayerSymbol.MiniNext, "下一首", if (state.hasNext) primary else secondary,
                    Modifier.size(PlayerSymbol.MiniNext.intrinsicSize))
            }
        }
    }
}
