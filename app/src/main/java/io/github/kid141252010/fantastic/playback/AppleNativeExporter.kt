package io.github.kid141252010.fantastic.playback

import io.github.kid141252010.fantastic.data.FantasticApi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads the same FairPlay-resolved fragments used by native playback. */
internal class AppleNativeExporter(
    private val api: FantasticApi,
    private val context: android.content.Context,
) {
    suspend fun export(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo = withContext(Dispatchers.IO) {
        val server = io.github.kid141252010.fantastic.data.SourceStatus.normalizeBaseUrl(request.server)
            ?: throw IOException("服务器地址无效")
        val decryptor = AppleNativeDecrypt(api) { path ->
            context.assets.open(path).use { it.readBytes() }
        }
        try {
            val requested = request.appleGroup?.takeIf { it.isNotBlank() } ?: request.quality
            val track = decryptor.resolve(
                server = server,
                adamId = request.track.id,
                requestedQuality = requested,
                profile = ApplePlaybackProfile("wifi", requested, "off"),
                explicit = request.appleGroup != null || request.quality.isNotBlank(),
            )
            if (track.widevine) {
                throw IOException("这首歌的 Widevine 许可未被设备接受")
            }
            if (request.appleGroup != null && track.variant.groupId != request.appleGroup) {
                throw IOException("该曲目没有所选音质")
            }
            val estimated = track.initBytes.size.toLong() +
                track.playlist.segments.sumOf { it.rangeLength.coerceAtLeast(0L) } + 1_048_576L
            if (estimated > request.maxBytes) throw IOException("下载文件过大")
            reserve(estimated.coerceAtMost(request.maxBytes))
            target.parentFile?.mkdirs()
            val moov = findTopLevelBox(track.initBytes, "moov") ?: throw IOException("Apple init 缺少 moov")
            val ftyp = byteArrayOf(
                0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
                0, 0, 0, 1,
                'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte(),
            )
            var written = 0L
            target.outputStream().buffered().use { output ->
                output.write(ftyp)
                output.write(decryptor.rewriteInit(moov))
                written += ftyp.size + moov.size
                track.playlist.segments.forEachIndexed { index, segment ->
                    val bytes = decryptor.fetchSegment(track, segment).bytes
                    if (written + bytes.size > request.maxBytes) throw IOException("下载文件过大")
                    output.write(bytes)
                    written += bytes.size
                    progress(((written * 100L) / estimated).toInt().coerceIn(0, 99))
                }
            }
            if (target.length() <= 0L) throw IOException("下载文件为空")
            progress(100)
            AudioFileInfo(
                sizeBytes = target.length(),
                durationMs = track.playlist.totalDurationMs.takeIf { it > 0 } ?: request.track.durationMs,
                mime = "audio/mp4",
                sampleRate = track.variant.sampleRate.takeIf { it > 0 },
                quality = codecGroupForExport(track.variant.groupId),
            )
        } finally {
            decryptor.close()
        }
    }

    private fun findTopLevelBox(bytes: ByteArray, wanted: String): ByteArray? {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size = readUInt32(bytes, offset).toLong()
            val header = if (size == 1L) 16 else 8
            val end = when {
                size == 0L -> bytes.size
                size == 1L && offset + 16 <= bytes.size -> readUInt64(bytes, offset + 8).toInt()
                else -> offset + size.toInt()
            }
            if (end > bytes.size || end <= offset + header) return null
            if (bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.ISO_8859_1) == wanted) {
                return bytes.copyOfRange(offset, end)
            }
            offset = end
        }
        return null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
        return value
    }

    private fun codecGroupForExport(group: String): String = when {
        group.contains("alac", ignoreCase = true) -> "alac"
        group.contains("atmos", ignoreCase = true) -> "atmos"
        group.contains("he-", ignoreCase = true) -> "aac-he"
        group.contains("128", ignoreCase = true) -> "aac-128"
        else -> "aac"
    }
}
