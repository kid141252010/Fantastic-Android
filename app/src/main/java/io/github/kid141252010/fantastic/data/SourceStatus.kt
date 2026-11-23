package io.github.kid141252010.fantastic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Read-only status request for the user-configured Fantastic server. */
object SourceStatus {
    private val json = Json { ignoreUnknownKeys = true }

    data class Wrapper(
        val httpCode: Int,
        val serverReachable: Boolean,
        val available: Boolean,
        val message: String,
        val regions: List<String>,
    )

    /** Accept only an HTTP(S) server URL, never credentials, query parameters, or fragments. */
    fun normalizeBaseUrl(value: String): String? {
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.host.isBlank() ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) return null
        return url.toString().trimEnd('/')
    }

    /** Follows no redirects so a status check can only contact the entered server. */
    suspend fun wrapper(client: OkHttpClient, baseUrl: String): Wrapper = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseUrl)?.toHttpUrlOrNull()
            ?: throw IOException("服务器地址无效；请输入不含账号、查询参数或片段的 HTTP(S) 地址")
        val statusUrl: HttpUrl = base.newBuilder().addPathSegment("status").build()
        val request = Request.Builder().url(statusUrl).get().build()
        val directClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        directClient.newCall(request).execute().use { response ->
            val payload = try {
                json.parseToJsonElement(response.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonObject
                    ?: throw IOException("状态响应不是 JSON 对象")
            } catch (e: Exception) {
                throw IOException("HTTP ${response.code}，状态响应不是有效 JSON", e)
            }
            val code = (payload["code"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val rawMessage = (payload["msg"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            val rawRegions = payload["regions"] as? kotlinx.serialization.json.JsonArray
            val message = rawMessage?.takeIf { it.isNotBlank() }?.filter { it >= ' ' && it != '\u007f' }
                ?.replace(Regex("(?i)https?://\\S+"), "[地址已隐藏]")
                ?.replace(Regex("(?i)(bearer\\s+)\\S+")) { "${it.groupValues[1]}[已隐藏]" }
                ?.take(120)
                ?: "HTTP ${response.code}"
            val regions = rawRegions.orEmpty().mapNotNull { value ->
                (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?.lowercase()
                    ?.takeIf { it.length == 2 && it.all { char -> char in 'a'..'z' } }
            }.distinct()
            Wrapper(
                httpCode = response.code,
                serverReachable = code != null && rawMessage != null && rawRegions != null,
                available = response.isSuccessful && code == 0,
                message = message,
                regions = regions,
            )
        }
    }
}
