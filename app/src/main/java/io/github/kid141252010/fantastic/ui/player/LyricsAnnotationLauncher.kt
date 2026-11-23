package io.github.kid141252010.fantastic.ui.player

import androidx.core.graphics.PathParser
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun LyricsAnnotationLauncher(
    translation: Boolean,
    pronunciation: Boolean,
    translationAvailable: Boolean,
    pronunciationAvailable: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = (translationAvailable && translation) || (pronunciationAvailable && pronunciation)
    Box(modifier) {
        HeaderAction(
            symbol = PlayerSymbol.Translations,
            description = "歌词翻译与发音",
            padding = PaddingValues(start = 32.dp, end = 10.dp, top = 10.dp),
            tint = PlayerSecondary,
            checked = active,
            exactHitTarget = true,
        ) { onExpandedChange(true) }
        if (expanded) {
            val inset = with(LocalDensity.current) { 32.dp.roundToPx() }
            val position = remember(inset) { AnnotationPopupPosition(inset) }
            Popup(popupPositionProvider = position, onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true)) {
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.width(IntrinsicSize.Max).shadow(2.5.dp, shape).clip(shape).background(Color(0xCC000000))) {
                    val showReading = pronunciationAvailable && pronunciation
                    AnnotationRow(if (showReading) "隐藏发音" else "显示发音", true, showReading, pronunciationAvailable) {
                        onPronunciationChange(!pronunciation)
                        onExpandedChange(false)
                    }
                    Box(Modifier.fillMaxWidth().height(.5.dp).background(Color.White.copy(alpha = .4f)))
                    val showTranslation = translationAvailable && translation
                    AnnotationRow(if (showTranslation) "隐藏翻译" else "显示翻译", false, showTranslation, translationAvailable) {
                        onTranslationChange(!translation)
                        onExpandedChange(false)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(title: String, reading: Boolean, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else PlayerSecondary
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AnnotationMark(reading, checked, tint)
        Text(title, color = tint, fontSize = 17.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun AnnotationMark(reading: Boolean, struck: Boolean, tint: Color) {
    val path = remember(reading, struck) {
        val data = when {
            reading && struck -> "M3.542,4.898L4.735,6.089L4.713,6.116C4.405,6.5 4.252,7.036 4.252,7.724L4.252,14.057C4.252,14.826 4.441,15.406 4.821,15.796C5.2,16.186 5.779,16.381 6.56,16.381L7.697,16.381C7.929,16.381 8.099,16.433 8.207,16.535C8.315,16.637 8.369,16.813 8.369,17.062L8.369,19.428L11,16.821C11.178,16.644 11.342,16.527 11.494,16.469C11.647,16.41 11.85,16.381 12.104,16.381L15.037,16.381L16.673,18.016L12.188,18.017L9.349,20.532C9.066,20.781 8.823,20.965 8.618,21.084C8.413,21.203 8.195,21.262 7.962,21.262C7.63,21.262 7.372,21.154 7.186,20.938C7.001,20.723 6.908,20.429 6.908,20.059L6.908,18.017L6.46,18.017C5.658,18.017 4.97,17.862 4.397,17.552C3.824,17.242 3.385,16.798 3.077,16.219C2.77,15.641 2.617,14.94 2.617,14.115L2.617,7.674C2.617,6.849 2.769,6.148 3.073,5.57C3.204,5.321 3.36,5.097 3.542,4.898ZM3.66,2C3.844,2 4.001,2.066 4.132,2.197L20.854,18.909C20.985,19.04 21.051,19.196 21.05,19.377C21.05,19.558 20.984,19.714 20.854,19.844C20.728,19.976 20.574,20.041 20.391,20.04C20.207,20.04 20.05,19.975 19.919,19.844L3.189,3.132C3.063,3.006 3,2.849 3,2.661C3,2.473 3.063,2.318 3.189,2.197C3.32,2.066 3.477,2 3.66,2ZM17.317,3.772C18.147,3.772 18.853,3.927 19.434,4.237C20.015,4.547 20.458,4.991 20.762,5.57C21.067,6.148 21.219,6.849 21.219,7.674L21.219,14.115C21.219,14.94 21.067,15.641 20.762,16.219C20.627,16.477 20.463,16.708 20.273,16.913L19.08,15.722L19.123,15.673C19.43,15.288 19.583,14.749 19.583,14.057L19.583,7.724C19.583,6.96 19.394,6.383 19.015,5.993C18.636,5.603 18.053,5.408 17.268,5.408L8.759,5.407L7.123,3.772L17.317,3.772ZM8.981,11.082C9.439,11.082 9.81,11.453 9.81,11.911C9.81,12.369 9.439,12.74 8.981,12.74L7.323,12.74C6.865,12.74 6.494,12.369 6.494,11.911C6.494,11.453 6.865,11.082 7.323,11.082L8.981,11.082ZM14.508,11.082C14.966,11.082 15.337,11.453 15.337,11.911L15.333,11.977L14.436,11.081L14.508,11.082ZM7.022,8.375L8.625,9.976L7.323,9.976C6.865,9.976 6.494,9.605 6.494,9.147C6.494,8.796 6.713,8.495 7.022,8.375ZM16.719,8.318C17.176,8.318 17.548,8.689 17.548,9.147C17.548,9.605 17.176,9.976 16.719,9.976L15.06,9.976C14.603,9.976 14.231,9.605 14.231,9.147C14.231,8.689 14.603,8.318 15.06,8.318L16.719,8.318ZM12.297,8.318C12.755,8.318 13.126,8.689 13.126,9.147C13.126,9.324 13.071,9.488 12.977,9.622L11.671,8.318L12.297,8.318Z"
            reading -> "M17.317,3.773C18.147,3.773 18.853,3.927 19.434,4.237C20.015,4.547 20.458,4.991 20.762,5.57C21.067,6.148 21.219,6.849 21.219,7.674L21.219,14.115C21.219,14.94 21.067,15.641 20.762,16.22C20.458,16.798 20.015,17.242 19.434,17.552C18.853,17.862 18.147,18.017 17.317,18.017L12.188,18.017L9.349,20.532C9.066,20.781 8.823,20.965 8.618,21.084C8.413,21.203 8.195,21.262 7.962,21.262C7.63,21.262 7.372,21.154 7.186,20.939C7.001,20.723 6.908,20.429 6.908,20.059L6.908,18.017L6.46,18.017C5.658,18.017 4.97,17.862 4.397,17.552C3.824,17.242 3.385,16.798 3.077,16.22C2.77,15.641 2.617,14.94 2.617,14.115L2.617,7.674C2.617,6.849 2.769,6.148 3.073,5.57C3.378,4.991 3.819,4.547 4.397,4.237C4.976,3.927 5.682,3.773 6.518,3.773L17.317,3.773ZM17.268,5.408L6.56,5.408C5.779,5.408 5.2,5.603 4.821,5.993C4.441,6.383 4.252,6.96 4.252,7.724L4.252,14.057C4.252,14.826 4.441,15.406 4.821,15.796C5.2,16.186 5.779,16.381 6.56,16.381L7.697,16.381C7.929,16.381 8.099,16.433 8.207,16.535C8.315,16.637 8.369,16.813 8.369,17.062L8.369,19.428L11,16.821C11.178,16.644 11.342,16.527 11.494,16.469C11.647,16.41 11.85,16.381 12.104,16.381L17.268,16.381C18.053,16.381 18.636,16.186 19.015,15.796C19.394,15.406 19.583,14.826 19.583,14.057L19.583,7.724C19.583,6.96 19.394,6.383 19.015,5.993C18.636,5.603 18.053,5.408 17.268,5.408ZM7.323,8.318L12.297,8.318C12.755,8.318 13.126,8.69 13.126,9.147C13.126,9.605 12.755,9.976 12.297,9.976L7.323,9.976C6.865,9.976 6.494,9.605 6.494,9.147C6.494,8.69 6.865,8.318 7.323,8.318ZM7.323,11.082L8.981,11.082C9.439,11.082 9.81,11.453 9.81,11.911C9.81,12.369 9.439,12.74 8.981,12.74L7.323,12.74C6.865,12.74 6.494,12.369 6.494,11.911C6.494,11.453 6.865,11.082 7.323,11.082ZM15.06,8.318L16.719,8.318C17.176,8.318 17.548,8.69 17.548,9.147C17.548,9.605 17.176,9.976 16.719,9.976L15.06,9.976C14.603,9.976 14.231,9.605 14.231,9.147C14.231,8.69 14.603,8.318 15.06,8.318ZM11.744,11.082L14.508,11.082C14.966,11.082 15.337,11.453 15.337,11.911C15.337,12.369 14.966,12.74 14.508,12.74L11.744,12.74C11.286,12.74 10.915,12.369 10.915,11.911C10.915,11.453 11.286,11.082 11.744,11.082Z"
            struck -> "M7.962,21.262C7.63,21.262 7.372,21.154 7.186,20.938C7.001,20.723 6.908,20.429 6.908,20.059L6.908,18.017L6.46,18.017C5.658,18.017 4.97,17.862 4.397,17.552C3.824,17.242 3.385,16.798 3.077,16.219C2.77,15.641 2.617,14.94 2.617,14.115L2.617,7.674C2.617,6.849 2.769,6.148 3.073,5.57C3.378,4.991 3.819,4.547 4.397,4.237C4.976,3.927 5.682,3.772 6.518,3.772L17.317,3.772C18.147,3.772 18.853,3.927 19.434,4.237C20.015,4.547 20.458,4.991 20.762,5.57C21.067,6.148 21.219,6.849 21.219,7.674L21.219,14.115C21.219,14.94 21.067,15.641 20.762,16.219C20.458,16.798 20.015,17.242 19.434,17.552C18.853,17.862 18.147,18.017 17.317,18.017L12.188,18.017L9.349,20.532C9.066,20.781 8.823,20.965 8.618,21.084C8.413,21.203 8.195,21.262 7.962,21.262ZM8.369,19.428L11,16.821C11.178,16.644 11.342,16.527 11.494,16.469C11.647,16.41 11.85,16.381 12.104,16.381L17.268,16.381C18.053,16.381 18.636,16.186 19.015,15.796C19.394,15.406 19.583,14.826 19.583,14.057L19.583,7.724C19.583,6.96 19.394,6.383 19.015,5.993C18.636,5.603 18.053,5.408 17.268,5.408L6.56,5.408C5.779,5.408 5.2,5.603 4.821,5.993C4.441,6.383 4.252,6.96 4.252,7.724L4.252,14.057C4.252,14.826 4.441,15.406 4.821,15.796C5.2,16.186 5.779,16.381 6.56,16.381L7.697,16.381C7.929,16.381 8.099,16.433 8.207,16.535C8.315,16.637 8.369,16.813 8.369,17.062L8.369,19.428ZM3.66,2C3.844,2 4.001,2.066 4.132,2.197L20.854,18.909C20.985,19.04 21.051,19.196 21.05,19.377C21.05,19.558 20.984,19.714 20.854,19.844C20.728,19.976 20.574,20.041 20.391,20.04C20.207,20.04 20.05,19.975 19.919,19.844L3.189,3.132C3.063,3.006 3,2.849 3,2.661C3,2.473 3.063,2.318 3.189,2.197C3.32,2.066 3.477,2 3.66,2ZM9.608,10.958L11.273,12.621L10.544,12.621L10.029,14.132C9.963,14.359 9.867,14.523 9.743,14.626C9.618,14.728 9.462,14.779 9.274,14.779C9.047,14.779 8.869,14.717 8.739,14.593C8.608,14.468 8.543,14.301 8.543,14.09C8.543,13.958 8.579,13.786 8.651,13.576L9.608,10.958ZM11.914,6.918C12.489,6.918 12.874,7.201 13.067,7.765L14.19,10.835L12.031,8.677L11.963,8.471L11.872,8.471L11.86,8.506L10.878,7.526C11.09,7.121 11.436,6.918 11.914,6.918Z"
            else -> "M7.962,21.262C7.63,21.262 7.372,21.154 7.186,20.938C7.001,20.723 6.908,20.429 6.908,20.059L6.908,18.017L6.46,18.017C5.658,18.017 4.97,17.862 4.397,17.552C3.824,17.242 3.385,16.798 3.077,16.219C2.77,15.641 2.617,14.94 2.617,14.115L2.617,7.674C2.617,6.849 2.769,6.148 3.073,5.57C3.378,4.991 3.819,4.547 4.397,4.237C4.976,3.927 5.682,3.772 6.518,3.772L17.317,3.772C18.147,3.772 18.853,3.927 19.434,4.237C20.015,4.547 20.458,4.991 20.762,5.57C21.067,6.148 21.219,6.849 21.219,7.674L21.219,14.115C21.219,14.94 21.067,15.641 20.762,16.219C20.458,16.798 20.015,17.242 19.434,17.552C18.853,17.862 18.147,18.017 17.317,18.017L12.188,18.017L9.349,20.532C9.066,20.781 8.823,20.965 8.618,21.084C8.413,21.203 8.195,21.262 7.962,21.262ZM8.369,19.428L11,16.821C11.178,16.644 11.342,16.527 11.494,16.469C11.647,16.41 11.85,16.381 12.104,16.381L17.268,16.381C18.053,16.381 18.636,16.186 19.015,15.796C19.394,15.406 19.583,14.826 19.583,14.057L19.583,7.724C19.583,6.96 19.394,6.383 19.015,5.993C18.636,5.603 18.053,5.408 17.268,5.408L6.56,5.408C5.779,5.408 5.2,5.603 4.821,5.993C4.441,6.383 4.252,6.96 4.252,7.724L4.252,14.057C4.252,14.826 4.441,15.406 4.821,15.796C5.2,16.186 5.779,16.381 6.56,16.381L7.697,16.381C7.929,16.381 8.099,16.433 8.207,16.535C8.315,16.637 8.369,16.813 8.369,17.062L8.369,19.428ZM9.274,14.779C9.047,14.779 8.869,14.717 8.739,14.593C8.608,14.468 8.543,14.301 8.543,14.09C8.543,13.958 8.579,13.786 8.651,13.576L10.776,7.765C10.97,7.201 11.349,6.918 11.914,6.918C12.489,6.918 12.874,7.201 13.067,7.765L15.192,13.576C15.264,13.786 15.3,13.958 15.3,14.09C15.3,14.295 15.234,14.461 15.101,14.588C14.968,14.716 14.791,14.779 14.57,14.779C14.194,14.779 13.942,14.563 13.814,14.132L13.3,12.621L10.544,12.621L10.029,14.132C9.963,14.359 9.867,14.523 9.743,14.626C9.618,14.728 9.462,14.779 9.274,14.779ZM10.884,11.476L12.951,11.476L11.963,8.471L11.872,8.471L10.884,11.476Z"
        }
        PathParser.createPathFromPathData(data)!!.asComposePath()
    }
    Canvas(Modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) { drawPath(path, tint) }
    }
}

private class AnnotationPopupPosition(private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left + inset
            else anchorBounds.right - inset - popupContentSize.width
        val y = if (anchorBounds.bottom + popupContentSize.height <= windowSize.height) anchorBounds.bottom
            else anchorBounds.top - popupContentSize.height
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtLeast(0))
    }
}
