package com.dingaimin.dedaocompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class QueueStoreTest {
    private static RedPacketItem item(String title) {
        return new RedPacketItem(title, 65, "课程", title, "", 0L);
    }

    private static RedPacketItem item(String course, String title) {
        return new RedPacketItem(course + title, 65, course, title, "", 0L);
    }

    @Test public void forwardSequenceSkipsEveryExpiredItem() {
        List<RedPacketItem> queue = List.of(item("过期目标"), item("过期中间项"), item("有效下一条"));
        QueueStore.QueueSelection result = QueueStore.selectAfterReject(
                queue, Set.of("有效下一条"), "过期目标", 1);

        assertEquals(List.of("有效下一条"), result.retained.stream().map(value -> value.title).toList());
        assertEquals("有效下一条", result.candidate.title);
        assertEquals(0, result.selectedIndex);
    }

    @Test public void backwardSequenceSkipsEveryExpiredItem() {
        List<RedPacketItem> queue = List.of(item("有效上一条"), item("过期中间项"), item("过期目标"));
        QueueStore.QueueSelection result = QueueStore.selectAfterReject(
                queue, Set.of("有效上一条"), "过期目标", -1);

        assertEquals(List.of("有效上一条"), result.retained.stream().map(value -> value.title).toList());
        assertEquals("有效上一条", result.candidate.title);
        assertEquals(0, result.selectedIndex);
    }

    @Test public void sequenceStopsWhenNoValidItemRemains() {
        List<RedPacketItem> queue = List.of(item("过期目标"), item("也已过期"));
        QueueStore.QueueSelection result = QueueStore.selectAfterReject(
                queue, Set.of(), "过期目标", 1);

        assertEquals(0, result.retained.size());
        assertNull(result.candidate);
        assertEquals(0, result.selectedIndex);
    }

    @Test public void directExpiredTapPrunesButDoesNotAutoplayAnotherItem() {
        List<RedPacketItem> queue = List.of(item("过期目标"), item("仍然有效"));
        QueueStore.QueueSelection result = QueueStore.selectAfterReject(
                queue, Set.of("仍然有效"), "过期目标", 0);

        assertEquals(List.of("仍然有效"), result.retained.stream().map(value -> value.title).toList());
        assertNull(result.candidate);
        assertEquals(0, result.selectedIndex);
    }

}
