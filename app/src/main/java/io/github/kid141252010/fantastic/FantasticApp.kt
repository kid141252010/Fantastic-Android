package io.github.kid141252010.fantastic

import android.app.Application
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.MusicLibrary
import io.github.kid141252010.fantastic.data.NeteaseApi
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.QqApi
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.AudioFileTransfer
import io.github.kid141252010.fantastic.playback.OfflineDownloads
import io.github.kid141252010.fantastic.playback.PlaybackCache
import io.github.kid141252010.fantastic.playback.PlayerConnection
import io.github.kid141252010.fantastic.playback.createMotionArtworkDataSource
import java.io.File

/** 手动依赖注入：整个进程共用一份设置、API 客户端和播放器连接。 */
class FantasticApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var api: FantasticApi
        private set
    val player: PlayerConnection by lazy { PlayerConnection(this) }
    internal val downloads by lazy { OfflineDownloads(this, prefs, api) }
    internal val playbackCache by lazy { PlaybackCache(this, prefs, AudioFileTransfer(this, api)) }
    val apple by lazy { AppleCatalogApi(api, prefs, File(cacheDir, "apple-motion-artwork.json")) }
    internal val motionArtworkDataSource by lazy { createMotionArtworkDataSource(this) }
    val netease by lazy { NeteaseApi(api, prefs) }
    val qq by lazy { QqApi(api) }
    val library by lazy { MusicLibrary(this) }
    val lyrics by lazy { LyricsController(this, api, apple, netease, player, prefs) }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = FantasticApi(prefs)
    }
}
