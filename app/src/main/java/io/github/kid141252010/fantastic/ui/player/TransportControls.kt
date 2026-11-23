package io.github.kid141252010.fantastic.ui.player

import android.media.MediaRouter2
import android.os.Build
import android.content.res.ColorStateList
import android.view.View
import android.widget.SeekBar
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import io.github.kid141252010.fantastic.R
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.qualityPresentation
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.PlayerState
import io.github.kid141252010.fantastic.ui.theme.withLocalAmFont
import kotlinx.coroutines.delay
import java.util.Locale

private val TransportPrimary = Color.White.copy(alpha = .94f)
private val TransportSecondary = Color.White.copy(alpha = .45f)
private val TransportTertiary = Color.White.copy(alpha = .18f)
private val TransportDisabled = Color.White.copy(alpha = .08f)
private val TransportSeekHeight = 17.dp
private val TransportTimeHeight = 34.dp
private val TransportTopHeight = TransportSeekHeight + TransportTimeHeight
private val TransportActionSize = 60.dp
private val TransportActionBottomMargin = 14.dp
private val TransportDistance = 40.dp

@Composable
internal fun TransportControls(
    state: PlayerState,
    player: PlayerConnection,
    lyrics: Boolean,
    queue: Boolean,
    toggleLyrics: () -> Unit,
    toggleQueue: () -> Unit,
    onQuality: () -> Unit,
    onRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by remember(state.mediaId) { mutableLongStateOf(0L) }
    var dragging by remember(state.mediaId) { mutableStateOf(false) }
    var dragFraction by remember(state.mediaId) { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val canSeek = state.isSeekable && !state.isLive && duration > 0L

    LaunchedEffect(state.mediaId, state.isPlaying, state.seekGeneration) {
        while (true) {
            if (!dragging) position = player.position().coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
            if (!state.isPlaying) break
            delay(100L)
        }
    }

    fun seek(fraction: Float) {
        if (!canSeek) return
        val target = (fraction.coerceIn(0f, 1f) * duration.toDouble()).toLong().coerceIn(0L, duration)
        position = target
        player.seekTo(target)
    }

    val progress = if (!canSeek) 0f else if (dragging) dragFraction else (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    val routeName = rememberPlatformRouteName()
    val externalRouteDescription = routeName?.takeIf { it.isNotBlank() }
    val source = state.currentTrack?.source
    val presentation = qualityPresentation(source, state.qualityLabel, state.audioDetails)
    val atmos = presentation?.title == "杜比全景声"
    val badgeText = presentation?.badgeLabel?.takeIf { it.isNotEmpty() }
    val badgeInteraction = remember { MutableInteractionSource() }
    val badgePressed by badgeInteraction.collectIsPressedAsState()
    val badgeTint = TransportSecondary
    val playbackEnabled = state.connected && state.mediaId != null
    val paused = !state.playWhenReady || state.ended || state.error != null
    val repeatSymbol = when {
        state.shuffle -> PlayerSymbol.Shuffle
        state.repeatMode == Player.REPEAT_MODE_ONE -> PlayerSymbol.RepeatOne
        state.repeatMode == Player.REPEAT_MODE_ALL -> PlayerSymbol.Repeat
        else -> null
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val topRegionHeight = if (state.isLive) 27.dp else TransportTopHeight
        val actionTop = (height - TransportActionBottomMargin - TransportActionSize).coerceAtLeast(0.dp)
        val centerY = ((topRegionHeight + actionTop) / 2f).coerceAtLeast(TransportActionSize / 2f)
        val controlTop = (centerY - TransportActionSize / 2f).coerceAtLeast(0.dp)

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topRegionHeight)
                .padding(horizontal = 32.dp),
        ) {
            if (state.isLive) {
                LiveRadioTrack(Modifier.fillMaxWidth().padding(top = 10.dp).height(TransportSeekHeight))
            } else {
                SeekTrack(
                    progress = progress,
                    buffering = state.buffering,
                    durationSeconds = (duration / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    enabled = canSeek,
                    onProgress = ::seek,
                    onDragStart = { fraction -> dragFraction = fraction; dragging = true },
                    onDragProgress = { dragFraction = it },
                    onDragEnd = { fraction -> seek(fraction); dragging = false },
                    modifier = Modifier.fillMaxWidth().height(TransportSeekHeight),
                )
                Box(Modifier.fillMaxWidth().height(TransportTimeHeight)) {
                    if (duration > 0L) {
                        val shown = if (dragging && canSeek) (dragFraction * duration.toDouble()).toLong() else position
                        val timeAlpha = if (!canSeek) TransportDisabled else if (dragging) TransportSecondary else TransportTertiary
                        TransportText(formatTransportTime(shown), timeAlpha,
                            Modifier.align(Alignment.CenterStart).padding(vertical = 10.dp))
                        TransportText("-" + formatTransportTime((duration - shown).coerceAtLeast(0L)), timeAlpha,
                            Modifier.align(Alignment.CenterEnd).padding(vertical = 10.dp))
                    }
                    if (atmos) {
                        val badgeModifier = Modifier.align(Alignment.Center).size(PlayerSymbol.Atmos.intrinsicSize)
                        val atmosModifier = if (source == Source.QQ || source == Source.Netease) {
                            badgeModifier.clickable(interactionSource = badgeInteraction, indication = null,
                                role = Role.Button, onClick = onQuality)
                        } else badgeModifier
                        PlayerGlyph(PlayerSymbol.Atmos, "杜比全景声", badgeTint, atmosModifier)
                    } else if (badgeText != null) {
                        Box(
                            Modifier.align(Alignment.Center)
                                .clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = if (badgePressed) .05f else .08f))
                                .clickable(interactionSource = badgeInteraction, indication = null,
                                    role = Role.Button, onClick = onQuality)
                                .padding(horizontal = 7.dp, vertical = 3.5.dp),
                        ) {
                            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                if (presentation?.lossless == true) {
                                    PlayerGlyph(PlayerSymbol.Lossless, null, badgeTint, Modifier.size(PlayerSymbol.Lossless.intrinsicSize))
                                    androidx.compose.foundation.layout.Spacer(Modifier.size(width = 5.dp, height = 1.dp))
                                }
                                TransportText(badgeText, badgeTint, Modifier, 11)
                            }
                        }
                    }
                }
            }
        }

        ControlTarget(
            description = "上一首",
            enabled = state.hasPrevious,
            onClick = player::previous,
            modifier = Modifier.align(Alignment.TopCenter).absoluteOffset(x = -(TransportActionSize / 2f + TransportDistance + 31.dp),
                y = controlTop + (TransportActionSize - 56.dp) / 2f).size(62.dp, 56.dp),
        ) {
            PlayerGlyph(
                PlayerSymbol.Previous,
                null,
                if (state.hasPrevious) TransportPrimary else Color.White.copy(alpha = .36f),
                Modifier.size(PlayerSymbol.Previous.intrinsicSize),
            )
        }
        val playSymbol = if (paused) PlayerSymbol.Play else PlayerSymbol.Pause
        ControlTarget(
            description = if (paused) "播放" else "暂停",
            enabled = playbackEnabled,
            onClick = player::togglePlay,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = controlTop).size(TransportActionSize),
        ) {
            PlayerGlyph(playSymbol, null, TransportPrimary, Modifier.size(playSymbol.intrinsicSize))
        }
        ControlTarget(
            description = "下一首",
            enabled = state.hasNext,
            onClick = player::next,
            modifier = Modifier.align(Alignment.TopCenter).absoluteOffset(x = TransportActionSize / 2f + TransportDistance + 31.dp,
                y = controlTop + (TransportActionSize - 56.dp) / 2f).size(62.dp, 56.dp),
        ) {
            PlayerGlyph(
                PlayerSymbol.Next,
                null,
                if (state.hasNext) TransportPrimary else Color.White.copy(alpha = .36f),
                Modifier.size(PlayerSymbol.Next.intrinsicSize),
            )
        }

        val bottomTop = (height - TransportActionBottomMargin - TransportActionSize).coerceAtLeast(0.dp)
        ControlTarget(
            description = "歌词",
            selected = lyrics,
            onClick = toggleLyrics,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = -(TransportActionSize / 2f + TransportDistance + 30.dp), y = bottomTop)
                .size(TransportActionSize),
        ) {
            Icon(
                imageVector = if (lyrics) Icons.Filled.Lyrics else Icons.Outlined.Lyrics,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (lyrics) TransportPrimary else Color.White.copy(alpha = .72f),
            )
        }

        Box(Modifier.align(Alignment.TopCenter).offset(y = bottomTop).size(TransportActionSize)) {
            ControlTarget(
                description = if (externalRouteDescription != null) "音频输出：$externalRouteDescription" else "音频输出",
                selected = externalRouteDescription != null,
                onClick = onRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                PlayerGlyph(
                    PlayerSymbol.Route,
                    null,
                    if (externalRouteDescription != null) TransportPrimary else Color.White.copy(alpha = .54f),
                    Modifier.size(PlayerSymbol.Route.intrinsicSize),
                )
            }
        }
        if (!externalRouteDescription.isNullOrBlank()) {
            TransportText(externalRouteDescription, TransportSecondary,
                Modifier.align(Alignment.TopCenter).offset(y = bottomTop + (TransportActionSize + 32.dp) / 2f + 6.dp)
                    .widthIn(max = width), 16, FontWeight.SemiBold)
        }

        Box(Modifier.align(Alignment.TopCenter).offset(x = TransportActionSize / 2f + TransportDistance + 30.dp, y = bottomTop)
            .size(TransportActionSize)) {
            ControlTarget(
                description = "待播清单",
                selected = queue,
                onClick = toggleQueue,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = if (queue) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (queue) TransportPrimary else Color.White.copy(alpha = .54f),
                )
            }
            if (repeatSymbol != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).background(TransportTertiary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerGlyph(repeatSymbol, null, TransportPrimary, Modifier.size(16.dp))
                }
            }
        }
    }
}


