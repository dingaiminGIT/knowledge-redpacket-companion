package com.dingaimin.dedaocompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps the one-shot loopback bridge alive while 得到 is in the foreground. */
class BridgeKeepAliveService : Service() {
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val timeout: Runnable = Runnable({ this.stopSelf() })

    public override fun onCreate() {
        super.onCreate()
        val manager: NotificationManager? =
            getSystemService<NotificationManager>(NotificationManager::class.java)
        if (manager != null) {
            val channel: NotificationChannel =
                NotificationChannel(CHANNEL_ID, "知识红包确认", NotificationManager.IMPORTANCE_LOW)
            channel.setDescription("仅在得到确认知识红包期间保持本机回传连接")
            channel.setShowBadge(false)
            manager!!.createNotificationChannel(channel)
        }
        val builder: Notification.Builder = Notification.Builder(this, CHANNEL_ID)
        val notification: Notification =
            builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("正在确认知识红包")
                .setContentText("完成后可返回知识红包伴侣")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        startForeground(NOTIFICATION_ID, notification)
        handler.postDelayed(timeout, 60_000L)
        record(this, "active")
    }

    public override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 60_000L)
        return Service.START_NOT_STICKY
    }

    public override fun onDestroy() {
        handler.removeCallbacks(timeout)
        record(this, "stopped")
        super.onDestroy()
    }

    public override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        private val CHANNEL_ID: String = "redpacket_bridge"
        private val NOTIFICATION_ID: Int = 2101

        fun start(context: Context): Boolean {
            try {
                val intent: Intent = Intent(context, BridgeKeepAliveService::class.java)
                context.startForegroundService(intent)
                record(context, "requested")
                return true
            } catch (error: Exception) {
                record(context, "failed:" + error.javaClass.getSimpleName())
                return false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BridgeKeepAliveService::class.java))
            } catch (ignored: Exception) {}
        }

        private fun record(context: Context, status: String) {
            context
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("keepalive_status", status)
                .putLong("keepalive_at", System.currentTimeMillis())
                .apply()
        }
    }
}
