package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.AppleLyricsOptions
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.NeteaseApi
import io.github.kid141252010.fantastic.data.NeteaseLyrics
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.QqLyricsDocument
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.playback.PlaybackCacheKey
import io.github.kid141252010.fantastic.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Preload the lyric document on track change; only an attached surface receives clock updates. */
class LyricsController(
    context: Context,
    private val api: FantasticApi,
    private val apple: AppleCatalogApi,
    private val netease: NeteaseApi,
    private val player: PlayerConnection,
    private val prefs: Prefs,
) {
    enum class Status { None, Loading, Ready, Missing, Failed }

    private val scope = MainScope()
    private val parser = LyricParser()
    private val cache = LyricsCache(context)
    private val qqJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val _status = MutableStateFlow(Status.None)
    val status = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _diag = MutableStateFlow("")
    val diag = _diag.asStateFlow()
    internal data class Annotations(val translation: Boolean = false, val pronunciation: Boolean = false)
    private val _annotations = MutableStateFlow(Annotations())
    internal val annotations = _annotations.asStateFlow()
    private val _currentLyricMediaId = MutableStateFlow<String?>(null)
    internal val currentLyricMediaId = _currentLyricMediaId.asStateFlow()
    private var loadJob: Job? = null
    private var loadedMediaId: String? = null
    private var pendingAppleRefreshId: String? = null
    private var syncJob: Job? = null
    private var lastSeekGeneration = -1
    private var currentSong: LyricSong? = null
    private var surface: NativeLyricsView? = null
    internal val attachedView get() = surface
    private var translation = true
    private var pronunciation = false

    internal fun createView(context: Context): NativeLyricsView = NativeLyricsView(context).apply {
        onSeek = player::seekLyric
        setSong(currentSong)
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                surface = this@apply
                setSong(currentSong)
                showAnnotations(translation = this@LyricsController.translation, pronunciation = this@LyricsController.pronunciation)
                lastSeekGeneration = -1
                syncJob?.cancel()
                syncJob = null
                val lifecycle = v.findViewTreeLifecycleOwner()?.lifecycle ?: return
                syncJob = scope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            sync()
                            delay(500)
                        }
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (surface != v) return
                syncJob?.cancel()
                syncJob = null
                surface = null
            }
        })
    }

    internal fun shareLines(): List<String> = currentSong?.lines?.map { it.main.text }.orEmpty()

    fun showAnnotations(translation: Boolean, pronunciation: Boolean) {
        this.translation = translation
        this.pronunciation = pronunciation
        surface?.showAnnotations(translation, pronunciation)
    }

    init {
        scope.launch {
            var first = true
            var lastPlaying = false
            var lastGeneration = -1
            player.state.collect { state ->
                reconcileLoad(state.mediaId, state.isPlaying)
                if (first || state.isPlaying != lastPlaying || state.seekGeneration != lastGeneration) {
                    first = false
                    lastPlaying = state.isPlaying
                    lastGeneration = state.seekGeneration
                    sync()
                }
            }
        }
    }

    internal fun onAppleLyricsOptionsChanged() {
        val state = player.state.value
        val id = state.mediaId ?: return
        if (!id.startsWith("apple:") || id.length == "apple:".length) return
        pendingAppleRefreshId = id
        reconcileLoad(id, state.isPlaying)
    }

    private fun reconcileLoad(mediaId: String?, isPlaying: Boolean) {
        if (pendingAppleRefreshId != mediaId) pendingAppleRefreshId = null
        val refresh = pendingAppleRefreshId != null
        if (loadedMediaId == mediaId && !(refresh && isPlaying)) return
        loadedMediaId = mediaId
        pendingAppleRefreshId = null
        load(mediaId, officialOnly = refresh)
    }

    private class LyricRequest(
        val result: Result<String?>,
        val startedAt: Long,
        val finishedAt: Long,
        val qq: QqLyricsDocument? = null,
    )
    private suspend fun requestLyrics(source: String, id: String, options: AppleLyricsOptions): LyricRequest {
        val startedAt = SystemClock.elapsedRealtime()
        var qq: QqLyricsDocument? = null
        val result: Result<String?> = try {
            Result.success(when (source) {
                "amll" -> apple.amllSongTtml(id)
                "apple" -> apple.songTtml(id, options)
                "ncm" -> Json.encodeToString(NeteaseLyrics.serializer(), netease.lyrics(id))
                else -> api.qqLyrics(id).also { qq = it }.payload
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        return LyricRequest(result, startedAt, SystemClock.elapsedRealtime(), qq)
    }


    private fun load(mediaId: String?, officialOnly: Boolean) {
        loadJob?.cancel()
        _currentLyricMediaId.value = mediaId
        currentSong = null
        _annotations.value = Annotations()
        surface?.setSong(null)
        _error.value = null
        val parts = mediaId?.split(':', limit = 2)
        val kind = parts?.firstOrNull()
        val id = parts?.getOrNull(1)
        if (kind !in setOf("qq", "apple", "ncm") || id.isNullOrEmpty()) {
            _status.value = Status.None
            return
        }
        val options = if (kind == "apple") prefs.appleLyricsOptions else AppleLyricsOptions.DEFAULT
        val preferAmll = kind == "apple" && !officialOnly && options == AppleLyricsOptions.DEFAULT
        val cacheKey = lyricCacheKey(kind!!, id, options, preferAmll)
        _status.value = Status.Loading
        loadJob = scope.launch {
            val loadStartedAt = SystemClock.elapsedRealtime()
            val cached = cacheKey?.let { cache.find(it) }
            if (cached != null && player.state.value.mediaId == mediaId &&
                cacheKey == lyricCacheKey(kind, id, options, preferAmll)) {
                publishSong(cached.song)
                note(mediaId, "来源=${cached.source} 阶段=歌词缓存命中 ${cached.song.lines.size} 行")
                return@launch
            }
            var requestStartedAt = loadStartedAt
            var requestFinishedAt = requestStartedAt
            var parseStartedAt = loadStartedAt
            var stage = "请求"
            var source = if (preferAmll) "amll" else kind!!
            var fallback = ""
            val optionsNote = if (kind == "apple") " language=${options.language} script=${options.script}" else ""
            if (preferAmll) note(mediaId, "来源=amll,apple 阶段=请求 模式=并行$optionsNote")
            val amllRequest = if (preferAmll) async { requestLyrics("amll", id, options) } else null
            val officialRequest = if (preferAmll) async { requestLyrics("apple", id, options) } else null
            try {
                var payload: String?
                var song: LyricSong?
                var parseElapsedMs: Long
                while (true) {
                    requestStartedAt = SystemClock.elapsedRealtime()
                    requestFinishedAt = requestStartedAt
                    stage = "请求"
                    parseElapsedMs = 0L
                    val directOptions = if (source == "apple" && officialRequest == null) optionsNote else ""
                    note(mediaId, "来源=$source 阶段=请求$directOptions$fallback")
                    try {
                        val response = when (source) {
                            "amll" -> amllRequest!!.await()
                            "apple" -> officialRequest?.await() ?: requestLyrics(source, id, options)
                            else -> requestLyrics(source, id, options)
                        }
                        requestStartedAt = response.startedAt
                        requestFinishedAt = response.finishedAt
                        payload = response.result.getOrThrow()
                        val qqDocument = response.qq
                        stage = "解析"
                        if (payload != null) note(mediaId,
                            "来源=$source 阶段=解析 请求耗时=${requestFinishedAt - requestStartedAt}ms$fallback")
                        parseStartedAt = SystemClock.elapsedRealtime()
                        song = payload?.let {
                            when {
                                source == "apple" -> parser.parse("apple", it, language = options.language)
                                source == "qq" && qqDocument != null -> parseQqLyrics(qqDocument)
                                else -> parser.parse(kind!!, it)
                            }
                        }
                        parseElapsedMs = if (payload == null) 0L else SystemClock.elapsedRealtime() - parseStartedAt
                        if (source != "amll" || song != null) break
                        fallback = " AMLL回退=未收录或无有效歌词"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (source != "amll") throw e
                        if (stage == "解析") parseElapsedMs = SystemClock.elapsedRealtime() - parseStartedAt
                        fallback = " AMLL回退=${stage}失败：${(e.message ?: e.javaClass.simpleName).take(320)}"
                    }
                    val requestElapsedMs = requestFinishedAt - requestStartedAt
                    note(mediaId, "来源=amll 阶段=$stage 请求耗时=${requestElapsedMs}ms " +
                        "解析耗时=${parseElapsedMs}ms$fallback")
                    source = "apple"
                }
                if (!isActive || player.state.value.mediaId != mediaId) return@launch
                publishSong(song)
                note(mediaId, "来源=$source 阶段=完成 请求耗时=${requestFinishedAt - requestStartedAt}ms " +
                    "解析耗时=${parseElapsedMs}ms 总耗时=${SystemClock.elapsedRealtime() - loadStartedAt}ms " +
                    "${song?.lines?.size ?: 0} 行，${payload?.length ?: 0} 字符$fallback")
                if (song != null && cacheKey != null && cacheKey == lyricCacheKey(kind, id, options, preferAmll)) {
                    val complete = CachedLyrics(source, song)
                    scope.launch { cache.store(cacheKey, complete) }
                }
            } catch (e: TimeoutCancellationException) {
                if (!isActive || player.state.value.mediaId != mediaId) return@launch
                val failedAt = SystemClock.elapsedRealtime()
                fail(mediaId, "歌词解析超时", "来源=$source 阶段=解析 请求耗时=${requestFinishedAt - requestStartedAt}ms " +
                    "解析耗时=${failedAt - parseStartedAt}ms " +
                    "总耗时=${failedAt - loadStartedAt}ms$fallback 歌词解析超时")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive || player.state.value.mediaId != mediaId) return@launch
                val failedAt = SystemClock.elapsedRealtime()
                val message = "歌词加载失败（${stage}阶段）：${e.message ?: e.javaClass.simpleName}"
                val timing = if (stage == "请求") "请求耗时=${requestFinishedAt - requestStartedAt}ms"
                    else "请求耗时=${requestFinishedAt - requestStartedAt}ms 解析耗时=${failedAt - parseStartedAt}ms"
                fail(mediaId, message, "来源=$source 阶段=$stage $timing " +
                    "总耗时=${failedAt - loadStartedAt}ms$fallback $message")
            } finally {
                amllRequest?.cancel()
                officialRequest?.cancel()
            }
        }
    }

    private fun lyricCacheKey(kind: String, id: String, options: AppleLyricsOptions, preferAmll: Boolean): String? {
        val source = Source.entries.firstOrNull { it.mediaPrefix == kind } ?: return null
        val key = PlaybackCacheKey.from(prefs.baseUrl, prefs.storefront, source, id) ?: return null
        return "${key.server}\u0000${key.storefront}\u0000$kind\u0000$id\u0000${options.language}\u0000${options.script}\u0000$preferAmll"
    }

    private fun publishSong(song: LyricSong?) {
        currentSong = song
        if (song != null) {
            var translated = false
            var pronounced = false
            for (line in song.lines) {
                val main = line.main
                val backing = line.background
                translated = translated || main.hasTranslation() || backing.hasTranslation()
                pronounced = pronounced || main.hasPronunciation() || backing.hasPronunciation()
                if (translated && pronounced) break
            }
            _annotations.value = Annotations(translated, pronounced)
        }
        surface?.setSong(song)
        lastSeekGeneration = -1
        sync()
        _status.value = if (song == null) Status.Missing else Status.Ready
    }

    private suspend fun parseQqLyrics(document: QqLyricsDocument): LyricSong? {
        val root = try {
            qqJson.parseToJsonElement(document.payload) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val candidates = root?.get("candidates") as? JsonArray
        val validCandidates = candidates?.filter(::isValidQqCandidate)
        val rawRoma = root?.get("rawRoma")?.let(::isTrueJsonBoolean) == true
        val rawTrans = root?.get("rawTrans")?.let(::isTrueJsonBoolean) == true
        val qrcCandidates = validCandidates?.filter { qqCandidateSource(it) == "qrc" }.orEmpty()
        if (root == null || validCandidates == null || (!rawRoma && !rawTrans) || qrcCandidates.isEmpty()) {
            return parser.parse("qq", document.payload)
        }

        val amllCandidates = validCandidates.filter { qqCandidateSource(it) == "amll" }
        if (amllCandidates.isNotEmpty()) {
            val amllSong = tryParseQqSummary(root, amllCandidates)
            if (amllSong != null) return amllSong
        }

        val annotations = api.qqQrcAnnotations(document, rawRoma, rawTrans)
        val enriched = root.toMutableMap()
        enriched.putAll(annotations)
        return parser.parse("qq", payload = JsonObject(enriched))
    }

    private suspend fun tryParseQqSummary(root: JsonObject, candidates: List<JsonElement>): LyricSong? {
        val payload = JsonObject(root.toMutableMap().apply { put("candidates", JsonArray(candidates)) })
        return try {
            parser.parse("qq", payload = payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidQqCandidate(element: JsonElement): Boolean {
        val candidate = element as? JsonObject ?: return false
        return qqCandidateSource(candidate) != null && !qqCandidateText(candidate).isNullOrBlank()
    }

    private fun qqCandidateSource(element: JsonElement): String? =
        (element as? JsonObject)?.get("source")?.let(::qqString)

    private fun qqCandidateText(element: JsonObject): String? = element["text"]?.let(::qqString)

    private fun qqString(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun isTrueJsonBoolean(element: JsonElement): Boolean {
        val primitive = element as? JsonPrimitive ?: return false
        return !primitive.isString && primitive.booleanOrNull == true
    }

    private fun sync() {
        val view = surface ?: return
        val state = player.state.value
        val seek = state.seekGeneration != lastSeekGeneration
        lastSeekGeneration = state.seekGeneration
        view.sync(player.position(), state.isPlaying, seek)
    }

    private fun fail(id: String?, message: String, diagnostics: String = message) {
        _error.value = message
        _status.value = Status.Failed
        note(id, diagnostics)
    }

    private fun LyricVoice.hasTranslation() = translation.isNotBlank() && !translation.equals(text, ignoreCase = true)
    private fun LyricVoice.hasPronunciation() = pronunciation.isNotBlank() || tokens.any { it.pronunciation.isNotBlank() }

    private fun note(id: String?, event: String) {
        val diagnostic = "歌曲=${id ?: "-"} 状态=${_status.value} 渲染=Android Canvas / RecyclerView $event"
        _diag.value = diagnostic
        Log.d("FantasticLyrics", diagnostic)
    }
}
