package com.dingaimin.dedaocompanion;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
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
import android.graphics.drawable.RippleDrawable;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    private ImageButton playPauseAction;
    private ImageButton previousAction;
    private ImageButton nextAction;
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
            case "queue": showPlaybackQueue(false); break;
            case "queue_manage": showPlaybackQueue(true); break;
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
            case "seek_last_three_minutes":
                MediaController controller = dedaoController();
                MediaMetadata metadata = controller == null ? null : controller.getMetadata();
                long duration = metadata == null ? 0L
                        : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                submitted = controller != null && duration > 8_000L;
                if (submitted) {
                    controller.getTransportControls().seekTo(Math.max(0L,
                            duration - ("seek_last_three_minutes".equals(control) ? 180_000L : 7_000L)));
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
        Button playbackSettings = compactButton("设置", v -> showPlaybackSettings(), false);
        playbackSettings.setTextColor(MUTED);
        playbackSettings.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        header.addView(playbackSettings, new LinearLayout.LayoutParams(dp(48), dp(40)));
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playerParams.setMargins(dp(14), dp(6), dp(14), dp(6));
        root.addView(nowPlayingCard, playerParams);

        LinearLayout pickers = new LinearLayout(this);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(Gravity.CENTER_VERTICAL);
        pickers.setPadding(dp(14), 0, dp(14), dp(6));
        sortOrder = spinner(new String[]{"最新领取优先", "最早领取优先", "自定义顺序"});
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

        FrameLayout transport = new FrameLayout(this);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        previousAction = mediaButton("上一条", MediaIcon.Kind.PREVIOUS,
                v -> playPrevious(), false);
        controls.addView(previousAction, new LinearLayout.LayoutParams(dp(48), dp(48)));
        playPauseAction = mediaButton("播放", MediaIcon.Kind.PLAY,
                v -> togglePlayback(), true);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        playParams.setMargins(dp(12), 0, dp(12), 0);
        controls.addView(playPauseAction, playParams);
        nextAction = mediaButton("下一条", MediaIcon.Kind.NEXT,
                v -> playNext(), false);
        controls.addView(nextAction, new LinearLayout.LayoutParams(dp(48), dp(48)));
        transport.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(72), Gravity.CENTER));
        ImageButton queueAction = mediaButton("播放列表", MediaIcon.Kind.QUEUE,
                v -> showPlaybackQueue(), false);
        queueAction.setImageDrawable(new MediaIcon(MediaIcon.Kind.QUEUE, MUTED, dp(24)));
        transport.addView(queueAction, new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        player.addView(transport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));
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
        boolean keepContinuous = continuousStart || probe.getBoolean("companion_queue_active", false)
                || probe.getBoolean("companion_queue_suspended", false);
        probe.edit()
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .putString("direct_open_target_title", item.title)
                .putBoolean("companion_queue_active", keepContinuous)
                .putBoolean("companion_queue_suspended", false)
                .commit();
        if (!OfficialPlayerControl.open(this, item.audioId)) {
            probe.edit().remove("direct_open_target_title").apply();
            playTitleLegacy(item.title, sequenceDirection);
            return;
        }
        int generation = ++directSkipGeneration;
        probe.edit()
                .putBoolean("companion_queue_active", keepContinuous)
                .putString("direct_open_status", "已提交：" + item.title)
                .putLong("direct_open_at", System.currentTimeMillis())
                .apply();
        handler.postDelayed(this::updateNowPlaying, 160L);
        handler.postDelayed(() -> verifyDirectOpen(
                item, sequenceDirection, keepContinuous, generation, 0), 100L);
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
        if (attempt < 40) {
            if (attempt == 24) {
                OfficialPlayerControl.open(this, item.audioId);
            }
            if (attempt == 12) openOfficialContextFor(item, generation);
            handler.postDelayed(() -> verifyDirectOpen(
                    item, sequenceDirection, continuousStart, generation, attempt + 1), 200L);
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
            updatePlayPauseVisual(false);
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
        playPauseAction.setContentDescription(playing ? "暂停" : "播放");
        playPauseAction.setImageDrawable(new MediaIcon(
                playing ? MediaIcon.Kind.PAUSE : MediaIcon.Kind.PLAY, Color.WHITE, dp(28)));
    }

    private void setTransportEnabled(boolean enabled) {
        // Pause must remain available while a previous/next request is being confirmed.
        playPauseAction.setEnabled(dedaoController() != null);
        if (previousAction != null) previousAction.setEnabled(enabled);
        if (nextAction != null) nextAction.setEnabled(enabled);
        if (previousAction != null) previousAction.setAlpha(enabled ? 1f : 0.35f);
        if (nextAction != null) nextAction.setAlpha(enabled ? 1f : 0.35f);
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
        if (desiredPlaying && findActiveItem(liveOfficialTitle()) != null
                && displayedIndexForTitle(liveOfficialTitle()) < 0) {
            RedPacketItem selected = QueueStore.current(this);
            if (selected != null) playDirect(selected, 1, false);
            return;
        }
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
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .remove("direct_open_target_title").apply();
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
                .putBoolean("companion_queue_suspended", wasActive
                        || probe.getBoolean("companion_queue_suspended", false))
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
        int orderPosition = sortOrder.getSelectedItemPosition();
        List<RedPacketItem> filtered = QueuePolicy.apply(
                allItems,
                selectedCourse,
                hideCompletedFilter != null && hideCompletedFilter.isChecked(),
                orderPosition != 1);
        if (orderPosition == 2) {
            filtered = QueuePolicy.applyCustomOrder(filtered, loadCustomQueueOrder());
        }
        displayedItems.addAll(QueuePolicy.excluding(filtered, removedQueueKeys()));
        int preservedIndex = 0;
        if (previouslyCurrent != null) {
            for (int i = 0; i < displayedItems.size(); i++) {
                if (QueuePolicy.stableKey(previouslyCurrent).equals(QueuePolicy.stableKey(displayedItems.get(i)))) {
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
        TextView count = text("有效内容  ·  " + displayedItems.size() + " 条",
                14, INK, Typeface.BOLD);
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

    private void showPlaybackQueue() {
        showPlaybackQueue(false);
    }

    private void showPlaybackQueue(boolean initiallyManaging) {
        if (displayedItems.isEmpty() && removedQueueKeys().isEmpty()) {
            Toast.makeText(this, "播放队列还是空的，请先刷新", Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ArrayList<RedPacketItem> workingQueue = new ArrayList<>(displayedItems);
        dialog.setContentView(buildPlaybackQueueSheet(dialog, workingQueue, initiallyManaging));
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setGravity(Gravity.BOTTOM);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.48f;
        window.setAttributes(attributes);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(screenHeight - dp(72), dp(620)));
    }

    private View buildPlaybackQueueSheet(Dialog dialog, ArrayList<RedPacketItem> workingQueue,
                                         boolean managing) {
        QueueAdapter[] adapterHolder = new QueueAdapter[1];
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(0, dp(8), 0, 0);
        sheet.setBackground(roundTopRect(Color.WHITE, 22));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), 0, dp(14), 0);
        if (managing) {
            View leadingSpace = new View(this);
            header.addView(leadingSpace, new LinearLayout.LayoutParams(dp(64), dp(56)));
            LinearLayout titleBlock = new LinearLayout(this);
            titleBlock.setOrientation(LinearLayout.VERTICAL);
            titleBlock.setGravity(Gravity.CENTER);
            titleBlock.addView(text("管理播放队列", 17, INK, Typeface.BOLD),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            titleBlock.addView(text("移除条目 · 拖动排序", 11, MUTED, Typeface.NORMAL),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.addView(titleBlock, new LinearLayout.LayoutParams(0, dp(64), 1f));
            Button done = compactButton("完成", v -> {
                if (adapterHolder[0] != null) adapterHolder[0].persistIfChanged();
                ArrayList<RedPacketItem> updated = new ArrayList<>(displayedItems);
                dialog.setContentView(buildPlaybackQueueSheet(dialog, updated, false));
            }, false);
            done.setTextColor(ORANGE);
            done.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            header.addView(done, new LinearLayout.LayoutParams(dp(64), dp(48)));
        } else {
            LinearLayout modeChip = new LinearLayout(this);
            modeChip.setGravity(Gravity.CENTER_VERTICAL);
            modeChip.setPadding(dp(10), 0, dp(12), 0);
            modeChip.setBackground(roundRect(Color.rgb(247, 247, 248), 22));
            ImageView orderIcon = new ImageView(this);
            orderIcon.setImageDrawable(new MediaIcon(MediaIcon.Kind.QUEUE, INK, dp(24)));
            orderIcon.setColorFilter(INK);
            orderIcon.setContentDescription("顺序播放");
            modeChip.addView(orderIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView title = text("顺序播放  ·  " + workingQueue.size() + " 条",
                    17, INK, Typeface.BOLD);
            title.setPadding(dp(8), 0, 0, 0);
            modeChip.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            header.addView(modeChip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            View spacer = new View(this);
            header.addView(spacer, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button manage = compactButton("管理", v -> dialog.setContentView(
                    buildPlaybackQueueSheet(dialog, workingQueue, true)), false);
            manage.setTextColor(INK);
            manage.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            header.addView(manage, new LinearLayout.LayoutParams(dp(68), dp(48)));
        }
        sheet.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(LINE);
        sheet.addView(headerDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        RecyclerView queueList = new RecyclerView(this);
        queueList.setLayoutManager(new LinearLayoutManager(this));
        queueList.setClipToPadding(false);
        queueList.setPadding(dp(14), dp(4), dp(14), dp(12));
        queueList.setHasFixedSize(true);
        QueueAdapter adapter = new QueueAdapter(dialog, workingQueue, managing);
        adapterHolder[0] = adapter;
        queueList.setAdapter(adapter);
        if (managing) {
            ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override public boolean onMove(RecyclerView recyclerView,
                                                RecyclerView.ViewHolder source,
                                                RecyclerView.ViewHolder target) {
                    return adapter.move(source.getBindingAdapterPosition(),
                            target.getBindingAdapterPosition());
                }

                @Override public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                }

                @Override public boolean isLongPressDragEnabled() {
                    return false;
                }

                @Override public float getMoveThreshold(RecyclerView.ViewHolder viewHolder) {
                    return 0.55f;
                }

                @Override public void onSelectedChanged(RecyclerView.ViewHolder viewHolder,
                                                        int actionState) {
                    super.onSelectedChanged(viewHolder, actionState);
                    if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder.itemView.setElevation(dp(10));
                        viewHolder.itemView.setScaleX(1.015f);
                        viewHolder.itemView.setScaleY(1.015f);
                    }
                }

                @Override public void clearView(RecyclerView recyclerView,
                                                RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    viewHolder.itemView.animate()
                            .scaleX(1f).scaleY(1f).setDuration(120).start();
                    viewHolder.itemView.setElevation(0f);
                    adapter.persistIfChanged();
                }
            });
            helper.attachToRecyclerView(queueList);
            adapter.setDragHelper(helper);
        }
        sheet.addView(queueList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        int currentIndex = adapter.currentIndex();
        if (currentIndex > 0) queueList.post(() -> queueList.scrollToPosition(currentIndex));

        if (managing) {
            View footerDivider = new View(this);
            footerDivider.setBackgroundColor(LINE);
            sheet.addView(footerDivider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            TextView footer = text("修改自动保存 · 仅影响本地播放列表",
                    12, MUTED, Typeface.NORMAL);
            footer.setGravity(Gravity.CENTER);
            footer.setBackgroundColor(Color.rgb(248, 248, 250));
            sheet.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        if (workingQueue.isEmpty()) {
            TextView empty = text("播放列表已清空", 15, MUTED, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            sheet.addView(empty, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        int removedCount = removedQueueKeys().size();
        if (removedCount > 0) {
            Button restore = compactButton("恢复已移除的内容（" + removedCount + "）", v -> {
                getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                        .remove("removed_queue_keys").apply();
                applyFilters();
                dialog.setContentView(buildPlaybackQueueSheet(dialog,
                        new ArrayList<>(displayedItems), managing));
            }, false);
            LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(-1, dp(48));
            restoreParams.setMargins(dp(20), dp(6), dp(20), dp(16));
            sheet.addView(restore, restoreParams);
        }
        return sheet;
    }

    private final class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueHolder> {
        private final Dialog dialog;
        private final ArrayList<RedPacketItem> items;
        private final boolean managing;
        private final RedPacketItem current = QueueStore.current(MainActivity.this);
        private ItemTouchHelper dragHelper;
        private boolean changed;

        QueueAdapter(Dialog dialog, ArrayList<RedPacketItem> items, boolean managing) {
            this.dialog = dialog;
            this.items = items;
            this.managing = managing;
        }

        void setDragHelper(ItemTouchHelper helper) {
            dragHelper = helper;
        }

        int currentIndex() {
            for (int i = 0; i < items.size(); i++) if (isCurrent(items.get(i))) return i;
            return -1;
        }

        boolean move(int from, int to) {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false;
            }
            RedPacketItem moved = items.remove(from);
            items.add(to, moved);
            changed = true;
            notifyItemMoved(from, to);
            return true;
        }

        void persistIfChanged() {
            if (!changed) return;
            changed = false;
            persistEditedQueueOrder(items);
        }

        private boolean isCurrent(RedPacketItem item) {
            return actualMediaTitle.isBlank()
                    ? current != null && current.id.equals(item.id)
                    : actualMediaTitle.equals(item.title);
        }

        @Override public QueueHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(4), dp(6));

            ImageView state = new ImageView(MainActivity.this);
            row.addView(state, new LinearLayout.LayoutParams(dp(28), dp(28)));

            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(dp(10), 0, dp(8), 0);
            TextView title = text("", 15, INK, Typeface.NORMAL);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView meta = text("", 11, MUTED, Typeface.NORMAL);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
            copy.addView(meta, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(23)));
            row.addView(copy, new LinearLayout.LayoutParams(0, dp(54), 1f));

            ImageView handle = new ImageView(MainActivity.this);
            handle.setImageDrawable(new MediaIcon(MediaIcon.Kind.DRAG, MUTED, dp(24)));
            handle.setColorFilter(Color.rgb(150, 153, 160));
            handle.setPadding(dp(10), dp(10), dp(8), dp(10));
            ImageButton remove = mediaButton("移出播放列表", MediaIcon.Kind.REMOVE,
                    v -> {}, false);
            remove.setImageDrawable(new MediaIcon(MediaIcon.Kind.REMOVE, Color.rgb(196, 71, 64), dp(24)));
            row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(48)));

            container.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(managing ? 77 : 73)));
            View divider = new View(MainActivity.this);
            divider.setBackgroundColor(LINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.setMargins(dp(46), 0, 0, 0);
            container.addView(divider, dividerParams);
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(managing ? 78 : 74)));
            return new QueueHolder(container, row, state, title, meta, handle, remove);
        }

        @Override public void onBindViewHolder(QueueHolder holder, int position) {
            RedPacketItem item = items.get(position);
            boolean selected = isCurrent(item);
            holder.row.setBackgroundColor(selected ? SOFT_ORANGE : Color.WHITE);
            holder.state.setImageDrawable(new MediaIcon(selected ? MediaIcon.Kind.PLAY
                    : MediaIcon.Kind.NEXT, selected ? ORANGE : MUTED, dp(24)));
            holder.state.setColorFilter(selected ? ORANGE : Color.rgb(185, 188, 194));
            holder.state.setAlpha(selected ? 1f : 0.32f);
            holder.state.setContentDescription(selected ? "当前播放" : "队列内容");
            holder.title.setText(item.title);
            holder.title.setTextColor(selected ? ORANGE : INK);
            holder.title.setTypeface(Typeface.DEFAULT,
                    selected ? Typeface.BOLD : Typeface.NORMAL);
            holder.meta.setText((item.course.isBlank() ? "未识别课程" : item.course)
                    + (selected ? "  ·  当前条目" : ""));
            holder.meta.setTextColor(selected ? ORANGE : MUTED);
            holder.handle.setVisibility(managing ? View.VISIBLE : View.GONE);
            holder.remove.setVisibility(managing ? View.VISIBLE : View.GONE);
            holder.remove.setContentDescription("移除 " + item.title);
            holder.remove.setOnClickListener(v -> {
                int index = holder.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return;
                persistIfChanged();
                removeQueueItem(items.get(index));
                dialog.setContentView(buildPlaybackQueueSheet(dialog,
                        new ArrayList<>(displayedItems), true));
            });
            holder.handle.setContentDescription("按住拖动 " + item.title);
            holder.handle.setOnTouchListener(managing ? (view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && dragHelper != null) {
                    dragHelper.startDrag(holder);
                }
                return false;
            } : null);
            holder.row.setOnClickListener(managing ? null : v -> {
                int index = holder.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return;
                QueueStore.save(MainActivity.this, displayedItems, index);
                dialog.dismiss();
                playTitle(items.get(index).title);
            });
        }

        @Override public int getItemCount() {
            return items.size();
        }

        final class QueueHolder extends RecyclerView.ViewHolder {
            final LinearLayout row;
            final ImageView state;
            final TextView title;
            final TextView meta;
            final ImageView handle;
            final ImageButton remove;

            QueueHolder(View itemView, LinearLayout row, ImageView state,
                        TextView title, TextView meta, ImageView handle, ImageButton remove) {
                super(itemView);
                this.row = row;
                this.state = state;
                this.title = title;
                this.meta = meta;
                this.handle = handle;
                this.remove = remove;
            }
        }
    }

    private Set<String> removedQueueKeys() {
        return new LinkedHashSet<>(getSharedPreferences("ui_preferences", MODE_PRIVATE)
                .getStringSet("removed_queue_keys", Set.of()));
    }

    private void removeQueueItem(RedPacketItem removed) {
        int removedIndex = -1;
        for (int i = 0; i < displayedItems.size(); i++) {
            if (QueuePolicy.stableKey(removed).equals(QueuePolicy.stableKey(displayedItems.get(i)))) {
                removedIndex = i;
                break;
            }
        }
        if (removedIndex < 0) return;
        MediaController controller = dedaoController();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        String liveTitle = liveOfficialTitle();
        int currentIndex = displayedIndexForTitle(liveTitle);
        if (currentIndex < 0) {
            RedPacketItem current = QueueStore.current(this);
            currentIndex = current == null ? 0 : displayedIndexForTitle(current.title);
        }
        int nextIndex = QueuePolicy.indexAfterRemoval(currentIndex, removedIndex, displayedItems.size());
        android.content.SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean active = probe.getBoolean("companion_queue_active", false);
        boolean removesPlaying = removed.title.equals(liveTitle);
        boolean removesPending = removed.title.equals(probe.getString("direct_open_target_title", ""));
        if (removesPlaying || removesPending) {
            cancelPendingPlaybackSwitch();
            probe.edit().remove("direct_open_target_title").apply();
        }
        Set<String> removedKeys = removedQueueKeys();
        removedKeys.add(QueuePolicy.stableKey(removed));
        getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                .putStringSet("removed_queue_keys", removedKeys).apply();
        applyFilters();
        QueueStore.save(this, displayedItems, Math.max(0, nextIndex));
        if (displayedItems.isEmpty()) {
            cancelPendingPlaybackSwitch();
            probe.edit().remove("direct_open_target_title")
                    .putBoolean("companion_queue_active", false)
                    .putBoolean("companion_queue_suspended", false).apply();
            if (removesPlaying) applyPlaybackState(false);
            PlaybackGuardService.stop(this);
        } else if (removesPlaying) {
            if (playing) {
                // Removing the last item must not unexpectedly replay the preceding item.
                if (removedIndex < displayedItems.size()) {
                    playDirect(displayedItems.get(nextIndex), 1, active);
                } else {
                    suspendContinuousQueue();
                    applyPlaybackState(false);
                }
            } else {
                suspendContinuousQueue();
            }
        }
        updateNowPlaying();
        Toast.makeText(this, "已移出播放列表，可在列表底部恢复", Toast.LENGTH_SHORT).show();
    }

    private void persistEditedQueueOrder(List<RedPacketItem> reorderedVisibleItems) {
        if (reorderedVisibleItems == null || reorderedVisibleItems.isEmpty()) return;
        List<RedPacketItem> baseOrder = QueuePolicy.apply(
                allItems, "全部课程", false, sortOrder.getSelectedItemPosition() != 1);
        ArrayList<String> globalOrder = new ArrayList<>();
        if (sortOrder.getSelectedItemPosition() == 2) {
            globalOrder.addAll(loadCustomQueueOrder());
        }
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (RedPacketItem item : baseOrder) available.add(QueuePolicy.stableKey(item));
        globalOrder.removeIf(key -> !available.contains(key));
        for (String key : available) if (!globalOrder.contains(key)) globalOrder.add(key);

        ArrayList<String> reorderedKeys = new ArrayList<>();
        for (RedPacketItem item : reorderedVisibleItems) {
            reorderedKeys.add(QueuePolicy.stableKey(item));
        }
        saveCustomQueueOrder(QueuePolicy.mergeVisibleOrder(globalOrder, reorderedKeys));
        getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                .putInt("sort_order", 2)
                .apply();
        sortOrder.setSelection(2);
        applyFilters();
    }

    private List<String> loadCustomQueueOrder() {
        ArrayList<String> result = new ArrayList<>();
        String raw = getSharedPreferences("ui_preferences", MODE_PRIVATE)
                .getString("custom_queue_order", "[]");
        try {
            JSONArray values = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < values.length(); i++) {
                String key = values.optString(i, "");
                if (!key.isBlank() && !result.contains(key)) result.add(key);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void saveCustomQueueOrder(List<String> order) {
        JSONArray values = new JSONArray();
        for (String key : order) values.put(key);
        getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                .putString("custom_queue_order", values.toString())
                .apply();
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
        android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
        boolean notificationsDisabled = notifications != null && !notifications.areNotificationsEnabled();
        if (listener && !monitorConnected && !backgroundRestricted && !notificationsDisabled) {
            // The authorization is still valid. Rebinding is an internal recovery detail and
            // should stay silent instead of looking like another permission request.
            permissionCard.setVisibility(View.GONE);
            return;
        }
        if (monitorConnected && (!needsFallback || accessibility) && !backgroundRestricted && !notificationsDisabled) {
            permissionCard.setVisibility(View.GONE);
            return;
        }
        permissionCard.setVisibility(View.VISIBLE);
        String permissionTitle = notificationsDisabled && listener ? "显示连续播放通知" : backgroundRestricted ? "允许后台连续播放"
                : (!listener ? "开启连续播放权限"
                : (!monitorConnected ? "正在恢复连续播放监控" : "可选：开启兼容模式"));
        permissionCard.addView(text(permissionTitle, 14, INK, Typeface.BOLD), matchWrap());
        if (!listener) {
            TextView copy = text("开启播放监控后，播完会自动衔接下一条；通常只需授权一次。", 12, MUTED, Typeface.NORMAL);
            copy.setPadding(0, dp(4), 0, dp(8));
            permissionCard.addView(copy, matchWrap());
            permissionCard.addView(compactButton("开启连续播放", v -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")), false), matchWrap());
        } else if (notificationsDisabled) {
            TextView copy = text("允许显示连续播放的常驻状态。通知权限与“播放监控”是两个独立授权；拒绝后仍可操作播放。", 12, MUTED, Typeface.NORMAL);
            copy.setPadding(0, dp(4), 0, dp(8));
            permissionCard.addView(copy, matchWrap());
            permissionCard.addView(compactButton("允许播放通知", v -> requestPlaybackNotifications(), false), matchWrap());
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

    private void showPlaybackSettings() {
        String manufacturer = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        boolean xiaomi = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco");
        String guidance = xiaomi
                ? "小米 / HyperOS 需要分别检查：\n\n1. 应用信息 → 自启动：开启\n2. 耗电管理 → 省电策略：无限制\n3. 允许显示连续播放通知\n\n仅设置“无限制”仍可能被系统冻结。建议在最近任务中锁定伴侣，避免一键清理。允许后台运行可能增加耗电。"
                : "请允许伴侣后台运行，并显示连续播放通知。若息屏后停止，请在应用信息中检查省电策略和系统的后台启动设置。允许后台运行可能增加耗电。";
        new android.app.AlertDialog.Builder(this)
                .setTitle("连续播放设置")
                .setMessage(guidance + "\n\n播放监控权限与显示通知权限相互独立；设置后仍建议息屏试听一次。")
                .setPositiveButton("应用设置", (dialog, which) -> openApplicationDetails())
                .setNeutralButton("复制诊断", (dialog, which) -> copyDiagnostics())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void requestPlaybackNotifications() {
        android.content.SharedPreferences preferences = getSharedPreferences("ui_preferences", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && (!preferences.getBoolean("notification_permission_requested", false)
                    || shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 33);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 33) renderPermissions();
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

    private ImageButton mediaButton(String label, MediaIcon.Kind icon, View.OnClickListener click, boolean primary) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setImageDrawable(new MediaIcon(icon, primary ? Color.WHITE : INK, dp(primary ? 28 : 24)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x11000000),
                roundRect(primary ? ORANGE : Color.TRANSPARENT, 40), roundRect(Color.WHITE, 40)));
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

    private GradientDrawable roundTopRect(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        float radius = dp(radiusDp);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
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
