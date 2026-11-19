package io.github.kid141252010.fantastic.data

import android.content.Context
import androidx.core.content.edit

/** 简单的键值设置。服务器地址指向用户自己部署的 Fantastic。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("fantastic", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", DEFAULT_BASE_URL)!!.trimEnd('/')
        set(value) = sp.edit { putString("baseUrl", value.trim().trimEnd('/')) }

    /** Apple Music 店面，如 us、tr、jp。 */
    var storefront: String
        get() = sp.getString("storefront", "us")!!
        set(value) = sp.edit { putString("storefront", value.trim().lowercase()) }

    /** QQ 音乐首选音质，对应 /qq/song/:mid/play?quality=。 */
    var qqQuality: String
        get() = sp.getString("qqQuality", "SQ")!!
        set(value) = sp.edit { putString("qqQuality", value) }

    companion object {
        const val DEFAULT_BASE_URL = "https://music.1412.tech"

        /** 播放失败时按这个顺序往下降。 */
        val QQ_QUALITIES = listOf(
            "RS" to "FLAC Hi-Res",
            "SQ" to "FLAC",
            "HQ" to "MP3 320 kbps",
            "AAC192" to "AAC 192 kbps",
            "MP3128" to "MP3 128 kbps",
        )
    }
}
