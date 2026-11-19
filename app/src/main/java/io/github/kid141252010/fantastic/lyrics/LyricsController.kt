package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 歌词：一个常驻的 WebView 跑 AMLL（assets/lyrics），全屏播放页打开歌词时挂上去。
 * 切歌时就预取歌词；挂在窗口上时每 500ms（以及播放 / 暂停、跳转时立刻）推送一次播放进度锚点，
 * 页面每帧自己外推，所以锚点不用很密。任何一步失败都把原因放进 [error]，界面上直接显示。
 */
class LyricsController(
    private val context: Context,
    private val api: FantasticApi,
    private val player: PlayerConnection,
) {
    enum class Status { None, Loading, Ready, Missing, Failed }

    private val scope = MainScope()
    private val _status = MutableStateFlow(Status.None)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    /** Failed 时的原因（网络、页面加载、脚本错误） */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pageReady = false
    private var pageError: String? = null
    /** 页面没加载完时先存着，加载完再推送；"" 表示清空 */
    private var pending: String? = null
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var lastSeekGeneration = -1

    val webView: WebView by lazy {
        LyricsWebView.create(context, Bridge(), onLoadError = { fail(it) }).apply {
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = startSync()
                override fun onViewDetachedFromWindow(v: View) {
                    syncJob?.cancel()
                }
            })
        }
    }

    init {
        scope.launch {
            player.state.map { it.mediaId }.distinctUntilChanged().collect { load(it) }
        }
        scope.launch {
            player.state.map { it.isPlaying to it.seekGeneration }.distinctUntilChanged().collect {
                if (syncJob?.isActive == true) sync()
            }
        }
        // 页面 15 秒还没跑起来（脚本没执行）就报出来，不要一直空白
        scope.launch {
            webView
            delay(15_000)
            if (!pageReady && pageError == null) fail("歌词页脚本没有运行（WebView ${webViewVersion()}）")
        }
    }

    private fun fail(message: String) {
        pageError = pageError ?: message
        _error.value = message
        _status.value = Status.Failed
    }

    private fun webViewVersion(): String =
        WebView.getCurrentWebViewPackage()?.versionName ?: "未知版本"

    private fun load(mediaId: String?) {
        loadJob?.cancel()
        push("")
        val mid = mediaId?.takeIf { it.startsWith("qq:") }?.removePrefix("qq:")
        if (mid == null) {
            _status.value = Status.None
            return
        }
        pageError?.let { fail(it); return }
        _status.value = Status.Loading
        _error.value = null
        loadJob = scope.launch {
            try {
                push(api.qqLyricsJson(mid))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "歌词请求失败：${e.message ?: e.javaClass.simpleName}"
                _status.value = Status.Failed
            }
        }
    }

    private fun push(json: String) {
        if (!pageReady) {
            pending = json
            webView // 确保页面开始加载
            return
        }
        val arg = if (json.isEmpty()) "null" else JSONObject.quote(json)
        webView.evaluateJavascript("fantastic.setLyrics($arg)", null)
    }

    private fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            lastSeekGeneration = -1 // 挂上时直接跳到当前位置
            while (isActive) {
                sync()
                delay(500)
            }
        }
    }

    private fun sync() {
        if (!pageReady) return
        val s = player.state.value
        val seek = s.seekGeneration != lastSeekGeneration
        lastSeekGeneration = s.seekGeneration
        webView.evaluateJavascript("fantastic.sync(${player.position()},${s.isPlaying},$seek)", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ready() {
            scope.launch {
                pageReady = true
                pending?.let { push(it) }
                pending = null
                lastSeekGeneration = -1
                sync()
            }
        }

        @JavascriptInterface
        fun seek(ms: Double) {
            scope.launch { player.seekTo(ms.toLong()) }
        }

        /** "ready" | "missing" | "error:<原因>" */
        @JavascriptInterface
        fun status(value: String) {
            scope.launch {
                when {
                    value == "ready" -> { _error.value = null; _status.value = Status.Ready }
                    value == "missing" -> _status.value = Status.Missing
                    value.startsWith("error:") -> {
                        _error.value = "歌词页出错：${value.removePrefix("error:")}"
                        _status.value = Status.Failed
                    }
                }
            }
        }
    }
}
