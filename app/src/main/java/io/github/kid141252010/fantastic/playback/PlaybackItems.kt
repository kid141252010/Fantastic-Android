package io.github.kid141252010.fantastic.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.normalizedAppleCountry

internal const val KEY_ID = "fantastic.id"
internal const val KEY_SOURCE = "fantastic.source"
internal const val KEY_DURATION_MS = "fantastic.durationMs"
internal const val KEY_ARTIST_ID = "fantastic.artistId"
internal const val KEY_ALBUM_ID = "fantastic.albumId"
internal const val KEY_ARTWORK_TEMPLATE = "fantastic.artworkTemplate"
internal const val KEY_ARTIST_ARTWORK_TEMPLATE = "fantastic.artistArtworkTemplate"
internal const val KEY_ALBUM_ARTIST = "fantastic.albumArtist"
internal const val KEY_DISC_NUMBER = "fantastic.discNumber"
internal const val KEY_TRACK_NUMBER = "fantastic.trackNumber"
internal const val KEY_QUALITY = "fantastic.quality"
internal const val KEY_PLAYBACK_MESSAGE = "fantastic.playbackMessage"
internal const val KEY_SHUFFLE_START = "fantastic.shuffleStart"
internal const val KEY_OCCURRENCE = "fantastic.occurrence"
internal const val KEY_COUNTRY = "fantastic.country"

internal val Track.mediaId get() = "${source.mediaPrefix}:$id"

internal fun Track.toQueueItem(quality: String? = null, shuffleStart: Boolean? = null, occurrence: Int? = null): MediaItem =
    MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(artwork(600)?.let(Uri::parse))
        .setExtras(Bundle().apply {
            putString(KEY_ID, id)
            putString(KEY_SOURCE, source.name)
            putLong(KEY_DURATION_MS, durationMs)
            putString(KEY_ARTIST_ID, artistId)
            putString(KEY_ALBUM_ID, albumId)
            putString(KEY_ARTWORK_TEMPLATE, artworkTemplate.orEmpty())
            putString(KEY_ARTIST_ARTWORK_TEMPLATE, artistArtworkTemplate.orEmpty())
            putString(KEY_ALBUM_ARTIST, albumArtist)
            putInt(KEY_DISC_NUMBER, discNumber)
            putInt(KEY_TRACK_NUMBER, trackNumber)
            if (source == Source.Apple) country.normalizedAppleCountry()?.let { putString(KEY_COUNTRY, it) }
            if (quality != null) putString(KEY_QUALITY, quality)
            if (shuffleStart != null) putBoolean(KEY_SHUFFLE_START, shuffleStart)
            if (occurrence != null) putInt(KEY_OCCURRENCE, occurrence)
        }).build()).build()

internal fun MediaItem.toTrack(): Track? {
    val extras = mediaMetadata.extras
    val source = Source.entries.firstOrNull { mediaId.startsWith("${it.mediaPrefix}:") } ?: return null
    val id = mediaId.substringAfter(':')
    if (!source.validSongId(id)) return null
    val artwork = extras?.getString(KEY_ARTWORK_TEMPLATE)?.takeIf { it.isNotBlank() }
        ?: mediaMetadata.artworkUri?.toString()
    return Track(
        id = id,
        source = source,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        artworkTemplate = artwork,
        durationMs = extras?.getLong(KEY_DURATION_MS, 0)?.coerceAtLeast(0) ?: 0,
        artistId = extras?.getString(KEY_ARTIST_ID).orEmpty(),
        albumId = extras?.getString(KEY_ALBUM_ID).orEmpty(),
        artistArtworkTemplate = extras?.getString(KEY_ARTIST_ARTWORK_TEMPLATE)?.takeIf { it.isNotBlank() },
        albumArtist = extras?.getString(KEY_ALBUM_ARTIST).orEmpty(),
        discNumber = extras?.getInt(KEY_DISC_NUMBER, 0) ?: 0,
        trackNumber = extras?.getInt(KEY_TRACK_NUMBER, 0) ?: 0,
        country = if (source == Source.Apple) extras?.getString(KEY_COUNTRY).normalizedAppleCountry() else null,
    )
}

private val qqSongId = Regex("[0-9A-Za-z]{1,32}")
private val numericSongId = Regex("[1-9][0-9]{0,18}")
internal fun Source.validSongId(id: String): Boolean = when (this) {
    Source.QQ -> qqSongId.matches(id)
    Source.Apple, Source.Netease -> numericSongId.matches(id) && id.toLongOrNull() != null
}

internal val Source.qualities: List<Pair<String, String>> get() = when (this) {
    Source.QQ -> Prefs.QQ_QUALITIES
    Source.Netease -> Prefs.NETEASE_QUALITIES
    Source.Apple -> Prefs.APPLE_QUALITIES
}

internal fun Prefs.preferredQuality(source: Source): String = when (source) {
    Source.QQ -> qqQuality
    Source.Netease -> neteaseQuality
    Source.Apple -> appleQuality
}

@UnstableApi
internal fun QueuedSong.nativeItem(baseUrl: String, quality: String, local: Uri?, requestId: String?): MediaItem {
    val base = track.toQueueItem(quality)
    val metadata = base.mediaMetadata.buildUpon()
        .setArtworkUri(if (local == null) track.artwork(600)?.let(Uri::parse) else null)
        .setExtras(Bundle(base.mediaMetadata.extras).apply {
            putInt(KEY_OCCURRENCE, uid)
        }).build()
    val uri = local ?: Uri.parse(baseUrl).buildUpon()
        .appendPath(track.source.mediaPrefix).appendPath("song").appendPath(track.id).appendPath("play")
        .appendQueryParameter("quality", quality).build()
    return MediaItem.Builder().setMediaId(track.mediaId).setUri(uri)
        .setCustomCacheKey(requestId).setMediaMetadata(metadata).build()
}
