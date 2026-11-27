package io.github.kid141252010.fantastic.lyrics

/** Projects the service/controller anchor; Apple samples may correct the visible time backwards. */
internal class LyricPlaybackClock {
    private var hasAnchor = false
    private var playing = false
    private var followOutput = false
    private var timeAtMs = 0L
    private var timeValueMs = 0L

    fun reset() {
        hasAnchor = false
        playing = false
        followOutput = false
        timeAtMs = 0L
        timeValueMs = 0L
    }

    fun sync(positionMs: Long, playing: Boolean, nowMs: Long, discontinuity: Boolean, followOutput: Boolean) {
        val projected = timeValueMs + if (this.playing) nowMs - timeAtMs else 0L
        if (followOutput || !hasAnchor || discontinuity || this.playing != playing ||
            kotlin.math.abs(projected - positionMs) > 30L) {
            timeValueMs = positionMs.coerceAtLeast(0L)
            timeAtMs = nowMs
        }
        this.playing = playing
        this.followOutput = followOutput
        hasAnchor = true
    }

    fun position(nowMs: Long, lastDispatchedMs: Long): Long {
        val projected = timeValueMs + if (playing) nowMs - timeAtMs else 0L
        return if (followOutput || lastDispatchedMs == Long.MIN_VALUE) projected else maxOf(projected, lastDispatchedMs)
    }
}
