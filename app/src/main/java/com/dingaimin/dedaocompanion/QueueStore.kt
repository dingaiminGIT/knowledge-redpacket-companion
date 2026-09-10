package com.dingaimin.dedaocompanion

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.ArrayList
import org.json.JSONArray
import org.json.JSONObject

object QueueStore {
    private val PREFS: String = "play_queue"
    private val ITEMS: String = "items"
    private val INDEX: String = "index"

    fun save(context: Context, items: List<RedPacketItem>, index: Int) {
        val array: JSONArray = JSONArray()
        for (item: RedPacketItem in items) array.put(item.toJson())
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ITEMS, array.toString())
            .putInt(INDEX, index)
            .apply()
    }

    fun current(context: Context): RedPacketItem? {
        val items: List<RedPacketItem> = load(context)
        val index: Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0)
        return if (index >= 0 && index < items.size) items.get(index) else null
    }

    fun selectTitle(context: Context, title: String?): RedPacketItem? {
        if (title == null || title!!.isBlank()) return null
        val items: List<RedPacketItem> = load(context)
        for (i: Int in items.indices) {
            val item: RedPacketItem = items.get(i)
            if (title != item.title) continue
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(INDEX, i)
                .apply()
            return item
        }
        return null
    }

    fun findTitle(context: Context, title: String?): RedPacketItem? {
        if (title == null || title!!.isBlank()) return null
        for (item: RedPacketItem in load(context)) {
            if (title == item.title) return item
        }
        return null
    }

    fun advanceAndOpen(context: Context): Boolean {
        val item: RedPacketItem? = advance(context)
        return item != null && open(context, item)
    }

    fun advance(context: Context): RedPacketItem? {
        val items: List<RedPacketItem> = load(context)
        val next: Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0) + 1
        if (next >= items.size) return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(INDEX, next).apply()
        return items.get(next)
    }

    fun hasNext(context: Context): Boolean {
        val items: List<RedPacketItem> = load(context)
        val index: Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0)
        return index >= 0 && index + 1 < items.size
    }

    fun retreat(context: Context): RedPacketItem? {
        val items: List<RedPacketItem> = load(context)
        val previous: Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INDEX, 0) - 1
        if (previous < 0 || previous >= items.size) return null
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(INDEX, previous)
            .apply()
        return items.get(previous)
    }

    fun retainValidAndSelectAdjacent(
        context: Context,
        validTitles: Set<String>,
        rejectedTitle: String,
        direction: Int,
    ): RedPacketItem? {
        val original: List<RedPacketItem> = load(context)
        val selection: QueueSelection =
            selectAfterReject(original, validTitles, rejectedTitle, direction)
        save(context, selection.retained, selection.selectedIndex)
        return selection.candidate
    }

    fun selectAfterReject(
        original: List<RedPacketItem>,
        validTitles: Set<String>,
        rejectedTitle: String,
        direction: Int,
    ): QueueSelection {
        var rejectedIndex: Int = -1
        for (i: Int in original.indices) {
            if (original.get(i).title == rejectedTitle) {
                rejectedIndex = i
                break
            }
        }

        var candidate: RedPacketItem? = null
        if (rejectedIndex >= 0 && direction > 0) {
            for (i: Int in rejectedIndex + 1 until original.size) {
                if (validTitles.contains(original.get(i).title)) {
                    candidate = original.get(i)
                    break
                }
            }
        } else if (rejectedIndex >= 0 && direction < 0) {
            for (i: Int in rejectedIndex - 1 downTo 0) {
                if (validTitles.contains(original.get(i).title)) {
                    candidate = original.get(i)
                    break
                }
            }
        }

        val retained: ArrayList<RedPacketItem> = ArrayList<RedPacketItem>()
        var selectedIndex: Int = 0
        for (item: RedPacketItem in original) {
            if (!validTitles.contains(item.title)) continue
            if (candidate != null && candidate!!.title == item.title) selectedIndex = retained.size
            retained.add(item)
        }
        val safeIndex: Int =
            if (retained.isEmpty()) 0 else Math.min(selectedIndex, retained.size - 1)
        return QueueSelection(retained, candidate, safeIndex)
    }

    class QueueSelection(
        val retained: List<RedPacketItem>,
        val candidate: RedPacketItem?,
        val selectedIndex: Int,
    )

    fun open(context: Context, item: RedPacketItem?): Boolean {
        if (item == null) return false
        if (
            (item!!.audioId != null &&
                !item!!.audioId.isBlank() &&
                OfficialPlayerControl.open(context, item!!.audioId))
        )
            return true
        if (item!!.deepLink == null || item!!.deepLink.isBlank()) return false
        try {
            val intent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(item!!.deepLink))
            intent.setPackage("com.luojilab.player")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (ignored: Exception) {
            return false
        }
    }

    private fun load(context: Context): List<RedPacketItem> {
        val result: ArrayList<RedPacketItem> = ArrayList<RedPacketItem>()
        val json: String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, "[]") ?: "[]"
        try {
            val array: JSONArray = JSONArray(json)
            for (i: Int in 0 until array.length()) {
                val value: JSONObject? = array.optJSONObject(i)
                if (value != null) result.add(RedPacketItem.fromStored(value!!))
            }
        } catch (ignored: Exception) {}

        return result
    }
}
