package io.github.kid141252010.fantastic.data

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class AppleStorefrontState(
    val baseUrl: String,
    val selected: String,
    val available: List<AppleStorefront>,
    val fetchedAtMs: Long,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    fun millisUntilStale(nowMs: Long = System.currentTimeMillis()): Long {
        if (available.isEmpty() || fetchedAtMs <= 0 || nowMs < fetchedAtMs) return 0
        return (86_400_000L - (nowMs - fetchedAtMs)).coerceAtLeast(0)
    }
}

/** All mutations and publication are serialized on Main. The cache belongs to one accepted server. */
internal class AppleStorefronts(private val prefs: Prefs, private val apple: AppleCatalogApi) {
    @Serializable
    private data class Cache(val baseUrl: String, val fetchedAtMs: Long, val available: List<AppleStorefront>)

    private val json = Json { ignoreUnknownKeys = true }
    private val current = MutableStateFlow(restore())
    val state: StateFlow<AppleStorefrontState> = current.asStateFlow()
    private var generation = 0L

    @MainThread
    fun select(baseUrl: String, id: String) {
        val snapshot = current.value
        val selected = normalizeAppleStorefront(id) ?: return
        if (snapshot.baseUrl != baseUrl || snapshot.selected == selected || snapshot.available.none { it.id == selected }) return
        prefs.saveAppleCatalogSettings(baseUrl, selected)
        current.value = snapshot.copy(selected = selected)
    }

    suspend fun refreshIfStale(): Unit = withContext(Dispatchers.Main.immediate) {
        val snapshot = current.value
        if (snapshot.refreshing || snapshot.millisUntilStale() > 0) return@withContext
        val baseUrl = SourceStatus.normalizeBaseUrl(snapshot.baseUrl) ?: return@withContext
        val ticket = ++generation
        current.value = snapshot.copy(refreshing = true, error = null)
        try {
            val available = apple.storefronts(baseUrl)
            ensureActive()
            if (generation == ticket) save(baseUrl, current.value.selected, available)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (generation == ticket) current.value = current.value.copy(refreshing = false, error = "无法读取 Apple Music 地区")
        } finally {
            if (generation == ticket && current.value.refreshing) current.value = current.value.copy(refreshing = false)
        }
    }

    @MainThread
    fun acceptCheckedServer(baseUrl: String, requested: String, available: List<AppleStorefront>): AppleStorefrontState {
        val normalized = requireNotNull(SourceStatus.normalizeBaseUrl(baseUrl))
        val selected = available.firstOrNull { it.id == normalizeAppleStorefront(requested) }?.id ?: current.value.selected
        return save(normalized, selected, available)
    }

    @MainThread
    private fun save(baseUrl: String, selected: String, available: List<AppleStorefront>): AppleStorefrontState {
        val resolved = available.firstOrNull { it.id == selected }?.id ?: available.first().id
        val fetchedAtMs = System.currentTimeMillis()
        val cache = json.encodeToString(Cache(baseUrl, fetchedAtMs, available))
        prefs.saveAppleCatalogSettings(baseUrl, resolved, cache)
        generation++
        return AppleStorefrontState(baseUrl, resolved, available, fetchedAtMs).also { current.value = it }
    }

    private fun restore(): AppleStorefrontState {
        val storedUrl = prefs.baseUrl
        val baseUrl = SourceStatus.normalizeBaseUrl(storedUrl)
            ?: return AppleStorefrontState(storedUrl, prefs.storefront, emptyList(), 0,
                error = "服务器地址无效。请输入完整的 HTTP(S) 地址，不要包含账号、查询参数或片段。")
        val cache = runCatching { prefs.appleStorefrontCache?.let { json.decodeFromString<Cache>(it) } }.getOrNull()
            ?.takeIf {
                SourceStatus.normalizeBaseUrl(it.baseUrl) == baseUrl && it.fetchedAtMs > 0 && it.available.isNotEmpty() &&
                    it.available.all { entry -> normalizeAppleStorefront(entry.id) == entry.id }
            }
        val available = cache?.available?.distinctBy { it.id }.orEmpty()
        val selected = prefs.storefront
        val resolved = if (available.isEmpty() || available.any { it.id == selected }) selected else available.first().id
        if (resolved != selected) prefs.saveAppleCatalogSettings(baseUrl, resolved)
        return AppleStorefrontState(baseUrl, resolved, available, cache?.fetchedAtMs ?: 0)
    }
}