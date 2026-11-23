package io.github.kid141252010.fantastic.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.MediaActionContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.Source
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Native host for Fantastic's existing Apple video and station pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMediaScreen(app: FantasticApp, hit: CatalogHit, onClose: () -> Unit) {
    val context = LocalContext.current
    val route = remember(hit, app.prefs.storefront) { appleMediaRoute(hit, app.prefs.storefront) }
    val server = remember(app.prefs.baseUrl) { configuredServer(app.prefs.baseUrl) }
    val initialError = when {
        hit.source != Source.Apple -> "Apple media pages require an Apple catalogue item."
        route == null -> "This Apple video or station link is not valid."
        server == null -> "The configured Fantastic server URL is invalid."
        else -> null
    }
    val ui = remember(route, server, initialError) { AppleMediaUi(initialError) }
    val host = remember(context, route, server, ui) {
        if (route != null && server != null) createAppleMediaHost(context, route, server, ui) else null
    }
    val webView = host?.webView
    val lifecycleOwner = LocalLifecycleOwner.current
    var showActions by remember(hit) { mutableStateOf(false) }

    DisposableEffect(host, route, server) {
        if (host != null && route != null && server != null) {
            host.webView.loadUrl(server.toString().trimEnd('/') + route.path)
        }
        onDispose { host?.dispose() }
    }
    DisposableEffect(lifecycleOwner, webView) {
        if (webView == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> webView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.onPause()
        }
    }

    fun handleBack() {
        if (ui.fullScreen) {
            host?.hideCustomView()
            return
        }
        val view = host?.webView
        if (view?.canGoBack() == true && server != null) {
            val history = view.copyBackForwardList()
            val previous = history.getItemAtIndex(history.currentIndex - 1)?.url
            if (previous != null && route != null && isAllowedMainFrame(server, Uri.parse(previous), route)) {
                view.goBack()
                return
            }
        }
        onClose()
    }

    BackHandler(enabled = true) { if (showActions) showActions = false else handleBack() }

    val title = when (hit.kind) {
        "station" -> "Apple Music 电台"
        else -> "Apple Music 视频"
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().let { if (ui.fullScreen) it else it.safeDrawingPadding() }) {
                if (!ui.fullScreen) {
                    TopAppBar(
                        title = { Text(hit.title.ifBlank { title }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { Md3BackButton(::handleBack) },
                        actions = {
                            IconButton(onClick = { showActions = true }) { Icon(Icons.Rounded.MoreHoriz, "更多操作") }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (host != null) {
                        AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
                    }
                    if (ui.loading && ui.error == null) {
                        Surface(
                            Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator()
                                Text("正在连接 Fantastic 媒体页…", Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    ui.error?.let { message ->
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("媒体页无法加载", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    message,
                                    Modifier.padding(top = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (host != null) {
                                        Button(onClick = {
                                            ui.error = null
                                            ui.loading = true
                                            host.webView.reload()
                                        }) { Text("重试") }
                                    }
                                    TextButton(onClick = onClose) { Text("关闭") }
                                }
                            }
                        }
                    }
                    if (ui.downloadBlocked && !ui.fullScreen) {
                        Card(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "此页面不支持保存到 Android；未保存任何文件。",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { ui.downloadBlocked = false }) { Text("知道了") }
                            }
                        }
                    }
                    if (ui.downloadBlocked && ui.fullScreen) {
                        Card(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                            Text(
                                "此页面不支持保存到 Android；未保存任何文件。",
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (showActions) GlassSheet(hit.title.ifBlank { title }, onDismissRequest = { showActions = false }, showHeader = false) {
                MediaActionContent(hit, app.library, emptyList(),
                    shareText = route?.let { media -> "${hit.title}\nhttps://music.apple.com/${media.storefront}/${media.kind}/_/${Uri.encode(media.id)}" },
                    onDismiss = { showActions = false })
            }
            }
        }
    }
}

private class AppleMediaUi(initialError: String?) {
    var loading by mutableStateOf(initialError == null)
    var error by mutableStateOf(initialError)
    var downloadBlocked by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
}

private data class AppleMediaRoute(val kind: String, val storefront: String, val id: String) {
    val path: String get() = "/https://music.apple.com/$storefront/$kind/_/$id"
}

/** Prefer the resource's official storefront only after validating its origin, type, and ID. */
private fun appleMediaRoute(hit: CatalogHit, configuredStorefront: String): AppleMediaRoute? {
    if (hit.source != Source.Apple) return null
    val kind = when (hit.kind) {
        "music-video" -> "music-video"
        "post" -> "post"
        "station" -> "station"
        else -> return null
    }
    val validId = when (kind) {
        "station" -> hit.id.matches(Regex("ra\\.[A-Za-z0-9._-]{1,128}"))
        else -> hit.id.matches(Regex("[0-9]{1,20}"))
    }
    if (!validId) return null

    val attributes = hit.attributes as? JsonObject
    val attributeUrl = (attributes?.get("url") as? JsonPrimitive)?.contentOrNull
    val verifiedStorefront = attributeUrl?.let { validatedStorefront(it, kind, hit.id) }
    val storefront = verifiedStorefront
        ?: configuredStorefront.takeIf { it.matches(Regex("[a-z]{2}")) }
        ?: "us"
    return AppleMediaRoute(kind, storefront, hit.id)
}

private fun validatedStorefront(value: String, expectedKind: String, expectedId: String): String? {
    return try {
        val uri = Uri.parse(value)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("music.apple.com", ignoreCase = true) ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.encodedQuery != null || uri.encodedFragment != null
        ) return null
        val match = APPLE_ATTRIBUTE_PATH.matchEntire(uri.encodedPath.orEmpty()) ?: return null
        val storefront = match.groupValues[1]
        val kind = match.groupValues[2]
        val id = match.groupValues[3]
        if (storefront.matches(Regex("[a-z]{2}")) && kind == expectedKind && id == expectedId) storefront else null
    } catch (_: Exception) {
        null
    }
}

private val APPLE_ATTRIBUTE_PATH = Regex(
    "/([a-z]{2})/(music-video|post|station)/(?:[A-Za-z0-9._~%+-]+/)?([0-9]{1,20}|ra\\.[A-Za-z0-9._-]{1,128})/?",
)

private fun configuredServer(value: String): Uri? {
    return try {
        val input = Uri.parse(value.trim().trimEnd('/'))
        val scheme = input.scheme?.lowercase()
        val port = input.port
        if (scheme !in setOf("http", "https") || input.host.isNullOrBlank() || input.userInfo != null ||
            (port != -1 && port !in 1..65535)
        ) null
        else input.buildUpon().scheme(scheme).clearQuery().fragment(null).build()
    } catch (_: Exception) {
        null
    }
}

private fun sameOrigin(expected: Uri, actual: Uri): Boolean =
    expected.scheme.equals(actual.scheme, ignoreCase = true) &&
        expected.host.equals(actual.host, ignoreCase = true) &&
        effectivePort(expected) == effectivePort(actual)

private fun effectivePort(uri: Uri): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

/** Main-frame document loads stay on the configured host and this narrow, canonical route shape. */
private fun isAllowedMainFrame(server: Uri, actual: Uri, route: AppleMediaRoute): Boolean {
    if (!sameOrigin(server, actual) || actual.userInfo != null ||
        actual.encodedQuery != null || actual.encodedFragment != null
    ) return false
    val basePath = server.encodedPath.orEmpty().trimEnd('/')
    val actualPath = actual.encodedPath.orEmpty()
    if (basePath.isNotEmpty() && !actualPath.startsWith("$basePath/")) return false
    val routePath = if (basePath.isEmpty()) actualPath else actualPath.removePrefix(basePath)
    return routePath == route.path
}

private fun AppleMediaUi.fail(message: String) {
    error = message
    loading = false
}

@SuppressLint("SetJavaScriptEnabled")
private fun createAppleMediaHost(
    context: Context,
    route: AppleMediaRoute,
    server: Uri,
    ui: AppleMediaUi,
): AppleMediaHost {
    val host = AppleMediaHost(context, ui)
    val webView = WebView(context)
    host.webView = webView
    host.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.mediaPlaybackRequiresUserGesture = true
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.javaScriptCanOpenWindowsAutomatically = false
    webView.settings.setSupportMultipleWindows(false)
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val allowed = request.isForMainFrame && isAllowedMainFrame(server, request.url, route)
            if (!allowed) {
                if (request.isForMainFrame) ui.fail("已阻止离开配置的 Fantastic 媒体页面。")
                return true
            }
            return false
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val allowed = isAllowedMainFrame(server, Uri.parse(url), route)
            if (!allowed) ui.fail("已阻止离开配置的 Fantastic 媒体页面。")
            return !allowed
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!isAllowedMainFrame(server, Uri.parse(url), route)) {
                ui.fail("已阻止离开配置的 Fantastic 媒体页面。")
                view.stopLoading()
            } else {
                ui.error = null
                ui.loading = true
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (view === host.webView && isAllowedMainFrame(server, Uri.parse(url), route)) {
                ui.error = null
                ui.loading = false
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) ui.fail(error.description?.toString() ?: "Fantastic 媒体页加载失败。")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            super.onReceivedHttpError(view, request, response)
            if (request.isForMainFrame) ui.fail("Fantastic 媒体页返回 HTTP ${response.statusCode}。")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            if (isAllowedMainFrame(server, Uri.parse(error.url), route)) ui.fail("Fantastic 媒体页的安全连接失败。")
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            host.showCustomView(view, callback)
        }

        override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) {
            host.showCustomView(view, callback)
        }

        override fun onHideCustomView() = host.hideCustomView()

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }
    webView.setDownloadListener { _, _, _, _, _ -> ui.downloadBlocked = true }
    return host
}

private class AppleMediaHost(context: Context, private val ui: AppleMediaUi) : FrameLayout(context) {
    lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var disposed = false

    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (disposed) {
            callback.onCustomViewHidden()
            return
        }
        customView?.let(::removeView)
        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        customViewCallback = callback
        removeAllViews()
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ui.fullScreen = true
    }

    fun hideCustomView() {
        val current = customView ?: return
        removeView(current)
        customView = null
        val callback = customViewCallback
        customViewCallback = null
        if (!disposed && ::webView.isInitialized) {
            addView(webView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ui.fullScreen = false
        callback?.onCustomViewHidden()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        customView?.let(::removeView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "(function(){try{if(window.AmApp&&AmApp.player)AmApp.player.pause()}catch(e){};" +
                    "document.querySelectorAll('audio,video').forEach(function(m){try{m.pause();m.removeAttribute('src');m.load()}catch(e){}})})()",
                null,
            )
            webView.onPause()
            webView.stopLoading()
            removeView(webView)
            webView.destroy()
        }
        removeAllViews()
        ui.fullScreen = false
    }

}
