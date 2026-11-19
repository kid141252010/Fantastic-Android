package io.github.kid141252010.fantastic.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * 歌词页的 WebView：用 WebViewAssetLoader 把 assets/lyrics 映射到
 * https://appassets.androidplatform.net/assets/lyrics/（ES 模块不能从 file:// 加载），
 * 不允许访问文件、不允许跳转。bridge 以 FantasticBridge 暴露给页面（ready / seek / status）。
 * 页面主文档或脚本加载失败时调用 onLoadError。
 */
object LyricsWebView {
    const val PAGE = "https://appassets.androidplatform.net/assets/lyrics/index.html"
    private const val TAG = "FantasticLyrics"

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, bridge: Any, onLoadError: (String) -> Unit = {}): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", ModuleAwareAssets(WebViewAssetLoader.AssetsPathHandler(context)))
            .build()
        return WebView(context).apply {
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

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    Log.w(TAG, "load error ${request.url}: ${error.errorCode} ${error.description}")
                    if (request.isForMainFrame) onLoadError("页面加载失败：${error.description}")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    Log.w(TAG, "http error ${request.url}: ${response.statusCode}")
                    if (request.isForMainFrame) onLoadError("页面加载失败：HTTP ${response.statusCode}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(bridge, "FantasticBridge")
            loadUrl(PAGE)
        }
    }

    /**
     * AssetsPathHandler 按扩展名猜 MIME，部分系统不认识 .mjs 会给 text/plain，
     * 而 ES 模块脚本必须是 JavaScript MIME，否则整个歌词页不执行
     */
    private class ModuleAwareAssets(private val inner: WebViewAssetLoader.PathHandler) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            // 词典以 .gz.bin 打包（aapt 不保留 .gz 资源原名），页面仍按 .gz 请求
            if (path.endsWith(".gz")) {
                val response = inner.handle("$path.bin") ?: return null
                response.mimeType = "application/gzip"
                response.encoding = null
                return response
            }
            val response = inner.handle(path) ?: return null
            when {
                path.endsWith(".mjs") -> response.mimeType = "text/javascript"
                path.endsWith(".css") -> response.mimeType = "text/css"
                path.endsWith(".html") -> response.mimeType = "text/html"
            }
            if (response.mimeType?.startsWith("text/") == true) response.encoding = "utf-8"
            return response
        }
    }
}
