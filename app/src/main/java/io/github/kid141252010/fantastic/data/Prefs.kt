package io.github.kid141252010.fantastic.data

import android.content.Context
import androidx.core.content.edit

internal enum class PlaybackCacheLimit(val id: String, val label: String, val bytes: Long) {
    Mb200("200mb", "200 MB", 200_000_000L),
    Mb500("500mb", "500 MB", 500_000_000L),
    Gb1("1gb", "1 GB", 1_000_000_000L),
    Gb5("5gb", "5 GB", 5_000_000_000L),
}

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
        get() = sp.getString("qqQuality", null)?.takeIf { it in QQ_QUALITY_IDS } ?: "SQ"
        set(value) = sp.edit { putString("qqQuality", value) }

    var neteaseQuality: String
        get() = sp.getString("neteaseQuality", null)?.takeIf { it in NETEASE_QUALITY_IDS } ?: "hires"
        set(value) = sp.edit { putString("neteaseQuality", value) }

    var appleQuality: String
        get() = sp.getString("appleQuality", "alac")!!.takeIf { q -> APPLE_QUALITIES.any { it.first == q } } ?: "alac"
        set(value) = sp.edit { putString("appleQuality", value) }
    var appleWifiQuality: String
        get() = sp.getString("appleWifiQuality", "hires")!!.takeIf { it in APPLE_PLAYBACK_QUALITIES_IDS } ?: "hires"
        set(value) = sp.edit { putString("appleWifiQuality", value) }

    var appleCellularQuality: String
        get() = sp.getString("appleCellularQuality", "high")!!.takeIf { it in APPLE_PLAYBACK_QUALITIES_IDS } ?: "high"
        set(value) = sp.edit { putString("appleCellularQuality", value) }

    var appleSpatial: String
        get() = sp.getString("appleSpatial", "auto")!!.takeIf { it in APPLE_SPATIALS_IDS } ?: "auto"
        set(value) = sp.edit { putString("appleSpatial", value) }

    var appleTransition: String
        get() = sp.getString("appleTransition", "off")!!.takeIf { it in APPLE_TRANSITIONS_IDS } ?: "off"
        set(value) = sp.edit { putString("appleTransition", value) }

    var appleCrossfade: Int
        get() = sp.getInt("appleCrossfade", 6).coerceIn(1, 12)
        set(value) = sp.edit { putInt("appleCrossfade", value.coerceIn(1, 12)) }

    internal var playbackCacheLimit: PlaybackCacheLimit
        get() {
            val id = sp.getString("playbackCacheLimit", null)
            return PlaybackCacheLimit.entries.firstOrNull { it.id == id } ?: PlaybackCacheLimit.Mb500
        }
        set(value) = sp.edit { putString("playbackCacheLimit", value.id) }

    internal var lastPlayerPane: String?
        get() = sp.getString("lastPlayerPane", null) ?: when (sp.getString("playerPage", null)) {
            "cover" -> "Cover"
            "lyrics" -> "Lyrics"
            "queue" -> "Queue"
            else -> null
        }
        set(value) = sp.edit { putString("lastPlayerPane", value) }

    internal var lyricTranslation: Boolean
        get() = sp.getBoolean("lyricTranslation", true)
        set(value) = sp.edit { putBoolean("lyricTranslation", value) }

    internal var lyricPronunciation: Boolean
        get() = sp.getBoolean("lyricPronunciation", false)
        set(value) = sp.edit { putBoolean("lyricPronunciation", value) }

    internal var librarySongsNewestFirst: Boolean
        get() = sp.getBoolean("librarySongsNewestFirst", false)
        set(value) = sp.edit { putBoolean("librarySongsNewestFirst", value) }

    internal var libraryAlbumsNewestFirst: Boolean
        get() = sp.getBoolean("libraryAlbumsNewestFirst", false)
        set(value) = sp.edit { putBoolean("libraryAlbumsNewestFirst", value) }

    internal var libraryArtistsNewestFirst: Boolean
        get() = sp.getBoolean("libraryArtistsNewestFirst", false)
        set(value) = sp.edit { putBoolean("libraryArtistsNewestFirst", value) }

    internal var showLibrarySources: Boolean
        get() = sp.getBoolean("showLibrarySources", true)
        set(value) = sp.edit { putBoolean("showLibrarySources", value) }

    internal var appleLyricsOptions: AppleLyricsOptions
        get() {
            val defaults = AppleLyricsOptions.DEFAULT
            return AppleLyricsOptions(
                sp.getString("appleLyricsLanguage", null)?.takeIf(::isValidAppleLyricsTag) ?: defaults.language,
                sp.getString("appleLyricsScript", null)?.takeIf(::isValidAppleLyricsTag) ?: defaults.script,
            )
        }
        set(value) = sp.edit {
            putString("appleLyricsLanguage", value.language)
            putString("appleLyricsScript", value.script)
        }


    companion object {
        const val DEFAULT_BASE_URL = "https://music.1412.tech"

        val QQ_QUALITIES = listOf(
            "RS" to "Hi-Res 无损 · FLAC", "SQ" to "无损 · FLAC",
            "RA360" to "360 Reality Audio", "DTS" to "DTS", "DTSX" to "DTS:X",
            "DAEAC3" to "E-AC-3 JOC", "DA" to "AC-4 杜比全景声",
            "DA448" to "AC-4 A-JOC · 448 kbps", "DA1500" to "AC-4 A-JOC · 1500 kbps",
            "HQ" to "MP3 · 320 kbps", "AAC192" to "AAC · 192 kbps", "WEB" to "AAC · 96 kbps", "AAC48" to "AAC · 48 kbps",
            "OGG192" to "Vorbis · 192 kbps", "OGG96" to "Vorbis · 96 kbps", "MP3128" to "MP3 · 128 kbps",
        )
        val NETEASE_QUALITIES = listOf(
            "hires" to "Hi-Res 无损 · FLAC", "lossless" to "无损 · FLAC",
            "exhigh" to "MP3 · 320 kbps", "higher" to "MP3 · 192 kbps", "standard" to "MP3 · 128 kbps",
        )
        val APPLE_PLAYBACK_QUALITIES = listOf(
            "efficient" to "高效 · HE-AAC",
            "high" to "高品质 · AAC 256 kbps",
            "lossless" to "无损 · ALAC ≤ 48 kHz",
            "hires" to "Hi-Res 无损 · ALAC ≤ 192 kHz",
        )
        val APPLE_SPATIALS = listOf("auto" to "自动优先全景声", "always" to "始终优先全景声", "off" to "关闭全景声优先")
        val APPLE_TRANSITIONS = listOf("off" to "关闭", "automix" to "自动混音", "crossfade" to "交叉淡化")

        private val QQ_QUALITY_IDS = QQ_QUALITIES.mapTo(hashSetOf()) { it.first }
        internal val NETEASE_QUALITY_IDS = NETEASE_QUALITIES.mapTo(hashSetOf()) { it.first }
        private val APPLE_PLAYBACK_QUALITIES_IDS = APPLE_PLAYBACK_QUALITIES.mapTo(hashSetOf()) { it.first }
        private val APPLE_SPATIALS_IDS = APPLE_SPATIALS.mapTo(hashSetOf()) { it.first }
        private val APPLE_TRANSITIONS_IDS = APPLE_TRANSITIONS.mapTo(hashSetOf()) { it.first }
        val APPLE_QUALITIES = listOf(
            "alac" to "ALAC 无损 / Hi-Res 无损", "atmos" to "杜比全景声 · E-AC-3",
            "aac" to "AAC · 256 kbps", "aac-128" to "AAC · 128 kbps", "aac-he" to "HE-AAC",
            "aac-binaural" to "AAC 双耳", "aac-binaural-128" to "AAC 双耳 · 128 kbps", "he-binaural" to "HE-AAC 双耳",
            "aac-downmix" to "AAC 立体声下混", "aac-downmix-128" to "AAC 立体声下混 · 128 kbps", "he-downmix" to "HE-AAC 立体声下混",
        )
    }
}
