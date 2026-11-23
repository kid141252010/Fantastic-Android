package io.github.kid141252010.fantastic.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** Exports one hosted Apple variant through the page's existing decrypt/OPFS bridge. */
internal class AppleOfflineExporter(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun export(
        request: FullAudioRequest,
        target: File,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): AudioFileInfo = withContext(Dispatchers.Main.immediate) {
        val server = request.server.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
            ?: throw IOException("服务器地址无效")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw IOException("Apple Music 下载需要更新 Android System WebView")
        }
        val binaryMessages = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        val home = server.newBuilder().encodedPath(server.encodedPath.trimEnd('/') + "/").query(null).fragment(null).build()
        val origin = server.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        val chunks = Channel<ByteArray>(1)
        var expected = 0
        var pageError: String? = null
        val view = WebView(context)
        try {
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            view.setDownloadListener { _, _, _, _, _ -> }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!samePage(home, url)) {
                        view.stopLoading()
                        pageError = "已阻止下载页导航到其他地址"
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) pageError = "Apple Music 下载页面加载失败"
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) pageError = "下载页面 HTTP ${response.statusCode}"
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    pageError = "WebView 下载进程已退出"
                    chunks.close(IOException(pageError))
                    return true
                }
            }
            WebViewCompat.addWebMessageListener(view, "FantasticOfflineFile", setOf(origin)) { source, message, sourceOrigin, main, _ ->
                if (source !== view || !main || !sameOrigin(server, sourceOrigin.toString()) ||
                    !samePage(home, view.url) || expected <= 0
                ) return@addWebMessageListener
                val bytes = when (message.type) {
                    WebMessageCompat.TYPE_ARRAY_BUFFER -> if (binaryMessages) message.arrayBuffer else null
                    WebMessageCompat.TYPE_STRING -> {
                        val text = message.data.orEmpty()
                        when {
                            text.startsWith("error:") -> {
                                chunks.close(IOException("无法读取下载文件"))
                                null
                            }
                            text.startsWith("base64:") && text.length <= 7 + 4 * ((expected + 2) / 3) ->
                                runCatching { Base64.decode(text.substring(7), Base64.NO_WRAP) }.getOrNull()
                            else -> null
                        }
                    }
                    else -> null
                }
                if (bytes?.size == expected) {
                    expected = 0
                    chunks.trySend(bytes)
                }
            }
            view.loadUrl(home.toString())
            val ready = withTimeoutOrNull(20_000L) { awaitReady(view, home, request.appleGroup != null) { pageError }; true }
            if (ready != true) throw IOException("Apple Music 下载页面准备超时")
            withTimeoutOrNull(20 * 60_000L) {
                view.evaluateJavascript(startScript(request), null)
                val state = awaitResult(view, home, request, { pageError }, reserve, progress)
                val size = state.optLong("size").takeIf { it > 0 } ?: throw IOException("Apple Music 下载文件为空")
                if (size > request.maxBytes) throw IOException("下载文件过大")
                withContext(Dispatchers.IO) { reserve(size) }
                var offset = 0L
                while (offset < size) {
                    val count = (size - offset).coerceAtMost(CHUNK.toLong()).toInt()
                    expected = count
                    view.evaluateJavascript(chunkScript(request.track.id, offset, count, binaryMessages), null)
                    val bytes = withTimeoutOrNull(15_000L) { chunks.receive() } ?: throw IOException("读取 Apple Music 下载文件超时")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(target, offset > 0).use { output -> output.write(bytes) }
                    }
                    offset += count
                    progress(90 + (9 * offset / size).toInt())
                }
                if (target.length() != size) throw IOException("下载文件不完整")
                AudioFileInfo(size, state.optLong("durationMs").takeIf { it > 0 }, state.optString("type").ifBlank { null }, null, request.quality)
            } ?: throw IOException("Apple Music 整曲导出超时")
        } finally {
            chunks.close()
            try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try { cleanup(view, home) } catch (_: Throwable) { }
                }
            } finally {
                runCatching { WebViewCompat.removeWebMessageListener(view, "FantasticOfflineFile") }
                view.destroy()
            }
        }
    }

    private suspend fun awaitReady(view: WebView, home: HttpUrl, exactVariant: Boolean, pageError: () -> String?) {
        val interfaces = if (exactVariant) {
            "typeof window.AmWrapper?.songMaster==='function' && typeof window.AmDecrypt?.openTrack==='function' && typeof window.AmDecrypt?.download==='function'"
        } else "typeof window.AmDownloads?.add==='function'"
        while (true) {
            pageError()?.let { throw IOException(it) }
            val url = view.url
            if (url != null && url != "about:blank") {
                if (!samePage(home, url)) throw IOException("下载页面已离开服务器")
                if (view.evalString(interfaces) == "true") return
            }
            delay(100)
        }
    }

    private suspend fun awaitResult(
        view: WebView,
        home: HttpUrl,
        request: FullAudioRequest,
        pageError: () -> String?,
        reserve: (Long) -> Unit,
        progress: (Int?) -> Unit,
    ): JSONObject {
        var approved = false
        val id = JSONObject.quote(request.track.id)
        val snapshot = """(function(){
            const state=window.fantasticNativeDownload;
            if (!state) return '{}';
            const task=state.job?.tasks.find(task=>String(task.id)===$id);
            const file=state.result?.file || task?.result?.file;
            const failed=['error','cancelled','skipped'].includes(task?.state);
            return JSON.stringify({
                error:state.error || (failed ? (task.error||'Apple Music 下载失败') : undefined),
                trackSize:state.trackSize,durationMs:state.durationMs,
                total:state.total||task?.total,doneBytes:state.doneBytes||task?.done,
                done:state.done || (task?.state==='done' && !!file),size:file?.size,type:file?.type
            });
        })()"""
        while (true) {
            pageError()?.let { throw IOException(it) }
            if (!samePage(home, view.url)) throw IOException("下载页面已离开服务器")
            val raw = view.evalString(snapshot)
                ?: throw IOException("Apple Music 下载页面没有响应")
            val state = JSONObject(raw)
            state.optString("error").takeIf(String::isNotBlank)?.let { throw IOException(it.take(200)) }
            val known = state.optLong("trackSize").takeIf { it > 0 }
            if (request.appleGroup != null && known != null && !approved) {
                if (known > request.maxBytes) throw IOException("下载文件过大")
                val duration = state.optLong("durationMs")
                val expected = maxOf(request.track.durationMs, request.expectedDurationMs)
                if (duration <= 0L || expected > 0L && duration + 5_000L < expected) {
                    throw IOException("Apple Music 音轨时长不完整")
                }
                withContext(Dispatchers.IO) { reserve(known) }
                approved = true
                view.evaluateJavascript("window.fantasticNativeDownload?.start?.()", null)
            }
            val total = state.optLong("total").takeIf { it > 0 }
            progress(total?.let { (90L * state.optLong("doneBytes").coerceIn(0, it) / it).toInt() })
            if (state.optBoolean("done")) return state
            delay(250)
        }
    }

    private fun startScript(request: FullAudioRequest): String {
        val id = JSONObject.quote(request.track.id)
        val country = JSONObject.quote(request.storefront)
        val quality = JSONObject.quote(request.quality)
        val group = request.appleGroup
        return if (group != null) {
            val groupValue = JSONObject.quote(group)
            val codecs = JSONObject.quote(request.appleCodecs.orEmpty())
            """(async function(){
                const s=window.fantasticNativeDownload={controller:new AbortController(),done:false};
                try {
                    const signal=s.controller.signal;
                    await AmWrapper.throttle.wait(signal);
                    const master=await AmWrapper.songMaster($id,signal);
                    const variants=(master?.variants||[]).filter(v=>v.group_id===$groupValue && String(v.codecs||'')===$codecs);
                    if(variants.length!==1) throw new Error('Apple Music 音频变体不唯一');
                    const track=await AmDecrypt.openTrack(variants[0].url,signal);
                    if(!track || !Number.isFinite(track.size) || track.size<=0) throw new Error('Apple Music 音频大小无效');
                    s.trackSize=track.size;s.durationMs=Math.round((track.duration||0)*1000);s.total=track.size;
                    s.start=async()=>{
                        delete s.start;
                        try {
                            s.result=await AmDecrypt.download(track,{
                                signal,memory:false,onProgress:(done,total)=>{s.doneBytes=done;s.total=total||track.size;}
                            });
                        } catch(error) { s.error=String(error?.message||error); }
                        s.done=true;
                    };
                }catch(e){s.error=String(e&&e.message||e);s.done=true;}
            })()"""
        } else {
            """(async function(){
                const s=window.fantasticNativeDownload={controller:new AbortController(),done:false};
                try {
                    const signal=s.controller.signal;
                    const job=await AmDownloads.add({source:'apple',kind:'song',id:$id,country:$country,select:[$id],codec:$quality,embed:[],saveCover:false});
                    if(!job) throw new Error('未建立下载任务');
                    if(signal.aborted){AmDownloads.remove(job);return;}
                    job.options.fallback=false;
                    job.options.embed=[];
                    job.options.saveCover=false;
                    s.job=job;
                }catch(e){s.error=String(e&&e.message||e);s.done=true;}
            })()"""
        }
    }

    private fun chunkScript(id: String, offset: Long, count: Int, binary: Boolean): String {
        val quoted = JSONObject.quote(id)
        return """(async function(){try{const s=window.fantasticNativeDownload;const f=s?.result?.file||s?.job?.tasks.find(t=>t.id===$quoted)?.result?.file;if(!f)throw new Error('missing file');const p=f.slice($offset,${offset + count});if($binary)window.FantasticOfflineFile.postMessage(await p.arrayBuffer());else{const r=new FileReader();r.onload=()=>window.FantasticOfflineFile.postMessage('base64:'+String(r.result).split(',')[1]);r.onerror=()=>window.FantasticOfflineFile.postMessage('error:read');r.readAsDataURL(p)}}catch(e){window.FantasticOfflineFile.postMessage('error:read')}})()"""
    }

    private suspend fun cleanup(view: WebView, home: HttpUrl) {
        if (!samePage(home, view.url)) return
        view.evaluateJavascript("""(function(){const s=window.fantasticNativeDownload;if(!s)return;s.controller?.abort();if(s.cleanupStarted)return;s.cleanupStarted=true;Promise.resolve().then(()=>s.result?.dispose?.()).catch(()=>{}).finally(()=>s.cleanupDone=true);if(s.job)window.AmDownloads?.remove(s.job)})()""", null)
        withTimeoutOrNull(2_000L) {
            while (view.evalString("String(!!window.fantasticNativeDownload?.cleanupDone)") != "true") delay(50)
        }
    }

    private suspend fun WebView.evalString(script: String): String? = suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { raw ->
            if (continuation.isActive) continuation.resume(runCatching { JSONTokener(raw.orEmpty()).nextValue().toString() }.getOrNull())
        }
    }

    private fun sameOrigin(expected: HttpUrl, url: String?): Boolean {
        val actual = url?.toHttpUrlOrNull() ?: return false
        return actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port &&
            actual.username.isEmpty() && actual.password.isEmpty()
    }

    private fun samePage(expected: HttpUrl, url: String?): Boolean = sameOrigin(expected, url) &&
        url?.toHttpUrlOrNull()?.encodedPath == expected.encodedPath

    private companion object { const val CHUNK = 256 * 1024 }
}
