package com.dingaimin.dedaocompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    static List<RedPacketItem> applyCustomOrder(List<RedPacketItem> source,
                                                 List<String> orderedKeys) {
        ArrayList<RedPacketItem> result = new ArrayList<>();
        if (source == null) return result;
        result.addAll(source);
        if (orderedKeys == null || orderedKeys.isEmpty()) return result;

        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < orderedKeys.size(); i++) {
            String key = orderedKeys.get(i);
            if (key != null && !key.isBlank()) ranks.putIfAbsent(key, i);
        }
        result.sort(Comparator.comparingInt(
                item -> ranks.getOrDefault(stableKey(item), Integer.MAX_VALUE)));
        return result;
    }

    static String stableKey(RedPacketItem item) {
        if (item == null) return "";
        if (item.id != null && !item.id.isBlank()) return "id:" + item.id + ":" + item.type;
        return "title:" + item.course + ":" + item.title;
    }

    static List<RedPacketItem> excluding(List<RedPacketItem> source, Set<String> removedKeys) {
        ArrayList<RedPacketItem> result = new ArrayList<>();
        for (RedPacketItem item : source) {
            if (!removedKeys.contains(stableKey(item))) result.add(item);
        }
        return result;
    }

    /** Preserve the current identity; deleting it selects its successor, then its predecessor. */
    static int indexAfterRemoval(int currentIndex, int removedIndex, int originalSize) {
        if (originalSize <= 1) return -1;
        int adjusted = currentIndex > removedIndex ? currentIndex - 1 : currentIndex;
        return Math.max(0, Math.min(adjusted, originalSize - 2));
    }

    static List<String> mergeVisibleOrder(List<String> globalOrder,
                                          List<String> reorderedVisibleKeys) {
        ArrayList<String> result = new ArrayList<>();
        if (globalOrder != null) result.addAll(globalOrder);
        if (reorderedVisibleKeys == null || reorderedVisibleKeys.isEmpty()) return result;
        Set<String> visible = new LinkedHashSet<>(reorderedVisibleKeys);
        int replacement = 0;
        for (int i = 0; i < result.size() && replacement < reorderedVisibleKeys.size(); i++) {
            if (visible.contains(result.get(i))) {
                result.set(i, reorderedVisibleKeys.get(replacement++));
            }
        }
        return result;
    }

    private QueuePolicy() {
    }
}
