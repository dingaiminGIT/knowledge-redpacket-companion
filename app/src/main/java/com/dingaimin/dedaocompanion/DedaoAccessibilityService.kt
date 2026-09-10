package com.dingaimin.dedaocompanion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.json.JSONArray

class DedaoAccessibilityService : AccessibilityService() {
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val scannedItems: LinkedHashMap<String, RedPacketItem> =
        LinkedHashMap<String, RedPacketItem>()
    private val skippedExpiredKeys: LinkedHashSet<String> = LinkedHashSet<String>()
    private var lastAutoClick: Long = 0
    private var scanning: Boolean = false
    private var scanSteps: Int = 0
    private var scanWaitSteps: Int = 0
    private var scanStartedReading: Boolean = false
    private var unchangedVisibleSteps: Int = 0
    private var skippedExpiredItems: Int = 0
    private var stepsWithoutNewActiveItems: Int = 0
    private var lastVisibleFingerprint: String = ""
    private var playTarget: String = ""
    private var seekBackwardSteps: Int = 0
    private var seekForwardSteps: Int = 0
    private var lastSeekViewportFingerprint: String = ""
    private var unchangedSeekViewportPasses: Int = 0
    private var pendingPlayScheduled: Boolean = false
    private var seekGestureInFlight: Boolean = false
    private var seekPassScheduled: Boolean = false
    private val lastTargetBounds: Rect = Rect()
    private var stableTargetChecks: Int = 0
    private var windowManager: WindowManager? = null
    private var automationCover: View? = null
    private var automationCoverBitmap: Bitmap? = null
    private val coverFailSafe: Runnable = Runnable {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (
            (probe.getBoolean("official_sync_active", false) ||
                probe.getBoolean("official_sync_resume_after_seed", false))
        ) {
            probe
                .edit()
                .putBoolean("official_sync_active", false)
                .putBoolean("official_sync_pending", false)
                .putBoolean("official_sync_resume_after_seed", false)
                .putString(
                    "official_queue_failed_fingerprint",
                    probe.getString("official_sync_fingerprint_pending", ""),
                )
                .putString("official_sync_status", "同步超时，已安全停止")
                .apply()
            if (
                (probe.getBoolean("official_sync_should_play", false) ||
                    !probe.getBoolean("official_sync_was_playing", false))
            ) {
                pauseDedaoSession()
            }
        }
        returnToCompanion()
        dismissAutomationCover()
    }

    private val isDedaoSessionPlaying: Boolean
        get() {
            try {
                val manager: MediaSessionManager =
                    getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
                val listener: ComponentName = ComponentName(this, DedaoSessionListener::class.java)
                for (controller: MediaController in manager.getActiveSessions(listener)) {
                    if ("com.luojilab.player" != controller.getPackageName()) continue
                    val state: PlaybackState? = controller.getPlaybackState()
                    return state != null && state!!.getState() == PlaybackState.STATE_PLAYING
                }
            } catch (ignored: Exception) {}

            return false
        }

    private val isOfficialQueueManagementVisible: Boolean
        get() {
            val root: AccessibilityNodeInfo? = getRootInActiveWindow()
            val visible: Boolean =
                (hasLabel(root, "完成") &&
                    (hasLabel(root, "管理") || hasLabel(root, "全选") || hasLabel(root, "移出")))
            if (root != null) root!!.recycle()
            return visible
        }

    private fun markPlayStage(stage: String, title: String?) {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        var startedAt: Long = probe.getLong("play_timing_started_elapsed", 0L)
        val now: Long = SystemClock.elapsedRealtime()
        if ("requested" == stage || startedAt <= 0L) startedAt = now
        probe
            .edit()
            .putLong("play_timing_started_elapsed", startedAt)
            .putLong("play_timing_" + stage, now - startedAt)
            .putString("play_timing_title", if (title == null) "" else title)
            .putString("play_timing_last_stage", stage)
            .apply()
    }

