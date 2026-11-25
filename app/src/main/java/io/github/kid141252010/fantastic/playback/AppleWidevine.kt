package io.github.kid141252010.fantastic.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import io.github.kid141252010.fantastic.data.FantasticApi
import io.github.kid141252010.fantastic.data.SourceStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.util.Base64
import java.io.IOException
import java.util.UUID

/** Widevine license relay for the Apple AAC-LC-only path; never retries another DRM system. */
@UnstableApi
internal class AppleWidevineCallback(
    api: FantasticApi,
    server: String,
    private val adamId: String,
    private val uri: String,
) : MediaDrmCallback {
    private val client = api.client
    private val endpoint = SourceStatus.normalizeBaseUrl(server)?.toHttpUrlOrNull()
        ?.newBuilder()?.addPathSegment("mv")?.addPathSegment("license")?.build()
        ?: throw IOException("服务器地址无效")

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        throw IOException(LICENSE_REJECTED)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        if (uuid != C.WIDEVINE_UUID) throw IOException(LICENSE_REJECTED)
        val payload = JSONObject()
            .put("adamId", adamId)
            .put("challenge", Base64.encodeToString(request.data, Base64.NO_WRAP))
            .put("uri", uri)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder().url(endpoint).post(payload).build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException(LICENSE_REJECTED)
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: throw IOException(LICENSE_REJECTED)
                if (json.optInt("code", -1) != 0) throw IOException(LICENSE_REJECTED)
                val license = json.optJSONObject("data")?.opt("license") as? String
                    ?: throw IOException(LICENSE_REJECTED)
                if (license.isBlank()) throw IOException(LICENSE_REJECTED)
                return runCatching { Base64.decode(license, Base64.DEFAULT) }.getOrNull()
                    ?: runCatching { Base64.decode(license, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
                    ?: license.toByteArray(Charsets.UTF_8)
            }
        } catch (error: IOException) {
            if (error.message == LICENSE_REJECTED) throw error
            throw IOException(LICENSE_REJECTED, error)
        } catch (error: Exception) {
            throw IOException(LICENSE_REJECTED, error)
        }
    }

    companion object {
        const val LICENSE_REJECTED = "这首歌的 Widevine 许可未被设备接受"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@UnstableApi
internal fun appleWidevineSessionManager(api: FantasticApi, server: String, track: AppleResolvedTrack): DefaultDrmSessionManager {
    val uri = track.widevineUri ?: throw IOException(AppleWidevineCallback.LICENSE_REJECTED)
    val callback = AppleWidevineCallback(api, server, track.media.adamId, uri)
    return DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
        .setMultiSession(false)
        .build(callback)
}
