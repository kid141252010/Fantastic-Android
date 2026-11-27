package io.github.kid141252010.fantastic.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerConnectionClockTest {
    @Test
    fun appleInternalOutputCorrectionIsNotALyricSeek() {
        assertFalse(shouldIncrementLyricSeekGeneration("apple:1541771590", Player.DISCONTINUITY_REASON_INTERNAL))
    }

    @Test
    fun appleUserSeekAndSeekAdjustmentRemainLyricSeeks() {
        assertTrue(shouldIncrementLyricSeekGeneration("apple:1541771590", Player.DISCONTINUITY_REASON_SEEK))
        assertTrue(shouldIncrementLyricSeekGeneration("apple:1541771590", Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT))
    }

    @Test
    fun appleItemTransitionsAndRemovalStillAdvanceTheSeekGeneration() {
        assertTrue(shouldIncrementLyricSeekGeneration("apple:next", Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertTrue(shouldIncrementLyricSeekGeneration("apple:next", Player.DISCONTINUITY_REASON_REMOVE))
    }

    @Test
    fun internalCorrectionsForOtherOrUnknownSourcesKeepExistingSeekBehavior() {
        assertTrue(shouldIncrementLyricSeekGeneration("qq:mid", Player.DISCONTINUITY_REASON_INTERNAL))
        assertTrue(shouldIncrementLyricSeekGeneration("ncm:123", Player.DISCONTINUITY_REASON_INTERNAL))
        assertTrue(shouldIncrementLyricSeekGeneration(null, Player.DISCONTINUITY_REASON_INTERNAL))
    }
}