@Composable
private fun ControlTarget(
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier.semantics {
            role = Role.Button
            contentDescription = description
            this.selected = selected
        }.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        glyph()
    }
}

@Composable
private fun SeekTrack(
    progress: Float,
    durationSeconds: Int,
    buffering: Boolean,
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onDragStart: (Float) -> Unit,
    onDragProgress: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier,
) {
    val onProgressUpdated by rememberUpdatedState(onProgress)
    val onDragStartUpdated by rememberUpdatedState(onDragStart)
    val onDragProgressUpdated by rememberUpdatedState(onDragProgress)
    val onDragEndUpdated by rememberUpdatedState(onDragEnd)
    AndroidView(
        factory = { context ->
            SeekBar(context).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR // Source seek bar does not mirror for RTL.
                val inset = (5f * resources.displayMetrics.density).toInt()
                setPadding(0, inset, 0, inset)
                background = null
                thumb = context.getDrawable(R.drawable.fantastic_seek_thumb)
                thumbOffset = 0
                splitTrack = false
                progressDrawable = context.getDrawable(R.drawable.fantastic_seek_progress)
                indeterminateDrawable = context.getDrawable(R.drawable.fantastic_seek_indeterminate)
                indeterminateTintList = ColorStateList.valueOf(0x73FFFFFF)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    private fun fraction(bar: SeekBar) = if (bar.max > 0) bar.progress.toFloat() / bar.max else 0f
                    override fun onStartTrackingTouch(bar: SeekBar) = onDragStartUpdated(fraction(bar))
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) {
                            if (bar.isPressed) onDragProgressUpdated(fraction(bar)) else onProgressUpdated(fraction(bar))
                        }
                    }
                    override fun onStopTrackingTouch(bar: SeekBar) = onDragEndUpdated(fraction(bar))
                })
            }
        },
        modifier = modifier,
        update = { bar ->
            bar.isEnabled = enabled
            bar.isIndeterminate = buffering
            bar.max = durationSeconds.coerceAtLeast(1)
            if (!bar.isPressed) bar.progress = (progress * bar.max).toInt().coerceIn(0, bar.max)
            bar.contentDescription = "播放进度 ${formatTransportTime((progress * durationSeconds * 1000.0).toLong())}，总时长 ${formatTransportTime(durationSeconds * 1000L)}"
        },
    )
}

