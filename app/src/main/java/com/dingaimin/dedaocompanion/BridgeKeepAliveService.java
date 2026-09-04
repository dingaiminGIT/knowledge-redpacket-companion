package com.dingaimin.dedaocompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps the one-shot loopback bridge alive while 得到 is in the foreground. */
public final class BridgeKeepAliveService extends Service {
    private static final String CHANNEL_ID = "redpacket_bridge";
    private static final int NOTIFICATION_ID = 2101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelf;

    static boolean start(Context context) {
        try {
            Intent intent = new Intent(context, BridgeKeepAliveService.class);
            context.startForegroundService(intent);
            record(context, "requested");
            return true;
        } catch (Exception error) {
            record(context, "failed:" + error.getClass().getSimpleName());
            return false;
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, BridgeKeepAliveService.class));
        } catch (Exception ignored) {
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "知识红包确认", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("仅在得到确认知识红包期间保持本机回传连接");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("正在确认知识红包")
                .setContentText("完成后可返回知识红包伴侣")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        handler.postDelayed(timeout, 60_000L);
        record(this, "active");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 60_000L);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(timeout);
        record(this, "stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static void record(Context context, String status) {
        context.getSharedPreferences("page_probe", Context.MODE_PRIVATE).edit()
                .putString("keepalive_status", status)
                .putLong("keepalive_at", System.currentTimeMillis())
                .apply();
    }
}
