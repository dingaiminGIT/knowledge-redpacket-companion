package com.dingaimin.dedaocompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryMarkerTest {
    @Test
    fun durationOnlyIsExpired() {
        assertFalse(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒"))
    }

    @Test
    fun everyObservedLearningMarkerIsActive() {
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 未学习"))
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 已学完"))
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 学习中"))
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 已完成"))
    }

    @Test
    fun emptyOrUnrelatedCopyIsNotActive() {
        assertFalse(DedaoAccessibilityService.hasActiveRedPacketState(null))
        assertFalse(DedaoAccessibilityService.hasActiveRedPacketState("体验权益"))
    }
}
