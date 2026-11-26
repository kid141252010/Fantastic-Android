package io.github.kid141252010.fantastic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface AppleAlacReader {
    val cookie: ByteArray
    val durationMs: Long
    val maxBlock: Int
    /** Returns the actual first decodable packet time, not the requested time (microseconds). */
    suspend fun seekTo(positionMs: Long): Long
    /** The packet remains valid until the next read. */
    suspend fun readPacket(): ByteArray?
    suspend fun close()
}

internal class AppleAlacCacheException(message: String, cause: Throwable? = null) : IOException(message, cause)

@UnstableApi
internal class AppleAlacStreamReader(
    private val source: AppleResolvedSource,
    scope: CoroutineScope,
) : AppleAlacReader {
    private val track = source.track
    override val cookie = track.alacCookie ?: throw IOException("该曲目没有所选音质")
    override val maxBlock = track.alacConfig?.maxBlock ?: throw IOException("ALAC 配置无效")
    override val durationMs = track.media.durationMs
    private var index = -1
    private var fragment: ByteArray? = null
    private var ranges = emptyList<AppleSampleRange>()
    private var packetIndex = 0
    private val window = AppleSegmentWindow(source.decrypt, track, scope, AppleSegmentPriority.Playback)

    override suspend fun seekTo(positionMs: Long): Long {
        fragment = null
        ranges = emptyList()
        packetIndex = 0
        index = -1
        window.reset()
        if (track.media.segments.isEmpty()) throw IOException("ALAC 播放列表为空")
        index = track.media.segments.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
        install(window.read(index))
        return Math.multiplyExact(track.media.segments[index].startMs, 1000L)
    }

    override suspend fun readPacket(): ByteArray? {
        currentCoroutineContext().ensureActive()
        if (packetIndex == ranges.size) {
            val next = index + 1
            if (index < 0 || next >= track.media.segments.size) return null
            fragment = null
            ranges = emptyList()
            packetIndex = 0
            val raw = window.read(next)
            index = next
            install(raw)
        }
        val range = ranges[packetIndex++]
        return checkNotNull(fragment).copyOfRange(range.start, range.end)
    }

    private suspend fun install(raw: ByteArray) {
        ranges = withContext(Dispatchers.Default) { source.decrypt.sampleRanges(raw) }
        if (ranges.isEmpty()) throw IOException("ALAC 音频分片为空")
        fragment = raw
        packetIndex = 0
    }

    override suspend fun close() {
        fragment = null
        ranges = emptyList()
        packetIndex = 0
        index = -1
        window.close()
    }
}

