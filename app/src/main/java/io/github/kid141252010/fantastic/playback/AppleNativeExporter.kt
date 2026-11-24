package io.github.kid141252010.fantastic.playback

import android.media.MediaDrm
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            val init = track.initBytes.copyOf()
            decryptor.rewriteInit(init)
            val timescale = mediaTimescale(init)
            val sizes = ArrayList<Int>()
            val durations = ArrayList<Long>()
            FileOutputStream(target).channel.use { channel ->
                writeFully(channel, ByteBuffer.wrap(M4A_FTYP))
                val mdatHeader = channel.position()
                writeFully(channel, ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(1)
                    put(byteArrayOf('m'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte()))
                    putLong(0)
                    flip()
                })
                var payload = 0L
                val segmentCount = track.playlist.segments.size.coerceAtLeast(1)
                track.playlist.segments.forEachIndexed { index, segment ->
                    val fetched = decryptor.fetchSegment(track, segment)
                    val bytes = widevineSession?.let { decryptWidevineFragment(decryptor, track, fetched, it) }
                        ?: fetched.bytes
                    val ranges = fetched.sampleRanges
                    val sampleDurations = decryptor.sampleDurations(bytes)
                    if (sampleDurations.size != ranges.size) throw IOException("Apple 分片样本时长无效")
                    val segmentUnits = (segment.durationMs * timescale / 1_000L).coerceAtLeast(ranges.size.toLong())
                    val fallback = (segmentUnits / ranges.size.coerceAtLeast(1)).coerceAtLeast(1L)
                    ranges.forEachIndexed { sampleIndex, range ->
                        val size = range.last + 1 - range.first
                        if (size <= 0) throw IOException("Apple 分片样本为空")
                        if (M4A_FTYP.size + 16L + payload + size > request.maxBytes) throw IOException("下载文件过大")
                        writeFully(channel, ByteBuffer.wrap(bytes, range.first, size))
                        sizes.add(size)
                        durations.add(sampleDurations[sampleIndex].takeIf { it > 0L } ?: fallback)
                        payload += size
                    }
                    progress((((index + 1L) * 99L) / segmentCount).toInt())
                }
                if (sizes.isEmpty()) throw IOException("下载文件为空")
                val duration = durations.fold(0L) { sum, value ->
                    if (value <= 0L || sum > Long.MAX_VALUE - value) throw IOException("M4A 时长无效")
                    sum + value
                }
                if (duration > 0xffffffffL) throw IOException("M4A 时长超出封装范围")
                val moov = progressiveMoov(audioSampleEntry(init), timescale, duration, sizes, durations, 48L)
                if (M4A_FTYP.size + 16L + payload + moov.size > request.maxBytes) throw IOException("下载文件过大")
                val sizePatch = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(16L + payload)
                sizePatch.flip()
                channel.position(mdatHeader + 8)
                writeFully(channel, sizePatch)
                channel.position(mdatHeader + 16 + payload)
                writeFully(channel, ByteBuffer.wrap(moov))
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

    private fun mediaTimescale(init: ByteArray): Long {
        val mdhd = nestedBox(init, "moov", "trak", "mdia", "mdhd")
        val header = boxHeader(mdhd)
        val version = mdhd[header].toInt() and 0xff
        val offset = header + if (version == 0) 12 else 20
        if (version > 1 || mdhd.size < offset + 4) throw IOException("Apple init 时基无效")
        val timescale = readUInt32(mdhd, offset).toLong() and 0xffffffffL
        if (timescale <= 0L) throw IOException("Apple init 时基无效")
        return timescale
    }

    private fun audioSampleEntry(init: ByteArray): ByteArray {
        val stsd = nestedBox(init, "moov", "trak", "mdia", "minf", "stbl", "stsd")
        val entryStart = boxHeader(stsd) + 8
        val entryEnd = boxEnd(stsd, entryStart) ?: throw IOException("Apple init 缺少音频样本描述")
        return stsd.copyOfRange(entryStart, entryEnd)
    }

    private fun nestedBox(bytes: ByteArray, vararg path: String): ByteArray {
        var current = topBox(bytes, path[0]) ?: throw IOException("Apple init 缺少 ${path[0]}")
        for (type in path.drop(1)) current = childBox(current, type) ?: throw IOException("Apple init 缺少 $type")
        return current
    }

    private fun topBox(bytes: ByteArray, type: String): ByteArray? {
        var cursor = 0
        while (cursor + 8 <= bytes.size) {
            val end = boxEnd(bytes, cursor) ?: return null
            if (boxType(bytes, cursor) == type) return bytes.copyOfRange(cursor, end)
            cursor = end
        }
        return null
    }

    private fun childBox(parent: ByteArray, type: String): ByteArray? {
        var cursor = boxHeader(parent)
        while (cursor + 8 <= parent.size) {
            val end = boxEnd(parent, cursor) ?: return null
            if (boxType(parent, cursor) == type) return parent.copyOfRange(cursor, end)
            cursor = end
        }
        return null
    }

    private fun boxHeader(box: ByteArray): Int = if ((readUInt32(box, 0).toLong() and 0xffffffffL) == 1L) 16 else 8

    private fun boxEnd(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        val shortSize = readUInt32(bytes, offset).toLong() and 0xffffffffL
        val header = if (shortSize == 1L) 16 else 8
        val size = when (shortSize) {
            0L -> (bytes.size - offset).toLong()
            1L -> if (offset + 16 > bytes.size) return null else readUInt64(bytes, offset + 8)
            else -> shortSize
        }
        if (size < header || offset.toLong() + size > bytes.size) return null
        return offset + size.toInt()
    }

    private fun boxType(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.ISO_8859_1)

    private fun progressiveMoov(
        sampleEntry: ByteArray,
        timescale: Long,
        duration: Long,
        sizes: List<Int>,
        durations: List<Long>,
        sampleOffset: Long,
    ): ByteArray {
        val runs = ArrayList<Pair<Long, Int>>()
        durations.forEach { value ->
            val last = runs.lastOrNull()
            if (last != null && last.first == value && last.second < Int.MAX_VALUE) runs[runs.lastIndex] = value to last.second + 1
            else runs.add(value to 1)
        }
        val stts = ByteArray(8 + runs.size * 8)
        putUInt32(stts, 4, runs.size.toLong())
        runs.forEachIndexed { index, (value, count) ->
            putUInt32(stts, 8 + index * 8, count.toLong())
            putUInt32(stts, 12 + index * 8, value)
        }
        val stsc = ByteArray(20)
        putUInt32(stsc, 4, 1)
        putUInt32(stsc, 8, 1)
        putUInt32(stsc, 12, sizes.size.toLong())
        putUInt32(stsc, 16, 1)
        val stsz = ByteArray(12 + sizes.size * 4)
        putUInt32(stsz, 8, sizes.size.toLong())
        sizes.forEachIndexed { index, size -> putUInt32(stsz, 12 + index * 4, size.toLong()) }
        val stco = ByteArray(12)
        putUInt32(stco, 4, 1)
        putUInt32(stco, 8, sampleOffset)
        val stsd = ByteArray(8 + sampleEntry.size)
        putUInt32(stsd, 4, 1)
        sampleEntry.copyInto(stsd, 8)
        val url = box("url ", byteArrayOf(0, 0, 0, 1))
        val dref = ByteArray(8 + url.size)
        putUInt32(dref, 4, 1)
        url.copyInto(dref, 8)
        val name = "SoundHandler\u0000".toByteArray(Charsets.ISO_8859_1)
        val hdlr = ByteArray(24 + name.size)
        "soun".toByteArray(Charsets.ISO_8859_1).copyInto(hdlr, 8)
        name.copyInto(hdlr, 24)
        return box("moov", concat(
            mvhd(timescale, duration),
            box("trak", concat(
                tkhd(duration),
                box("mdia", concat(
                    mdhd(timescale, duration),
                    box("hdlr", hdlr),
                    box("minf", concat(
                        box("smhd", ByteArray(8)),
                        box("dinf", box("dref", dref)),
                        box("stbl", concat(
                            box("stsd", stsd),
                            box("stts", stts),
                            box("stsc", stsc),
                            box("stsz", stsz),
                            box("stco", stco),
                        )),
                    )),
                )),
            )),
        ))
    }

    private fun mvhd(timescale: Long, duration: Long): ByteArray {
        val body = ByteArray(100)
        putUInt32(body, 12, timescale)
        putUInt32(body, 16, duration)
        putUInt32(body, 20, 0x10000)
        putUInt16(body, 24, 0x0100)
        identityMatrix(body, 36)
        putUInt32(body, 96, 2)
        return box("mvhd", body)
    }

    private fun tkhd(duration: Long): ByteArray {
        val body = ByteArray(84)
        putUInt32(body, 0, 3)
        putUInt32(body, 12, 1)
        putUInt32(body, 20, duration)
        putUInt16(body, 36, 0x0100)
        identityMatrix(body, 40)
        return box("tkhd", body)
    }

    private fun mdhd(timescale: Long, duration: Long): ByteArray {
        val body = ByteArray(24)
        putUInt32(body, 12, timescale)
        putUInt32(body, 16, duration)
        putUInt16(body, 20, 0x55C4)
        return box("mdhd", body)
    }

    private fun identityMatrix(bytes: ByteArray, offset: Int) {
        putUInt32(bytes, offset, 0x10000)
        putUInt32(bytes, offset + 16, 0x10000)
        putUInt32(bytes, offset + 32, 0x40000000)
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        putUInt32(out, 0, out.size.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        body.copyInto(out, 8)
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    private fun putUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
    private fun writeFully(channel: java.nio.channels.WritableByteChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw IOException("M4A 写入失败")
        }
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

    private companion object {
        val M4A_FTYP = byteArrayOf(
            0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
            0, 0, 0, 1,
            'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte(),
        )
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
