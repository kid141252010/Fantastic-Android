package io.github.kid141252010.fantastic.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

private const val CACHE_SCHEME = "fantastic-cache"
private const val CACHE_AUTHORITY = "audio"

internal fun playbackCacheUri(id: String): Uri = Uri.Builder()
    .scheme(CACHE_SCHEME).authority(CACHE_AUTHORITY).appendPath(id).build()

@UnstableApi
internal class CachedAudioDataSource(
    private val cache: PlaybackCache,
    private val upstream: DataSource,
) : DataSource {
    private var readers: MutableList<TransferListener>? = null
    private var active: DataSource? = null
    private var lease: PlaybackCacheLease? = null
    private var localUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        val listeners = readers ?: ArrayList<TransferListener>(1).also { readers = it }
        if (transferListener !in listeners) listeners.add(transferListener)
        (active as? FileDataSource)?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != CACHE_SCHEME) {
            active = upstream
            return upstream.open(dataSpec)
        }
        val uri = dataSpec.uri
        val id = uri.encodedPath?.removePrefix("/")
        if (uri.encodedAuthority != CACHE_AUTHORITY || uri.encodedQuery != null || uri.encodedFragment != null ||
            id == null || !PlaybackCache.FILE_ID.matches(id)) throw IOException("无效的播放缓存地址")
        val acquired = cache.acquire(id) ?: throw IOException("播放缓存文件不存在或不完整")
        lease = acquired
        localUri = uri
        val source = FileDataSource()
        active = source
        readers?.forEach(source::addTransferListener)
        return try {
            source.open(dataSpec.withUri(Uri.fromFile(acquired.file)))
        } catch (error: IOException) {
            try { close() } catch (closeError: IOException) { error.addSuppressed(closeError) }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = localUri ?: active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        try { active?.close() }
        finally {
            active = null
            localUri = null
            lease?.close()
            lease = null
        }
    }
}
