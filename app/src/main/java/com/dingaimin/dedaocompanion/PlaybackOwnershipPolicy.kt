package com.dingaimin.dedaocompanion

/** Metadata does not identify who pressed play. An unrequested title must never be overridden. */
object PlaybackOwnershipPolicy {
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
