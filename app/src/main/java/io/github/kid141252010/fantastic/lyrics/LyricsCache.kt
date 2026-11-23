package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.util.Log
import coil3.disk.DiskCache
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath

@Serializable
internal data class CachedLyrics(val source: String, val song: LyricSong)

/** Stores the complete parsed song, including the timing and annotation data used by the renderer. */
internal class LyricsCache(context: Context) {
    private val directory = File(context.cacheDir, "lyrics-v1")
    private val disk by lazy {
        DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(MAX_BYTES).build()
    }

    suspend fun find(key: String): CachedLyrics? = withContext(Dispatchers.IO) {
        try {
            val snapshot = disk.openSnapshot(key) ?: return@withContext null
            val cached = snapshot.use {
                val size = disk.fileSystem.metadata(it.data).size ?: 0L
                if (size !in 1L..MAX_DOCUMENT_BYTES) throw IOException("歌词缓存长度无效")
                disk.fileSystem.read(it.data) { Json.decodeFromString<CachedLyrics>(readUtf8(size)) }
            }
            if (cached.song.lines.isNotEmpty()) return@withContext cached
            disk.remove(key)
        } catch (error: Exception) {
            if (error !is IOException && error !is IllegalArgumentException) throw error
            try { disk.remove(key) } catch (_: IOException) { }
        }
        null
    }

    suspend fun store(key: String, lyrics: CachedLyrics) = withContext(Dispatchers.IO) {
        if (lyrics.song.lines.isEmpty()) return@withContext
        val data = Json.encodeToString(lyrics).encodeToByteArray()
        if (data.size.toLong() > MAX_DOCUMENT_BYTES) return@withContext
        try {
            val editor = disk.openEditor(key) ?: return@withContext
            try {
                disk.fileSystem.write(editor.metadata) { }
                disk.fileSystem.write(editor.data) { write(data) }
            } catch (error: IOException) {
                editor.abort()
                throw error
            }
            editor.commit()
        } catch (error: IOException) {
            Log.d("FantasticLyrics", "无法写入歌词缓存", error)
        }
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 4L * 1024 * 1024
    }
}
