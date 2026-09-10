package com.dingaimin.dedaocompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompletionPolicyTest {
    @Test
    fun acceptsTerminalSampleWithinThreeSecondsOfEnd() {
        assertTrue(PlaybackCompletionPolicy.reachedEnd(717_000L, 718_000L))
        assertTrue(PlaybackCompletionPolicy.reachedEnd(715_000L, 718_000L))
    }

    @Test
    fun doesNotTreatOrdinaryPauseAsCompletion() {
        assertFalse(PlaybackCompletionPolicy.reachedEnd(151_000L, 718_000L))
        assertFalse(PlaybackCompletionPolicy.reachedEnd(0L, 0L))
    }

    @Test
    fun nearEndUsesFastProbeWithoutTreatingPlayingAsCompleted() {
        assertEquals(1000L, PlaybackCompletionPolicy.probeDelay(1000, 180000))
        assertEquals(250L, PlaybackCompletionPolicy.probeDelay(176000, 180000))
        assertEquals(1000L, PlaybackCompletionPolicy.probeDelay(0, 0))
    }

    @Test
    fun backwardSeekClearsOldEndEvidenceButTerminalResetRetainsIt() {
        assertEquals(10000L, PlaybackCompletionPolicy.updateFurthest(718000, 10000, true))
        assertEquals(718000L, PlaybackCompletionPolicy.updateFurthest(718000, 0, false))
        assertEquals(718000L, PlaybackCompletionPolicy.updateFurthest(717000, 718000, true))
    }
}
