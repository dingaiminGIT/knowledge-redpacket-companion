package com.dingaimin.dedaocompanion

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val allItems: ArrayList<RedPacketItem> = ArrayList<RedPacketItem>()
    private val displayedItems: ArrayList<RedPacketItem> = ArrayList<RedPacketItem>()
    private val episodeRows: ArrayList<View> = ArrayList<View>()
    private val handler: Handler = Handler(Looper.getMainLooper())

    private var syncStatus: TextView? = null
    private var syncBanner: LinearLayout? = null
    private var permissionCard: LinearLayout? = null
    private var nowPlayingCard: LinearLayout? = null
    private var list: LinearLayout? = null
    private var sortOrder: Spinner? = null
    private var courseFilter: Spinner? = null
    private var hideCompletedFilter: CheckBox? = null
    private var primaryAction: Button? = null
    private var refreshAction: Button? = null
    private var diagnosticAction: Button? = null
    private var playPauseAction: ImageButton? = null
    private var previousAction: ImageButton? = null
    private var nextAction: ImageButton? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingProgress: TextView? = null
    private var nowPlayingState: TextView? = null
    private var nowPlayingCourse: TextView? = null
    private var playbackBar: ProgressBar? = null
    private var autoSyncStarted: Boolean = false
    private var externalAction: Boolean = false
    private var resumed: Boolean = false
    private var actualMediaTitle: String = ""
    private var switchingToTitle: String = ""
    private var switchStartedAt: Long = 0
    private var lastToggleAt: Long = 0
    private var optimisticPlaying: Boolean = false
    private var pendingContinuousStart: Boolean = false
    private var toggleGeneration: Int = 0
    private var directSkipGeneration: Int = 0
    private var directOpenForegroundGeneration: Int = 0
    private var monitorWaitGeneration: Int = 0

    private val queueSync: Runnable = Runnable {
        if (displayedItems.isEmpty() || allDisplayedItemsHaveAudioIds() || !isAccessibilityEnabled)
            return@Runnable
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (
            (probe.getBoolean("scan_requested", false) ||
                probe.getBoolean("official_sync_active", false))
        )
            return@Runnable
        val desired: String = displayedFingerprint()
        if (desired == probe.getString("official_queue_fingerprint", "")) return@Runnable
        if (desired == probe.getString("official_queue_failed_fingerprint", "")) return@Runnable
        DedaoAccessibilityService.syncOfficialQueue(this, ArrayList<RedPacketItem>(displayedItems))
    }

    private val scanPoll: Runnable =
        object : Runnable {
            public override fun run() {
                val probe: android.content.SharedPreferences =
                    getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                if (probe.getBoolean("scan_requested", false)) {
                    if (recoverStalledScanIfNeeded(false)) {
                        refreshCachedResults()
                        return
                    }
                    val progress: String? = probe.getString("scan_status", "正在同步有效红包…")
                    syncStatus!!.setText(if (progress == null) "正在同步有效红包…" else progress)
                    handler.postDelayed(this, 600)
                } else {
                    refreshCachedResults()
                }
            }
        }

    private val mediaPoll: Runnable =
        object : Runnable {
            public override fun run() {
                if (!resumed) return
                updateNowPlaying()
                handler.postDelayed(this, 1000)
            }
        }

    private val isAccessibilityEnabled: Boolean
        get() {
            val enabled: String? =
                Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            val component: String =
                ComponentName(this, DedaoAccessibilityService::class.java).flattenToString()
            return enabled != null && enabled!!.contains(component)
        }

    private val isNotificationListenerEnabled: Boolean
        get() {
            val enabled: String? =
                Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners")
            val component: String =
                ComponentName(this, DedaoSessionListener::class.java).flattenToString()
            return enabled != null && enabled!!.contains(component)
        }

    private val isBackgroundRestricted: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val activityManager: ActivityManager? =
                getSystemService<ActivityManager>(ActivityManager::class.java)
            if (activityManager != null && activityManager!!.isBackgroundRestricted()) return true
            val usage: UsageStatsManager? =
                getSystemService<UsageStatsManager>(UsageStatsManager::class.java)
            return (usage != null &&
                usage!!.getAppStandbyBucket() >= UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        }

    protected override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        visibleActivity = WeakReference<MainActivity>(this)
        setContentView(buildUi())
        refreshCachedResults()
        handleIntent(getIntent())
    }

    private fun handleIntent(intent: Intent?) {
        val action: String? = if (intent == null) null else intent!!.getStringExtra("action")
        val data: Uri? = if (intent == null) null else intent!!.getData()
        val bridgeReturn: Boolean =
            (data != null && "dedaocompanion" == data!!.getScheme() && "bridge" == data!!.getHost())
        externalAction = action != null || bridgeReturn
        var externalActionName: String? = action
        if (bridgeReturn) externalActionName = "bridge:" + data!!.getLastPathSegment()
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "last_external_action",
                if (externalActionName == null) "" else externalActionName,
            )
            .putLong("last_external_action_at", System.currentTimeMillis())
            .apply()
        if (bridgeReturn) {
            BridgeKeepAliveService.stop(this)
            overridePendingTransition(0, 0)
            val result: String? = data!!.getLastPathSegment()
            val probe: android.content.SharedPreferences =
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            if (probe.getBoolean("scan_requested", false)) {
                failPendingScan("确认页已返回，但没有收到完整结果")
            }
            if (resumed) BridgeReturn.onResumed(this)
            refreshCachedResults()
            if ("failed" == result && isAccessibilityEnabled) {
                syncStatus!!.setText("接口确认未完成，正在使用兼容模式…")
                handler.postDelayed(Runnable({ this.requestLegacyPageScan() }), 280L)
            }
        } else if ("scan" == action) requestPageScan()
        else if ("play" == action) playTitle(intent!!.getStringExtra("title"))
        else if ("continuous" == action) {
            handler.postDelayed(Runnable({ this.startContinuousPlayback() }), 180)
        } else if ("test_click" == action) {
            val control: String? = intent!!.getStringExtra("control")
            handler.postDelayed({ performControlTest(control) }, 180)
        } else if ("scan_complete" == action || "now_playing" == action) {
            refreshCachedResults()
            markCompanionVisible()
            DedaoAccessibilityService.dismissAutomationCoverAfter(180)
        }
    }

    private fun markCompanionVisible() {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val startedAt: Long = probe.getLong("play_timing_started_elapsed", 0L)
        if (startedAt <= 0L) return
        probe
            .edit()
            .putLong("play_timing_companion_visible", SystemClock.elapsedRealtime() - startedAt)
            .putString("play_timing_last_stage", "companion_visible")
            .apply()
    }

    private fun performControlTest(control: String?) {
        if (control == null) return
        var submitted: Boolean = true
        when (control) {
            "refresh" -> refreshAction!!.performClick()
            "previous" -> previousAction!!.performClick()
            "play_pause" -> playPauseAction!!.performClick()
            "next" -> nextAction!!.performClick()
            "continuous" -> primaryAction!!.performClick()
            "queue" -> showPlaybackQueue(false)
            "queue_manage" -> showPlaybackQueue(true)
            "first_row" -> submitted = !episodeRows.isEmpty() && episodeRows.get(0).performClick()
            "sort_oldest" -> sortOrder!!.setSelection(1)
            "sort_newest" -> sortOrder!!.setSelection(0)
            "course_first" -> {
                submitted = courseFilter!!.getCount() > 1
                if (submitted) courseFilter!!.setSelection(1)
            }
            "course_all" -> courseFilter!!.setSelection(0)
            "hide_completed_on" -> {
                submitted = hideCompletedFilter != null
                if (submitted) hideCompletedFilter!!.setChecked(true)
            }
            "hide_completed_off" -> {
                submitted = hideCompletedFilter != null
                if (submitted) hideCompletedFilter!!.setChecked(false)
            }
            "rapid_toggle" -> {
                playPauseAction!!.performClick()
                handler.postDelayed(Runnable({ playPauseAction!!.performClick() }), 120)
                handler.postDelayed(Runnable({ playPauseAction!!.performClick() }), 240)
            }
            "direct_play" ->
                submitted = OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY)
            "direct_pause" ->
                submitted = OfficialPlayerControl.send(this, OfficialPlayerControl.PAUSE)
            "direct_play_delayed" ->
                handler.postDelayed(
                    { OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY) },
                    1_500L,
                )
            "direct_pause_delayed" ->
                handler.postDelayed(
                    { OfficialPlayerControl.send(this, OfficialPlayerControl.PAUSE) },
                    1_500L,
                )
            "seek_near_end",
            "seek_last_three_minutes" -> {
                val controller: MediaController? = dedaoController()
                val metadata: MediaMetadata? =
                    if (controller == null) null else controller!!.getMetadata()
                val duration: Long =
                    if (metadata == null) 0L
                    else metadata!!.getLong(MediaMetadata.METADATA_KEY_DURATION)
                submitted = controller != null && duration > 8_000L
                if (submitted) {
                    controller!!
                        .getTransportControls()
                        .seekTo(
                            Math.max(
                                0L,
                                duration -
                                    (if ("seek_last_three_minutes" == control) 180_000L else 7_000L),
                            )
                        )
                    controller!!.getTransportControls().play()
                }
            }
            "direct_skip_0" -> submitted = OfficialPlayerControl.skip(this, 0)
            "direct_skip_1" -> submitted = OfficialPlayerControl.skip(this, 1)
            "queue_probe" -> submitted = DedaoAccessibilityService.probeOfficialQueue(this)
            "queue_reorder" ->
                submitted = DedaoAccessibilityService.reorderOfficialQueueForProbe(this)
            else -> submitted = false
        }
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putString("ui_test_last", control)
            .putBoolean("ui_test_submitted", submitted)
            .putLong("ui_test_at", System.currentTimeMillis())
            .apply()
    }

    private fun buildUi(): View {
        val root: LinearLayout = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(PAGE)
        root.setFitsSystemWindows(true)
        root.setPadding(0, 0, 0, 0)

        val header: LinearLayout = LinearLayout(this)
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setPadding(dp(14), dp(3), dp(8), dp(3))
        header.setBackgroundColor(Color.WHITE)
        val logo: ImageView = ImageView(this)
        logo.setImageResource(com.dingaimin.dedaocompanion.R.drawable.ic_launcher)
        header.addView(logo, LinearLayout.LayoutParams(dp(30), dp(30)))
        val title: TextView = text("知识红包伴侣", 19, INK, Typeface.BOLD)
        title.setPadding(dp(8), 0, 0, 0)
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        refreshAction = compactButton("刷新", { v -> requestPageScan() }, false)
        refreshAction!!.setTextColor(MUTED)
        refreshAction!!.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        header.addView(refreshAction, LinearLayout.LayoutParams(dp(58), dp(40)))
        val playbackSettings: Button = compactButton("设置", { v -> showPlaybackSettings() }, false)
        playbackSettings.setTextColor(MUTED)
        playbackSettings.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        header.addView(playbackSettings, LinearLayout.LayoutParams(dp(48), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        permissionCard = card()
        permissionCard!!.setOrientation(LinearLayout.VERTICAL)
        val permissionParams: LinearLayout.LayoutParams = cardParams()
        permissionParams.setMargins(dp(18), 0, dp(18), dp(8))
        root.addView(permissionCard, permissionParams)

        syncBanner = LinearLayout(this)
        syncBanner!!.setGravity(Gravity.CENTER_VERTICAL)
        syncBanner!!.setPadding(dp(12), 0, dp(12), 0)
        syncBanner!!.setBackground(roundRect(SOFT_ORANGE, 10))
        syncStatus = text("正在同步有效内容…", 12, MUTED, Typeface.NORMAL)
        syncBanner!!.addView(
            syncStatus,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        diagnosticAction = compactButton("复制诊断", { v -> copyDiagnostics() }, false)
        diagnosticAction!!.setTextColor(ORANGE)
        diagnosticAction!!.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        diagnosticAction!!.setVisibility(View.GONE)
        syncBanner!!.addView(diagnosticAction, LinearLayout.LayoutParams(dp(82), dp(34)))
        val syncParams: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
        syncParams.setMargins(dp(14), dp(6), dp(14), dp(6))
        root.addView(syncBanner, syncParams)
        syncBanner!!.setVisibility(View.GONE)

        nowPlayingCard = buildCompactPlayer()
        val playerParams: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        playerParams.setMargins(dp(14), dp(6), dp(14), dp(6))
        root.addView(nowPlayingCard, playerParams)

        val pickers: LinearLayout = LinearLayout(this)
        pickers.setOrientation(LinearLayout.HORIZONTAL)
        pickers.setGravity(Gravity.CENTER_VERTICAL)
        pickers.setPadding(dp(14), 0, dp(14), dp(6))
        sortOrder = spinner(arrayOf<String>("最新领取优先", "最早领取优先", "自定义顺序"))
        courseFilter = spinner(arrayOf<String>("全部课程"))
        sortOrder!!.setSelection(
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).getInt("sort_order", 0)
        )
        val sortLabel: TextView = text("排序", 13, MUTED, Typeface.NORMAL)
        sortLabel.setGravity(Gravity.CENTER_VERTICAL)
        pickers.addView(sortLabel, LinearLayout.LayoutParams(dp(38), dp(42)))
        pickers.addView(sortOrder, LinearLayout.LayoutParams(0, dp(42), 1f))
        val secondPicker: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        secondPicker.setMargins(dp(8), 0, 0, 0)
        pickers.addView(courseFilter, secondPicker)
        root.addView(
            pickers,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)),
        )

        val listener: android.widget.AdapterView.OnItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                public override fun onItemSelected(
                    parent: android.widget.AdapterView<*>,
                    view: View,
                    position: Int,
                    id: Long,
                ) {
                    val edit: android.content.SharedPreferences.Editor =
                        getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).edit()
                    if (parent === sortOrder) edit.putInt("sort_order", position)
                    if (parent === courseFilter && courseFilter!!.getSelectedItem() != null) {
                        edit.putString(
                            "course_filter",
                            (courseFilter!!.getSelectedItem()).toString(),
                        )
                    }
                    edit.apply()
                    applyFilters()
                }

                public override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
        sortOrder!!.setOnItemSelectedListener(listener)
        courseFilter!!.setOnItemSelectedListener(listener)

        hideCompletedFilter = CheckBox(this)
        hideCompletedFilter!!.setText("隐藏已学完")
        hideCompletedFilter!!.setTextSize(13f)
        hideCompletedFilter!!.setTextColor(INK)
        hideCompletedFilter!!.setButtonTintList(ColorStateList.valueOf(ORANGE))
        hideCompletedFilter!!.setGravity(Gravity.CENTER_VERTICAL)
        hideCompletedFilter!!.setPadding(dp(10), 0, dp(14), 0)
        hideCompletedFilter!!.setChecked(
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .getBoolean("hide_completed", false)
        )
        hideCompletedFilter!!.setOnCheckedChangeListener({ button, checked ->
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("hide_completed", checked)
                .apply()
            applyFilters()
        })
        val completedRow: LinearLayout = LinearLayout(this)
        completedRow.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        completedRow.addView(
            hideCompletedFilter,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)),
        )
        root.addView(
            completedRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)),
        )

        val scroll: ScrollView = ScrollView(this)
        scroll.setBackgroundColor(PAGE)
        scroll.setClipToPadding(false)
        list = LinearLayout(this)
        list!!.setOrientation(LinearLayout.VERTICAL)
        list!!.setPadding(0, 0, 0, dp(14))
        scroll.addView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        primaryAction = compactButton("连续播放  ·  2 条", { v -> startContinuousPlayback() }, true)
        primaryAction!!.setTextSize(14f)
        primaryAction!!.setBackground(roundRect(ORANGE, 13))
        val primaryParams: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        primaryParams.setMargins(dp(14), dp(6), dp(14), dp(8))
        root.addView(primaryAction, primaryParams)
        return root
    }

    private fun buildCompactPlayer(): LinearLayout {
        val player: LinearLayout = LinearLayout(this)
        player.setOrientation(LinearLayout.VERTICAL)
        player.setPadding(dp(14), dp(10), dp(14), dp(8))
        player.setBackground(roundRect(Color.WHITE, 14))

        val heading: LinearLayout = LinearLayout(this)
        heading.setGravity(Gravity.CENTER_VERTICAL)
        nowPlayingCourse = text("知识红包队列", 12, MUTED, Typeface.NORMAL)
        nowPlayingState = text("等待播放", 12, ORANGE, Typeface.BOLD)
        nowPlayingState!!.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        heading.addView(nowPlayingCourse, LinearLayout.LayoutParams(0, dp(24), 1f))
        heading.addView(
            nowPlayingState,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)),
        )
        player.addView(heading, matchWrap())

        nowPlayingTitle = text("选择一条有效内容开始播放", 17, INK, Typeface.BOLD)
        nowPlayingTitle!!.setMaxLines(2)
        nowPlayingTitle!!.setEllipsize(TextUtils.TruncateAt.END)
        nowPlayingTitle!!.setLineSpacing(0f, 1.04f)
        player.addView(
            nowPlayingTitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
        )

        val info: LinearLayout = LinearLayout(this)
        info.setGravity(Gravity.CENTER_VERTICAL)
        val progressLabel: TextView = text("播放进度", 11, MUTED, Typeface.NORMAL)
        nowPlayingProgress = text("00:00 / 00:00", 12, MUTED, Typeface.NORMAL)
        info.addView(progressLabel, LinearLayout.LayoutParams(0, dp(22), 1f))
        info.addView(
            nowPlayingProgress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)),
        )
        player.addView(info, matchWrap())

        playbackBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        playbackBar!!.setMax(1000)
        playbackBar!!.setProgressTintList(ColorStateList.valueOf(ORANGE))
        playbackBar!!.setProgressBackgroundTintList(
            ColorStateList.valueOf(Color.rgb(229, 229, 229))
        )
        player.addView(
            playbackBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)),
        )

        val transport: FrameLayout = FrameLayout(this)
        val controls: LinearLayout = LinearLayout(this)
        controls.setGravity(Gravity.CENTER)
        previousAction = mediaButton("上一条", MediaIcon.Kind.PREVIOUS, { v -> playPrevious() }, false)
        controls.addView(previousAction, LinearLayout.LayoutParams(dp(48), dp(48)))
        playPauseAction = mediaButton("播放", MediaIcon.Kind.PLAY, { v -> togglePlayback() }, true)
        val playParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        playParams.setMargins(dp(12), 0, dp(12), 0)
        controls.addView(playPauseAction, playParams)
        nextAction = mediaButton("下一条", MediaIcon.Kind.NEXT, { v -> playNext() }, false)
        controls.addView(nextAction, LinearLayout.LayoutParams(dp(48), dp(48)))
        transport.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(72), Gravity.CENTER),
        )
        val queueAction: ImageButton =
            mediaButton("播放列表", MediaIcon.Kind.QUEUE, { v -> showPlaybackQueue() }, false)
        queueAction.setImageDrawable(MediaIcon(MediaIcon.Kind.QUEUE, MUTED, dp(24)))
        transport.addView(
            queueAction,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        player.addView(
            transport,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)),
        )
        return player
    }

    private fun requestPageScan() {
        autoSyncStarted = true
        val now: Long = System.currentTimeMillis()
        val attemptId: String = UUID.randomUUID().toString().substring(0, 8)
        BridgeReturn.reset(this)
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("scan_requested", true)
            .putBoolean("scan_return_to_app", true)
            .putLong("scan_started_at", now)
            .putString("scan_attempt_id", attemptId)
            .remove("official_queue_failed_fingerprint")
            .putString("scan_source", "bridge_pending")
            .putString("scan_status", "正在请得到确认红包权益…")
            .commit()
        DiagnosticReport.mark(this, "scan_requested", "attempt=" + attemptId)
        syncStatus!!.setText("正在请得到确认红包权益…")
        syncBanner!!.setVisibility(View.VISIBLE)
        refreshAction!!.setEnabled(false)
        handler.removeCallbacks(scanPoll)
        handler.post(scanPoll)
        try {
            val bridgeUrl: String = DedaoBridgeServer.start(this)
            val route: String =
                ("igetapp://activity/detail?url=" + URLEncoder.encode(bridgeUrl, "UTF-8"))
            val intent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(route))
            intent.setPackage("com.luojilab.player")
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(0, 0)
        } catch (error: Exception) {
            DiagnosticReport.mark(this, "open_dedao_failed", error.javaClass.getSimpleName())
            if (isAccessibilityEnabled) requestLegacyPageScan()
            else {
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("scan_requested", false)
                    .putString("scan_source", "bridge_failed")
                    .putString("scan_status", "无法打开得到，请确认已安装最新版得到")
                    .apply()
                refreshCachedResults()
            }
        }
    }

    private fun requestLegacyPageScan() {
        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "当前得到版本需要开启兼容模式", Toast.LENGTH_LONG).show()
            renderPermissions()
            return
        }
        val now: Long = System.currentTimeMillis()
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("scan_requested", true)
            .putBoolean("scan_return_to_app", true)
            .putLong("scan_started_at", now)
            .putString("scan_attempt_id", "legacy-" + java.lang.Long.toHexString(now))
            .putString("scan_source", "accessibility_fallback")
            .putString("scan_status", "兼容模式正在识别有效红包…")
            .apply()
        DiagnosticReport.mark(this, "accessibility_fallback", "正在打开得到红包列表")
        DedaoAccessibilityService.requestScanNow()
        try {
            val intent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://redPacket/list"))
            intent.setPackage("com.luojilab.player")
            startActivity(intent)
        } catch (error: Exception) {
            syncStatus!!.setText("没有找到得到 App")
        }
    }

    private fun playTitle(title: String?, sequenceDirection: Int = 0) {
        if (title == null || title!!.isBlank()) return
        var item: RedPacketItem? = null
        for (candidate: RedPacketItem in displayedItems) {
            if (title == candidate.title) {
                item = candidate
                break
            }
        }
        if (item != null && item!!.audioId != null && !item!!.audioId.isBlank()) {
            playDirect(item, sequenceDirection, false)
            return
        }
        playTitleLegacy(title!!, sequenceDirection)
    }

    private fun playDirect(item: RedPacketItem?, sequenceDirection: Int, continuousStart: Boolean) {
        if (item == null || item!!.audioId == null || item!!.audioId.isBlank()) return
        PlaybackOwnership.request(this, item.title, liveOfficialTitle())
        switchingToTitle = item!!.title
        switchStartedAt = SystemClock.elapsedRealtime()
        nowPlayingState!!.setText(if (continuousStart) "正在开始连续播放…" else "正在切换…")
        setTransportEnabled(false)
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val keepContinuous: Boolean =
            (continuousStart ||
                probe.getBoolean("companion_queue_active", false) ||
                probe.getBoolean("companion_queue_suspended", false))
        probe
            .edit()
            .remove("pending_play_title")
            .remove("pending_play_direction")
            .remove("expected_detail_title")
            .remove("expected_detail_started_at")
            .putString("direct_open_target_title", item!!.title)
            .putBoolean("companion_queue_active", keepContinuous)
            .putBoolean("companion_queue_suspended", false)
            .commit()
        if (!OfficialPlayerControl.open(this, item!!.audioId)) {
            probe.edit().remove("direct_open_target_title").apply()
            playTitleLegacy(item!!.title, sequenceDirection)
            return
        }
        val generation: Int = ++directSkipGeneration
        probe
            .edit()
            .putBoolean("companion_queue_active", keepContinuous)
            .putString("direct_open_status", "已提交：" + item!!.title)
            .putLong("direct_open_at", System.currentTimeMillis())
            .apply()
        handler.postDelayed(Runnable({ this.updateNowPlaying() }), 160L)
        handler.postDelayed(
            { verifyDirectOpen(item, sequenceDirection, keepContinuous, generation, 0) },
            100L,
        )
    }

    private fun verifyDirectOpen(
        item: RedPacketItem,
        sequenceDirection: Int,
        continuousStart: Boolean,
        generation: Int,
        attempt: Int,
    ) {
        if (generation != directSkipGeneration) return
        if (PlaybackOwnership.releaseIfExternal(this, liveOfficialTitle())) return
        if (
            item.title == liveOfficialTitle() &&
                dedaoController()?.playbackState?.state == PlaybackState.STATE_PLAYING
        ) {
            switchingToTitle = ""
            setTransportEnabled(true)
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(
                    "companion_queue_active",
                    (continuousStart ||
                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                            .getBoolean("companion_queue_active", false)),
                )
                .putBoolean("direct_open_unreliable", false)
                .putString("direct_open_status", "已确认：" + item.title)
                .putString("playback_monitor_status", "正在播放：" + item.title)
                .remove("direct_open_target_title")
                .apply()
            resumeOfficialPlayback()
            returnToCompanionAfterOfficialFallback(generation)
            if (
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false)
            ) {
                PlaybackGuardService.start(this)
            }
            updateNowPlaying()
            return
        }
        if (attempt < 40) {
            if (item.title == liveOfficialTitle()) resumeOfficialPlayback()
            if (attempt == 24 && item.title != liveOfficialTitle()) {
                OfficialPlayerControl.open(this, item.audioId)
            }
            if (attempt == 12 && item.title != liveOfficialTitle())
                openOfficialContextFor(item, generation)
            handler.postDelayed(
                {
                    verifyDirectOpen(
                        item,
                        sequenceDirection,
                        continuousStart,
                        generation,
                        attempt + 1,
                    )
                },
                200L,
            )
            return
        }
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("companion_queue_active", false)
            .putBoolean("direct_open_unreliable", true)
            .putString("direct_open_status", "当前得到版本未确认直接点播")
            .remove("direct_open_target_title")
            .apply()
        QueueStore.selectTitle(this, liveOfficialTitle())
        returnToCompanionAfterOfficialFallback(generation)
        if (isAccessibilityEnabled) {
            playTitleLegacy(item.title, sequenceDirection)
            if (continuousStart) PlaybackGuardService.start(this)
        } else {
            PlaybackGuardService.stop(this)
            switchingToTitle = ""
            setTransportEnabled(true)
            nowPlayingState!!.setText("点播未确认")
            Toast.makeText(this, "当前得到版本需要开启兼容模式", Toast.LENGTH_LONG).show()
            renderPermissions()
        }
    }

    private fun openOfficialContextFor(item: RedPacketItem?, generation: Int) {
        if (item == null || item!!.deepLink == null || item!!.deepLink.isBlank()) return
        val power: PowerManager? = getSystemService<PowerManager>(PowerManager::class.java)
        if (power != null && !power!!.isInteractive()) return
        try {
            val intent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(item!!.deepLink))
            intent.setPackage("com.luojilab.player")
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            directOpenForegroundGeneration = generation
            startActivity(intent)
            overridePendingTransition(0, 0)
            handler.postDelayed(
                {
                    if (generation == directSkipGeneration) {
                        if (PlaybackOwnership.releaseIfExternal(this, liveOfficialTitle()))
                            return@postDelayed
                        OfficialPlayerControl.open(this, item!!.audioId)
                    }
                },
                350L,
            )
        } catch (ignored: Exception) {
            directOpenForegroundGeneration = 0
        }
    }

    private fun returnToCompanionAfterOfficialFallback(generation: Int) {
        if (directOpenForegroundGeneration != generation) return
        directOpenForegroundGeneration = 0
        try {
            val intent: Intent = Intent(this, MainActivity::class.java)
            intent.addFlags(
                (Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            startActivity(intent)
            overridePendingTransition(0, 0)
        } catch (ignored: Exception) {}
    }

    private fun playTitleLegacy(title: String, sequenceDirection: Int) {
        PlaybackOwnership.request(this, title, liveOfficialTitle())
        if (title != actualMediaTitle) {
            switchingToTitle = title
            switchStartedAt = SystemClock.elapsedRealtime()
            nowPlayingState!!.setText("正在切换…")
            setTransportEnabled(false)
        }
        val targetIndex: Int = displayedIndexForTitle(title)
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val officialFingerprint: String = probe.getString("official_queue_fingerprint", "") ?: ""
        if (
            (targetIndex >= 0 &&
                displayedFingerprint() == officialFingerprint &&
                !probe.getBoolean("direct_skip_unreliable", false) &&
                OfficialPlayerControl.skip(this, targetIndex))
        ) {
            val generation: Int = ++directSkipGeneration
            probe
                .edit()
                .putBoolean("companion_queue_active", true)
                .putString("direct_skip_status", "已提交：" + targetIndex + " · " + title)
                .putLong("direct_skip_at", System.currentTimeMillis())
                .apply()
            handler.postDelayed(Runnable({ this.updateNowPlaying() }), 180L)
            handler.postDelayed(Runnable({ this.updateNowPlaying() }), 650L)
            handler.postDelayed(
                { verifyDirectSkip(title, sequenceDirection, generation, 0) },
                2_000L,
            )
            return
        }
        ++directSkipGeneration
        DedaoAccessibilityService.returnToListAndPlay(this, title, sequenceDirection)
    }

    private fun verifyDirectSkip(
        title: String,
        sequenceDirection: Int,
        generation: Int,
        attempt: Int,
    ) {
        if (generation != directSkipGeneration) return
        val officialTitle: String = currentOfficialTitle()
        if (title == officialTitle) {
            switchingToTitle = ""
            setTransportEnabled(true)
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("direct_skip_status", "已确认：" + title)
                .putLong("direct_skip_confirmed_at", System.currentTimeMillis())
                .putBoolean("direct_skip_unreliable", false)
                .apply()
            updateNowPlaying()
            return
        }
        if (attempt < 2) {
            handler.postDelayed(
                { verifyDirectSkip(title, sequenceDirection, generation, attempt + 1) },
                1_000L,
            )
            return
        }
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("direct_skip_unreliable", true)
            .putString("direct_skip_status", "后台接口未确认，正在安全切换：" + title)
            .apply()
        DedaoAccessibilityService.returnToListAndPlay(this, title, sequenceDirection)
    }

    private fun startContinuousPlayback() {
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putLong("continuous_requested_at", System.currentTimeMillis())
            .putBoolean("companion_queue_suspended", false)
            .apply()
        if (displayedItems.isEmpty()) {
            requestPageScan()
            return
        }
        if (!isNotificationListenerEnabled) {
            Toast.makeText(this, "请开启“知识红包伴侣连续播放”，用于播完切下一条", Toast.LENGTH_LONG).show()
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            return
        }
        PlaybackGuardService.start(this)
        if (!DedaoSessionListener.ensureConnected(this)) {
            pendingContinuousStart = true
            nowPlayingState!!.setText("正在启动连续播放监控…")
            val generation: Int = ++monitorWaitGeneration
            handler.postDelayed({ waitForPlaybackMonitor(generation, 0) }, 500L)
            return
        }
        pendingContinuousStart = false
        QueueStore.save(this, displayedItems, 0)
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        probe.edit().remove("official_queue_failed_fingerprint").apply()
        val first: RedPacketItem = displayedItems.get(0)
        PlaybackOwnership.request(this, first.title, liveOfficialTitle())
        if (first.audioId != null && !first.audioId.isBlank()) {
            playDirect(first, 1, true)
            return
        }
        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "旧缓存需要刷新；若仍失败请开启兼容模式", Toast.LENGTH_LONG).show()
            requestPageScan()
            return
        }
        if (
            (displayedFingerprint() == probe.getString("official_queue_fingerprint", "") &&
                !probe.getBoolean("direct_skip_unreliable", false) &&
                !liveOfficialTitle().isBlank())
        ) {
            if (OfficialPlayerControl.skip(this, 0)) {
                val generation: Int = ++directSkipGeneration
                switchingToTitle = first.title
                switchStartedAt = SystemClock.elapsedRealtime()
                probe
                    .edit()
                    .putBoolean("companion_queue_active", false)
                    .putString("direct_skip_status", "连续播放已提交：" + first.title)
                    .apply()
                handler.postDelayed(Runnable({ this.updateNowPlaying() }), 250L)
                handler.postDelayed(Runnable({ this.updateNowPlaying() }), 900L)
                handler.postDelayed({ verifyContinuousStart(first.title, generation, 0) }, 1_200L)
                return
            }
        }
        ++directSkipGeneration
        if (
            !DedaoAccessibilityService.syncOfficialQueue(
                this,
                ArrayList<RedPacketItem>(displayedItems),
                true,
            )
        ) {
            Toast.makeText(this, "播放顺序同步服务暂未就绪", Toast.LENGTH_SHORT).show()
        }
    }

    private fun waitForPlaybackMonitor(generation: Int, attempt: Int) {
        if (generation != monitorWaitGeneration || !pendingContinuousStart) return
        if (DedaoSessionListener.isConnected) {
            pendingContinuousStart = false
            startContinuousPlayback()
            return
        }
        if (attempt == 5) DedaoSessionListener.repairBinding(this)
        else DedaoSessionListener.ensureConnected(this)
        if (attempt < 24) {
            handler.postDelayed({ waitForPlaybackMonitor(generation, attempt + 1) }, 500L)
            return
        }
        pendingContinuousStart = false
        nowPlayingState!!.setText("连续播放监控未连接")
        PlaybackGuardService.stop(this)
        Toast.makeText(this, "授权仍然有效，系统监控正在恢复，请稍后再点一次连续播放", Toast.LENGTH_LONG).show()
        renderPermissions()
    }

    private fun verifyContinuousStart(title: String, generation: Int, attempt: Int) {
        if (generation != directSkipGeneration) return
        val liveTitle: String = liveOfficialTitle()
        if (title == liveTitle) {
            OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY)
            switchingToTitle = ""
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("companion_queue_active", true)
                .putBoolean("direct_skip_unreliable", false)
                .putString("direct_skip_status", "连续播放已确认：" + title)
                .apply()
            updateNowPlaying()
            return
        }
        if (attempt < 2) {
            handler.postDelayed({ verifyContinuousStart(title, generation, attempt + 1) }, 900L)
            return
        }
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        probe
            .edit()
            .putBoolean("companion_queue_active", false)
            .putBoolean("direct_skip_unreliable", true)
            .remove("official_queue_fingerprint")
            .putString("direct_skip_status", "得到播放器队列已失效，正在重新同步")
            .commit()
        if (
            !DedaoAccessibilityService.syncOfficialQueue(
                this,
                ArrayList<RedPacketItem>(displayedItems),
                true,
            )
        ) {
            Toast.makeText(this, "得到播放器队列已失效，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dedaoController(): MediaController? {
        try {
            val manager: MediaSessionManager =
                getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
            val listener: ComponentName = ComponentName(this, DedaoSessionListener::class.java)
            for (controller: MediaController in manager.getActiveSessions(listener)) {
                if ("com.luojilab.player" == controller.getPackageName()) return controller
            }
        } catch (ignored: Exception) {}

        return null
    }

    private fun updateNowPlaying() {
        val controller: MediaController? = dedaoController()
        var current: RedPacketItem? = QueueStore.current(this)
        if (controller == null || current == null || controller!!.getMetadata() == null) {
            actualMediaTitle = ""
            nowPlayingTitle!!.setText(if (current == null) "选择一条有效内容开始播放" else current!!.title)
            nowPlayingCourse!!.setText(if (current == null) "知识红包队列" else current!!.course)
            nowPlayingState!!.setText("等待播放")
            nowPlayingProgress!!.setText("00:00 / 00:00")
            playbackBar!!.setProgress(0)
            updatePlayPauseVisual(false)
            setTransportEnabled(false)
            return
        }
        val metadata: MediaMetadata = controller!!.getMetadata() ?: return
        var title: String? = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title == null) title = ""
        val state: PlaybackState? = controller!!.getPlaybackState()
        var position: Long = if (state == null) 0L else Math.max(0L, state!!.getPosition())
        if (state != null && state!!.getState() == PlaybackState.STATE_PLAYING) {
            val elapsed: Long =
                Math.max(0L, SystemClock.elapsedRealtime() - state!!.getLastPositionUpdateTime())
            position += (elapsed * state!!.getPlaybackSpeed()).toLong()
        }
        val duration: Long = Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        val sessionPlaying: Boolean =
            state != null && state!!.getState() == PlaybackState.STATE_PLAYING

        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        var pending: String? = probe.getString("pending_play_title", "")
        val pendingStarted: Long = probe.getLong("play_timing_started_elapsed", 0L)
        if (
            (pending != null &&
                !pending!!.isBlank() &&
                ((pendingStarted <= 0L ||
                    SystemClock.elapsedRealtime() - pendingStarted > 60_000L)))
        ) {
            probe
                .edit()
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .apply()
            pending = ""
        }
        if (pending == null || pending!!.isBlank()) {
            pending = probe.getString("expected_detail_title", "")
            val expectedStarted: Long = probe.getLong("expected_detail_started_at", 0L)
            if (
                (pending != null &&
                    !pending!!.isBlank() &&
                    ((expectedStarted <= 0L ||
                        System.currentTimeMillis() - expectedStarted > 60_000L)))
            ) {
                probe
                    .edit()
                    .remove("expected_detail_title")
                    .remove("expected_detail_started_at")
                    .apply()
                pending = ""
            }
        }
        if (pending != null && !pending!!.isBlank() && pending != title) {
            val playingItem: RedPacketItem? = findActiveItem(title)
            val previousActual: String = actualMediaTitle
            actualMediaTitle = title
            if (actualMediaTitle != previousActual && list != null) renderItems()
            nowPlayingTitle!!.setText(title)
            val artist: String? = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            nowPlayingCourse!!.setText(
                if (playingItem != null) playingItem!!.course
                else (if (artist == null || artist!!.isBlank()) "得到正在播放的内容" else artist)
            )
            nowPlayingState!!.setText("正在切换…")
            nowPlayingProgress!!.setText(
                formatPlaybackTime(position) + " / " + formatPlaybackTime(duration)
            )
            playbackBar!!.setProgress(
                if (duration <= 0L) 0 else Math.min(1000L, position * 1000L / duration).toInt()
            )
            updatePlayPauseVisual(sessionPlaying)
            setTransportEnabled(false)
            return
        }

        val actualItem: RedPacketItem? = QueueStore.selectTitle(this, title)
        val previousActual: String = actualMediaTitle
        actualMediaTitle = title
        if (actualItem != null) current = actualItem
        if (actualMediaTitle != previousActual && list != null) renderItems()

        nowPlayingTitle!!.setText(title)
        val artist: String? = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        nowPlayingCourse!!.setText(
            if (actualItem != null) actualItem!!.course
            else (if (artist == null || artist!!.isBlank()) "得到正在播放的内容" else artist)
        )
        nowPlayingProgress!!.setText(
            formatPlaybackTime(position) + " / " + formatPlaybackTime(duration)
        )
        val playing: Boolean =
            if (SystemClock.elapsedRealtime() - lastToggleAt < 800L) optimisticPlaying
            else sessionPlaying
        updatePlayPauseVisual(playing)
        nowPlayingState!!.setText(
            if (actualItem == null) (if (playing) "得到正在播放其他内容" else "得到其他内容已暂停")
            else (if (playing) "正在播放" else "已暂停")
        )
        playbackBar!!.setProgress(
            if (duration <= 0L) 0 else Math.min(1000L, position * 1000L / duration).toInt()
        )
        if (!switchingToTitle.isBlank() && switchingToTitle == title) {
            switchingToTitle = ""
            switchStartedAt = 0L
        } else if (
            (!switchingToTitle.isBlank() &&
                SystemClock.elapsedRealtime() - switchStartedAt > 35_000L)
        ) {
            switchingToTitle = ""
            switchStartedAt = 0L
        }
        setTransportEnabled(switchingToTitle.isBlank())
    }

    private fun findActiveItem(title: String?): RedPacketItem? {
        if (title == null || title!!.isBlank()) return null
        for (item: RedPacketItem in allItems) if (title == item.title) return item
        return null
    }

    private fun updatePlayPauseVisual(playing: Boolean) {
        optimisticPlaying = playing
        playPauseAction!!.setContentDescription(if (playing) "暂停" else "播放")
        playPauseAction!!.setImageDrawable(
            MediaIcon(
                if (playing) MediaIcon.Kind.PAUSE else MediaIcon.Kind.PLAY,
                Color.WHITE,
                dp(28),
            )
        )
    }

    private fun setTransportEnabled(enabled: Boolean) {
        // Pause must remain available while a previous/next request is being confirmed.
        playPauseAction!!.setEnabled(dedaoController() != null)
        if (previousAction != null) previousAction!!.setEnabled(enabled)
        if (nextAction != null) nextAction!!.setEnabled(enabled)
        if (previousAction != null) previousAction!!.setAlpha(if (enabled) 1f else 0.35f)
        if (nextAction != null) nextAction!!.setAlpha(if (enabled) 1f else 0.35f)
    }

    private fun formatPlaybackTime(millis: Long): String {
        val seconds: Long = Math.max(0L, millis / 1_000L)
        return String.format(Locale.CHINA, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun togglePlayback() {
        val controller: MediaController? = dedaoController()
        if (controller == null) return
        val state: PlaybackState? = controller!!.getPlaybackState()
        val sessionPlaying: Boolean =
            state != null && state!!.getState() == PlaybackState.STATE_PLAYING
        val now: Long = SystemClock.elapsedRealtime()
        val baseline: Boolean = if (now - lastToggleAt < 800L) optimisticPlaying else sessionPlaying
        val desiredPlaying: Boolean = !baseline
        if (
            (desiredPlaying &&
                findActiveItem(liveOfficialTitle()) != null &&
                displayedIndexForTitle(liveOfficialTitle()) < 0)
        ) {
            val selected: RedPacketItem? = QueueStore.current(this)
            if (selected != null) playDirect(selected, 1, false)
            return
        }
        lastToggleAt = now
        optimisticPlaying = desiredPlaying
        val generation: Int = ++toggleGeneration
        if (!desiredPlaying) {
            cancelPendingPlaybackSwitch()
            suspendContinuousQueue()
        } else {
            resumeContinuousQueueIfSuspended()
        }
        updatePlayPauseVisual(desiredPlaying)
        nowPlayingState!!.setText(if (desiredPlaying) "正在开始播放…" else "正在暂停…")
        applyPlaybackState(desiredPlaying)
        handler.postDelayed({ verifyPlaybackState(desiredPlaying, generation, 0) }, 250L)
    }

    private fun applyPlaybackState(playing: Boolean) {
        val controller: MediaController? = dedaoController()
        if (controller != null) {
            if (playing) controller!!.getTransportControls().play()
            else controller!!.getTransportControls().pause()
        }
        OfficialPlayerControl.send(
            this,
            if (playing) OfficialPlayerControl.PLAY else OfficialPlayerControl.PAUSE,
        )
    }

    private fun verifyPlaybackState(desiredPlaying: Boolean, generation: Int, attempt: Int) {
        if (generation != toggleGeneration) return
        val controller: MediaController? = dedaoController()
        val state: PlaybackState? =
            if (controller == null) null else controller!!.getPlaybackState()
        val actualPlaying: Boolean =
            (state != null && state!!.getState() == PlaybackState.STATE_PLAYING)
        if (actualPlaying == desiredPlaying) {
            updateNowPlaying()
            return
        }
        if (attempt < 3) {
            applyPlaybackState(desiredPlaying)
            handler.postDelayed(
                { verifyPlaybackState(desiredPlaying, generation, attempt + 1) },
                if (attempt == 0) 450L else 800L,
            )
            return
        }
        updateNowPlaying()
        Toast.makeText(this, if (desiredPlaying) "得到暂未开始播放" else "得到暂未暂停", Toast.LENGTH_SHORT)
            .show()
    }

    private fun cancelPendingPlaybackSwitch() {
        ++directSkipGeneration
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .remove("direct_open_target_title")
            .apply()
        directOpenForegroundGeneration = 0
        switchingToTitle = ""
        switchStartedAt = 0L
        DedaoAccessibilityService.cancelPendingPlayback(this)
        QueueStore.selectTitle(this, liveOfficialTitle())
        setTransportEnabled(true)
    }

    private fun suspendContinuousQueue() {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val wasActive: Boolean = probe.getBoolean("companion_queue_active", false)
        probe
            .edit()
            .putBoolean("companion_queue_active", false)
            .putBoolean(
                "companion_queue_suspended",
                (wasActive || probe.getBoolean("companion_queue_suspended", false)),
            )
            .putString("playback_monitor_status", if (wasActive) "连续播放已暂停" else "播放已暂停")
            .apply()
        PlaybackGuardService.stop(this)
    }

    private fun resumeContinuousQueueIfSuspended() {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (!probe.getBoolean("companion_queue_suspended", false)) return
        probe
            .edit()
            .putBoolean("companion_queue_suspended", false)
            .putBoolean("companion_queue_active", true)
            .putLong("playback_monitor_last_playing_at", System.currentTimeMillis())
            .putString("playback_monitor_status", "连续播放已恢复")
            .apply()
        PlaybackGuardService.start(this)
    }

    private fun resumeOfficialPlayback() {
        val controller: MediaController? = dedaoController()
        if (controller == null) return
        val state: PlaybackState? = controller!!.getPlaybackState()
        if (state != null && state!!.getState() == PlaybackState.STATE_PLAYING) return
        controller!!.getTransportControls().play()
        OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY)
    }

    private fun playNext() {
        playAdjacent(true)
    }

    private fun playPrevious() {
        playAdjacent(false)
    }

    private fun playAdjacent(forward: Boolean) {
        val liveTitle: String = currentOfficialTitle()
        var actualIndex: Int = displayedIndexForTitle(liveTitle)
        if (actualIndex < 0) {
            val stored: RedPacketItem? = QueueStore.current(this)
            if (stored != null) actualIndex = displayedIndexForTitle(stored!!.title)
        }
        val targetIndex: Int = actualIndex + (if (forward) 1 else -1)
        if (actualIndex < 0 || targetIndex < 0 || targetIndex >= displayedItems.size) {
            Toast.makeText(this, if (forward) "已经是最后一条" else "已经是第一条", Toast.LENGTH_SHORT).show()
            return
        }
        val item: RedPacketItem = displayedItems.get(targetIndex)
        QueueStore.save(this, displayedItems, targetIndex)
        switchingToTitle = item.title
        switchStartedAt = SystemClock.elapsedRealtime()
        nowPlayingState!!.setText("正在切换…")
        if (item.audioId != null && !item.audioId.isBlank()) {
            val queueActive: Boolean =
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false)
            playDirect(item, if (forward) 1 else -1, queueActive)
        } else {
            playTitleLegacy(item.title, if (forward) 1 else -1)
        }
        handler.postDelayed(Runnable({ this.updateNowPlaying() }), 80)
        handler.postDelayed(Runnable({ this.updateNowPlaying() }), 300)
    }

    private fun currentOfficialTitle(): String {
        val liveTitle: String = liveOfficialTitle()
        return if (liveTitle.isBlank()) actualMediaTitle else liveTitle
    }

    private fun liveOfficialTitle(): String {
        return dedaoController()?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    }

    private fun displayedIndexForTitle(title: String?): Int {
        if (title == null || title!!.isBlank()) return -1
        for (i: Int in displayedItems.indices) {
            if (title == displayedItems.get(i).title) return i
        }
        return -1
    }

    private fun refreshCachedResults() {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val scanning: Boolean = probe.getBoolean("scan_requested", false)
        val failed: Boolean = "bridge_failed" == probe.getString("scan_source", "")
        var failureStatus: String? = probe.getString("scan_status", "同步未完成")
        if (failureStatus == null || failureStatus!!.isBlank()) failureStatus = "同步未完成"
        diagnosticAction!!.setVisibility(if (failed && !scanning) View.VISIBLE else View.GONE)
        val count: Int = loadScannedItems(probe.getString("scan_items", "[]"))
        refreshAction!!.setEnabled(!scanning)
        if (count > 0) {
            rebuildCourseFilter()
            applyFilters()
            syncBanner!!.setVisibility(if (scanning || failed) View.VISIBLE else View.GONE)
            if (scanning) syncStatus!!.setText(probe.getString("scan_status", "正在向得到确认红包权益…"))
            else if (failed) syncStatus!!.setText(failureStatus)
        } else if (scanning) {
            syncBanner!!.setVisibility(View.VISIBLE)
            syncStatus!!.setText(probe.getString("scan_status", "正在同步有效红包…"))
            renderEmpty("正在同步，请稍候…")
        } else if (failed) {
            syncBanner!!.setVisibility(View.VISIBLE)
            syncStatus!!.setText(failureStatus)
            renderEmpty("同步未完成，可重试或复制诊断反馈")
        } else {
            syncBanner!!.setVisibility(View.VISIBLE)
            syncStatus!!.setText("尚未同步有效红包")
            renderEmpty("点击刷新，由得到确认当前有效红包")
        }
        renderPermissions()
    }

    private fun loadScannedItems(json: String?): Int {
        try {
            val array: JSONArray = JSONArray(if (json == null) "[]" else json)
            allItems.clear()
            for (i: Int in 0 until array.length()) {
                val value: JSONObject? = array.optJSONObject(i)
                if (value != null) allItems.add(RedPacketItem.fromStored(value!!))
            }
            return allItems.size
        } catch (ignored: Exception) {
            allItems.clear()
            return 0
        }
    }

    private fun rebuildCourseFilter() {
        val previous: String =
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .getString("course_filter", "全部课程") ?: "全部课程"
        val courses: MutableSet<String> = LinkedHashSet<String>()
        for (item: RedPacketItem in allItems) if (!item.course.isBlank()) courses.add(item.course)
        val values: ArrayList<String> = ArrayList<String>()
        values.add("全部课程")
        values.addAll(courses)
        courseFilter!!.setAdapter(adapter(values))
        courseFilter!!.setSelection(Math.max(0, values.indexOf(previous)))
    }

    private fun applyFilters() {
        if (list == null || sortOrder == null || courseFilter == null) return
        val previouslyCurrent: RedPacketItem? = QueueStore.current(this)
        val selectedCourse: String =
            if (courseFilter!!.getSelectedItem() == null) "全部课程"
            else (courseFilter!!.getSelectedItem()).toString()
        displayedItems.clear()
        val orderPosition: Int = sortOrder!!.getSelectedItemPosition()
        var filtered: List<RedPacketItem> =
            QueuePolicy.apply(
                allItems,
                selectedCourse,
                hideCompletedFilter != null && hideCompletedFilter!!.isChecked(),
                orderPosition != 1,
            )
        if (orderPosition == 2) {
            filtered = QueuePolicy.applyCustomOrder(filtered, loadCustomQueueOrder())
        }
        displayedItems.addAll(QueuePolicy.excluding(filtered, removedQueueKeys()))
        var preservedIndex: Int = 0
        if (previouslyCurrent != null) {
            for (i: Int in displayedItems.indices) {
                if (
                    QueuePolicy.stableKey(previouslyCurrent) ==
                        QueuePolicy.stableKey(displayedItems.get(i))
                ) {
                    preservedIndex = i
                    break
                }
            }
        }
        QueueStore.save(this, displayedItems, preservedIndex)
        renderItems()
        handler.removeCallbacks(queueSync)
        handler.postDelayed(queueSync, 700)
    }

    private fun displayedFingerprint(): String {
        val titles: JSONArray = JSONArray()
        for (item: RedPacketItem in displayedItems) titles.put(item.title)
        return titles.toString()
    }

    private fun allDisplayedItemsHaveAudioIds(): Boolean {
        if (displayedItems.isEmpty()) return false
        for (item: RedPacketItem in displayedItems) {
            if (item.audioId == null || item.audioId.isBlank()) return false
        }
        return true
    }

    private fun renderItems() {
        list!!.removeAllViews()
        episodeRows.clear()
        if (displayedItems.isEmpty()) {
            renderEmpty(if (allItems.isEmpty()) "还没有同步到有效内容" else "当前筛选下没有内容")
            primaryAction!!.setText(if (allItems.isEmpty()) "同步有效红包" else "没有可播放内容")
            primaryAction!!.setEnabled(allItems.isEmpty())
            return
        }
        val count: TextView = text("有效内容  ·  " + displayedItems.size + " 条", 14, INK, Typeface.BOLD)
        count.setPadding(dp(16), dp(10), dp(16), dp(9))
        list!!.addView(count, matchWrap())
        val current: RedPacketItem? = QueueStore.current(this)
        var groupCourse: String? = null
        var group: LinearLayout? = null
        for (i: Int in displayedItems.indices) {
            val item: RedPacketItem = displayedItems.get(i)
            val index: Int = i
            val selected: Boolean =
                if (actualMediaTitle.isBlank()) current != null && current!!.id == item.id
                else actualMediaTitle == item.title

            val courseName: String = if (item.course.isBlank()) "未识别课程" else item.course
            if (courseName != groupCourse) {
                groupCourse = courseName
                group = LinearLayout(this)
                group!!.setOrientation(LinearLayout.VERTICAL)
                group!!.setBackground(roundRect(Color.WHITE, 14))

                val courseHeader: LinearLayout = LinearLayout(this)
                courseHeader.setGravity(Gravity.CENTER_VERTICAL)
                courseHeader.setPadding(dp(14), dp(8), dp(14), dp(8))
                val courseIcon: ImageView = ImageView(this)
                courseIcon.setImageResource(com.dingaimin.dedaocompanion.R.drawable.ic_launcher)
                courseHeader.addView(courseIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
                val courseTitle: TextView = text(courseName, 14, INK, Typeface.BOLD)
                courseTitle.setPadding(dp(9), 0, 0, 0)
                courseHeader.addView(courseTitle, LinearLayout.LayoutParams(0, dp(34), 1f))
                group!!.addView(
                    courseHeader,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
                )

                val groupParams: LinearLayout.LayoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                groupParams.setMargins(dp(14), 0, dp(14), dp(6))
                list!!.addView(group, groupParams)
            }

            val row: LinearLayout = LinearLayout(this)
            row.setGravity(Gravity.CENTER_VERTICAL)
            row.setPadding(dp(12), dp(7), dp(10), dp(7))
            row.setBackgroundColor(Color.WHITE)

            val copy: LinearLayout = LinearLayout(this)
            copy.setOrientation(LinearLayout.VERTICAL)
            copy.setPadding(0, 0, dp(10), 0)
            val itemTitle: TextView =
                text(item.title, 15, if (selected) ORANGE else INK, Typeface.BOLD)
            itemTitle.setPadding(0, 0, 0, dp(3))
            itemTitle.setLineSpacing(0f, 1.08f)
            itemTitle.setMaxLines(2)
            itemTitle.setEllipsize(TextUtils.TruncateAt.END)
            val learningState: String = if (item.completed) "已学完" else "红包有效"
            val meta: TextView =
                text(
                    if (selected) learningState + "  ·  当前播放" else learningState + "  ·  点击播放",
                    11,
                    if (selected) ORANGE else MUTED,
                    if (selected) Typeface.BOLD else Typeface.NORMAL,
                )
            copy.addView(itemTitle, matchWrap())
            copy.addView(meta, matchWrap())
            row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val play: ImageView = ImageView(this)
            play.setImageResource(android.R.drawable.ic_media_play)
            play.setColorFilter(if (selected) ORANGE else MUTED)
            play.setContentDescription("播放 " + item.title)
            row.addView(play, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.setOnClickListener({ v ->
                QueueStore.save(this, displayedItems, index)
                renderItems()
                playTitle(item.title)
            })
            episodeRows.add(row)
            if (group != null)
                group!!.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)),
                )
            val nextSameCourse: Boolean =
                (i < displayedItems.size - 1 &&
                    courseName ==
                        (if (displayedItems.get(i + 1).course.isBlank()) "未识别课程"
                        else displayedItems.get(i + 1).course))
            if (nextSameCourse && group != null) {
                val divider: View = View(this)
                divider.setBackgroundColor(LINE)
                val dividerParams: LinearLayout.LayoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                dividerParams.setMargins(dp(14), 0, dp(14), 0)
                group!!.addView(divider, dividerParams)
            }
        }
        primaryAction!!.setEnabled(true)
        primaryAction!!.setText("连续播放  ·  " + displayedItems.size + " 条")
    }

    private fun showPlaybackQueue(initiallyManaging: Boolean = false) {
        if (displayedItems.isEmpty() && removedQueueKeys().isEmpty()) {
            Toast.makeText(this, "播放队列还是空的，请先刷新", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog: Dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val workingQueue: ArrayList<RedPacketItem> = ArrayList<RedPacketItem>(displayedItems)
        dialog.setContentView(buildPlaybackQueueSheet(dialog, workingQueue, initiallyManaging))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        val window: Window? = dialog.getWindow()
        if (window == null) return
        window!!.setBackgroundDrawableResource(android.R.color.transparent)
        window!!.getDecorView().setPadding(0, 0, 0, 0)
        window!!.setGravity(Gravity.BOTTOM)
        window!!.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes: WindowManager.LayoutParams = window!!.getAttributes()
        attributes.dimAmount = 0.48f
        window!!.setAttributes(attributes)
        val screenHeight: Int = getResources().getDisplayMetrics().heightPixels
        window!!.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.min(screenHeight - dp(72), dp(620)),
        )
    }

    private fun buildPlaybackQueueSheet(
        dialog: Dialog,
        workingQueue: ArrayList<RedPacketItem>,
        managing: Boolean,
    ): View {
        val adapterHolder: Array<QueueAdapter?> = arrayOfNulls<QueueAdapter>(1)
        val sheet: LinearLayout = LinearLayout(this)
        sheet.setOrientation(LinearLayout.VERTICAL)
        sheet.setPadding(0, dp(8), 0, 0)
        sheet.setBackground(roundTopRect(Color.WHITE, 22))

        val header: LinearLayout = LinearLayout(this)
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setPadding(dp(20), 0, dp(14), 0)
        if (managing) {
            val leadingSpace: View = View(this)
            header.addView(leadingSpace, LinearLayout.LayoutParams(dp(64), dp(56)))
            val titleBlock: LinearLayout = LinearLayout(this)
            titleBlock.setOrientation(LinearLayout.VERTICAL)
            titleBlock.setGravity(Gravity.CENTER)
            titleBlock.addView(
                text("管理播放队列", 17, INK, Typeface.BOLD),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            titleBlock.addView(
                text("移除条目 · 拖动排序", 11, MUTED, Typeface.NORMAL),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            header.addView(titleBlock, LinearLayout.LayoutParams(0, dp(64), 1f))
            val done: Button =
                compactButton(
                    "完成",
                    { v ->
                        adapterHolder[0]?.persistIfChanged()
                        val updated: ArrayList<RedPacketItem> =
                            ArrayList<RedPacketItem>(displayedItems)
                        dialog.setContentView(buildPlaybackQueueSheet(dialog, updated, false))
                    },
                    false,
                )
            done.setTextColor(ORANGE)
            done.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
            header.addView(done, LinearLayout.LayoutParams(dp(64), dp(48)))
        } else {
            val modeChip: LinearLayout = LinearLayout(this)
            modeChip.setGravity(Gravity.CENTER_VERTICAL)
            modeChip.setPadding(dp(10), 0, dp(12), 0)
            modeChip.setBackground(roundRect(Color.rgb(247, 247, 248), 22))
            val orderIcon: ImageView = ImageView(this)
            orderIcon.setImageDrawable(MediaIcon(MediaIcon.Kind.QUEUE, INK, dp(24)))
            orderIcon.setColorFilter(INK)
            orderIcon.setContentDescription("顺序播放")
            modeChip.addView(orderIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
            val title: TextView =
                text("顺序播放  ·  " + workingQueue.size + " 条", 17, INK, Typeface.BOLD)
            title.setPadding(dp(8), 0, 0, 0)
            modeChip.addView(
                title,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
            )
            header.addView(
                modeChip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
            )
            val spacer: View = View(this)
            header.addView(spacer, LinearLayout.LayoutParams(0, dp(44), 1f))
            val manage: Button =
                compactButton(
                    "管理",
                    { v ->
                        dialog.setContentView(buildPlaybackQueueSheet(dialog, workingQueue, true))
                    },
                    false,
                )
            manage.setTextColor(INK)
            manage.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
            header.addView(manage, LinearLayout.LayoutParams(dp(68), dp(48)))
        }
        sheet.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)),
        )

        val headerDivider: View = View(this)
        headerDivider.setBackgroundColor(LINE)
        sheet.addView(
            headerDivider,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )

        val queueList: RecyclerView = RecyclerView(this)
        queueList.setLayoutManager(LinearLayoutManager(this))
        queueList.setClipToPadding(false)
        queueList.setPadding(dp(14), dp(4), dp(14), dp(12))
        queueList.setHasFixedSize(true)
        val adapter: QueueAdapter = QueueAdapter(dialog, workingQueue, managing)
        adapterHolder[0] = adapter
        queueList.setAdapter(adapter)
        if (managing) {
            val helper: ItemTouchHelper =
                ItemTouchHelper(
                    object :
                        ItemTouchHelper.SimpleCallback(
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                            0,
                        ) {

                        override fun isLongPressDragEnabled(): Boolean = false

                        override fun onMove(
                            recyclerView: RecyclerView,
                            source: RecyclerView.ViewHolder,
                            target: RecyclerView.ViewHolder,
                        ): Boolean {
                            return adapter.move(
                                source.getBindingAdapterPosition(),
                                target.getBindingAdapterPosition(),
                            )
                        }

                        override fun onSwiped(
                            viewHolder: RecyclerView.ViewHolder,
                            direction: Int,
                        ) {}

                        override fun getMoveThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                            return 0.55f
                        }

                        override fun onSelectedChanged(
                            viewHolder: RecyclerView.ViewHolder?,
                            actionState: Int,
                        ) {
                            super.onSelectedChanged(viewHolder, actionState)
                            if (
                                viewHolder != null &&
                                    actionState == ItemTouchHelper.ACTION_STATE_DRAG
                            ) {
                                viewHolder!!.itemView.setElevation(dp(10).toFloat())
                                viewHolder!!.itemView.setScaleX(1.015f)
                                viewHolder!!.itemView.setScaleY(1.015f)
                            }
                        }

                        override fun clearView(
                            recyclerView: RecyclerView,
                            viewHolder: RecyclerView.ViewHolder,
                        ) {
                            super.clearView(recyclerView, viewHolder)
                            viewHolder.itemView
                                .animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(120)
                                .start()
                            viewHolder.itemView.setElevation(0f)
                            adapter.persistIfChanged()
                        }
                    }
                )
            helper.attachToRecyclerView(queueList)
            adapter.setDragHelper(helper)
        }
        sheet.addView(
            queueList,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        val currentIndex: Int = adapter.currentIndex()
        if (currentIndex > 0) queueList.post({ queueList.scrollToPosition(currentIndex) })

        if (managing) {
            val footerDivider: View = View(this)
            footerDivider.setBackgroundColor(LINE)
            sheet.addView(
                footerDivider,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
            val footer: TextView = text("修改自动保存 · 仅影响本地播放列表", 12, MUTED, Typeface.NORMAL)
            footer.setGravity(Gravity.CENTER)
            footer.setBackgroundColor(Color.rgb(248, 248, 250))
            sheet.addView(
                footer,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
            )
        }
        if (workingQueue.isEmpty()) {
            val empty: TextView = text("播放列表已清空", 15, MUTED, Typeface.NORMAL)
            empty.setGravity(Gravity.CENTER)
            sheet.addView(empty, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val removedCount: Int = removedQueueKeys().size
        if (removedCount > 0) {
            val restore: Button =
                compactButton(
                    "恢复已移除的内容（" + removedCount + "）",
                    { v ->
                        getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                            .edit()
                            .remove("removed_queue_keys")
                            .apply()
                        applyFilters()
                        dialog.setContentView(
                            buildPlaybackQueueSheet(
                                dialog,
                                ArrayList<RedPacketItem>(displayedItems),
                                managing,
                            )
                        )
                    },
                    false,
                )
            val restoreParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, dp(48))
            restoreParams.setMargins(dp(20), dp(6), dp(20), dp(16))
            sheet.addView(restore, restoreParams)
        }
        return sheet
    }

    private inner class QueueAdapter(
        private val dialog: Dialog,
        private val items: ArrayList<RedPacketItem>,
        private val managing: Boolean,
    ) : RecyclerView.Adapter<QueueAdapter.QueueHolder>() {
        private val current: RedPacketItem? = QueueStore.current(this@MainActivity)
        private var dragHelper: ItemTouchHelper? = null
        private var changed: Boolean = false

        override fun getItemCount(): Int = items.size

        fun setDragHelper(helper: ItemTouchHelper) {
            dragHelper = helper
        }

        fun currentIndex(): Int {
            for (i: Int in items.indices) if (isCurrent(items.get(i))) return i
            return -1
        }

        fun move(from: Int, to: Int): Boolean {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false
            }
            val moved: RedPacketItem = items.removeAt(from)
            items.add(to, moved)
            changed = true
            notifyItemMoved(from, to)
            return true
        }

        fun persistIfChanged() {
            if (!changed) return
            changed = false
            persistEditedQueueOrder(items)
        }

        private fun isCurrent(item: RedPacketItem): Boolean {
            return if (actualMediaTitle.isBlank()) current != null && current!!.id == item.id
            else actualMediaTitle == item.title
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueHolder {
            val container: LinearLayout = LinearLayout(this@MainActivity)
            container.setOrientation(LinearLayout.VERTICAL)

            val row: LinearLayout = LinearLayout(this@MainActivity)
            row.setGravity(Gravity.CENTER_VERTICAL)
            row.setPadding(dp(8), dp(7), dp(4), dp(6))

            val state: ImageView = ImageView(this@MainActivity)
            row.addView(state, LinearLayout.LayoutParams(dp(28), dp(28)))

            val copy: LinearLayout = LinearLayout(this@MainActivity)
            copy.setOrientation(LinearLayout.VERTICAL)
            copy.setPadding(dp(10), 0, dp(8), 0)
            val title: TextView = text("", 15, INK, Typeface.NORMAL)
            title.setSingleLine(true)
            title.setEllipsize(TextUtils.TruncateAt.END)
            val meta: TextView = text("", 11, MUTED, Typeface.NORMAL)
            meta.setSingleLine(true)
            meta.setEllipsize(TextUtils.TruncateAt.END)
            copy.addView(
                title,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)),
            )
            copy.addView(
                meta,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(23)),
            )
            row.addView(copy, LinearLayout.LayoutParams(0, dp(54), 1f))

            val handle: ImageView = ImageView(this@MainActivity)
            handle.setImageDrawable(MediaIcon(MediaIcon.Kind.DRAG, MUTED, dp(24)))
            handle.setColorFilter(Color.rgb(150, 153, 160))
            handle.setPadding(dp(10), dp(10), dp(8), dp(10))
            val remove: ImageButton = mediaButton("移出播放列表", MediaIcon.Kind.REMOVE, { v -> }, false)
            remove.setImageDrawable(
                MediaIcon(MediaIcon.Kind.REMOVE, Color.rgb(196, 71, 64), dp(24))
            )
            row.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (managing) 77 else 73),
                ),
            )
            val divider: View = View(this@MainActivity)
            divider.setBackgroundColor(LINE)
            val dividerParams: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            dividerParams.setMargins(dp(46), 0, 0, 0)
            container.addView(divider, dividerParams)
            container.setLayoutParams(
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (managing) 78 else 74),
                )
            )
            return QueueHolder(container, row, state, title, meta, handle, remove)
        }

        override fun onBindViewHolder(holder: QueueHolder, position: Int) {
            val item: RedPacketItem = items.get(position)
            val selected: Boolean = isCurrent(item)
            holder.row.setBackgroundColor(if (selected) SOFT_ORANGE else Color.WHITE)
            holder.state.setImageDrawable(
                MediaIcon(
                    if (selected) MediaIcon.Kind.PLAY else MediaIcon.Kind.NEXT,
                    if (selected) ORANGE else MUTED,
                    dp(24),
                )
            )
            holder.state.setColorFilter(if (selected) ORANGE else Color.rgb(185, 188, 194))
            holder.state.setAlpha(if (selected) 1f else 0.32f)
            holder.state.setContentDescription(if (selected) "当前播放" else "队列内容")
            holder.title.setText(item.title)
            holder.title.setTextColor(if (selected) ORANGE else INK)
            holder.title.setTypeface(
                Typeface.DEFAULT,
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            )
            holder.meta.setText(
                ((if (item.course.isBlank()) "未识别课程" else item.course) +
                    (if (selected) "  ·  当前条目" else ""))
            )
            holder.meta.setTextColor(if (selected) ORANGE else MUTED)
            holder.handle.setVisibility(if (managing) View.VISIBLE else View.GONE)
            holder.remove.setVisibility(if (managing) View.VISIBLE else View.GONE)
            holder.remove.setContentDescription("移除 " + item.title)
            holder.remove.setOnClickListener({ v ->
                val index: Int = holder.getBindingAdapterPosition()
                if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                persistIfChanged()
                removeQueueItem(items.get(index))
                dialog.setContentView(
                    buildPlaybackQueueSheet(dialog, ArrayList<RedPacketItem>(displayedItems), true)
                )
            })
            holder.handle.setContentDescription("按住拖动 " + item.title)
            holder.handle.setOnTouchListener(
                if (managing)
                    { view, event ->
                        if (
                            event.getActionMasked() == MotionEvent.ACTION_DOWN && dragHelper != null
                        ) {
                            dragHelper!!.startDrag(holder)
                        }
                        false
                    }
                else null
            )
            holder.row.setOnClickListener(
                if (managing) null
                else
                    { v ->
                        val index: Int = holder.getBindingAdapterPosition()
                        if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                        QueueStore.save(this@MainActivity, displayedItems, index)
                        dialog.dismiss()
                        playTitle(items.get(index).title)
                    }
            )
        }

        inner class QueueHolder(
            itemView: View,
            val row: LinearLayout,
            val state: ImageView,
            val title: TextView,
            val meta: TextView,
            val handle: ImageView,
            val remove: ImageButton,
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun removedQueueKeys(): MutableSet<String> {
        return LinkedHashSet<String>(
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .getStringSet("removed_queue_keys", emptySet()) ?: emptySet()
        )
    }

    private fun removeQueueItem(removed: RedPacketItem) {
        var removedIndex: Int = -1
        for (i: Int in displayedItems.indices) {
            if (QueuePolicy.stableKey(removed) == QueuePolicy.stableKey(displayedItems.get(i))) {
                removedIndex = i
                break
            }
        }
        if (removedIndex < 0) return
        val controller: MediaController? = dedaoController()
        val state: PlaybackState? =
            if (controller == null) null else controller!!.getPlaybackState()
        val playing: Boolean = state != null && state!!.getState() == PlaybackState.STATE_PLAYING
        val liveTitle: String = liveOfficialTitle()
        var currentIndex: Int = displayedIndexForTitle(liveTitle)
        if (currentIndex < 0) {
            val current: RedPacketItem? = QueueStore.current(this)
            currentIndex = if (current == null) 0 else displayedIndexForTitle(current!!.title)
        }
        val nextIndex: Int =
            QueuePolicy.indexAfterRemoval(currentIndex, removedIndex, displayedItems.size)
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val active: Boolean = probe.getBoolean("companion_queue_active", false)
        val removesPlaying: Boolean = removed.title == liveTitle
        val removesPending: Boolean =
            removed.title == probe.getString("direct_open_target_title", "")
        if (removesPlaying || removesPending) {
            cancelPendingPlaybackSwitch()
            probe.edit().remove("direct_open_target_title").apply()
        }
        val removedKeys: MutableSet<String> = removedQueueKeys()
        removedKeys.add(QueuePolicy.stableKey(removed))
        getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("removed_queue_keys", removedKeys)
            .apply()
        applyFilters()
        QueueStore.save(this, displayedItems, Math.max(0, nextIndex))
        if (displayedItems.isEmpty()) {
            cancelPendingPlaybackSwitch()
            probe
                .edit()
                .remove("direct_open_target_title")
                .putBoolean("companion_queue_active", false)
                .putBoolean("companion_queue_suspended", false)
                .apply()
            if (removesPlaying) applyPlaybackState(false)
            PlaybackGuardService.stop(this)
        } else if (removesPlaying) {
            if (playing) {
                // Removing the last item must not unexpectedly replay the preceding item.
                if (removedIndex < displayedItems.size) {
                    playDirect(displayedItems.get(nextIndex), 1, active)
                } else {
                    suspendContinuousQueue()
                    applyPlaybackState(false)
                }
            } else {
                suspendContinuousQueue()
            }
        }
        updateNowPlaying()
        Toast.makeText(this, "已移出播放列表，可在列表底部恢复", Toast.LENGTH_SHORT).show()
    }

    private fun persistEditedQueueOrder(reorderedVisibleItems: List<RedPacketItem>?) {
        if (reorderedVisibleItems == null || reorderedVisibleItems!!.isEmpty()) return
        val baseOrder: List<RedPacketItem> =
            QueuePolicy.apply(allItems, "全部课程", false, sortOrder!!.getSelectedItemPosition() != 1)
        val globalOrder: ArrayList<String> = ArrayList<String>()
        if (sortOrder!!.getSelectedItemPosition() == 2) {
            globalOrder.addAll(loadCustomQueueOrder())
        }
        val available: LinkedHashSet<String> = LinkedHashSet<String>()
        for (item: RedPacketItem in baseOrder) available.add(QueuePolicy.stableKey(item))
        globalOrder.removeIf({ key -> !available.contains(key) })
        for (key: String in available) if (!globalOrder.contains(key)) globalOrder.add(key)

        val reorderedKeys: ArrayList<String> = ArrayList<String>()
        for (item: RedPacketItem in reorderedVisibleItems!!) {
            reorderedKeys.add(QueuePolicy.stableKey(item))
        }
        saveCustomQueueOrder(QueuePolicy.mergeVisibleOrder(globalOrder, reorderedKeys))
        getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
            .edit()
            .putInt("sort_order", 2)
            .apply()
        sortOrder!!.setSelection(2)
        applyFilters()
    }

    private fun loadCustomQueueOrder(): List<String> {
        val result: ArrayList<String> = ArrayList<String>()
        val raw: String? =
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .getString("custom_queue_order", "[]")
        try {
            val values: JSONArray = JSONArray(if (raw == null) "[]" else raw)
            for (i: Int in 0 until values.length()) {
                val key: String = values.optString(i, "")
                if (!key.isBlank() && !result.contains(key)) result.add(key)
            }
        } catch (ignored: Exception) {}

        return result
    }

    private fun saveCustomQueueOrder(order: List<String>) {
        val values: JSONArray = JSONArray()
        for (key: String in order) values.put(key)
        getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_queue_order", values.toString())
            .apply()
    }

    private fun renderEmpty(message: String) {
        list!!.removeAllViews()
        val empty: TextView = text(message, 15, MUTED, Typeface.NORMAL)
        empty.setGravity(Gravity.CENTER)
        empty.setPadding(dp(16), dp(54), dp(16), dp(54))
        list!!.addView(empty, matchWrap())
    }

    private fun renderPermissions() {
        permissionCard!!.removeAllViews()
        val accessibility: Boolean = isAccessibilityEnabled
        val listener: Boolean = isNotificationListenerEnabled
        val source: String =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE).getString("scan_source", "")
                ?: ""
        val needsFallback: Boolean =
            ("bridge_failed" == source || "accessibility_fallback" == source)
        val monitorConnected: Boolean = listener && DedaoSessionListener.isConnected
        val backgroundRestricted: Boolean = isBackgroundRestricted
        val notifications: android.app.NotificationManager? =
            getSystemService<android.app.NotificationManager>(
                android.app.NotificationManager::class.java
            )
        val notificationsDisabled: Boolean =
            notifications != null && !notifications!!.areNotificationsEnabled()
        if (listener && !monitorConnected && !backgroundRestricted && !notificationsDisabled) {
            // The authorization is still valid. Rebinding is an internal recovery detail and
            // should stay silent instead of looking like another permission request.
            permissionCard!!.setVisibility(View.GONE)
            return
        }
        if (
            monitorConnected &&
                (!needsFallback || accessibility) &&
                !backgroundRestricted &&
                !notificationsDisabled
        ) {
            permissionCard!!.setVisibility(View.GONE)
            return
        }
        permissionCard!!.setVisibility(View.VISIBLE)
        val permissionTitle: String =
            if (notificationsDisabled && listener) "显示连续播放通知"
            else if (backgroundRestricted) "允许后台连续播放"
            else
                (if (!listener) "开启连续播放权限"
                else (if (!monitorConnected) "正在恢复连续播放监控" else "可选：开启兼容模式"))
        permissionCard!!.addView(text(permissionTitle, 14, INK, Typeface.BOLD), matchWrap())
        if (!listener) {
            val copy: TextView = text("开启播放监控后，播完会自动衔接下一条；通常只需授权一次。", 12, MUTED, Typeface.NORMAL)
            copy.setPadding(0, dp(4), 0, dp(8))
            permissionCard!!.addView(copy, matchWrap())
            permissionCard!!.addView(
                compactButton(
                    "开启连续播放",
                    { v ->
                        startActivity(
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        )
                    },
                    false,
                ),
                matchWrap(),
            )
        } else if (notificationsDisabled) {
            val copy: TextView =
                text("允许显示连续播放的常驻状态。通知权限与“播放监控”是两个独立授权；拒绝后仍可操作播放。", 12, MUTED, Typeface.NORMAL)
            copy.setPadding(0, dp(4), 0, dp(8))
            permissionCard!!.addView(copy, matchWrap())
            permissionCard!!.addView(
                compactButton("允许播放通知", { v -> requestPlaybackNotifications() }, false),
                matchWrap(),
            )
        } else if (backgroundRestricted) {
            val copy: TextView =
                text(
                    "系统把伴侣设成了“受限”，锁屏清理会停止连续播放。请在应用信息的省电策略中选择“不限制”，并在最近任务里锁定伴侣；只需设置一次。",
                    12,
                    MUTED,
                    Typeface.NORMAL,
                )
            copy.setPadding(0, dp(4), 0, dp(8))
            permissionCard!!.addView(copy, matchWrap())
            permissionCard!!.addView(
                compactButton("允许后台运行", { v -> requestBackgroundPlaybackAccess() }, false),
                matchWrap(),
            )
        } else if (needsFallback && !accessibility) {
            val message: String =
                if (DiagnosticReport.isHuaweiFamily)
                    "这是刷新列表的可选兼容模式，不是连续播放授权。鸿蒙/华为若无法开启，可在应用信息中允许受限设置。"
                else "这是刷新列表和切换失败时的可选备用模式，不是连续播放授权；不开启也不会撤销已有的连续播放权限。"
            val copy: TextView = text(message, 12, MUTED, Typeface.NORMAL)
            copy.setPadding(0, dp(4), 0, dp(8))
            permissionCard!!.addView(copy, matchWrap())
            permissionCard!!.addView(
                compactButton("打开应用信息", { v -> openApplicationDetails() }, false),
                matchWrap(),
            )
            permissionCard!!.addView(
                compactButton(
                    "开启兼容模式",
                    { v -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    false,
                ),
                matchWrap(),
            )
        }
    }

    private fun openApplicationDetails() {
        val intent: Intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null),
            )
        startActivity(intent)
    }

    private fun showPlaybackSettings() {
        val manufacturer: String = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US)
        val xiaomi: Boolean =
            manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco")
        val guidance: String =
            if (xiaomi)
                "小米 / HyperOS 需要分别检查：\n\n1. 应用信息 → 自启动：开启\n2. 耗电管理 → 省电策略：无限制\n3. 允许显示连续播放通知\n\n仅设置“无限制”仍可能被系统冻结。建议在最近任务中锁定伴侣，避免一键清理。允许后台运行可能增加耗电。"
            else "请允许伴侣后台运行，并显示连续播放通知。若息屏后停止，请在应用信息中检查省电策略和系统的后台启动设置。允许后台运行可能增加耗电。"
        android.app.AlertDialog.Builder(this)
            .setTitle("连续播放设置")
            .setMessage(
                guidance +
                    "\n\n刷新通过得到确认页自动返回。若当前得到版本不支持回跳，可点击“红包已刷新”通知或手动打开伴侣，结果已保存，不必反复刷新。\n\n播放监控权限与显示通知权限相互独立；设置后仍建议息屏试听一次。"
            )
            .setPositiveButton("应用设置", { dialog, which -> openApplicationDetails() })
            .setNeutralButton("复制诊断", { dialog, which -> copyDiagnostics() })
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun requestPlaybackNotifications() {
        val preferences: android.content.SharedPreferences =
            getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
        if (
            (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                ((!preferences.getBoolean("notification_permission_requested", false) ||
                    shouldShowRequestPermissionRationale(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ))))
        ) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            requestPermissions(arrayOf<String>(android.Manifest.permission.POST_NOTIFICATIONS), 33)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
            )
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 33) renderPermissions()
    }

    private fun requestBackgroundPlaybackAccess() {
        val power: PowerManager? = getSystemService<PowerManager>(PowerManager::class.java)
        if (power != null && !power!!.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                val intent: Intent =
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()),
                    )
                startActivity(intent)
                return
            } catch (ignored: Exception) {}
        }
        openApplicationDetails()
    }

    private fun copyDiagnostics() {
        val clipboard: ClipboardManager? =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard!!.setPrimaryClip(
            ClipData.newPlainText("知识红包伴侣诊断报告", DiagnosticReport.build(this))
        )
        Toast.makeText(this, "诊断信息已复制，可直接粘贴到群里", Toast.LENGTH_LONG).show()
    }

    private fun recoverStalledScanIfNeeded(force: Boolean): Boolean {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        if (!probe.getBoolean("scan_requested", false)) return false
        val startedAt: Long = probe.getLong("scan_started_at", 0L)
        val elapsed: Long =
            if (startedAt <= 0L) java.lang.Long.MAX_VALUE
            else System.currentTimeMillis() - startedAt
        if (!force && elapsed < BRIDGE_SCAN_TIMEOUT_MS) return false
        val stage: String? = probe.getString("bridge_stage", "unknown")
        failPendingScan("同步已中断，最后阶段：" + (if (stage == null) "unknown" else stage))
        return true
    }

    private fun failPendingScan(status: String) {
        val probe: android.content.SharedPreferences =
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        val stage: String = probe.getString("bridge_stage", "unknown") ?: "unknown"
        probe
            .edit()
            .putBoolean("scan_requested", false)
            .remove("scan_return_to_app")
            .putString("scan_source", "bridge_failed")
            .putString("scan_status", status)
            .putLong("scan_finished_at", System.currentTimeMillis())
            .commit()
        BridgeKeepAliveService.stop(this)
        DiagnosticReport.mark(this, "scan_recovered", "last=" + stage)
    }

    private fun card(): LinearLayout {
        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.HORIZONTAL)
        card.setGravity(Gravity.CENTER_VERTICAL)
        card.setPadding(dp(15), dp(13), dp(15), dp(13))
        card.setBackground(roundRect(Color.WHITE, 16))
        card.setElevation(dp(1).toFloat())
        return card
    }

    private fun compactButton(
        label: String,
        click: View.OnClickListener,
        primary: Boolean,
    ): Button {
        val button: Button = Button(this)
        button.setText(label)
        button.setAllCaps(false)
        button.setTextSize(14f)
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        button.setTextColor(if (primary) Color.WHITE else ORANGE)
        button.setBackgroundTintList(
            ColorStateList.valueOf(if (primary) ORANGE else Color.rgb(255, 238, 230))
        )
        button.setMinWidth(0)
        button.setMinHeight(0)
        button.setPadding(dp(8), 0, dp(8), 0)
        button.setElevation(0f)
        button.setStateListAnimator(null)
        button.setOnClickListener(click)
        return button
    }

    private fun mediaButton(
        label: String,
        icon: MediaIcon.Kind,
        click: View.OnClickListener,
        primary: Boolean,
    ): ImageButton {
        val button: ImageButton = ImageButton(this)
        button.setContentDescription(label)
        button.setTooltipText(label)
        button.setImageDrawable(
            MediaIcon(icon, if (primary) Color.WHITE else INK, dp(if (primary) 28 else 24))
        )
        button.setScaleType(ImageView.ScaleType.CENTER)
        button.setPadding(0, 0, 0, 0)
        button.setBackground(
            RippleDrawable(
                ColorStateList.valueOf(if (primary) 0x33ffffff else 0x11000000),
                roundRect(if (primary) ORANGE else Color.TRANSPARENT, 40),
                roundRect(Color.WHITE, 40),
            )
        )
        button.setElevation(0f)
        button.setStateListAnimator(null)
        button.setOnClickListener(click)
        return button
    }

    private fun text(value: String, size: Int, color: Int, style: Int): TextView {
        val view: TextView = TextView(this)
        view.setText(value)
        view.setTextSize(size.toFloat())
        view.setTextColor(color)
        view.setTypeface(Typeface.DEFAULT, style)
        return view
    }

    private fun spinner(values: Array<String>): Spinner {
        val spinner: Spinner = Spinner(this)
        spinner.setAdapter(adapter(values.toList()))
        spinner.setPadding(dp(8), 0, dp(8), 0)
        spinner.setBackground(roundRect(Color.WHITE, 12, LINE))
        return spinner
    }

    private fun adapter(values: List<String>): ArrayAdapter<String> {
        val adapter: ArrayAdapter<String> =
            ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun roundRect(fill: Int, radiusDp: Int): GradientDrawable {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(fill)
        drawable.setCornerRadius(dp(radiusDp).toFloat())
        return drawable
    }

    private fun roundTopRect(fill: Int, radiusDp: Int): GradientDrawable {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(fill)
        val radius: Float = dp(radiusDp).toFloat()
        drawable.setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
        return drawable
    }

    private fun roundRect(fill: Int, radiusDp: Int, stroke: Int): GradientDrawable {
        val drawable: GradientDrawable = roundRect(fill, radiusDp)
        drawable.setStroke(dp(1), stroke)
        return drawable
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun cardParams(): LinearLayout.LayoutParams {
        val params: LinearLayout.LayoutParams = matchWrap()
        params.setMargins(0, 0, 0, dp(10))
        return params
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    protected override fun onResume() {
        super.onResume()
        resumed = true
        BridgeReturn.onResumed(this)
        if (isNotificationListenerEnabled) {
            DedaoSessionListener.ensureConnected(this)
            handler.postDelayed(
                {
                    if (
                        (resumed &&
                            isNotificationListenerEnabled &&
                            !DedaoSessionListener.isConnected)
                    ) {
                        DedaoSessionListener.repairBinding(this)
                    }
                },
                1_500L,
            )
            handler.postDelayed(Runnable({ this.renderPermissions() }), 800L)
        }
        handler.removeCallbacks(mediaPoll)
        handler.post(mediaPoll)
        refreshCachedResults()
        if (
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getBoolean("scan_requested", false)
        ) {
            if (recoverStalledScanIfNeeded(false)) {
                refreshCachedResults()
                return
            }
            handler.removeCallbacks(scanPoll)
            handler.post(scanPoll)
            return
        }
        if (!externalAction && !autoSyncStarted) {
            val finishedAt: Long =
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .getLong("scan_finished_at", 0L)
            if (
                finishedAt == 0L || System.currentTimeMillis() - finishedAt > AUTO_REFRESH_AFTER_MS
            ) {
                handler.postDelayed(Runnable({ this.requestPageScan() }), 350)
            }
            autoSyncStarted = true
        }
    }

    protected override fun onPause() {
        resumed = false
        handler.removeCallbacks(mediaPoll)
        super.onPause()
    }

    protected override fun onDestroy() {
        if (visibleActivity.get() == this) visibleActivity.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        fun onExternalPlaybackSelected() {
            val activity = visibleActivity.get() ?: return
            ++activity.toggleGeneration
            ++activity.monitorWaitGeneration
            activity.pendingContinuousStart = false
            activity.cancelPendingPlaybackSwitch()
        }

        private var visibleActivity: WeakReference<MainActivity> = WeakReference<MainActivity>(null)
        private val ORANGE: Int = Color.rgb(255, 106, 42)
        private val INK: Int = Color.rgb(29, 31, 36)
        private val MUTED: Int = Color.rgb(112, 116, 126)
        private val PAGE: Int = Color.rgb(250, 250, 250)
        private val SOFT_ORANGE: Int = Color.rgb(255, 247, 242)
        private val LINE: Int = Color.rgb(232, 233, 236)
        private val AUTO_REFRESH_AFTER_MS: Long = 10 * 60 * 1_000L
        private val BRIDGE_SCAN_TIMEOUT_MS: Long = 40_000L

        fun captureCurrentScreen(): Bitmap? {
            val activity: MainActivity? = visibleActivity.get()
            if (activity == null || activity!!.isFinishing() || activity!!.isDestroyed())
                return null
            val decor: View = activity!!.getWindow().getDecorView()
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return null
            try {
                val full: Bitmap =
                    Bitmap.createBitmap(
                        decor.getWidth(),
                        decor.getHeight(),
                        Bitmap.Config.ARGB_8888,
                    )
                decor.draw(Canvas(full))
                val statusBarId: Int =
                    activity!!.getResources().getIdentifier("status_bar_height", "dimen", "android")
                val navigationBarId: Int =
                    activity!!
                        .getResources()
                        .getIdentifier("navigation_bar_height", "dimen", "android")
                val top: Int =
                    if (statusBarId == 0) 0
                    else activity!!.getResources().getDimensionPixelSize(statusBarId)
                val bottom: Int =
                    if (navigationBarId == 0) 0
                    else activity!!.getResources().getDimensionPixelSize(navigationBarId)
                val contentHeight: Int = full.getHeight() - top - bottom
                if (top > 0 && contentHeight > 0) {
                    val content: Bitmap =
                        Bitmap.createBitmap(full, 0, top, full.getWidth(), contentHeight)
                    full.recycle()
                    return content
                }
                return full
            } catch (ignored: Exception) {
                return null
            }
        }
    }
}
