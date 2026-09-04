package com.dingaimin.dedaocompanion;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class QueueStore {
    private static final String PREFS = "play_queue";
    private static final String ITEMS = "items";
    private static final String INDEX = "index";

    static void save(Context context, List<RedPacketItem> items, int index) {
        JSONArray array = new JSONArray();
        for (RedPacketItem item : items) array.put(item.toJson());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ITEMS, array.toString())
                .putInt(INDEX, index)
                .apply();
    }

    static RedPacketItem current(Context context) {
        List<RedPacketItem> items = load(context);
        int index = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0);
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    static RedPacketItem selectTitle(Context context, String title) {
        if (title == null || title.isBlank()) return null;
        List<RedPacketItem> items = load(context);
        for (int i = 0; i < items.size(); i++) {
            RedPacketItem item = items.get(i);
            if (!title.equals(item.title)) continue;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(INDEX, i)
                    .apply();
            return item;
        }
        return null;
    }

    static RedPacketItem findTitle(Context context, String title) {
        if (title == null || title.isBlank()) return null;
        for (RedPacketItem item : load(context)) {
            if (title.equals(item.title)) return item;
        }
        return null;
    }

    static boolean advanceAndOpen(Context context) {
        RedPacketItem item = advance(context);
        return item != null && open(context, item);
    }

    static RedPacketItem advance(Context context) {
        List<RedPacketItem> items = load(context);
        int next = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0) + 1;
        if (next >= items.size()) return null;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(INDEX, next).apply();
        return items.get(next);
    }

    static boolean hasNext(Context context) {
        List<RedPacketItem> items = load(context);
        int index = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0);
        return index >= 0 && index + 1 < items.size();
    }

    static RedPacketItem retreat(Context context) {
        List<RedPacketItem> items = load(context);
        int previous = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0) - 1;
        if (previous < 0 || previous >= items.size()) return null;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(INDEX, previous).apply();
        return items.get(previous);
    }

    static RedPacketItem retainValidAndSelectAdjacent(Context context, Set<String> validTitles,
                                                       String rejectedTitle, int direction) {
        List<RedPacketItem> original = load(context);
        QueueSelection selection = selectAfterReject(original, validTitles, rejectedTitle, direction);
        save(context, selection.retained, selection.selectedIndex);
        return selection.candidate;
    }

    static QueueSelection selectAfterReject(List<RedPacketItem> original, Set<String> validTitles,
                                             String rejectedTitle, int direction) {
        int rejectedIndex = -1;
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i).title.equals(rejectedTitle)) {
                rejectedIndex = i;
                break;
            }
        }

        RedPacketItem candidate = null;
        if (rejectedIndex >= 0 && direction > 0) {
            for (int i = rejectedIndex + 1; i < original.size(); i++) {
                if (validTitles.contains(original.get(i).title)) {
                    candidate = original.get(i);
                    break;
                }
            }
        } else if (rejectedIndex >= 0 && direction < 0) {
            for (int i = rejectedIndex - 1; i >= 0; i--) {
                if (validTitles.contains(original.get(i).title)) {
                    candidate = original.get(i);
                    break;
                }
            }
        }

        ArrayList<RedPacketItem> retained = new ArrayList<>();
        int selectedIndex = 0;
        for (RedPacketItem item : original) {
            if (!validTitles.contains(item.title)) continue;
            if (candidate != null && candidate.title.equals(item.title)) selectedIndex = retained.size();
            retained.add(item);
        }
        int safeIndex = retained.isEmpty() ? 0 : Math.min(selectedIndex, retained.size() - 1);
        return new QueueSelection(retained, candidate, safeIndex);
    }

    static final class QueueSelection {
        final List<RedPacketItem> retained;
        final RedPacketItem candidate;
        final int selectedIndex;

        QueueSelection(List<RedPacketItem> retained, RedPacketItem candidate, int selectedIndex) {
            this.retained = retained;
            this.candidate = candidate;
            this.selectedIndex = selectedIndex;
        }
    }

    static boolean open(Context context, RedPacketItem item) {
        if (item == null) return false;
        if (item.audioId != null && !item.audioId.isBlank()
                && OfficialPlayerControl.open(context, item.audioId)) return true;
        if (item.deepLink == null || item.deepLink.isBlank()) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.deepLink));
            intent.setPackage("com.luojilab.player");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<RedPacketItem> load(Context context) {
        ArrayList<RedPacketItem> result = new ArrayList<>();
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value != null) result.add(RedPacketItem.fromStored(value));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private QueueStore() {
    }
}
