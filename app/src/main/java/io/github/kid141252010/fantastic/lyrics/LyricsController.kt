package io.github.kid141252010.fantastic.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
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
 * 页面每帧自己外推，所以锚点不用很密。
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

    private var pageReady = false
    /** 页面没加载完时先存着，加载完再推送；"" 表示清空 */
    private var pending: String? = null
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var lastSeekGeneration = -1

    val webView: WebView by lazy {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        createWebView(loader)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(loader: WebViewAssetLoader): WebView =
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // 歌词页不跳转到任何地方
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            addJavascriptInterface(Bridge(), "FantasticBridge")
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = startSync()
                override fun onViewDetachedFromWindow(v: View) {
                    syncJob?.cancel()
                }
            })
            loadUrl(PAGE)
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
    }

    private fun load(mediaId: String?) {
        loadJob?.cancel()
        push("")
        val mid = mediaId?.takeIf { it.startsWith("qq:") }?.removePrefix("qq:")
        if (mid == null) {
            _status.value = Status.None
            return
        }
        _status.value = Status.Loading
        loadJob = scope.launch {
            try {
                push(api.qqLyricsJson(mid))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

        @JavascriptInterface
        fun status(value: String) {
            scope.launch { _status.value = if (value == "ready") Status.Ready else Status.Missing }
        }
    }

    private companion object {
        const val PAGE = "https://appassets.androidplatform.net/assets/lyrics/index.html"
    }
}
