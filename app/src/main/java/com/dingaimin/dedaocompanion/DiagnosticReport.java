package com.dingaimin.dedaocompanion;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class DiagnosticReport {
    private static final long PROCESS_STARTED_AT = System.currentTimeMillis();

    private DiagnosticReport() {}

    static void mark(Context context, String stage, String detail) {
        if (context == null) return;
        context.getSharedPreferences("page_probe", Context.MODE_PRIVATE).edit()
                .putString("bridge_stage", safe(stage))
                .putString("bridge_detail", safe(detail))
                .putLong("bridge_stage_at", System.currentTimeMillis())
                .commit();
    }

    static String build(Context context) {
        SharedPreferences probe = context.getSharedPreferences("page_probe", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long scanStarted = probe.getLong("scan_started_at", 0L);
        long stageAt = probe.getLong("bridge_stage_at", 0L);
        StringBuilder report = new StringBuilder();
        report.append("知识红包伴侣诊断报告\n");
        report.append("生成时间: ").append(time(now)).append('\n');
        report.append("伴侣版本: ").append(version(context)).append('\n');
        report.append("设备: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.BRAND).append(' ').append(Build.MODEL).append('\n');
        report.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT)
                .append(" / ").append(Build.DISPLAY).append('\n');
        report.append("本次进程启动: ").append(time(PROCESS_STARTED_AT)).append('\n');
        report.append("无障碍兼容模式: ").append(accessibilityEnabled(context) ? "已开启" : "未开启").append('\n');
        report.append("连续播放权限: ").append(notificationListenerEnabled(context) ? "已开启" : "未开启").append('\n');
        report.append("连续播放监控: ").append(DedaoSessionListener.isConnected() ? "已连接" : "未连接").append('\n');
        report.append("连续播放状态: ").append(probe.getString("playback_monitor_status", "-")).append('\n');
        report.append("连续播放队列: ").append(probe.getBoolean("companion_queue_active", false) ? "运行中" : "未运行").append('\n');
        report.append("连续播放守护: ").append(probe.getString("playback_guard_status", "-")).append('\n');
        android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
        report.append("显示播放通知: ").append(notifications != null && notifications.areNotificationsEnabled() ? "已允许" : "未允许").append('\n');
        report.append("守护采样距今: ").append(ageSeconds(now, probe.getLong("playback_guard_watchdog_at", 0L))).append('\n');
        report.append("播放采样距今: ").append(ageSeconds(now, probe.getLong("playback_monitor_last_callback_at", 0L))).append('\n');
        report.append("后台省电豁免: ").append(batteryExempt(context) ? "已允许" : "未允许").append('\n');
        report.append("监控采样来源: ").append(probe.getString("playback_monitor_last_source", "-")).append('\n');
        report.append("下一条启动尝试: ").append(probe.getInt("playback_monitor_open_attempts", 0)).append('\n');
        report.append("最近衔接阶段: ").append(probe.getString("playback_transition_stage", "-")).append('\n');
        report.append("最近衔接耗时(ms): ").append(probe.getLong("playback_transition_ms", -1L)).append('\n');
        report.append("同步进行中: ").append(probe.getBoolean("scan_requested", false)).append('\n');
        report.append("同步尝试: ").append(probe.getString("scan_attempt_id", "-")).append('\n');
        report.append("同步已用时: ").append(ageSeconds(now, scanStarted)).append('\n');
        report.append("同步来源: ").append(probe.getString("scan_source", "-")).append('\n');
        report.append("同步状态: ").append(probe.getString("scan_status", "-")).append('\n');
        report.append("最后阶段: ").append(probe.getString("bridge_stage", "-")).append('\n');
        report.append("阶段详情: ").append(probe.getString("bridge_detail", "-")).append('\n');
        report.append("阶段距今: ").append(ageSeconds(now, stageAt)).append('\n');
        report.append("桥接保活: ").append(probe.getString("keepalive_status", "-")).append('\n');
        report.append("有效红包数: ").append(probe.getInt("scan_count", 0)).append('\n');
        report.append("最后外部动作: ").append(probe.getString("last_external_action", "-")).append('\n');
        appendLastExit(context, report);
        report.append("说明: 报告不包含红包标题、课程内容或登录信息");
        return report.toString();
    }

    static boolean isHuaweiFamily() {
        String value = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        return value.contains("huawei") || value.contains("honor");
    }

    private static boolean accessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = new ComponentName(context, DedaoAccessibilityService.class).flattenToString();
        return enabled != null && enabled.contains(component);
    }

    private static boolean notificationListenerEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        String component = new ComponentName(context, DedaoSessionListener.class).flattenToString();
        return enabled != null && enabled.contains(component);
    }

    private static boolean batteryExempt(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static void appendLastExit(Context context, StringBuilder report) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return;
            List<ApplicationExitInfo> history = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 1);
            if (history == null || history.isEmpty()) return;
            ApplicationExitInfo exit = history.get(0);
            report.append("上次进程退出: reason=").append(exit.getReason())
                    .append(", status=").append(exit.getStatus())
                    .append(", at=").append(time(exit.getTimestamp())).append('\n');
        } catch (Exception ignored) {
        }
    }

    private static String ageSeconds(long now, long timestamp) {
        return timestamp <= 0L ? "-" : Math.max(0L, (now - timestamp) / 1_000L) + " 秒";
    }

    private static String time(long timestamp) {
        if (timestamp <= 0L) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(timestamp));
    }

    private static String safe(String value) {
        if (value == null) return "";
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 240 ? value : value.substring(0, 240);
    }
}
