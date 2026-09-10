package com.dingaimin.dedaocompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedPacketItemTest {
    @Test
    fun completedStateCanBeStoredOnItem() {
        val item: RedPacketItem = RedPacketItem("id", 65, "课程", "标题", "", 123L, true)

        assertTrue(item.completed)
    }

    @Test
    fun legacyConstructorDefaultsToNotCompleted() {
        val item: RedPacketItem = RedPacketItem("id", 65, "课程", "标题", "", 123L)

        assertFalse(item.completed)
        assertEquals("", item.audioId)
    }

    @Test
    fun directPlaybackSelectorCanBeStoredOnItem() {
        val item: RedPacketItem =
            RedPacketItem("id", 65, "课程", "标题", "igetapp://detail", "audio-selector", 123L, false)

        assertEquals("audio-selector", item.audioId)
        assertEquals("标题", item.title)
    }
}
