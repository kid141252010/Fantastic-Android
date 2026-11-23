package io.github.kid141252010.fantastic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.kid141252010.fantastic.R

internal data class ActionSheetColors(val surface: Color, val label: Color, val secondary: Color, val divider: Color)

private val DarkActionSheetColors = ActionSheetColors(
    Color(0xFF1C1C1E), Color.White, Color(0x99EBEBF5), Color(0x33EBEBF5),
)
private val LightActionSheetColors = ActionSheetColors(Color.White, Color.Black, Color(0x993C3C43), Color(0x333C3C43))

@Composable
internal fun actionSheetColors() = if (isSystemInDarkTheme()) DarkActionSheetColors else LightActionSheetColors

/** APK action_sheet.xml: edge-to-edge bottom surface; share lyrics uses its centered 10dp card. */
@Composable
internal fun GlassSheet(
    title: String,
    onDismissRequest: () -> Unit,
    centered: Boolean = false,
    scrollable: Boolean = true,
    showHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val onVisibilityChanged = LocalGlassSheetVisibility.current
    DisposableEffect(onVisibilityChanged) {
        onVisibilityChanged(true)
        onDispose { onVisibilityChanged(false) }
    }
    val palette = actionSheetColors()
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val card = centered || tablet
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            window.setDimAmount(if (centered) .4f else .6f)
            window.setWindowAnimations(if (centered) R.style.FantasticLyricShareAnimation else R.style.FantasticActionSheetAnimation)
        }
        val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.ime)
        // Keep the bottom surface behind the navigation bar and pad its contents inside it.
        val outerInsets = if (card) safeInsets else safeInsets
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            .union(WindowInsets.displayCutout).union(WindowInsets.ime)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(outerInsets)) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = onDismissRequest))
            val shape = if (card) RoundedCornerShape(10.dp) else RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
            Surface(
                modifier = Modifier.align(if (card) Alignment.Center else Alignment.BottomCenter)
                    .widthIn(max = if (tablet) if (centered) 520.dp else 400.dp else maxWidth)
                    .fillMaxWidth().heightIn(max = if (centered && tablet) minOf(maxHeight, 600.dp) else maxHeight)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .semantics { paneTitle = title },
                shape = shape, color = palette.surface,
            ) {
                Column(Modifier.fillMaxWidth().then(if (card) Modifier else Modifier.navigationBarsPadding())) {
                    if (showHeader) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).semantics { heading() }, color = palette.label,
                                fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            CircularIconButton(onClick = onDismissRequest) { Icon(Icons.Rounded.Close, "关闭$title", tint = palette.secondary) }
                        }
                        HorizontalDivider(thickness = .5.dp, color = palette.divider)
                    }
                    ProvideTextStyle(TextStyle(color = palette.label, fontSize = 17.sp)) {
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier), content = content)
                    }
                }
            }
        }
    }
}

/** A null action is an explicitly disabled row; [detail] explains the missing capability or target. */
@Composable
internal fun SheetAction(
    title: String,
    icon: ImageVector? = null,
    detail: String? = null,
    selected: Boolean? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val palette = actionSheetColors()
    val tint = if (onClick == null) palette.secondary else if (destructive) Color(0xFFFF3B30) else palette.label
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
        .clickable(enabled = onClick != null, role = if (selected == null) Role.Button else Role.RadioButton) { onClick?.invoke() }
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(24.dp), tint = tint); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = 17.sp)
            if (!detail.isNullOrBlank()) Text(detail, Modifier.padding(top = 4.dp), color = palette.secondary, fontSize = 13.sp)
        }
        if (selected == true) Icon(Icons.Outlined.Check, null, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
internal fun SheetDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp, color = actionSheetColors().divider)
}
