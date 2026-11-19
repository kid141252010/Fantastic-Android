package io.github.kid141252010.fantastic

import android.app.Application
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.playback.PlayerConnection

/** 手动依赖注入：整个进程共用一份设置、API 客户端和播放器连接。 */
class FantasticApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var api: FantasticApi
        private set
    val player: PlayerConnection by lazy { PlayerConnection(this, prefs) }
    val lyrics: LyricsController by lazy { LyricsController(this, api, player) }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = FantasticApi(prefs)
    }
}
