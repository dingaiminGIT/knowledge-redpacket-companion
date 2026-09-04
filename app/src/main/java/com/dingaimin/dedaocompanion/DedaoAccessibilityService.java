package com.dingaimin.dedaocompanion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

public final class DedaoAccessibilityService extends AccessibilityService {
    // Detail probing is intentionally disabled: opening an expired red-packet item can consume
    // the course's limited trial allowance. Expiry must be resolved from list data only.
    private static final boolean DETAIL_AUTOMATION_ENABLED = true;
    private static volatile DedaoAccessibilityService activeInstance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, RedPacketItem> scannedItems = new LinkedHashMap<>();
    private final LinkedHashSet<String> skippedExpiredKeys = new LinkedHashSet<>();
    private long lastAutoClick;
    private boolean scanning;
    private int scanSteps;
    private int scanWaitSteps;
    private boolean scanStartedReading;
    private int unchangedVisibleSteps;
    private int skippedExpiredItems;
    private int stepsWithoutNewActiveItems;
    private String lastVisibleFingerprint = "";
    private String playTarget = "";
    private int seekBackwardSteps;
    private int seekForwardSteps;
    private String lastSeekViewportFingerprint = "";
    private int unchangedSeekViewportPasses;
    private boolean pendingPlayScheduled;
    private boolean seekGestureInFlight;
    private boolean seekPassScheduled;
    private final Rect lastTargetBounds = new Rect();
    private int stableTargetChecks;
    private WindowManager windowManager;
    private View automationCover;
    private Bitmap automationCoverBitmap;
    private final Runnable coverFailSafe = () -> {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        if (probe.getBoolean("official_sync_active", false)
                || probe.getBoolean("official_sync_resume_after_seed", false)) {
            probe.edit()
                    .putBoolean("official_sync_active", false)
                    .putBoolean("official_sync_pending", false)
                    .putBoolean("official_sync_resume_after_seed", false)
                    .putString("official_queue_failed_fingerprint",
                            probe.getString("official_sync_fingerprint_pending", ""))
                    .putString("official_sync_status", "同步超时，已安全停止")
                    .apply();
            if (probe.getBoolean("official_sync_should_play", false)
                    || !probe.getBoolean("official_sync_was_playing", false)) {
                pauseDedaoSession();
            }
        }
        returnToCompanion();
        dismissAutomationCover();
    };

    private void markPlayStage(String stage, String title) {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        long startedAt = probe.getLong("play_timing_started_elapsed", 0L);
        long now = SystemClock.elapsedRealtime();
        if ("requested".equals(stage) || startedAt <= 0L) startedAt = now;
        probe.edit()
                .putLong("play_timing_started_elapsed", startedAt)
                .putLong("play_timing_" + stage, now - startedAt)
                .putString("play_timing_title", title == null ? "" : title)
                .putString("play_timing_last_stage", stage)
                .apply();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        if (getSharedPreferences("page_probe", MODE_PRIVATE).getBoolean("scan_requested", false)) {
            handler.postDelayed(this::beginScan, 1_000);
        }
        schedulePendingPlay(400);
        handler.postDelayed(this::verifyDetailAndPlay, 600);
    }

    static void requestScanNow() {
        DedaoAccessibilityService service = activeInstance;
        if (service != null) service.handler.postDelayed(service::beginScan, 250);
    }

    static void requestPlayTitle(Context context, String title) {
        requestPlayTitle(context, title, 0);
    }

    static void requestPlayTitle(Context context, String title, int sequenceDirection) {
        if (context == null || title == null || title.isBlank()) return;
        SharedPreferences probe = context.getSharedPreferences("page_probe", MODE_PRIVATE);
        if (!DETAIL_AUTOMATION_ENABLED || !isVerifiedActiveTitle(context, title)) {
            probe.edit()
                    .remove("pending_play_title")
                    .remove("pending_play_direction")
                    .putString("seek_status", "拒绝打开：该条目未经红包列表确认有效")
                    .apply();
            return;
        }
        // Persist first. App updates and AccessibilityService reconnects must not lose a safe,
        // already list-verified play request.
        probe.edit()
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
                .putString("seek_status", "已排队，等待定位：" + title)
                .apply();
        DedaoAccessibilityService service = activeInstance;
        if (service != null) {
            service.markPlayStage("requested", title);
            service.schedulePendingPlay(60);
        }
    }

    static void returnToListAndPlay(Context context, String title) {
        returnToListAndPlay(context, title, 0);
    }

    static void returnToListAndPlay(Context context, String title, int sequenceDirection) {
        requestPlayTitle(context, title, sequenceDirection);
        DedaoAccessibilityService service = activeInstance;
        if (!isVerifiedActiveTitle(context, title)) return;
        Runnable openList = () -> {
            try {
                Intent list = new Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://redPacket/list"));
                list.setPackage("com.luojilab.player");
                Context launcher = context instanceof Activity
                        ? context
                        : (service == null ? context : service);
                list.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                if (!(launcher instanceof Activity)) list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launcher.startActivity(list,
                        ActivityOptions.makeCustomAnimation(launcher, 0, 0).toBundle());
                if (service != null) {
                    service.markPlayStage("list_opened", title);
                    service.schedulePendingPlay(120);
                }
            } catch (Exception ignored) {
                if (service != null) service.dismissAutomationCover();
            }
        };
        if (service == null) {
            openList.run();
        } else {
            service.showAutomationCover(title);
            // The overlay is attached synchronously. Delaying the actual navigation only makes
            // transport controls feel unresponsive on fast devices.
            openList.run();
        }
    }

    static void dismissAutomationCoverAfter(long delayMillis) {
        DedaoAccessibilityService service = activeInstance;
        if (service != null) service.handler.postDelayed(service::dismissAutomationCover, delayMillis);
    }

    static void cancelPendingPlayback(Context context) {
        if (context == null) return;
        context.getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .remove("play_attempts")
                .remove("play_attempt_at")
                .apply();
        DedaoAccessibilityService service = activeInstance;
        if (service != null) {
            service.handler.post(() -> {
                service.playTarget = "";
                service.pendingPlayScheduled = false;
                service.seekGestureInFlight = false;
                service.seekPassScheduled = false;
                service.dismissAutomationCover();
            });
        }
    }

    static boolean probeOfficialQueue(Context context) {
        return probeOfficialQueue(context, false);
    }

    static boolean reorderOfficialQueueForProbe(Context context) {
        return probeOfficialQueue(context, true);
    }

    static boolean syncOfficialQueue(Context context, List<RedPacketItem> items) {
        return syncOfficialQueue(context, items, false);
    }

    static boolean syncOfficialQueue(Context context, List<RedPacketItem> items,
                                     boolean startPlayback) {
        DedaoAccessibilityService service = activeInstance;
        if (service == null || context == null || items == null || items.isEmpty()) return false;
        JSONArray titles = new JSONArray();
        for (RedPacketItem item : items) titles.put(item.title);
        String fingerprint = titles.toString();
        String currentTitle = service.currentDedaoTitle();
        boolean wasPlaying = service.isDedaoSessionPlaying();
        context.getSharedPreferences("page_probe", MODE_PRIVATE).edit()
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
                .putString("official_sync_start_title",
                        startPlayback ? items.get(0).title : currentTitle)
                .putBoolean("official_sync_was_playing", wasPlaying)
                .putBoolean("official_sync_should_play", startPlayback)
                .putString("official_sync_status", "正在同步得到播放顺序…")
                .commit();
        if (!wasPlaying) service.pauseDedaoSession();
        service.showAutomationCover("正在同步播放顺序");
        service.handler.removeCallbacks(service.coverFailSafe);
        service.handler.postDelayed(service.coverFailSafe, 60_000);
        boolean opened = service.openOfficialPlayer();
        if (opened && !wasPlaying) {
            service.handler.postDelayed(service::pauseDedaoSession, 80);
            service.handler.postDelayed(service::pauseDedaoSession, 250);
            service.handler.postDelayed(service::pauseDedaoSession, 600);
            service.handler.postDelayed(service::pauseDedaoSession, 1_200);
        }
        if (!opened) service.failOfficialQueueSync("没有找到得到播放器");
        return opened;
    }

