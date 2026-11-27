package io.github.kid141252010.fantastic.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricPlaybackClockTest {
    @Test
    fun appleOutputCorrectionImmediatelyMovesLyricsBackwards() {
        val clock = LyricPlaybackClock()
        clock.sync(10_000L, true, 0L, discontinuity = true, followOutput = true)
        val dispatched = clock.position(250L, Long.MIN_VALUE)
        assertEquals(10_250L, dispatched)

        clock.sync(10_180L, true, 250L, discontinuity = false, followOutput = true)
        assertEquals(10_180L, clock.position(250L, dispatched))
        assertEquals(10_280L, clock.position(350L, 10_180L))
    }

    @Test
    fun otherSourcesKeepTheLastDispatchedTimeUntilProjectionCatchesUp() {
        val clock = LyricPlaybackClock()
        clock.sync(10_000L, true, 0L, discontinuity = true, followOutput = false)
        val dispatched = clock.position(250L, Long.MIN_VALUE)
        assertEquals(10_250L, dispatched)

        clock.sync(10_180L, true, 250L, discontinuity = false, followOutput = false)
        assertEquals(dispatched, clock.position(250L, dispatched))
        assertEquals(10_280L, clock.position(350L, dispatched))
    }

    @Test
    fun onlyNonAppleSamplesRetainTheThirtyMillisecondDeadband() {
        for (followOutput in listOf(false, true)) {
            val clock = LyricPlaybackClock()
            clock.sync(10_000L, true, 0L, discontinuity = true, followOutput = followOutput)
            clock.sync(10_230L, true, 250L, discontinuity = false, followOutput = followOutput)
            assertEquals(if (followOutput) 10_230L else 10_250L, clock.position(250L, Long.MIN_VALUE))
        }
    }

    @Test
    fun pauseFreezesAndResumeReanchorsToTheLatestSourceSample() {
        for (followOutput in listOf(false, true)) {
            val clock = LyricPlaybackClock()
            clock.sync(5_000L, true, 0L, discontinuity = true, followOutput = followOutput)
            assertEquals(5_100L, clock.position(100L, Long.MIN_VALUE))
            clock.sync(5_100L, false, 100L, discontinuity = false, followOutput = followOutput)
            assertEquals(5_100L, clock.position(600L, 5_100L))
            clock.sync(5_250L, true, 600L, discontinuity = false, followOutput = followOutput)
            assertEquals(5_350L, clock.position(700L, 5_100L))
        }
    }

    @Test
    fun explicitSeekAdoptsItsTargetWithTheViewsClearedDispatchAnchor() {
        val clock = LyricPlaybackClock()
        clock.sync(10_000L, true, 0L, discontinuity = true, followOutput = false)
        assertEquals(10_250L, clock.position(250L, Long.MIN_VALUE))
        clock.sync(1_000L, true, 250L, discontinuity = true, followOutput = false)
        assertEquals(1_000L, clock.position(250L, Long.MIN_VALUE))
        assertEquals(1_100L, clock.position(350L, 1_000L))
    }

    @Test
    fun replacingTheSongDoesNotReuseThePreviousClock() {
        val clock = LyricPlaybackClock()
        clock.sync(100_000L, true, 0L, discontinuity = true, followOutput = true)
        clock.reset()
        assertEquals(0L, clock.position(500L, Long.MIN_VALUE))
        clock.sync(2_000L, false, 500L, discontinuity = false, followOutput = false)
        assertEquals(2_000L, clock.position(1_000L, Long.MIN_VALUE))
    }
}
