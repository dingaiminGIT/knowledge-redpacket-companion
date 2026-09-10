package com.dingaimin.dedaocompanion

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticReport {
    private val PROCESS_STARTED_AT: Long = System.currentTimeMillis()

    val isHuaweiFamily: Boolean
        get() {
            val value: String = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US)
            return value.contains("huawei") || value.contains("honor")
        }

    fun mark(context: Context?, stage: String, detail: String) {
        if (context == null) return
        context!!
            .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("bridge_stage", safe(stage))
            .putString("bridge_detail", safe(detail))
            .putLong("bridge_stage_at", System.currentTimeMillis())
            .commit()
    }

    fun build(context: Context): String {
        val probe: SharedPreferences =
            context.getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val now: Long = System.currentTimeMillis()
        val scanStarted: Long = probe.getLong("scan_started_at", 0L)
        val stageAt: Long = probe.getLong("bridge_stage_at", 0L)
        val report: StringBuilder = StringBuilder()
        report.append("知识红包伴侣诊断报告\n")
        report.append("生成时间: ").append(time(now)).append('\n')
        report.append("伴侣版本: ").append(version(context)).append('\n')
        report
            .append("设备: ")
            .append(Build.MANUFACTURER)
            .append(' ')
            .append(Build.BRAND)
            .append(' ')
            .append(Build.MODEL)
            .append('\n')
        report
            .append("系统: Android ")
            .append(Build.VERSION.RELEASE)
            .append(" / SDK ")
            .append(Build.VERSION.SDK_INT)
            .append(" / ")
            .append(Build.DISPLAY)
            .append('\n')
        report.append("本次进程启动: ").append(time(PROCESS_STARTED_AT)).append('\n')
        report
            .append("无障碍兼容模式: ")
            .append(if (accessibilityEnabled(context)) "已开启" else "未开启")
            .append('\n')
        report
            .append("连续播放权限: ")
            .append(if (notificationListenerEnabled(context)) "已开启" else "未开启")
            .append('\n')
        report
            .append("连续播放监控: ")
            .append(if (DedaoSessionListener.isConnected) "已连接" else "未连接")
            .append('\n')
        report
            .append("连续播放状态: ")
            .append(probe.getString("playback_monitor_status", "-"))
            .append('\n')
        report
            .append("连续播放队列: ")
            .append(if (probe.getBoolean("companion_queue_active", false)) "运行中" else "未运行")
            .append('\n')
        report.append("连续播放守护: ").append(probe.getString("playback_guard_status", "-")).append('\n')
        val notifications: android.app.NotificationManager? =
            context.getSystemService<android.app.NotificationManager>(
                android.app.NotificationManager::class.java
            )
        report
            .append("显示播放通知: ")
            .append(
                if (notifications != null && notifications!!.areNotificationsEnabled()) "已允许"
                else "未允许"
            )
            .append('\n')
        report
            .append("守护采样距今: ")
            .append(ageSeconds(now, probe.getLong("playback_guard_watchdog_at", 0L)))
            .append('\n')
        report
            .append("播放采样距今: ")
            .append(ageSeconds(now, probe.getLong("playback_monitor_last_callback_at", 0L)))
            .append('\n')
        report.append("后台省电豁免: ").append(if (batteryExempt(context)) "已允许" else "未允许").append('\n')
        report
            .append("监控采样来源: ")
            .append(probe.getString("playback_monitor_last_source", "-"))
            .append('\n')
        report
            .append("下一条启动尝试: ")
            .append(probe.getInt("playback_monitor_open_attempts", 0))
            .append('\n')
        report
            .append("最近衔接阶段: ")
            .append(probe.getString("playback_transition_stage", "-"))
            .append('\n')
        report
            .append("最近衔接耗时(ms): ")
            .append(probe.getLong("playback_transition_ms", -1L))
            .append('\n')
        report.append("同步进行中: ").append(probe.getBoolean("scan_requested", false)).append('\n')
        report.append("同步尝试: ").append(probe.getString("scan_attempt_id", "-")).append('\n')
        report.append("同步已用时: ").append(ageSeconds(now, scanStarted)).append('\n')
        report.append("同步来源: ").append(probe.getString("scan_source", "-")).append('\n')
        report.append("同步状态: ").append(probe.getString("scan_status", "-")).append('\n')
        report.append("最后阶段: ").append(probe.getString("bridge_stage", "-")).append('\n')
        report.append("等待回前台: ").append(probe.getBoolean("return_pending", false)).append('\n')
        report.append("回跳通知: ").append(probe.getString("return_notice", "-")).append('\n')
        report.append("阶段详情: ").append(probe.getString("bridge_detail", "-")).append('\n')
        report.append("阶段距今: ").append(ageSeconds(now, stageAt)).append('\n')
        report.append("桥接保活: ").append(probe.getString("keepalive_status", "-")).append('\n')
        report.append("有效红包数: ").append(probe.getInt("scan_count", 0)).append('\n')
        report.append("最后外部动作: ").append(probe.getString("last_external_action", "-")).append('\n')
        appendLastExit(context, report)
        report.append("说明: 报告不包含红包标题、课程内容或登录信息")
        return report.toString()
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        val enabled: String? =
            Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        val component: String =
            ComponentName(context, DedaoAccessibilityService::class.java).flattenToString()
        return enabled != null && enabled!!.contains(component)
    }

    private fun notificationListenerEnabled(context: Context): Boolean {
        val enabled: String? =
            Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners",
            )
        val component: String =
            ComponentName(context, DedaoSessionListener::class.java).flattenToString()
        return enabled != null && enabled!!.contains(component)
    }

    private fun batteryExempt(context: Context): Boolean {
        val power: PowerManager? = context.getSystemService<PowerManager>(PowerManager::class.java)
        return power != null && power!!.isIgnoringBatteryOptimizations(context.getPackageName())
    }

    private fun version(context: Context): String {
        try {
            val info: PackageInfo =
                context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
            val code: Long =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.getLongVersionCode()
                else info.versionCode.toLong()
            return info.versionName + " (" + code + ")"
        } catch (ignored: Exception) {
            return "unknown"
        }
    }

    private fun appendLastExit(context: Context, report: StringBuilder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val manager: ActivityManager? =
                context.getSystemService<ActivityManager>(ActivityManager::class.java)
            if (manager == null) return
            val history: List<ApplicationExitInfo>? =
                manager!!.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1)
            if (history == null || history!!.isEmpty()) return
            val exit: ApplicationExitInfo = history!!.get(0)
            report
                .append("上次进程退出: reason=")
                .append(exit.getReason())
                .append(", status=")
                .append(exit.getStatus())
                .append(", at=")
                .append(time(exit.getTimestamp()))
                .append('\n')
        } catch (ignored: Exception) {}
    }

    private fun ageSeconds(now: Long, timestamp: Long): String {
        return if (timestamp <= 0L) "-" else "${maxOf(0L, (now - timestamp) / 1_000L)} 秒"
    }

    private fun time(timestamp: Long): String {
        if (timestamp <= 0L) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
    }

    private fun safe(value: String?): String {
        var value = value
        if (value == null) return ""
        value = value!!.replace('\n', ' ').replace('\r', ' ').trim({ it <= ' ' })
        return if (value!!.length <= 240) value else value!!.substring(0, 240)
    }
}
