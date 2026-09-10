package com.dingaimin.dedaocompanion

object PlaybackCompletionPolicy {
    private const val END_WINDOW_MS = 3_000L

    fun reachedEnd(furthestPosition: Long, duration: Long): Boolean =
        duration > 0 &&
            furthestPosition.coerceAtLeast(0) >= (duration - END_WINDOW_MS).coerceAtLeast(0)

    fun probeDelay(position: Long, duration: Long): Long =
        if (duration > 0 && position >= duration - 5_000L) 250L else 1_000L

    fun updateFurthest(previous: Long, position: Long, playing: Boolean): Long =
        if (playing && position + END_WINDOW_MS < previous) position.coerceAtLeast(0)
        else maxOf(previous, position, 0L)
}
