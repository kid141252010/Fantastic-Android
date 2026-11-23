package io.github.kid141252010.fantastic.ui.player

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.kid141252010.fantastic.ui.ActionSheetColors


private data class ShareDestination(val component: ComponentName, val label: String, val icon: Drawable?)

@Composable
internal fun LyricShareSheet(
    request: PlayerPanel.Share,
    artwork: String?,
    palette: ActionSheetColors,
    selection: Set<Int>,
    onSelection: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lines = remember(request.lines) { request.lines.map { it.text } }
    val selectedText = remember(lines, selection) { lines.filterIndexed { index, _ -> index in selection }.joinToString("\n") }
    val characterCount = remember(selectedText) { selectedText.length }
    val shareEnabled = selectedText.isNotBlank()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = request.startIndex.coerceIn(0, lines.lastIndex.coerceAtLeast(0)))
    val destinations by produceState(emptyList<ShareDestination>(), context) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(lyricShareIntent(""), PackageManager.MATCH_DEFAULT_ONLY)
                .asSequence()
                .filter { it.activityInfo.exported && it.activityInfo.packageName != context.packageName }
                .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .map { target ->
                    ShareDestination(ComponentName(target.activityInfo.packageName, target.activityInfo.name),
                        target.loadLabel(pm).toString(), target.loadIcon(pm))
                }.toList()
        }
    }
    fun lyricText() = selectedText
    fun share(destination: ShareDestination?) {
        if (!shareEnabled) return
        val intent = lyricShareIntent(lyricText())
        try {
            context.startActivity(if (destination == null) Intent.createChooser(intent, null) else intent.setComponent(destination.component))
            onDismiss()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "找不到此分享应用", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "无法向此应用分享", Toast.LENGTH_SHORT).show()
        }
    }
    val dark = isSystemInDarkTheme()
    val activeBackground = if (dark) Color(0xFF2C2C2E) else Color.White
    BoxWithConstraints(modifier.background(palette.surface)) {
        val pickerHeight = (maxHeight * .55f).coerceAtLeast(240.dp)
        AppleArtworkBackdrop(artwork, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 80.dp).background(palette.surface).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerArtwork(artwork, Modifier.size(30.dp), 5.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    val count = selection.count { it in lines.indices }
                    Text("已选 $count 行歌词", color = palette.label, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("$characterCount 个字符", color = palette.secondary, fontSize = 15.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(.5.dp).background(palette.divider))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = pickerHeight),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    val checked = index in selection
                    val topCorner = if (!checked || index - 1 !in selection) 16.dp else 8.dp
                    val bottomCorner = if (!checked || index + 1 !in selection) 16.dp else 8.dp
                    val rowColor = if (checked) PlayerPrimary else palette.label.copy(alpha = .7f)
                    Text(
                        text = line,
                        color = rowColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                            .clip(RoundedCornerShape(topCorner, topCorner, bottomCorner, bottomCorner))
                            .background(if (checked) Color.Transparent else activeBackground)
                            .semantics { selected = checked }
                            .clickable(role = Role.Button) {
                                onSelection(if (checked) selection - index else selection + index)
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(.5.dp).background(palette.divider))
            LazyRow(
                modifier = Modifier.fillMaxWidth().background(palette.surface),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(destinations, key = { it.component.flattenToString() }) { target ->
                    DestinationTile(target.label, palette, shareEnabled, onClick = { share(target) }) {
                        AndroidView(factory = ::ImageView, modifier = Modifier.size(48.dp), update = { it.setImageDrawable(target.icon) })
                    }
                }
                item {
                    DestinationTile("其他应用", palette, shareEnabled, onClick = { share(null) }) {
                        Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(48.dp), tint = palette.label)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(palette.surface), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("歌词", lyricText()))
                    onDismiss()
                }, enabled = shareEnabled) { Text("复制歌词", color = if (shareEnabled) palette.label else palette.secondary) }
                TextButton(onClick = { share(null) }, enabled = shareEnabled) { Text("分享", color = if (shareEnabled) palette.label else palette.secondary) }
                TextButton(onClick = onDismiss) { Text("取消", color = palette.label) }
            }
        }
    }
}

@Composable
private fun DestinationTile(
    label: String, palette: ActionSheetColors, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit,
) {
    Column(
        Modifier.width(72.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, color = if (enabled) palette.label else palette.secondary, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp))
    }
}

private fun lyricShareIntent(text: String) = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, text)
}
