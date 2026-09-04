package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