    private String currentDedaoTitle() {
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, DedaoSessionListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if (!"com.luojilab.player".equals(controller.getPackageName())) continue;
                MediaMetadata metadata = controller.getMetadata();
                String title = metadata == null ? ""
                        : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                return title == null ? "" : title;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean isDedaoSessionPlaying() {
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, DedaoSessionListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if (!"com.luojilab.player".equals(controller.getPackageName())) continue;
                PlaybackState state = controller.getPlaybackState();
                return state != null && state.getState() == PlaybackState.STATE_PLAYING;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void pauseDedaoSession() {
        boolean submitted = false;
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, DedaoSessionListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if ("com.luojilab.player".equals(controller.getPackageName())) {
                    controller.getTransportControls().pause();
                    submitted = true;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (!submitted) OfficialPlayerControl.send(this, OfficialPlayerControl.PAUSE);
    }

    private void playDedaoSession() {
        boolean submitted = false;
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, DedaoSessionListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if ("com.luojilab.player".equals(controller.getPackageName())) {
                    controller.getTransportControls().play();
                    submitted = true;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (!submitted) OfficialPlayerControl.send(this, OfficialPlayerControl.PLAY);
    }

    private static boolean probeOfficialQueue(Context context, boolean reorder) {
        DedaoAccessibilityService service = activeInstance;
        if (service == null) return false;
        context.getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("queue_probe_pending", true)
                .putBoolean("queue_probe_active", true)
                .putBoolean("queue_probe_reorder", reorder)
                .commit();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://base/ddplayer"));
            intent.setPackage("com.luojilab.player");
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean clickExactText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        boolean clicked = false;
        for (AccessibilityNodeInfo match : matches) {
            CharSequence matchText = match.getText();
            CharSequence matchDescription = match.getContentDescription();
            if ((matchText == null || !text.contentEquals(matchText))
                    && (matchDescription == null || !text.contentEquals(matchDescription))) continue;
            AccessibilityNodeInfo node = match;
            while (node != null) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true;
                    break;
                }
                node = node.getParent();
            }
            if (clicked) break;
        }
        root.recycle();
        return clicked;
    }

    private boolean clickDescriptionStartingWith(String prefix) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean clicked = clickDescriptionStartingWith(root, prefix);
        root.recycle();
        return clicked;
    }

    private boolean clickDescriptionStartingWith(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return false;
        CharSequence description = node.getContentDescription();
        CharSequence text = node.getText();
        if ((description != null && description.toString().trim().startsWith(prefix))
                || (text != null && text.toString().trim().startsWith(prefix))) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null) {
                if (clickable.isClickable()
                        && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                clickable = clickable.getParent();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickDescriptionStartingWith(node.getChild(i), prefix)) return true;
        }
        return false;
    }

