package com.dingaimin.dedaocompanion;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DedaoSessionListener extends NotificationListenerService {
    private static volatile boolean connected;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, SessionObserver> observed = new HashMap<>();
    private MediaSessionManager manager;
    private ComponentName listenerComponent;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::syncSessions;

    @Override public void onListenerConnected() {
        connected = true;
        manager = getSystemService(MediaSessionManager.class);
        listenerComponent = new ComponentName(this, DedaoSessionListener.class);
        manager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler);
        syncSessions(manager.getActiveSessions(listenerComponent));
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("playback_monitor_connected", true)
                .putLong("playback_monitor_connected_at", System.currentTimeMillis())
                .putString("playback_monitor_status", "连续播放监控已连接")
                .commit();
        if (getSharedPreferences("page_probe", MODE_PRIVATE)
                .getBoolean("companion_queue_active", false)) {
            PlaybackGuardService.start(this);
        }
    }

    private void syncSessions(List<MediaController> sessions) {
        if (sessions == null) return;
        Set<MediaSession.Token> activeTokens = new HashSet<>();
        for (MediaController controller : sessions) {
            if (!"com.luojilab.player".equals(controller.getPackageName())) continue;
            MediaSession.Token token = controller.getSessionToken();
            activeTokens.add(token);
            if (observed.containsKey(token)) continue;
            SessionObserver observer = new SessionObserver(controller);
            observed.put(token, observer);
            controller.registerCallback(observer, handler);
        }
        ArrayList<MediaSession.Token> stale = new ArrayList<>();
        for (MediaSession.Token token : observed.keySet()) {
            if (!activeTokens.contains(token)) stale.add(token);
        }
        for (MediaSession.Token token : stale) {
            SessionObserver observer = observed.remove(token);
            if (observer != null) observer.dispose();
        }
    }

    private final class SessionObserver extends MediaController.Callback {
        final MediaController controller;
        PlaybackState previousState;
        MediaMetadata metadata;
        boolean completionHandled;
        boolean playbackSeenForTitle;
        long lastAdvanceAt;
        long furthestPositionForTitle;
        int openGeneration;
        final Runnable completionProbe = this::pollCompletion;

        SessionObserver(MediaController controller) {
            this.controller = controller;
            this.previousState = controller.getPlaybackState();
            this.metadata = controller.getMetadata();
            this.playbackSeenForTitle = this.previousState != null
                    && this.previousState.getState() == PlaybackState.STATE_PLAYING;
            QueueStore.selectTitle(DedaoSessionListener.this, title(this.metadata));
            observeState(this.previousState, "initial");
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            String oldTitle = title(this.metadata);
            String newTitle = title(metadata);
            this.metadata = metadata;
            android.content.SharedPreferences probe =
                    getSharedPreferences("page_probe", MODE_PRIVATE);
            String pendingTitle = probe.getString("direct_open_target_title", "");
            RedPacketItem expectedItem = QueueStore.current(DedaoSessionListener.this);
            RedPacketItem oldItem = QueueStore.findTitle(DedaoSessionListener.this, oldTitle);
            RedPacketItem newItem = QueueStore.findTitle(DedaoSessionListener.this, newTitle);
            boolean queueActive = probe
                    .getBoolean("companion_queue_active", false);
            boolean expectedManualTransition = queueActive && expectedItem != null
                    && expectedItem.title.equals(newTitle) && !newTitle.equals(oldTitle);
            if ((!pendingTitle.isBlank() && pendingTitle.equals(newTitle))
                    || expectedManualTransition) {
                QueueStore.selectTitle(DedaoSessionListener.this, newTitle);
                probe.edit()
                        .remove("direct_open_target_title")
                        .putString("playback_monitor_status", "正在播放：" + newTitle)
                        .apply();
                ensurePlaying();
            } else if (queueActive && oldItem != null && !newTitle.equals(oldTitle)
                    && !newTitle.isBlank()) {
                // 得到 may auto-advance its own unrelated queue. Re-align to the companion
                // queue; accept the native transition only when it is exactly our next item.
                QueueStore.selectTitle(DedaoSessionListener.this, oldTitle);
                advanceFrom(oldItem.title, newTitle);
            } else if (queueActive && newItem != null && !newTitle.equals(oldTitle)) {
                QueueStore.selectTitle(DedaoSessionListener.this, newTitle);
                probe.edit()
                        .putString("playback_monitor_status", "正在播放：" + newTitle)
                        .apply();
            }
            if (!newTitle.equals(oldTitle)) {
                completionHandled = false;
                furthestPositionForTitle = 0L;
                PlaybackState state = controller.getPlaybackState();
                playbackSeenForTitle = state != null
                        && state.getState() == PlaybackState.STATE_PLAYING;
            }
            scheduleCompletionProbe();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            observeState(state, "callback");
        }

        private void observeState(PlaybackState state, String source) {
            if (state == null) {
                scheduleCompletionProbe();
                return;
            }
            int current = state.getState();
            long duration = metadata == null ? 0L
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = estimatedPosition(state);
            furthestPositionForTitle = Math.max(furthestPositionForTitle, position);
            boolean terminalState = current == PlaybackState.STATE_PAUSED
                    || current == PlaybackState.STATE_STOPPED
                    || current == PlaybackState.STATE_NONE
                    || current == PlaybackState.STATE_ERROR;
            boolean reachedEnd = PlaybackCompletionPolicy.reachedEnd(
                    furthestPositionForTitle, duration);

            if (current == PlaybackState.STATE_PLAYING) {
                playbackSeenForTitle = true;
            }
            if (current == PlaybackState.STATE_PLAYING && (!reachedEnd || position < 2_000L)) {
                completionHandled = false;
            }
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putInt("playback_monitor_last_state", current)
                    .putLong("playback_monitor_last_position", position)
                    .putLong("playback_monitor_last_duration", duration)
                    .putLong("playback_monitor_last_callback_at", System.currentTimeMillis())
                    .putString("playback_monitor_last_source", source)
                    .apply();
            if (current == PlaybackState.STATE_PLAYING) {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putLong("playback_monitor_last_playing_at", System.currentTimeMillis())
                        .apply();
            }
            previousState = state;
            scheduleCompletionProbe();
            String pendingTitle = getSharedPreferences("page_probe", MODE_PRIVATE)
                    .getString("direct_open_target_title", "");
            if (!pendingTitle.isBlank()) return;
            if (!playbackSeenForTitle || !terminalState || !reachedEnd || completionHandled) return;

            completionHandled = true;
            playbackSeenForTitle = false;
            String finishedTitle = title(metadata);
            boolean queueActive = getSharedPreferences("page_probe", MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false);
            if (queueActive) advanceFrom(finishedTitle, "");
        }

        private long estimatedPosition(PlaybackState state) {
            long position = Math.max(0L, state.getPosition());
            if (state.getState() != PlaybackState.STATE_PLAYING) return position;
            long updatedAt = state.getLastPositionUpdateTime();
            if (updatedAt <= 0L) return position;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - updatedAt);
            return Math.max(0L, position + (long) (elapsed * state.getPlaybackSpeed()));
        }

        private void scheduleCompletionProbe() {
            handler.removeCallbacks(completionProbe);
            if (getSharedPreferences("page_probe", MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false)) {
                handler.postDelayed(completionProbe, 1_000L);
            }
        }

        private void pollCompletion() {
            observeState(controller.getPlaybackState(), "poll");
        }

        private void advanceFrom(String finishedTitle, String alreadyPlayingTitle) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastAdvanceAt < 1_200L) return;
            lastAdvanceAt = now;
            QueueStore.selectTitle(DedaoSessionListener.this, finishedTitle);
            RedPacketItem next = QueueStore.advance(DedaoSessionListener.this);
            if (next != null && next.title.equals(alreadyPlayingTitle)) {
                completionHandled = false;
                ensurePlaying();
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putString("playback_monitor_status", "正在播放：" + next.title)
                        .apply();
                return;
            }
            if (next != null && next.audioId != null && !next.audioId.isBlank()) {
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putString("direct_open_target_title", next.title)
                        .commit();
            }
            if (next != null && next.audioId != null && !next.audioId.isBlank()
                    && OfficialPlayerControl.open(DedaoSessionListener.this, next.audioId)) {
                completionHandled = false;
                getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                        .putString("playback_monitor_status", "正在衔接下一条：" + next.title)
                        .apply();
                confirmOpenedItem(next, ++openGeneration, 0);
                return;
            }
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .remove("direct_open_target_title")
                    .apply();
            controller.getTransportControls().pause();
            getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putBoolean("companion_queue_active", false)
                    .putString("playback_monitor_status", "当前队列已全部播完")
                    .apply();
            PlaybackGuardService.stop(DedaoSessionListener.this);
        }

        private void confirmOpenedItem(RedPacketItem item, int generation, int attempt) {
            handler.postDelayed(() -> {
                if (generation != openGeneration) return;
                String currentTitle = title(controller.getMetadata());
                if (item.title.equals(currentTitle)) {
                    PlaybackState state = controller.getPlaybackState();
                    boolean playing = state != null
                            && state.getState() == PlaybackState.STATE_PLAYING;
                    if (playing) {
                        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                                .remove("direct_open_target_title")
                                .putString("playback_monitor_status", "正在播放：" + item.title)
                                .putInt("playback_monitor_open_attempts", attempt + 1)
                                .apply();
                        return;
                    }
                    ensurePlaying();
                    getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                            .putString("playback_monitor_status", "正在启动下一条：" + item.title)
                            .putInt("playback_monitor_open_attempts", attempt + 1)
                            .apply();
                    if (attempt < 20) confirmOpenedItem(item, generation, attempt + 1);
                    return;
                }
                if (attempt >= 20 || !getSharedPreferences("page_probe", MODE_PRIVATE)
                        .getBoolean("companion_queue_active", false)) {
                    getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                            .putString("playback_monitor_status", "下一条启动未确认，等待恢复")
                            .putInt("playback_monitor_open_attempts", attempt + 1)
                            .apply();
                    return;
                }
                if (attempt == 1 || attempt == 4 || attempt == 8 || attempt == 14) {
                    OfficialPlayerControl.open(DedaoSessionListener.this, item.audioId);
                }
                confirmOpenedItem(item, generation, attempt + 1);
            }, attempt < 4 ? 650L : 1_500L);
        }

        private void ensurePlaying() {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return;
            controller.getTransportControls().play();
            OfficialPlayerControl.send(DedaoSessionListener.this, OfficialPlayerControl.PLAY);
        }

        void dispose() {
            handler.removeCallbacks(completionProbe);
            ++openGeneration;
            controller.unregisterCallback(this);
        }

        private String title(MediaMetadata value) {
            if (value == null) return "";
            String title = value.getString(MediaMetadata.METADATA_KEY_TITLE);
            return title == null ? "" : title;
        }
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putBoolean("playback_monitor_connected", false)
                .putLong("playback_monitor_disconnected_at", System.currentTimeMillis())
                .putString("playback_monitor_status", "连续播放监控已断开，正在重连")
                .commit();
        if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (SessionObserver observer : observed.values()) {
            observer.dispose();
        }
        observed.clear();
        manager = null;
        handler.postDelayed(() -> requestReconnect(DedaoSessionListener.this), 1_000L);
    }

    static boolean ensureConnected(android.content.Context context) {
        if (connected) return true;
        requestReconnect(context);
        return false;
    }

    static boolean isConnected() {
        return connected;
    }

    static void repairBinding(Context context) {
        if (context == null || connected) return;
        ComponentName component = new ComponentName(context, DedaoSessionListener.class);
        context.getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                .putLong("playback_monitor_repair_at", System.currentTimeMillis())
                .putString("playback_monitor_status", "正在自动修复连续播放监控")
                .apply();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                NotificationListenerService.requestUnbind(component);
            }
        } catch (Exception ignored) {
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> requestReconnect(context), 500L);
    }

    private static void requestReconnect(android.content.Context context) {
        if (context == null) return;
        ComponentName component = new ComponentName(context, DedaoSessionListener.class);
        try {
            NotificationListenerService.requestRebind(component);
            context.getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putLong("playback_monitor_rebind_requested_at", System.currentTimeMillis())
                    .putString("playback_monitor_status", "正在连接连续播放监控")
                    .apply();
        } catch (Exception error) {
            context.getSharedPreferences("page_probe", MODE_PRIVATE).edit()
                    .putString("playback_monitor_status",
                            "连续播放监控重连失败：" + error.getClass().getSimpleName())
                    .apply();
        }
    }
}
