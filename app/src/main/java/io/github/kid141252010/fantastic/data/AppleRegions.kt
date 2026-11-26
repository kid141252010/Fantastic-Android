package io.github.kid141252010.fantastic.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import okhttp3.OkHttpClient

internal data class AppleRegionsState(
    val entries: Map<String, AppleRegionEntry> = emptyMap(),
    val loading: Boolean = true,
    val refreshingServer: String? = null,
    val persistenceError: String? = null,
)

internal data class AppleRegionEntry(
    val storefronts: List<AppleStorefront>? = null,
    val wrapperRegions: List<String>? = null,
    val storefrontError: String? = null,
    val wrapperError: String? = null,
)

internal data class AppleRegionRefresh(
    val storefronts: Result<List<AppleStorefront>>,
    val wrapper: Result<SourceStatus.Wrapper>,
)

/** Successful regions have no expiry. Only startup misses and explicit refreshes use the network. */
internal class AppleRegions(context: Context, private val apple: AppleCatalogApi, private val client: OkHttpClient) {
    private val file = File(context.applicationContext.filesDir, "apple-regions.json")
    private val atomicFile = AtomicFile(file)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(AppleRegionsState())
    val state: StateFlow<AppleRegionsState> = mutableState.asStateFlow()

    fun start(baseUrl: String) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            mutex.withLock {
                load()
                val server = SourceStatus.normalizeBaseUrl(baseUrl) ?: return@withLock
                val entry = mutableState.value.entries[server]
                if (entry?.storefronts == null || entry.wrapperRegions == null) {
                    fetch(server, entry?.storefronts == null, entry?.wrapperRegions == null)
                }
            }
        }
    }

    suspend fun refresh(baseUrl: String): AppleRegionRefresh = withContext(Dispatchers.IO) {
        val server = SourceStatus.normalizeBaseUrl(baseUrl) ?: throw IOException("服务器地址无效")
        mutex.withLock {
            load()
            val (storefronts, wrapper) = fetch(server, fetchStorefronts = true, fetchWrapper = true)
            AppleRegionRefresh(checkNotNull(storefronts), checkNotNull(wrapper))
        }
    }

    // The mutex covers disk loading, requests, merging, and persistence, including draft servers.
    private suspend fun fetch(
        server: String,
        fetchStorefronts: Boolean,
        fetchWrapper: Boolean,
    ): Pair<Result<List<AppleStorefront>>?, Result<SourceStatus.Wrapper>?> {
        mutableState.value = mutableState.value.copy(refreshingServer = server)
        try {
            val results = coroutineScope {
                val storefronts = if (fetchStorefronts) async {
                    attempt {
                        cleanStorefronts(apple.storefronts(server))
                            ?: throw IOException("Apple Music 地区列表为空")
                    }
                } else null
                val wrapper = if (fetchWrapper) async { attempt { SourceStatus.wrapper(client, server) } } else null
                storefronts?.await() to wrapper?.await()
            }
            currentCoroutineContext().ensureActive()
            val (storefronts, wrapper) = results
            val current = mutableState.value
            val previous = current.entries[server] ?: AppleRegionEntry()
            val status = wrapper?.getOrNull()
            val validWrapper = status?.takeIf { it.httpCode in 200..299 && it.serverReachable && it.available }
            val entry = previous.copy(
                storefronts = storefronts?.getOrNull() ?: previous.storefronts,
                wrapperRegions = validWrapper?.regions?.let(::cleanCodes) ?: previous.wrapperRegions,
                storefrontError = if (storefronts != null) storefronts.exceptionOrNull()?.message else previous.storefrontError,
                wrapperError = if (wrapper == null) previous.wrapperError else when {
                    wrapper.isFailure -> wrapper.exceptionOrNull()?.message ?: "wrapper 请求失败"
                    validWrapper == null -> status?.message ?: "wrapper 状态无效"
                    else -> null
                },
            )
            mutableState.value = current.copy(entries = current.entries + (server to entry))
            persist()
            return results
        } finally {
            mutableState.value = mutableState.value.copy(refreshingServer = null)
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        mutableState.value = try {
            val entries = if (!file.exists() && !File(file.path + ".bak").exists()) emptyMap() else {
                val stored = atomicFile.openRead().use { input ->
                    json.decodeFromString<StoredRegions>(input.bufferedReader(Charsets.UTF_8).readText())
                }
                if (stored.version != 1) throw IOException("不支持的地区缓存版本")
                stored.servers.mapNotNull { (key, value) ->
                    val server = SourceStatus.normalizeBaseUrl(key) ?: return@mapNotNull null
                    server to AppleRegionEntry(cleanStorefronts(value.storefronts), value.wrapperRegions?.let(::cleanCodes))
                }.toMap()
            }
            AppleRegionsState(entries = entries, loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AppleRegionsState(loading = false, persistenceError = "地区缓存读取失败，按缺失缓存重新获取")
        }
    }

    private fun persist() {
        try {
            val stored = StoredRegions(1, mutableState.value.entries.mapValues { (_, entry) ->
                StoredEntry(entry.storefronts, entry.wrapperRegions)
            })
            val stream = atomicFile.startWrite()
            try {
                stream.write(json.encodeToString(stored).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (error: Exception) {
                atomicFile.failWrite(stream)
                throw error
            }
            mutableState.value = mutableState.value.copy(persistenceError = null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(persistenceError = "地区缓存保存失败")
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun cleanStorefronts(values: List<AppleStorefront>?): List<AppleStorefront>? = values
        ?.filter { validCode(it.id) }?.distinctBy { it.id }?.takeIf { it.isNotEmpty() }

    private fun cleanCodes(values: List<String>): List<String> = values.filter(::validCode).distinct()

    private fun validCode(value: String): Boolean = value.length == 2 && value.all { it in 'a'..'z' }

    @Serializable
    private data class StoredRegions(val version: Int, val servers: Map<String, StoredEntry>)

    @Serializable
    private data class StoredEntry(val storefronts: List<AppleStorefront>? = null, val wrapperRegions: List<String>? = null)
}
