package com.dingaimin.dedaocompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePolicyTest {

    @Test
    fun nullableInputsKeepLegacyEmptyDefaults() {
        assertEquals(emptyList<RedPacketItem>(), QueuePolicy.apply(null, null, false, true))
        assertEquals(emptyList<RedPacketItem>(), QueuePolicy.applyCustomOrder(null, null))
        assertEquals(emptyList<String>(), QueuePolicy.mergeVisibleOrder(null, null))
        assertEquals("", QueuePolicy.stableKey(null))
    }

    @Test
    fun blankCourseAndMissingOrderPreserveAllItems() {
        assertEquals(listOf(newest, middle, oldest), QueuePolicy.apply(source, " ", false, true))
        assertEquals(source, QueuePolicy.applyCustomOrder(source, null))
    }

    @Test
    fun duplicateOrderKeysUseFirstOccurrenceAndPreserveUnrankedOrder() {
        val keys =
            listOf(
                QueuePolicy.stableKey(middle),
                QueuePolicy.stableKey(oldest),
                QueuePolicy.stableKey(middle),
                "",
            )
        assertEquals(listOf(middle, oldest, newest), QueuePolicy.applyCustomOrder(source, keys))
        assertEquals(listOf(oldest, newest, middle), source)
    }

    private val newest: RedPacketItem = item("最新未学完", "课程甲", 30, false)
    private val middle: RedPacketItem = item("中间已学完", "课程乙", 20, true)
    private val oldest: RedPacketItem = item("最早未学完", "课程甲", 10, false)
    private val source: List<RedPacketItem> = listOf(oldest, newest, middle)

    private fun item(
        title: String,
        course: String,
        claimedAt: Long,
        completed: Boolean,
    ): RedPacketItem {
        return RedPacketItem(title, 65, course, title, "", claimedAt, completed)
    }

    @Test
    fun newestFirstControlsRealQueueOrder() {
        val result: List<RedPacketItem> = QueuePolicy.apply(source, "全部课程", false, true)
        assertEquals(
            listOf("最新未学完", "中间已学完", "最早未学完"),
            result.stream().map<String>({ item -> item.title }).toList(),
        )
    }

    @Test
    fun oldestFirstControlsRealQueueOrder() {
        val result: List<RedPacketItem> = QueuePolicy.apply(source, "全部课程", false, false)
        assertEquals(
            listOf("最早未学完", "中间已学完", "最新未学完"),
            result.stream().map<String>({ item -> item.title }).toList(),
        )
    }

    @Test
    fun completedItemsCanBeOptionallyHidden() {
        val result: List<RedPacketItem> = QueuePolicy.apply(source, "全部课程", true, true)
        assertEquals(
            listOf("最新未学完", "最早未学完"),
            result.stream().map<String>({ item -> item.title }).toList(),
        )
    }

    @Test
    fun courseAndCompletedFiltersCompose() {
        val result: List<RedPacketItem> = QueuePolicy.apply(source, "课程乙", true, true)
        assertEquals(0, result.size)
    }

    @Test
    fun customOrderOverridesClaimTimeOrder() {
        val sorted: List<RedPacketItem> = QueuePolicy.apply(source, "全部课程", false, true)
        val result: List<RedPacketItem> =
            QueuePolicy.applyCustomOrder(
                sorted,
                listOf(
                    QueuePolicy.stableKey(oldest),
                    QueuePolicy.stableKey(newest),
                    QueuePolicy.stableKey(middle),
                ),
            )

        assertEquals(
            listOf("最早未学完", "最新未学完", "中间已学完"),
            result.stream().map<String>({ item -> item.title }).toList(),
        )
    }

    @Test
    fun customOrderKeepsNewItemsAtTheEnd() {
        val sorted: List<RedPacketItem> = QueuePolicy.apply(source, "全部课程", false, true)
        val result: List<RedPacketItem> =
            QueuePolicy.applyCustomOrder(sorted, listOf(QueuePolicy.stableKey(oldest)))

        assertEquals(
            listOf("最早未学完", "最新未学完", "中间已学完"),
            result.stream().map<String>({ item -> item.title }).toList(),
        )
    }

    @Test
    fun visibleReorderKeepsFilteredOutItemsInPlace() {
        val result: List<String> =
            QueuePolicy.mergeVisibleOrder(listOf("a", "hidden", "b", "c"), listOf("c", "a", "b"))

        assertEquals(listOf("c", "hidden", "a", "b"), result)
    }

    @Test
    fun removalSurvivesSortingAndRefreshWithoutMutatingSource() {
        val removed: Set<String> = setOf(QueuePolicy.stableKey(middle))
        val fresh: List<RedPacketItem> = QueuePolicy.apply(source, "全部课程", false, true)
        assertEquals(listOf(newest, oldest), QueuePolicy.excluding(fresh, removed))
        assertEquals(3, source.size)
        assertEquals(fresh, QueuePolicy.excluding(fresh, setOf()))
    }

    @Test
    fun removingBeforeCurrentPreservesTheSameEpisode() {
        assertEquals(1, QueuePolicy.indexAfterRemoval(2, 0, 4))
        assertEquals(1, QueuePolicy.indexAfterRemoval(1, 3, 4))
    }

    @Test
    fun removingCurrentSelectsSuccessorOrLastRemainingAndHandlesEmpty() {
        assertEquals(1, QueuePolicy.indexAfterRemoval(1, 1, 3))
        assertEquals(1, QueuePolicy.indexAfterRemoval(2, 2, 3))
        assertEquals(-1, QueuePolicy.indexAfterRemoval(0, 0, 1))
    }
}
