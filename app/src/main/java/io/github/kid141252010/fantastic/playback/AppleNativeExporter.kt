package io.github.kid141252010.fantastic.playback

import android.media.MediaDrm
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.kid141252010.fantastic.data.FantasticApi
import java.io.File
import java.io.IOException
import java.util.HashMap
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
        var widevineSession: WidevineExportSession? = null
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
                widevineSession = WidevineExportSession(api, server, track, request.track.id)
            }
            if (request.appleGroup != null && track.variant.groupId != request.appleGroup) {
                throw IOException("该曲目没有所选音质")
            }
            val estimated = track.initBytes.size.toLong() +
                track.playlist.segments.sumOf { it.rangeLength.coerceAtLeast(0L) } + 1_048_576L
            if (estimated > request.maxBytes) throw IOException("下载文件过大")
            reserve(estimated.coerceAtMost(request.maxBytes))
            target.parentFile?.mkdirs()
            val fragmented = File.createTempFile("apple-native-", ".fmp4", context.cacheDir)
            try {
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
                fragmented.outputStream().buffered().use { output ->
                    output.write(ftyp)
                    output.write(decryptor.rewriteInit(moov))
                    written += ftyp.size + moov.size
                    track.playlist.segments.forEach { segment ->
                        val fetched = decryptor.fetchSegment(track, segment)
                        val bytes = widevineSession?.let { decryptWidevineFragment(decryptor, track, fetched, it) }
                            ?: fetched.bytes
                        if (written + bytes.size > request.maxBytes) throw IOException("下载文件过大")
                        output.write(bytes)
                        written += bytes.size
                        progress(((written * 80L) / estimated).toInt().coerceIn(0, 80))
                    }
                }
                muxProgressive(fragmented, target, request.maxBytes, progress)
            } finally {
                fragmented.delete()
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
            widevineSession?.close()
            decryptor.close()
        }
    }

    private fun muxProgressive(input: File, target: File, maxBytes: Long, progress: (Int?) -> Unit) {
        val muxed = File.createTempFile("apple-native-muxed-", ".mp4", target.parentFile ?: input.parentFile)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.path)
            val audioIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("下载文件没有可识别的音频轨")
            extractor.selectTrack(audioIndex)
            muxer = MediaMuxer(muxed.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(extractor.getTrackFormat(audioIndex))
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            val duration = extractor.getTrackFormat(audioIndex).getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                duration?.let { progress((80L + (19L * extractor.sampleTime.coerceAtLeast(0L) / it)).toInt().coerceIn(80, 99)) }
                extractor.advance()
            }
            muxer.stop()
            muxer = null
            if (muxed.length() > maxBytes) throw IOException("下载文件过大")
            fastStartM4a(muxed, target, maxBytes)
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            muxed.delete()
        }
    }

    private data class TopBox(val start: Int, val body: Int, val end: Int, val header: Int, val type: String)

    private fun fastStartM4a(input: File, target: File, maxBytes: Long) {
        val bytes = input.readBytes()
        val boxes = parseBoxes(bytes, 0, bytes.size)
        val ftyp = boxes.firstOrNull { it.type == "ftyp" } ?: throw IOException("M4A 缺少 ftyp")
        val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IOException("M4A 缺少 moov")
        val mdat = boxes.firstOrNull { it.type == "mdat" } ?: throw IOException("M4A 缺少 mdat")
        val ftypBytes = bytes.copyOfRange(ftyp.start, ftyp.end)
        patchFtyp(ftypBytes)
        val moovBytes = bytes.copyOfRange(moov.start, moov.end)
        val newMdatStart = ftypBytes.size + moovBytes.size
        val delta = (newMdatStart + mdat.header - (mdat.start + mdat.header)).toLong()
        patchChunkOffsets(moovBytes, delta)
        val mdatBytes = bytes.copyOfRange(mdat.start, mdat.end)
        if (ftypBytes.size + moovBytes.size + mdatBytes.size > maxBytes) throw IOException("下载文件过大")
        target.outputStream().buffered().use { output ->
            output.write(ftypBytes)
            output.write(moovBytes)
            output.write(mdatBytes)
        }
    }

    private fun patchFtyp(bytes: ByteArray) {
        if (bytes.size < 32) return
        "M4A ".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 8)
        bytes[12] = 0
        bytes[13] = 0
        bytes[14] = 0
        bytes[15] = 1
        listOf("M4A ", "mp42", "isom", "iso5").forEachIndexed { index, brand ->
            brand.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 16 + index * 4)
        }
    }

    private fun patchChunkOffsets(bytes: ByteArray, delta: Long) {
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "dinf", "edts", "udta", "meta", "ilst")
        fun visit(start: Int, end: Int) {
            for (box in parseBoxes(bytes, start, end)) {
                when (box.type) {
                    "stco" -> {
                        val count = readUInt32(bytes, box.body + 4)
                        for (index in 0 until count) {
                            val offset = readUInt32(bytes, box.body + 8 + index * 4).toLong() + delta
                            if (offset !in 0..0xffffffffL) throw IOException("M4A chunk offset 超出范围")
                            putUInt32(bytes, box.body + 8 + index * 4, offset)
                        }
                    }
                    "co64" -> {
                        val count = readUInt32(bytes, box.body + 4)
                        for (index in 0 until count) {
                            val offset = readUInt64(bytes, box.body + 8 + index * 8) + delta
                            if (offset < 0) throw IOException("M4A chunk offset 无效")
                            putUInt64(bytes, box.body + 8 + index * 8, offset)
                        }
                    }
                }
                if (box.type in containers) {
                    val childStart = if (box.type == "meta") box.body + 4 else box.body
                    if (childStart <= box.end) visit(childStart, box.end)
                }
            }
        }
        visit(8, bytes.size)
    }

    private fun parseBoxes(bytes: ByteArray, start: Int, end: Int): List<TopBox> {
        val result = ArrayList<TopBox>()
        var cursor = start
        while (cursor + 8 <= end) {
            val shortSize = readUInt32(bytes, cursor).toLong() and 0xffffffffL
            val header = if (shortSize == 1L) 16 else 8
            val size = if (shortSize == 0L) (end - cursor).toLong() else if (shortSize == 1L) readUInt64(bytes, cursor + 8) else shortSize
            val next = cursor + size.toInt()
            if (size < header || next > end || next <= cursor) throw IOException("M4A box 长度无效")
            result += TopBox(cursor, cursor + header, next, header, bytes.copyOfRange(cursor + 4, cursor + 8).toString(Charsets.ISO_8859_1))
            cursor = next
        }
        if (cursor != end) throw IOException("M4A box 对齐无效")
        return result
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun putUInt64(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { bytes[offset + it] = (value ushr (56 - it * 8)).toByte() }
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

private class WidevineExportSession(
    api: FantasticApi,
    server: String,
    track: AppleNativeTrack,
    adamId: String,
) : AutoCloseable {
    private val drm = MediaDrm(AppleNativeWidevine.WIDEVINE_UUID)
    private val sessionId: ByteArray
    private val crypto: MediaDrm.CryptoSession

    init {
        try {
            sessionId = drm.openSession()
            val keyUri = track.playlist.segments.asSequence().mapNotNull { it.keyUri }.firstOrNull()
                ?: throw IOException("这首歌的 Widevine 许可未被设备接受")
            val keyRequest = drm.getKeyRequest(
                sessionId,
                null,
                "audio/mp4",
                MediaDrm.KEY_TYPE_STREAMING,
                HashMap(),
            )
            val callback = AppleNativeWidevine(api, server, adamId)
            val license = callback.requestLicense(keyUri, keyRequest.data)
            drm.provideKeyResponse(sessionId, license)
            crypto = drm.getCryptoSession(sessionId, "AES/CENC/NoPadding", "HmacSHA256")
        } catch (error: Throwable) {
            drm.release()
            if (error is IOException && error.message == AppleNativeWidevine.WIDEVINE_REJECTED) throw error
            throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED, error)
        }
    }

    fun decrypt(keyId: ByteArray, encrypted: ByteArray, iv: ByteArray): ByteArray {
        val clear = crypto.decrypt(keyId, encrypted, iv)
        if (clear.size != encrypted.size) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
        return clear
    }

    override fun close() {
        runCatching { drm.closeSession(sessionId) }
        runCatching { drm.release() }
    }
}

private fun decryptWidevineFragment(
    decryptor: AppleNativeDecrypt,
    track: AppleNativeTrack,
    fetched: AppleNativeFetchedSegment,
    session: WidevineExportSession,
): ByteArray {
    val keyUri = track.playlist.segments.mapNotNull { it.keyUri }.firstOrNull()
        ?: throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    val keyId = appleWidevineKeyId(keyUri)
    val encryptions = parseSenc(fetched.bytes)
    if (encryptions.size != fetched.sampleRanges.size) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    encryptions.zip(fetched.sampleRanges).forEach { (encryption, range) ->
        if (encryption.subsamples) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
        val encrypted = fetched.bytes.copyOfRange(range.first, range.last + 1)
        val clear = session.decrypt(keyId, encrypted, encryption.iv)
        clear.copyInto(fetched.bytes, range.first)
    }
    return decryptor.rewriteFragment(fetched.bytes, fetched.sampleRanges)
}

private data class SencSample(val iv: ByteArray, val subsamples: Boolean)

private fun parseSenc(bytes: ByteArray): List<SencSample> {
    val moof = mp4Children(bytes, 0, bytes.size).firstOrNull { it.type == "moof" }
        ?: throw IOException("Apple Widevine 分片缺少 moof")
    val traf = mp4Children(bytes, moof.body, moof.end).firstOrNull { it.type == "traf" }
        ?: throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    val senc = mp4Children(bytes, traf.body, traf.end).firstOrNull { it.type == "senc" }
        ?: throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    var cursor = senc.body
    val flags = readInt32(bytes, cursor) and 0x00ffffff
    cursor += 4
    val count = readInt32(bytes, cursor)
    cursor += 4
    var ivSize = 16
    if (flags and 1 != 0) {
        if (cursor + 20 > senc.end) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
        cursor += 3
        ivSize = bytes[cursor++].toInt() and 0xff
        cursor += 16
    }
    if (ivSize != 8 && ivSize != 16) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
    return buildList(count) {
        repeat(count) {
            if (cursor + ivSize > senc.end) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
            val iv = bytes.copyOfRange(cursor, cursor + ivSize)
            cursor += ivSize
            val hasSubsamples = flags and 2 != 0
            if (hasSubsamples) {
                if (cursor + 2 > senc.end) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
                val subsampleCount = ((bytes[cursor].toInt() and 0xff) shl 8) or (bytes[cursor + 1].toInt() and 0xff)
                cursor += 2 + subsampleCount * 6
                if (cursor > senc.end) throw IOException(AppleNativeWidevine.WIDEVINE_REJECTED)
            }
            add(SencSample(iv, hasSubsamples))
        }
    }
}

private data class Mp4Range(val body: Int, val end: Int, val type: String)

private fun mp4Children(bytes: ByteArray, start: Int, end: Int): List<Mp4Range> {
    val result = ArrayList<Mp4Range>()
    var cursor = start
    while (cursor + 8 <= end) {
        val size = readInt32(bytes, cursor).toLong() and 0xffffffffL
        val boxEnd = when (size) {
            0L -> end
            1L -> if (cursor + 16 <= end) readInt64(bytes, cursor + 8).toInt() + cursor else throw IOException("Apple MP4 无效")
            else -> cursor + size.toInt()
        }
        if (boxEnd <= cursor + 8 || boxEnd > end) throw IOException("Apple MP4 无效")
        result += Mp4Range(cursor + if (size == 1L) 16 else 8, boxEnd, bytes.copyOfRange(cursor + 4, cursor + 8).toString(Charsets.ISO_8859_1))
        cursor = boxEnd
    }
    return result
}

private fun readInt32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)

private fun readInt64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    repeat(8) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
    return value
}
