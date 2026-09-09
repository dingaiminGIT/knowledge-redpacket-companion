package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PlaybackCompletionPolicyTest {
    @Test public void acceptsTerminalSampleWithinThreeSecondsOfEnd() {
        assertTrue(PlaybackCompletionPolicy.reachedEnd(717_000L, 718_000L));
        assertTrue(PlaybackCompletionPolicy.reachedEnd(715_000L, 718_000L));
    }

    @Test public void doesNotTreatOrdinaryPauseAsCompletion() {
        assertFalse(PlaybackCompletionPolicy.reachedEnd(151_000L, 718_000L));
        assertFalse(PlaybackCompletionPolicy.reachedEnd(0L, 0L));
    }

    @Test public void nearEndUsesFastProbeWithoutTreatingPlayingAsCompleted() {
        assertEquals(1000L, PlaybackCompletionPolicy.probeDelay(1000, 180000));
        assertEquals(250L, PlaybackCompletionPolicy.probeDelay(176000, 180000));
        assertEquals(1000L, PlaybackCompletionPolicy.probeDelay(0, 0));
    }

    @Test public void backwardSeekClearsOldEndEvidenceButTerminalResetRetainsIt() {
        assertEquals(10000L, PlaybackCompletionPolicy.updateFurthest(718000, 10000, true));
        assertEquals(718000L, PlaybackCompletionPolicy.updateFurthest(718000, 0, false));
        assertEquals(718000L, PlaybackCompletionPolicy.updateFurthest(717000, 718000, true));
    }
}
