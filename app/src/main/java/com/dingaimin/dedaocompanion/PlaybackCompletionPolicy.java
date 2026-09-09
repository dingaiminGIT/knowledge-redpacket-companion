package com.dingaimin.dedaocompanion;

final class PlaybackCompletionPolicy {
    private static final long END_WINDOW_MS = 3_000L;

    static boolean reachedEnd(long furthestPosition, long duration) {
        return duration > 0L
                && Math.max(0L, furthestPosition) >= Math.max(0L, duration - END_WINDOW_MS);
    }

    static long probeDelay(long position, long duration) {
        return duration > 0L && position >= duration - 5_000L ? 250L : 1_000L;
    }

    static long updateFurthest(long previous, long position, boolean playing) {
        // A backward seek starts a fresh end window. A terminal zero, however, may be EOF.
        if (playing && position + END_WINDOW_MS < previous) return Math.max(0L, position);
        return Math.max(previous, Math.max(0L, position));
    }

    private PlaybackCompletionPolicy() {
    }
}
