package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class QueuePolicyTest {
    private static RedPacketItem item(String title, String course, long claimedAt, boolean completed) {
        return new RedPacketItem(title, 65, course, title, "", claimedAt, completed);
    }

    private final RedPacketItem newest = item("最新未学完", "课程甲", 30, false);
    private final RedPacketItem middle = item("中间已学完", "课程乙", 20, true);
    private final RedPacketItem oldest = item("最早未学完", "课程甲", 10, false);
    private final List<RedPacketItem> source = List.of(oldest, newest, middle);

    @Test public void newestFirstControlsRealQueueOrder() {
        List<RedPacketItem> result = QueuePolicy.apply(source, "全部课程", false, true);
        assertEquals(List.of("最新未学完", "中间已学完", "最早未学完"),
                result.stream().map(item -> item.title).toList());
    }

    @Test public void oldestFirstControlsRealQueueOrder() {
        List<RedPacketItem> result = QueuePolicy.apply(source, "全部课程", false, false);
        assertEquals(List.of("最早未学完", "中间已学完", "最新未学完"),
                result.stream().map(item -> item.title).toList());
    }

    @Test public void completedItemsCanBeOptionallyHidden() {
        List<RedPacketItem> result = QueuePolicy.apply(source, "全部课程", true, true);
        assertEquals(List.of("最新未学完", "最早未学完"),
                result.stream().map(item -> item.title).toList());
    }

    @Test public void courseAndCompletedFiltersCompose() {
        List<RedPacketItem> result = QueuePolicy.apply(source, "课程乙", true, true);
        assertEquals(0, result.size());
    }

    @Test public void customOrderOverridesClaimTimeOrder() {
        List<RedPacketItem> sorted = QueuePolicy.apply(source, "全部课程", false, true);
        List<RedPacketItem> result = QueuePolicy.applyCustomOrder(sorted, List.of(
                QueuePolicy.stableKey(oldest),
                QueuePolicy.stableKey(newest),
                QueuePolicy.stableKey(middle)));

        assertEquals(List.of("最早未学完", "最新未学完", "中间已学完"),
                result.stream().map(item -> item.title).toList());
    }

    @Test public void customOrderKeepsNewItemsAtTheEnd() {
        List<RedPacketItem> sorted = QueuePolicy.apply(source, "全部课程", false, true);
        List<RedPacketItem> result = QueuePolicy.applyCustomOrder(sorted, List.of(
                QueuePolicy.stableKey(oldest)));

        assertEquals(List.of("最早未学完", "最新未学完", "中间已学完"),
                result.stream().map(item -> item.title).toList());
    }

    @Test public void visibleReorderKeepsFilteredOutItemsInPlace() {
        List<String> result = QueuePolicy.mergeVisibleOrder(
                List.of("a", "hidden", "b", "c"),
                List.of("c", "a", "b"));

        assertEquals(List.of("c", "hidden", "a", "b"), result);
    }

    @Test public void removalSurvivesSortingAndRefreshWithoutMutatingSource() {
        Set<String> removed = Set.of(QueuePolicy.stableKey(middle));
        List<RedPacketItem> fresh = QueuePolicy.apply(source, "全部课程", false, true);
        assertEquals(List.of(newest, oldest), QueuePolicy.excluding(fresh, removed));
        assertEquals(3, source.size());
        assertEquals(fresh, QueuePolicy.excluding(fresh, Set.of()));
    }

    @Test public void removingBeforeCurrentPreservesTheSameEpisode() {
        assertEquals(1, QueuePolicy.indexAfterRemoval(2, 0, 4));
        assertEquals(1, QueuePolicy.indexAfterRemoval(1, 3, 4));
    }

    @Test public void removingCurrentSelectsSuccessorOrLastRemainingAndHandlesEmpty() {
        assertEquals(1, QueuePolicy.indexAfterRemoval(1, 1, 3));
        assertEquals(1, QueuePolicy.indexAfterRemoval(2, 2, 3));
        assertEquals(-1, QueuePolicy.indexAfterRemoval(0, 0, 1));
    }
}
