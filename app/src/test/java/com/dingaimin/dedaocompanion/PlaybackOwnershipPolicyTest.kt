package com.dingaimin.dedaocompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOwnershipPolicyTest {
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