@Composable
private fun LiveRadioTrack(modifier: Modifier) {
    val leftGradient = remember { Brush.horizontalGradient(listOf(TransportTertiary, Color.Transparent)) }
    val rightGradient = remember { Brush.horizontalGradient(listOf(Color.Transparent, TransportTertiary)) }
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(7.dp)) {
            val radius = CornerRadius(3.5.dp.toPx())
            drawRoundRect(leftGradient, Offset.Zero, Size(size.width, size.height), radius)
        }
        Text(
            "LIVE",
            Modifier.padding(horizontal = 10.dp),
            style = TextStyle(color = TransportSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                lineHeight = 15.6.sp).withLocalAmFont(),
            maxLines = 1,
        )
        Canvas(Modifier.weight(1f).height(7.dp)) {
            val radius = CornerRadius(3.5.dp.toPx())
            drawRoundRect(rightGradient, Offset.Zero, Size(size.width, size.height), radius)
        }
    }
}

@Composable
private fun TransportText(value: String, color: Color, modifier: Modifier, size: Int = 12, weight: FontWeight = FontWeight.Medium) {
    Text(
        text = value,
        modifier = modifier,
        style = TextStyle(color = color, fontWeight = weight,
            fontSize = size.sp, lineHeight = (size * 1.2f).sp, fontFeatureSettings = "tnum").withLocalAmFont(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun rememberPlatformRouteName(): String? {
    val context = LocalContext.current
    var selectedRouteName by remember { mutableStateOf<String?>(null) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val router = remember(context) { MediaRouter2.getInstance(context) }
        DisposableEffect(router) {
            fun update() {
                selectedRouteName = router.systemController.selectedRoutes.firstOrNull {
                    it.type != android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER
                }?.name?.toString()
                    ?.takeIf { it.isNotBlank() }
            }
            val callback = object : MediaRouter2.ControllerCallback() {
                override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
                    if (controller == router.systemController) update()
                }
            }
            router.registerControllerCallback(context.mainExecutor, callback)
            update()
            onDispose { router.unregisterControllerCallback(callback) }
        }
    }
    return selectedRouteName
}

private fun formatTransportTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes >= 60L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", minutes / 60L, minutes % 60L, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
