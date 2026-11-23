package io.github.kid141252010.fantastic.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.kid141252010.fantastic.MainActivity
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.NeteaseAudio
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** 后台播放服务：持有 ExoPlayer 和 MediaSession，通知栏、耳机按键、蓝牙控制都走这里。 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Fantastic-Android")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val app = application as FantasticApp
        val cache = app.playbackCache
        val requests = NeteasePlaybackRequests()
        val data = ResolvingDataSource.Factory(http) { spec ->
            val request = spec.key?.let(requests::get)?.takeIf { it.uri == spec.uri } ?: return@Factory spec
            val audio = runBlocking { app.netease.resolvePlay(request.id, request.quality, server = request.server) }
            request.audio.compareAndSet(null, audio)
            spec.withUri(Uri.parse(audio.url))
        }
        val audioSources = DefaultDataSource.Factory(this, data)
        val native = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory {
                CachedAudioDataSource(cache, audioSources.createDataSource())
            }))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val apple = ApplePlaybackEngine(this, app.prefs)
        val player = FantasticSessionPlayer(this, native, apple, app.prefs, app.downloads::audio, cache, requests)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(AccessCallback())
            .build()
        player.onCurrentAudioChanged = { audio ->
            // MediaMetadata equality ignores extras contents; use the session's explicit update channel.
            session?.setSessionExtras(Bundle().apply {
                if (audio != null) {
                    putString(AUDIO_MEDIA_ID, audio.mediaId)
                    putInt(AUDIO_QUEUE_INDEX, audio.queueIndex)
                    putInt(AUDIO_OCCURRENCE, audio.occurrence)
                    putString(AUDIO_LABEL, audio.label)
                    putString(AUDIO_DETAILS, audio.details)
                }
            })
        }
        player.restore()
        apple.prewarm(app.prefs.baseUrl)
    }

    /**
     * 只有本应用能设置队列和音源；通知、耳机、车机等控制器只负责传输控制。
     */
    @OptIn(UnstableApi::class)
    private inner class AccessCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (isLocal(controller)) return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(SessionCommands.Builder().add(SELECT_QUALITY).build())
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(REMOTE_COMMANDS)
                .setAvailableSessionCommands(SessionCommands.EMPTY)
                .build()
        }

        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            // Only source-tagged song metadata crosses into the service. Service owns all audio URIs.
            if (!isLocal(controller) || mediaItems.any { it.toTrack() == null }) {
                return Futures.immediateFailedFuture(IllegalArgumentException("Invalid playback queue"))
            }
            return Futures.immediateFuture(mediaItems)
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            val item = session.player.currentMediaItem
            val options = item?.toTrack()?.source?.qualities.orEmpty()
            val quality = args.getString("quality")
            val code = when {
                !isLocal(controller) -> SessionResult.RESULT_ERROR_PERMISSION_DENIED
                customCommand != SELECT_QUALITY -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
                item == null || item.mediaId != args.getString("mediaId") || quality == null || options.none { it.first == quality } ->
                    SessionResult.RESULT_ERROR_BAD_VALUE
                else -> {
                    (session.player as FantasticSessionPlayer).selectQuality(quality)
                    SessionResult.RESULT_SUCCESS
                }
            }
            return Futures.immediateFuture(SessionResult(code))
        }

        private fun isLocal(controller: MediaSession.ControllerInfo) =
            controller.uid == applicationInfo.uid && controller.packageName == packageName
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    companion object {
        internal val SELECT_QUALITY = SessionCommand("fantastic.selectQuality", Bundle.EMPTY)
        internal const val AUDIO_MEDIA_ID = "fantastic.audioMediaId"
        internal const val AUDIO_QUEUE_INDEX = "fantastic.audioQueueIndex"
        internal const val AUDIO_DETAILS = "fantastic.audioDetails"
        internal const val AUDIO_OCCURRENCE = "fantastic.audioOccurrence"
        internal const val AUDIO_LABEL = "fantastic.audioLabel"
        private val REMOTE_COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** A resolver can finish after the corresponding media item is removed. It updates only its own request. */
internal class NeteasePlaybackRequests {
    class Request(val uri: Uri, val id: String, val quality: String, val server: String) {
        val audio = AtomicReference<NeteaseAudio?>()
    }

    private val requests = ConcurrentHashMap<String, Request>()
    fun add(key: String, request: Request) { requests[key] = request }
    fun get(key: String): Request? = requests[key]
    fun remove(key: String) { requests.remove(key) }
    fun clear() { requests.clear() }
}
