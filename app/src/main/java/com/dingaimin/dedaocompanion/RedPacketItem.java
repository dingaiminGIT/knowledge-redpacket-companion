package com.dingaimin.dedaocompanion;

import org.json.JSONObject;

final class RedPacketItem {
    final String id;
    final int type;
    final String course;
    final String title;
    final String deepLink;
    final String audioId;
    final long claimedAtSeconds;
    final boolean completed;

    RedPacketItem(String id, int type, String course, String title, String deepLink, long claimedAtSeconds) {
        this(id, type, course, title, deepLink, "", claimedAtSeconds, false);
    }

    RedPacketItem(String id, int type, String course, String title, String deepLink,
                  long claimedAtSeconds, boolean completed) {
        this(id, type, course, title, deepLink, "", claimedAtSeconds, completed);
    }

    RedPacketItem(String id, int type, String course, String title, String deepLink,
                  String audioId, long claimedAtSeconds, boolean completed) {
        this.id = id;
        this.type = type;
        this.course = course;
        this.title = title;
        this.deepLink = deepLink;
        this.audioId = audioId;
        this.claimedAtSeconds = claimedAtSeconds;
        this.completed = completed;
    }

    static RedPacketItem from(JSONObject raw) {
        boolean articleCollection = raw.optBoolean("is_article_collection", false);
        JSONObject article = raw.optJSONObject("article_item");
        JSONObject source = articleCollection && article != null ? article : raw;

        String course = first(raw, "product_title", "title", "name");
        String title = first(source, "product_title", "title", "name");
        if (title.isEmpty()) title = course;

        String id = first(source, "id", "product_id", "id_str", "alias_id");
        int type = source.optInt("product_type", raw.optInt("product_type", 0));
        String link = first(source, "dd_url", "ddurl", "ext_ddurl");
        if (link.isEmpty()) link = first(raw, "dd_url", "ddurl", "ext_ddurl");

        long claimed = longValue(raw, "collection_timestamp", "receive_time", "received_at", "claim_time", "created_at");
        if (claimed > 10_000_000_000L) claimed /= 1000L;
        boolean completed = raw.optBoolean("completed", false)
                || raw.optBoolean("is_completed", false)
                || first(raw, "progress", "learn_status", "study_status").contains("已学完");
        JSONObject resource = source.optJSONObject("resource");
        String audioId = resource == null ? "" : first(resource, "audio_id", "audioId");
        return new RedPacketItem(id, type, course, title, link, audioId, claimed, completed);
    }

    JSONObject toJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("id", id);
            value.put("type", type);
            value.put("course", course);
            value.put("title", title);
            value.put("deepLink", deepLink);
            value.put("audioId", audioId);
            value.put("claimedAt", claimedAtSeconds);
            value.put("completed", completed);
        } catch (Exception ignored) {
        }
        return value;
    }

    static RedPacketItem fromStored(JSONObject value) {
        return new RedPacketItem(
                value.optString("id"),
                value.optInt("type"),
                value.optString("course"),
                value.optString("title"),
                value.optString("deepLink"),
                value.optString("audioId"),
                value.optLong("claimedAt"),
                value.optBoolean("completed", false));
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) return text;
            }
        }
        return "";
    }

    private static long longValue(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }
}
