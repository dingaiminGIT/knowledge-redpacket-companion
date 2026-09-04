package com.dingaimin.dedaocompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Keeps the playback coordinator alive and its main-thread timers running while the screen is off. */
public final class PlaybackGuardService extends Service {
    private static final String CHANNEL_ID = "continuous_playback";
    private static final int NOTIFICATION_ID = 2102;
    private static final long ARM_WINDOW_MS = 30_000L;
    private static final long STALE_QUEUE_MS = 2 * 60_000L;
    private static final long WAKE_LOCK_WINDOW_MS = 30 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = this::runWatchdog;
    private PowerManager.WakeLock wakeLock;
    private long armedUntil;

    static boolean start(Context context) {
        if (context == null) return false;
        try {
            Intent intent = new Intent(context, PlaybackGuardService.class);
            intent.setAction("arm");
            context.startForegroundService(intent);
            record(context, "requested");
            return true;
        } catch (Exception error) {
            record(context, "failed:" + error.getClass().getSimpleName());
            return false;
        }
    }

    static void stop(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, PlaybackGuardService.class));
        } catch (Exception ignored) {
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "知识红包连续播放", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("连续播放期间保持监控，支持息屏自动衔接");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, notification("息屏后仍会自动播放下一条"));
        armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS;
        record(this, "active");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "arm".equals(intent.getAction())) {
            armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS;
        }
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        return START_STICKY;
    }

    private void runWatchdog() {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean queueActive = probe.getBoolean("companion_queue_active", false);
        long lastActivityAt = Math.max(
                probe.getLong("playback_monitor_last_playing_at", 0L),
                probe.getLong("continuous_requested_at", 0L));
        if (queueActive && lastActivityAt > 0L
                && System.currentTimeMillis() - lastActivityAt > STALE_QUEUE_MS) {
            queueActive = false;
            probe.edit()
                    .putBoolean("companion_queue_active", false)
                    .putString("playback_monitor_status", "上次连续播放已结束")
                    .apply();
        }
        boolean armed = SystemClock.elapsedRealtime() < armedUntil;
        if (!queueActive && !armed) {
            stopSelf();
            return;
        }
        acquireWakeLock();
        DedaoSessionListener.ensureConnected(this);
        probe.edit()
                .putString("playback_guard_status", queueActive ? "守护中" : "准备中")
                .putLong("playback_guard_watchdog_at", System.currentTimeMillis())
                .apply();
        handler.postDelayed(watchdog, 5_000L);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return;
        wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":continuous-playback");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_WINDOW_MS);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("知识红包连续播放中")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        record(this, "stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static void record(Context context, String status) {
        context.getSharedPreferences("page_probe", Context.MODE_PRIVATE).edit()
                .putString("playback_guard_status", status)
                .putLong("playback_guard_at", System.currentTimeMillis())
                .apply();
    }
}
