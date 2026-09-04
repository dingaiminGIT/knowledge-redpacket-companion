package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RedPacketItemTest {
    @Test public void completedStateCanBeStoredOnItem() {
        RedPacketItem item = new RedPacketItem(
                "id", 65, "课程", "标题", "", 123L, true);

        assertTrue(item.completed);
    }

    @Test public void legacyConstructorDefaultsToNotCompleted() {
        RedPacketItem item = new RedPacketItem(
                "id", 65, "课程", "标题", "", 123L);

        assertFalse(item.completed);
        assertEquals("", item.audioId);
    }

    @Test public void directPlaybackSelectorCanBeStoredOnItem() {
        RedPacketItem item = new RedPacketItem(
                "id", 65, "课程", "标题", "igetapp://detail", "audio-selector", 123L, false);

        assertEquals("audio-selector", item.audioId);
        assertEquals("标题", item.title);
    }
}
