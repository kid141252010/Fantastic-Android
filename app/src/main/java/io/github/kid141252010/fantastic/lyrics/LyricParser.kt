package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DOMParser consumes the vendored upstream TTML dialect. No HTML or JavaScript is built from lyric text. */
internal class LyricParser(context: Context) {
    private val ready = CompletableDeferred<Unit>()
    private val web = LyricsWebView.create(context, Bridge()) { fail(it) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parse(kind: String, payload: String, language: String = "zh-Hans"): LyricSong? = withTimeout(15_000) {
        ready.await()
        val args = listOf(kind, payload, language).joinToString(",", transform = JSONObject::quote)
        suspendCancellableCoroutine { result ->
            web.evaluateJavascript("parseFantasticLyrics($args)") { value ->
                if (!result.isActive) return@evaluateJavascript
                runCatching {
                    if (value == null || value == "null") return@runCatching null
                    JSONObject(value).optString("error").takeIf(String::isNotEmpty)?.let { error(it) }
                    json.decodeFromString<LyricSong>(value)
                }.fold(result::resume, result::resumeWithException)
            }
        }
    }

    fun close() = web.destroy()

    private fun fail(message: String) { ready.completeExceptionally(IllegalStateException(message)) }

    private inner class Bridge {
        @JavascriptInterface fun ready() { ready.complete(Unit) }
        @JavascriptInterface fun status(message: String) = fail(message.removePrefix("error:"))
    }
}
