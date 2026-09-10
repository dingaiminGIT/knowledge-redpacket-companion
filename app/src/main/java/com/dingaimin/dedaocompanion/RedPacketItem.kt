package com.dingaimin.dedaocompanion

import org.json.JSONObject

class RedPacketItem(
    val id: String,
    val type: Int,
    val course: String,
    val title: String,
    val deepLink: String,
    val audioId: String,
    val claimedAtSeconds: Long,
    val completed: Boolean,
) {

    constructor(
        id: String,
        type: Int,
        course: String,
        title: String,
        deepLink: String,
        claimedAtSeconds: Long,
    ) : this(id, type, course, title, deepLink, "", claimedAtSeconds, false) {}

    constructor(
        id: String,
        type: Int,
        course: String,
        title: String,
        deepLink: String,
        claimedAtSeconds: Long,
        completed: Boolean,
    ) : this(id, type, course, title, deepLink, "", claimedAtSeconds, completed) {}

    fun toJson(): JSONObject {
        val value: JSONObject = JSONObject()
        try {
            value.put("id", id)
            value.put("type", type)
            value.put("course", course)
            value.put("title", title)
            value.put("deepLink", deepLink)
            value.put("audioId", audioId)
            value.put("claimedAt", claimedAtSeconds)
            value.put("completed", completed)
        } catch (ignored: Exception) {}

        return value
    }

    companion object {

        fun from(raw: JSONObject): RedPacketItem {
            val articleCollection: Boolean = raw.optBoolean("is_article_collection", false)
            val article: JSONObject? = raw.optJSONObject("article_item")
            val source: JSONObject = if (articleCollection && article != null) article else raw

            val course: String = first(raw, "product_title", "title", "name")
            var title: String = first(source, "product_title", "title", "name")
            if (title.isEmpty()) title = course

            val id: String = first(source, "id", "product_id", "id_str", "alias_id")
            val type: Int = source.optInt("product_type", raw.optInt("product_type", 0))
            var link: String = first(source, "dd_url", "ddurl", "ext_ddurl")
            if (link.isEmpty()) link = first(raw, "dd_url", "ddurl", "ext_ddurl")

            var claimed: Long =
                longValue(
                    raw,
                    "collection_timestamp",
                    "receive_time",
                    "received_at",
                    "claim_time",
                    "created_at",
                )
            if (claimed > 10_000_000_000L) claimed /= 1000L
            val completed: Boolean =
                (raw.optBoolean("completed", false) ||
                    raw.optBoolean("is_completed", false) ||
                    first(raw, "progress", "learn_status", "study_status").contains("已学完"))
            val resource: JSONObject? = source.optJSONObject("resource")
            val audioId: String =
                if (resource == null) "" else first(resource, "audio_id", "audioId")
            return RedPacketItem(id, type, course, title, link, audioId, claimed, completed)
        }

        fun fromStored(value: JSONObject): RedPacketItem {
            return RedPacketItem(
                value.optString("id"),
                value.optInt("type"),
                value.optString("course"),
                value.optString("title"),
                value.optString("deepLink"),
                value.optString("audioId"),
                value.optLong("claimedAt"),
                value.optBoolean("completed", false),
            )
        }

        private fun first(`object`: JSONObject, vararg keys: String): String {
            for (key: String in keys) {
                val value: Any? = `object`.opt(key)
                if (value != null && value !== JSONObject.NULL) {
                    val text: String = (value).toString().trim({ it <= ' ' })
                    if (!text.isEmpty()) return text
                }
            }
            return ""
        }

        private fun longValue(`object`: JSONObject, vararg keys: String): Long {
            for (key: String in keys) {
                val value: Any? = `object`.opt(key)
                if (value is Number) return (value as Number).toLong()
                if (value != null) {
                    try {
                        return java.lang.Long.parseLong((value).toString())
                    } catch (ignored: NumberFormatException) {}
                }
            }
            return 0L
        }
    }
}