/** Media3 owns the MP4 sample tables; only the current compressed packet is buffered here. */
@UnstableApi
internal class AppleAlacFileReader private constructor(
    private val file: File,
    private val lease: PlaybackCacheLease?,
) : AppleAlacReader {
    private val dataSource = FileDataSource()
    private val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)
    private val seekPosition = PositionHolder()
    private lateinit var input: DefaultExtractorInput
    private var length = 0L
    private var closed = false
    private var tracksEnded = false
    private var seekMap: SeekMap? = null
    private var audioTrackId = C.INDEX_UNSET
    private var packets: PacketOutput? = null
    override lateinit var cookie: ByteArray
        private set
    override var durationMs = 0L
        private set
    override var maxBlock = 0
        private set

    private fun invalid(): Nothing = throw AppleAlacCacheException("ALAC 缓存格式无效")

    private suspend fun initialize() {
        length = file.length()
        if (length <= 0) invalid()
        reposition(0L)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                if (type == C.TRACK_TYPE_AUDIO) PacketOutput(id) else DummyTrackOutput()
            override fun endTracks() { tracksEnded = true }
            override fun seekMap(map: SeekMap) { seekMap = map }
        })
        while (!tracksEnded || seekMap == null) {
            currentCoroutineContext().ensureActive()
            when (extractor.read(input, seekPosition)) {
                Extractor.RESULT_SEEK -> reposition(seekPosition.position)
                Extractor.RESULT_END_OF_INPUT -> invalid()
            }
        }
        val format = packets?.format ?: invalid()
        if (format.drmInitData != null) invalid()
        cookie = format.initializationData.singleOrNull() ?: invalid()
        if (cookie.size != 24 && cookie.size != 48) invalid()
        val config = ByteBuffer.wrap(cookie).order(ByteOrder.BIG_ENDIAN)
        val frames = config.int.toLong() and 0xffff_ffffL
        val version = cookie[4].toInt() and 255
        val bits = cookie[5].toInt() and 255
        val channels = cookie[9].toInt() and 255
        val rate = config.getInt(20).toLong() and 0xffff_ffffL
        if (frames !in 1L..(1L shl 20) || version != 0 || bits !in setOf(16, 20, 24, 32) ||
            channels !in 1..8 || rate !in 1L..Int.MAX_VALUE.toLong()) invalid()
        if (format.sampleRate != Format.NO_VALUE && format.sampleRate != rate.toInt() ||
            format.channelCount != Format.NO_VALUE && format.channelCount != channels) invalid()
        maxBlock = frames.toInt()
        durationMs = (seekMap?.durationUs ?: C.TIME_UNSET) / 1000L
        if (durationMs <= 0 || seekMap?.isSeekable != true) invalid()
        packets?.discard()
    }

    override suspend fun seekTo(positionMs: Long): Long = reading {
        val point = extractor.getSeekPoints(Math.multiplyExact(positionMs.coerceAtLeast(0L), 1000L), audioTrackId).first
        if (point.position < 0 || point.position >= length || point.timeUs < 0) invalid()
        packets?.discard()
        extractor.seek(point.position, point.timeUs)
        reposition(point.position)
        point.timeUs
    }

    override suspend fun readPacket(): ByteArray? = reading {
        val target = packets ?: invalid()
        target.ready = false
        while (!target.ready) {
            currentCoroutineContext().ensureActive()
            when (extractor.read(input, seekPosition)) {
                Extractor.RESULT_SEEK -> reposition(seekPosition.position)
                Extractor.RESULT_END_OF_INPUT -> {
                    if (target.received != 0) invalid()
                    return@reading null
                }
            }
        }
        target.takePacket()
    }

    private fun reposition(position: Long) {
        if (position < 0 || position > length) invalid()
        dataSource.close()
        dataSource.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(position).build())
        input = DefaultExtractorInput(dataSource, position, length)
    }

    private suspend fun <T> reading(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            if (closed) throw IOException("reader closed")
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AppleAlacCacheException) {
            throw error
        } catch (error: Exception) {
            throw AppleAlacCacheException("ALAC 缓存读取失败", error)
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (!closed) {
            closed = true
            try {
                try { dataSource.close() }
                finally {
                    try { extractor.release() }
                    finally { lease?.close() }
                }
            } catch (error: Exception) {
                throw AppleAlacCacheException("ALAC 缓存读取失败", error)
            }
        }
    }

    private inner class PacketOutput(private val id: Int) : TrackOutput {
        private val ignored = DummyTrackOutput()
        var format: Format? = null
            private set
        private var selected = false
        private var buffer = ByteArray(0)
        var received = 0
            private set
        var ready = false
        private var timeUs = C.TIME_UNSET

        override fun format(format: Format) {
            if (format.sampleMimeType != MimeTypes.AUDIO_ALAC) return
            if (packets != null && packets !== this) invalid()
            selected = true
            this.format = format
            packets = this
            audioTrackId = id
        }

        private fun prepare(length: Int, part: Int) {
            if (part != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0 || ready) invalid()
            if (received == 0 && buffer.size != length) buffer = ByteArray(length)
            if (length > buffer.size - received) invalid()
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!selected) return ignored.sampleData(input, length, allowEndOfInput, sampleDataPart)
            prepare(length, sampleDataPart)
            val count = input.read(buffer, received, length)
            if (count == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw EOFException()
                return count
            }
            received += count
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!selected) { ignored.sampleData(data, length, sampleDataPart); return }
            prepare(length, sampleDataPart)
            data.readBytes(buffer, received, length)
            received += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!selected) return
            if (offset != 0 || cryptoData != null || flags and C.BUFFER_FLAG_ENCRYPTED != 0 ||
                size <= 0 || size != received || size != buffer.size || timeUs < 0 || ready) invalid()
            this.timeUs = timeUs
            ready = true
        }

        fun takePacket(): ByteArray {
            if (!ready || timeUs < 0) invalid()
            received = 0
            ready = false
            return buffer
        }

        fun discard() {
            received = 0
            ready = false
            timeUs = C.TIME_UNSET
        }
    }

    companion object {
        suspend fun open(file: File, lease: PlaybackCacheLease? = null): AppleAlacFileReader {
            var reader: AppleAlacFileReader? = null
            try {
                return withContext(Dispatchers.IO) {
                    AppleAlacFileReader(file, lease).also { reader = it; it.initialize() }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { reader?.close() ?: lease?.close() }
                    catch (closeError: Exception) { error.addSuppressed(closeError) }
                }
                if (error is CancellationException) throw error
                if (error is AppleAlacCacheException) throw error
                if (error !is Exception) throw error
                throw AppleAlacCacheException("ALAC 缓存格式无效", error)
            }
        }
    }
}
