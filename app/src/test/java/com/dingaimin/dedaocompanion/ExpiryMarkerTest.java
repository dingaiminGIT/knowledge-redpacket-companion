package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExpiryMarkerTest {
    @Test public void durationOnlyIsExpired() {
        assertFalse(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒"));
    }

    @Test public void everyObservedLearningMarkerIsActive() {
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 未学习"));
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 已学完"));
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 学习中"));
        assertTrue(DedaoAccessibilityService.hasActiveRedPacketState("10分04秒 已完成"));
    }

    @Test public void emptyOrUnrelatedCopyIsNotActive() {
        assertFalse(DedaoAccessibilityService.hasActiveRedPacketState(null));
        assertFalse(DedaoAccessibilityService.hasActiveRedPacketState("体验权益"));
    }
}
