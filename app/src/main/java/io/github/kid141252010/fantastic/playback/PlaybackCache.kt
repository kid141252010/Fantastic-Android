package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.util.AtomicFile
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import io.github.kid141252010.fantastic.BuildConfig
import io.github.kid141252010.fantastic.data.NeteaseAudio
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.PlaybackCacheLimit
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class PlaybackRendition(
    val track: Track,
    val occurrence: Int,
    val requestedQuality: String,
    val actualQuality: String,
    val durationMs: Long,
    val format: Format?,
    val requestId: String?,
    val appleGroup: String? = null,
    val appleCodecs: String? = null,
    val appleProfile: ApplePlaybackProfile? = null,
    val appleMode: String? = null,
    val resolvedNetease: NeteaseAudio? = null,
    val neteaseRequestQuality: String? = null,
    val resolvedApple: AppleResolvedSource? = null,
    val key: PlaybackCacheKey,
)

@Serializable
internal data class PlaybackCacheKey(val server: String, val storefront: String, val source: Source, val trackId: String) {
    companion object {
        fun from(server: String, storefront: String, source: Source, id: String): PlaybackCacheKey? {
            val base = SourceStatus.normalizeBaseUrl(server) ?: return null
            if (!source.validSongId(id)) return null
            val region = if (source == Source.Apple) {
                storefront.lowercase().takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } } ?: return null
            } else ""
            return PlaybackCacheKey(base, region, source, id)
        }
    }
}

@Serializable
internal data class PlaybackCacheEntry(
    val key: PlaybackCacheKey,
    val fileId: String,
    val sizeBytes: Long,
    val lastUsed: Long,
    val durationMs: Long,
    val mime: String,
    val sampleRate: Int?,
    val requestedQuality: String,
    val actualQuality: String,
    val appleGroup: String? = null,
    val appleMode: String? = null,
    val appleNetwork: String? = null,
    val appleSpatial: String? = null,
    val appleExplicitQuality: String? = null,
    val appleProfileQuality: String? = null,
)

internal val PlaybackCacheEntry.isCompleteAppleAlac: Boolean
    get() = key.source == Source.Apple && actualQuality == "alac" && appleMode == "alac" &&
        appleGroup?.contains("alac", true) == true

