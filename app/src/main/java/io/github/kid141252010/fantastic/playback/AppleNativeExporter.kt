package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.media.MediaDrm
import androidx.media3.common.C
import android.util.Base64
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.Prefs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Writes a progressive M4A from Apple's fragmented audio without re-encoding it. */
internal class AppleNativeExporter(private val context: Context, private val api: FantasticApi) {
    suspend fun export(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo = withContext(Dispatchers.IO) {
        val wanted = normalizeWanted(request.quality)
        val profile = applePlaybackProfile(context.applicationContext, Prefs(context.applicationContext))
        val decrypt = AppleNativeDecrypt(context.applicationContext, api, request.server)
        val track = try {
            decrypt.prepare(request.track.id, wanted, profile, request.appleGroup)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        val encryptedSize = track.media.totalBytes
        if (encryptedSize <= 0 || encryptedSize > Long.MAX_VALUE - 1_048_576L || encryptedSize + 1_048_576L > request.maxBytes) {
            throw IOException("下载文件过大")
        }
        val init = decrypt.fetchInit(track)
        val source = parseInit(init)
        val drm = if (track.variant.widevine) openWidevine(track, request.server) else null
        try {
            val payloadFile = File.createTempFile("${target.name.take(20)}-apple-", ".payload", target.parentFile ?: context.cacheDir)
            var spoolPosition = 0L
            val chunks = ArrayList<Chunk>()
            var totalDuration = 0L
            try {
                FileOutputStream(payloadFile, false).use { spool ->
                    for (index in track.media.segments.indices) {
                        coroutineContext.ensureActive()
                        val raw = decrypt.fetchSegment(track, index)
                        val segment = track.media.segments[index]
                        val parsed = parseFragment(raw, source.trackId, segment.keyKind == KeyKind.WIDEVINE, drm?.kid, source.ivSize)
                        val bytes = if (drm != null) decryptWidevine(raw, parsed, drm) else raw
                        for (chunk in parsed.chunks) {
                            if (chunk.samples.isEmpty()) continue
                            val spoolStart = spoolPosition
                            for (sample in chunk.samples) {
                                if (sample.offset < 0 || sample.size < 0 || sample.offset.toLong() + sample.size > bytes.size.toLong()) throw badMp4()
                                spool.write(bytes, sample.offset, sample.size)
                                spoolPosition = Math.addExact(spoolPosition, sample.size.toLong())
                            }
                            chunks += Chunk(chunk.samples.mapIndexed { i, sample ->
                                Sample(sample.duration, sample.size, spoolStart + chunk.samples.take(i).sumOf { it.size.toLong() }, sample.index)
                            })
                        }
                        totalDuration = Math.addExact(totalDuration, parsed.duration)
                        progress((index + 1) * 70 / track.media.segments.size.coerceAtLeast(1))
                    }
                }
                if (chunks.isEmpty() || totalDuration <= 0) throw IOException("Apple 音频轨道为空")
                val flat = chunks.flatMap { it.samples }
                val durationTicks = flat.fold(0L) { acc, sample -> Math.addExact(acc, sample.duration.toLong()) }
                val mdatPlan = planMdat(chunks)
                val ftyp = box("ftyp", concat(bytes("M4A "), u32(1), bytes("M4A "), bytes("mp42"), bytes("isom"), bytes("iso5")))
                var moov = buildMoov(source, chunks, flat, mdatPlan.offsets, false, durationTicks)
                var absoluteOffsets = mdatPlan.offsets.map { ftyp.size.toLong() + moov.size + it }
                val co64 = absoluteOffsets.lastOrNull()?.let { it > 0xffff_ffffL - 64L * 1024 * 1024 } ?: false
                if (co64) {
                    moov = buildMoov(source, chunks, flat, absoluteOffsets, true, durationTicks)
                    absoluteOffsets = mdatPlan.offsets.map { ftyp.size.toLong() + moov.size + it }
                }
                moov = buildMoov(source, chunks, flat, absoluteOffsets, co64, durationTicks)
                val projected = ftyp.size.toLong() + moov.size + mdatPlan.payloadBytes + mdatPlan.headers.sum()
                if (projected > request.maxBytes) throw IOException("下载文件过大")
                target.parentFile?.mkdirs()
                FileOutputStream(target, false).use { output ->
                    output.write(ftyp); output.write(moov)
                    var written = ftyp.size.toLong() + moov.size
                    RandomAccessFile(payloadFile, "r").use { spool ->
                        for ((groupIndex, group) in mdatPlan.groups.withIndex()) {
                            coroutineContext.ensureActive()
                            val groupSize = group.sumOf { c -> c.samples.sumOf { it.size.toLong() } }
                            output.write(boxHeader(groupSize + 8, "mdat")); written += 8; reserve(written)
                            for (chunk in group) for (sample in chunk.samples) {
                                spool.seek(sample.dataOffset)
                                var remaining = sample.size
                                val buffer = ByteArray(64 * 1024)
                                while (remaining > 0) {
                                    coroutineContext.ensureActive()
                                    val count = minOf(remaining, buffer.size)
                                    spool.readFully(buffer, 0, count); output.write(buffer, 0, count)
                                    remaining -= count; written += count
                                    if (written > request.maxBytes) throw IOException("下载文件过大")
                                    reserve(written)
                                }
                            }
                            progress(70 + (groupIndex + 1) * 29 / mdatPlan.groups.size.coerceAtLeast(1))
                        }
                    }
                    output.flush()
                }
                progress(100)
                val durationMs = (durationTicks * 1000L / source.timescale).takeIf { it > 0 } ?: track.media.durationMs
                AudioFileInfo(target.length(), durationMs, "audio/mp4", track.variant.sampleRate.takeIf { it > 0 }, request.quality)
            } finally {
                payloadFile.delete()
            }
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            drm?.close()
        }
    }

    private fun ftypBaseOffset(offset: Long, moovSize: Int) = 32L + moovSize + offset

    private fun normalizeWanted(value: String): String? = when (value) {
        "hires", "lossless", "high", "efficient", "alac", "atmos", "aac", "aac-128", "aac-he",
        "aac-binaural", "aac-binaural-128", "he-binaural", "aac-downmix", "aac-downmix-128", "he-downmix" -> value
        else -> null // A concrete cached rendition is selected by appleGroup; otherwise use the saved profile.
    }

    private suspend fun openWidevine(track: AppleResolvedTrack, base: String): DrmSession {
        try {
            if (!MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID)) throw IOException()
            val drm = MediaDrm(C.WIDEVINE_UUID)
            val sessionId = drm.openSession()
            try {
                val pssh = track.widevinePssh ?: throw IOException()
                val request = drm.getKeyRequest(sessionId, pssh, "audio/mp4", MediaDrm.KEY_TYPE_STREAMING, null)
                val server = base.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() }
                    ?: throw IOException()
                val url = server.newBuilder().addPathSegment("mv").addPathSegment("license").build()
                val json = JSONObject().put("adamId", track.media.adamId)
                    .put("challenge", Base64.encodeToString(request.data, Base64.NO_WRAP))
                    .put("uri", track.widevineUri)
                val call = api.client.newCall(Request.Builder().url(url).post(json.toString().toRequestBody("application/json".toMediaType())).build())
                val response = executeCancellable(call)
                val licenseBytes = response.use {
                    if (!it.isSuccessful) throw IOException()
                    val body = it.body?.string() ?: throw IOException()
                    val obj = JSONObject(body)
                    if (obj.optInt("code", -1) != 0) throw IOException()
                    val encoded = obj.optJSONObject("data")?.opt("license") as? String
                        ?: throw IOException()
                    if (encoded.isBlank()) throw IOException()
                    decodeLicense(encoded)
                }
                drm.provideKeyResponse(sessionId, licenseBytes)
                val crypto = drm.getCryptoSession(sessionId, "AES/CTR/NoPadding", "HmacSHA256")
                val kid = psshKid(pssh) ?: throw IOException()
                return DrmSession(drm, sessionId, crypto, kid)
            } catch (error: Throwable) {
                drm.closeSession(sessionId)
                drm.release()
                throw error
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw IOException("这首歌的 Widevine 许可未被设备接受", error)
        }
    }

    private fun decodeLicense(text: String): ByteArray = try {
        Base64.decode(text, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        try { Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP) } catch (_: IllegalArgumentException) { text.toByteArray(Charsets.UTF_8) }
    }
    private suspend fun executeCancellable(call: okhttp3.Call): okhttp3.Response = suspendCancellableCoroutine { continuation ->
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
        continuation.invokeOnCancellation { call.cancel() }
    }
    private fun decryptWidevine(bytes: ByteArray, fragment: Fragment, drm: DrmSession): ByteArray {
        try {
            val output = bytes.copyOf()
            for (sample in fragment.samples) {
                val iv = sample.iv ?: throw IOException()
                if (sample.offset < 0 || sample.size < 0 || sample.offset.toLong() + sample.size > output.size.toLong()) throw IOException()
                val encrypted = output.copyOfRange(sample.offset, sample.offset + sample.size)
                val plain = drm.crypto.decrypt(drm.kid, encrypted, iv)
                if (plain.size != sample.size) throw IOException()
                plain.copyInto(output, sample.offset)
            }
            for (offset in fragment.encryptionBoxes) "free".toByteArray(Charsets.ISO_8859_1).copyInto(output, offset + 4)
            return output
        } catch (error: Throwable) {
            throw IOException("这首歌的 Widevine 许可未被设备接受", error)
        }
    }

    private data class DrmSession(val drm: MediaDrm, val session: ByteArray, val crypto: MediaDrm.CryptoSession, val kid: ByteArray) {
        fun close() { runCatching { drm.closeSession(session) }; drm.release() }
    }

    private data class Box(val type: String, val start: Int, val body: Int, val end: Int) { val size get() = end - start }
    private data class Sample(val duration: Int, val size: Int, val dataOffset: Long, val index: Int)
    private data class Chunk(val samples: List<Sample>)
    private data class Source(val init: ByteArray, val moov: Box, val trak: Box, val stsd: ByteArray, val trackId: Long, val timescale: Long, val duration: Long, val ivSize: Int)
    private data class LocatedSample(val offset: Int, val size: Int, val duration: Int, val iv: ByteArray?, val index: Int)
    private data class FragmentChunk(val samples: List<LocatedSample>)
    private data class Fragment(val chunks: List<FragmentChunk>, val samples: List<LocatedSample>, val duration: Long, val encryptionBoxes: List<Int>, val keyId: ByteArray?)
    private data class MdatPlan(val groups: List<List<Chunk>>, val offsets: List<Long>, val headers: List<Long>, val payloadBytes: Long)

    private fun parseInit(data: ByteArray): Source {
        val top = boxes(data)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw badMp4()
        for (trak in children(data, moov).filter { it.type == "trak" }) {
            val mdia = child(data, trak, "mdia") ?: continue
            val hdlr = child(data, mdia, "hdlr") ?: continue
            if (hdlr.body + 12 > hdlr.end || type(data, hdlr.body + 8) != "soun") continue
            val minf = child(data, mdia, "minf") ?: throw badMp4()
            val stbl = child(data, minf, "stbl") ?: throw badMp4()
            val stsd = child(data, stbl, "stsd") ?: throw badMp4()
            val originalEntry = boxes(data, stsd.body + 8, stsd.end).firstOrNull() ?: throw badMp4()
            val sinf = boxes(data, originalEntry.body + 28, originalEntry.end).firstOrNull { it.type == "sinf" }
            val schi = sinf?.let { child(data, it, "schi") }
            val tenc = schi?.let { child(data, it, "tenc") }
            val ivSize = tenc?.let { if (it.body + 7 <= it.end) u8(data, it.body + 6) else 0 } ?: 0
            val patched = patchSampleEntry(data.copyOfRange(stsd.start, stsd.end))
            val tkhd = child(data, trak, "tkhd") ?: throw badMp4()
            val trackIdAt = tkhd.body + if (u8(data, tkhd.body) == 1) 20 else 12
            val trackId = u32(data, trackIdAt)
            val mdhd = child(data, mdia, "mdhd") ?: throw badMp4()
            val version = u8(data, mdhd.body)
            val timescaleAt = mdhd.body + if (version == 1) 20 else 12
            val durationAt = timescaleAt + 4
            return Source(data.copyOf(), moov, trak, patched, trackId, u32(data, timescaleAt), if (version == 1) u64(data, durationAt) else u32(data, durationAt), ivSize)
        }
        throw IOException("Apple 音频轨道不存在")
    }

    private fun patchSampleEntry(stsd: ByteArray): ByteArray {
        val patched = stsd.copyOf()
        val entries = boxes(patched, 16, patched.size)
        for (entry in entries) {
            if (entry.type != "enca") continue
            val sinf = boxes(patched, entry.body + 28, entry.end).firstOrNull { it.type == "sinf" } ?: continue
            val frma = child(patched, sinf, "frma") ?: continue
            if (frma.body + 4 > frma.end) throw badMp4()
            typeBytes(patched, entry.start, type(patched, frma.body))
            typeBytes(patched, sinf.start, "free")
            patched.fill(0, sinf.body, sinf.end)
        }
        return patched
    }

    private fun parseFragment(data: ByteArray, audioTrackId: Long, widevine: Boolean, keyId: ByteArray?, ivSize: Int): Fragment {
        val chunks = ArrayList<FragmentChunk>()
        val samples = ArrayList<LocatedSample>()
        val all = ArrayList<LocatedSample>()
        val encryption = ArrayList<Int>()
        val ivs = ArrayList<ByteArray>()
        var duration = 0L
        val moof = boxes(data).firstOrNull { it.type == "moof" } ?: throw badMp4()
        for (traf in children(data, moof).filter { it.type == "traf" }) {
            val trafChildren = children(data, traf)
            val tfhd = trafChildren.firstOrNull { it.type == "tfhd" } ?: throw badMp4()
            if (tfhd.body + 8 > tfhd.end) throw badMp4()
            val flags = u24(data, tfhd.body + 1)
            var cursor = tfhd.body + 4
            val trackId = u32(data, cursor); cursor += 4
            var defaultDuration: Long? = null
            var defaultSize: Long? = null
            if (flags and 1 != 0) throw IOException("Apple 分片使用不支持的绝对数据偏移")
            if (flags and 2 != 0) cursor += 4
            if (flags and 8 != 0) { if (cursor + 4 > tfhd.end) throw badMp4(); defaultDuration = u32(data, cursor); cursor += 4 }
            if (flags and 16 != 0) { if (cursor + 4 > tfhd.end) throw badMp4(); defaultSize = u32(data, cursor); cursor += 4 }
            if (trackId != audioTrackId) continue
            if (widevine) {
                trafChildren.filter { it.type == "senc" || it.type == "saiz" || it.type == "saio" }.forEach { encryption += it.start }
                trafChildren.filter { it.type == "sgpd" || it.type == "sbgp" }.filter {
                    it.body + 8 <= it.end && type(data, it.body + 4) in setOf("seig", "seam")
                }.forEach { encryption += it.start }
                val senc = trafChildren.firstOrNull { it.type == "senc" } ?: throw IOException("这首歌的 Widevine 许可未被设备接受")
                if (u24(data, senc.body + 1) and 2 != 0) throw IOException("这首歌的 Widevine 许可未被设备接受")
                var p = senc.body + 8
                val count = u32(data, senc.body + 4).toInt()
                if (count <= 0 || count > data.size / 8) throw IOException("这首歌的 Widevine 许可未被设备接受")
                val ivBytes = senc.end - (senc.body + 8)
                val entryIvSize = when {
                    ivSize == 8 || ivSize == 16 -> ivSize
                    ivBytes == count * 8 -> 8
                    ivBytes == count * 16 -> 16
                    else -> throw IOException("这首歌的 Widevine 许可未被设备接受")
                }
                repeat(count) {
                    if (p + entryIvSize > senc.end) throw IOException("这首歌的 Widevine 许可未被设备接受")
                    ivs += ByteArray(16).also { data.copyInto(it, 16 - entryIvSize, p, p + entryIvSize) }
                    p += entryIvSize
                }
            }
            var next = moof.start.toLong()
            for (trun in trafChildren.filter { it.type == "trun" }) {
                if (trun.body + 8 > trun.end) throw badMp4()
                val f = u24(data, trun.body + 1)
                val count = u32(data, trun.body + 4)
                if (count > data.size.toLong()) throw badMp4()
                var p = trun.body + 8
                var offset = next
                if (f and 1 != 0) { if (p + 4 > trun.end) throw badMp4(); offset = moof.start.toLong() + i32(data, p); p += 4 }
                if (f and 4 != 0) p += 4
                repeat(count.toInt()) {
                    val sampleDuration = if (f and 0x100 != 0) { if (p + 4 > trun.end) throw badMp4(); u32(data, p).also { p += 4 } } else defaultDuration ?: throw badMp4()
                    val size = if (f and 0x200 != 0) { if (p + 4 > trun.end) throw badMp4(); u32(data, p).also { p += 4 } } else defaultSize ?: throw badMp4()
                    if (f and 0x400 != 0) p += 4
                    if (f and 0x800 != 0) p += 4
                    if (p > trun.end || size > Int.MAX_VALUE.toLong() || offset < 0 || offset + size > data.size.toLong() || sampleDuration > Int.MAX_VALUE.toLong()) throw badMp4()
                    val sample = LocatedSample(offset.toInt(), size.toInt(), sampleDuration.toInt(), null, all.size)
                    samples += sample; all += sample; duration = Math.addExact(duration, sampleDuration); offset += size
                }
                next = offset
                chunks += FragmentChunk(samples)
            }
        }
        if (chunks.isEmpty()) throw badMp4()
        if (widevine) {
            if (keyId == null || ivs.size != all.size) throw IOException("这首歌的 Widevine 许可未被设备接受")
            for (i in all.indices) all[i] = all[i].copy(iv = ivs[i])
            var at = 0
            for (i in chunks.indices) { val count = chunks[i].samples.size; chunks[i] = FragmentChunk(all.subList(at, at + count).toList()); at += count }
        }
        return Fragment(chunks, all, duration, encryption.distinct(), keyId)
    }

    private fun psshKid(pssh: ByteArray): ByteArray? {
        val box = boxes(pssh).firstOrNull { it.type == "pssh" } ?: return null
        if (box.body + 4 > box.end || u8(pssh, box.body) != 1) return null
        val count = u32(pssh, box.body + 20).toInt()
        if (count <= 0 || box.body + 24 + 16 > box.end) return null
        return pssh.copyOfRange(box.body + 24, box.body + 40)
    }

    private fun planMdat(chunks: List<Chunk>): MdatPlan {
        val max = 0x7fff_fff7L
        val groups = ArrayList<List<Chunk>>()
        var group = ArrayList<Chunk>(); var size = 0L
        for (chunk in chunks) {
            val chunkSize = chunk.samples.sumOf { it.size.toLong() }
            if (chunkSize > max) throw IOException("Apple 音频分片过大")
            if (group.isNotEmpty() && size + chunkSize > max) { groups += group; group = ArrayList(); size = 0 }
            group += chunk; size += chunkSize
        }
        if (group.isNotEmpty()) groups += group
        val offsets = ArrayList<Long>(); val headers = ArrayList<Long>(); var total = 0L
        for (g in groups) {
            headers += 8L
            total += 8L
            for (chunk in g) {
                offsets += total
                total = Math.addExact(total, chunk.samples.sumOf { it.size.toLong() })
            }
        }
        return MdatPlan(groups, offsets, headers, total - headers.sum())
    }

    private fun buildMoov(source: Source, chunks: List<Chunk>, samples: List<Sample>, chunkOffsets: List<Long>, co64: Boolean, duration: Long): ByteArray {
        val data = source.init
        val moovChildren = children(data, source.moov)
        val mvhd = moovChildren.firstOrNull { it.type == "mvhd" } ?: throw badMp4()
        val mvhdVersion = u8(data, mvhd.body)
        val movieTimescale = u32(data, mvhd.body + if (mvhdVersion == 1) 20 else 12)
        val movieDuration = duration * movieTimescale / source.timescale
        val trakBytes = rebuildTrak(data, source.trak, source.stsd, chunks, samples, chunkOffsets, co64, duration, movieDuration)
        val contents = ByteArrayOutputStream()
        for (child in moovChildren) when {
            child.type == "trak" && child.start == source.trak.start -> contents.write(trakBytes)
            child.type == "trak" || child.type == "mvex" || child.type == "pssh" || child.type == "udta" -> Unit
            child.type == "mvhd" -> contents.write(patchDuration(data.copyOfRange(child.start, child.end), movieDuration,
                if (mvhdVersion == 1) 32 else 24, mvhdVersion == 1))
            else -> contents.write(data, child.start, child.size)
        }
        return box("moov", contents.toByteArray())
    }

    private fun rebuildTrak(data: ByteArray, trak: Box, stsd: ByteArray, chunks: List<Chunk>, samples: List<Sample>, offsets: List<Long>, co64: Boolean, duration: Long, movieDuration: Long): ByteArray {
        val mdia = child(data, trak, "mdia") ?: throw badMp4()
        val minf = child(data, mdia, "minf") ?: throw badMp4()
        val stbl = child(data, minf, "stbl") ?: throw badMp4()
        val newStblContent = ByteArrayOutputStream()
        for (box in children(data, stbl)) when (box.type) {
            "stsd" -> newStblContent.write(stsd)
            "stts" -> newStblContent.write(tableStts(samples))
            "stsc" -> newStblContent.write(tableStsc(chunks))
            "stsz" -> newStblContent.write(tableStsz(samples))
            "stco", "co64" -> newStblContent.write(tableOffsets(offsets, co64))
        }
        val stblNew = box("stbl", newStblContent.toByteArray())
        val minfNew = replaceChild(data, minf, stbl.start, stbl.end, stblNew)
        val mdiaContent = ByteArrayOutputStream()
        for (child in children(data, mdia)) when {
            child.type == "minf" -> mdiaContent.write(minfNew)
            child.type == "mdhd" -> mdiaContent.write(patchDuration(data.copyOfRange(child.start, child.end), duration,
                if (u8(data, child.body) == 1) 32 else 24, u8(data, child.body) == 1))
            else -> mdiaContent.write(data, child.start, child.size)
        }
        val mdiaNew = box("mdia", mdiaContent.toByteArray())
        val trakContent = ByteArrayOutputStream()
        for (child in children(data, trak)) when {
            child.type == "mdia" -> trakContent.write(mdiaNew)
            child.type == "tkhd" -> trakContent.write(patchDuration(data.copyOfRange(child.start, child.end), movieDuration,
                if (u8(data, child.body) == 1) 36 else 28, u8(data, child.body) == 1))
            else -> trakContent.write(data, child.start, child.size)
        }
        return box("trak", trakContent.toByteArray())
    }


    private fun patchDuration(box: ByteArray, duration: Long, durationOffset: Int, wide: Boolean): ByteArray {
        if (box.size < durationOffset + if (wide) 8 else 4) throw badMp4()
        val result = box.copyOf()
        if (wide) put64(result, durationOffset, duration) else put32(result, durationOffset, duration.coerceAtMost(0xffff_ffffL))
        return result
    }

    private fun tableStts(samples: List<Sample>): ByteArray {
        val runs = ArrayList<Pair<Int, Int>>()
        for (sample in samples) {
            val previous = runs.lastOrNull()
            if (previous != null && previous.second == sample.duration) runs[runs.lastIndex] = previous.first + 1 to previous.second else runs += 1 to sample.duration
        }
        val body = ByteArrayOutputStream().apply { write(byteArrayOf(0, 0, 0, 0)); write(u32(runs.size.toLong())); runs.forEach { write(u32(it.first.toLong())); write(u32(it.second.toLong())) } }
        return box("stts", body.toByteArray())
    }
    private fun tableStsc(chunks: List<Chunk>): ByteArray {
        val entries = ArrayList<Triple<Int, Int, Int>>()
        for ((index, chunk) in chunks.withIndex()) {
            val count = chunk.samples.size
            if (index == 0 || chunks[index - 1].samples.size != count) entries += Triple(index + 1, count, 1)
        }
        val body = ByteArrayOutputStream().apply {
            write(ByteArray(4)); write(u32(entries.size.toLong()))
            entries.forEach { (first, count, description) -> write(u32(first.toLong())); write(u32(count.toLong())); write(u32(description.toLong())) }
        }
        return box("stsc", body.toByteArray())
    }
    private fun tableOffsets(offsets: List<Long>, co64: Boolean) = box(if (co64) "co64" else "stco", ByteArrayOutputStream().apply { write(ByteArray(4)); write(u32(offsets.size.toLong())); offsets.forEach { write(if (co64) u64(it) else u32(it)) } }.toByteArray())
    private fun tableStsz(samples: List<Sample>) = box("stsz", ByteArrayOutputStream().apply {
        write(ByteArray(4)); write(u32(0)); write(u32(samples.size.toLong())); samples.forEach { write(u32(it.size.toLong())) }
    }.toByteArray())

    private fun replaceChild(data: ByteArray, parent: Box, start: Int, end: Int, replacement: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); var cursor = parent.body
        out.write(data, cursor, start - cursor); out.write(replacement); cursor = end
        out.write(data, cursor, parent.end - cursor)
        return box(parent.type, out.toByteArray())
    }

