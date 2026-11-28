package io.github.kid141252010.fantastic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.appAccentColors
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight

@Composable
internal fun Md3SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge.withUserFontWeight(FontWeightRole.Title, defaultWeight = 500),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun Md3PreferenceRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable (() -> Unit))? = null,
    trailing: (@Composable (() -> Unit))? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    val defaultTrailing: (@Composable () -> Unit)? = if (onClick != null && trailing == null) {
        { Icon(Icons.Rounded.ChevronRight, contentDescription = null) }
    } else null
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = summary?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = leading,
        trailingContent = trailing ?: defaultTrailing,
        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).then(clickModifier).padding(horizontal = 20.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun Md3EmptyState(
    title: String,
    message: String,
    icon: ImageVector = Icons.Rounded.LibraryMusic,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

@Composable
internal fun Md3BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
    }
}

@Composable
internal fun Md3MediaSectionHeader(
    title: String,
    modifier: Modifier = Modifier.padding(horizontal = Dimens.Page),
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}

@Composable
internal fun Md3MediaRow(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    queueSwipeLabel: String? = null,
    onQueueSwipe: (() -> Unit)? = null,
) {
    MediaRowInteraction(modifier, playing, onClick, queueSwipeLabel, onQueueSwipe) { rowModifier ->
        Md3MediaRowContent(title, subtitle, playing, leading, trailing,
            rowModifier.clip(MaterialTheme.shapes.medium))
    }
}

/** Input and accessibility only; each row owns its foreground geometry and color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaRowInteraction(
    modifier: Modifier,
    playing: Boolean,
    onClick: (() -> Unit)?,
    queueSwipeLabel: String?,
    onQueueSwipe: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit,
) {
    val swipeLabel = queueSwipeLabel?.takeIf { onQueueSwipe != null }
    if (swipeLabel == null) {
        content(mediaRowModifier(modifier, playing, onClick))
        return
    }
    val latestQueueSwipe = rememberUpdatedState(onQueueSwipe)
    val latestSwipeLabel = rememberUpdatedState(swipeLabel)
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.StartToEnd) latestQueueSwipe.value?.invoke()
        false
    })
    val accent = appAccentColors()
    SwipeToDismissBox(
        state = dismiss,
        modifier = modifier.fillMaxWidth().clipToBounds(),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            val revealed = if (dismiss.dismissDirection == SwipeToDismissBoxValue.Settled) 0.dp
                else with(LocalDensity.current) { kotlin.math.abs(dismiss.requireOffset()).toDp() }
            Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.CenterStart) {
                if (revealed > 0.dp) {
                    Box(Modifier.width(revealed).fillMaxHeight().clipToBounds()
                        .background(accent.primary).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(latestSwipeLabel.value, color = accent.onPrimary,
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }
                }
            }
        },
    ) {
        val action = CustomAccessibilityAction(latestSwipeLabel.value) {
            latestQueueSwipe.value?.invoke()
            true
        }
        content(mediaRowModifier(Modifier, playing, onClick, listOf(action)))
    }
}

internal fun queueSwipeActionLabel(canEditQueue: Boolean, canAddToQueueSection: Boolean): String? = when {
    !canEditQueue -> null
    canAddToQueueSection -> "添加到队列"
    else -> "插播"
}

@Composable
private fun mediaRowModifier(
    modifier: Modifier,
    playing: Boolean,
    onClick: (() -> Unit)?,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
): Modifier = modifier.fillMaxWidth()
    .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
    .then(if (playing || accessibilityActions.isNotEmpty()) Modifier.semantics {
        if (playing) stateDescription = "当前曲目"
        if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
    } else Modifier)

@Composable
private fun Md3MediaRowContent(
    title: String,
    subtitle: String,
    playing: Boolean,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent = appAccentColors()
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isNotBlank()) {
            { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = if (playing) colors.surfaceVariant else colors.background,
            headlineColor = if (playing) accent.primary else colors.onSurface,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun Md3MediaArtwork(
    url: String?,
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    icon: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

