package io.github.kid141252010.fantastic.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** A per-open file lease keeps a revoked download alive until Media3 closes its loader. */
@UnstableApi
internal class DownloadedAudioDataSource(private val pin: OfflineAudioPin) : DataSource {
    private val source = FileDataSource()
    private var lease: OfflineAudioLease? = null
    private val mediaUri = Uri.Builder().scheme(SCHEME).authority(pin.audio.fileId).build()

    override fun addTransferListener(transferListener: TransferListener) {
        source.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (lease != null) throw IOException("下载音频数据源已打开")
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME || uri.encodedAuthority != pin.audio.fileId ||
            !uri.encodedPath.isNullOrEmpty() || uri.encodedQuery != null || uri.encodedFragment != null) {
            throw IOException("下载音频请求无效")
        }
        val openedLease = pin.acquire() ?: throw IOException("离线文件已不可用")
        lease = openedLease
        return try {
            source.open(dataSpec.withUri(Uri.fromFile(openedLease.file)))
        } catch (error: Throwable) {
            try { source.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            try { openedLease.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            lease = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

    override fun getUri(): Uri? = if (lease == null) null else mediaUri

    override fun close() {
        val openedLease = lease
        lease = null
        try { source.close() } finally { openedLease?.close() }
    }

    companion object {
        private const val SCHEME = "downloaded-audio"
    }
}