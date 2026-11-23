package io.github.kid141252010.fantastic.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.github.kid141252010.fantastic.BuildConfig
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.R
import io.github.kid141252010.fantastic.data.AppleMotionVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun PlayerMotionArtwork(
    artwork: Bitmap?,
    variant: AppleMotionVariant?,
    videoAspectRatio: Float,
    videoBaseWidthPx: Int,
    playing: Boolean,
    modifier: Modifier,
    stroke: Boolean = false,
    showMotion: Boolean = true,
    captureMotionFrame: Boolean,
    onMotionFrame: (Bitmap?) -> Unit,
    onMotionError: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FantasticApp
    val hlsMasterUrl = variant?.hlsMasterUrl
    var factory by remember(app) { mutableStateOf<DataSource.Factory?>(null) }
    LaunchedEffect(app, hlsMasterUrl) {
        if (hlsMasterUrl != null && factory == null) {
            factory = withContext(Dispatchers.IO) { app.motionArtworkDataSource }
        }
    }
    val dataSource = factory
    Box(modifier) {
        PlayerArtwork(artwork, Modifier.fillMaxSize(), 0.dp, stroke = stroke)
        if (variant != null && hlsMasterUrl != null && dataSource != null) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val player = remember(context, hlsMasterUrl, dataSource) {
                createMotionPlayer(context, hlsMasterUrl, dataSource)
            }
            var firstFrame by remember(player) { mutableStateOf(false) }
            var failed by remember(player) { mutableStateOf(false) }
            var playerView by remember { mutableStateOf<PlayerView?>(null) }
            val notifyFrame by rememberUpdatedState(onMotionFrame)
            val notifyError by rememberUpdatedState(onMotionError)
            val shapeName by rememberUpdatedState(if (videoAspectRatio < 1f) "tall" else "square")
            val motionEnabled = playing && !failed && android.animation.ValueAnimator.areAnimatorsEnabled()
            val firstFrameAlpha by key(player) {
                animateFloatAsState(
                    targetValue = if (firstFrame) 1f else 0f,
                    animationSpec = tween(1_000),
                    label = "motion-artwork-fade",
                )
            }
            val videoVisible = showMotion && !failed
            val videoAlpha = if (videoVisible) firstFrameAlpha else 0f

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (firstFrame || failed) return
                        firstFrame = true
                        if (BuildConfig.DEBUG) Log.d("FantasticMotion", "Artwork first frame: $shapeName")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (failed) return
                        failed = true
                        notifyFrame(null)
                        Log.w("FantasticMotion", "Apple motion playback failed ($shapeName, ${error.errorCodeName})")
                        notifyError(hlsMasterUrl)
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }
            LaunchedEffect(player, lifecycle, motionEnabled) {
                if (!motionEnabled) {
                    player.pause()
                } else {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        try {
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.playWhenReady = true
                            awaitCancellation()
                        } finally {
                            player.pause()
                        }
                    }
                }
            }
            val texture = playerView?.videoSurfaceView as? TextureView
            val sampling = captureMotionFrame && videoVisible && motionEnabled && firstFrame
            LaunchedEffect(player, lifecycle, texture, sampling, onMotionFrame) {
                if (!sampling || texture == null) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    var sampled = false
                    try {
                        while (isActive && !failed) {
                            if (!texture.isAvailable) {
                                if (sampled) onMotionFrame(null)
                                sampled = false
                            } else {
                                texture.sampleMotionFrame()?.let { frame ->
                                    onMotionFrame(frame)
                                    if (!sampled && BuildConfig.DEBUG) {
                                        Log.d("FantasticMotion", "Artwork backdrop sample: ${frame.width}x${frame.height}")
                                    }
                                    sampled = true
                                }
                            }
                            delay(42L)
                        }
                    } finally {
                        onMotionFrame(null)
                    }
                }
            }
            val preview = variant.previewArtworkUri
            if (videoVisible && videoAlpha < 1f && preview != null) key(preview) {
                AsyncImage(preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    AndroidView(
                        factory = { viewContext -> motionPlayerView(viewContext, player).also { playerView = it } },
                        modifier = Modifier.fillMaxSize().alpha(videoAlpha),
                        update = {
                            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            it.isClickable = false
                            it.visibility = if (videoVisible) View.VISIBLE else View.INVISIBLE
                            it.player = player
                        },
                        onRelease = {
                            it.player = null
                            if (playerView === it) {
                                playerView = null
                                notifyFrame(null)
                            }
                        },
                    )
                },
            ) { measurables, constraints ->
                val nativeHeight = (videoBaseWidthPx / videoAspectRatio).roundToInt().coerceAtLeast(1)
                val video = measurables.single().measure(Constraints.fixed(videoBaseWidthPx, nativeHeight))
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val scale = maxOf(width / video.width.toFloat(), height / video.height.toFloat())
                layout(width, height) {
                    video.placeWithLayer(((width - video.width) * .5f).roundToInt(), ((height - video.height) * .5f).roundToInt()) {
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
        }
    }
}

private fun TextureView.sampleMotionFrame(): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val sampleWidth = (ceil(width / 80f).toInt() * 10).coerceAtLeast(2)
    val sampleHeight = (height / 16f).roundToInt().coerceAtLeast(2)
    val sample = try {
        getBitmap(sampleWidth, sampleHeight)
    } catch (_: IllegalStateException) {
        null
    } ?: return null
    // TextureView may return an untouched bitmap when the surface copy fails.
    if (sample.getPixel(sampleWidth / 2, sampleHeight - 1) ushr 24 == 0) {
        sample.recycle()
        return null
    }
    return sample
}

@OptIn(UnstableApi::class)
private fun createMotionPlayer(context: Context, url: String, dataSource: DataSource.Factory): ExoPlayer {
    val selector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
    }
    // A decorative loop shares the heap with the audio session and the lyrics renderer.
    val loadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE)
        .build()
    return ExoPlayer.Builder(context)
        .setTrackSelector(selector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(HlsMediaSource.Factory(dataSource))
        .build().apply {
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ONE
        setMediaItem(MediaItem.Builder().setUri(url).setMimeType("application/x-mpegURL").build())
    }
}

private fun motionPlayerView(context: Context, player: ExoPlayer): PlayerView =
    (LayoutInflater.from(context).inflate(R.layout.view_motion_video, null, false) as PlayerView).apply {
        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        this.player = player
    }
