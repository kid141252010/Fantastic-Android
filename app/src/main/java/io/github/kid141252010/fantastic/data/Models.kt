package io.github.kid141252010.fantastic.data

import kotlinx.serialization.Serializable

enum class Source { Apple, QQ }

/** 界面统一使用的歌曲。 */
data class Track(
    val id: String,
    val source: Source,
    val title: String,
    val artist: String,
    val album: String,
    /** 带 {w}/{h} 占位符的封面模板。 */
    val artworkTemplate: String?,
    val durationMs: Long,
) {
    fun artwork(size: Int): String? {
        // QQ 封面只有固定几档尺寸，其他尺寸（如 600、120）返回 404；取不小于所需的最小一档
        val px = if (source == Source.QQ) QQ_ARTWORK_SIZES.firstOrNull { it >= size } ?: QQ_ARTWORK_SIZES.last() else size
        return artworkTemplate
            ?.replace("{w}", px.toString())
            ?.replace("{h}", px.toString())
            ?.replace("{f}", "jpg")
    }

    private companion object {
        val QQ_ARTWORK_SIZES = listOf(90, 150, 300, 500, 800)
    }
}

// ---- QQ：/qq/search ----

@Serializable
data class QqArtist(val name: String = "", val mid: String = "")

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
) {
    fun toTrack() = Track(
        id = id,
        source = Source.QQ,
        title = name,
        artist = artists.joinToString(" / ") { it.name },
        album = album,
        artworkTemplate = cover,
        durationMs = durationMs,
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
    val durationInMillis: Long = 0,
    val artwork: AmArtwork? = null,
)

@Serializable
data class AmSong(val id: String, val type: String = "", val attributes: AmSongAttributes? = null) {
    fun toTrack(): Track {
        val a = attributes ?: AmSongAttributes()
        // Apple 的模板是 {w}x{h}bb.jpg，replace 后即可直接用。
        return Track(id, Source.Apple, a.name, a.artistName, a.albumName, a.artwork?.url, a.durationInMillis)
    }
}

@Serializable
data class AmSongs(val data: List<AmSong> = emptyList())

@Serializable
data class AmSearchResults(val songs: AmSongs? = null)

@Serializable
data class AmSearch(val results: AmSearchResults = AmSearchResults())
