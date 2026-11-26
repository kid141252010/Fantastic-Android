package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.NeteaseApi
import io.github.kid141252010.fantastic.data.NeteasePlayFailure
import io.github.kid141252010.fantastic.data.NeteaseAudio
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import java.io.File
import java.io.IOException
import java.io.EOFException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class FullAudioRequest(
    val track: Track,
    val server: String,
    val storefront: String,
    val quality: String,
    val maxBytes: Long,
    val appleGroup: String? = null,
    val appleCodecs: String? = null,
    val resolvedNetease: NeteaseAudio? = null,
    val strict: Boolean = false,
    val expectedDurationMs: Long = 0,
    val requestedNeteaseQuality: String? = null,
    val resolvedApple: AppleResolvedSource? = null,
)

internal data class AudioFileInfo(
    val sizeBytes: Long,
    val durationMs: Long?,
    val mime: String?,
    val sampleRate: Int?,
    val quality: String,
)

/** Transfers one complete source file into the caller-owned temporary file. */
internal class AudioFileTransfer(
    private val context: Context,
    private val api: FantasticApi,
    private val http: NativeAudioHttp,
    private val netease: NeteaseApi,
) {

    suspend fun transfer(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo {
        require(request.maxBytes > 0) { "缓存上限无效" }
        withContext(Dispatchers.IO) { target.parentFile?.mkdirs() }
        val duration = maxOf(request.track.durationMs, request.expectedDurationMs)
        if (request.track.source == Source.Apple) {
            return AppleNativeExporter(context, api).export(request, target, reserve, progress)
        }
        val qqPlayFallback = transferNative(request, target, reserve, progress, duration)
        return withContext(Dispatchers.IO) { verifyAudio(target, duration, request.strict || qqPlayFallback, request.quality) }
    }

    /** Returns true only for QQ's same-rendition fallback to the playback resolver. */
    private suspend fun transferNative(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
        duration: Long,
    ): Boolean {
        val song = NativeSong(request.track.source, request.track.id, request.quality, request.server)
        val wire = http.transfer(song, downloading = true)
        var stage = AudioStage.Download
        var resolved = request.resolvedNetease
        var legacyNetease = false
        var sequentialOnly = false
        var refreshed = false
        var retried = false
        while (true) {
            try {
                if (song.source == Source.Netease && !legacyNetease && resolved == null) {
                    resolved = netease.resolveAudio(song.copy(quality = request.requestedNeteaseQuality ?: song.quality))
                }
                val audio = resolved
                if (audio != null && audio.quality != song.quality) throw IOException("网易云未返回所选音质，未保存文件")
                val address = audio?.cdnAddress() ?: song.route(stage)
                wire.save(address, audio?.sizeBytes?.takeIf { it > 0 }, request.maxBytes, target, reserve, progress, sequentialOnly)
                return stage == AudioStage.Play
            } catch (error: IOException) {
                when {
                    song.source == Source.QQ && stage == AudioStage.Download && error is AudioHttpFailure &&
                        error.stage == AudioStage.Download && error.status == 502 -> {
                        if (duration <= 0) throw IOException("QQ 下载入口 HTTP 502，缺少曲目时长，无法确认备用音源为整曲", error)
                        wire.note("download HTTP 502 -> same-quality play")
                        stage = AudioStage.Play
                    }
                    song.source == Source.Netease && !legacyNetease && request.resolvedNetease == null &&
                        error is NeteasePlayFailure && (error.status == 404 || error.status == 405) -> {
                        legacyNetease = true
                    }
                    error is AudioHttpFailure && error.shouldResolveAgain && !refreshed -> {
                        wire.note("resolve again after CDN HTTP ${error.status}")
                        refreshed = true
                        resolved = null
                    }
                    !sequentialOnly && (error is AudioRangeRejected || error is AudioChanged) -> {
                        sequentialOnly = true
                        if (error is AudioChanged) resolved = null
                        wire.note("restart with one complete stream")
                    }
                    !retried && error.audioRetryable() -> {
                        retried = true
                        delay(350L)
                    }
                    stage == AudioStage.Play -> throw IOException("QQ 下载入口 HTTP 502；${error.message}", error)
                    else -> throw error
                }
                progress(0)
            }
        }
    }

    private suspend fun NativeAudioHttp.Transfer.save(
        address: AudioAddress,
        expected: Long?,
        maxBytes: Long,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
        sequentialOnly: Boolean,
    ) {
        val probe = AudioRange(0, 0)
        if (sequentialOnly) {
            background { open(address).use { it.saveFull(expected, maxBytes, target, reserve, progress) } }
            return
        }
        var endpoint = address
        var size = 0L
        val audio = background {
            val response = try { open(address, probe) }
            catch (error: AudioHttpFailure) { if (error.rangeUnsupported) throw AudioRangeRejected(); throw error }
            response.use {
                if (it.status == 200) {
                    it.saveFull(expected, maxBytes, target, reserve, progress)
                    return@background null
                }
                endpoint = it.address
                size = it.totalFor(probe)
                checkSize(size, maxBytes, expected)
                it.readExact(1)
                it.etag?.let { tag -> AudioObject(endpoint, size, tag) }
            }
        }
        // A 200 response to the probe was already consumed as one complete file.
        if (size == 0L) return
        val lanes = minOf(AUDIO_LANES.toLong(), size / MIN_DOWNLOAD_PART).toInt()
        if (audio == null || lanes < 2) {
            background { open(endpoint).use { it.saveFull(size, maxBytes, target, reserve, progress) } }
        } else {
            saveParallel(audio, lanes, target, reserve, progress)
        }
    }

    private suspend fun NativeAudioHttp.Transfer.saveParallel(
        audio: AudioObject,
        lanes: Int,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ) = coroutineScope {
        withContext(Dispatchers.IO) {
            RandomAccessFile(target, "rw").use { file ->
                file.setLength(0)
                reserve(audio.size)
                file.setLength(audio.size)
            }
        }
        val reporter = ByteProgress(audio.size, progress)
        val piece = audio.size / lanes
        (0 until lanes).map { lane ->
            async {
                val first = piece * lane
                val last = if (lane == lanes - 1) audio.size - 1 else first + piece - 1
                val range = AudioRange(first, last)
                background {
                    openRange(audio, range).use { part ->
                        part.copyTo(target, first, range.length, range.length, null, reporter::advance)
                    }
                }
            }
        }.awaitAll()
        if (reporter.writtenBytes != audio.size) throw IOException("下载分段未写满")
    }

    private suspend fun AudioBody.saveFull(
        expected: Long?, maxBytes: Long, target: File, reserve: (Long) -> Unit, progress: (Int?) -> Unit,
    ) {
        if (status != 200) throw IOException("音源未返回完整文件（HTTP $status）")
        checkSize(length, maxBytes, expected)
        val exact = expected ?: length.takeIf { it >= 0 }
        val reporter = exact?.takeIf { it > 0 }?.let { ByteProgress(it, progress) }
        if (reporter == null) progress(null)
        copyTo(target, 0, maxBytes, exact, reserve) { reporter?.advance(it) }
    }

    private suspend fun AudioBody.copyTo(
        target: File, offset: Long, maxBytes: Long, expected: Long?, reserve: ((Long) -> Unit)?, onBytes: (Int) -> Unit,
    ) = read { input, owner ->
        RandomAccessFile(target, "rw").use { file ->
            if (reserve != null) {
                file.setLength(0)
                if (expected != null && expected > 0) reserve(expected)
            }
            file.seek(offset)
            val buffer = ByteArray(64 * 1024)
            val limit = expected ?: maxBytes
            var written = 0L
            while (written < limit) {
                owner.ensureActive()
                val count = input.readAudio(buffer, 0, minOf(limit - written, buffer.size.toLong()).toInt(), address.stage)
                if (count < 0) break
                written += count
                if (expected == null) reserve?.invoke(written)
                file.write(buffer, 0, count)
                onBytes(count)
            }
            if (written <= 0 || expected != null && written != expected) {
                throw AudioReadFailure(address.stage, EOFException("下载文件不完整"))
            }
            owner.ensureActive()
            if (written == limit && input.readAudio(buffer, 0, 1, address.stage) != -1) {
                throw IOException("下载文件超出预期长度或上限")
            }
        }
    }

    private fun checkSize(length: Long, maxBytes: Long, expected: Long?) {
        if (length > maxBytes || expected != null && expected > maxBytes) throw IOException("下载文件过大")
        if (expected != null && length >= 0 && length != expected) throw IOException("下载文件长度不一致")
    }

    private class ByteProgress(private val size: Long, private val report: (Int?) -> Unit) {
        private var written = 0L
        private var previous = -1

        @Synchronized fun advance(count: Int) {
            written += count
            val percent = (written * 100 / size).toInt().coerceAtMost(99)
            if (percent == previous) return
            previous = percent
            report(percent)
        }
        val writtenBytes get() = synchronized(this) { written }
    }

    private fun verifyAudio(file: File, expectedDurationMs: Long, strict: Boolean, quality: String): AudioFileInfo {
        val extractor = MediaExtractor()
        val format = try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).asSequence().map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (error: Exception) {
            null
        } finally {
            extractor.release()
        }
        if (format == null) {
            if (strict || !looksLikeAudio(file)) throw IOException("下载文件不是可识别的音频")
            return AudioFileInfo(file.length(), null, null, null, quality)
        }
        val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1_000L)
        } else null
        if (strict && (durationMs == null || durationMs <= 0)) throw IOException("下载文件没有完整时长")
        if (expectedDurationMs > 10_000 && durationMs != null && durationMs + 5_000 < expectedDurationMs) {
            throw IOException("音源仅返回试听片段，未保存为完整歌曲")
        }
        val mime = format.getString(MediaFormat.KEY_MIME)
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
        } else null
        return AudioFileInfo(file.length(), durationMs, mime, sampleRate, quality)
    }

    private fun looksLikeAudio(file: File): Boolean {
        val header = ByteArray(16)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            if (read < 4) return false
        }
        fun ascii(offset: Int, value: String) = offset >= 0 && offset + value.length <= header.size &&
            header.copyOfRange(offset, offset + value.length).contentEquals(value.toByteArray())
        val sync = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
        return ascii(0, "fLaC") || ascii(0, "OggS") || ascii(0, "ID3") || ascii(4, "ftyp") ||
            sync == 0x0b77 || sync == 0xac40 || sync == 0xac41 || ascii(0, "RIFF") ||
            ascii(0, "FORM") || sync == 0x7ffe || sync == 0xfe7f
    }

    private companion object { const val MIN_DOWNLOAD_PART = 256L * 1024 }
}
