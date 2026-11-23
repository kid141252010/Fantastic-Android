package io.github.kid141252010.fantastic.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.github.kid141252010.fantastic.BuildConfig
import java.io.File

/** Owned by FantasticApp so the same cache survives Activity and playback-service recreation. */
@OptIn(UnstableApi::class)
internal fun createMotionArtworkDataSource(context: Context): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory()
    var cache: SimpleCache? = null
    return try {
        val artworkCache = SimpleCache(
            File(context.cacheDir, "streaming-motion-artwork"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
        artworkCache.checkInitialization()
        CacheDataSource.Factory()
            .setCache(artworkCache)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .apply {
                if (BuildConfig.DEBUG) setEventListener(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        if (cachedBytesRead > 0) Log.d("FantasticMotion", "Artwork cache hit: $cachedBytesRead bytes")
                    }

                    override fun onCacheIgnored(reason: Int) {
                        if (reason == CacheDataSource.CACHE_IGNORED_REASON_ERROR) {
                            Log.w("FantasticMotion", "Artwork cache ignored: $reason")
                        }
                    }
                })
            }
    } catch (error: Exception) {
        try {
            cache?.release()
        } catch (releaseError: Exception) {
            error.addSuppressed(releaseError)
        }
        Log.w("FantasticMotion", "Artwork disk cache unavailable", error)
        http
    }
}
