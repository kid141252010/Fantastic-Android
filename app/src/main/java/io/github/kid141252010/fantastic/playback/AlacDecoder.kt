package io.github.kid141252010.fantastic.playback

import android.util.Log

internal object AlacDecoder {
    private const val TAG = "FantasticPlayback"
    private var loaded = false

    init {
        try {
            System.loadLibrary("fantastic_alac")
            loaded = true
        } catch (error: Throwable) {
            Log.e(TAG, "ALAC native library unavailable", error)
        }
    }

    @JvmStatic
    internal fun isAvailable(): Boolean = loaded

    @JvmStatic
    external fun open(cookie: ByteArray): Long

    @JvmStatic
    external fun decode(handle: Long, packet: ByteArray): ByteArray

    @JvmStatic
    external fun close(handle: Long)

    @JvmStatic
    external fun channels(handle: Long): Int

    @JvmStatic
    external fun sampleRate(handle: Long): Int

    @JvmStatic
    external fun bits(handle: Long): Int
}
