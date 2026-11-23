package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import io.github.kid141252010.fantastic.data.NeteaseAudio
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.PlaybackCacheLimit
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import java.io.DataInputStream
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

internal class PlaybackCacheLease(val file: File, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/** One current rendition per song. Retired files still count until deletion succeeds. */
@OptIn(UnstableApi::class)
internal class PlaybackCache(context: Context, private val prefs: Prefs, private val transfer: AudioFileTransfer) {
    private class Record(var entry: PlaybackCacheEntry, var readers: Int = 0, var available: Boolean = true, var invalid: Boolean = false)
    private class Work(val rendition: PlaybackRendition, var lastUsed: Long)

    private val directory = File(context.cacheDir, "playback-audio")
    private val index = AtomicFile(File(directory, "index-v1.json"))
    private val legacyDirectory = File(context.cacheDir, "streaming-audio")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val byKey = mutableMapOf<PlaybackCacheKey, Record>()
    private val byId = mutableMapOf<String, Record>()
    private val orphans = mutableMapOf<File, Long>()
    private var occupied = 0L
    private var reserved = 0L
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

    fun audio(track: Track, requestedQuality: String, appleProfile: ApplePlaybackProfile? = null): OfflineAudio? {
        val key = PlaybackCacheKey.from(prefs.baseUrl, prefs.storefront, track.source, track.id) ?: return null
        val entry = find(key, requestedQuality, appleProfile) ?: return null
        return OfflineAudio(file(entry.fileId), entry.actualQuality)
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
                if (record.readers == 0 && (!record.available || occupied + reserved > prefs.playbackCacheLimit.bytes)) {
                    repair.trySend(Unit)
                }
            }
        }
    }

    fun pinCurrent(fileId: String?) = synchronized(lock) {
        if (fileId == pinned) return@synchronized
        val old = pinned?.let(byId::get)
        pinned = fileId?.takeIf(byId::containsKey)
        if (old?.available == false || occupied + reserved > prefs.playbackCacheLimit.bytes) repair.trySend(Unit)
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
                    val first = pending.entries.firstOrNull() ?: return@synchronized null
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
            requestedNeteaseQuality = rendition.neteaseRequestQuality)
        if (write(entry) { part, reserve -> transfer.transfer(request, part, reserve) {} }) {
            Log.i(TAG, "已缓存 ${key.source}/${key.trackId} ${rendition.actualQuality}")
        }
    }

    suspend fun write(entry: PlaybackCacheEntry, transfer: suspend (File, (Long) -> Unit) -> AudioFileInfo): Boolean = writes.withLock {
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
                    occupied += saved.sizeBytes
                    reserved = 0L
                    published = true
                    changed()
                }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.w(TAG, "未缓存 ${entry.key.source}/${entry.key.trackId}：${error.message}")
            false
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
        if (bytes <= reserved && occupied + reserved <= limit) return
        val target = maxOf(bytes, reserved)
        val needed = target - partial.length()
        if ((occupied + target > limit || target > reserved && needed > directory.usableSpace) && !trim(limit, target, needed)) {
            throw IOException("播放缓存空间不足")
        }
        reserved = target
    }

    private fun trim(limit: Long, target: Long, diskNeeded: Long = 0L): Boolean {
        if (target > limit) return false
        fun fits() = occupied + target <= limit && (diskNeeded <= 0L || diskNeeded <= directory.usableSpace)
        if (fits()) return true
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
            val (file, bytes) = files.next()
            if (delete(file)) { occupied -= bytes; files.remove() }
        }
        val entries = byId.values.iterator()
        while (entries.hasNext()) {
            val record = entries.next()
            if (!record.available && !protected(record) && delete(file(record.entry.fileId))) {
                occupied -= record.entry.sizeBytes
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
        occupied -= record.entry.sizeBytes
    }
    private fun rememberOrphan(file: File) {
        val bytes = file.length()
        occupied += bytes - (orphans.put(file, bytes) ?: 0L)
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
            occupied += entry.sizeBytes
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
