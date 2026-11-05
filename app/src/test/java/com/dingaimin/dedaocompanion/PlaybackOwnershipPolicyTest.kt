package com.dingaimin.dedaocompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOwnershipPolicyTest {
    @Test
    fun interruptedOwnedItemCanResumeQueueMonitoring() {
        assertTrue(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "红包A", "红包A", true, false, ""))
    }

    @Test
    fun officialSelectionCannotResumeSuspendedQueue() {
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "红包A", "课程B", true, false, ""))
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "红包A", "", true, false, ""))
    }

    @Test
    fun explicitCompanionPauseAndLegacyFlagsCannotRearmMonitoring() {
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "", "红包A", true, false, ""))
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("", "", "红包A", true, false, ""))
    }

    @Test
    fun oldPauseCannotResumeNewOwnerOrPendingOpen() {
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包B", "红包A", "红包B", true, false, ""))
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "红包A", "红包A", true, false, "红包A"))
    }

    @Test
    fun activeAndEndedQueuesDoNotRearmOnEveryPlayingSample() {
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "红包A", "红包A", true, true, ""))
        assertFalse(PlaybackOwnershipPolicy.shouldResumeQueue("红包A", "红包A", "红包A", false, false, ""))
    }

    @Test
    fun returningToOldEpisodeAfterTargetWasSeenCancelsRetry() {
        assertTrue(PlaybackOwnershipPolicy.shouldYield("红包B", "红包B", "红包A", "红包A", true))
    }

    @Test
    fun unrelatedOfficialSelectionYields() {
        assertTrue(PlaybackOwnershipPolicy.shouldYield("红包A", "", "", "课程B"))
    }

    @Test
    fun anotherQueueItemAlsoYieldsWithoutExplicitRequest() {
        assertTrue(PlaybackOwnershipPolicy.shouldYield("红包A", "", "", "红包B"))
    }

    @Test
    fun companionRequestAcceptsTargetAndStillLoadingPreviousTitle() {
        assertFalse(PlaybackOwnershipPolicy.shouldYield("红包B", "红包B", "红包A", "红包A"))
        assertFalse(PlaybackOwnershipPolicy.shouldYield("红包B", "红包B", "红包A", "红包B"))
    }

    @Test
    fun externalSelectionCancelsPendingRequest() {
        assertTrue(PlaybackOwnershipPolicy.shouldYield("红包B", "红包B", "红包A", "课程C"))
    }

    @Test
    fun emptyMetadataAndIdleObserverDoNotTakeControl() {
        assertFalse(PlaybackOwnershipPolicy.shouldYield("红包A", "", "", ""))
        assertFalse(PlaybackOwnershipPolicy.shouldYield("", "", "", "课程C"))
    }

    @Test
    fun sameEpisodeDoesNotLoseOwnership() {
        assertFalse(PlaybackOwnershipPolicy.shouldYield("红包A", "", "", "红包A"))
    }
}
