package com.dingaimin.dedaocompanion

import android.content.Context

object PlaybackOwnership {
    fun request(context: Context, target: String, previous: String) {
        context
            .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("companion_owned_title", target)
            .putString("direct_open_target_title", target)
            .putBoolean("direct_open_target_seen", false)
            .putString("direct_open_previous_title", previous)
            .apply()
    }

    fun releaseIfExternal(context: Context, actual: String): Boolean {
        val prefs = context.getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val involved =
            prefs.getBoolean("companion_queue_active", false) ||
                prefs.getBoolean("companion_queue_suspended", false) ||
                !prefs.getString("direct_open_target_title", "").isNullOrBlank()
        val savedOwner = prefs.getString("companion_owned_title", null)
        // Pre-fix persisted flags are not proof that the user requested control in this version.
        val staleLegacyOwnership = involved && savedOwner == null
        val owned = savedOwner.orEmpty()
        if (
            !staleLegacyOwnership &&
                !PlaybackOwnershipPolicy.shouldYield(
                    owned,
                    prefs.getString("direct_open_target_title", "").orEmpty(),
                    prefs.getString("direct_open_previous_title", "").orEmpty(),
                    actual,
                    prefs.getBoolean("direct_open_target_seen", false),
                )
        ) {
            if (actual.isNotBlank() && actual == prefs.getString("direct_open_target_title", "")) {
                prefs.edit().putBoolean("direct_open_target_seen", true).apply()
            }
            return false
        }
        prefs
            .edit()
            .putBoolean("companion_queue_active", false)
            .putBoolean("companion_queue_suspended", false)
            .remove("companion_owned_title")
            .remove("direct_open_target_title")
            .remove("direct_open_previous_title")
            .putString("playback_monitor_status", "已让出播放控制，请在伴侣中主动重新开始连续播放")
            .putLong("playback_control_released_at", System.currentTimeMillis())
            .apply()
        DedaoAccessibilityService.cancelPendingPlayback(context)
        PlaybackGuardService.stop(context)
        MainActivity.onExternalPlaybackSelected()
        return true
    }
}
