package io.github.kid141252010.fantastic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Media3 source whose playlist and fragments are resolved by the in-process Apple decryptor. */
internal class AppleNativeDataSourceFactory(
    private val track: AppleNativeTrack,
    private val decryptor: AppleNativeDecrypt,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = AppleNativeDataSource(track, decryptor)
}

private class AppleNativeDataSource(
    private val track: AppleNativeTrack,
    private val decryptor: AppleNativeDecrypt,
) : DataSource {
    private var uri: Uri? = null
    private var bytes = ByteArray(0)
    private var position = 0
    private var end = 0

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val loaded = runBlocking(Dispatchers.IO) { load(dataSpec.uri) }
        val requestedStart = dataSpec.position.coerceAtLeast(0L).coerceAtMost(loaded.size.toLong()).toInt()
        val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) loaded.size else
            (requestedStart.toLong() + dataSpec.length).coerceAtMost(loaded.size.toLong()).toInt()
        bytes = loaded
        position = requestedStart
        end = requestedEnd.coerceAtLeast(requestedStart)
        return (end - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= end) return C.RESULT_END_OF_INPUT
        val count = minOf(length, end - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        bytes = ByteArray(0)
        position = 0
        end = 0
        uri = null
    }

    private suspend fun load(uri: Uri): ByteArray {
        return when {
            uri.host == "playlist" -> playlistBytes()
            uri.host == "init" -> decryptor.rewriteInit(track.initBytes)
            uri.host == "segment" -> {
                val index = uri.pathSegments.lastOrNull()?.toIntOrNull()
                    ?: throw IOException("Apple 分片地址无效")
                val segment = track.playlist.segments.getOrNull(index)
                    ?: throw IOException("Apple 分片地址无效")
                decryptor.fetchSegment(track, segment).bytes
            }
            else -> throw IOException("Apple 播放地址无效")
        }
    }

    private fun playlistBytes(): ByteArray {
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:")
            append(((track.playlist.targetDurationMs + 999L) / 1000L).coerceAtLeast(1L))
            append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            track.playlist.init?.let {
                append("#EXT-X-MAP:URI=\"apple-native://init\"")
                append('\n')
            }
            track.playlist.segments.forEachIndexed { index, segment ->
                append("#EXTINF:")
                append(segment.durationMs / 1000.0)
                append(",\napple-native://segment/")
                append(index)
                append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
        return playlist.toByteArray(Charsets.UTF_8)
    }
}
