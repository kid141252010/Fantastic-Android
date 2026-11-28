package io.github.kid141252010.fantastic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.appAccentColors
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight

// Apple Music Android 7.0.0/1614: the page palette is local, not a settings/player theme.
@Composable
internal fun amPageBackground(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.Black else Color.White

@Composable
internal fun amLabelColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White else Color.Black

@Composable
internal fun amSecondaryLabelColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0x99EBEBF5) else Color(0x993C3C43)

@Composable
private fun amDividerColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xA6545458) else Color(0x4A3C3C43)

internal enum class AmSongRowStyle { Library, Collection, AlbumNumbered, Search, Shelf, Chart }

/** The six native XML layouts have distinct slots; none inherits Material ListItem geometry. */
@Composable
internal fun AmSongRow(
    title: String,
    subtitle: String,
    style: AmSongRowStyle,
    artworkUrl: String?,
    trackNumber: String? = null,
    rank: Int = 0,
    favorite: Boolean = false,
    playing: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    queueSwipeLabel: String? = null,
    onQueueSwipe: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val numbered = style == AmSongRowStyle.AlbumNumbered
    val search = style == AmSongRowStyle.Search
    val chart = style == AmSongRowStyle.Chart
    val collection = style == AmSongRowStyle.Collection
    val artworkSize = if (search) 56.dp else 48.dp
    val verticalPadding = when (style) {
        AmSongRowStyle.Library -> 8.dp
        AmSongRowStyle.Search -> 10.dp
        AmSongRowStyle.AlbumNumbered -> 0.dp
        else -> 4.dp
    }
    val gap = when (style) {
        AmSongRowStyle.Collection -> if (rank > 0) 6.dp else 12.dp
        AmSongRowStyle.Search -> 10.dp
        AmSongRowStyle.Chart -> 8.dp
        AmSongRowStyle.AlbumNumbered -> 8.dp
        else -> 16.dp
    }
    val endPadding = when (style) {
        AmSongRowStyle.Library, AmSongRowStyle.Collection, AmSongRowStyle.AlbumNumbered -> 10.dp
        AmSongRowStyle.Search -> 8.dp
        else -> 0.dp
    }
    val dividerStart = when (style) {
        AmSongRowStyle.AlbumNumbered -> 48.dp
        AmSongRowStyle.Search -> 16.dp
        AmSongRowStyle.Collection -> 76.dp
        AmSongRowStyle.Chart -> 104.dp
        else -> 80.dp
    }
    val label = amLabelColor()
    val accent = appAccentColors()
    val secondary = if (style == AmSongRowStyle.Library || chart) Color(0xFF8E8E93) else amSecondaryLabelColor()
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp,
        fontWeight = FontWeight.Normal, lineHeight = TextUnit.Unspecified).withUserFontWeight(defaultWeight = 400)
    val subtitleStyle = MaterialTheme.typography.bodySmall.copy(fontSize = if (search) 13.sp else 12.sp,
        fontWeight = FontWeight.Normal, lineHeight = TextUnit.Unspecified).withUserFontWeight(defaultWeight = 400)
    MediaRowInteraction(modifier, playing, onClick, queueSwipeLabel, onQueueSwipe) { rowModifier ->
        Box(rowModifier.background(amPageBackground())) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (numbered) 48.dp else artworkSize + verticalPadding * 2)
                .padding(end = endPadding), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                    if (favorite) Icon(Icons.Rounded.Star, "喜爱", Modifier.size(16.dp).padding(3.dp), tint = Color(0xFFFFCC00))
                }
                if (numbered) {
                    Text(trackNumber.orEmpty(), Modifier.widthIn(min = 24.dp), color = secondary,
                        style = titleStyle, textAlign = TextAlign.Center, maxLines = 1)
                } else {
                    Md3MediaArtwork(artworkUrl, Modifier.padding(vertical = verticalPadding).size(artworkSize),
                        shape = RoundedCornerShape(5.dp))
                }
                if (chart) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (rank > 0) rank.toString() else "", Modifier.widthIn(min = 32.dp), color = label,
                        style = titleStyle, textAlign = TextAlign.Center, maxLines = 1)
                } else if (collection && rank > 0) {
                    Text(rank.toString(), Modifier.width(34.dp), color = label,
                        style = titleStyle, textAlign = TextAlign.Center, maxLines = 1)
                }
                Spacer(Modifier.width(gap))
                Column(Modifier.weight(1f).padding(end = 16.dp)
                    .then(if (chart) Modifier.padding(top = 10.dp, bottom = 8.dp) else Modifier)) {
                    Text(title, color = if (playing) accent.primary else label,
                        style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!numbered && subtitle.isNotBlank()) {
                        Text(subtitle, Modifier.padding(top = if (chart) 4.dp else if (search) 2.dp else 0.dp,
                            bottom = if (collection) 3.dp else 0.dp), color = secondary,
                            style = subtitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                CompositionLocalProvider(LocalContentColor provides amSecondaryLabelColor(),
                    LocalMinimumInteractiveComponentSize provides 28.dp) {
                    trailing?.invoke()
                }
            }
            if (showDivider) {
                val bottom = collection || numbered
                HorizontalDivider(Modifier.align(if (bottom) Alignment.BottomStart else Alignment.TopStart)
                    .padding(start = dividerStart, end = if (bottom) 16.dp else 0.dp),
                    thickness = .5.dp, color = amDividerColor())
            }
        }
    }
}

