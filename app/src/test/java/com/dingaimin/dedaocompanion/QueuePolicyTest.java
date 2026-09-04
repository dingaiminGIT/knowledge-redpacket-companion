package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

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
}
