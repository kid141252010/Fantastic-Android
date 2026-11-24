package io.github.kid141252010.fantastic.playback

import android.util.Log

internal object Temari {
    private const val TAG = "FantasticPlayback"
    private var loaded = false

    init {
        try {
            System.loadLibrary("fantastic_temari")
            loaded = true
        } catch (error: Throwable) {
            Log.e(TAG, "FairPlay native library unavailable", error)
        }
    }

    @JvmStatic
    internal fun isAvailable(): Boolean = loaded

    @JvmStatic
    external fun open(json: ByteArray): Long

    @JvmStatic
    external fun decrypt(handle: Long, sample: ByteArray): ByteArray

    @JvmStatic
    external fun close(handle: Long)
}