/** 28dp vertical menu glyph; its hit extension does not enlarge the XML's layout slot. */
@Composable
internal fun AmSongMoreButton(onClick: () -> Unit, enabled: Boolean = true) {
    val tint = amSecondaryLabelColor()
    Box(Modifier.size(28.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "更多" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(28.dp)) {
            val scale = size.width / 28f
            for (index in 0..2) {
                drawCircle(tint, 1.69f * scale, Offset(13.9944f * scale, (8.1548f + index * 5.8398f) * scale))
            }
        }
    }
}

/** Ordinary collection header only; artist and Uber identities deliberately stay separate. */
@Composable
internal fun AmCollectionHeader(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    caption: String,
    description: String,
    busy: Boolean,
    onSubtitle: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    actionReady: Boolean = false,
) {
    var expanded by rememberSaveable(title, description) { mutableStateOf(false) }
    val label = amLabelColor()
    val secondary = amSecondaryLabelColor()
    val accent = appAccentColors()
    val onButton = amPageBackground()
    val buttonFill = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0x3D767680) else Color(0x1F767680)
    val headingStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp,
        fontWeight = FontWeight.Medium, lineHeight = TextUnit.Unspecified).withUserFontWeight(FontWeightRole.Title, 500)
    val subtitleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp,
        fontWeight = FontWeight.Normal, lineHeight = TextUnit.Unspecified).withUserFontWeight(defaultWeight = 400)
    Column(Modifier.fillMaxWidth().background(amPageBackground()), horizontalAlignment = Alignment.CenterHorizontally) {
        Md3MediaArtwork(artworkUrl, Modifier.fillMaxWidth().padding(start = 72.dp, end = 72.dp, top = 10.dp)
            .aspectRatio(1f), shape = RoundedCornerShape(10.dp))
        Text(title, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp),
            style = headingStyle, color = label, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, Modifier.padding(horizontal = 16.dp).then(if (onSubtitle == null) Modifier else
                Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onSubtitle)),
                style = subtitleStyle, color = if (onSubtitle == null) label else accent.primary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (caption.isNotBlank()) Text(caption, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            color = secondary, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium,
                lineHeight = TextUnit.Unspecified).withUserFontWeight(FontWeightRole.Title, 500))
        if (onPlay != null || onShuffle != null || onAction != null || actionReady) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                val playWidth = (maxWidth - 128.dp).coerceIn(0.dp, 160.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (onShuffle == null) Spacer(Modifier.size(48.dp)) else {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(80.dp)).background(buttonFill)
                            .clickable(enabled = !busy, role = Role.Button, onClick = onShuffle),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Shuffle, "随机播放", tint = label)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    if (onPlay == null) Spacer(Modifier.width(playWidth).height(48.dp)) else {
                        Row(Modifier.width(playWidth).heightIn(min = 48.dp).clip(RoundedCornerShape(80.dp))
                            .background(label)
                            .clickable(enabled = !busy, role = Role.Button, onClick = onPlay),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = onButton, strokeWidth = 2.dp)
                            else {
                                Icon(Icons.Rounded.PlayArrow, null, tint = onButton)
                                Text("播放", Modifier.padding(start = 5.dp), color = onButton,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp,
                                        lineHeight = TextUnit.Unspecified).withUserFontWeight())
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    when {
                        actionReady -> Box(Modifier.size(48.dp).clip(RoundedCornerShape(80.dp)).background(buttonFill),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DownloadDone, actionLabel ?: "已下载", tint = label)
                        }
                        onAction != null -> Box(Modifier.size(48.dp).clip(RoundedCornerShape(80.dp)).background(buttonFill)
                            .clickable(enabled = !busy, role = Role.Button, onClick = onAction), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Download, actionLabel ?: "下载", tint = label)
                        }
                        else -> Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
        if (description.isNotBlank()) {
            Text(description, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clickable(role = Role.Button) { expanded = !expanded }, color = label,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = TextUnit.Unspecified).withUserFontWeight(),
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp, color = amDividerColor())
    }
}
