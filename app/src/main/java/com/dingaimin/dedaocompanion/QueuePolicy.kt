package com.dingaimin.dedaocompanion

/** Pure queue operations: never mutate the source cache or the official player's library. */
object QueuePolicy {
    fun apply(
        source: List<RedPacketItem>?,
        course: String?,
        hideCompleted: Boolean,
        newestFirst: Boolean,
    ): List<RedPacketItem> {
        val selectedCourse = course?.takeUnless { it.isBlank() } ?: "全部课程"
        val filtered =
            source.orEmpty().filter {
                (selectedCourse == "全部课程" || selectedCourse == it.course) &&
                    (!hideCompleted || !it.completed)
            }
        return if (newestFirst) filtered.sortedByDescending { it.claimedAtSeconds }
        else filtered.sortedBy { it.claimedAtSeconds }
    }

    fun applyCustomOrder(
        source: List<RedPacketItem>?,
        orderedKeys: List<String>?,
    ): List<RedPacketItem> {
        val ranks = linkedMapOf<String, Int>()
        orderedKeys.orEmpty().forEachIndexed { index, key ->
            if (key.isNotBlank()) ranks.putIfAbsent(key, index)
        }
        return source.orEmpty().sortedBy { ranks[stableKey(it)] ?: Int.MAX_VALUE }
    }

    fun stableKey(item: RedPacketItem?): String =
        when {
            item == null -> ""
            item.id.isNotBlank() -> "id:${item.id}:${item.type}"
            else -> "title:${item.course}:${item.title}"
        }

    fun excluding(source: List<RedPacketItem>, removedKeys: Set<String>): List<RedPacketItem> =
        source.filterNot { stableKey(it) in removedKeys }

    /** Preserve the current identity; deleting it selects its successor, then its predecessor. */
    fun indexAfterRemoval(currentIndex: Int, removedIndex: Int, originalSize: Int): Int {
        if (originalSize <= 1) return -1
        val adjusted = if (currentIndex > removedIndex) currentIndex - 1 else currentIndex
        return adjusted.coerceIn(0, originalSize - 2)
    }

    fun mergeVisibleOrder(
        globalOrder: List<String>?,
        reorderedVisibleKeys: List<String>?,
    ): List<String> {
        val result = globalOrder.orEmpty().toMutableList()
        val reordered = reorderedVisibleKeys.orEmpty()
        val visible = reordered.toSet()
        var replacement = 0
        result.indices.forEach { index ->
            if (result[index] in visible && replacement < reordered.size) {
                result[index] = reordered[replacement++]
            }
        }
        return result
    }
}