    private fun boxes(data: ByteArray, start: Int = 0, end: Int = data.size): List<Box> {
        if (start < 0 || end < start || end > data.size) throw badMp4()
        val out = ArrayList<Box>(); var p = start
        while (p < end) {
            if (end - p < 8) throw badMp4()
            val size32 = u32(data, p); val header = if (size32 == 1L) 16 else 8
            val size = when (size32) { 0L -> (end - p).toLong(); 1L -> { if (end - p < 16) throw badMp4(); u64(data, p + 8) }; else -> size32 }
            if (size < header || size > Int.MAX_VALUE.toLong() || p.toLong() + size > end.toLong()) throw badMp4()
            out += Box(type(data, p + 4), p, p + header, p + size.toInt()); p += size.toInt()
        }
        return out
    }
    private fun children(data: ByteArray, box: Box) = boxes(data, box.body, box.end)
    private fun child(data: ByteArray, box: Box, name: String) = children(data, box).firstOrNull { it.type == name }
    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        for (part in parts) out.write(part)
        return out.toByteArray()
    }
    private fun box(type: String, payload: ByteArray): ByteArray = boxHeader(payload.size.toLong() + 8, type) + payload
    private fun boxHeader(size: Long, type: String): ByteArray { if (size !in 8..0xffff_ffffL) throw badMp4(); return u32(size) + bytes(type) }
    private fun type(data: ByteArray, at: Int) = String(data, at, 4, Charsets.ISO_8859_1)
    private fun typeBytes(data: ByteArray, at: Int, value: String) = bytes(value).copyInto(data, at + 4)
    private fun bytes(value: String) = value.toByteArray(Charsets.ISO_8859_1)
    private fun u8(data: ByteArray, at: Int) = data[at].toInt() and 255
    private fun u24(data: ByteArray, at: Int) = (u8(data, at) shl 16) or (u8(data, at + 1) shl 8) or u8(data, at + 2)
    private fun u32(data: ByteArray, at: Int) = ByteBuffer.wrap(data, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
    private fun i32(data: ByteArray, at: Int) = ByteBuffer.wrap(data, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
    private fun u64(data: ByteArray, at: Int) = ByteBuffer.wrap(data, at, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()
    private fun u64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()
    private fun put32(data: ByteArray, at: Int, value: Long) { u32(value).copyInto(data, at) }
    private fun put64(data: ByteArray, at: Int, value: Long) { u64(value).copyInto(data, at) }
    private fun badMp4() = IOException("Apple 音频分片格式无效")
}
