package com.dingaimin.dedaocompanion

import android.content.ComponentName
import android.content.Context
import android.content.Intent

object OfficialPlayerControl {
    private val DEDAO_PACKAGE: String = "com.luojilab.player"
    private val DEDAO_PLAYER_SERVICE: String = "com.luojilab.service.DDPlayerService"

    val PLAY: Int = 3
    val PAUSE: Int = 4
    val PREVIOUS: Int = 7
    val NEXT: Int = 8

    fun send(context: Context?, command: Int): Boolean {
        if (context == null) return false
        val action: String? = serviceAction(command)
        if (action != null && sendToExportedService(context, action)) return true
        return sendLegacyBroadcast(context, command)
    }

    fun skip(context: Context?, index: Int): Boolean {
        if (context == null || index < 0) return false
        try {
            val intent: Intent = Intent("skip")
            intent.setComponent(ComponentName(DEDAO_PACKAGE, DEDAO_PLAYER_SERVICE))
            intent.putExtra("index", index)
            val started: ComponentName? = context!!.startService(intent)
            record(context!!, "skip", if (started == null) "service_null" else "service", "")
            context!!
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putInt("official_control_index", index)
                .apply()
            return started != null
        } catch (error: Exception) {
            record(context!!, "skip", "service_failed", error.javaClass.getSimpleName())
            return false
        }
    }

    fun open(context: Context?, audioId: String?): Boolean {
        if (context == null || audioId == null || audioId!!.isBlank()) return false
        try {
            val intent: Intent = Intent("open")
            intent.setComponent(ComponentName(DEDAO_PACKAGE, DEDAO_PLAYER_SERVICE))
            intent.putExtra("AUDIO_ID", audioId)
            val started: ComponentName? = context!!.startService(intent)
            // Never persist or log the selector itself. It is cached only with the
            // sanitized red-packet snapshot that needs it for later playback.
            record(context!!, "open", if (started == null) "service_null" else "service", "")
            return started != null
        } catch (error: Exception) {
            record(context!!, "open", "service_failed", error.javaClass.getSimpleName())
            return false
        }
    }

    private fun sendToExportedService(context: Context, action: String): Boolean {
        try {
            val intent: Intent = Intent(action)
            intent.setComponent(ComponentName(DEDAO_PACKAGE, DEDAO_PLAYER_SERVICE))
            val started: ComponentName? = context.startService(intent)
            record(context, action, if (started == null) "service_null" else "service", "")
            return started != null
        } catch (error: Exception) {
            record(context, action, "service_failed", error.javaClass.getSimpleName())
            return false
        }
    }

    private fun sendLegacyBroadcast(context: Context, command: Int): Boolean {
        try {
            val intent: Intent = Intent("PlayNotifyReceiver")
            intent.setPackage(DEDAO_PACKAGE)
            intent.putExtra("want_do", command)
            context.sendBroadcast(intent)
            record(context, serviceAction(command), "broadcast_fallback", "")
            return true
        } catch (error: Exception) {
            record(context, serviceAction(command), "failed", error.javaClass.getSimpleName())
            return false
        }
    }

    private fun serviceAction(command: Int): String? {
        when (command) {
            PLAY -> return "play"
            PAUSE -> return "pause"
            PREVIOUS -> return "prev"
            NEXT -> return "next"
            else -> return null
        }
    }

    private fun record(context: Context, action: String?, channel: String, error: String) {
        context
            .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("official_control_action", if (action == null) "" else action)
            .putString("official_control_channel", channel)
            .putString("official_control_error", error)
            .putLong("official_control_at", System.currentTimeMillis())
            .apply()
    }
}
