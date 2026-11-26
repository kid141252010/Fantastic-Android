package io.github.kid141252010.fantastic.data

import kotlinx.serialization.Serializable

@Serializable
enum class Source(val mediaPrefix: String) { Apple("apple"), QQ("qq"), Netease("ncm") }

internal fun String?.normalizedAppleCountry(): String? =
    this?.takeIf { it.length == 2 && it.all { char -> char in 'a'..'z' || char in 'A'..'Z' } }
        ?.lowercase(java.util.Locale.ROOT)

/** 界面统一使用的歌曲。 */
@Serializable
data class Track(
    val id: String,
    val source: Source,
    val title: String,
    val artist: String,
    val album: String,
    /** 带 {w}/{h} 占位符的封面模板。 */
    val artworkTemplate: String?,
    val durationMs: Long,
    val artistId: String = "",
    val albumId: String = "",
    /** 艺人头像模板；不得用专辑封面代替。 */
    val artistArtworkTemplate: String? = null,
    val albumArtist: String = "",
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val country: String? = null,
) {
    fun artwork(size: Int): String? {
        // QQ 封面只有固定几档尺寸，其他尺寸（如 600、120）返回 404；取不小于所需的最小一档
        val px = if (source == Source.QQ) QQ_ARTWORK_SIZES.firstOrNull { it >= size } ?: QQ_ARTWORK_SIZES.last() else size
        return artworkTemplate
            ?.replace("{w}", px.toString())
            ?.replace("{h}", px.toString())
            ?.replace("{f}", "jpg")
    }

    fun artistArtwork(size: Int): String? = artistArtworkTemplate
        ?.replace("{w}", size.toString())
        ?.replace("{h}", size.toString())
        ?.replace("{f}", "jpg")

    private companion object {
        val QQ_ARTWORK_SIZES = listOf(90, 150, 300, 500, 800)
    }
}

fun NeteaseSong.toTrack() = Track(
    id = id,
    source = Source.Netease,
    title = name,
    artist = artists.joinToString(" / ") { it.name },
    album = album,
    artworkTemplate = cover.ifBlank { null },
    durationMs = durationMs,
    artistId = artists.firstOrNull()?.mid.orEmpty(),
    albumId = albumMid,
    artistArtworkTemplate = artists.firstOrNull()?.avatar?.ifBlank { null },
)

// ---- QQ：/qq/search ----

@Serializable
data class QqArtist(val name: String = "", val mid: String = "", val avatar: String = "")

@Serializable
data class QqSongMeta(
    val trackNumber: Int = 0,
    val disc: Int = 0,
)

@Serializable
data class QqSong(
    val id: String,
    val name: String = "",
    val artists: List<QqArtist> = emptyList(),
    val album: String = "",
    val albumMid: String = "",
    val durationMs: Long = 0,
    val cover: String? = null,
    val vip: Boolean = false,
    val ttml: Boolean = false,
    val mv: String = "",
    val albumArtist: String = "",
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val meta: QqSongMeta? = null,
) {
    fun toTrack() = Track(
        id = id,
        source = Source.QQ,
        title = name,
        artist = artists.joinToString(" / ") { it.name },
        album = album,
        artworkTemplate = cover,
        durationMs = durationMs,
        artistId = artists.firstOrNull()?.mid.orEmpty(),
        albumId = albumMid,
        artistArtworkTemplate = artists.firstOrNull()?.let { artist ->
            artist.avatar.ifBlank { artist.mid.takeIf(String::isNotBlank)?.let { mid ->
                "https://y.gtimg.cn/music/photo_new/T001R{w}x{h}M000$mid.jpg"
            } }
        },
        albumArtist = albumArtist,
        discNumber = (meta?.disc ?: discNumber).coerceAtLeast(0),
        trackNumber = (meta?.trackNumber ?: trackNumber).coerceAtLeast(0),
    )
}

@Serializable
data class QqSearch(val songs: List<QqSong> = emptyList())

// ---- Apple Music：/amp/v1/catalog/{sf}/search ----

@Serializable
data class AmArtwork(val url: String? = null)

@Serializable
data class AmSongAttributes(
    val name: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val albumArtistName: String = "",
    val durationInMillis: Long = 0,
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val artwork: AmArtwork? = null,
)

@Serializable
data class AmSong(val id: String, val type: String = "", val attributes: AmSongAttributes? = null) {
    fun toTrack(country: String): Track {
        val a = attributes ?: AmSongAttributes()
        return Track(id, Source.Apple, a.name, a.artistName, a.albumName, a.artwork?.url, a.durationInMillis,
            albumArtist = a.albumArtistName, discNumber = a.discNumber, trackNumber = a.trackNumber,
            country = country.normalizedAppleCountry())
    }
}

@Serializable
data class AmSongs(val data: List<AmSong> = emptyList())

@Serializable
data class AmSearchResults(val songs: AmSongs? = null)

@Serializable
data class AmSearch(val results: AmSearchResults = AmSearchResults())
