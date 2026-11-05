package com.dingaimin.dedaocompanion

/** Metadata does not identify who pressed play. An unrequested title must never be overridden. */
object PlaybackOwnershipPolicy {
    /** Rearm monitoring only after an observed pause of an explicitly owned queue item. */
    fun shouldResumeQueue(
        owned: String,
        pausedTitle: String,
        actual: String,
        suspended: Boolean,
        active: Boolean,
        pending: String,
    ): Boolean =
        suspended && !active && pending.isBlank() && owned.isNotBlank() &&
            pausedTitle == owned && actual == owned

    fun shouldYield(
        owned: String,
        pending: String,
        previous: String,
        actual: String,
        targetSeen: Boolean = false,
    ): Boolean {
        if (actual.isBlank()) return false // Loading metadata is not a new selection.
        if (pending.isNotBlank()) return actual != pending && (targetSeen || actual != previous)
        return owned.isNotBlank() && actual != owned
    }
}