    private boolean openOfficialPlayer() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("igetapp://base/ddplayer"));
            intent.setPackage("com.luojilab.player");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void captureOfficialQueue() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        JSONArray items = new JSONArray();
        appendOfficialQueueItems(root, items);
        boolean reorder = getSharedPreferences("page_probe", MODE_PRIVATE)
                .getBoolean("queue_probe_reorder", false);
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("queue_probe_items", items.toString())
                .putLong("queue_probe_captured_at", System.currentTimeMillis())
                .putBoolean("queue_probe_active", reorder)
                .apply();
        if (root != null) root.recycle();
        if (reorder) {
            handler.postDelayed(() -> getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("queue_done_clicked", clickDescriptionStartingWith("完成")).apply(), 250);
            handler.postDelayed(() -> getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("queue_player_reopened", openOfficialPlayer()).apply(), 800);
            handler.postDelayed(() -> getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("queue_list_reopened", clickExactText("播放列表")).apply(), 1_600);
            handler.postDelayed(() -> getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("queue_first_clicked", clickDescriptionStartingWith("166｜")).apply(), 2_500);
            handler.postDelayed(this::returnToCompanion, 3_500);
            handler.postDelayed(() -> getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("queue_probe_active", false).apply(), 3_700);
        }
    }

    private void appendOfficialQueueItems(AccessibilityNodeInfo node, JSONArray items) {
        if (node == null) return;
        String id = node.getViewIdResourceName();
        CharSequence description = node.getContentDescription();
        boolean flutterQueueRow = description != null
                && description.toString().matches("(?s)^\\s*\\d+｜.*");
        if ((id != null && id.endsWith(":id/audioNameTextView") && node.getText() != null)
                || flutterQueueRow) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            org.json.JSONObject item = new org.json.JSONObject();
            try {
                String title = flutterQueueRow
                        ? description.toString().trim().split("\\n", 2)[0]
                        : node.getText().toString();
                item.put("title", title);
                item.put("left", bounds.left);
                item.put("top", bounds.top);
                item.put("right", bounds.right);
                item.put("bottom", bounds.bottom);
                items.put(item);
            } catch (org.json.JSONException ignored) {
            }
        } else if (id != null && id.endsWith(":id/sortAudioButton")) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            org.json.JSONObject item = new org.json.JSONObject();
            try {
                item.put("handle", true);
                item.put("left", bounds.left);
                item.put("top", bounds.top);
                item.put("right", bounds.right);
                item.put("bottom", bounds.bottom);
                items.put(item);
            } catch (org.json.JSONException ignored) {
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            appendOfficialQueueItems(node.getChild(i), items);
        }
    }

    private static final class OfficialQueueRow {
        final String title;
        final Rect bounds;

        OfficialQueueRow(String title, Rect bounds) {
            this.title = title;
            this.bounds = bounds;
        }
    }

    private Rect officialQueueRowBounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        AccessibilityNodeInfo parent = node.getParent();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        while (parent != null) {
            Rect parentBounds = new Rect();
            parent.getBoundsInScreen(parentBounds);
            if (parentBounds.width() >= screenWidth * 9 / 10
                    && parentBounds.height() >= dp(45) && parentBounds.height() <= dp(100)) {
                bounds.set(parentBounds);
                break;
            }
            parent = parent.getParent();
        }
        return bounds;
    }

    private void collectOfficialQueueRows(AccessibilityNodeInfo node, List<OfficialQueueRow> rows) {
        if (node == null) return;
        CharSequence description = node.getContentDescription();
        if (description != null && description.toString().matches("(?s)^\\s*\\d+｜.*")) {
            rows.add(new OfficialQueueRow(
                    description.toString().trim().split("\\n", 2)[0],
                    officialQueueRowBounds(node)));
        } else if (node.getText() != null
                && node.getText().toString().matches("(?s)^\\s*\\d+｜.*")) {
            rows.add(new OfficialQueueRow(
                    node.getText().toString().trim().split("\\n", 2)[0],
                    officialQueueRowBounds(node)));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectOfficialQueueRows(node.getChild(i), rows);
        }
    }

    private List<String> desiredOfficialTitles() {
        ArrayList<String> result = new ArrayList<>();
        String json = getSharedPreferences("page_probe", MODE_PRIVATE)
                .getString("official_sync_titles", "[]");
        try {
            JSONArray titles = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < titles.length(); i++) {
                String title = titles.optString(i, "").trim();
                if (!title.isEmpty()) result.add(title);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private List<OfficialQueueRow> currentOfficialQueueRows() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        ArrayList<OfficialQueueRow> rows = new ArrayList<>();
        collectOfficialQueueRows(root, rows);
        if (root != null) root.recycle();
        Collections.sort(rows, (left, right) -> Integer.compare(left.bounds.top, right.bounds.top));
        ArrayList<OfficialQueueRow> unique = new ArrayList<>();
        for (OfficialQueueRow row : rows) {
            boolean seen = false;
            for (OfficialQueueRow saved : unique) {
                if (saved.title.equals(row.title)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) unique.add(row);
        }
        return unique;
    }

    private void beginOfficialQueueSync() {
        handler.postDelayed(() -> clickLabel("播放列表"), 800);
        handler.postDelayed(() -> clickLabel("管理"), 1_600);
        handler.postDelayed(() -> syncOfficialQueueIndex(0), 2_500);
    }

    private boolean clickLabel(String label) {
        return clickDescriptionStartingWith(label) || clickExactText(label);
    }

    private boolean hasLabel(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if ((text != null && label.contentEquals(text))
                || (description != null && label.contentEquals(description))) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasLabel(node.getChild(i), label)) return true;
        }
        return false;
    }

    private boolean isOfficialQueueManagementVisible() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean visible = hasLabel(root, "完成")
                && (hasLabel(root, "管理") || hasLabel(root, "全选") || hasLabel(root, "移出"));
        if (root != null) root.recycle();
        return visible;
    }

    private void syncOfficialQueueIndex(int desiredIndex) {
        if (!isOfficialQueueManagementVisible()) {
            SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
            int waits = probe.getInt("official_sync_ui_waits", 0) + 1;
            probe.edit().putInt("official_sync_ui_waits", waits).apply();
            if (waits > 18) {
                failOfficialQueueSync("得到播放列表管理页未能打开");
                return;
            }
            clickLabel("播放列表");
            handler.postDelayed(() -> clickLabel("管理"), 450);
            handler.postDelayed(() -> syncOfficialQueueIndex(desiredIndex), 950);
            return;
        }
        SharedPreferences syncProbe = getSharedPreferences("page_probe", MODE_PRIVATE);
        if (!syncProbe.getBoolean("official_sync_queue_rewound", false)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo queue = findOfficialQueueScrollable(root);
            boolean moved = queue != null
                    && queue.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (root != null) root.recycle();
            int attempts = syncProbe.getInt("official_sync_rewind_attempts", 0) + 1;
            syncProbe.edit().putInt("official_sync_rewind_attempts", attempts).apply();
            if (moved && attempts < 24) {
                handler.postDelayed(() -> syncOfficialQueueIndex(desiredIndex), 280);
                return;
            }
            syncProbe.edit().putBoolean("official_sync_queue_rewound", true).apply();
            handler.postDelayed(() -> syncOfficialQueueIndex(desiredIndex), 180);
            return;
        }
        List<String> desired = desiredOfficialTitles();
        if (desired.isEmpty()) {
            failOfficialQueueSync("没有可同步的播放内容");
            return;
        }
        List<OfficialQueueRow> rows = currentOfficialQueueRows();
        JSONArray observed = new JSONArray();
        for (OfficialQueueRow row : rows) observed.put(row.title);
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("official_sync_observed", observed.toString())
                .apply();
        for (String title : desired) {
            boolean found = false;
            for (OfficialQueueRow row : rows) if (title.equals(row.title)) found = true;
            if (!found) {
                seedMissingOfficialTitle(title);
                return;
            }
        }
        ArrayList<String> nativeOrder = new ArrayList<>();
        // Keep non-filtered native items before the companion queue. Playback starts at the
        // first desired item, so forward/continuous playback reaches the end without ever
        // crossing into an excluded or expired entry.
        for (OfficialQueueRow row : rows) {
            if (!desired.contains(row.title)) nativeOrder.add(row.title);
        }
        nativeOrder.addAll(desired);
        if (desiredIndex >= nativeOrder.size()) {
            for (int i = 0; i < nativeOrder.size(); i++) {
                if (i >= rows.size() || !nativeOrder.get(i).equals(rows.get(i).title)) {
                    failOfficialQueueSync("得到播放顺序校验未通过");
                    return;
                }
            }
            SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
            String currentTitle = probe.getString("official_sync_start_title", "");
            finishOfficialQueueSync(desired.contains(currentTitle) ? currentTitle : desired.get(0));
            return;
        }
        String wanted = nativeOrder.get(desiredIndex);
        int sourceIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (wanted.equals(rows.get(i).title)) {
                sourceIndex = i;
                break;
            }
        }
        if (sourceIndex == desiredIndex) {
            syncOfficialQueueIndex(desiredIndex + 1);
            return;
        }
        if (sourceIndex < 0 || desiredIndex >= rows.size()) {
            failOfficialQueueSync("无法定位得到播放顺序");
            return;
        }
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        int moves = probe.getInt("official_sync_moves", 0) + 1;
        probe.edit().putInt("official_sync_moves", moves).apply();
        if (moves > Math.max(12, rows.size() * 2)) {
            failOfficialQueueSync("得到播放顺序多次调整仍未到位");
            return;
        }
        probe.edit().putString("official_sync_last_move",
                sourceIndex + "->" + desiredIndex + ":" + wanted).apply();
        dragOfficialQueueRow(rows.get(sourceIndex), rows.get(desiredIndex),
                () -> handler.postDelayed(() -> syncOfficialQueueIndex(0), 900));
    }

    private AccessibilityNodeInfo findOfficialQueueScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            String id = node.getViewIdResourceName();
            CharSequence className = node.getClassName();
            if ((id != null && id.endsWith(":id/dl_listview"))
                    || (className != null
                    && "android.widget.ListView".contentEquals(className))) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return null;
    }

    private void seedMissingOfficialTitle(String title) {
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("official_sync_active", false)
                .putBoolean("official_sync_resume_after_seed", true)
                .putString("official_sync_seed_title", title)
                .putString("official_sync_status", "正在把有效红包加入得到播放列表…")
                .apply();
        clickDescriptionStartingWith("完成");
        handler.postDelayed(() -> returnToListAndPlay(this, title), 450);
    }

    private void dragOfficialQueueRow(OfficialQueueRow source, OfficialQueueRow target,
                                      Runnable completed) {
        float x = Math.min(getResources().getDisplayMetrics().widthPixels - dp(20),
                source.bounds.right - dp(24));
        float y = source.bounds.centerY();
        Path holdPath = new Path();
        holdPath.moveTo(x, y);
        holdPath.lineTo(x + 1, y);
        GestureDescription.StrokeDescription hold =
                new GestureDescription.StrokeDescription(holdPath, 0, 500, true);
        GestureDescription first = new GestureDescription.Builder().addStroke(hold).build();
        if (!dispatchGesture(first, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                Path dragPath = new Path();
                dragPath.moveTo(x + 1, y);
                float destination = source.bounds.top > target.bounds.top
                        ? target.bounds.centerY() - dp(6)
                        : target.bounds.centerY() + dp(6);
                dragPath.lineTo(x + 1, destination);
                GestureDescription second = new GestureDescription.Builder()
                        .addStroke(hold.continueStroke(dragPath, 0, 900, false))
                        .build();
                if (!dispatchGesture(second, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gestureDescription) {
                        completed.run();
                    }

                    @Override public void onCancelled(GestureDescription gestureDescription) {
                        failOfficialQueueSync("得到取消了播放顺序调整");
                    }
                }, null)) failOfficialQueueSync("无法提交播放顺序调整");
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                failOfficialQueueSync("得到取消了播放顺序调整");
            }
        }, null)) failOfficialQueueSync("无法开始播放顺序调整");
    }

    private void finishOfficialQueueSync(String firstTitle) {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        String fingerprint = probe.getString("official_sync_fingerprint_pending", "");
        probe.edit().putString("official_sync_status", "正在应用播放顺序…").apply();
        handler.postDelayed(() -> clickDescriptionStartingWith("完成"), 200);
        handler.postDelayed(this::openOfficialPlayer, 750);
        handler.postDelayed(() -> clickLabel("播放列表"), 1_550);
        handler.postDelayed(() -> {
            boolean clicked = clickDescriptionStartingWith(firstTitle);
            // Some OEM/WebView combinations expose the player list later than others. The
            // exported player index command is independent of layout and confirmation below
            // still requires the real MediaSession title to equal firstTitle.
            boolean skipped = clicked || OfficialPlayerControl.skip(this, 0);
            if (!skipped) {
                failOfficialQueueSync("播放顺序已调整，但首条启动失败");
                return;
            }
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("official_sync_status", "正在确认实际播放内容…")
                    .apply();
            handler.postDelayed(() -> confirmOfficialQueueStart(firstTitle, fingerprint, 0), 450);
        }, 2_450);
    }

    private void confirmOfficialQueueStart(String firstTitle, String fingerprint, int attempt) {
        SharedPreferences preferences = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean wasPlaying = preferences.getBoolean("official_sync_was_playing", false);
        boolean shouldPlay = preferences.getBoolean("official_sync_should_play", false);
        String actualTitle = currentDedaoTitle();
        if (firstTitle.equals(actualTitle)) {
            preferences.edit()
                    .putBoolean("official_sync_active", false)
                    .putBoolean("official_sync_pending", false)
                    .putString("official_queue_fingerprint", fingerprint)
                    .putString("official_sync_status", "播放顺序已同步")
                    .remove("official_queue_failed_fingerprint")
                    .putBoolean("companion_queue_active", true)
                    .putBoolean("direct_skip_unreliable", false)
                    .apply();
            if (shouldPlay) {
                playDedaoSession();
            } else if (!wasPlaying) {
                pauseDedaoSession();
            }
            handler.postDelayed(this::returnToCompanion, 650);
            handler.postDelayed(this::dismissAutomationCover, 900);
            return;
        }
        if (!wasPlaying) pauseDedaoSession();
        if (attempt >= 10) {
            failOfficialQueueSync("播放顺序已调整，但实际首条未确认");
            return;
        }
        if (attempt == 3 || attempt == 7) {
            openOfficialPlayer();
            handler.postDelayed(() -> clickLabel("播放列表"), 450);
            handler.postDelayed(() -> clickDescriptionStartingWith(firstTitle), 850);
        } else {
            clickDescriptionStartingWith(firstTitle);
        }
        handler.postDelayed(
                () -> confirmOfficialQueueStart(firstTitle, fingerprint, attempt + 1), 650);
    }

    private void failOfficialQueueSync(String reason) {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        probe.edit()
                .putBoolean("official_sync_active", false)
                .putBoolean("official_sync_pending", false)
                .putBoolean("official_sync_resume_after_seed", false)
                .putString("official_queue_failed_fingerprint",
                        probe.getString("official_sync_fingerprint_pending", ""))
                .putString("official_sync_status", reason)
                .apply();
        if (probe.getBoolean("official_sync_should_play", false)
                || !probe.getBoolean("official_sync_was_playing", false)) {
            pauseDedaoSession();
        }
        returnToCompanion();
        dismissAutomationCover();
    }

    private void reorder166Before950ForProbe() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<OfficialQueueRow> rows = new ArrayList<>();
        collectOfficialQueueRows(root, rows);
        if (root != null) root.recycle();
        OfficialQueueRow source = null;
        OfficialQueueRow target = null;
        for (OfficialQueueRow row : rows) {
            if (row.title.startsWith("166｜")) source = row;
            if (row.title.startsWith("950｜")) target = row;
        }
        if (source == null || target == null || source.bounds.top < target.bounds.top) return;
        OfficialQueueRow targetRow = target;
        float x = Math.min(source.bounds.right - dp(24), 1020);
        float y = source.bounds.centerY();
        Path holdPath = new Path();
        holdPath.moveTo(x, y);
        holdPath.lineTo(x + 1, y);
        GestureDescription.StrokeDescription hold =
                new GestureDescription.StrokeDescription(holdPath, 0, 500, true);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(hold)
                .build();
        boolean submitted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Path dragPath = new Path();
                dragPath.moveTo(x + 1, y);
                dragPath.lineTo(x + 1, targetRow.bounds.top - dp(12));
                GestureDescription drag = new GestureDescription.Builder()
                        .addStroke(hold.continueStroke(dragPath, 0, 900, false))
                        .build();
                boolean continued = dispatchGesture(drag, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                                .putString("queue_reorder_result", "completed")
                                .apply();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                                .putString("queue_reorder_result", "drag_cancelled")
                                .apply();
                    }
                }, null);
                if (!continued) {
                    getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                            .putString("queue_reorder_result", "drag_rejected")
                            .apply();
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putString("queue_reorder_result", "hold_cancelled")
                        .apply();
            }
        }, null);
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("queue_reorder_submitted", submitted)
                .putString("queue_reorder_source", source.title)
                .putString("queue_reorder_target", target.title)
                .apply();
    }

    private void showAutomationCover(String title) {
        dismissAutomationCover();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        Bitmap snapshot = MainActivity.captureCurrentScreen();
        if (snapshot != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(snapshot);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setBackgroundColor(Color.rgb(250, 250, 250));
            automationCoverBitmap = snapshot;
            attachAutomationCover(image);
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(78), dp(28), dp(36));
        root.setBackgroundColor(Color.rgb(246, 247, 249));

        TextView brand = overlayText("知识红包伴侣", 27, Color.rgb(29, 31, 36), Typeface.BOLD);
        root.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = overlayText("正在准备播放", 14, Color.rgb(255, 106, 42), Typeface.BOLD);
        status.setPadding(0, dp(42), 0, dp(12));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(24), dp(22), dp(24));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.WHITE);
        panelBackground.setCornerRadius(dp(22));
        panel.setBackground(panelBackground);

        TextView titleView = overlayText(title, 20, Color.rgb(29, 31, 36), Typeface.BOLD);
        titleView.setLineSpacing(0f, 1.12f);
        panel.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, dp(22), 0, 0);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 106, 42)));
        progressRow.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView progressCopy = overlayText("正在安全切换，马上回来", 14,
                Color.rgb(112, 116, 126), Typeface.NORMAL);
        progressCopy.setPadding(dp(12), 0, 0, 0);
        progressRow.addView(progressCopy, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(progressRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(panel, panelParams);

        TextView hint = overlayText("播放授权由得到在后台完成，全程不会打开过期内容", 13,
                Color.rgb(112, 116, 126), Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(20), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        attachAutomationCover(root);
    }

    private void attachAutomationCover(View root) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.windowAnimations = 0;
        params.dimAmount = 0f;
        try {
            windowManager.addView(root, params);
            automationCover = root;
            handler.removeCallbacks(coverFailSafe);
            handler.postDelayed(coverFailSafe, 35_000);
        } catch (Exception ignored) {
            automationCover = null;
            if (automationCoverBitmap != null) automationCoverBitmap.recycle();
            automationCoverBitmap = null;
        }
    }

    private TextView overlayText(String value, int sizeSp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void dismissAutomationCover() {
        handler.removeCallbacks(coverFailSafe);
        if (automationCover != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(automationCover);
            } catch (Exception ignored) {
            }
        }
        automationCover = null;
        if (automationCoverBitmap != null) automationCoverBitmap.recycle();
        automationCoverBitmap = null;
    }

    private void returnToCompanion() {
        try {
            Intent companion = new Intent(this, MainActivity.class);
            companion.putExtra("action", "now_playing");
            companion.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(companion,
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null
                || !"com.luojilab.player".contentEquals(event.getPackageName())) return;
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        if (probe.getBoolean("official_sync_active", false)) {
            if (probe.getBoolean("official_sync_pending", false)) {
                probe.edit().putBoolean("official_sync_pending", false).apply();
                beginOfficialQueueSync();
            }
            return;
        }
        if (probe.getBoolean("queue_probe_active", false)) {
            if (probe.getBoolean("queue_probe_pending", false)) {
                probe.edit().putBoolean("queue_probe_pending", false).apply();
                boolean reorder = probe.getBoolean("queue_probe_reorder", false);
                handler.postDelayed(() -> clickExactText("播放列表"), 800);
                handler.postDelayed(() -> clickExactText("管理"), 1_600);
                if (reorder) {
                    handler.postDelayed(this::reorder166Before950ForProbe, 2_500);
                    handler.postDelayed(this::captureOfficialQueue, 4_000);
                } else {
                    handler.postDelayed(this::captureOfficialQueue, 2_500);
                }
            }
            return;
        }
        if (scanning) handler.postDelayed(this::capturePageStructure, 120);
        if (!playTarget.isEmpty()) scheduleSeekPass(40);
        else schedulePendingPlay(40);
        if (getSharedPreferences("page_probe", MODE_PRIVATE).getBoolean("scan_requested", false) && !scanning) {
            handler.postDelayed(this::beginScan, 300);
        }
        if (!DETAIL_AUTOMATION_ENABLED) return;
        handler.postDelayed(this::verifyDetailAndPlay, 60);
    }

    private void beginScan() {
        if (scanning) return;
        scanning = true;
        scanSteps = 0;
        scanWaitSteps = 0;
        scanStartedReading = false;
        unchangedVisibleSteps = 0;
        skippedExpiredItems = 0;
        stepsWithoutNewActiveItems = 0;
        lastVisibleFingerprint = "";
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("scan_status", "等待得到知识红包列表…")
                .apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }
        scanPage();
    }

    private void beginSeek(String title) {
        if (title == null || title.isBlank() || !isVerifiedActiveTitle(this, title)) return;
        playTarget = title;
        seekBackwardSteps = 0;
        seekForwardSteps = 0;
        lastSeekViewportFingerprint = "";
        unchangedSeekViewportPasses = 0;
        stableTargetChecks = 0;
        lastTargetBounds.setEmpty();
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("seek_status", "开始定位：" + title)
                .apply();
        markPlayStage("seek_started", title);
        seekAndTapTarget();
    }

    private void seekAndTapTarget() {
        if (playTarget.isEmpty() || seekGestureInFlight) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"com.luojilab.player".contentEquals(root.getPackageName())) {
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("seek_status", "等待得到列表出现，尝试 "
                            + (seekBackwardSteps + seekForwardSteps + 1))
                    .apply();
            // Retry through short app transitions. If the phone is locked, keep the target and
            // resume from the next Dedao accessibility event after the user unlocks.
            if (++seekBackwardSteps < 60) scheduleSeekPass(100);
            else failSeekTarget("知识红包列表一直没有出现");
            return;
        }

        if (!isRedPacketList(root)) {
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("seek_status", "等待知识红包列表稳定出现")
                    .apply();
            if (++seekBackwardSteps < 60) scheduleSeekPass(100);
            else failSeekTarget("知识红包列表一直没有稳定出现");
            return;
        }

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        Rect viewport = new Rect(0, dp(72),
                getResources().getDisplayMetrics().widthPixels, screenHeight - dp(48));
        if (scrollable != null) scrollable.getBoundsInScreen(viewport);
        List<AccessibilityNodeInfo> matches = findNodesWithExactText(root, playTarget);
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("seek_status", "已在得到列表查找标题，匹配 " + matches.size() + " 个")
                .putInt("seek_last_match_count", matches.size())
                .apply();
        if (matches.isEmpty() && seekBackwardSteps == 0 && seekForwardSteps == 0) {
            capturePageStructure();
        }
        boolean targetAboveViewport = false;
        boolean targetBelowViewport = false;
        for (AccessibilityNodeInfo node : matches) {
            if (node.getText() == null || !playTarget.contentEquals(node.getText())) continue;
            String viewId = node.getViewIdResourceName();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("seek_match_bounds", bounds.toShortString())
                    .putString("seek_match_view_id", viewId == null ? "" : viewId)
                    .apply();
            if (viewId != null && !viewId.isEmpty()) continue;
            if (!isCurrentlyActiveEpisode(node, playTarget)) {
                cancelExpiredPlaybackAndRefresh(playTarget);
                return;
            }
            if (bounds.bottom <= viewport.top + dp(8)) {
                targetAboveViewport = true;
                continue;
            }
            if (bounds.top >= viewport.bottom - dp(8)) {
                targetBelowViewport = true;
                continue;
            }
            if (bounds.height() <= 0) continue;
            // The WebView row has no clickable accessibility action. Sample the exact title
            // twice at the same bounds so a list-opening animation cannot move another row under
            // the gesture.
            if (!bounds.equals(lastTargetBounds)) {
                lastTargetBounds.set(bounds);
                stableTargetChecks = 1;
                scheduleSeekPass(80);
                return;
            }
            if (++stableTargetChecks < 2) {
                scheduleSeekPass(450);
                return;
            }
            String tappedTitle = playTarget;
            if (performSemanticClick(node)) {
                playTarget = "";
                lastAutoClick = 0L;
                completeTitleTap(tappedTitle, true);
                return;
            }
            Path path = new Path();
            path.moveTo(bounds.centerX(), bounds.centerY());
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                    .build();
            seekGestureInFlight = true;
            if (dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    seekGestureInFlight = false;
                    playTarget = "";
                    lastAutoClick = 0L;
                    completeTitleTap(tappedTitle, false);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    seekGestureInFlight = false;
                    getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                            .putString("seek_status", "点击被系统取消，正在重试")
                            .apply();
                    scheduleSeekPass(100);
                }
            }, null)) {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putString("seek_status", "已提交点击：" + tappedTitle)
                        .apply();
                return;
            }
            seekGestureInFlight = false;
        }

        if (scrollable == null && seekBackwardSteps + seekForwardSteps < 20) {
            seekBackwardSteps++;
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("seek_status", "红包列表仍在加载，正在重试")
                    .apply();
            scheduleSeekPass(100);
            return;
        }
        if (scrollable == null) {
            failSeekTarget("没有找到可滚动的知识红包列表");
            return;
        }

        // Dedao's WebView keeps the requested title in the accessibility tree even just outside
        // the viewport. Follow that coordinate directly so a large page jump cannot skip it.
        if (targetAboveViewport || targetBelowViewport) {
            boolean forward = targetBelowViewport && !targetAboveViewport;
            if (seekBackwardSteps + seekForwardSteps > 80) {
                failSeekTarget("多次滚动仍未能显示标题");
                return;
            }
            if (forward) seekForwardSteps++;
            else seekBackwardSteps++;
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("seek_status", forward
                            ? "标题在下方，正在逐页靠近…"
                            : "标题在上方，正在逐页返回…")
                    .apply();
            if (!dispatchSeekScroll(scrollable, forward)) {
                failSeekTarget("知识红包列表无法滚动到标题");
            }
            return;
        }

        String viewportFingerprint = visibleEpisodeFingerprint(root);
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putInt("seek_backward_steps", seekBackwardSteps)
                .putInt("seek_forward_steps", seekForwardSteps)
                .putString("seek_viewport_fingerprint", viewportFingerprint)
                .apply();
        if (seekForwardSteps == 0) {
            boolean unchanged = !viewportFingerprint.isEmpty()
                    && viewportFingerprint.equals(lastSeekViewportFingerprint);
            unchangedSeekViewportPasses = unchanged ? unchangedSeekViewportPasses + 1 : 0;
            if (unchangedSeekViewportPasses < 2 && seekBackwardSteps < 50) {
                lastSeekViewportFingerprint = viewportFingerprint;
                seekBackwardSteps++;
                if (dispatchSeekScroll(scrollable, false)) {
                    getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                            .putString("seek_status", "正在回到红包列表顶部…")
                            .apply();
                    return;
                }
            }
            if (seekBackwardSteps >= 50 && unchangedSeekViewportPasses < 2) {
                failSeekTarget("无法回到知识红包列表顶部");
                return;
            }
            // A downward swipe leaving the same visible episode rows means the newest entries
            // are now at the top. Start the forward pass from this exact viewport.
            seekForwardSteps = 1;
            lastSeekViewportFingerprint = "";
            unchangedSeekViewportPasses = 0;
            scheduleSeekPass(80);
            return;
        }

        boolean unchanged = !viewportFingerprint.isEmpty()
                && viewportFingerprint.equals(lastSeekViewportFingerprint);
        unchangedSeekViewportPasses = unchanged ? unchangedSeekViewportPasses + 1 : 0;
        if (seekForwardSteps > 80 || unchangedSeekViewportPasses >= 2) {
            failSeekTarget("没有定位到标题");
            return;
        }
        lastSeekViewportFingerprint = viewportFingerprint;
        seekForwardSteps++;
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("seek_status", "已从列表顶部开始向下查找…")
                .apply();
        if (!dispatchSeekScroll(scrollable, true)) {
            failSeekTarget("知识红包列表无法继续滚动");
        }
    }

    private boolean dispatchSeekScroll(AccessibilityNodeInfo scrollable, boolean forward) {
        if (forward && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            scheduleSeekPass(450);
            return true;
        }
        Rect bounds = new Rect();
        scrollable.getBoundsInScreen(bounds);
        float x = bounds.centerX();
        // Use fractions of the actual scrollable viewport so this works across screen sizes and
        // densities. The lower endpoint stays above a possible persistent mini-player.
        float upper = bounds.top + bounds.height() * 0.32f;
        float lower = bounds.top + bounds.height() * 0.68f;
        if (lower <= upper) {
            boolean moved = scrollable.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (moved) scheduleSeekPass(220);
            return moved;
        }
        Path swipe = new Path();
        swipe.moveTo(x, forward ? lower : upper);
        swipe.lineTo(x, forward ? upper : lower);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(swipe, 0, 300))
                .build();
        seekGestureInFlight = true;
        boolean submitted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                seekGestureInFlight = false;
                scheduleSeekPass(350);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                seekGestureInFlight = false;
                scheduleSeekPass(350);
            }
        }, null);
        if (!submitted) seekGestureInFlight = false;
        return submitted;
    }

    private void scheduleSeekPass(long delayMillis) {
        if (seekPassScheduled || playTarget.isEmpty()) return;
        seekPassScheduled = true;
        handler.postDelayed(() -> {
            seekPassScheduled = false;
            seekAndTapTarget();
        }, delayMillis);
    }

    private String visibleEpisodeFingerprint(AccessibilityNodeInfo root) {
        StringBuilder fingerprint = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (looksLikeEpisode(node)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.height() > 0 && bounds.bottom > dp(72) && bounds.top < screenHeight) {
                    fingerprint.append(directText(node.getChild(0))).append('|');
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return fingerprint.toString();
    }

    private void failSeekTarget(String reason) {
        String target = playTarget;
        playTarget = "";
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean resumeQueueSync = probe.getBoolean("official_sync_resume_after_seed", false);
        probe.edit()
                .putString("seek_status", reason + "：" + target)
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .apply();
        if (resumeQueueSync) {
            failOfficialQueueSync("无法把有效红包加入得到播放列表：" + target);
        } else {
            returnToCompanion();
            dismissAutomationCover();
        }
    }

    private boolean performSemanticClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            try {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            } catch (Exception ignored) {
            }
            current = current.getParent();
        }
        return false;
    }

    private void completeTitleTap(String title, boolean semantic) {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        probe.edit()
                .putString("seek_status", "已点击列表标题，等待核对详情：" + title)
                .putString("expected_detail_title", title)
                .putLong("expected_detail_started_at", System.currentTimeMillis())
                .putBoolean("title_click_semantic", semantic)
                .remove("pending_play_title")
                .remove("pending_play_direction")
                .apply();
        markPlayStage(semantic ? "semantic_tapped" : "title_tapped", title);
        handler.postDelayed(this::verifyDetailAndPlay, 60);
    }

    private boolean isCurrentlyActiveEpisode(AccessibilityNodeInfo titleNode, String title) {
        AccessibilityNodeInfo current = titleNode;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (looksLikeEpisode(current)
                    && title.equals(directText(current.getChild(0)))) {
                return hasActiveRedPacketState(directText(current.getChild(1)));
            }
            current = current.getParent();
        }
        return false;
    }

    private void cancelExpiredPlaybackAndRefresh(String title) {
        playTarget = "";
        seekGestureInFlight = false;
        lastTargetBounds.setEmpty();
        stableTargetChecks = 0;
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        int sequenceDirection = probe.getInt("pending_play_direction", 0);
        probe.edit()
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
                .apply();
        Toast.makeText(this, "该知识红包已过期，未进入详情", Toast.LENGTH_LONG).show();
        scanning = false;
        resetListToTopThenScan(0);
    }

    private void resetListToTopThenScan(int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo scrollable = root == null ? null : findScrollable(root);
        boolean moved = scrollable != null
                && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (moved && attempt < 16) {
            handler.postDelayed(() -> resetListToTopThenScan(attempt + 1), 120);
            return;
        }
        beginScan();
    }

    private List<AccessibilityNodeInfo> findNodesWithExactText(AccessibilityNodeInfo root, String text) {
        ArrayList<AccessibilityNodeInfo> matches = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            CharSequence value = node.getText();
            if (value != null && text.contentEquals(value)) matches.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return matches;
    }

    private List<AccessibilityNodeInfo> findNodesWithExactLabel(AccessibilityNodeInfo root, String label) {
        ArrayList<AccessibilityNodeInfo> matches = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            if ((text != null && label.contentEquals(text))
                    || (description != null && label.contentEquals(description))) {
                matches.add(node);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return matches;
    }

    private boolean isRedPacketList(AccessibilityNodeInfo root) {
        return !findNodesWithExactText(root, "知识红包领取的内容").isEmpty();
    }

    private void scanPage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"com.luojilab.player".contentEquals(root.getPackageName())
                || !isRedPacketList(root)) {
            if (++scanWaitSteps <= 20) {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putString("scan_status", "正在等待得到红包列表出现…")
                        .apply();
                handler.postDelayed(this::scanPage, 500);
            } else {
                abortScanPreservingCache("同步未完成：没有看到得到红包列表");
            }
            return;
        }

        if (!scanStartedReading) {
            scanStartedReading = true;
            scanSteps = 0;
            unchangedVisibleSteps = 0;
            skippedExpiredItems = 0;
            stepsWithoutNewActiveItems = 0;
            lastVisibleFingerprint = "";
            scannedItems.clear();
            skippedExpiredKeys.clear();
        }

        int activeBefore = scannedItems.size();
        ScanCursor cursor = new ScanCursor();
        collectStructuredItems(root, cursor);
        if (cursor.visibleFingerprint.length() == 0 && scanSteps < 10) {
            scanSteps++;
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("scan_status", "红包列表正在加载…")
                    .apply();
            handler.postDelayed(this::scanPage, 500);
            return;
        }
        skippedExpiredItems = skippedExpiredKeys.size();
        if (scannedItems.size() == activeBefore) stepsWithoutNewActiveItems++;
        else stepsWithoutNewActiveItems = 0;
        String fingerprint = cursor.visibleFingerprint.toString();
        if (!fingerprint.isEmpty() && fingerprint.equals(lastVisibleFingerprint)) unchangedVisibleSteps++;
        else unchangedVisibleSteps = 0;
        lastVisibleFingerprint = fingerprint;
        scanSteps++;
        saveScanProgress("正在确认有效内容，已找到 " + scannedItems.size() + " 条…");

        // Claims are newest-first. Once an expired boundary has been seen and the next pass adds
        // no active item, return immediately instead of browsing older expired history.
        if (!scannedItems.isEmpty() && skippedExpiredItems > 0 && stepsWithoutNewActiveItems >= 1) {
            finishScan("同步完成");
            return;
        }

        AccessibilityNodeInfo scrollable = findScrollable(root);
        boolean moved = scrollable != null
                && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (!moved || unchangedVisibleSteps >= 3 || scanSteps >= 80) {
            finishScan("同步完成");
            return;
        }
        handler.postDelayed(this::scanPage, 650);
    }

    private void collectStructuredItems(AccessibilityNodeInfo node, ScanCursor cursor) {
        if (looksLikeCourseHeader(node)) {
            cursor.course = directText(node.getChild(1));
        } else if (looksLikeEpisode(node)) {
            String title = directText(node.getChild(0));
            String meta = directText(node.getChild(1));
            String course = cursor.course == null ? "未识别课程" : cursor.course;
            String key = course + "\u0000" + title;
            if (hasActiveRedPacketState(meta)) {
                if (!scannedItems.containsKey(key)) {
                    long pageOrder = 1_000_000L - scannedItems.size();
                    boolean completed = meta.contains("已学完") || meta.contains("已完成")
                            || meta.matches(".*已学\\s*100%.*");
                    scannedItems.put(key, new RedPacketItem(
                            key, 65, course, title, "", pageOrder, completed));
                }
            } else {
                skippedExpiredKeys.add(key);
            }
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.height() > 0 && bounds.top < getResources().getDisplayMetrics().heightPixels
                    && bounds.bottom > 0) cursor.visibleFingerprint.append(title).append('|');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectStructuredItems(child, cursor);
        }
    }

    private boolean looksLikeCourseHeader(AccessibilityNodeInfo node) {
        if (node.getChildCount() < 3) return false;
        AccessibilityNodeInfo first = node.getChild(0);
        AccessibilityNodeInfo second = node.getChild(1);
        if (first == null || second == null) return false;
        String firstText = directText(first);
        String secondText = directText(second);
        return firstText.isEmpty() && !secondText.isEmpty() && first.getChildCount() > 0
                && !looksLikeDuration(secondText);
    }

    private boolean looksLikeEpisode(AccessibilityNodeInfo node) {
        if (node.getChildCount() < 2) return false;
        String title = directText(node.getChild(0));
        String meta = directText(node.getChild(1));
        return !title.isEmpty() && title.length() > 2 && looksLikeDuration(meta);
    }

    private boolean looksLikeDuration(String text) {
        return text != null && text.matches(".*\\d+分\\d+秒.*");
    }

    static boolean hasActiveRedPacketState(String text) {
        if (text == null) return false;
        // On the red-packet list, active rights show a learning state after the duration.
        // Expired claims show duration only. This is deliberately a list-only check: never
        // open a detail page to probe expiry because that can consume a normal trial allowance.
        return text.contains("未学习") || text.contains("已学")
                || text.contains("学习中") || text.contains("已完成");
    }

    private static boolean isVerifiedActiveTitle(Context context, String title) {
        if (context == null || title == null || title.isBlank()) return false;
        String json = context.getSharedPreferences("page_probe", MODE_PRIVATE)
                .getString("scan_items", "[]");
        try {
            JSONArray items = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                if (item != null && title.equals(item.optString("title"))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void schedulePendingPlay(long delayMillis) {
        if (pendingPlayScheduled || !playTarget.isEmpty()) return;
        String expected = getSharedPreferences("page_probe", MODE_PRIVATE)
                .getString("expected_detail_title", "");
        if (expected != null && !expected.isBlank()) return;
        pendingPlayScheduled = true;
        handler.postDelayed(() -> {
            pendingPlayScheduled = false;
            if (!playTarget.isEmpty()) return;
            SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
            String title = probe.getString("pending_play_title", "");
            if (title == null || title.isBlank()) return;
            if (!isVerifiedActiveTitle(this, title)) {
                probe.edit()
                        .remove("pending_play_title")
                        .remove("pending_play_direction")
                        .putString("seek_status", "已取消：待播条目不在有效红包队列中")
                        .apply();
                return;
            }
            beginSeek(title);
        }, delayMillis);
    }

    private String directText(AccessibilityNodeInfo node) {
        if (node == null || node.getText() == null) return "";
        return node.getText().toString().trim();
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (node.isScrollable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return null;
    }

    private void saveScanProgress(String status) {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, RedPacketItem> entry : scannedItems.entrySet()) {
            array.put(entry.getValue().toJson());
        }
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putString("scan_items", array.toString())
                .putInt("scan_count", scannedItems.size())
                .putInt("scan_expired_skipped", skippedExpiredItems)
                .putString("scan_status", status)
                .apply();
    }

    private void finishScan(String status) {
        saveScanProgress(status);
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean returnToApp = probe.getBoolean("scan_return_to_app", false);
        String rejectedTitle = probe.getString("scan_rejected_title", "");
        int resumeDirection = probe.getInt("scan_resume_direction", 0);
        RedPacketItem resumeItem = null;
        if (rejectedTitle != null && !rejectedTitle.isBlank()) {
            LinkedHashSet<String> validTitles = new LinkedHashSet<>();
            for (RedPacketItem item : scannedItems.values()) validTitles.add(item.title);
            resumeItem = QueueStore.retainValidAndSelectAdjacent(
                    this, validTitles, rejectedTitle, resumeDirection);
        }
        probe.edit()
                .putBoolean("scan_requested", false)
                .remove("scan_return_to_app")
                .remove("scan_rejected_title")
                .remove("scan_resume_direction")
                .putLong("scan_finished_at", System.currentTimeMillis())
                .apply();
        scanning = false;
        if (resumeItem != null && resumeDirection != 0) {
            Toast.makeText(this, "已跳过过期内容", Toast.LENGTH_SHORT).show();
            requestPlayTitle(this, resumeItem.title, resumeDirection);
            schedulePendingPlay(120);
            return;
        }
        if (resumeDirection != 0 && rejectedTitle != null && !rejectedTitle.isBlank()) {
            Toast.makeText(this, "后续没有未过期内容", Toast.LENGTH_SHORT).show();
        }
        if (returnToApp) handler.postDelayed(this::returnToCompanion, 120);
    }

    private void abortScanPreservingCache(String status) {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean returnToApp = probe.getBoolean("scan_return_to_app", false);
        probe.edit()
                .putBoolean("scan_requested", false)
                .remove("scan_return_to_app")
                .remove("scan_rejected_title")
                .remove("scan_resume_direction")
                .putString("scan_status", status)
                .apply();
        scanning = false;
        if (returnToApp) handler.postDelayed(this::returnToCompanion, 120);
    }

    private static final class ScanCursor {
        String course;
        final StringBuilder visibleFingerprint = new StringBuilder();
    }

    private void capturePageStructure() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"com.luojilab.player".contentEquals(root.getPackageName())) return;

        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        Set<String> visibleTexts = new LinkedHashSet<>();
        pending.add(root);
        int nodes = 0;
        int webViewChildren = -1;
        while (!pending.isEmpty() && nodes < 800) {
            AccessibilityNodeInfo node = pending.removeFirst();
            nodes++;
            CharSequence className = node.getClassName();
            if (className != null && className.toString().contains("WebView")) {
                webViewChildren = Math.max(webViewChildren, node.getChildCount());
            }
            addText(visibleTexts, node.getText());
            addText(visibleTexts, node.getContentDescription());
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }

        StringBuilder text = new StringBuilder();
        for (String value : visibleTexts) {
            if (text.length() > 0) text.append('\n');
            text.append(value);
            if (text.length() > 8_000) break;
        }
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putLong("captured_at", System.currentTimeMillis())
                .putInt("node_count", nodes)
                .putInt("webview_children", webViewChildren)
                .putString("texts", text.toString())
                .apply();
    }

    private void addText(Set<String> values, CharSequence raw) {
        if (raw == null) return;
        String value = raw.toString().trim();
        if (!value.isEmpty()) values.add(value);
    }

    private void verifyDetailAndPlay() {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        String expected = probe.getString("expected_detail_title", "");
        if (expected == null || expected.isBlank()) return;
        long startedAt = probe.getLong("expected_detail_started_at", 0L);
        long elapsed = startedAt <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - startedAt;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"com.luojilab.player".contentEquals(root.getPackageName())) {
            if (elapsed < 15_000L) handler.postDelayed(this::verifyDetailAndPlay, 150);
            else failDetailPlayback(expected, "得到详情页没有保持在前台");
            return;
        }
        if (isRedPacketList(root)) {
            if (elapsed >= 500L
                    && probe.getBoolean("title_click_semantic", false)
                    && !probe.getBoolean("fallback_gesture_submitted", false)) {
                if (submitFallbackGesture(root, expected)) return;
            }
            if (elapsed < 8_000L) {
                probe.edit().putString("seek_status", "等待列表点击进入详情：" + expected).apply();
                handler.postDelayed(this::verifyDetailAndPlay, 100);
            } else {
                failDetailPlayback(expected, "列表点击未进入详情");
            }
            return;
        }
        if (findNodesWithExactText(root, expected).isEmpty()) {
            boolean detailLoaded = !findNodesWithExactLabel(root, "进入课程").isEmpty();
            if (!detailLoaded || elapsed < 8_000L) {
                probe.edit().putString("seek_status", "等待详情标题加载：" + expected).apply();
                handler.postDelayed(this::verifyDetailAndPlay, 100);
                return;
            }
            // Never press play on a detail page whose title differs from the requested queue item.
            failDetailPlayback(expected, "详情标题核对失败");
            return;
        }
        if (isDedaoPlaying(expected)) {
            finishPlaybackStart(expected);
            return;
        }

        int attempts = probe.getInt("play_attempts", 0);
        long attemptedAt = probe.getLong("play_attempt_at", 0L);
        if (attempts > 0 && System.currentTimeMillis() - attemptedAt < 1_500L) {
            handler.postDelayed(this::verifyDetailAndPlay, 100);
            return;
        }
        if (attempts >= 3) {
            failDetailPlayback(expected, "播放按钮多次未生效");
            return;
        }

        // WebView sometimes reports ACTION_CLICK success without executing it. The first attempt
        // stays semantic; later attempts use only the verified play button's own bounds.
        if (clickPlayIfPresent(root, attempts > 0)) {
            int nextAttempt = attempts + 1;
            probe.edit()
                    .putInt("play_attempts", nextAttempt)
                    .putLong("play_attempt_at", System.currentTimeMillis())
                    .putString("seek_status", "已核对详情，正在确认播放（第 " + nextAttempt + " 次）")
                    .apply();
            markPlayStage("play_submitted", expected);
            handler.postDelayed(this::verifyDetailAndPlay, 100);
            return;
        }
        if (elapsed < 15_000L) handler.postDelayed(this::verifyDetailAndPlay, 150);
        else failDetailPlayback(expected, "详情页没有可用的播放按钮");
    }

    private void failDetailPlayback(String expected, String reason) {
        SharedPreferences probe = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean resumeQueueSync = probe.getBoolean("official_sync_resume_after_seed", false);
        probe.edit()
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .remove("play_attempts")
                .remove("play_attempt_at")
                .putString("seek_status", reason + "：" + expected)
                .apply();
        if (resumeQueueSync) failOfficialQueueSync(reason + "：" + expected);
        else {
            returnToCompanion();
            dismissAutomationCover();
        }
    }

    private boolean submitFallbackGesture(AccessibilityNodeInfo root, String expected) {
        for (AccessibilityNodeInfo node : findNodesWithExactText(root, expected)) {
            if (!isCurrentlyActiveEpisode(node, expected)) continue;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) continue;
            if (automationCover != null) automationCover.setVisibility(View.INVISIBLE);
            Path path = new Path();
            path.moveTo(bounds.centerX(), bounds.centerY());
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                    .build();
            boolean submitted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    if (automationCover != null) automationCover.setVisibility(View.VISIBLE);
                    markPlayStage("fallback_tapped", expected);
                    handler.postDelayed(DedaoAccessibilityService.this::verifyDetailAndPlay, 60);
                }

                @Override public void onCancelled(GestureDescription gestureDescription) {
                    if (automationCover != null) automationCover.setVisibility(View.VISIBLE);
                    getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                            .putBoolean("fallback_gesture_submitted", false)
                            .apply();
                    handler.postDelayed(DedaoAccessibilityService.this::verifyDetailAndPlay, 120);
                }
            }, null);
            if (submitted) {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putBoolean("fallback_gesture_submitted", true)
                        .putString("seek_status", "正在确认得到页面点击")
                        .apply();
                return true;
            }
            if (automationCover != null) automationCover.setVisibility(View.VISIBLE);
        }
        return false;
    }

    private boolean isDedaoPlaying(String expectedTitle) {
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, DedaoSessionListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if (!"com.luojilab.player".equals(controller.getPackageName())) continue;
                PlaybackState state = controller.getPlaybackState();
                MediaMetadata metadata = controller.getMetadata();
                String title = metadata == null ? null
                        : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                        && expectedTitle.equals(title)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void finishPlaybackStart(String title) {
        markPlayStage("playing_confirmed", title);
        SharedPreferences preferences = getSharedPreferences("page_probe", MODE_PRIVATE);
        boolean resumeQueueSync = preferences.getBoolean(
                "official_sync_resume_after_seed", false);
        preferences.edit()
                .remove("expected_detail_title")
                .remove("expected_detail_started_at")
                .remove("play_attempts")
                .remove("play_attempt_at")
                .putString("seek_status", "已确认正在播放：" + title)
                .apply();
        if (resumeQueueSync) {
            preferences.edit()
                    .putBoolean("official_sync_resume_after_seed", false)
                    .putBoolean("official_sync_active", true)
                    .putBoolean("official_sync_pending", true)
                    .putString("official_sync_status", "有效内容已加入，继续同步播放顺序…")
                    .apply();
            handler.postDelayed(this::openOfficialPlayer, 250);
            return;
        }
        handler.postDelayed(() -> {
            markPlayStage("companion_requested", title);
            returnToCompanion();
        }, 30);
    }

    private boolean clickPlayIfPresent(AccessibilityNodeInfo root, boolean useGesture) {
        for (String text : new String[]{"播放", "继续播放", "开始学习", "继续学习"}) {
            List<AccessibilityNodeInfo> nodes = findNodesWithExactLabel(root, text);
            for (AccessibilityNodeInfo node : nodes) {
                Rect nodeBounds = new Rect();
                node.getBoundsInScreen(nodeBounds);
                // Ignore Dedao's persistent mini-player at the bottom. It can still represent the
                // previous episode while the requested detail page is loading.
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                if (useGesture && nodeBounds.isEmpty()) continue;
                if (!nodeBounds.isEmpty() && nodeBounds.top >= screenHeight - dp(104)) continue;
                boolean submitted;
                if (useGesture) {
                    Path path = new Path();
                    path.moveTo(nodeBounds.centerX(), nodeBounds.centerY());
                    GestureDescription gesture = new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                            .build();
                    submitted = dispatchGesture(gesture, null, null);
                } else {
                    submitted = clickNodeOrParent(node);
                }
                if (submitted) {
                    lastAutoClick = System.currentTimeMillis();
                    return true;
                }
            }
        }
        // Do not fall back to a hard-coded coordinate. Different courses use different detail
        // layouts; an unverified tap could activate an unrelated control.
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        ArrayDeque<AccessibilityNodeInfo> chain = new ArrayDeque<>();
        AccessibilityNodeInfo cursor = node;
        while (cursor != null && chain.size() < 5) {
            chain.add(cursor);
            if (cursor.isClickable()) return cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            cursor = cursor.getParent();
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        dismissAutomationCover();
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }
}
