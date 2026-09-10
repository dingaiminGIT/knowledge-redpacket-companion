package com.dingaimin.dedaocompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** A startActivity call is only an attempt: Android may silently block a background launch. */
object BridgeReturn {
    private const val CHANNEL = "redpacket_result"
    private const val NOTIFICATION = 2102
    private val handler = Handler(Looper.getMainLooper())

    fun request(context: Context, success: Boolean) {
        val app = context.applicationContext
        handler.post {
            val attempt = System.nanoTime()
            val prefs = app.getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            prefs
                .edit()
                .putLong("return_attempt", attempt)
                .putBoolean("return_pending", true)
                .putString("return_notice", "not_needed")
                .apply()
            DiagnosticReport.mark(app, "return_requested", if (success) "complete" else "failed")
            val intent =
                Intent(app, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data =
                        Uri.parse(
                            "dedaocompanion://bridge/" + if (success) "complete" else "failed"
                        )
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
            // Let the visible official page use its native navigation first.
            handler.postDelayed(
                {
                    if (
                        prefs.getLong("return_attempt", 0) != attempt ||
                            !prefs.getBoolean("return_pending", false)
                    )
                        return@postDelayed
                    DiagnosticReport.mark(
                        app,
                        "background_return_attempted",
                        "native_return_unconfirmed",
                    )
                    try {
                        app.startActivity(intent)
                    } catch (error: Exception) {
                        DiagnosticReport.mark(
                            app,
                            "background_return_failed",
                            error.javaClass.simpleName,
                        )
                    }
                },
                800L,
            )
            handler.postDelayed(
                {
                    if (
                        prefs.getLong("return_attempt", 0) != attempt ||
                            !prefs.getBoolean("return_pending", false)
                    )
                        return@postDelayed
                    // Only onResume acknowledges success, never startActivity or onNewIntent.
                    DiagnosticReport.mark(app, "return_needs_user", "foreground_not_confirmed")
                    val manager = app.getSystemService(NotificationManager::class.java)
                    if (manager == null || !manager.areNotificationsEnabled()) {
                        prefs.edit().putString("return_notice", "notifications_disabled").apply()
                        return@postDelayed
                    }
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL,
                            "红包刷新结果",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                    )
                    val pending =
                        PendingIntent.getActivity(
                            app,
                            NOTIFICATION,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    try {
                        manager.notify(
                            NOTIFICATION,
                            Notification.Builder(app, CHANNEL)
                                .setSmallIcon(android.R.drawable.stat_notify_sync)
                                .setContentTitle(if (success) "红包已刷新" else "红包确认未完成")
                                .setContentText("自动返回未成功，点此回到知识红包伴侣")
                                .setContentIntent(pending)
                                .setAutoCancel(true)
                                .setTimeoutAfter(300_000L)
                                .build(),
                        )
                        prefs.edit().putString("return_notice", "posted").apply()
                    } catch (error: SecurityException) {
                        prefs.edit().putString("return_notice", "permission_denied").apply()
                    }
                },
                1_500L,
            )
        }
    }

    fun onResumed(context: Context) {
        val prefs = context.getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (prefs.getBoolean("return_pending", false)) {
            DiagnosticReport.mark(context, "companion_returned", "foreground_confirmed")
        }
        reset(context)
    }

    fun reset(context: Context) {
        context
            .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("return_pending", false)
            .apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
    }
}
