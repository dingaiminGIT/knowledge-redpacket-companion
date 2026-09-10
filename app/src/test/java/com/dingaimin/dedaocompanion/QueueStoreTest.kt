package com.dingaimin.dedaocompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueStoreTest {
    private fun item(title: String): RedPacketItem {
        return RedPacketItem(title, 65, "课程", title, "", 0L)
    }

    private fun item(course: String, title: String): RedPacketItem {
        return RedPacketItem(course + title, 65, course, title, "", 0L)
    }

    @Test
    fun forwardSequenceSkipsEveryExpiredItem() {
        val queue: List<RedPacketItem> = listOf(item("过期目标"), item("过期中间项"), item("有效下一条"))
        val result: QueueStore.QueueSelection =
            QueueStore.selectAfterReject(queue, setOf("有效下一条"), "过期目标", 1)

        assertEquals(
            listOf("有效下一条"),
            result.retained.stream().map<String>({ value -> value.title }).toList(),
        )
        assertEquals("有效下一条", result.candidate?.title)
        assertEquals(0, result.selectedIndex)
    }

    @Test
    fun backwardSequenceSkipsEveryExpiredItem() {
        val queue: List<RedPacketItem> = listOf(item("有效上一条"), item("过期中间项"), item("过期目标"))
        val result: QueueStore.QueueSelection =
            QueueStore.selectAfterReject(queue, setOf("有效上一条"), "过期目标", -1)

        assertEquals(
            listOf("有效上一条"),
            result.retained.stream().map<String>({ value -> value.title }).toList(),
        )
        assertEquals("有效上一条", result.candidate?.title)
        assertEquals(0, result.selectedIndex)
    }

    @Test
    fun sequenceStopsWhenNoValidItemRemains() {
        val queue: List<RedPacketItem> = listOf(item("过期目标"), item("也已过期"))
        val result: QueueStore.QueueSelection =
            QueueStore.selectAfterReject(queue, setOf(), "过期目标", 1)

        assertEquals(0, result.retained.size)
        assertNull(result.candidate)
        assertEquals(0, result.selectedIndex)
    }

    @Test
    fun directExpiredTapPrunesButDoesNotAutoplayAnotherItem() {
        val queue: List<RedPacketItem> = listOf(item("过期目标"), item("仍然有效"))
        val result: QueueStore.QueueSelection =
            QueueStore.selectAfterReject(queue, setOf("仍然有效"), "过期目标", 0)

        assertEquals(
            listOf("仍然有效"),
            result.retained.stream().map<String>({ value -> value.title }).toList(),
        )
        assertNull(result.candidate)
        assertEquals(0, result.selectedIndex)
    }
}
