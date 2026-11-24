package io.github.kid141252010.fantastic.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.qualityPresentation
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.ActionSheetColors
import io.github.kid141252010.fantastic.ui.actionSheetColors

/** Player action sheets use their own surface, independent of the playback backdrop. */
@Composable
internal fun PlayerPanels(
    panel: PlayerPanel?,
    onDismiss: () -> Unit,
    trackPanel: @Composable (ActionSheetColors) -> Unit,
    entityPanel: @Composable (ActionSheetColors) -> Unit,
    qualityState: PlayerState,
    onOpenSettings: () -> Unit,
) {
    val showing = panel ?: return
    if (showing == PlayerPanel.Quality) {
        QualityDetailsDialog(qualityState, onDismiss, onOpenSettings)
        return
    }
    val palette = actionSheetColors()
    val name = when (showing) {
        PlayerPanel.Track -> "歌曲操作"
        PlayerPanel.Entities -> "查看艺人和专辑"
        PlayerPanel.Quality -> "当前码流规格"
    }
    io.github.kid141252010.fantastic.ui.GlassSheet(name, onDismiss, showHeader = false) {
        when (showing) {
            PlayerPanel.Track -> trackPanel(palette)
            PlayerPanel.Entities -> entityPanel(palette)
            PlayerPanel.Quality -> Unit
        }
    }
}

internal fun Modifier.panelTouchBoundary() = pointerInput(Unit) {
    // Keep touches on this surface, letting descendants handle their own clicks and drags.
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}


@Composable
internal fun CatalogActionContent(entities: List<CatalogHit>, palette: ActionSheetColors, onOpen: (CatalogHit) -> Unit) {
    entities.forEach { entity ->
        val action = when (entity.kind) {
            "artist" -> "前往艺人"
            "album" -> "前往专辑"
            else -> return@forEach
        }
        Row(Modifier.fillMaxWidth().height(72.dp).clickable { onOpen(entity) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            io.github.kid141252010.fantastic.ui.Artwork(entity.artwork, 40.dp, if (entity.kind == "artist") 20.dp else 5.dp)
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(action, color = palette.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entity.title, Modifier.padding(top = 4.dp), color = palette.secondary, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun QualityDetailsDialog(state: PlayerState, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val presentation = qualityPresentation(state.currentTrack?.source, state.qualityLabel, state.audioDetails)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (presentation?.lossless == true) {
            {
                PlayerGlyph(
                    PlayerSymbol.Lossless,
                    null,
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.width(85.dp).height(52.dp),
                )
            }
        } else null,
        title = { Text(presentation?.title ?: "当前码流规格") },
        text = { Text(state.audioDetails ?: "未知") },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenSettings()
            }) { Text("设置") }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}

