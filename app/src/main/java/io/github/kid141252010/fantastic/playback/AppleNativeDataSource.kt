package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Virtual HLS resources yield decrypted fMP4; Widevine segment URLs remain ordinary HTTPS resources. */
@UnstableApi
internal class AppleNativeDataSource(
    context: Context,
    private val decrypt: AppleNativeDecrypt,
    private val track: AppleResolvedTrack,
    private val requestId: Long,
) : BaseDataSource(true) {
    private val http = DefaultHttpDataSource.Factory().createDataSource()
    private val appContext = context.applicationContext
    private var scope: CoroutineScope? = null
    private var uri: Uri? = null
    private var data: ByteArray? = null
    private var offset = 0
    private var remaining = 0L
    private var contentType = "application/octet-stream"
    private var delegatedHttp = false

    override fun open(dataSpec: DataSpec): Long {
        check(uri == null)
        transferInitializing(dataSpec)
        if (dataSpec.uri.scheme != "apple-native") {
            delegatedHttp = true
            return try {
                val length = http.open(dataSpec)
                uri = dataSpec.uri
                transferStarted(dataSpec)
                length
            } catch (error: Exception) {
                runCatching { http.close() }
                delegatedHttp = false
                throw error
            }
        }
        val host = dataSpec.uri.host?.toLongOrNull()
        if (host != requestId) throw IOException("Apple 播放列表请求已过期")
        val bytes = when {
            dataSpec.uri.encodedPath == "/playlist.m3u8" -> {
                contentType = "application/vnd.apple.mpegurl"
                if (track.variant.widevine) decrypt.generatedWidevinePlaylist(track) else decrypt.generatedPlaylist(track, requestId)
            }
            dataSpec.uri.encodedPath == "/init" -> {
                contentType = "video/mp4"
                await { decrypt.fetchInit(track) }
            }
            dataSpec.uri.encodedPath?.startsWith("/segment/") == true -> {
                contentType = "video/mp4"
                val index = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Apple 分片地址无效")
                await { decrypt.fetchSegment(track, index) }
            }
            else -> throw IOException("Apple 分片地址无效")
        }
        if (dataSpec.position > bytes.size) throw EOFException("Apple 分片读取越界")
        val available = bytes.size.toLong() - dataSpec.position
        val length = if (dataSpec.length >= 0) minOf(available, dataSpec.length) else available
        if (dataSpec.length >= 0 && length != dataSpec.length) throw EOFException("Apple 分片长度不符")
        data = bytes
        offset = dataSpec.position.toInt()
        remaining = length
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return length
    }

    private fun <T> await(block: suspend () -> T): T {
        val current = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        return runBlocking { current.async { block() }.await() }
    }

    override fun read(buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (delegatedHttp) {
            val count = http.read(buffer, bufferOffset, length)
            if (count > 0) bytesTransferred(count)
            return count
        }
        val bytes = data ?: return C.RESULT_END_OF_INPUT
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), remaining, (bytes.size - offset).toLong()).toInt()
        if (count <= 0) return C.RESULT_END_OF_INPUT
        bytes.copyInto(buffer, bufferOffset, offset, offset + count)
        offset += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = if (delegatedHttp) uri ?: http.uri else uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (delegatedHttp) http.responseHeaders else
        mapOf("Content-Type" to listOf(contentType))

    override fun close() {
        try {
            if (delegatedHttp) http.close()
        } finally {
            delegatedHttp = false
            data = null
            offset = 0
            remaining = 0
            scope?.cancel()
            scope = null
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }
}