internal class PlaybackCacheLease(val file: File, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
internal enum class AppleSegmentRangeMode { Verified, Serial }

/** Response identity stays in memory and is never written beside compressed media bytes. */
internal data class AppleSegmentRangeIdentity(
    val mode: AppleSegmentRangeMode,
    val total: Long = 0L,
    val strongEtag: String? = null,
    val finalResource: String? = null,
)

internal data class AppleSegmentRequestIdentity(
    val start: Long,
    val end: Long,
    val keyKind: KeyKind?,
    val keyUri: String?,
)

internal data class AppleSegmentNamespace(
    val id: String,
    val key: PlaybackCacheKey,
    val adamId: String,
    val groupId: String,
    val codecs: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val resourceUrl: String,
    val segments: List<AppleSegmentRequestIdentity>,
)

internal class AppleSegmentCache internal constructor(
    private val owner: PlaybackCache,
    internal val namespace: AppleSegmentNamespace,
    internal val track: AppleResolvedTrack,
) {
    @Volatile private var rangeIdentity: AppleSegmentRangeIdentity? = null
    @Volatile private var invalid = false
    @Volatile private var playbackAttached = true

    internal fun publishRangeIdentity(value: AppleSegmentRangeIdentity) {
        if (invalid) return
        val previous = rangeIdentity
        if (previous != null && previous != value) {
            invalidate()
            return
        }
        rangeIdentity = value
    }

    internal fun currentRangeIdentity(): AppleSegmentRangeIdentity? = rangeIdentity
    internal fun isHealthy(): Boolean = !invalid && rangeIdentity != null
    internal fun isInvalid(): Boolean = invalid
    internal fun isPlaybackAttached(): Boolean = playbackAttached
    internal fun detachPlaybackState() { playbackAttached = false }
    internal fun markInvalid() { invalid = true }

    suspend fun read(index: Int): ByteArray? = owner.readAppleSegment(this, index)
    suspend fun write(index: Int, bytes: ByteArray, priority: AppleSegmentPriority) =
        owner.writeAppleSegment(this, index, bytes, priority)
    fun touchPlayback(index: Int) = owner.touchAppleSegment(this, index)
    fun detachPlayback() = owner.detachAppleSegments(this)
    fun invalidate() = owner.invalidateAppleSegments(this)
}


/** One current rendition per song. Retired files still count until deletion succeeds. */
@OptIn(UnstableApi::class)
internal class PlaybackCache(context: Context, private val prefs: Prefs, private val transfer: AudioFileTransfer) {
    private class Record(var entry: PlaybackCacheEntry, var readers: Int = 0, var available: Boolean = true, var invalid: Boolean = false)
    private class Work(val rendition: PlaybackRendition, var lastUsed: Long)
    private data class SegmentKey(val namespace: String, val index: Int)
    private class SegmentRecord(
        val key: SegmentKey,
        val fileId: String,
        val expectedBytes: Int,
        var accountedBytes: Long,
        val crc32: Long,
        val identity: AppleSegmentRangeIdentity,
        var readers: Int = 0,
        var available: Boolean = true,
        var foreground: Boolean,
        var lastUsed: Long,
    )

    private val directory = File(context.cacheDir, "playback-audio")
    private val index = AtomicFile(File(directory, "index-v1.json"))
    private val legacyDirectory = File(context.cacheDir, "streaming-audio")
    private val appleSegmentDirectory = File(directory, "apple-segments")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val byKey = mutableMapOf<PlaybackCacheKey, Record>()
    private val byId = mutableMapOf<String, Record>()
    private val orphans = mutableMapOf<File, Long>()
    private val appleSegmentsByKey = mutableMapOf<SegmentKey, SegmentRecord>()
    private val appleSegmentsById = mutableMapOf<String, SegmentRecord>()
    private var occupied = 0L
    private var reserved = 0L
    private var segmentReserved = 0L
    private var serial = 0L
    private var pinned: String? = null
    private var dirty = false
    private var lastMark: Pair<PlaybackCacheKey, Long>? = null
    private val writes = Mutex()
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val maintenance = CoroutineScope(SupervisorJob() + io)
    private val repair = Channel<Unit>(Channel.CONFLATED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pending = linkedMapOf<PlaybackCacheKey, Work>()
    private var active: Pair<Work, Job>? = null
    private val mutableRevision = MutableStateFlow(0L)
    internal val revision: StateFlow<Long> = mutableRevision.asStateFlow()
    private val startup: Job

    init {
        // MediaSession construction and restore are synchronous. Only metadata is loaded here.
        loadIndex()
        startup = maintenance.launch {
            synchronized(lock) {
                for (record in byKey.values.toList()) {
                    if (!hasExpectedLength(record.entry)) retire(record)
                }
                directory.listFiles()?.forEach { file ->
                    if (file.extension == "part" || file.extension == "audio" && file.nameWithoutExtension !in byId) {
                        if (!delete(file)) rememberOrphan(file)
                    }
                }
                appleSegmentDirectory.listFiles()?.forEach { file ->
                    if (!delete(file)) rememberOrphan(file)
                }
                if (appleSegmentDirectory.exists() && !appleSegmentDirectory.delete() && appleSegmentDirectory.listFiles().isNullOrEmpty()) {
                    rememberOrphan(appleSegmentDirectory)
                }
                maintain()
            }
            if (legacyDirectory.exists()) {
                val database = StandaloneDatabaseProvider(context.applicationContext)
                try { SimpleCache.delete(legacyDirectory, database) }
                catch (error: Exception) { Log.w(TAG, "无法清理旧播放流缓存", error) }
                finally { database.close() }
            }
        }
        maintenance.launch {
            startup.join()
            for (signal in repair) synchronized(lock) { maintain() }
        }
    }

    fun find(key: PlaybackCacheKey, requestedQuality: String, appleProfile: ApplePlaybackProfile? = null): PlaybackCacheEntry? =
        synchronized(lock) {
            val record = byKey[key] ?: return@synchronized null
            val entry = record.entry
            val matches = if (key.source == Source.Apple) {
                val profile = appleProfile ?: return@synchronized null
                val explicit = requestedQuality.takeIf { it != profile.quality }
                entry.requestedQuality == requestedQuality && entry.appleNetwork == profile.network &&
                    entry.appleSpatial == profile.spatial && entry.appleExplicitQuality == explicit &&
                    entry.appleProfileQuality == profile.quality
            } else entry.requestedQuality == requestedQuality || entry.actualQuality == requestedQuality
            if (!matches) return@synchronized null
            if (hasExpectedLength(entry)) entry else {
                retire(record)
                repair.trySend(Unit)
                null
            }
        }

    fun appleNetwork(key: PlaybackCacheKey): String? = synchronized(lock) { byKey[key]?.entry?.appleNetwork }

    fun openAppleSegments(key: PlaybackCacheKey, track: AppleResolvedTrack): AppleSegmentCache {
        require(key.source == Source.Apple && key.trackId == track.media.adamId)
        val namespace = AppleSegmentNamespace(
            id = UUID.randomUUID().toString(),
            key = key,
            adamId = track.media.adamId,
            groupId = track.variant.groupId,
            codecs = track.variant.codecs,
            sampleRate = track.variant.sampleRate,
            bitDepth = track.variant.bitDepth,
            resourceUrl = track.media.resourceUrl.toString(),
            segments = track.media.segments.map { segment ->
                AppleSegmentRequestIdentity(segment.range.start, segment.range.end, segment.keyKind, segment.keyUri)
            },
        )
        return AppleSegmentCache(this, namespace, track)
    }

    private class SegmentCacheException(val safeReason: String) : IOException()

    suspend fun readAppleSegment(cache: AppleSegmentCache, index: Int): ByteArray? {
        startup.join()
        val key = SegmentKey(cache.namespace.id, index)
        val record = synchronized(lock) {
            val current = appleSegmentsByKey[key]
            val identity = cache.currentRangeIdentity()
            if (cache.isInvalid() || !cache.isHealthy() || current == null || !current.available || current.identity != identity) {
                current?.let(::retireAppleSegmentLocked)
                repair.trySend(Unit)
                null
            } else {
                current.readers++
                current.lastUsed = nextSegmentUse()
                current
            }
        }
        if (record == null) {
            logAppleSegment(cache, index, "miss", 0, "absent")
            return null
        }

        var failure: String? = null
        val bytes = try {
            withContext(Dispatchers.IO) {
                val source = segmentFile(record.fileId)
                if (!source.isFile || source.length() != record.expectedBytes.toLong()) {
                    throw SegmentCacheException("length")
                }
                val result = ByteArray(record.expectedBytes)
                FileInputStream(source).use { input ->
                    var offset = 0
                    while (offset < result.size) {
                        val count = input.read(result, offset, result.size - offset)
                        if (count <= 0) throw SegmentCacheException("short")
                        offset += count
                    }
                    if (input.read() != -1) throw SegmentCacheException("length")
                }
                val crc = CRC32().apply { update(result) }.value
                if (crc != record.crc32) throw SegmentCacheException("crc")
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SegmentCacheException) {
            failure = error.safeReason
            null
        } catch (error: IOException) {
            failure = "io"
            null
        } finally {
            synchronized(lock) {
                record.readers--
                if (failure != null || cache.isInvalid() || cache.currentRangeIdentity() != record.identity) {
                    retireAppleSegmentLocked(record)
                    failure = failure ?: "identity"
                }
                reconcileAppleSegmentBytes(record)
                if (record.readers == 0 && !record.available) deleteAppleSegmentRecordLocked(record)
                if (record.readers == 0 && !withinLimit(prefs.playbackCacheLimit.bytes, reserved, segmentReserved)) {
                    repair.trySend(Unit)
                }
            }
        }
        if (bytes == null) {
            logAppleSegment(cache, index, "invalid", record.expectedBytes, failure ?: "read")
            return null
        }
        if (cache.isInvalid() || cache.currentRangeIdentity() != record.identity) {
            synchronized(lock) {
                retireAppleSegmentLocked(record)
                reconcileAppleSegmentBytes(record)
                if (record.readers == 0) deleteAppleSegmentRecordLocked(record)
            }
            logAppleSegment(cache, index, "invalid", bytes.size, "identity")
            return null
        }
        logAppleSegment(cache, index, "hit", bytes.size)
        return bytes
    }

    suspend fun writeAppleSegment(cache: AppleSegmentCache, index: Int, bytes: ByteArray, priority: AppleSegmentPriority) {
        startup.join()
        val descriptor = cache.namespace.segments.getOrNull(index)
        val identity = cache.currentRangeIdentity()
        if (descriptor == null || descriptor.end < descriptor.start ||
            bytes.size.toLong() != descriptor.end - descriptor.start + 1L || cache.isInvalid() || identity == null) {
            logAppleSegment(cache, index, "skip", bytes.size, "identity")
            return
        }
        if (bytes.isEmpty() || bytes.size.toLong() > prefs.playbackCacheLimit.bytes) {
            logAppleSegment(cache, index, "skip", bytes.size, "size")
            return
        }
        val directoryReady = withContext(Dispatchers.IO) {
            try {
                (directory.isDirectory || directory.mkdirs() || directory.isDirectory) &&
                    (appleSegmentDirectory.isDirectory || appleSegmentDirectory.mkdirs() || appleSegmentDirectory.isDirectory)
            } catch (_: SecurityException) {
                false
            }
        }
        if (!directoryReady) {
            logAppleSegment(cache, index, "skip", bytes.size, "io")
            return
        }
        val key = SegmentKey(cache.namespace.id, index)
        val reservation = synchronized(lock) {
            if (cache.isInvalid() || cache.currentRangeIdentity() != identity || appleSegmentsByKey[key]?.available == true) {
                "present"
            } else reserveAppleSegmentLocked(bytes.size.toLong(), priority)
        }
        if (reservation != null) {
            logAppleSegment(cache, index, "skip", bytes.size, reservation)
            return
        }

        val id = UUID.randomUUID().toString()
        val partial = segmentPart(id)
        val completed = segmentFile(id)
        val job = currentCoroutineContext()[Job]!!
        var published = false
        var reservedHere = true
        try {
            withContext(Dispatchers.IO) {
                job.ensureActive()
                FileOutputStream(partial).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                job.ensureActive()
                synchronized(lock) {
                    if (cache.isInvalid() || cache.currentRangeIdentity() != identity ||
                        appleSegmentsByKey[key]?.available == true) throw IOException("segment-identity")
                    Files.move(partial.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    val record = SegmentRecord(key, id, bytes.size, bytes.size.toLong(),
                        CRC32().apply { update(bytes) }.value, identity,
                        foreground = priority == AppleSegmentPriority.Playback && cache.isPlaybackAttached(), lastUsed = nextSegmentUse())
                    appleSegmentsByKey[key] = record
                    appleSegmentsById[id] = record
                    segmentReserved -= bytes.size.toLong()
                    occupied = addOccupied(occupied, bytes.size.toLong())
                    reservedHere = false
                    published = true
                }
            }
            logAppleSegment(cache, index, "write", bytes.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            logAppleSegment(cache, index, "skip", bytes.size,
                if (error.message == "segment-identity") "identity" else "io")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(lock) {
                    if (reservedHere) segmentReserved -= bytes.size.toLong()
                    if (!delete(partial)) rememberOrphan(partial)
                    if (!published && !delete(completed)) rememberOrphan(completed)
                    maintain()
                }
            }
        }
    }

    fun touchAppleSegment(cache: AppleSegmentCache, index: Int) = synchronized(lock) {
        if (cache.isInvalid() || !cache.isPlaybackAttached()) return@synchronized
        appleSegmentsByKey[SegmentKey(cache.namespace.id, index)]?.takeIf { it.available }?.let {
            it.foreground = true
            it.lastUsed = nextSegmentUse()
        }
    }

    fun detachAppleSegments(cache: AppleSegmentCache) = synchronized(lock) {
        cache.detachPlaybackState()
        appleSegmentsById.values.filter { it.key.namespace == cache.namespace.id }.forEach { it.foreground = false }
    }

    fun invalidateAppleSegments(cache: AppleSegmentCache) = synchronized(lock) {
        cache.markInvalid()
        appleSegmentsById.values.filter { it.key.namespace == cache.namespace.id }.forEach(::retireAppleSegmentLocked)
        repair.trySend(Unit)
    }


    private fun segmentFile(id: String) = File(appleSegmentDirectory, "$id.segment")
    private fun segmentPart(id: String) = File(appleSegmentDirectory, "$id.part")

    private fun logAppleSegment(cache: AppleSegmentCache, index: Int, event: String, bytes: Int, reason: String? = null) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "apple-segment-cache event=$event id=${cache.namespace.key.trackId.appleLogToken()} " +
            "group=${cache.namespace.groupId.appleLogToken()} index=$index bytes=$bytes" +
            (reason?.let { " reason=$it" } ?: ""))
    }

    private fun nextSegmentUse(): Long {
        serial = if (serial == Long.MAX_VALUE) 1L else serial + 1L
        return serial
    }

    private fun withinLimit(limit: Long, fullReservation: Long, segmentReservation: Long): Boolean {
        if (limit < 0L || occupied < 0L || fullReservation < 0L || segmentReservation < 0L || occupied > limit) return false
        val afterOccupied = limit - occupied
        if (fullReservation > afterOccupied) return false
        return segmentReservation <= afterOccupied - fullReservation
    }

    private fun addOccupied(value: Long, amount: Long): Long =
        if (amount <= 0L) value else if (value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount

    private fun reserveAppleSegmentLocked(bytes: Long, priority: AppleSegmentPriority): String? {
        val limit = prefs.playbackCacheLimit.bytes
        if (bytes <= 0L || bytes > limit || segmentReserved > Long.MAX_VALUE - bytes) return "size"
        val targetSegments = segmentReserved + bytes
        fun fits() = withinLimit(limit, reserved, targetSegments) && bytes <= directory.usableSpace
        if (!fits() && !trimAppleSegmentsLocked(limit, reserved, targetSegments, priority, bytes)) return "capacity"
        if (!fits()) return "capacity"
        segmentReserved = targetSegments
        return null
    }

    private fun trimAppleSegmentsLocked(
        limit: Long,
        fullTarget: Long,
        segmentTarget: Long,
        priority: AppleSegmentPriority,
        diskNeeded: Long,
    ): Boolean {
        fun fits() = withinLimit(limit, fullTarget, segmentTarget) && diskNeeded <= directory.usableSpace
        if (fits()) return true
        val candidates = appleSegmentsById.values.asSequence()
            .filter { it.available && it.readers == 0 && (priority == AppleSegmentPriority.Playback || !it.foreground) }
            .sortedWith(compareBy<SegmentRecord> { it.foreground }.thenBy { it.lastUsed }.thenBy { it.fileId })
            .toList()
        for (record in candidates) {
            deleteAppleSegmentRecordLocked(record)
            if (fits()) return true
        }
        return fits()
    }

    private fun retireAppleSegmentLocked(record: SegmentRecord) {
        if (!record.available) return
        record.available = false
        appleSegmentsByKey.remove(record.key, record)
    }

    private fun reconcileAppleSegmentBytes(record: SegmentRecord) {
        val actual = segmentFile(record.fileId).takeIf(File::exists)?.length() ?: 0L
        if (actual >= record.accountedBytes) occupied = addOccupied(occupied, actual - record.accountedBytes)
        else occupied = (occupied - (record.accountedBytes - actual)).coerceAtLeast(0L)
        record.accountedBytes = actual
    }

    private fun deleteAppleSegmentRecordLocked(record: SegmentRecord): Boolean {
        if (record.readers > 0) return false
        reconcileAppleSegmentBytes(record)
        if (!delete(segmentFile(record.fileId))) return false
        occupied = (occupied - record.accountedBytes).coerceAtLeast(0L)
        record.accountedBytes = 0L
        appleSegmentsByKey.remove(record.key, record)
        appleSegmentsById.remove(record.fileId, record)
        return true
    }

    fun acquire(fileId: String): PlaybackCacheLease? = synchronized(lock) {
        val record = byId[fileId] ?: return@synchronized null
        if (record.invalid || !record.available && fileId != pinned) return@synchronized null
        if (!hasExpectedLength(record.entry)) {
            retire(record)
            repair.trySend(Unit)
            return@synchronized null
        }
        record.readers++
        PlaybackCacheLease(file(fileId)) {
            synchronized(lock) {
                record.readers--
                if (record.readers == 0 && (!record.available || !withinLimit(prefs.playbackCacheLimit.bytes, reserved, segmentReserved))) {
                    repair.trySend(Unit)
                }
            }
        }
    }

    fun pinCurrent(fileId: String?) = synchronized(lock) {
        if (fileId == pinned) return@synchronized
        val old = pinned?.let(byId::get)
        pinned = fileId?.takeIf(byId::containsKey)
        if (old?.available == false || !withinLimit(prefs.playbackCacheLimit.bytes, reserved, segmentReserved)) repair.trySend(Unit)
    }

    fun invalidate(fileId: String) = synchronized(lock) {
        val record = byId[fileId] ?: return@synchronized
        retire(record)
        repair.trySend(Unit)
    }

    fun setLimit(limit: PlaybackCacheLimit) {
        prefs.playbackCacheLimit = limit
        repair.trySend(Unit)
    }

    fun markPlayed(key: PlaybackCacheKey): Long = synchronized(lock) {
        val used = ++serial
        lastMark = key to used
        pending[key]?.lastUsed = used
        active?.first?.takeIf { it.rendition.key == key }?.lastUsed = used
        byKey[key]?.let { record ->
            record.entry = record.entry.copy(lastUsed = used)
            dirty = true
            repair.trySend(Unit)
        }
        used
    }

    fun enqueue(rendition: PlaybackRendition) = synchronized(lock) {
        val key = rendition.key
        val latest = lastMark?.takeIf { it.first == key }?.second ?: serial
        val running = active?.takeIf { it.first.rendition.key == key && !it.second.isCancelled }
        val queued = pending[key]
        if (queued != null && sameRendition(queued.rendition, rendition)) return@synchronized
        if (running != null) {
            if (sameRendition(running.first.rendition, rendition)) return@synchronized
            running.second.cancel()
        }
        pending.remove(key)
        val existing = find(key, rendition.requestedQuality, rendition.appleProfile)
        if (existing?.actualQuality == rendition.actualQuality && existing.appleGroup == rendition.appleGroup) return@synchronized
        pending[key] = Work(rendition, latest)
        wake.trySend(Unit)
    }

    /** The session owns this worker; switching tracks doesn't cancel transfers that have already started. */
    suspend fun runPending() = coroutineScope {
        for (signal in wake) {
            while (true) {
                val next = synchronized(lock) {
                    val first = pending.entries.maxByOrNull { it.value.lastUsed } ?: return@synchronized null
                    val work = first.value
                    pending.remove(first.key)
                    launch(start = CoroutineStart.LAZY) { complete(work) }.also { active = work to it }
                } ?: break
                next.start()
                try { next.join() }
                finally { synchronized(lock) { if (active?.second == next) active = null } }
            }
        }
    }

    fun cancelPending() = synchronized(lock) {
        pending.clear()
        active?.second?.cancel()
    }

    private suspend fun complete(work: Work) {
        val rendition = work.rendition
        val key = rendition.key
        val profile = rendition.appleProfile
        val explicit = profile?.let { rendition.requestedQuality.takeIf { q -> q != it.quality } }
        val entry = PlaybackCacheEntry(key, "", 0L, work.lastUsed, 0L, "", null,
            rendition.requestedQuality, rendition.actualQuality, rendition.appleGroup, rendition.appleMode,
            profile?.network, profile?.spatial, explicit, profile?.quality)
        val request = FullAudioRequest(rendition.track, key.server, key.storefront, rendition.actualQuality,
            prefs.playbackCacheLimit.bytes, appleGroup = rendition.appleGroup, appleCodecs = rendition.appleCodecs,
            resolvedNetease = rendition.resolvedNetease, strict = true, expectedDurationMs = rendition.durationMs,
            requestedNeteaseQuality = rendition.neteaseRequestQuality, resolvedApple = rendition.resolvedApple)
        val started = SystemClock.elapsedRealtime()
        if (rendition.resolvedApple != null) {
            Log.i(TAG, "apple-cache-start id=${key.trackId.appleLogToken()} occurrence=${rendition.occurrence} " +
                "request=${rendition.requestId?.appleLogToken()} group=${rendition.appleGroup?.appleLogToken()} " +
                "segments=${rendition.resolvedApple.track.media.segments.size} parallelCap=$AUDIO_LANES " +
                "bufferBytes=$APPLE_ALAC_SEGMENT_BUFFER_BYTES reused=resolved")
        }
        val saved = write(entry) { part, reserve -> transfer.transfer(request, part, reserve) {} }
        val resolved = rendition.resolvedApple
        if (saved != null && resolved != null && request.strict && saved.key == key && resolved.key == key &&
            resolved.track.media.adamId == key.trackId && rendition.appleGroup == resolved.track.variant.groupId &&
            rendition.appleCodecs == resolved.track.variant.codecs && saved.appleGroup == resolved.track.variant.groupId &&
            saved.actualQuality == rendition.actualQuality) {
            resolved.completedCache = saved
        }
        if (saved != null) {
            if (rendition.resolvedApple != null) {
                Log.i(TAG, "apple-cache-complete id=${key.trackId.appleLogToken()} occurrence=${rendition.occurrence} " +
                    "request=${rendition.requestId?.appleLogToken()} group=${rendition.appleGroup?.appleLogToken()} " +
                    "bytes=${saved.sizeBytes} durationMs=${saved.durationMs} elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "file=${saved.fileId}")
            } else Log.i(TAG, "已缓存 ${key.source}/${key.trackId} ${rendition.actualQuality}")
        }
    }

    suspend fun write(entry: PlaybackCacheEntry, transfer: suspend (File, (Long) -> Unit) -> AudioFileInfo): PlaybackCacheEntry? = writes.withLock {
        startup.join()
        val id = UUID.randomUUID().toString()
        val partial = File(directory, "$id.part")
        val completed = file(id)
        var published = false
        try {
            withContext(io) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建播放缓存目录")
                val job = currentCoroutineContext()[Job]!!
                val info = transfer(partial) { size ->
                    job.ensureActive()
                    synchronized(lock) { reserve(partial, size) }
                }
                job.ensureActive()
                synchronized(lock) {
                    val key = entry.key
                    val latest = maxOf(entry.lastUsed, byKey[key]?.entry?.lastUsed ?: 0L,
                        active?.first?.takeIf { it.rendition.key == key }?.lastUsed ?: 0L,
                        lastMark?.takeIf { it.first == key }?.second ?: 0L)
                    val saved = entry.copy(fileId = id, sizeBytes = info.sizeBytes, lastUsed = latest,
                        durationMs = info.durationMs ?: 0L, mime = info.mime.orEmpty(), sampleRate = info.sampleRate,
                        actualQuality = info.quality)
                    if (!valid(saved) || !partial.isFile || partial.length() != saved.sizeBytes) {
                        throw IOException("播放缓存校验失败")
                    }
                    reserve(partial, saved.sizeBytes)
                    job.ensureActive()
                    Files.move(partial.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    persist(saved)
                    byKey[key]?.let(::retireQuietly)
                    val record = Record(saved)
                    byKey[key] = record
                    byId[id] = record
                    occupied = addOccupied(occupied, saved.sizeBytes)
                    reserved = 0L
                    published = true
                    changed()
                    saved
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "未缓存 ${entry.key.source}/${entry.key.trackId}：${error.message}")
            null
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "未缓存 ${entry.key.source}/${entry.key.trackId}：内存不足")
            null
        } finally {
            withContext(NonCancellable + io) {
                synchronized(lock) {
                    reserved = 0L
                    if (!delete(partial)) rememberOrphan(partial)
                    if (!published && !delete(completed)) rememberOrphan(completed)
                    maintain()
                }
            }
        }
    }

    private fun reserve(partial: File, bytes: Long) {
        val limit = prefs.playbackCacheLimit.bytes
        if (bytes <= 0L || bytes > limit) throw IOException("整曲大小超过播放缓存上限")
        if (bytes <= reserved && withinLimit(limit, reserved, segmentReserved)) return
        val target = maxOf(bytes, reserved)
        val needed = target - partial.length().coerceAtMost(target)
        if ((!withinLimit(limit, target, segmentReserved) || target > reserved && needed > directory.usableSpace) &&
            !trim(limit, target, needed)) throw IOException("播放缓存空间不足")
        reserved = target
    }

    private fun trim(limit: Long, target: Long, diskNeeded: Long = 0L): Boolean {
        if (target < 0L || target > limit) return false
        fun fits() = withinLimit(limit, target, segmentReserved) && (diskNeeded <= 0L || diskNeeded <= directory.usableSpace)
        if (fits()) return true
        if (trimAppleSegmentsLocked(limit, target, segmentReserved, AppleSegmentPriority.Playback, diskNeeded) && fits()) return true
        val candidates = byKey.values.filterNot(::protected).sortedWith(
            compareBy<Record> { it.entry.lastUsed }.thenBy { it.entry.fileId },
        )
        for (record in candidates) {
            if (!delete(file(record.entry.fileId))) continue
            retire(record)
            forget(record)
            if (fits()) return true
        }
        return false
    }

    private fun maintain() {
        val files = orphans.iterator()
        while (files.hasNext()) {
            val orphan = files.next()
            val file = orphan.key
            val accounted = orphan.value
            val actual = file.length()
            if (actual >= accounted) occupied = addOccupied(occupied, actual - accounted)
            else occupied = (occupied - (accounted - actual)).coerceAtLeast(0L)
            if (delete(file)) { occupied = (occupied - actual).coerceAtLeast(0L); files.remove() }
            else orphan.setValue(actual)
        }
        for (record in appleSegmentsById.values.toList()) {
            if (!record.available && record.readers == 0) deleteAppleSegmentRecordLocked(record)
        }
        val entries = byId.values.iterator()
        while (entries.hasNext()) {
            val record = entries.next()
            if (!record.available && !protected(record) && delete(file(record.entry.fileId))) {
                occupied = (occupied - record.entry.sizeBytes).coerceAtLeast(0L)
                entries.remove()
            }
        }
        val limit = prefs.playbackCacheLimit.bytes
        if (!trim(limit, reserved) && reserved > 0L) {
            active?.second?.cancel()
            trim(limit, 0L)
        }
        if (dirty) {
            try { persist() }
            catch (error: IOException) { Log.w(TAG, "无法保存播放缓存索引", error) }
        }
    }

    private fun protected(record: Record) = record.readers > 0 || record.entry.fileId == pinned
    private fun retireQuietly(record: Record) { record.available = false; byKey.remove(record.entry.key) }
    private fun retire(record: Record) {
        record.invalid = true
        if (!record.available) return
        retireQuietly(record)
        dirty = true
        changed()
    }
    private fun forget(record: Record) {
        val id = record.entry.fileId
        byId.remove(id)
        occupied = (occupied - record.entry.sizeBytes).coerceAtLeast(0L)
    }
    private fun rememberOrphan(file: File) {
        val bytes = file.length()
        val previous = orphans.put(file, bytes) ?: 0L
        if (bytes >= previous) occupied = addOccupied(occupied, bytes - previous)
        else occupied = (occupied - (previous - bytes)).coerceAtLeast(0L)
    }
    private fun changed() { mutableRevision.value++ }
    private fun file(id: String) = File(directory, "$id.audio")
    private fun delete(file: File) = file.delete() || !file.exists()
    private fun hasExpectedLength(entry: PlaybackCacheEntry) = file(entry.fileId).let { it.isFile && it.length() == entry.sizeBytes }

    private fun loadIndex() {
        val snapshot = try {
            index.openRead().use { stream ->
                val size = stream.channel.size()
                if (size !in 1L..INDEX_LIMIT.toLong()) throw IOException("播放缓存索引大小无效")
                val bytes = ByteArray(size.toInt())
                DataInputStream(stream).readFully(bytes)
                json.decodeFromString<List<PlaybackCacheEntry>>(bytes.decodeToString())
            }
        } catch (_: IllegalArgumentException) { return }
        catch (_: IOException) { return }
        for (entry in snapshot) {
            if (!valid(entry) || entry.key in byKey || entry.fileId in byId) { dirty = true; continue }
            val record = Record(entry)
            byKey[entry.key] = record
            byId[entry.fileId] = record
            serial = maxOf(serial, entry.lastUsed)
            occupied = addOccupied(occupied, entry.sizeBytes)
        }
    }

    private fun persist(new: PlaybackCacheEntry? = null) {
        val entries = ArrayList<PlaybackCacheEntry>(byKey.size + if (new == null) 0 else 1)
        byKey.values.forEach { if (it.entry.key != new?.key) entries.add(it.entry) }
        if (new != null) entries.add(new)
        val payload = json.encodeToString(entries).encodeToByteArray()
        if (payload.size > INDEX_LIMIT) throw IOException("播放缓存索引过大")
        val stream = index.startWrite()
        try {
            stream.write(payload)
            index.finishWrite(stream)
            if (!index.baseFile.isFile || File(directory, "index-v1.json.new").exists()) throw IOException("播放缓存索引提交失败")
            dirty = false
        } catch (error: IOException) {
            index.failWrite(stream)
            throw error
        }
    }

    private fun valid(entry: PlaybackCacheEntry): Boolean {
        val (server, storefront, source, id) = entry.key
        if (PlaybackCacheKey.from(server, storefront, source, id) != entry.key || !FILE_ID.matches(entry.fileId) ||
            entry.sizeBytes !in 1L..PlaybackCacheLimit.Gb5.bytes || entry.lastUsed !in 1L until Long.MAX_VALUE ||
            entry.durationMs <= 0L || !entry.mime.startsWith("audio/") || entry.sampleRate?.let { it <= 0 } == true ||
            source.qualities.none { it.first == entry.actualQuality }) return false
        if (source != Source.Apple) return source.qualities.any { it.first == entry.requestedQuality }
        return entry.appleGroup?.matches(APPLE_GROUP) == true && !entry.appleMode.isNullOrBlank() &&
            entry.appleNetwork in APPLE_NETWORKS && Prefs.APPLE_SPATIALS.any { it.first == entry.appleSpatial } &&
            Prefs.APPLE_PLAYBACK_QUALITIES.any { it.first == entry.appleProfileQuality } &&
            (entry.appleExplicitQuality == null || source.qualities.any { it.first == entry.appleExplicitQuality }) &&
            entry.requestedQuality == (entry.appleExplicitQuality ?: entry.appleProfileQuality)
    }

    private fun sameRendition(a: PlaybackRendition, b: PlaybackRendition) =
        a.requestedQuality == b.requestedQuality && a.actualQuality == b.actualQuality && a.appleProfile == b.appleProfile &&
            a.appleGroup == b.appleGroup && a.appleCodecs == b.appleCodecs

    companion object {
        private const val TAG = "FantasticPlaybackCache"
        private const val INDEX_LIMIT = 4 * 1024 * 1024
        internal val FILE_ID = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
        private val APPLE_GROUP = Regex("[A-Za-z0-9._-]{1,128}")
        private val APPLE_NETWORKS = setOf("wifi", "cellular")
    }
}
