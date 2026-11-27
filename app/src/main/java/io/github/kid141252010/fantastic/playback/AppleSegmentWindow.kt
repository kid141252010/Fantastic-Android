package io.github.kid141252010.fantastic.playback

import android.os.SystemClock
import android.util.Log
import io.github.kid141252010.fantastic.BuildConfig
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Serial consumer, bounded IO producers; the caller supplies its existing supervisor scope. */
internal class AppleSegmentWindow(
    private val decrypt: AppleNativeDecrypt,
    private val track: AppleResolvedTrack,
    private val scope: CoroutineScope,
    private val priority: AppleSegmentPriority,
    private val legacyLookahead: Int = 1,
) {
    private class Pending(val index: Int, val length: Long, val result: Deferred<ByteArray>)

    private val queued = ArrayDeque<Pending>()
    private var queuedBytes = 0L
    private var held: ByteArray? = null
    private var heldIndex = -1
    private var heldBytes = 0L
    private var next = 0
    private var urgentIndex = -1
    private var closed = false

    init { require(legacyLookahead in 1..AUDIO_LANES) }

    suspend fun read(index: Int): ByteArray {
        val started = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        try {
            currentCoroutineContext().ensureActive()
            scope.coroutineContext.ensureActive()
            check(!closed) { "Apple segment window is closed" }
            if (index !in track.media.segments.indices) throw IOException("Apple 分片获取失败：HTTP 416")
            decrypt.segmentLookahead(track, legacyLookahead) // Failed resources cannot return even a held fragment.
            if (index == heldIndex) return checkNotNull(held)
            if (heldIndex >= 0 && index != heldIndex + 1 || queued.isNotEmpty() && queued.first.index != index) reset()
            held = null
            heldIndex = -1
            heldBytes = 0L
            if (queued.isEmpty() && priority == AppleSegmentPriority.Playback) {
                // The initial/seek target never waits for the speculative window to fill.
                val bytes = withContext(Dispatchers.IO) { decrypt.fetchSegment(track, index, AppleSegmentPriority.Playback) }
                hold(index, bytes)
                next = index + 1
                fill()
                return bytes
            }
            if (queued.isEmpty()) {
                next = index
                fill()
            }
            val pending = queued.first
            val bytes = pending.result.await()
            queued.removeFirst()
            queuedBytes -= pending.length
            setUrgentSegment(queued.peekFirst()?.index ?: -1)
            hold(index, bytes)
            // Replenish before parsing/muxing so that one writer overlaps network work.
            fill()
            return bytes
        } catch (error: Throwable) {
            setUrgentSegment(-1)
            throw error
        } finally {
            if (BuildConfig.DEBUG) {
                Log.d("FantasticPlayback", "apple-window role=$priority index=$index queued=${queued.size} " +
                    "heldBytes=$heldBytes queuedBytes=$queuedBytes waitMs=${SystemClock.elapsedRealtime() - started} " +
                    "range=${decrypt.segmentRangeMode(track)}")
            }
        }
    }

    private fun hold(index: Int, bytes: ByteArray) {
        held = bytes
        heldIndex = index
        heldBytes = declaredLength(index)
    }

    private fun setUrgentSegment(index: Int) {
        if (priority != AppleSegmentPriority.Playback || index == urgentIndex) return
        if (urgentIndex >= 0) decrypt.releaseSegmentPriority(track, urgentIndex)
        urgentIndex = index
        if (index >= 0) decrypt.prioritizeSegment(track, index)
    }

    private suspend fun fill() {
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        // The legacy resolved exporter had three slots INCLUDING the muxed fragment.
        // Generic serial export keeps one next fragment to overlap its single writer.
        val legacy = if (priority == AppleSegmentPriority.Background && held != null) {
            maxOf(1, legacyLookahead - 1)
        } else legacyLookahead
        val limit = decrypt.segmentLookahead(track, legacy)
        setUrgentSegment(queued.peekFirst()?.index ?: -1)
        while (next < track.media.segments.size && queued.size < limit) {
            currentCoroutineContext().ensureActive()
            scope.coroutineContext.ensureActive()
            val length = declaredLength(next)
            val occupied = if (priority == AppleSegmentPriority.Playback) queuedBytes else Math.addExact(heldBytes, queuedBytes)
            // An oversize fragment may occupy an otherwise empty budget alone.
            if (occupied > 0L && (length > APPLE_ALAC_SEGMENT_BUFFER_BYTES ||
                    occupied > APPLE_ALAC_SEGMENT_BUFFER_BYTES - length)) break
            val index = next++
            // Register the nearest successor before its producer can reach admission.
            if (queued.isEmpty()) setUrgentSegment(index)
            queuedBytes = Math.addExact(queuedBytes, length)
            queued.addLast(Pending(index, length, scope.async(Dispatchers.IO) {
                decrypt.fetchSegment(track, index, AppleSegmentPriority.Background)
            }))
        }
    }

    private fun declaredLength(index: Int): Long {
        val length = track.media.segments[index].range.length
        if (length <= 0L) throw IOException("Apple 分片获取失败：HTTP 416")
        return length
    }

    suspend fun reset() {
        withContext(NonCancellable) {
            setUrgentSegment(-1)
            for (pending in queued) pending.result.cancel()
            try {
                for (pending in queued) pending.result.join()
            } finally {
                queued.clear()
                queuedBytes = 0L
                held = null
                heldIndex = -1
                heldBytes = 0L
                next = 0
            }
        }
    }

    suspend fun close() {
        closed = true
        reset()
    }
}
