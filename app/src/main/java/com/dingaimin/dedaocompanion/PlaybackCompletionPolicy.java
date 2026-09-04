package com.dingaimin.dedaocompanion;

final class PlaybackCompletionPolicy {
    private static final long END_WINDOW_MS = 3_000L;

    static boolean reachedEnd(long furthestPosition, long duration) {
        return duration > 0L
                && Math.max(0L, furthestPosition) >= Math.max(0L, duration - END_WINDOW_MS);
    }

    private PlaybackCompletionPolicy() {
    }
}
