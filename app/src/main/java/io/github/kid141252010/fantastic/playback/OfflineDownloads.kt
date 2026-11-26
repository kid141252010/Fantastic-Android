package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class DownloadStatus { Queued, Downloading, Ready, Failed, Cancelled }

@Serializable
internal data class OfflineDownload(
    val track: Track,
    val fileId: String,
    val server: String,
    val storefront: String,
    val quality: String,
    val status: DownloadStatus = DownloadStatus.Queued,
    val percent: Int? = null,
    val error: String? = null,
) {
    val key: String get() = track.mediaId
}

internal data class OfflineAudio(val file: File, val quality: String)

/** Durable files are separate from the evictable streaming cache. One worker owns the active file. */
internal class OfflineDownloads(private val context: Context, private val prefs: Prefs, private val transfer: AudioFileTransfer) {
    private val directory = File(context.noBackupFilesDir, "offline-audio")
    private val index = AtomicFile(File(directory, "downloads.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())
    val entries = state.asStateFlow()
    private val updates = Channel<List<OfflineDownload>>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ready = readyIndex(state.value)
    private var active: Job? = null
    private var workerRunning = false

    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in updates) {
                try {
                    val stream = index.startWrite()
                    try {
                        stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                        index.finishWrite(stream)
                    } catch (error: Exception) {
                        index.failWrite(stream)
                        throw error
                    }
                } catch (error: IOException) {
                    Log.e("FantasticDownloads", "无法保存下载列表", error)
                }
            }
        }
    }

    fun audio(track: Track, requestedQuality: String?): OfflineAudio? = ready[track.mediaId]?.let { job ->
        val country = if (track.source == Source.Apple) track.country.normalizedAppleCountry() else null
        if (country != null && job.storefront != country) return@let null
        if (requestedQuality != null && requestedQuality != job.quality) return@let null
        val file = completed(job).takeIf(File::isFile) ?: return@let null
        OfflineAudio(file, job.quality)
    }

    fun enqueue(tracks: List<Track>, quality: String? = null) {
        val existing = state.value.associateBy { it.key }
        fun country(track: Track): String? = if (track.source == Source.Apple)
            track.country.normalizedAppleCountry() ?: prefs.storefront.normalizedAppleCountry() else prefs.storefront
        val new = tracks.distinctBy { it.mediaId }.filter { track ->
            val region = country(track) ?: return@filter false
            val previous = existing[track.mediaId]
            val changedAppleRegion = track.source == Source.Apple && previous != null && previous.storefront != region
            track.source.validSongId(track.id) && (changedAppleRegion || previous?.status !in
                setOf(DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Ready))
        }.mapNotNull { track ->
            val selected = quality?.takeIf { q -> track.source.qualities.any { it.first == q } }
                ?: prefs.preferredQuality(track.source)
            val region = country(track) ?: return@mapNotNull null
            val previous = existing[track.mediaId]
            if (track.source == Source.Apple && previous != null && previous.storefront != region) {
                scope.launch(Dispatchers.IO) { completed(previous).delete() }
            }
            OfflineDownload(track, UUID.randomUUID().toString(), prefs.baseUrl, region, selected)
        }
        if (new.isEmpty()) return
        val replacing = new.mapTo(hashSetOf()) { it.key }
        publish(state.value.filter { it.key !in replacing } + new)
        launchService()
    }

    fun retry(key: String) {
        val job = find(key) ?: return
        if (job.status !in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled)) return
        val quality = job.quality.takeIf { q -> job.track.source.qualities.any { it.first == q } }
            ?: prefs.preferredQuality(job.track.source)
        val queued = job.copy(fileId = UUID.randomUUID().toString(), status = DownloadStatus.Queued,
            error = null, percent = null, server = prefs.baseUrl, quality = quality)
        publish(state.value.map { if (it.key == key) queued else it })
        launchService()
    }

    fun cancel(key: String) {
        val job = find(key) ?: return
        if (job.status !in setOf(DownloadStatus.Queued, DownloadStatus.Downloading)) return
        update(job.copy(status = DownloadStatus.Cancelled, percent = null))
        if (job.status == DownloadStatus.Downloading) active?.cancel()
    }

    fun remove(key: String) {
        val job = find(key) ?: return
        if (job.status == DownloadStatus.Downloading) active?.cancel()
        publish(state.value.filter { it.key != key })
        scope.launch(Dispatchers.IO) { completed(job).delete() }
    }

    fun failQueued(reason: String) = publish(state.value.map { job ->
        if (job.status == DownloadStatus.Queued) job.copy(status = DownloadStatus.Failed, error = reason) else job
    })

    suspend fun runPending() = coroutineScope {
        if (workerRunning) return@coroutineScope
        workerRunning = true
        try {
            while (true) {
                val job = state.value.firstOrNull { it.status == DownloadStatus.Queued } ?: break
                val worker = launch { transfer(job) }
                active = worker
                worker.join()
                active = null
            }
        } finally { workerRunning = false; active = null }
    }

    private suspend fun transfer(job: OfflineDownload) {
        val partial = File(directory, job.fileId + ".part")
        update(job.copy(status = DownloadStatus.Downloading), durable = false)
        try {
            withContext(Dispatchers.IO) { directory.mkdirs(); partial.delete() }
            val report: (Int?) -> Unit = { percent ->
                scope.launch {
                    val current = find(job.key)
                    if (current?.fileId == job.fileId && current.status == DownloadStatus.Downloading && current.percent != percent) {
                        update(current.copy(percent = percent), durable = false)
                    }
                }
            }
            transfer.transfer(
                FullAudioRequest(job.track, job.server, job.storefront, job.quality, MAX_AUDIO_BYTES),
                partial,
                reserve = {},
                progress = report,
            )
            withContext(Dispatchers.IO) {
                Files.move(partial.toPath(), completed(job).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            updateActive(job.copy(status = DownloadStatus.Ready, percent = 100))
        } catch (error: CancellationException) {
            updateActive(job.copy(status = DownloadStatus.Failed, error = "下载已中断"))
            throw error
        } catch (error: Exception) {
            updateActive(job.copy(status = DownloadStatus.Failed, error = error.localizedMessage?.take(200) ?: "下载失败"))
        } finally {
            val saved = find(job.key)?.let { it.fileId == job.fileId && it.status == DownloadStatus.Ready } == true
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                partial.delete()
                if (!saved) completed(job).delete()
            }
        }
    }


    private fun launchService() {
        try { ContextCompat.startForegroundService(context, Intent(context, OfflineDownloadService::class.java)) }
        catch (_: IllegalStateException) { failQueued("系统未允许启动后台下载，请在应用内重试") }
    }

    private fun find(key: String) = state.value.firstOrNull { it.key == key }
    private fun updateActive(job: OfflineDownload) {
        val existing = find(job.key)
        if (existing?.fileId == job.fileId && existing.status == DownloadStatus.Downloading) update(job)
    }
    private fun update(job: OfflineDownload, durable: Boolean = true) = publish(state.value.map { if (it.fileId == job.fileId) job else it }, durable)
    private fun publish(list: List<OfflineDownload>, durable: Boolean = true) {
        if (durable) { ready = readyIndex(list); updates.trySend(list) }
        state.value = list
    }
    private fun completed(job: OfflineDownload) = File(directory, job.fileId + ".audio")
    private fun readyIndex(list: List<OfflineDownload>) = list.filter { it.status == DownloadStatus.Ready }.associateBy(OfflineDownload::key)
    private fun load(): List<OfflineDownload> = runCatching {
        json.decodeFromString<List<OfflineDownload>>(index.readFully().decodeToString()).mapNotNull { job ->
            if (!FILE_ID.matches(job.fileId)) return@mapNotNull null
            when {
                job.status == DownloadStatus.Ready && completed(job).isFile -> job
                job.status == DownloadStatus.Ready -> job.copy(status = DownloadStatus.Failed, percent = null, error = "离线文件已丢失")
                job.status == DownloadStatus.Downloading || job.status == DownloadStatus.Queued -> {
                    File(directory, job.fileId + ".part").delete()
                    job.copy(status = DownloadStatus.Failed, percent = null, error = "下载已中断，点按重试")
                }
                else -> job
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_AUDIO_BYTES = 512L * 1024 * 1024
        private val FILE_ID = Regex("[0-9a-f-]{36}")
    }
}
