package com.dingaimin.dedaocompanion;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static WeakReference<MainActivity> visibleActivity = new WeakReference<>(null);
    private static final int ORANGE = Color.rgb(255, 106, 42);
    private static final int INK = Color.rgb(29, 31, 36);
    private static final int MUTED = Color.rgb(112, 116, 126);
    private static final int PAGE = Color.rgb(250, 250, 250);
    private static final int SOFT_ORANGE = Color.rgb(255, 247, 242);
    private static final int LINE = Color.rgb(232, 233, 236);
    private static final long AUTO_REFRESH_AFTER_MS = 10 * 60 * 1_000L;
    private static final long BRIDGE_SCAN_TIMEOUT_MS = 40_000L;

    private final ArrayList<RedPacketItem> allItems = new ArrayList<>();
    private final ArrayList<RedPacketItem> displayedItems = new ArrayList<>();
    private final ArrayList<View> episodeRows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView syncStatus;
    private LinearLayout syncBanner;
    private LinearLayout permissionCard;
    private LinearLayout nowPlayingCard;
    private LinearLayout list;
    private Spinner sortOrder;
    private Spinner courseFilter;
    private CheckBox hideCompletedFilter;
    private Button primaryAction;
    private Button refreshAction;
    private Button diagnosticAction;
    private Button playPauseAction;
    private Button previousAction;
    private Button nextAction;
    private TextView nowPlayingTitle;
    private TextView nowPlayingProgress;
    private TextView nowPlayingState;
    private TextView nowPlayingCourse;
    private ProgressBar playbackBar;
    private boolean autoSyncStarted;
    private boolean externalAction;
    private boolean resumed;
    private String actualMediaTitle = "";
    private String switchingToTitle = "";
    private long switchStartedAt;
    private long lastToggleAt;
    private boolean optimisticPlaying;
    private boolean pendingContinuousStart;
    private int toggleGeneration;
    private int directSkipGeneration;
    private int directOpenForegroundGeneration;
    private int monitorWaitGeneration;

    private final Runnable queueSync = () -> {
        if (displayedItems.isEmpty() || allDisplayedItemsHaveAudioIds() || !isAccessibilityEnabled()) return;
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        if (probe.getBoolean("scan_requested", false)
                || probe.getBoolean("official_sync_active", false)) return;
        String desired = displayedFingerprint();
        if (desired.equals(probe.getString("official_queue_fingerprint", ""))) return;
        if (desired.equals(probe.getString("official_queue_failed_fingerprint", ""))) return;
        DedaoAccessibilityService.syncOfficialQueue(this, new ArrayList<>(displayedItems));
    };

    private final Runnable scanPoll = new Runnable() {
        @Override public void run() {
            android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
            if (probe.getBoolean("scan_requested", false)) {
                if (recoverStalledScanIfNeeded(false)) {
                    refreshCachedResults();
                    return;
                }
                String progress = probe.getString("scan_status", "正在同步有效红包…");
                syncStatus.setText(progress == null ? "正在同步有效红包…" : progress);
                handler.postDelayed(this, 600);
            } else {
                refreshCachedResults();
            }
        }
    };

    private final Runnable mediaPoll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateNowPlaying();
            handler.postDelayed(this, 1_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        visibleActivity = new WeakReference<>(this);
        setContentView(buildUi());
        refreshCachedResults();
        handleIntent(getIntent());
    }

    private void handleIntent(Intent intent) {
        String action = intent == null ? null : intent.getStringExtra("action");
        Uri data = intent == null ? null : intent.getData();
        boolean bridgeReturn = data != null && "dedaocompanion".equals(data.getScheme())
                && "bridge".equals(data.getHost());
        externalAction = action != null || bridgeReturn;
        String externalActionName = action;
        if (bridgeReturn) externalActionName = "bridge:" + data.getLastPathSegment();
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("last_external_action", externalActionName == null ? "" : externalActionName)
                .putLong("last_external_action_at", System.currentTimeMillis())
                .apply();
        if (bridgeReturn) {
            BridgeKeepAliveService.stop(this);
            overridePendingTransition(0, 0);
            String result = data.getLastPathSegment();
            android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
            if (probe.getBoolean("scan_requested", false)) {
                failPendingScan("确认页已返回，但没有收到完整结果");
            }
            DiagnosticReport.mark(this, "companion_returned", result == null ? "unknown" : result);
            refreshCachedResults();
            if ("failed".equals(result) && isAccessibilityEnabled()) {
                syncStatus.setText("接口确认未完成，正在使用兼容模式…");
                handler.postDelayed(this::requestLegacyPageScan, 280L);
            }
        }
        else if ("scan".equals(action)) requestPageScan();
        else if ("play".equals(action)) playTitle(intent.getStringExtra("title"));
        else if ("continuous".equals(action)) {
            handler.postDelayed(this::startContinuousPlayback, 180);
        }
        else if ("test_click".equals(action)) {
            String control = intent.getStringExtra("control");
            handler.postDelayed(() -> performControlTest(control), 180);
        }
        else if ("scan_complete".equals(action) || "now_playing".equals(action)) {
            refreshCachedResults();
            markCompanionVisible();
            DedaoAccessibilityService.dismissAutomationCoverAfter(180);
        }
    }

    private void markCompanionVisible() {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        long startedAt = probe.getLong("play_timing_started_elapsed", 0L);
        if (startedAt <= 0L) return;
        probe.edit()
                .putLong("play_timing_companion_visible", SystemClock.elapsedRealtime() - startedAt)
                .putString("play_timing_last_stage", "companion_visible")
                .apply();
    }

    private void performControlTest(String control) {
        if (control == null) return;
        boolean submitted = true;
        switch (control) {
            case "refresh": refreshAction.performClick(); break;
            case "previous": previousAction.performClick(); break;
            case "play_pause": playPauseAction.performClick(); break;
            case "next": nextAction.performClick(); break;
            case "continuous": primaryAction.performClick(); break;
            case "first_row":
                submitted = !episodeRows.isEmpty() && episodeRows.get(0).performClick();
                break;
            case "sort_oldest": sortOrder.setSelection(1); break;
            case "sort_newest": sortOrder.setSelection(0); break;
            case "course_first":
                submitted = courseFilter.getCount() > 1;
                if (submitted) courseFilter.setSelection(1);
                break;
            case "course_all": courseFilter.setSelection(0); break;
            case "hide_completed_on":
                submitted = hideCompletedFilter != null;
                if (submitted) hideCompletedFilter.setChecked(true);
                break;
            case "hide_completed_off":
                submitted = hideCompletedFilter != null;
                if (submitted) hideCompletedFilter.setChecked(false);
                break;
            case "rapid_toggle":
                playPauseAction.performClick();
                handler.postDelayed(playPauseAction::performClick, 120);
                handler.postDelayed(playPauseAction::performClick, 240);
                break;
            case "direct_play":
                submitted = OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY);
                break;
            case "direct_pause":
                submitted = OfficialPlayerControl.send(this, OfficialPlayerControl.PAUSE);
                break;
            case "direct_play_delayed":
                handler.postDelayed(
                        () -> OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY),
                        1_500L);
                break;
            case "direct_pause_delayed":
                handler.postDelayed(
                        () -> OfficialPlayerControl.send(this, OfficialPlayerControl.PAUSE),
                        1_500L);
                break;
            case "seek_near_end":
                MediaController controller = dedaoController();
                MediaMetadata metadata = controller == null ? null : controller.getMetadata();
                long duration = metadata == null ? 0L
                        : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                submitted = controller != null && duration > 8_000L;
                if (submitted) {
                    controller.getTransportControls().seekTo(duration - 7_000L);
                    controller.getTransportControls().play();
                }
                break;
            case "direct_skip_0":
                submitted = OfficialPlayerControl.skip(this, 0);
                break;
            case "direct_skip_1":
                submitted = OfficialPlayerControl.skip(this, 1);
                break;
            case "queue_probe":
                submitted = DedaoAccessibilityService.probeOfficialQueue(this);
                break;
            case "queue_reorder":
                submitted = DedaoAccessibilityService.reorderOfficialQueueForProbe(this);
                break;
            default: submitted = false;
        }
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("ui_test_last", control)
                .putBoolean("ui_test_submitted", submitted)
                .putLong("ui_test_at", System.currentTimeMillis())
                .apply();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);
        root.setFitsSystemWindows(true);
        root.setPadding(0, 0, 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(3), dp(8), dp(3));
        header.setBackgroundColor(Color.WHITE);
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.dingaimin.dedaocompanion.R.drawable.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView title = text("知识红包伴侣", 19, INK, Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        refreshAction = compactButton("刷新", v -> requestPageScan(), false);
        refreshAction.setTextColor(MUTED);
        refreshAction.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        header.addView(refreshAction, new LinearLayout.LayoutParams(dp(58), dp(40)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        permissionCard = card();
        permissionCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams permissionParams = cardParams();
        permissionParams.setMargins(dp(18), 0, dp(18), dp(8));
        root.addView(permissionCard, permissionParams);

        syncBanner = new LinearLayout(this);
        syncBanner.setGravity(Gravity.CENTER_VERTICAL);
        syncBanner.setPadding(dp(12), 0, dp(12), 0);
        syncBanner.setBackground(roundRect(SOFT_ORANGE, 10));
        syncStatus = text("正在同步有效内容…", 12, MUTED, Typeface.NORMAL);
        syncBanner.addView(syncStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        diagnosticAction = compactButton("复制诊断", v -> copyDiagnostics(), false);
        diagnosticAction.setTextColor(ORANGE);
        diagnosticAction.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        diagnosticAction.setVisibility(View.GONE);
        syncBanner.addView(diagnosticAction, new LinearLayout.LayoutParams(dp(82), dp(34)));
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        syncParams.setMargins(dp(14), dp(6), dp(14), dp(6));
        root.addView(syncBanner, syncParams);
        syncBanner.setVisibility(View.GONE);

        nowPlayingCard = buildCompactPlayer();
        LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(184));
        playerParams.setMargins(dp(14), dp(6), dp(14), dp(6));
        root.addView(nowPlayingCard, playerParams);

        LinearLayout pickers = new LinearLayout(this);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(Gravity.CENTER_VERTICAL);
        pickers.setPadding(dp(14), 0, dp(14), dp(6));
        sortOrder = spinner(new String[]{"最新领取优先", "最早领取优先"});
        courseFilter = spinner(new String[]{"全部课程"});
        sortOrder.setSelection(getSharedPreferences("ui_preferences", MODE_PRIVATE)
                .getInt("sort_order", 0));
        TextView sortLabel = text("排序", 13, MUTED, Typeface.NORMAL);
        sortLabel.setGravity(Gravity.CENTER_VERTICAL);
        pickers.addView(sortLabel, new LinearLayout.LayoutParams(dp(38), dp(42)));
        pickers.addView(sortOrder, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams secondPicker = new LinearLayout.LayoutParams(0, dp(42), 1f);
        secondPicker.setMargins(dp(8), 0, 0, 0);
        pickers.addView(courseFilter, secondPicker);
        root.addView(pickers, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        android.widget.AdapterView.OnItemSelectedListener listener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                android.content.SharedPreferences.Editor edit =
                        getSharedPreferences("ui_preferences", MODE_PRIVATE).edit();
                if (parent == sortOrder) edit.putInt("sort_order", position);
                if (parent == courseFilter && courseFilter.getSelectedItem() != null) {
                    edit.putString("course_filter", String.valueOf(courseFilter.getSelectedItem()));
                }
                edit.apply();
                applyFilters();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        sortOrder.setOnItemSelectedListener(listener);
        courseFilter.setOnItemSelectedListener(listener);

        hideCompletedFilter = new CheckBox(this);
        hideCompletedFilter.setText("隐藏已学完");
        hideCompletedFilter.setTextSize(13);
        hideCompletedFilter.setTextColor(INK);
        hideCompletedFilter.setButtonTintList(ColorStateList.valueOf(ORANGE));
        hideCompletedFilter.setGravity(Gravity.CENTER_VERTICAL);
        hideCompletedFilter.setPadding(dp(10), 0, dp(14), 0);
        hideCompletedFilter.setChecked(getSharedPreferences("ui_preferences", MODE_PRIVATE)
                .getBoolean("hide_completed", false));
        hideCompletedFilter.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                    .putBoolean("hide_completed", checked)
                    .apply();
            applyFilters();
        });
        LinearLayout completedRow = new LinearLayout(this);
        completedRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        completedRow.addView(hideCompletedFilter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        root.addView(completedRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(14));
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        primaryAction = compactButton("连续播放  ·  2 条", v -> startContinuousPlayback(), true);
        primaryAction.setTextSize(14);
        primaryAction.setBackground(roundRect(ORANGE, 13));
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        primaryParams.setMargins(dp(14), dp(6), dp(14), dp(8));
        root.addView(primaryAction, primaryParams);
        return root;
    }

    private LinearLayout buildCompactPlayer() {
        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setPadding(dp(14), dp(10), dp(14), dp(8));
        player.setBackground(roundRect(Color.WHITE, 14));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        nowPlayingCourse = text("知识红包队列", 12, MUTED, Typeface.NORMAL);
        nowPlayingState = text("等待播放", 12, ORANGE, Typeface.BOLD);
        nowPlayingState.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        heading.addView(nowPlayingCourse, new LinearLayout.LayoutParams(0, dp(24), 1f));
        heading.addView(nowPlayingState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
        player.addView(heading, matchWrap());

        nowPlayingTitle = text("选择一条有效内容开始播放", 17, INK, Typeface.BOLD);
        nowPlayingTitle.setMaxLines(2);
        nowPlayingTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowPlayingTitle.setLineSpacing(0, 1.04f);
        player.addView(nowPlayingTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView progressLabel = text("播放进度", 11, MUTED, Typeface.NORMAL);
        nowPlayingProgress = text("00:00 / 00:00", 12, MUTED, Typeface.NORMAL);
        info.addView(progressLabel, new LinearLayout.LayoutParams(0, dp(22), 1f));
        info.addView(nowPlayingProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)));
        player.addView(info, matchWrap());

        playbackBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        playbackBar.setMax(1000);
        playbackBar.setProgressTintList(ColorStateList.valueOf(ORANGE));
        playbackBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(229, 229, 229)));
        player.addView(playbackBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(6), 0, 0);
        previousAction = mediaButton("上一个", android.R.drawable.ic_media_previous,
                v -> playPrevious(), false);
        controls.addView(previousAction, new LinearLayout.LayoutParams(0, dp(52), 1f));
        playPauseAction = mediaButton("播放", android.R.drawable.ic_media_play,
                v -> togglePlayback(), true);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(88), dp(52));
        playParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(playPauseAction, playParams);
        nextAction = mediaButton("下一个", android.R.drawable.ic_media_next,
                v -> playNext(), false);
        controls.addView(nextAction, new LinearLayout.LayoutParams(0, dp(52), 1f));
        player.addView(controls, matchWrap());
        return player;
    }

    private void requestPageScan() {
        autoSyncStarted = true;
        long now = System.currentTimeMillis();
        String attemptId = UUID.randomUUID().toString().substring(0, 8);
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("scan_requested", true)
                .putBoolean("scan_return_to_app", true)
                .putLong("scan_started_at", now)
                .putString("scan_attempt_id", attemptId)
                .remove("official_queue_failed_fingerprint")
                .putString("scan_source", "bridge_pending")
                .putString("scan_status", "正在请得到确认红包权益…")
                .commit();
        DiagnosticReport.mark(this, "scan_requested", "attempt=" + attemptId);
        syncStatus.setText("正在请得到确认红包权益…");
        syncBanner.setVisibility(View.VISIBLE);
        refreshAction.setEnabled(false);
        handler.removeCallbacks(scanPoll);
        handler.post(scanPoll);
        try {
            String bridgeUrl = DedaoBridgeServer.start(this);
            String route = "igetapp://activity/detail?url="
                    + URLEncoder.encode(bridgeUrl, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(route));
            intent.setPackage("com.luojilab.player");
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(0, 0);
        } catch (Exception error) {
            DiagnosticReport.mark(this, "open_dedao_failed", error.getClass().getSimpleName());
            if (isAccessibilityEnabled()) requestLegacyPageScan();
            else {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putBoolean("scan_requested", false)
                        .putString("scan_source", "bridge_failed")
                        .putString("scan_status", "无法打开得到，请确认已安装最新版得到")
                        .apply();
                refreshCachedResults();
            }
        }
    }

    private void requestLegacyPageScan() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "当前得到版本需要开启兼容模式", Toast.LENGTH_LONG).show();
            renderPermissions();
            return;
        }
        long now = System.currentTimeMillis();
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("scan_requested", true)
                .putBoolean("scan_return_to_app", true)
                .putLong("scan_started_at", now)
                .putString("scan_attempt_id", "legacy-" + Long.toHexString(now))
                .putString("scan_source", "accessibility_fallback")
                .putString("scan_status", "兼容模式正在识别有效红包…")
                .apply();
        DiagnosticReport.mark(this, "accessibility_fallback", "正在打开得到红包列表");
        DedaoAccessibilityService.requestScanNow();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://redPacket/list"));
            intent.setPackage("com.luojilab.player");
            startActivity(intent);
        } catch (Exception error) {
            syncStatus.setText("没有找到得到 App");
        }
    }

    private void playTitle(String title) {
        playTitle(title, 0);
    }

    private void playTitle(String title, int sequenceDirection) {
        if (title == null || title.isBlank()) return;
        RedPacketItem item = null;
        for (RedPacketItem candidate : displayedItems) {
            if (title.equals(candidate.title)) {
                item = candidate;
                break;
            }
        }
        if (item != null && item.audioId != null && !item.audioId.isBlank()) {
            playDirect(item, sequenceDirection, false);
            return;
        }
        playTitleLegacy(title, sequenceDirection);
    }

    private void playDirect(RedPacketItem item, int sequenceDirection, boolean continuousStart) {
        if (item == null || item.audioId == null || item.audioId.isBlank()) return;
        switchingToTitle = item.title;
        switchStartedAt = SystemClock.elapsedRealtime();
        nowPlayingState.setText(continuousStart ? "正在开始连续播放…" : "正在切换…");
        setTransportEnabled(false);
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        probe.edit()
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .putString("direct_open_target_title", item.title)
                .commit();
        if (!OfficialPlayerControl.open(this, item.audioId)) {
            probe.edit().remove("direct_open_target_title").apply();
            playTitleLegacy(item.title, sequenceDirection);
            return;
        }
        int generation = ++directSkipGeneration;
        probe.edit()
                .putBoolean("companion_queue_active", continuousStart)
                .putString("direct_open_status", "已提交：" + item.title)
                .putLong("direct_open_at", System.currentTimeMillis())
                .apply();
        handler.postDelayed(this::updateNowPlaying, 160L);
        handler.postDelayed(() -> verifyDirectOpen(
                item, sequenceDirection, continuousStart, generation, 0), 650L);
    }

    private void verifyDirectOpen(RedPacketItem item, int sequenceDirection,
                                  boolean continuousStart, int generation, int attempt) {
        if (generation != directSkipGeneration) return;
        if (item.title.equals(liveOfficialTitle())) {
            switchingToTitle = "";
            setTransportEnabled(true);
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("companion_queue_active", continuousStart
                            || getSharedPreferences("page_probe", MODE_PRIVATE)
                            .getBoolean("companion_queue_active", false))
                    .putBoolean("direct_open_unreliable", false)
                    .putString("direct_open_status", "已确认：" + item.title)
                    .putString("playback_monitor_status", "正在播放：" + item.title)
                    .remove("direct_open_target_title")
                    .apply();
            resumeOfficialPlayback();
            returnToCompanionAfterOfficialFallback(generation);
            if (getSharedPreferences("page_probe", MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false)) {
                PlaybackGuardService.start(this);
            }
            updateNowPlaying();
            return;
        }
        if (attempt < 8) {
            if (attempt == 1 || attempt == 4 || attempt == 6) {
                OfficialPlayerControl.open(this, item.audioId);
            }
            if (attempt == 2) openOfficialContextFor(item, generation);
            handler.postDelayed(() -> verifyDirectOpen(
                    item, sequenceDirection, continuousStart, generation, attempt + 1), 700L);
            return;
        }
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("companion_queue_active", false)
                .putBoolean("direct_open_unreliable", true)
                .putString("direct_open_status", "当前得到版本未确认直接点播")
                .remove("direct_open_target_title")
                .apply();
        QueueStore.selectTitle(this, liveOfficialTitle());
        returnToCompanionAfterOfficialFallback(generation);
        if (isAccessibilityEnabled()) {
            playTitleLegacy(item.title, sequenceDirection);
            if (continuousStart) PlaybackGuardService.start(this);
        } else {
            PlaybackGuardService.stop(this);
            switchingToTitle = "";
            setTransportEnabled(true);
            nowPlayingState.setText("点播未确认");
            Toast.makeText(this, "当前得到版本需要开启兼容模式", Toast.LENGTH_LONG).show();
            renderPermissions();
        }
    }

    private void openOfficialContextFor(RedPacketItem item, int generation) {
        if (item == null || item.deepLink == null || item.deepLink.isBlank()) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.deepLink));
            intent.setPackage("com.luojilab.player");
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            directOpenForegroundGeneration = generation;
            startActivity(intent);
            overridePendingTransition(0, 0);
            handler.postDelayed(() -> {
                if (generation == directSkipGeneration) {
                    OfficialPlayerControl.open(this, item.audioId);
                }
            }, 350L);
        } catch (Exception ignored) {
            directOpenForegroundGeneration = 0;
        }
    }

    private void returnToCompanionAfterOfficialFallback(int generation) {
        if (directOpenForegroundGeneration != generation) return;
        directOpenForegroundGeneration = 0;
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(0, 0);
        } catch (Exception ignored) {
        }
    }

    private void playTitleLegacy(String title, int sequenceDirection) {
        if (!title.equals(actualMediaTitle)) {
            switchingToTitle = title;
            switchStartedAt = SystemClock.elapsedRealtime();
            nowPlayingState.setText("正在切换…");
            setTransportEnabled(false);
        }
        int targetIndex = displayedIndexForTitle(title);
        android.content.SharedPreferences probe =
                getSharedPreferences("page_probe", MODE_PRIVATE);
        String officialFingerprint = probe.getString("official_queue_fingerprint", "");
        if (targetIndex >= 0
                && displayedFingerprint().equals(officialFingerprint)
                && !probe.getBoolean("direct_skip_unreliable", false)
                && OfficialPlayerControl.skip(this, targetIndex)) {
            int generation = ++directSkipGeneration;
            probe.edit()
                    .putBoolean("companion_queue_active", true)
                    .putString("direct_skip_status", "已提交：" + targetIndex + " · " + title)
                    .putLong("direct_skip_at", System.currentTimeMillis())
                    .apply();
            handler.postDelayed(this::updateNowPlaying, 180L);
            handler.postDelayed(this::updateNowPlaying, 650L);
            handler.postDelayed(
                    () -> verifyDirectSkip(title, sequenceDirection, generation, 0),
                    2_000L);
            return;
        }
        ++directSkipGeneration;
        DedaoAccessibilityService.returnToListAndPlay(this, title, sequenceDirection);
    }

    private void verifyDirectSkip(String title, int sequenceDirection,
                                  int generation, int attempt) {
        if (generation != directSkipGeneration) return;
        String officialTitle = currentOfficialTitle();
        if (title.equals(officialTitle)) {
            switchingToTitle = "";
            setTransportEnabled(true);
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("direct_skip_status", "已确认：" + title)
                    .putLong("direct_skip_confirmed_at", System.currentTimeMillis())
                    .putBoolean("direct_skip_unreliable", false)
                    .apply();
            updateNowPlaying();
            return;
        }
        if (attempt < 2) {
            handler.postDelayed(
                    () -> verifyDirectSkip(title, sequenceDirection, generation, attempt + 1),
                    1_000L);
            return;
        }
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("direct_skip_unreliable", true)
                .putString("direct_skip_status", "后台接口未确认，正在安全切换：" + title)
                .apply();
        DedaoAccessibilityService.returnToListAndPlay(this, title, sequenceDirection);
    }

    private void startContinuousPlayback() {
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putLong("continuous_requested_at", System.currentTimeMillis())
                .putBoolean("companion_queue_suspended", false)
                .apply();
        if (displayedItems.isEmpty()) {
            requestPageScan();
            return;
        }
        if (!isNotificationListenerEnabled()) {
            Toast.makeText(this, "请开启“知识红包伴侣连续播放”，用于播完切下一条", Toast.LENGTH_LONG).show();
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            return;
        }
        PlaybackGuardService.start(this);
        if (!DedaoSessionListener.ensureConnected(this)) {
            pendingContinuousStart = true;
            nowPlayingState.setText("正在启动连续播放监控…");
            int generation = ++monitorWaitGeneration;
            handler.postDelayed(() -> waitForPlaybackMonitor(generation, 0), 500L);
            return;
        }
        pendingContinuousStart = false;
        QueueStore.save(this, displayedItems, 0);
        android.content.SharedPreferences probe =
                getSharedPreferences("page_probe", MODE_PRIVATE);
        probe.edit()
                .remove("official_queue_failed_fingerprint")
                .apply();
        RedPacketItem first = displayedItems.get(0);
        if (first.audioId != null && !first.audioId.isBlank()) {
            playDirect(first, 1, true);
            return;
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "旧缓存需要刷新；若仍失败请开启兼容模式", Toast.LENGTH_LONG).show();
            requestPageScan();
            return;
        }
        if (displayedFingerprint().equals(
                probe.getString("official_queue_fingerprint", ""))
                && !probe.getBoolean("direct_skip_unreliable", false)
                && !liveOfficialTitle().isBlank()) {
            if (OfficialPlayerControl.skip(this, 0)) {
                int generation = ++directSkipGeneration;
                switchingToTitle = first.title;
                switchStartedAt = SystemClock.elapsedRealtime();
                probe.edit()
                        .putBoolean("companion_queue_active", false)
                        .putString("direct_skip_status", "连续播放已提交：" + first.title)
                        .apply();
                handler.postDelayed(this::updateNowPlaying, 250L);
                handler.postDelayed(this::updateNowPlaying, 900L);
                handler.postDelayed(
                        () -> verifyContinuousStart(first.title, generation, 0),
                        1_200L);
                return;
            }
        }
        ++directSkipGeneration;
        if (!DedaoAccessibilityService.syncOfficialQueue(
                this, new ArrayList<>(displayedItems), true)) {
            Toast.makeText(this, "播放顺序同步服务暂未就绪", Toast.LENGTH_SHORT).show();
        }
    }

    private void waitForPlaybackMonitor(int generation, int attempt) {
        if (generation != monitorWaitGeneration || !pendingContinuousStart) return;
        if (DedaoSessionListener.isConnected()) {
            pendingContinuousStart = false;
            startContinuousPlayback();
            return;
        }
        if (attempt == 5) DedaoSessionListener.repairBinding(this);
        else DedaoSessionListener.ensureConnected(this);
        if (attempt < 24) {
            handler.postDelayed(() -> waitForPlaybackMonitor(generation, attempt + 1), 500L);
            return;
        }
        pendingContinuousStart = false;
        nowPlayingState.setText("连续播放监控未连接");
        PlaybackGuardService.stop(this);
        Toast.makeText(this, "授权仍然有效，系统监控正在恢复，请稍后再点一次连续播放", Toast.LENGTH_LONG).show();
        renderPermissions();
    }

    private void verifyContinuousStart(String title, int generation, int attempt) {
        if (generation != directSkipGeneration) return;
        String liveTitle = liveOfficialTitle();
        if (title.equals(liveTitle)) {
            OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY);
            switchingToTitle = "";
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("companion_queue_active", true)
                    .putBoolean("direct_skip_unreliable", false)
                    .putString("direct_skip_status", "连续播放已确认：" + title)
                    .apply();
            updateNowPlaying();
            return;
        }
        if (attempt < 2) {
            handler.postDelayed(
                    () -> verifyContinuousStart(title, generation, attempt + 1),
                    900L);
            return;
        }
        android.content.SharedPreferences probe =
                getSharedPreferences("page_probe", MODE_PRIVATE);
        probe.edit()
                .putBoolean("companion_queue_active", false)
                .putBoolean("direct_skip_unreliable", true)
                .remove("official_queue_fingerprint")
                .putString("direct_skip_status", "得到播放器队列已失效，正在重新同步")
                .commit();
        if (!DedaoAccessibilityService.syncOfficialQueue(
                this, new ArrayList<>(displayedItems), true)) {
            Toast.makeText(this, "得到播放器队列已失效，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private MediaController dedaoController() {
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, DedaoSessionListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if ("com.luojilab.player".equals(controller.getPackageName())) return controller;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateNowPlaying() {
        MediaController controller = dedaoController();
        RedPacketItem current = QueueStore.current(this);
        if (controller == null || current == null || controller.getMetadata() == null) {
            actualMediaTitle = "";
            nowPlayingTitle.setText(current == null ? "选择一条有效内容开始播放" : current.title);
            nowPlayingCourse.setText(current == null ? "知识红包队列" : current.course);
            nowPlayingState.setText("等待播放");
            nowPlayingProgress.setText("00:00 / 00:00");
            playbackBar.setProgress(0);
            playPauseAction.setText("播放");
            playPauseAction.setCompoundDrawablesWithIntrinsicBounds(
                    0, android.R.drawable.ic_media_play, 0, 0);
            setTransportEnabled(false);
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = "";
        PlaybackState state = controller.getPlaybackState();
        long position = state == null ? 0L : Math.max(0L, state.getPosition());
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime());
            position += (long) (elapsed * state.getPlaybackSpeed());
        }
        long duration = Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        boolean sessionPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        String pending = probe.getString("pending_play_title", "");
        long pendingStarted = probe.getLong("play_timing_started_elapsed", 0L);
        if (pending != null && !pending.isBlank()
                && (pendingStarted <= 0L
                || SystemClock.elapsedRealtime() - pendingStarted > 60_000L)) {
            probe.edit()
                    .remove("pending_play_title")
                    .remove("pending_play_direction")
                    .remove("expected_detail_title")
                    .remove("expected_detail_started_at")
                    .apply();
            pending = "";
        }
        if (pending == null || pending.isBlank()) {
            pending = probe.getString("expected_detail_title", "");
            long expectedStarted = probe.getLong("expected_detail_started_at", 0L);
            if (pending != null && !pending.isBlank()
                    && (expectedStarted <= 0L
                    || System.currentTimeMillis() - expectedStarted > 60_000L)) {
                probe.edit()
                        .remove("expected_detail_title")
                        .remove("expected_detail_started_at")
                        .apply();
                pending = "";
            }
        }
        if (pending != null && !pending.isBlank() && !pending.equals(title)) {
            RedPacketItem playingItem = findActiveItem(title);
            String previousActual = actualMediaTitle;
            actualMediaTitle = title;
            if (!actualMediaTitle.equals(previousActual) && list != null) renderItems();
            nowPlayingTitle.setText(title);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            nowPlayingCourse.setText(playingItem != null ? playingItem.course
                    : (artist == null || artist.isBlank() ? "得到正在播放的内容" : artist));
            nowPlayingState.setText("正在切换…");
            nowPlayingProgress.setText(formatPlaybackTime(position) + " / " + formatPlaybackTime(duration));
            playbackBar.setProgress(duration <= 0L ? 0
                    : (int) Math.min(1000L, position * 1000L / duration));
            updatePlayPauseVisual(sessionPlaying);
            setTransportEnabled(false);
            return;
        }

        RedPacketItem actualItem = QueueStore.selectTitle(this, title);
        String previousActual = actualMediaTitle;
        actualMediaTitle = title;
        if (actualItem != null) current = actualItem;
        if (!actualMediaTitle.equals(previousActual) && list != null) renderItems();

        nowPlayingTitle.setText(title);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        nowPlayingCourse.setText(actualItem != null ? actualItem.course
                : (artist == null || artist.isBlank() ? "得到正在播放的内容" : artist));
        nowPlayingProgress.setText(formatPlaybackTime(position) + " / " + formatPlaybackTime(duration));
        boolean playing = SystemClock.elapsedRealtime() - lastToggleAt < 800L
                ? optimisticPlaying : sessionPlaying;
        updatePlayPauseVisual(playing);
        nowPlayingState.setText(actualItem == null
                ? (playing ? "得到正在播放其他内容" : "得到其他内容已暂停")
                : (playing ? "正在播放" : "已暂停"));
        playbackBar.setProgress(duration <= 0L ? 0
                : (int) Math.min(1000L, position * 1000L / duration));
        if (!switchingToTitle.isBlank() && switchingToTitle.equals(title)) {
            switchingToTitle = "";
            switchStartedAt = 0L;
        } else if (!switchingToTitle.isBlank()
                && SystemClock.elapsedRealtime() - switchStartedAt > 35_000L) {
            switchingToTitle = "";
            switchStartedAt = 0L;
        }
        setTransportEnabled(switchingToTitle.isBlank());
    }

    private RedPacketItem findActiveItem(String title) {
        if (title == null || title.isBlank()) return null;
        for (RedPacketItem item : allItems) if (title.equals(item.title)) return item;
        return null;
    }

    private void updatePlayPauseVisual(boolean playing) {
        optimisticPlaying = playing;
        playPauseAction.setText(playing ? "暂停" : "播放");
        playPauseAction.setCompoundDrawablesWithIntrinsicBounds(0,
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                0, 0);
    }

    private void setTransportEnabled(boolean enabled) {
        // Pause must remain available while a previous/next request is being confirmed.
        playPauseAction.setEnabled(dedaoController() != null);
        if (previousAction != null) previousAction.setEnabled(enabled);
        if (nextAction != null) nextAction.setEnabled(enabled);
    }

    private String formatPlaybackTime(long millis) {
        long seconds = Math.max(0L, millis / 1_000L);
        return String.format(Locale.CHINA, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private void togglePlayback() {
        MediaController controller = dedaoController();
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        boolean sessionPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        long now = SystemClock.elapsedRealtime();
        boolean baseline = now - lastToggleAt < 800L ? optimisticPlaying : sessionPlaying;
        boolean desiredPlaying = !baseline;
        lastToggleAt = now;
        optimisticPlaying = desiredPlaying;
        int generation = ++toggleGeneration;
        if (!desiredPlaying) {
            cancelPendingPlaybackSwitch();
            suspendContinuousQueue();
        } else {
            resumeContinuousQueueIfSuspended();
        }
        updatePlayPauseVisual(desiredPlaying);
        nowPlayingState.setText(desiredPlaying ? "正在开始播放…" : "正在暂停…");
        applyPlaybackState(desiredPlaying);
        handler.postDelayed(() -> verifyPlaybackState(desiredPlaying, generation, 0), 250L);
    }

    private void applyPlaybackState(boolean playing) {
        MediaController controller = dedaoController();
        if (controller != null) {
            if (playing) controller.getTransportControls().play();
            else controller.getTransportControls().pause();
        }
        OfficialPlayerControl.send(this,
                playing ? OfficialPlayerControl.PLAY : OfficialPlayerControl.PAUSE);
    }

    private void verifyPlaybackState(boolean desiredPlaying, int generation, int attempt) {
        if (generation != toggleGeneration) return;
        MediaController controller = dedaoController();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean actualPlaying = state != null
                && state.getState() == PlaybackState.STATE_PLAYING;
        if (actualPlaying == desiredPlaying) {
            updateNowPlaying();
            return;
        }
        if (attempt < 3) {
            applyPlaybackState(desiredPlaying);
            handler.postDelayed(
                    () -> verifyPlaybackState(desiredPlaying, generation, attempt + 1),
                    attempt == 0 ? 450L : 800L);
            return;
        }
        updateNowPlaying();
        Toast.makeText(this, desiredPlaying ? "得到暂未开始播放" : "得到暂未暂停",
                Toast.LENGTH_SHORT).show();
    }

    private void cancelPendingPlaybackSwitch() {
        ++directSkipGeneration;
        directOpenForegroundGeneration = 0;
        switchingToTitle = "";
        switchStartedAt = 0L;
        DedaoAccessibilityService.cancelPendingPlayback(this);
        QueueStore.selectTitle(this, liveOfficialTitle());
        setTransportEnabled(true);
    }

    private void suspendContinuousQueue() {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean wasActive = probe.getBoolean("companion_queue_active", false);
        probe.edit()
                .putBoolean("companion_queue_active", false)
                .putBoolean("companion_queue_suspended", wasActive)
                .putString("playback_monitor_status",
                        wasActive ? "连续播放已暂停" : "播放已暂停")
                .apply();
        PlaybackGuardService.stop(this);
    }

    private void resumeContinuousQueueIfSuspended() {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        if (!probe.getBoolean("companion_queue_suspended", false)) return;
        probe.edit()
                .putBoolean("companion_queue_suspended", false)
                .putBoolean("companion_queue_active", true)
                .putLong("playback_monitor_last_playing_at", System.currentTimeMillis())
                .putString("playback_monitor_status", "连续播放已恢复")
                .apply();
        PlaybackGuardService.start(this);
    }

    private void resumeOfficialPlayback() {
        MediaController controller = dedaoController();
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return;
        controller.getTransportControls().play();
        OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY);
    }

    private void playNext() {
        playAdjacent(true);
    }

    private void playPrevious() {
        playAdjacent(false);
    }

    private void playAdjacent(boolean forward) {
        String liveTitle = currentOfficialTitle();
        int actualIndex = displayedIndexForTitle(liveTitle);
        if (actualIndex < 0) {
            RedPacketItem stored = QueueStore.current(this);
            if (stored != null) actualIndex = displayedIndexForTitle(stored.title);
        }
        int targetIndex = actualIndex + (forward ? 1 : -1);
        if (actualIndex < 0 || targetIndex < 0 || targetIndex >= displayedItems.size()) {
            Toast.makeText(this, forward ? "已经是最后一条" : "已经是第一条", Toast.LENGTH_SHORT).show();
            return;
        }
        RedPacketItem item = displayedItems.get(targetIndex);
        QueueStore.save(this, displayedItems, targetIndex);
        switchingToTitle = item.title;
        switchStartedAt = SystemClock.elapsedRealtime();
        nowPlayingState.setText("正在切换…");
        if (item.audioId != null && !item.audioId.isBlank()) {
            boolean queueActive = getSharedPreferences("page_probe", MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false);
            playDirect(item, forward ? 1 : -1, queueActive);
        } else {
            playTitleLegacy(item.title, forward ? 1 : -1);
        }
        handler.postDelayed(this::updateNowPlaying, 80);
        handler.postDelayed(this::updateNowPlaying, 300);
    }

    private String currentOfficialTitle() {
        String liveTitle = liveOfficialTitle();
        return liveTitle.isBlank() ? actualMediaTitle : liveTitle;
    }

    private String liveOfficialTitle() {
        MediaController controller = dedaoController();
        if (controller != null && controller.getMetadata() != null) {
            String title = controller.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title != null && !title.isBlank()) return title;
        }
        return "";
    }

    private int displayedIndexForTitle(String title) {
        if (title == null || title.isBlank()) return -1;
        for (int i = 0; i < displayedItems.size(); i++) {
            if (title.equals(displayedItems.get(i).title)) return i;
        }
        return -1;
    }

    private void refreshCachedResults() {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean scanning = probe.getBoolean("scan_requested", false);
        boolean failed = "bridge_failed".equals(probe.getString("scan_source", ""));
        String failureStatus = probe.getString("scan_status", "同步未完成");
        if (failureStatus == null || failureStatus.isBlank()) failureStatus = "同步未完成";
        diagnosticAction.setVisibility(failed && !scanning ? View.VISIBLE : View.GONE);
        int count = loadScannedItems(probe.getString("scan_items", "[]"));
        refreshAction.setEnabled(!scanning);
        if (count > 0) {
            rebuildCourseFilter();
            applyFilters();
            syncBanner.setVisibility(scanning || failed ? View.VISIBLE : View.GONE);
            if (scanning) syncStatus.setText(
                    probe.getString("scan_status", "正在向得到确认红包权益…"));
            else if (failed) syncStatus.setText(failureStatus);
        } else if (scanning) {
            syncBanner.setVisibility(View.VISIBLE);
            syncStatus.setText(probe.getString("scan_status", "正在同步有效红包…"));
            renderEmpty("正在同步，请稍候…");
        } else if (failed) {
            syncBanner.setVisibility(View.VISIBLE);
            syncStatus.setText(failureStatus);
            renderEmpty("同步未完成，可重试或复制诊断反馈");
        } else {
            syncBanner.setVisibility(View.VISIBLE);
            syncStatus.setText("尚未同步有效红包");
            renderEmpty("点击刷新，由得到确认当前有效红包");
        }
        renderPermissions();
    }

    private int loadScannedItems(String json) {
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            allItems.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value != null) allItems.add(RedPacketItem.fromStored(value));
            }
            return allItems.size();
        } catch (Exception ignored) {
            allItems.clear();
            return 0;
        }
    }

    private void rebuildCourseFilter() {
        String previous = getSharedPreferences("ui_preferences", MODE_PRIVATE)
                .getString("course_filter", "全部课程");
        Set<String> courses = new LinkedHashSet<>();
        for (RedPacketItem item : allItems) if (!item.course.isBlank()) courses.add(item.course);
        ArrayList<String> values = new ArrayList<>();
        values.add("全部课程");
        values.addAll(courses);
        courseFilter.setAdapter(adapter(values));
        courseFilter.setSelection(Math.max(0, values.indexOf(previous)));
    }

    private void applyFilters() {
        if (list == null || sortOrder == null || courseFilter == null) return;
        RedPacketItem previouslyCurrent = QueueStore.current(this);
        String selectedCourse = courseFilter.getSelectedItem() == null ? "全部课程" : String.valueOf(courseFilter.getSelectedItem());
        displayedItems.clear();
        displayedItems.addAll(QueuePolicy.apply(
                allItems,
                selectedCourse,
                hideCompletedFilter != null && hideCompletedFilter.isChecked(),
                sortOrder.getSelectedItemPosition() == 0));
        int preservedIndex = 0;
        if (previouslyCurrent != null) {
            for (int i = 0; i < displayedItems.size(); i++) {
                if (previouslyCurrent.id.equals(displayedItems.get(i).id)) {
                    preservedIndex = i;
                    break;
                }
            }
        }
        QueueStore.save(this, displayedItems, preservedIndex);
        renderItems();
        handler.removeCallbacks(queueSync);
        handler.postDelayed(queueSync, 700);
    }

    private String displayedFingerprint() {
        JSONArray titles = new JSONArray();
        for (RedPacketItem item : displayedItems) titles.put(item.title);
        return titles.toString();
    }

    private boolean allDisplayedItemsHaveAudioIds() {
        if (displayedItems.isEmpty()) return false;
        for (RedPacketItem item : displayedItems) {
            if (item.audioId == null || item.audioId.isBlank()) return false;
        }
        return true;
    }

    private void renderItems() {
        list.removeAllViews();
        episodeRows.clear();
        if (displayedItems.isEmpty()) {
            renderEmpty(allItems.isEmpty() ? "还没有同步到有效内容" : "当前筛选下没有内容");
            primaryAction.setText(allItems.isEmpty() ? "同步有效红包" : "没有可播放内容");
            primaryAction.setEnabled(allItems.isEmpty());
            return;
        }
        TextView count = text("播放队列  ·  " + displayedItems.size() + " 条", 14, INK, Typeface.BOLD);
        count.setPadding(dp(16), dp(10), dp(16), dp(9));
        list.addView(count, matchWrap());
        RedPacketItem current = QueueStore.current(this);
        String groupCourse = null;
        LinearLayout group = null;
        for (int i = 0; i < displayedItems.size(); i++) {
            RedPacketItem item = displayedItems.get(i);
            final int index = i;
            boolean selected = actualMediaTitle.isBlank()
                    ? current != null && current.id.equals(item.id)
                    : actualMediaTitle.equals(item.title);

            String courseName = item.course.isBlank() ? "未识别课程" : item.course;
            if (!courseName.equals(groupCourse)) {
                groupCourse = courseName;
                group = new LinearLayout(this);
                group.setOrientation(LinearLayout.VERTICAL);
                group.setBackground(roundRect(Color.WHITE, 14));

                LinearLayout courseHeader = new LinearLayout(this);
                courseHeader.setGravity(Gravity.CENTER_VERTICAL);
                courseHeader.setPadding(dp(14), dp(8), dp(14), dp(8));
                ImageView courseIcon = new ImageView(this);
                courseIcon.setImageResource(com.dingaimin.dedaocompanion.R.drawable.ic_launcher);
                courseHeader.addView(courseIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
                TextView courseTitle = text(courseName, 14, INK, Typeface.BOLD);
                courseTitle.setPadding(dp(9), 0, 0, 0);
                courseHeader.addView(courseTitle, new LinearLayout.LayoutParams(0, dp(34), 1f));
                group.addView(courseHeader, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

                LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                groupParams.setMargins(dp(14), 0, dp(14), dp(6));
                list.addView(group, groupParams);
            }

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(10), dp(7));
            row.setBackgroundColor(Color.WHITE);

            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(0, 0, dp(10), 0);
            TextView itemTitle = text(item.title, 15, selected ? ORANGE : INK, Typeface.BOLD);
            itemTitle.setPadding(0, 0, 0, dp(3));
            itemTitle.setLineSpacing(0, 1.08f);
            itemTitle.setMaxLines(2);
            itemTitle.setEllipsize(TextUtils.TruncateAt.END);
            String learningState = item.completed ? "已学完" : "红包有效";
            TextView meta = text(selected ? learningState + "  ·  当前播放"
                    : learningState + "  ·  点击播放", 11,
                    selected ? ORANGE : MUTED, selected ? Typeface.BOLD : Typeface.NORMAL);
            copy.addView(itemTitle, matchWrap());
            copy.addView(meta, matchWrap());
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView play = new ImageView(this);
            play.setImageResource(android.R.drawable.ic_media_play);
            play.setColorFilter(selected ? ORANGE : MUTED);
            play.setContentDescription("播放 " + item.title);
            row.addView(play, new LinearLayout.LayoutParams(dp(30), dp(30)));
            row.setOnClickListener(v -> {
                QueueStore.save(this, displayedItems, index);
                renderItems();
                playTitle(item.title);
            });
            episodeRows.add(row);
            if (group != null) group.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
            boolean nextSameCourse = i < displayedItems.size() - 1
                    && courseName.equals(displayedItems.get(i + 1).course.isBlank()
                    ? "未识别课程" : displayedItems.get(i + 1).course);
            if (nextSameCourse && group != null) {
                View divider = new View(this);
                divider.setBackgroundColor(LINE);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.setMargins(dp(14), 0, dp(14), 0);
                group.addView(divider, dividerParams);
            }
        }
        primaryAction.setEnabled(true);
        primaryAction.setText("连续播放  ·  " + displayedItems.size() + " 条");
    }

    private void renderEmpty(String message) {
        list.removeAllViews();
        TextView empty = text(message, 15, MUTED, Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(54), dp(16), dp(54));
        list.addView(empty, matchWrap());
    }

    private void renderPermissions() {
        permissionCard.removeAllViews();
        boolean accessibility = isAccessibilityEnabled();
        boolean listener = isNotificationListenerEnabled();
        String source = getSharedPreferences("page_probe", MODE_PRIVATE)
                .getString("scan_source", "");
        boolean needsFallback = "bridge_failed".equals(source)
                || "accessibility_fallback".equals(source);
        boolean monitorConnected = listener && DedaoSessionListener.isConnected();
        boolean backgroundRestricted = isBackgroundRestricted();
        if (listener && !monitorConnected && !backgroundRestricted) {
            // The authorization is still valid. Rebinding is an internal recovery detail and
            // should stay silent instead of looking like another permission request.
            permissionCard.setVisibility(View.GONE);
            return;
        }
        if (monitorConnected && (!needsFallback || accessibility) && !backgroundRestricted) {
            permissionCard.setVisibility(View.GONE);
            return;
        }
        permissionCard.setVisibility(View.VISIBLE);
        String permissionTitle = backgroundRestricted ? "允许后台连续播放"
                : (!listener ? "开启连续播放权限"
                : (!monitorConnected ? "正在恢复连续播放监控" : "可选：开启兼容模式"));
        permissionCard.addView(text(permissionTitle, 14, INK, Typeface.BOLD), matchWrap());
        if (!listener) {
            TextView copy = text("开启播放监控后，播完会自动衔接下一条；通常只需授权一次。", 12, MUTED, Typeface.NORMAL);
            copy.setPadding(0, dp(4), 0, dp(8));
            permissionCard.addView(copy, matchWrap());
            permissionCard.addView(compactButton("开启连续播放", v -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")), false), matchWrap());
        } else if (backgroundRestricted) {
            TextView copy = text("系统把伴侣设成了“受限”，锁屏清理会停止连续播放。请在应用信息的省电策略中选择“不限制”，并在最近任务里锁定伴侣；只需设置一次。", 12, MUTED, Typeface.NORMAL);
            copy.setPadding(0, dp(4), 0, dp(8));
            permissionCard.addView(copy, matchWrap());
            permissionCard.addView(compactButton("允许后台运行", v -> requestBackgroundPlaybackAccess(), false), matchWrap());
        } else if (needsFallback && !accessibility) {
            String message = DiagnosticReport.isHuaweiFamily()
                    ? "这是刷新列表的可选兼容模式，不是连续播放授权。鸿蒙/华为若无法开启，可在应用信息中允许受限设置。"
                    : "这是刷新列表和切换失败时的可选备用模式，不是连续播放授权；不开启也不会撤销已有的连续播放权限。";
            TextView copy = text(message, 12, MUTED, Typeface.NORMAL);
            copy.setPadding(0, dp(4), 0, dp(8));
            permissionCard.addView(copy, matchWrap());
            permissionCard.addView(compactButton("打开应用信息", v -> openApplicationDetails(), false), matchWrap());
            permissionCard.addView(compactButton("开启兼容模式", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), false), matchWrap());
        }
    }

    private void openApplicationDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void requestBackgroundPlaybackAccess() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        openApplicationDetails();
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "知识红包伴侣诊断报告", DiagnosticReport.build(this)));
        Toast.makeText(this, "诊断信息已复制，可直接粘贴到群里", Toast.LENGTH_LONG).show();
    }

    private boolean recoverStalledScanIfNeeded(boolean force) {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        if (!probe.getBoolean("scan_requested", false)) return false;
        long startedAt = probe.getLong("scan_started_at", 0L);
        long elapsed = startedAt <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - startedAt;
        if (!force && elapsed < BRIDGE_SCAN_TIMEOUT_MS) return false;
        String stage = probe.getString("bridge_stage", "unknown");
        failPendingScan("同步已中断，最后阶段：" + (stage == null ? "unknown" : stage));
        return true;
    }

    private void failPendingScan(String status) {
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        String stage = probe.getString("bridge_stage", "unknown");
        probe.edit()
                .putBoolean("scan_requested", false)
                .remove("scan_return_to_app")
                .putString("scan_source", "bridge_failed")
                .putString("scan_status", status)
                .putLong("scan_finished_at", System.currentTimeMillis())
                .commit();
        BridgeKeepAliveService.stop(this);
        DiagnosticReport.mark(this, "scan_recovered", "last=" + stage);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = new ComponentName(this, DedaoAccessibilityService.class).flattenToString();
        return enabled != null && enabled.contains(component);
    }

    private boolean isNotificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        String component = new ComponentName(this, DedaoSessionListener.class).flattenToString();
        return enabled != null && enabled.contains(component);
    }

    private boolean isBackgroundRestricted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && power.isIgnoringBatteryOptimizations(getPackageName())) return false;
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager != null && activityManager.isBackgroundRestricted()) return true;
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        return usage != null
                && usage.getAppStandbyBucket() >= UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(roundRect(Color.WHITE, 16));
        card.setElevation(dp(1));
        return card;
    }

    private Button compactButton(String label, View.OnClickListener click, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : ORANGE);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ORANGE : Color.rgb(255, 238, 230)));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setOnClickListener(click);
        return button;
    }

    private Button mediaButton(String label, int icon, View.OnClickListener click, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(primary ? Color.WHITE : INK));
        button.setCompoundDrawablePadding(dp(3));
        button.setGravity(Gravity.CENTER);
        button.setBackground(primary ? roundRect(ORANGE, 40) : roundRect(Color.WHITE, 40, LINE));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setOnClickListener(click);
        return button;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter(List.of(values)));
        spinner.setPadding(dp(8), 0, dp(8), 0);
        spinner.setBackground(roundRect(Color.WHITE, 12, LINE));
        return spinner;
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private GradientDrawable roundRect(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = roundRect(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    static Bitmap captureCurrentScreen() {
        MainActivity activity = visibleActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        View decor = activity.getWindow().getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return null;
        try {
            Bitmap full = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(full));
            int statusBarId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            int navigationBarId = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            int top = statusBarId == 0 ? 0 : activity.getResources().getDimensionPixelSize(statusBarId);
            int bottom = navigationBarId == 0 ? 0 : activity.getResources().getDimensionPixelSize(navigationBarId);
            int contentHeight = full.getHeight() - top - bottom;
            if (top > 0 && contentHeight > 0) {
                Bitmap content = Bitmap.createBitmap(full, 0, top, full.getWidth(), contentHeight);
                full.recycle();
                return content;
            }
            return full;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (isNotificationListenerEnabled()) {
            DedaoSessionListener.ensureConnected(this);
            handler.postDelayed(() -> {
                if (resumed && isNotificationListenerEnabled()
                        && !DedaoSessionListener.isConnected()) {
                    DedaoSessionListener.repairBinding(this);
                }
            }, 1_500L);
            handler.postDelayed(this::renderPermissions, 800L);
        }
        handler.removeCallbacks(mediaPoll);
        handler.post(mediaPoll);
        refreshCachedResults();
        if (getSharedPreferences("page_probe", MODE_PRIVATE).getBoolean("scan_requested", false)) {
            if (recoverStalledScanIfNeeded(false)) {
                refreshCachedResults();
                return;
            }
            handler.removeCallbacks(scanPoll);
            handler.post(scanPoll);
            return;
        }
        if (!externalAction && !autoSyncStarted) {
            long finishedAt = getSharedPreferences("page_probe", MODE_PRIVATE).getLong("scan_finished_at", 0L);
            if (finishedAt == 0L || System.currentTimeMillis() - finishedAt > AUTO_REFRESH_AFTER_MS) {
                handler.postDelayed(this::requestPageScan, 350);
            }
            autoSyncStarted = true;
        }
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(mediaPoll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (visibleActivity.get() == this) visibleActivity.clear();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
