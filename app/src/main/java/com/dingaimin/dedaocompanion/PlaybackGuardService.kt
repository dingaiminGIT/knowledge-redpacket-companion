package com.dingaimin.dedaocompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Keeps the playback coordinator alive and its main-thread timers running while the screen is off.
 */
class PlaybackGuardService : Service() {

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val watchdog: Runnable = Runnable({ this.runWatchdog() })
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt: Long = 0
    private var armedUntil: Long = 0

    public override fun onCreate() {
        super.onCreate()
        val manager: NotificationManager? =
            getSystemService<NotificationManager>(NotificationManager::class.java)
        if (manager != null) {
            val channel: NotificationChannel =
                NotificationChannel(CHANNEL_ID, "知识红包连续播放", NotificationManager.IMPORTANCE_LOW)
            channel.setDescription("连续播放期间保持监控，支持息屏自动衔接")
            channel.setShowBadge(false)
            manager!!.createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, notification("正在监控播放进度 · 请允许后台运行"))
        armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        record(this, "active")
    }

    public override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_ARM == intent!!.getAction()) {
            armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        }
        if (
            (intent != null &&
                ((ACTION_ARM == intent!!.getAction() || ACTION_PULSE == intent!!.getAction())))
        ) {
            renewWakeLock()
        }
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        return Service.START_STICKY
    }

    private fun runWatchdog() {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val queueActive: Boolean = probe.getBoolean("companion_queue_active", false)
        val armed: Boolean = SystemClock.elapsedRealtime() < armedUntil
        if (!queueActive && !armed) {
            stopSelf()
            return
        }
        acquireWakeLock()
        DedaoSessionListener.ensureConnected(this)
        probe
            .edit()
            .putString("playback_guard_status", if (queueActive) "守护中" else "准备中")
            .putLong("playback_guard_watchdog_at", System.currentTimeMillis())
            .apply()
        handler.postDelayed(watchdog, 5_000L)
    }

    private fun acquireWakeLock() {
        // Refresh before expiry, even when a long episode produces no notifications.
        // Ordinary notification bursts must not release/re-acquire the CPU lock each time.
        if (wakeLock != null && wakeLock!!.isHeld()) {
            if (SystemClock.elapsedRealtime() - wakeLockAcquiredAt < WAKE_LOCK_WINDOW_MS - 60_000L)
                return
            wakeLock!!.release()
        }
        val power: PowerManager? = getSystemService<PowerManager>(PowerManager::class.java)
        if (power == null) return
        wakeLock =
            power!!.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":continuous-playback",
            )
        wakeLock!!.setReferenceCounted(false)
        wakeLock!!.acquire(WAKE_LOCK_WINDOW_MS)
        wakeLockAcquiredAt = SystemClock.elapsedRealtime()
    }

    private fun renewWakeLock() {
        acquireWakeLock()
    }

    private fun notification(text: String): Notification {
        val open: Intent = Intent(this, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent: PendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("知识红包连续播放中")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    public override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (wakeLock != null && wakeLock!!.isHeld()) wakeLock!!.release()
        wakeLock = null
        record(this, "stopped")
        super.onDestroy()
    }

    public override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        private val CHANNEL_ID: String = "continuous_playback"
        private val NOTIFICATION_ID: Int = 2102
        private val ACTION_ARM: String = "arm"
        private val ACTION_PULSE: String = "pulse"
        private val ARM_WINDOW_MS: Long = 30_000L
        private val WAKE_LOCK_WINDOW_MS: Long = 30 * 60_000L

        fun start(context: Context?): Boolean {
            if (context == null) return false
            try {
                val intent: Intent = Intent(context, PlaybackGuardService::class.java)
                intent.setAction(ACTION_ARM)
                context!!.startForegroundService(intent)
                record(context!!, "requested")
                return true
            } catch (error: Exception) {
                record(context!!, "failed:" + error.javaClass.getSimpleName())
                return false
            }
        }

        /** Renews the CPU wake window when the system delivers a player notification. */
        fun pulse(context: Context?): Boolean {
            if (context == null) return false
            try {
                val intent: Intent = Intent(context, PlaybackGuardService::class.java)
                intent.setAction(ACTION_PULSE)
                context!!.startForegroundService(intent)
                record(context!!, "pulsed")
                return true
            } catch (error: Exception) {
                record(context!!, "pulse_failed:" + error.javaClass.getSimpleName())
                return false
            }
        }

        fun stop(context: Context?) {
            if (context == null) return
            try {
                context!!.stopService(Intent(context, PlaybackGuardService::class.java))
                record(context!!, "stopped")
            } catch (ignored: Exception) {}
        }

        private fun record(context: Context, status: String) {
            context
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("playback_guard_status", status)
                .putLong("playback_guard_at", System.currentTimeMillis())
                .apply()
        }
    }
}
