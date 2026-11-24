package io.github.kid141252010.fantastic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.kid141252010.fantastic.data.NeteaseApi
import io.github.kid141252010.fantastic.data.NeteaseAudio
import io.github.kid141252010.fantastic.data.Source
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Media3 owns seek and decode. Each open owns only a bounded window of upcoming bytes. */
@UnstableApi
internal class NativeAudioDataSource(
    private val http: NativeAudioHttp,
    private val netease: NeteaseApi,
    private val requests: NativeAudioRequests,
) : BaseDataSource(true) {
    private data class Version(val address: AudioAddress, val size: Long, val etag: String?, val audio: NeteaseAudio?)
    private class Block(val bytes: ByteArray, val count: Int = bytes.size)

    private var request: NativeAudioRequests.Request? = null
    private var version: Version? = null
    private var sequentialOnly = false
    private var refreshNext = false
    private var refreshed = false
    private var uri: Uri? = null
    private var mime: String? = null
    private var parts: Parts? = null
    private var block: Block? = null
    private var offset = 0
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        check(uri == null)
        val next = dataSpec.key?.let(requests::get)?.takeIf { it.uri == dataSpec.uri }
            ?: throw IOException("找不到当前曲目的音源请求")
        if (request !== next) {
            request = next
            version = null
            sequentialOnly = false
            refreshNext = false
            refreshed = false
        }
        transferInitializing(dataSpec)
        try {
            onLoader {
                val wire = http.transfer(next.song, downloading = false)
                val force = refreshNext && !refreshed
                if (force) {
                    refreshNext = false
                    refreshed = true
                }
                try {
                    openTrack(dataSpec, next, wire, force)
                } catch (error: AudioHttpFailure) {
                    if (!error.shouldResolveAgain || refreshed) throw error
                    refreshed = true
                    wire.note("resolve again after CDN HTTP ${error.status}")
                    openTrack(dataSpec, next, wire, force = true)
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
        // Never expose a signed CDN URI to Media3 diagnostics.
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return dataSpec.length.takeIf { it >= 0 } ?: remaining
    }

    private suspend fun openTrack(
        spec: DataSpec, request: NativeAudioRequests.Request, wire: NativeAudioHttp.Transfer, force: Boolean,
    ) {
        val prior = version
        val cached = prior?.takeUnless { force }
        val song = request.song
        val audio = cached?.audio ?: if (song.source == Source.Netease) netease.resolveAudio(song) else null
        val address = cached?.address ?: audio?.cdnAddress() ?: song.route(AudioStage.Play)
        val position = spec.position
        if (position == Long.MAX_VALUE) throw outOfRange()
        val end = position + minOf(spec.length.takeIf { it >= 0 } ?: Long.MAX_VALUE, Long.MAX_VALUE - position)
        val first = AudioRange(position, minOf(end, position + minOf(FIRST_BYTES.toLong(), Long.MAX_VALUE - position)) - 1)
        val range = if (!sequentialOnly) first else if (position > 0 || spec.length >= 0) {
            AudioRange(position, (end - 1).takeIf { spec.length >= 0 })
        } else null
        val received = try {
            wire.open(address, range)
        } catch (error: AudioHttpFailure) {
            when {
                error.status == 416 && error.total != null && range != null -> {
                    if (prior != null && prior.size >= 0 && prior.size != error.total) throw AudioChanged()
                    if (position != error.total) throw outOfRange()
                    remaining = 0
                    return
                }
                range != null && error.rangeUnsupported -> {
                    sequentialOnly = true
                    openSequential(wire.open(address), null, position, end, audio)
                    request.audio.set(audio)
                    return
                }
                else -> throw error
            }
        }
        if (received.status == 200 || sequentialOnly) {
            sequentialOnly = true
            openSequential(received, range, position, end, audio)
            request.audio.set(audio)
            return
        }
        var exhausted = false
        try {
            val total = received.totalFor(first)
            val stop = minOf(end, total)
            val count = (minOf(first.last!! + 1, stop) - position).toInt()
            val candidate = accept(received, total, audio)
            if (position + count < stop && candidate.etag == null) {
                sequentialOnly = true
            } else {
                val ahead = candidate.etag?.takeIf { position + count < stop }?.let {
                    Ranges(wire, AudioObject(candidate.address, total, it), position + count, stop)
                }
                try {
                    ahead?.fill(AUDIO_LANES - 1)
                    val bodyBytes = if (received.length >= 0) received.length else first.length
                    exhausted = count.toLong() == bodyBytes
                    block = Block(received.readExact(count, exhausted))
                    remaining = stop - position
                    parts = ahead
                    mime = received.mime
                    version = candidate
                    request.audio.set(audio)
                    return
                } finally {
                    if (parts !== ahead) ahead?.close()
                }
            }
        } catch (error: AudioRangeRejected) {
            sequentialOnly = true
        } finally {
            // Also abandon an unconsumed or partial probe on fallback, cancellation or mismatch.
            if (!exhausted) received.cancel()
            received.close()
        }
        val rest = if (position == 0L && spec.length < 0) null else AudioRange(position, (end - 1).takeIf { spec.length >= 0 })
        val full = try {
            wire.open(received.address, rest)
        } catch (error: AudioHttpFailure) {
            if (rest == null || !error.rangeUnsupported) throw error
            openSequential(wire.open(received.address), null, position, end, audio)
            request.audio.set(audio)
            return
        }
        openSequential(full, rest, position, end, audio)
        request.audio.set(audio)
    }

    private fun openSequential(body: AudioBody, range: AudioRange?, position: Long, end: Long, audio: NeteaseAudio?) {
        try {
            val total = if (body.status == 206) body.totalFor(range ?: throw AudioRangeRejected()) else body.length
            if (total >= 0 && position > total) throw outOfRange()
            val candidate = accept(body, total, audio)
            remaining = if (total < 0 && end == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else {
                minOf(total.takeIf { it >= 0 } ?: Long.MAX_VALUE, end) - position
            }
            mime = body.mime
            if (remaining != 0L) parts = Sequential(body, if (body.status == 200) position else 0, remaining)
            else {
                body.cancel()
                body.close()
            }
            version = candidate
        } catch (error: IOException) {
            body.cancel()
            body.close()
            throw error
        }
    }

    private fun accept(body: AudioBody, total: Long, audio: NeteaseAudio?): Version {
        val old = version
        if (old != null) {
            val previous = old.address.url
            val current = body.address.url
            val sameResource = previous.scheme == current.scheme && previous.host == current.host &&
                previous.port == current.port && previous.encodedPath == current.encodedPath
            val sameSize = old.size >= 0 && total >= 0 && old.size == total
            val changed = when {
                old.audio?.quality != audio?.quality -> true
                old.size >= 0 && total >= 0 && old.size != total -> true
                old.etag != null || body.etag != null -> !sameResource || old.etag != body.etag
                sameSize && sameResource -> false
                else -> previous != current
            }
            if (changed) throw AudioChanged()
        }
        val expected = audio?.sizeBytes?.takeIf { it > 0 }
        if (expected != null && total >= 0 && expected != total) throw IOException("网易云音源长度与播放入口不一致")
        return Version(body.address, total, body.etag, audio)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val current = block?.takeIf { this.offset < it.count } ?: run {
            val result = try {
                parts?.let { onLoader { it.next() } }
            } catch (error: IOException) {
                if (error is AudioHttpFailure && error.shouldResolveAgain && !refreshed) refreshNext = true
                if (error is AudioRangeRejected) sequentialOnly = true
                throw error
            }
            if (result == null) {
                if (remaining > 0) throw EOFException("音源提前结束")
                return C.RESULT_END_OF_INPUT
            }
            this.offset = 0
            block = result
            result
        }
        val available = minOf(length, current.count - this.offset)
        val count = if (remaining < 0) available else minOf(remaining, available.toLong()).toInt()
        current.bytes.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        if (remaining >= 0) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = mime?.let { type ->
        TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER).apply { put("Content-Type", listOf(type)) }
    }.orEmpty()

    override fun close() {
        parts?.close()
        parts = null
        block = null
        offset = 0
        remaining = 0
        mime = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    private interface Parts {
        suspend fun next(): Block?
        fun close()
    }

    private class Ranges(
        private val wire: NativeAudioHttp.Transfer, private val audio: AudioObject, private var position: Long, private val end: Long,
    ) : Parts {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val upcoming = ArrayDeque<Deferred<Block>>(AUDIO_LANES)

        fun fill(lanes: Int = AUDIO_LANES) {
            while (upcoming.size < lanes && position < end) {
                val count = minOf(end - position, RANGE_BYTES.toLong()).toInt()
                val range = AudioRange(position, position + count - 1)
                position += count
                upcoming.addLast(scope.async { Block(wire.openRange(audio, range).use { it.readExact(count) }) })
            }
        }

        override suspend fun next(): Block? {
            fill()
            val ahead = upcoming.firstOrNull() ?: return null
            val bytes = ahead.await()
            upcoming.removeFirst()
            fill()
            return bytes
        }

        override fun close() {
            scope.cancel()
            upcoming.clear()
        }
    }

    private class Sequential(body: AudioBody, skip: Long, length: Long) : Parts {
        private val channel = Channel<Block>(1)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    body.use {
                        it.read { input, owner ->
                            var discard = skip
                            val stage = it.address.stage
                            if (discard > 0) {
                                val scratch = ByteArray(32 * 1024)
                                while (discard > 0) {
                                    owner.ensureActive()
                                    val read = input.readAudio(scratch, 0, minOf(discard, scratch.size.toLong()).toInt(), stage)
                                    if (read <= 0) throw outOfRange()
                                    discard -= read
                                }
                            }
                            var left = length
                            var first = true
                            while (left != 0L) {
                                val size = minOf(if (left < 0) Long.MAX_VALUE else left, if (first) 16_384 else FIRST_BYTES.toLong()).toInt()
                                val bytes = ByteArray(size)
                                var offset = 0
                                do {
                                    owner.ensureActive()
                                    val read = input.readAudio(bytes, offset, size - offset, stage)
                                    if (read <= 0) break
                                    offset += read
                                } while (!first && offset < size)
                                if (offset == 0) break
                                channel.send(Block(bytes, offset))
                                if (left >= 0) left -= offset
                                first = false
                            }
                            if (left > 0) throw EOFException("音源提前结束")
                            if (left == 0L) it.cancel() // The DataSpec can end before the origin body does.
                        }
                    }
                    channel.close()
                } catch (error: IOException) {
                    channel.close(error)
                } finally {
                    channel.close()
                }
            }
        }

        override suspend fun next(): Block? = channel.receiveCatching().let {
            it.exceptionOrNull()?.let { error -> throw error }
            it.getOrNull()
        }

        override fun close() {
            scope.cancel()
            channel.cancel()
        }
    }

    private companion object {
        const val FIRST_BYTES = 128 * 1024
        const val RANGE_BYTES = 512 * 1024

        fun outOfRange() = DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)

        fun <T> onLoader(block: suspend CoroutineScope.() -> T): T = try {
            runBlocking(block = block)
        } catch (error: InterruptedException) {
            throw InterruptedIOException("已取消音源读取").apply { initCause(error) }
        }
    }
}
