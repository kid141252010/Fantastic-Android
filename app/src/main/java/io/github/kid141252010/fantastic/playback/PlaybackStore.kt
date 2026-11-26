package io.github.kid141252010.fantastic.playback

import android.content.Context
import androidx.media3.common.Player
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class QueuedSong(val uid: Int, val track: Track, val quality: String? = null)

@Serializable
internal data class PlaybackSnapshot(
    val songs: List<QueuedSong> = emptyList(),
    val order: List<Int> = emptyList(),
    val current: Int? = null,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

/** Queue edits and transport checkpoints are independent, so a position update never encodes a playlist. */
internal class PlaybackStore(context: Context, private val settings: Prefs) {
    private val prefs = context.getSharedPreferences("fantastic-playback", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PlaybackSnapshot {
        val saved = runCatching {
            json.decodeFromString<PlaybackSnapshot>(prefs.getString("queue", null).orEmpty())
        }.getOrDefault(PlaybackSnapshot())
        val modes = PlaybackSnapshot(shuffle = saved.shuffle,
            repeat = saved.repeat.takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL } ?: Player.REPEAT_MODE_OFF)
        if (prefs.getString("server", null) != settings.baseUrl) return modes
        if (prefs.getString("storefront", null) != settings.storefront &&
            saved.songs.any { it.track.source == Source.Apple && it.track.country.normalizedAppleCountry() == null }) return modes
        val ids = saved.songs.map { it.uid }.toSet()
        val selected = prefs.getInt("positionUid", -1).takeIf { it in ids } ?: saved.current
        if (saved.songs.size != ids.size || saved.order.size != ids.size || saved.order.toSet() != ids || selected == null || selected !in ids) {
            return modes
        }
        return saved.copy(current = selected, repeat = modes.repeat)
    }

    fun position(uid: Int?): Long = if (uid != null && prefs.getInt("positionUid", -1) == uid)
        prefs.getLong("position", 0).coerceAtLeast(0) else 0

    fun save(snapshot: PlaybackSnapshot, position: Long) {
        prefs.edit().putString("server", settings.baseUrl).putString("storefront", settings.storefront)
            .putString("queue", json.encodeToString(PlaybackSnapshot.serializer(), snapshot))
            .putInt("positionUid", snapshot.current ?: -1).putLong("position", position).apply()
    }

    fun checkpoint(uid: Int, position: Long) {
        prefs.edit().putInt("positionUid", uid).putLong("position", position.coerceAtLeast(0)).apply()
    }
}
