package com.dingaimin.dedaocompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class QueuePolicy {
    static List<RedPacketItem> apply(List<RedPacketItem> source, String course,
                                     boolean hideCompleted, boolean newestFirst) {
        ArrayList<RedPacketItem> result = new ArrayList<>();
        if (source == null) return result;
        String selectedCourse = course == null || course.isBlank() ? "全部课程" : course;
        for (RedPacketItem item : source) {
            if (!"全部课程".equals(selectedCourse) && !selectedCourse.equals(item.course)) continue;
            if (hideCompleted && item.completed) continue;
            result.add(item);
        }
        Comparator<RedPacketItem> order = Comparator.comparingLong(item -> item.claimedAtSeconds);
        if (newestFirst) order = order.reversed();
        Collections.sort(result, order);
        return result;
    }

    private QueuePolicy() {
    }
}