    protected override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        if (
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getBoolean("scan_requested", false)
        ) {
            handler.postDelayed(Runnable({ this.beginScan() }), 1000)
        }
        schedulePendingPlay(400)
        handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 600)
    }

    private fun currentDedaoTitle(): String {
        try {
            val manager: MediaSessionManager =
                getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
            val listener: ComponentName = ComponentName(this, DedaoSessionListener::class.java)
            for (controller: MediaController in manager.getActiveSessions(listener)) {
                if ("com.luojilab.player" != controller.getPackageName()) continue
                val metadata: MediaMetadata? = controller.getMetadata()
                val title: String? =
                    if (metadata == null) ""
                    else metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE)
                return if (title == null) "" else title
            }
        } catch (ignored: Exception) {}

        return ""
    }

    private fun pauseDedaoSession() {
        var submitted: Boolean = false
        try {
            val manager: MediaSessionManager =
                getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
            val listener: ComponentName = ComponentName(this, DedaoSessionListener::class.java)
            for (controller: MediaController in manager.getActiveSessions(listener)) {
                if ("com.luojilab.player" == controller.getPackageName()) {
                    controller.getTransportControls().pause()
                    submitted = true
                    break
                }
            }
        } catch (ignored: Exception) {}

        if (!submitted) OfficialPlayerControl.send(this, OfficialPlayerControl.PAUSE)
    }

    private fun playDedaoSession() {
        var submitted: Boolean = false
        try {
            val manager: MediaSessionManager =
                getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
            val listener: ComponentName = ComponentName(this, DedaoSessionListener::class.java)
            for (controller: MediaController in manager.getActiveSessions(listener)) {
                if ("com.luojilab.player" == controller.getPackageName()) {
                    controller.getTransportControls().play()
                    submitted = true
                    break
                }
            }
        } catch (ignored: Exception) {}

        if (!submitted) OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY)
    }

    private fun clickExactText(text: String): Boolean {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (root == null) return false
        val matches: List<AccessibilityNodeInfo> = root!!.findAccessibilityNodeInfosByText(text)
        var clicked: Boolean = false
        for (match: AccessibilityNodeInfo in matches) {
            val matchText: CharSequence? = match.getText()
            val matchDescription: CharSequence? = match.getContentDescription()
            if (
                ((matchText == null || !text.contentEquals(matchText!!)) &&
                    (matchDescription == null || !text.contentEquals(matchDescription!!)))
            )
                continue
            var node: AccessibilityNodeInfo? = match
            while (node != null) {
                if (
                    node!!.isClickable() && node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    clicked = true
                    break
                }
                node = node!!.getParent()
            }
            if (clicked) break
        }
        root!!.recycle()
        return clicked
    }

    private fun clickDescriptionStartingWith(prefix: String): Boolean {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (root == null) return false
        val clicked: Boolean = clickDescriptionStartingWith(root, prefix)
        root!!.recycle()
        return clicked
    }

    private fun clickDescriptionStartingWith(
        node: AccessibilityNodeInfo?,
        prefix: String,
    ): Boolean {
        if (node == null) return false
        val description: CharSequence? = node!!.getContentDescription()
        val text: CharSequence? = node!!.getText()
        if (
            ((description != null &&
                description!!.toString().trim({ it <= ' ' }).startsWith(prefix)) ||
                (text != null && text!!.toString().trim({ it <= ' ' }).startsWith(prefix)))
        ) {
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null) {
                if (
                    (clickable!!.isClickable() &&
                        clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                )
                    return true
                clickable = clickable!!.getParent()
            }
        }
        for (i: Int in 0 until node!!.getChildCount()) {
            if (clickDescriptionStartingWith(node!!.getChild(i), prefix)) return true
        }
        return false
    }

    private fun openOfficialPlayer(): Boolean {
        try {
            val intent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://base/ddplayer"))
            intent.setPackage("com.luojilab.player")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
            return true
        } catch (ignored: Exception) {
            return false
        }
    }

    private fun captureOfficialQueue() {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        val items: JSONArray = JSONArray()
        appendOfficialQueueItems(root, items)
        val reorder: Boolean =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getBoolean("queue_probe_reorder", false)
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("queue_probe_items", items.toString())
            .putLong("queue_probe_captured_at", System.currentTimeMillis())
            .putBoolean("queue_probe_active", reorder)
            .apply()
        if (root != null) root!!.recycle()
        if (reorder) {
            handler.postDelayed(
                {
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("queue_done_clicked", clickDescriptionStartingWith("完成"))
                        .apply()
                },
                250,
            )
            handler.postDelayed(
                {
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("queue_player_reopened", openOfficialPlayer())
                        .apply()
                },
                800,
            )
            handler.postDelayed(
                {
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("queue_list_reopened", clickExactText("播放列表"))
                        .apply()
                },
                1600,
            )
            handler.postDelayed(
                {
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("queue_first_clicked", clickDescriptionStartingWith("166｜"))
                        .apply()
                },
                2500,
            )
            handler.postDelayed(Runnable({ this.returnToCompanion() }), 3500)
            handler.postDelayed(
                {
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("queue_probe_active", false)
                        .apply()
                },
                3700,
            )
        }
    }

    private fun appendOfficialQueueItems(node: AccessibilityNodeInfo?, items: JSONArray) {
        if (node == null) return
        val id: String? = node!!.getViewIdResourceName()
        val description: CharSequence? = node!!.getContentDescription()
        val flutterQueueRow: Boolean =
            (description != null &&
                description!!.toString().matches(("(?s)^\\s*\\d+｜.*").toRegex()))
        if (
            ((id != null && id!!.endsWith(":id/audioNameTextView") && node!!.getText() != null) ||
                flutterQueueRow)
        ) {
            val bounds: Rect = Rect()
            node!!.getBoundsInScreen(bounds)
            val item: org.json.JSONObject = org.json.JSONObject()
            try {
                val title: String =
                    if (flutterQueueRow)
                        description!!
                            .toString()
                            .trim({ it <= ' ' })
                            .split(("\\n").toRegex(), 2)
                            .toTypedArray()[0]
                    else node!!.getText().toString()
                item.put("title", title)
                item.put("left", bounds.left)
                item.put("top", bounds.top)
                item.put("right", bounds.right)
                item.put("bottom", bounds.bottom)
                items.put(item)
            } catch (ignored: org.json.JSONException) {}
        } else if (id != null && id!!.endsWith(":id/sortAudioButton")) {
            val bounds: Rect = Rect()
            node!!.getBoundsInScreen(bounds)
            val item: org.json.JSONObject = org.json.JSONObject()
            try {
                item.put("handle", true)
                item.put("left", bounds.left)
                item.put("top", bounds.top)
                item.put("right", bounds.right)
                item.put("bottom", bounds.bottom)
                items.put(item)
            } catch (ignored: org.json.JSONException) {}
        }
        for (i: Int in 0 until node!!.getChildCount()) {
            appendOfficialQueueItems(node!!.getChild(i), items)
        }
    }

    private class OfficialQueueRow(val title: String, val bounds: Rect)

    private fun officialQueueRowBounds(node: AccessibilityNodeInfo): Rect {
        val bounds: Rect = Rect()
        node.getBoundsInScreen(bounds)
        var parent: AccessibilityNodeInfo? = node.getParent()
        val screenWidth: Int = getResources().getDisplayMetrics().widthPixels
        while (parent != null) {
            val parentBounds: Rect = Rect()
            parent!!.getBoundsInScreen(parentBounds)
            if (
                (parentBounds.width() >= screenWidth * 9 / 10 &&
                    parentBounds.height() >= dp(45) &&
                    parentBounds.height() <= dp(100))
            ) {
                bounds.set(parentBounds)
                break
            }
            parent = parent!!.getParent()
        }
        return bounds
    }

    private fun collectOfficialQueueRows(
        node: AccessibilityNodeInfo?,
        rows: MutableList<OfficialQueueRow>,
    ) {
        if (node == null) return
        val description: CharSequence? = node!!.getContentDescription()
        if (
            description != null && description!!.toString().matches(("(?s)^\\s*\\d+｜.*").toRegex())
        ) {
            rows.add(
                OfficialQueueRow(
                    description!!
                        .toString()
                        .trim({ it <= ' ' })
                        .split(("\\n").toRegex(), 2)
                        .toTypedArray()[0],
                    officialQueueRowBounds(node!!),
                )
            )
        } else if (
            (node!!.getText() != null &&
                node!!.getText().toString().matches(("(?s)^\\s*\\d+｜.*").toRegex()))
        ) {
            rows.add(
                OfficialQueueRow(
                    node!!
                        .getText()
                        .toString()
                        .trim({ it <= ' ' })
                        .split(("\\n").toRegex(), 2)
                        .toTypedArray()[0],
                    officialQueueRowBounds(node!!),
                )
            )
        }
        for (i: Int in 0 until node!!.getChildCount()) {
            collectOfficialQueueRows(node!!.getChild(i), rows)
        }
    }

    private fun desiredOfficialTitles(): List<String> {
        val result: ArrayList<String> = ArrayList<String>()
        val json: String? =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getString("official_sync_titles", "[]")
        try {
            val titles: JSONArray = JSONArray(if (json == null) "[]" else json)
            for (i: Int in 0 until titles.length()) {
                val title: String = titles.optString(i, "").trim({ it <= ' ' })
                if (!title.isEmpty()) result.add(title)
            }
        } catch (ignored: Exception) {}

        return result
    }

    private fun currentOfficialQueueRows(): List<OfficialQueueRow> {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        val rows: ArrayList<OfficialQueueRow> = ArrayList<OfficialQueueRow>()
        collectOfficialQueueRows(root, rows)
        if (root != null) root!!.recycle()
        Collections.sort<OfficialQueueRow>(
            rows,
            { left, right -> Integer.compare(left.bounds.top, right.bounds.top) },
        )
        val unique: ArrayList<OfficialQueueRow> = ArrayList<OfficialQueueRow>()
        for (row: OfficialQueueRow in rows) {
            var seen: Boolean = false
            for (saved: OfficialQueueRow in unique) {
                if (saved.title == row.title) {
                    seen = true
                    break
                }
            }
            if (!seen) unique.add(row)
        }
        return unique
    }

    private fun beginOfficialQueueSync() {
        handler.postDelayed({ clickLabel("播放列表") }, 800)
        handler.postDelayed({ clickLabel("管理") }, 1600)
        handler.postDelayed({ syncOfficialQueueIndex(0) }, 2500)
    }

    private fun clickLabel(label: String): Boolean {
        return clickDescriptionStartingWith(label) || clickExactText(label)
    }

    private fun hasLabel(node: AccessibilityNodeInfo?, label: String): Boolean {
        if (node == null) return false
        val text: CharSequence? = node!!.getText()
        val description: CharSequence? = node!!.getContentDescription()
        if (
            ((text != null && label.contentEquals(text!!)) ||
                (description != null && label.contentEquals(description!!)))
        )
            return true
        for (i: Int in 0 until node!!.getChildCount()) {
            if (hasLabel(node!!.getChild(i), label)) return true
        }
        return false
    }

    private fun syncOfficialQueueIndex(desiredIndex: Int) {
        if (!isOfficialQueueManagementVisible) {
            val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            val waits: Int = probe.getInt("official_sync_ui_waits", 0) + 1
            probe.edit().putInt("official_sync_ui_waits", waits).apply()
            if (waits > 18) {
                failOfficialQueueSync("得到播放列表管理页未能打开")
                return
            }
            clickLabel("播放列表")
            handler.postDelayed({ clickLabel("管理") }, 450)
            handler.postDelayed({ syncOfficialQueueIndex(desiredIndex) }, 950)
            return
        }
        val syncProbe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (!syncProbe.getBoolean("official_sync_queue_rewound", false)) {
            val root: AccessibilityNodeInfo? = getRootInActiveWindow()
            val queue: AccessibilityNodeInfo? = findOfficialQueueScrollable(root)
            val moved: Boolean =
                (queue != null &&
                    queue!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            if (root != null) root!!.recycle()
            val attempts: Int = syncProbe.getInt("official_sync_rewind_attempts", 0) + 1
            syncProbe.edit().putInt("official_sync_rewind_attempts", attempts).apply()
            if (moved && attempts < 24) {
                handler.postDelayed({ syncOfficialQueueIndex(desiredIndex) }, 280)
                return
            }
            syncProbe.edit().putBoolean("official_sync_queue_rewound", true).apply()
            handler.postDelayed({ syncOfficialQueueIndex(desiredIndex) }, 180)
            return
        }
        val desired: List<String> = desiredOfficialTitles()
        if (desired.isEmpty()) {
            failOfficialQueueSync("没有可同步的播放内容")
            return
        }
        val rows: List<OfficialQueueRow> = currentOfficialQueueRows()
        val observed: JSONArray = JSONArray()
        for (row: OfficialQueueRow in rows) observed.put(row.title)
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("official_sync_observed", observed.toString())
            .apply()
        for (title: String in desired) {
            var found: Boolean = false
            for (row: OfficialQueueRow in rows) if (title == row.title) found = true
            if (!found) {
                seedMissingOfficialTitle(title)
                return
            }
        }
        val nativeOrder: ArrayList<String> = ArrayList<String>()
        // Keep non-filtered native items before the companion queue. Playback starts at the
        // first desired item, so forward/continuous playback reaches the end without ever
        // crossing into an excluded or expired entry.
        for (row: OfficialQueueRow in rows) {
            if (!desired.contains(row.title)) nativeOrder.add(row.title)
        }
        nativeOrder.addAll(desired)
        if (desiredIndex >= nativeOrder.size) {
            for (i: Int in nativeOrder.indices) {
                if (i >= rows.size || nativeOrder.get(i) != rows.get(i).title) {
                    failOfficialQueueSync("得到播放顺序校验未通过")
                    return
                }
            }
            val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            val currentTitle: String = probe.getString("official_sync_start_title", "") ?: ""
            finishOfficialQueueSync(
                if (desired.contains(currentTitle)) currentTitle else desired.get(0)
            )
            return
        }
        val wanted: String = nativeOrder.get(desiredIndex)
        var sourceIndex: Int = -1
        for (i: Int in rows.indices) {
            if (wanted == rows.get(i).title) {
                sourceIndex = i
                break
            }
        }
        if (sourceIndex == desiredIndex) {
            syncOfficialQueueIndex(desiredIndex + 1)
            return
        }
        if (sourceIndex < 0 || desiredIndex >= rows.size) {
            failOfficialQueueSync("无法定位得到播放顺序")
            return
        }
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val moves: Int = probe.getInt("official_sync_moves", 0) + 1
        probe.edit().putInt("official_sync_moves", moves).apply()
        if (moves > Math.max(12, rows.size * 2)) {
            failOfficialQueueSync("得到播放顺序多次调整仍未到位")
            return
        }
        probe
            .edit()
            .putString("official_sync_last_move", "$sourceIndex->$desiredIndex:$wanted")
            .apply()
        dragOfficialQueueRow(
            rows.get(sourceIndex),
            rows.get(desiredIndex),
            { handler.postDelayed({ syncOfficialQueueIndex(0) }, 900) },
        )
    }

    private fun findOfficialQueueScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val pending: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (!pending.isEmpty()) {
            val node: AccessibilityNodeInfo = pending.removeFirst()
            val id: String? = node.getViewIdResourceName()
            val className: CharSequence? = node.getClassName()
            if (
                ((id != null && id!!.endsWith(":id/dl_listview")) ||
                    ((className != null && "android.widget.ListView".contentEquals(className!!))))
            )
                return node
            for (i: Int in 0 until node.getChildCount()) {
                val child: AccessibilityNodeInfo? = node.getChild(i)
                if (child != null) pending.addLast(child)
            }
        }
        return null
    }

    private fun seedMissingOfficialTitle(title: String) {
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("official_sync_active", false)
            .putBoolean("official_sync_resume_after_seed", true)
            .putString("official_sync_seed_title", title)
            .putString("official_sync_status", "正在把有效红包加入得到播放列表…")
            .apply()
        clickDescriptionStartingWith("完成")
        handler.postDelayed({ returnToListAndPlay(this, title) }, 450)
    }

    private fun dragOfficialQueueRow(
        source: OfficialQueueRow,
        target: OfficialQueueRow,
        completed: Runnable,
    ) {
        val x: Float =
            Math.min(
                    getResources().getDisplayMetrics().widthPixels - dp(20),
                    source.bounds.right - dp(24),
                )
                .toFloat()
        val y: Float = source.bounds.centerY().toFloat()
        val holdPath: Path = Path()
        holdPath.moveTo(x, y)
        holdPath.lineTo(x + 1, y)
        val hold: GestureDescription.StrokeDescription =
            GestureDescription.StrokeDescription(holdPath, 0, 500, true)
        val first: GestureDescription = GestureDescription.Builder().addStroke(hold).build()
        if (
            !dispatchGesture(
                first,
                object : AccessibilityService.GestureResultCallback() {
                    public override fun onCompleted(gestureDescription: GestureDescription) {
                        val dragPath: Path = Path()
                        dragPath.moveTo(x + 1, y)
                        val destination: Float =
                            (if (source.bounds.top > target.bounds.top)
                                    target.bounds.centerY() - dp(6)
                                else target.bounds.centerY() + dp(6))
                                .toFloat()
                        dragPath.lineTo(x + 1, destination)
                        val second: GestureDescription =
                            GestureDescription.Builder()
                                .addStroke(hold.continueStroke(dragPath, 0, 900, false))
                                .build()
                        if (
                            !dispatchGesture(
                                second,
                                object : AccessibilityService.GestureResultCallback() {
                                    public override fun onCompleted(
                                        gestureDescription: GestureDescription
                                    ) {
                                        completed.run()
                                    }

                                    public override fun onCancelled(
                                        gestureDescription: GestureDescription
                                    ) {
                                        failOfficialQueueSync("得到取消了播放顺序调整")
                                    }
                                },
                                null,
                            )
                        )
                            failOfficialQueueSync("无法提交播放顺序调整")
                    }

                    public override fun onCancelled(gestureDescription: GestureDescription) {
                        failOfficialQueueSync("得到取消了播放顺序调整")
                    }
                },
                null,
            )
        )
            failOfficialQueueSync("无法开始播放顺序调整")
    }

    private fun finishOfficialQueueSync(firstTitle: String) {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val fingerprint: String = probe.getString("official_sync_fingerprint_pending", "") ?: ""
        probe.edit().putString("official_sync_status", "正在应用播放顺序…").apply()
        handler.postDelayed({ clickDescriptionStartingWith("完成") }, 200)
        handler.postDelayed(Runnable({ this.openOfficialPlayer() }), 750)
        handler.postDelayed({ clickLabel("播放列表") }, 1550)
        handler.postDelayed(
            {
                val clicked: Boolean = clickDescriptionStartingWith(firstTitle)
                // Some OEM/WebView combinations expose the player list later than others. The
                // exported player index command is independent of layout and confirmation below
                // still requires the real MediaSession title to equal firstTitle.
                val skipped: Boolean = clicked || OfficialPlayerControl.skip(this, 0)
                if (!skipped) {
                    failOfficialQueueSync("播放顺序已调整，但首条启动失败")
                    return@postDelayed
                }
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString("official_sync_status", "正在确认实际播放内容…")
                    .apply()
                handler.postDelayed({ confirmOfficialQueueStart(firstTitle, fingerprint, 0) }, 450)
            },
            2450,
        )
    }

    private fun confirmOfficialQueueStart(firstTitle: String, fingerprint: String, attempt: Int) {
        val preferences: SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val wasPlaying: Boolean = preferences.getBoolean("official_sync_was_playing", false)
        val shouldPlay: Boolean = preferences.getBoolean("official_sync_should_play", false)
        val actualTitle: String = currentDedaoTitle()
        if (firstTitle == actualTitle) {
            preferences
                .edit()
                .putBoolean("official_sync_active", false)
                .putBoolean("official_sync_pending", false)
                .putString("official_queue_fingerprint", fingerprint)
                .putString("official_sync_status", "播放顺序已同步")
                .remove("official_queue_failed_fingerprint")
                .putBoolean("companion_queue_active", true)
                .putBoolean("direct_skip_unreliable", false)
                .apply()
            if (shouldPlay) {
                playDedaoSession()
            } else if (!wasPlaying) {
                pauseDedaoSession()
            }
            handler.postDelayed(Runnable({ this.returnToCompanion() }), 650)
            handler.postDelayed(Runnable({ this.dismissAutomationCover() }), 900)
            return
        }
        if (!wasPlaying) pauseDedaoSession()
        if (attempt >= 10) {
            failOfficialQueueSync("播放顺序已调整，但实际首条未确认")
            return
        }
        if (attempt == 3 || attempt == 7) {
            openOfficialPlayer()
            handler.postDelayed({ clickLabel("播放列表") }, 450)
            handler.postDelayed({ clickDescriptionStartingWith(firstTitle) }, 850)
        } else {
            clickDescriptionStartingWith(firstTitle)
        }
        handler.postDelayed(
            { confirmOfficialQueueStart(firstTitle, fingerprint, attempt + 1) },
            650,
        )
    }

    private fun failOfficialQueueSync(reason: String) {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        probe
            .edit()
            .putBoolean("official_sync_active", false)
            .putBoolean("official_sync_pending", false)
            .putBoolean("official_sync_resume_after_seed", false)
            .putString(
                "official_queue_failed_fingerprint",
                probe.getString("official_sync_fingerprint_pending", ""),
            )
            .putString("official_sync_status", reason)
            .apply()
        if (
            (probe.getBoolean("official_sync_should_play", false) ||
                !probe.getBoolean("official_sync_was_playing", false))
        ) {
            pauseDedaoSession()
        }
        returnToCompanion()
        dismissAutomationCover()
    }

    private fun reorder166Before950ForProbe() {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        val rows: MutableList<OfficialQueueRow> = ArrayList<OfficialQueueRow>()
        collectOfficialQueueRows(root, rows)
        if (root != null) root!!.recycle()
        var source: OfficialQueueRow? = null
        var target: OfficialQueueRow? = null
        for (row: OfficialQueueRow in rows) {
            if (row.title.startsWith("166｜")) source = row
            if (row.title.startsWith("950｜")) target = row
        }
        if (source == null || target == null || source!!.bounds.top < target!!.bounds.top) return
        val targetRow: OfficialQueueRow = target
        val x: Float = Math.min(source!!.bounds.right - dp(24), 1020).toFloat()
        val y: Float = source!!.bounds.centerY().toFloat()
        val holdPath: Path = Path()
        holdPath.moveTo(x, y)
        holdPath.lineTo(x + 1, y)
        val hold: GestureDescription.StrokeDescription =
            GestureDescription.StrokeDescription(holdPath, 0, 500, true)
        val gesture: GestureDescription = GestureDescription.Builder().addStroke(hold).build()
        val submitted: Boolean =
            dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    public override fun onCompleted(gestureDescription: GestureDescription) {
                        val dragPath: Path = Path()
                        dragPath.moveTo(x + 1, y)
                        dragPath.lineTo(x + 1, (targetRow.bounds.top - dp(12)).toFloat())
                        val drag: GestureDescription =
                            GestureDescription.Builder()
                                .addStroke(hold.continueStroke(dragPath, 0, 900, false))
                                .build()
                        val continued: Boolean =
                            dispatchGesture(
                                drag,
                                object : AccessibilityService.GestureResultCallback() {
                                    public override fun onCompleted(
                                        gestureDescription: GestureDescription
                                    ) {
                                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                                            .edit()
                                            .putString("queue_reorder_result", "completed")
                                            .apply()
                                    }

                                    public override fun onCancelled(
                                        gestureDescription: GestureDescription
                                    ) {
                                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                                            .edit()
                                            .putString("queue_reorder_result", "drag_cancelled")
                                            .apply()
                                    }
                                },
                                null,
                            )
                        if (!continued) {
                            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                                .edit()
                                .putString("queue_reorder_result", "drag_rejected")
                                .apply()
                        }
                    }

                    public override fun onCancelled(gestureDescription: GestureDescription) {
                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                            .edit()
                            .putString("queue_reorder_result", "hold_cancelled")
                            .apply()
                    }
                },
                null,
            )
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("queue_reorder_submitted", submitted)
            .putString("queue_reorder_source", source!!.title)
            .putString("queue_reorder_target", target!!.title)
            .apply()
    }

    private fun showAutomationCover(title: String) {
        dismissAutomationCover()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (windowManager == null) return

        val snapshot: Bitmap? = MainActivity.captureCurrentScreen()
        if (snapshot != null) {
            val image: ImageView = ImageView(this)
            image.setImageBitmap(snapshot)
            image.setScaleType(ImageView.ScaleType.FIT_XY)
            image.setBackgroundColor(Color.rgb(250, 250, 250))
            automationCoverBitmap = snapshot
            attachAutomationCover(image)
            return
        }

        val root: LinearLayout = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setGravity(Gravity.CENTER_HORIZONTAL)
        root.setPadding(dp(28), dp(78), dp(28), dp(36))
        root.setBackgroundColor(Color.rgb(246, 247, 249))

        val brand: TextView = overlayText("知识红包伴侣", 27, Color.rgb(29, 31, 36), Typeface.BOLD)
        root.addView(
            brand,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val status: TextView = overlayText("正在准备播放", 14, Color.rgb(255, 106, 42), Typeface.BOLD)
        status.setPadding(0, dp(42), 0, dp(12))
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(dp(22), dp(24), dp(22), dp(24))
        val panelBackground: GradientDrawable = GradientDrawable()
        panelBackground.setColor(Color.WHITE)
        panelBackground.setCornerRadius(dp(22).toFloat())
        panel.setBackground(panelBackground)

        val titleView: TextView = overlayText(title, 20, Color.rgb(29, 31, 36), Typeface.BOLD)
        titleView.setLineSpacing(0f, 1.12f)
        panel.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val progressRow: LinearLayout = LinearLayout(this)
        progressRow.setGravity(Gravity.CENTER_VERTICAL)
        progressRow.setPadding(0, dp(22), 0, 0)
        val progress: ProgressBar = ProgressBar(this)
        progress.setIndeterminateTintList(
            android.content.res.ColorStateList.valueOf(Color.rgb(255, 106, 42))
        )
        progressRow.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)))
        val progressCopy: TextView =
            overlayText("正在安全切换，马上回来", 14, Color.rgb(112, 116, 126), Typeface.NORMAL)
        progressCopy.setPadding(dp(12), 0, 0, 0)
        progressRow.addView(
            progressCopy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        panel.addView(
            progressRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val panelParams: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        root.addView(panel, panelParams)

        val hint: TextView =
            overlayText("播放授权由得到在后台完成，全程不会打开过期内容", 13, Color.rgb(112, 116, 126), Typeface.NORMAL)
        hint.setGravity(Gravity.CENTER)
        hint.setPadding(0, dp(20), 0, 0)
        root.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        attachAutomationCover(root)
    }

    private fun attachAutomationCover(root: View) {
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START
        params.windowAnimations = 0
        params.dimAmount = 0f
        try {
            windowManager!!.addView(root, params)
            automationCover = root
            handler.removeCallbacks(coverFailSafe)
            handler.postDelayed(coverFailSafe, 35000)
        } catch (ignored: Exception) {
            automationCover = null
            if (automationCoverBitmap != null) automationCoverBitmap!!.recycle()
            automationCoverBitmap = null
        }
    }

    private fun overlayText(value: String, sizeSp: Int, color: Int, style: Int): TextView {
        val text: TextView = TextView(this)
        text.setText(value)
        text.setTextSize(sizeSp.toFloat())
        text.setTextColor(color)
        text.setTypeface(Typeface.DEFAULT, style)
        return text
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    private fun dismissAutomationCover() {
        handler.removeCallbacks(coverFailSafe)
        if (automationCover != null && windowManager != null) {
            try {
                windowManager!!.removeViewImmediate(automationCover)
            } catch (ignored: Exception) {}
        }
        automationCover = null
        if (automationCoverBitmap != null) automationCoverBitmap!!.recycle()
        automationCoverBitmap = null
    }

    private fun returnToCompanion() {
        try {
            val companion: Intent = Intent(this, MainActivity::class.java)
            companion.putExtra("action", "now_playing")
            companion.addFlags(
                (Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            startActivity(companion, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } catch (ignored: Exception) {}
    }

    public override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (
            (event.getPackageName() == null ||
                !"com.luojilab.player".contentEquals(event.getPackageName()))
        )
            return
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (probe.getBoolean("official_sync_active", false)) {
            if (probe.getBoolean("official_sync_pending", false)) {
                probe.edit().putBoolean("official_sync_pending", false).apply()
                beginOfficialQueueSync()
            }
            return
        }
        if (probe.getBoolean("queue_probe_active", false)) {
            if (probe.getBoolean("queue_probe_pending", false)) {
                probe.edit().putBoolean("queue_probe_pending", false).apply()
                val reorder: Boolean = probe.getBoolean("queue_probe_reorder", false)
                handler.postDelayed({ clickExactText("播放列表") }, 800)
                handler.postDelayed({ clickExactText("管理") }, 1600)
                if (reorder) {
                    handler.postDelayed(Runnable({ this.reorder166Before950ForProbe() }), 2500)
                    handler.postDelayed(Runnable({ this.captureOfficialQueue() }), 4000)
                } else {
                    handler.postDelayed(Runnable({ this.captureOfficialQueue() }), 2500)
                }
            }
            return
        }
        if (scanning) handler.postDelayed(Runnable({ this.capturePageStructure() }), 120)
        if (!playTarget.isEmpty()) scheduleSeekPass(40) else schedulePendingPlay(40)
        if (
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getBoolean("scan_requested", false) && !scanning
        ) {
            handler.postDelayed(Runnable({ this.beginScan() }), 300)
        }
        if (!DETAIL_AUTOMATION_ENABLED) return
        handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 60)
    }

    private fun beginScan() {
        if (scanning) return
        scanning = true
        scanSteps = 0
        scanWaitSteps = 0
        scanStartedReading = false
        unchangedVisibleSteps = 0
        skippedExpiredItems = 0
        stepsWithoutNewActiveItems = 0
        lastVisibleFingerprint = ""
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("scan_status", "等待得到知识红包列表…")
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        scanPage()
    }

    private fun beginSeek(title: String?) {
        if (title == null || title!!.isBlank() || !isVerifiedActiveTitle(this, title)) return
        playTarget = title
        seekBackwardSteps = 0
        seekForwardSteps = 0
        lastSeekViewportFingerprint = ""
        unchangedSeekViewportPasses = 0
        stableTargetChecks = 0
        lastTargetBounds.setEmpty()
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("seek_status", "开始定位：" + title!!)
            .apply()
        markPlayStage("seek_started", title)
        seekAndTapTarget()
    }

    private fun seekAndTapTarget() {
        if (playTarget.isEmpty() || seekGestureInFlight) return
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (
            (root == null ||
                root!!.getPackageName() == null ||
                !"com.luojilab.player".contentEquals(root!!.getPackageName()))
        ) {
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "seek_status",
                    ("等待得到列表出现，尝试 " + (seekBackwardSteps + seekForwardSteps + 1)),
                )
                .apply()
            // Retry through short app transitions. If the phone is locked, keep the target and
            // resume from the next Dedao accessibility event after the user unlocks.
            if (++seekBackwardSteps < 60) scheduleSeekPass(100) else failSeekTarget("知识红包列表一直没有出现")
            return
        }

        if (!isRedPacketList(root)) {
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("seek_status", "等待知识红包列表稳定出现")
                .apply()
            if (++seekBackwardSteps < 60) scheduleSeekPass(100)
            else failSeekTarget("知识红包列表一直没有稳定出现")
            return
        }

        val screenHeight: Int = getResources().getDisplayMetrics().heightPixels
        val scrollable: AccessibilityNodeInfo? = findScrollable(root)
        val viewport: Rect =
            Rect(0, dp(72), getResources().getDisplayMetrics().widthPixels, screenHeight - dp(48))
        if (scrollable != null) scrollable!!.getBoundsInScreen(viewport)
        val matches: List<AccessibilityNodeInfo> = findNodesWithExactText(root, playTarget)
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("seek_status", "已在得到列表查找标题，匹配 " + matches.size + " 个")
            .putInt("seek_last_match_count", matches.size)
            .apply()
        if (matches.isEmpty() && seekBackwardSteps == 0 && seekForwardSteps == 0) {
            capturePageStructure()
        }
        var targetAboveViewport: Boolean = false
        var targetBelowViewport: Boolean = false
        for (node: AccessibilityNodeInfo in matches) {
            if (node.getText() == null || !playTarget.contentEquals(node.getText())) continue
            val viewId: String? = node.getViewIdResourceName()
            val bounds: Rect = Rect()
            node.getBoundsInScreen(bounds)
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("seek_match_bounds", bounds.toShortString())
                .putString("seek_match_view_id", if (viewId == null) "" else viewId)
                .apply()
            if (viewId != null && !viewId!!.isEmpty()) continue
            if (!isCurrentlyActiveEpisode(node, playTarget)) {
                cancelExpiredPlaybackAndRefresh(playTarget)
                return
            }
            if (bounds.bottom <= viewport.top + dp(8)) {
                targetAboveViewport = true
                continue
            }
            if (bounds.top >= viewport.bottom - dp(8)) {
                targetBelowViewport = true
                continue
            }
            if (bounds.height() <= 0) continue
            // The WebView row has no clickable accessibility action. Sample the exact title
            // twice at the same bounds so a list-opening animation cannot move another row under
            // the gesture.
            if (bounds != lastTargetBounds) {
                lastTargetBounds.set(bounds)
                stableTargetChecks = 1
                scheduleSeekPass(80)
                return
            }
            if (++stableTargetChecks < 2) {
                scheduleSeekPass(450)
                return
            }
            val tappedTitle: String = playTarget
            if (performSemanticClick(node)) {
                playTarget = ""
                lastAutoClick = 0L
                completeTitleTap(tappedTitle, true)
                return
            }
            val path: Path = Path()
            path.moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            val gesture: GestureDescription =
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build()
            seekGestureInFlight = true
            if (
                dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        public override fun onCompleted(gestureDescription: GestureDescription) {
                            seekGestureInFlight = false
                            playTarget = ""
                            lastAutoClick = 0L
                            completeTitleTap(tappedTitle, false)
                        }

                        public override fun onCancelled(gestureDescription: GestureDescription) {
                            seekGestureInFlight = false
                            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                                .edit()
                                .putString("seek_status", "点击被系统取消，正在重试")
                                .apply()
                            scheduleSeekPass(100)
                        }
                    },
                    null,
                )
            ) {
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString("seek_status", "已提交点击：" + tappedTitle)
                    .apply()
                return
            }
            seekGestureInFlight = false
        }

        if (scrollable == null && seekBackwardSteps + seekForwardSteps < 20) {
            seekBackwardSteps++
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("seek_status", "红包列表仍在加载，正在重试")
                .apply()
            scheduleSeekPass(100)
            return
        }
        if (scrollable == null) {
            failSeekTarget("没有找到可滚动的知识红包列表")
            return
        }

        // Dedao's WebView keeps the requested title in the accessibility tree even just outside
        // the viewport. Follow that coordinate directly so a large page jump cannot skip it.
        if (targetAboveViewport || targetBelowViewport) {
            val forward: Boolean = targetBelowViewport && !targetAboveViewport
            if (seekBackwardSteps + seekForwardSteps > 80) {
                failSeekTarget("多次滚动仍未能显示标题")
                return
            }
            if (forward) seekForwardSteps++ else seekBackwardSteps++
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("seek_status", if (forward) "标题在下方，正在逐页靠近…" else "标题在上方，正在逐页返回…")
                .apply()
            if (!dispatchSeekScroll(scrollable, forward)) {
                failSeekTarget("知识红包列表无法滚动到标题")
            }
            return
        }

        val viewportFingerprint: String = visibleEpisodeFingerprint(root)
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putInt("seek_backward_steps", seekBackwardSteps)
            .putInt("seek_forward_steps", seekForwardSteps)
            .putString("seek_viewport_fingerprint", viewportFingerprint)
            .apply()
        if (seekForwardSteps == 0) {
            val unchanged: Boolean =
                (!viewportFingerprint.isEmpty() &&
                    viewportFingerprint == lastSeekViewportFingerprint)
            unchangedSeekViewportPasses = if (unchanged) unchangedSeekViewportPasses + 1 else 0
            if (unchangedSeekViewportPasses < 2 && seekBackwardSteps < 50) {
                lastSeekViewportFingerprint = viewportFingerprint
                seekBackwardSteps++
                if (dispatchSeekScroll(scrollable, false)) {
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                        .edit()
                        .putString("seek_status", "正在回到红包列表顶部…")
                        .apply()
                    return
                }
            }
            if (seekBackwardSteps >= 50 && unchangedSeekViewportPasses < 2) {
                failSeekTarget("无法回到知识红包列表顶部")
                return
            }
            // A downward swipe leaving the same visible episode rows means the newest entries
            // are now at the top. Start the forward pass from this exact viewport.
            seekForwardSteps = 1
            lastSeekViewportFingerprint = ""
            unchangedSeekViewportPasses = 0
            scheduleSeekPass(80)
            return
        }

        val unchanged: Boolean =
            (!viewportFingerprint.isEmpty() && viewportFingerprint == lastSeekViewportFingerprint)
        unchangedSeekViewportPasses = if (unchanged) unchangedSeekViewportPasses + 1 else 0
        if (seekForwardSteps > 80 || unchangedSeekViewportPasses >= 2) {
            failSeekTarget("没有定位到标题")
            return
        }
        lastSeekViewportFingerprint = viewportFingerprint
        seekForwardSteps++
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("seek_status", "已从列表顶部开始向下查找…")
            .apply()
        if (!dispatchSeekScroll(scrollable, true)) {
            failSeekTarget("知识红包列表无法继续滚动")
        }
    }

    private fun dispatchSeekScroll(scrollable: AccessibilityNodeInfo, forward: Boolean): Boolean {
        if (forward && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            scheduleSeekPass(450)
            return true
        }
        val bounds: Rect = Rect()
        scrollable.getBoundsInScreen(bounds)
        val x: Float = bounds.centerX().toFloat()
        // Use fractions of the actual scrollable viewport so this works across screen sizes and
        // densities. The lower endpoint stays above a possible persistent mini-player.
        val upper: Float = bounds.top + bounds.height() * 0.32f
        val lower: Float = bounds.top + bounds.height() * 0.68f
        if (lower <= upper) {
            val moved: Boolean =
                scrollable.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            if (moved) scheduleSeekPass(220)
            return moved
        }
        val swipe: Path = Path()
        swipe.moveTo(x, if (forward) lower else upper)
        swipe.lineTo(x, if (forward) upper else lower)
        val gesture: GestureDescription =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipe, 0, 300))
                .build()
        seekGestureInFlight = true
        val submitted: Boolean =
            dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    public override fun onCompleted(gestureDescription: GestureDescription) {
                        seekGestureInFlight = false
                        scheduleSeekPass(350)
                    }

                    public override fun onCancelled(gestureDescription: GestureDescription) {
                        seekGestureInFlight = false
                        scheduleSeekPass(350)
                    }
                },
                null,
            )
        if (!submitted) seekGestureInFlight = false
        return submitted
    }

    private fun scheduleSeekPass(delayMillis: Long) {
        if (seekPassScheduled || playTarget.isEmpty()) return
        seekPassScheduled = true
        handler.postDelayed(
            {
                seekPassScheduled = false
                seekAndTapTarget()
            },
            delayMillis,
        )
    }

    private fun visibleEpisodeFingerprint(root: AccessibilityNodeInfo): String {
        val fingerprint: StringBuilder = StringBuilder()
        val pending: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val screenHeight: Int = getResources().getDisplayMetrics().heightPixels
        while (!pending.isEmpty()) {
            val node: AccessibilityNodeInfo = pending.removeFirst()
            if (looksLikeEpisode(node)) {
                val bounds: Rect = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.height() > 0 && bounds.bottom > dp(72) && bounds.top < screenHeight) {
                    fingerprint.append(directText(node.getChild(0))).append('|')
                }
            }
            for (i: Int in 0 until node.getChildCount()) {
                val child: AccessibilityNodeInfo? = node.getChild(i)
                if (child != null) pending.addLast(child)
            }
        }
        return fingerprint.toString()
    }

    private fun failSeekTarget(reason: String) {
        val target: String = playTarget
        playTarget = ""
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val resumeQueueSync: Boolean = probe.getBoolean("official_sync_resume_after_seed", false)
        probe
            .edit()
            .putString("seek_status", reason + "：" + target)
            .remove("pending_play_title")
            .remove("pending_play_direction")
            .apply()
        if (resumeQueueSync) {
            failOfficialQueueSync("无法把有效红包加入得到播放列表：" + target)
        } else {
            returnToCompanion()
            dismissAutomationCover()
        }
    }

    private fun performSemanticClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth: Int = 0
        while (current != null && depth < 5) {
            try {
                if (current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            } catch (ignored: Exception) {}

            current = current!!.getParent()
            depth++
        }
        return false
    }

    private fun completeTitleTap(title: String, semantic: Boolean) {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        probe
            .edit()
            .putString("seek_status", "已点击列表标题，等待核对详情：" + title)
            .putString("expected_detail_title", title)
            .putLong("expected_detail_started_at", System.currentTimeMillis())
            .putBoolean("title_click_semantic", semantic)
            .remove("pending_play_title")
            .remove("pending_play_direction")
            .apply()
        markPlayStage(if (semantic) "semantic_tapped" else "title_tapped", title)
        handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 60)
    }

    private fun isCurrentlyActiveEpisode(titleNode: AccessibilityNodeInfo, title: String): Boolean {
        var current: AccessibilityNodeInfo? = titleNode
        var depth: Int = 0
        while (current != null && depth < 5) {
            if ((looksLikeEpisode(current!!) && title == directText(current!!.getChild(0)))) {
                return hasActiveRedPacketState(directText(current!!.getChild(1)))
            }
            current = current!!.getParent()
            depth++
        }
        return false
    }

    private fun cancelExpiredPlaybackAndRefresh(title: String) {
        playTarget = ""
        seekGestureInFlight = false
        lastTargetBounds.setEmpty()
        stableTargetChecks = 0
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val sequenceDirection: Int = probe.getInt("pending_play_direction", 0)
        probe
            .edit()
            .remove("pending_play_title")
            .remove("pending_play_direction")
            .remove("expected_detail_title")
            .putBoolean("scan_requested", true)
            .putBoolean("scan_return_to_app", true)
            .putLong("scan_started_at", System.currentTimeMillis())
            .putString("scan_attempt_id", "expired-refresh")
            .putString("scan_rejected_title", title)
            .putInt("scan_resume_direction", sequenceDirection)
            .putString("scan_status", "内容已过期，正在更新有效队列…")
            .putString("seek_status", "已取消播放，列表确认该内容已过期：" + title)
            .apply()
        Toast.makeText(this, "该知识红包已过期，未进入详情", Toast.LENGTH_LONG).show()
        scanning = false
        resetListToTopThenScan(0)
    }

    private fun resetListToTopThenScan(attempt: Int) {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        val scrollable: AccessibilityNodeInfo? = if (root == null) null else findScrollable(root)
        val moved: Boolean =
            (scrollable != null &&
                scrollable!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        if (moved && attempt < 16) {
            handler.postDelayed({ resetListToTopThenScan(attempt + 1) }, 120)
            return
        }
        beginScan()
    }

    private fun findNodesWithExactText(
        root: AccessibilityNodeInfo,
        text: String,
    ): List<AccessibilityNodeInfo> {
        val matches: ArrayList<AccessibilityNodeInfo> = ArrayList<AccessibilityNodeInfo>()
        val pending: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (!pending.isEmpty()) {
            val node: AccessibilityNodeInfo = pending.removeFirst()
            val value: CharSequence? = node.getText()
            if (value != null && text.contentEquals(value!!)) matches.add(node)
            for (i: Int in 0 until node.getChildCount()) {
                val child: AccessibilityNodeInfo? = node.getChild(i)
                if (child != null) pending.addLast(child)
            }
        }
        return matches
    }

    private fun findNodesWithExactLabel(
        root: AccessibilityNodeInfo,
        label: String,
    ): List<AccessibilityNodeInfo> {
        val matches: ArrayList<AccessibilityNodeInfo> = ArrayList<AccessibilityNodeInfo>()
        val pending: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (!pending.isEmpty()) {
            val node: AccessibilityNodeInfo = pending.removeFirst()
            val text: CharSequence? = node.getText()
            val description: CharSequence? = node.getContentDescription()
            if (
                ((text != null && label.contentEquals(text!!)) ||
                    (description != null && label.contentEquals(description!!)))
            ) {
                matches.add(node)
            }
            for (i: Int in 0 until node.getChildCount()) {
                val child: AccessibilityNodeInfo? = node.getChild(i)
                if (child != null) pending.addLast(child)
            }
        }
        return matches
    }

    private fun isRedPacketList(root: AccessibilityNodeInfo): Boolean {
        return !findNodesWithExactText(root, "知识红包领取的内容").isEmpty()
    }

    private fun scanPage() {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (
            (root == null ||
                root!!.getPackageName() == null ||
                !"com.luojilab.player".contentEquals(root!!.getPackageName()) ||
                !isRedPacketList(root))
        ) {
            if (++scanWaitSteps <= 20) {
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString("scan_status", "正在等待得到红包列表出现…")
                    .apply()
                handler.postDelayed(Runnable({ this.scanPage() }), 500)
            } else {
                abortScanPreservingCache("同步未完成：没有看到得到红包列表")
            }
            return
        }

        if (!scanStartedReading) {
            scanStartedReading = true
            scanSteps = 0
            unchangedVisibleSteps = 0
            skippedExpiredItems = 0
            stepsWithoutNewActiveItems = 0
            lastVisibleFingerprint = ""
            scannedItems.clear()
            skippedExpiredKeys.clear()
        }

        val activeBefore: Int = scannedItems.size
        val cursor: ScanCursor = ScanCursor()
        collectStructuredItems(root, cursor)
        if (cursor.visibleFingerprint.length == 0 && scanSteps < 10) {
            scanSteps++
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("scan_status", "红包列表正在加载…")
                .apply()
            handler.postDelayed(Runnable({ this.scanPage() }), 500)
            return
        }
        skippedExpiredItems = skippedExpiredKeys.size
        if (scannedItems.size == activeBefore) stepsWithoutNewActiveItems++
        else stepsWithoutNewActiveItems = 0
        val fingerprint: String = cursor.visibleFingerprint.toString()
        if (!fingerprint.isEmpty() && fingerprint == lastVisibleFingerprint) unchangedVisibleSteps++
        else unchangedVisibleSteps = 0
        lastVisibleFingerprint = fingerprint
        scanSteps++
        saveScanProgress("正在确认有效内容，已找到 " + scannedItems.size + " 条…")

        // Claims are newest-first. Once an expired boundary has been seen and the next pass adds
        // no active item, return immediately instead of browsing older expired history.
        if (!scannedItems.isEmpty() && skippedExpiredItems > 0 && stepsWithoutNewActiveItems >= 1) {
            finishScan("同步完成")
            return
        }

        val scrollable: AccessibilityNodeInfo? = findScrollable(root)
        val moved: Boolean =
            (scrollable != null &&
                scrollable!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        if (!moved || unchangedVisibleSteps >= 3 || scanSteps >= 80) {
            finishScan("同步完成")
            return
        }
        handler.postDelayed(Runnable({ this.scanPage() }), 650)
    }

    private fun collectStructuredItems(node: AccessibilityNodeInfo, cursor: ScanCursor) {
        if (looksLikeCourseHeader(node)) {
            cursor.course = directText(node.getChild(1))
        } else if (looksLikeEpisode(node)) {
            val title: String = directText(node.getChild(0))
            val meta: String = directText(node.getChild(1))
            val course: String = cursor.course ?: "未识别课程"
            val key: String = course + "\u0000" + title
            if (hasActiveRedPacketState(meta)) {
                if (!scannedItems.containsKey(key)) {
                    val pageOrder: Long = 1_000_000L - scannedItems.size
                    val completed: Boolean =
                        (meta.contains("已学完") ||
                            meta.contains("已完成") ||
                            meta.matches((".*已学\\s*100%.*").toRegex()))
                    scannedItems.put(
                        key,
                        RedPacketItem(key, 65, course, title, "", pageOrder, completed),
                    )
                }
            } else {
                skippedExpiredKeys.add(key)
            }
            val bounds: android.graphics.Rect = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (
                (bounds.height() > 0 &&
                    bounds.top < getResources().getDisplayMetrics().heightPixels &&
                    bounds.bottom > 0)
            )
                cursor.visibleFingerprint.append(title).append('|')
        }
        for (i: Int in 0 until node.getChildCount()) {
            val child: AccessibilityNodeInfo? = node.getChild(i)
            if (child != null) collectStructuredItems(child, cursor)
        }
    }

    private fun looksLikeCourseHeader(node: AccessibilityNodeInfo): Boolean {
        if (node.getChildCount() < 3) return false
        val first: AccessibilityNodeInfo? = node.getChild(0)
        val second: AccessibilityNodeInfo? = node.getChild(1)
        if (first == null || second == null) return false
        val firstText: String = directText(first)
        val secondText: String = directText(second)
        return (firstText.isEmpty() &&
            !secondText.isEmpty() &&
            first!!.getChildCount() > 0 &&
            !looksLikeDuration(secondText))
    }

    private fun looksLikeEpisode(node: AccessibilityNodeInfo): Boolean {
        if (node.getChildCount() < 2) return false
        val title: String = directText(node.getChild(0))
        val meta: String = directText(node.getChild(1))
        return !title.isEmpty() && title.length > 2 && looksLikeDuration(meta)
    }

    private fun looksLikeDuration(text: String?): Boolean {
        return text != null && text!!.matches((".*\\d+分\\d+秒.*").toRegex())
    }

    private fun schedulePendingPlay(delayMillis: Long) {
        if (pendingPlayScheduled || !playTarget.isEmpty()) return
        val expected: String? =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getString("expected_detail_title", "")
        if (expected != null && !expected!!.isBlank()) return
        pendingPlayScheduled = true
        handler.postDelayed(
            {
                pendingPlayScheduled = false
                if (!playTarget.isEmpty()) return@postDelayed
                val probe: SharedPreferences =
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                val title: String? = probe.getString("pending_play_title", "")
                if (title == null || title!!.isBlank()) return@postDelayed
                if (!isVerifiedActiveTitle(this, title)) {
                    probe
                        .edit()
                        .remove("pending_play_title")
                        .remove("pending_play_direction")
                        .putString("seek_status", "已取消：待播条目不在有效红包队列中")
                        .apply()
                    return@postDelayed
                }
                beginSeek(title)
            },
            delayMillis,
        )
    }

    private fun directText(node: AccessibilityNodeInfo?): String {
        if (node == null || node!!.getText() == null) return ""
        return node!!.getText().toString().trim({ it <= ' ' })
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pending: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (!pending.isEmpty()) {
            val node: AccessibilityNodeInfo = pending.removeFirst()
            if (node.isScrollable()) return node
            for (i: Int in 0 until node.getChildCount()) {
                val child: AccessibilityNodeInfo? = node.getChild(i)
                if (child != null) pending.addLast(child)
            }
        }
        return null
    }

    private fun saveScanProgress(status: String) {
        val array: JSONArray = JSONArray()
        for (entry: Map.Entry<String, RedPacketItem> in scannedItems.entries) {
            array.put(entry.value.toJson())
        }
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("scan_items", array.toString())
            .putInt("scan_count", scannedItems.size)
            .putInt("scan_expired_skipped", skippedExpiredItems)
            .putString("scan_status", status)
            .apply()
    }

    private fun finishScan(status: String) {
        saveScanProgress(status)
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val returnToApp: Boolean = probe.getBoolean("scan_return_to_app", false)
        val rejectedTitle: String? = probe.getString("scan_rejected_title", "")
        val resumeDirection: Int = probe.getInt("scan_resume_direction", 0)
        var resumeItem: RedPacketItem? = null
        if (rejectedTitle != null && !rejectedTitle!!.isBlank()) {
            val validTitles: LinkedHashSet<String> = LinkedHashSet<String>()
            for (item: RedPacketItem in scannedItems.values) validTitles.add(item.title)
            resumeItem =
                QueueStore.retainValidAndSelectAdjacent(
                    this,
                    validTitles,
                    rejectedTitle,
                    resumeDirection,
                )
        }
        probe
            .edit()
            .putBoolean("scan_requested", false)
            .remove("scan_return_to_app")
            .remove("scan_rejected_title")
            .remove("scan_resume_direction")
            .putLong("scan_finished_at", System.currentTimeMillis())
            .apply()
        scanning = false
        if (resumeItem != null && resumeDirection != 0) {
            Toast.makeText(this, "已跳过过期内容", Toast.LENGTH_SHORT).show()
            requestPlayTitle(this, resumeItem!!.title, resumeDirection)
            schedulePendingPlay(120)
            return
        }
        if (resumeDirection != 0 && rejectedTitle != null && !rejectedTitle!!.isBlank()) {
            Toast.makeText(this, "后续没有未过期内容", Toast.LENGTH_SHORT).show()
        }
        if (returnToApp) handler.postDelayed(Runnable({ this.returnToCompanion() }), 120)
    }

    private fun abortScanPreservingCache(status: String) {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val returnToApp: Boolean = probe.getBoolean("scan_return_to_app", false)
        probe
            .edit()
            .putBoolean("scan_requested", false)
            .remove("scan_return_to_app")
            .remove("scan_rejected_title")
            .remove("scan_resume_direction")
            .putString("scan_status", status)
            .apply()
        scanning = false
        if (returnToApp) handler.postDelayed(Runnable({ this.returnToCompanion() }), 120)
    }

    private class ScanCursor {
        var course: String? = null
        val visibleFingerprint: StringBuilder = StringBuilder()
    }

    private fun capturePageStructure() {
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (
            (root == null ||
                root!!.getPackageName() == null ||
                !"com.luojilab.player".contentEquals(root!!.getPackageName()))
        )
            return

        val pending: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        val visibleTexts: MutableSet<String> = LinkedHashSet<String>()
        pending.add(root)
        var nodes: Int = 0
        var webViewChildren: Int = -1
        while (!pending.isEmpty() && nodes < 800) {
            val node: AccessibilityNodeInfo = pending.removeFirst()
            nodes++
            val className: CharSequence? = node.getClassName()
            if (className != null && className!!.toString().contains("WebView")) {
                webViewChildren = Math.max(webViewChildren, node.getChildCount())
            }
            addText(visibleTexts, node.getText())
            addText(visibleTexts, node.getContentDescription())
            for (i: Int in 0 until node.getChildCount()) {
                val child: AccessibilityNodeInfo? = node.getChild(i)
                if (child != null) pending.addLast(child)
            }
        }

        val text: StringBuilder = StringBuilder()
        for (value: String in visibleTexts) {
            if (text.length > 0) text.append('\n')
            text.append(value)
            if (text.length > 8000) break
        }
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putLong("captured_at", System.currentTimeMillis())
            .putInt("node_count", nodes)
            .putInt("webview_children", webViewChildren)
            .putString("texts", text.toString())
            .apply()
    }

    private fun addText(values: MutableSet<String>, raw: CharSequence?) {
        if (raw == null) return
        val value: String = raw!!.toString().trim({ it <= ' ' })
        if (!value.isEmpty()) values.add(value)
    }

    private fun verifyDetailAndPlay() {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val expected: String? = probe.getString("expected_detail_title", "")
        if (expected == null || expected!!.isBlank()) return
        val startedAt: Long = probe.getLong("expected_detail_started_at", 0L)
        val elapsed: Long =
            if (startedAt <= 0L) java.lang.Long.MAX_VALUE
            else System.currentTimeMillis() - startedAt
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (
            (root == null ||
                root!!.getPackageName() == null ||
                !"com.luojilab.player".contentEquals(root!!.getPackageName()))
        ) {
            if (elapsed < 15_000L)
                handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 150)
            else failDetailPlayback(expected, "得到详情页没有保持在前台")
            return
        }
        if (isRedPacketList(root)) {
            if (
                (elapsed >= 500L &&
                    probe.getBoolean("title_click_semantic", false) &&
                    !probe.getBoolean("fallback_gesture_submitted", false))
            ) {
                if (submitFallbackGesture(root, expected)) return
            }
            if (elapsed < 8_000L) {
                probe.edit().putString("seek_status", "等待列表点击进入详情：" + expected!!).apply()
                handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 100)
            } else {
                failDetailPlayback(expected, "列表点击未进入详情")
            }
            return
        }
        if (findNodesWithExactText(root, expected).isEmpty()) {
            val detailLoaded: Boolean = !findNodesWithExactLabel(root, "进入课程").isEmpty()
            if (!detailLoaded || elapsed < 8_000L) {
                probe.edit().putString("seek_status", "等待详情标题加载：" + expected!!).apply()
                handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 100)
                return
            }
            // Never press play on a detail page whose title differs from the requested queue item.
            failDetailPlayback(expected, "详情标题核对失败")
            return
        }
        if (isDedaoPlaying(expected)) {
            finishPlaybackStart(expected)
            return
        }

        val attempts: Int = probe.getInt("play_attempts", 0)
        val attemptedAt: Long = probe.getLong("play_attempt_at", 0L)
        if (attempts > 0 && System.currentTimeMillis() - attemptedAt < 1_500L) {
            handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 100)
            return
        }
        if (attempts >= 3) {
            failDetailPlayback(expected, "播放按钮多次未生效")
            return
        }

        // WebView sometimes reports ACTION_CLICK success without executing it. The first attempt
        // stays semantic; later attempts use only the verified play button's own bounds.
        if (clickPlayIfPresent(root, attempts > 0)) {
            val nextAttempt: Int = attempts + 1
            probe
                .edit()
                .putInt("play_attempts", nextAttempt)
                .putLong("play_attempt_at", System.currentTimeMillis())
                .putString("seek_status", "已核对详情，正在确认播放（第 " + nextAttempt + " 次）")
                .apply()
            markPlayStage("play_submitted", expected)
            handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 100)
            return
        }
        if (elapsed < 15_000L) handler.postDelayed(Runnable({ this.verifyDetailAndPlay() }), 150)
        else failDetailPlayback(expected, "详情页没有可用的播放按钮")
    }

    private fun failDetailPlayback(expected: String, reason: String) {
        val probe: SharedPreferences = getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val resumeQueueSync: Boolean = probe.getBoolean("official_sync_resume_after_seed", false)
        probe
            .edit()
            .remove("expected_detail_title")
            .remove("expected_detail_started_at")
            .remove("play_attempts")
            .remove("play_attempt_at")
            .putString("seek_status", reason + "：" + expected)
            .apply()
        if (resumeQueueSync) failOfficialQueueSync(reason + "：" + expected)
        else {
            returnToCompanion()
            dismissAutomationCover()
        }
    }

    private fun submitFallbackGesture(root: AccessibilityNodeInfo, expected: String): Boolean {
        for (node: AccessibilityNodeInfo in findNodesWithExactText(root, expected)) {
            if (!isCurrentlyActiveEpisode(node, expected)) continue
            val bounds: Rect = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty()) continue
            if (automationCover != null) automationCover!!.setVisibility(View.INVISIBLE)
            val path: Path = Path()
            path.moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            val gesture: GestureDescription =
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build()
            val submitted: Boolean =
                dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        public override fun onCompleted(gestureDescription: GestureDescription) {
                            if (automationCover != null)
                                automationCover!!.setVisibility(View.VISIBLE)
                            markPlayStage("fallback_tapped", expected)
                            handler.postDelayed(
                                Runnable({ this@DedaoAccessibilityService.verifyDetailAndPlay() }),
                                60,
                            )
                        }

                        public override fun onCancelled(gestureDescription: GestureDescription) {
                            if (automationCover != null)
                                automationCover!!.setVisibility(View.VISIBLE)
                            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("fallback_gesture_submitted", false)
                                .apply()
                            handler.postDelayed(
                                Runnable({ this@DedaoAccessibilityService.verifyDetailAndPlay() }),
                                120,
                            )
                        }
                    },
                    null,
                )
            if (submitted) {
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("fallback_gesture_submitted", true)
                    .putString("seek_status", "正在确认得到页面点击")
                    .apply()
                return true
            }
            if (automationCover != null) automationCover!!.setVisibility(View.VISIBLE)
        }
        return false
    }

    private fun isDedaoPlaying(expectedTitle: String): Boolean {
        try {
            val manager: MediaSessionManager =
                getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
            val listener: ComponentName = ComponentName(this, DedaoSessionListener::class.java)
            for (controller: MediaController in manager.getActiveSessions(listener)) {
                if ("com.luojilab.player" != controller.getPackageName()) continue
                val state: PlaybackState? = controller.getPlaybackState()
                val metadata: MediaMetadata? = controller.getMetadata()
                val title: String? =
                    if (metadata == null) null
                    else metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE)
                if (
                    (state != null &&
                        state!!.getState() == PlaybackState.STATE_PLAYING &&
                        expectedTitle == title)
                )
                    return true
            }
        } catch (ignored: Exception) {}

        return false
    }

    private fun finishPlaybackStart(title: String) {
        markPlayStage("playing_confirmed", title)
        val preferences: SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val resumeQueueSync: Boolean =
            preferences.getBoolean("official_sync_resume_after_seed", false)
        preferences
            .edit()
            .remove("expected_detail_title")
            .remove("expected_detail_started_at")
            .remove("play_attempts")
            .remove("play_attempt_at")
            .putString("seek_status", "已确认正在播放：" + title)
            .apply()
        if (resumeQueueSync) {
            preferences
                .edit()
                .putBoolean("official_sync_resume_after_seed", false)
                .putBoolean("official_sync_active", true)
                .putBoolean("official_sync_pending", true)
                .putString("official_sync_status", "有效内容已加入，继续同步播放顺序…")
                .apply()
            handler.postDelayed(Runnable({ this.openOfficialPlayer() }), 250)
            return
        }
        handler.postDelayed(
            {
                markPlayStage("companion_requested", title)
                returnToCompanion()
            },
            30,
        )
    }

    private fun clickPlayIfPresent(root: AccessibilityNodeInfo, useGesture: Boolean): Boolean {
        for (text: String in arrayOf<String>("播放", "继续播放", "开始学习", "继续学习")) {
            val nodes: List<AccessibilityNodeInfo> = findNodesWithExactLabel(root, text)
            for (node: AccessibilityNodeInfo in nodes) {
                val nodeBounds: Rect = Rect()
                node.getBoundsInScreen(nodeBounds)
                // Ignore Dedao's persistent mini-player at the bottom. It can still represent the
                // previous episode while the requested detail page is loading.
                val screenHeight: Int = getResources().getDisplayMetrics().heightPixels
                if (useGesture && nodeBounds.isEmpty()) continue
                if (!nodeBounds.isEmpty() && nodeBounds.top >= screenHeight - dp(104)) continue
                val submitted: Boolean
                if (useGesture) {
                    val path: Path = Path()
                    path.moveTo(nodeBounds.centerX().toFloat(), nodeBounds.centerY().toFloat())
                    val gesture: GestureDescription =
                        GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                            .build()
                    submitted = dispatchGesture(gesture, null, null)
                } else {
                    submitted = clickNodeOrParent(node)
                }
                if (submitted) {
                    lastAutoClick = System.currentTimeMillis()
                    return true
                }
            }
        }
        // Do not fall back to a hard-coded coordinate. Different courses use different detail
        // layouts; an unverified tap could activate an unrelated control.
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        val chain: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque<AccessibilityNodeInfo>()
        var cursor: AccessibilityNodeInfo? = node
        while (cursor != null && chain.size < 5) {
            chain.add(cursor)
            if (cursor!!.isClickable())
                return cursor!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cursor = cursor!!.getParent()
        }
        return false
    }

    public override fun onInterrupt() {}

    public override fun onDestroy() {
        dismissAutomationCover()
        if (activeInstance == this) activeInstance = null
        super.onDestroy()
    }

    companion object {
        // Detail probing is intentionally disabled: opening an expired red-packet item can consume
        // the course's limited trial allowance. Expiry must be resolved from list data only.
        private val DETAIL_AUTOMATION_ENABLED: Boolean = true
        @Volatile private var activeInstance: DedaoAccessibilityService? = null

        fun requestScanNow() {
            val service: DedaoAccessibilityService? = activeInstance
            if (service != null)
                service!!.handler.postDelayed(Runnable({ service!!.beginScan() }), 250)
        }

        @JvmOverloads
        fun requestPlayTitle(context: Context?, title: String?, sequenceDirection: Int = 0) {
            if (context == null || title == null || title!!.isBlank()) return
            val probe: SharedPreferences =
                context!!.getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            if (!DETAIL_AUTOMATION_ENABLED || !isVerifiedActiveTitle(context, title)) {
                probe
                    .edit()
                    .remove("pending_play_title")
                    .remove("pending_play_direction")
                    .putString("seek_status", "拒绝打开：该条目未经红包列表确认有效")
                    .apply()
                return
            }
            // Persist first. App updates and AccessibilityService reconnects must not lose a safe,
            // already list-verified play request.
            probe
                .edit()
                .putString("pending_play_title", title)
                .putInt("pending_play_direction", Integer.compare(sequenceDirection, 0))
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .remove("play_attempts")
                .remove("play_attempt_at")
                .remove("fallback_gesture_submitted")
                .remove("title_click_semantic")
                .remove("play_timing_seek_started")
                .remove("play_timing_title_tapped")
                .remove("play_timing_semantic_tapped")
                .remove("play_timing_play_submitted")
                .remove("play_timing_playing_confirmed")
                .remove("play_timing_companion_requested")
                .remove("play_timing_companion_visible")
                .putString("seek_status", "已排队，等待定位：" + title!!)
                .apply()
            val service: DedaoAccessibilityService? = activeInstance
            if (service != null) {
                service!!.markPlayStage("requested", title)
                service!!.schedulePendingPlay(60)
            }
        }

        @JvmOverloads
        fun returnToListAndPlay(context: Context, title: String, sequenceDirection: Int = 0) {
            requestPlayTitle(context, title, sequenceDirection)
            val service: DedaoAccessibilityService? = activeInstance
            if (!isVerifiedActiveTitle(context, title)) return
            val openList: Runnable = Runnable {
                try {
                    val list: Intent =
                        Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://redPacket/list"))
                    list.setPackage("com.luojilab.player")
                    val launcher: Context =
                        if (context is Activity) context
                        else (if (service == null) context else service)
                    list.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    if (!(launcher is Activity)) list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launcher.startActivity(
                        list,
                        ActivityOptions.makeCustomAnimation(launcher, 0, 0).toBundle(),
                    )
                    if (service != null) {
                        service!!.markPlayStage("list_opened", title)
                        service!!.schedulePendingPlay(120)
                    }
                } catch (ignored: Exception) {
                    if (service != null) service!!.dismissAutomationCover()
                }
            }
            if (service == null) {
                openList.run()
            } else {
                service!!.showAutomationCover(title)
                // The overlay is attached synchronously. Delaying the actual navigation only makes
                // transport controls feel unresponsive on fast devices.
                openList.run()
            }
        }

        fun dismissAutomationCoverAfter(delayMillis: Long) {
            val service: DedaoAccessibilityService? = activeInstance
            if (service != null)
                service!!
                    .handler
                    .postDelayed(Runnable({ service!!.dismissAutomationCover() }), delayMillis)
        }

        fun cancelPendingPlayback(context: Context?) {
            if (context == null) return
            context!!
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .remove("play_attempts")
                .remove("play_attempt_at")
                .apply()
            val service: DedaoAccessibilityService? = activeInstance
            if (service != null) {
                service!!
                    .handler
                    .post({
                        service!!.playTarget = ""
                        service!!.pendingPlayScheduled = false
                        service!!.seekGestureInFlight = false
                        service!!.seekPassScheduled = false
                        service!!.dismissAutomationCover()
                    })
            }
        }

        fun probeOfficialQueue(context: Context): Boolean {
            return probeOfficialQueue(context, false)
        }

        fun reorderOfficialQueueForProbe(context: Context): Boolean {
            return probeOfficialQueue(context, true)
        }

        @JvmOverloads
        fun syncOfficialQueue(
            context: Context?,
            items: List<RedPacketItem>?,
            startPlayback: Boolean = false,
        ): Boolean {
            val service: DedaoAccessibilityService? = activeInstance
            if (service == null || context == null || items == null || items!!.isEmpty())
                return false
            val titles: JSONArray = JSONArray()
            for (item: RedPacketItem in items!!) titles.put(item.title)
            val fingerprint: String = titles.toString()
            val currentTitle: String = service!!.currentDedaoTitle()
            val wasPlaying: Boolean = service!!.isDedaoSessionPlaying
            context!!
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("official_sync_titles", fingerprint)
                .putString("official_sync_fingerprint_pending", fingerprint)
                .putBoolean("official_sync_active", true)
                .putBoolean("official_sync_pending", true)
                .putBoolean("official_sync_resume_after_seed", false)
                .putInt("official_sync_moves", 0)
                .putInt("official_sync_ui_waits", 0)
                .putInt("official_sync_rewind_attempts", 0)
                .putBoolean("official_sync_queue_rewound", false)
                .remove("official_sync_seed_title")
                .putString(
                    "official_sync_start_title",
                    if (startPlayback) items!!.get(0).title else currentTitle,
                )
                .putBoolean("official_sync_was_playing", wasPlaying)
                .putBoolean("official_sync_should_play", startPlayback)
                .putString("official_sync_status", "正在同步得到播放顺序…")
                .commit()
            if (!wasPlaying) service!!.pauseDedaoSession()
            service!!.showAutomationCover("正在同步播放顺序")
            service!!.handler.removeCallbacks(service!!.coverFailSafe)
            service!!.handler.postDelayed(service!!.coverFailSafe, 60000)
            val opened: Boolean = service!!.openOfficialPlayer()
            if (opened && !wasPlaying) {
                service!!.handler.postDelayed(Runnable({ service!!.pauseDedaoSession() }), 80)
                service!!.handler.postDelayed(Runnable({ service!!.pauseDedaoSession() }), 250)
                service!!.handler.postDelayed(Runnable({ service!!.pauseDedaoSession() }), 600)
                service!!.handler.postDelayed(Runnable({ service!!.pauseDedaoSession() }), 1200)
            }
            if (!opened) service!!.failOfficialQueueSync("没有找到得到播放器")
            return opened
        }

        private fun probeOfficialQueue(context: Context, reorder: Boolean): Boolean {
            val service: DedaoAccessibilityService? = activeInstance
            if (service == null) return false
            context
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("queue_probe_pending", true)
                .putBoolean("queue_probe_active", true)
                .putBoolean("queue_probe_reorder", reorder)
                .commit()
            try {
                val intent: Intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://base/ddplayer"))
                intent.setPackage("com.luojilab.player")
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {
                return false
            }
        }

        fun hasActiveRedPacketState(text: String?): Boolean {
            if (text == null) return false
            // On the red-packet list, active rights show a learning state after the duration.
            // Expired claims show duration only. This is deliberately a list-only check: never
            // open a detail page to probe expiry because that can consume a normal trial allowance.
            return (text!!.contains("未学习") ||
                text!!.contains("已学") ||
                text!!.contains("学习中") ||
                text!!.contains("已完成"))
        }

        private fun isVerifiedActiveTitle(context: Context?, title: String?): Boolean {
            if (context == null || title == null || title!!.isBlank()) return false
            val json: String? =
                context!!
                    .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .getString("scan_items", "[]")
            try {
                val items: JSONArray = JSONArray(if (json == null) "[]" else json)
                for (i: Int in 0 until items.length()) {
                    val item: org.json.JSONObject? = items.optJSONObject(i)
                    if (item != null && title == item!!.optString("title")) return true
                }
            } catch (ignored: Exception) {}

            return false
        }
    }
}
