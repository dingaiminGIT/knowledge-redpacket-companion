package com.dingaimin.dedaocompanion

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

class DedaoSessionListener : NotificationListenerService() {

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val observed: MutableMap<MediaSession.Token, SessionObserver> =
        HashMap<MediaSession.Token, SessionObserver>()
    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null

    private val sessionsChanged: MediaSessionManager.OnActiveSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener({ this.syncSessions(it) })

    public override fun onListenerConnected() {
        isConnected = true
        manager = getSystemService<MediaSessionManager>(MediaSessionManager::class.java)
        listenerComponent = ComponentName(this, DedaoSessionListener::class.java)
        manager!!.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
        syncSessions(manager!!.getActiveSessions(listenerComponent))
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("playback_monitor_connected", true)
            .putLong("playback_monitor_connected_at", System.currentTimeMillis())
            .putString("playback_monitor_status", "连续播放监控已连接")
            .commit()
        if (
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getBoolean("companion_queue_active", false)
        ) {
            PlaybackGuardService.start(this)
        }
    }

    private fun syncSessions(sessions: List<MediaController>?) {
        if (sessions == null) return
        val activeTokens: MutableSet<MediaSession.Token> = HashSet<MediaSession.Token>()
        for (controller: MediaController in sessions!!) {
            if (DEDAO_PACKAGE != controller.getPackageName()) continue
            val token: MediaSession.Token = controller.getSessionToken()
            activeTokens.add(token)
            if (observed.containsKey(token)) continue
            val observer: SessionObserver = SessionObserver(controller)
            observed.put(token, observer)
            controller.registerCallback(observer, handler)
        }
        val stale: ArrayList<MediaSession.Token> = ArrayList<MediaSession.Token>()
        for (token: MediaSession.Token in observed.keys) {
            if (!activeTokens.contains(token)) stale.add(token)
        }
        for (token: MediaSession.Token in stale) {
            val observer: SessionObserver? = observed.remove(token)
            if (observer != null) observer!!.dispose()
        }
    }

    public override fun onNotificationPosted(notification: StatusBarNotification) {
        handlePlayerNotification(notification, "notification_posted")
    }

    public override fun onNotificationRemoved(notification: StatusBarNotification) {
        handlePlayerNotification(notification, "notification_removed")
    }

    /**
     * Xiaomi may suspend ordinary Handler work shortly after the display turns off even while a
     * foreground service is present. The official player still updates its media notification at
     * completion, and NotificationListenerService is a system-bound wake-up path. Use that signal
     * to inspect MediaSession synchronously instead of waiting for a delayed poll to run.
     */
    private fun handlePlayerNotification(notification: StatusBarNotification?, source: String) {
        if (notification == null || DEDAO_PACKAGE != notification!!.getPackageName()) return
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putLong("playback_monitor_last_notification_at", System.currentTimeMillis())
            .putString("playback_monitor_last_notification_source", source)
            .apply()
        if (manager == null || listenerComponent == null) return
        try {
            syncSessions(manager!!.getActiveSessions(listenerComponent))
        } catch (ignored: Exception) {}

        if (
            !getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .getBoolean("companion_queue_active", false)
        )
            return
        PlaybackGuardService.pulse(this)
        for (observer: SessionObserver in ArrayList<SessionObserver>(observed.values)) {
            observer.observeExternalSignal(source)
        }
    }

    private inner class SessionObserver(val controller: MediaController) :
        MediaController.Callback() {
        var previousState: PlaybackState? = null
        var metadata: MediaMetadata? = null
        var completionHandled: Boolean = false
        var playbackSeenForTitle: Boolean = false
        var lastAdvanceAt: Long = 0
        var furthestPositionForTitle: Long = 0
        var openGeneration: Int = 0
        var transitionStartedAt: Long = 0
        var lastPlayRequestAt: Long = 0
        val completionProbe: Runnable = Runnable({ this.pollCompletion() })

        init {
            this.previousState = controller.getPlaybackState()
            this.metadata = controller.getMetadata()
            this.playbackSeenForTitle =
                (this.previousState != null &&
                    this.previousState!!.getState() == PlaybackState.STATE_PLAYING)
            QueueStore.selectTitle(this@DedaoSessionListener, title(this.metadata))
            observeState(this.previousState, "initial")
        }

        public override fun onMetadataChanged(metadata: MediaMetadata?) {
            val oldTitle: String = title(this.metadata)
            val newTitle: String = title(metadata)
            this.metadata = metadata
            val probe: android.content.SharedPreferences =
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            val pendingTitle: String = probe.getString("direct_open_target_title", "") ?: ""
            val expectedItem: RedPacketItem? = QueueStore.current(this@DedaoSessionListener)
            val oldItem: RedPacketItem? = QueueStore.findTitle(this@DedaoSessionListener, oldTitle)
            val newItem: RedPacketItem? = QueueStore.findTitle(this@DedaoSessionListener, newTitle)
            val queueActive: Boolean = probe.getBoolean("companion_queue_active", false)
            // Ignore intermediate metadata from the official queue while an explicit open is
            // pending. It is not another completed companion item.
            if (!pendingTitle.isBlank() && pendingTitle != newTitle) return
            val expectedManualTransition: Boolean =
                (queueActive &&
                    expectedItem != null &&
                    expectedItem!!.title == newTitle &&
                    newTitle != oldTitle)
            if (
                ((!pendingTitle.isBlank() && pendingTitle == newTitle) || expectedManualTransition)
            ) {
                QueueStore.selectTitle(this@DedaoSessionListener, newTitle)
                probe.edit().putString("playback_monitor_status", "正在播放：" + newTitle).apply()
                ensurePlaying()
            } else if (
                (queueActive && oldItem != null && newTitle != oldTitle && !newTitle.isBlank())
            ) {
                // 得到 may auto-advance its own unrelated queue. Re-align to the companion
                // queue; accept the native transition only when it is exactly our next item.
                QueueStore.selectTitle(this@DedaoSessionListener, oldTitle)
                advanceFrom(oldItem!!.title, newTitle)
            } else if (queueActive && newItem != null && newTitle != oldTitle) {
                QueueStore.selectTitle(this@DedaoSessionListener, newTitle)
                probe.edit().putString("playback_monitor_status", "正在播放：" + newTitle).apply()
            }
            if (newTitle != oldTitle) {
                completionHandled = false
                furthestPositionForTitle = 0L
                val state: PlaybackState? = controller.getPlaybackState()
                playbackSeenForTitle =
                    (state != null && state!!.getState() == PlaybackState.STATE_PLAYING)
            }
            scheduleCompletionProbe()
        }

        public override fun onPlaybackStateChanged(state: PlaybackState?) {
            observeState(state, "callback")
        }

        fun observeExternalSignal(source: String) {
            val latestMetadata: MediaMetadata? = controller.getMetadata()
            if (title(latestMetadata) != title(metadata)) {
                onMetadataChanged(latestMetadata)
            } else {
                metadata = latestMetadata
            }
            observeState(controller.getPlaybackState(), source)
        }

        private fun observeState(state: PlaybackState?, source: String) {
            if (state == null) {
                scheduleCompletionProbe()
                return
            }
            val current: Int = state!!.getState()
            val duration: Long =
                if (metadata == null) 0L
                else metadata!!.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val position: Long = estimatedPosition(state!!)
            furthestPositionForTitle =
                PlaybackCompletionPolicy.updateFurthest(
                    furthestPositionForTitle,
                    position,
                    current == PlaybackState.STATE_PLAYING,
                )
            val terminalState: Boolean =
                (current == PlaybackState.STATE_PAUSED ||
                    current == PlaybackState.STATE_STOPPED ||
                    current == PlaybackState.STATE_NONE ||
                    current == PlaybackState.STATE_ERROR)
            val reachedEnd: Boolean =
                PlaybackCompletionPolicy.reachedEnd(furthestPositionForTitle, duration)

            if (current == PlaybackState.STATE_PLAYING) {
                playbackSeenForTitle = true
            }
            if (current == PlaybackState.STATE_PLAYING && (!reachedEnd || position < 2_000L)) {
                completionHandled = false
            }
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putInt("playback_monitor_last_state", current)
                .putLong("playback_monitor_last_position", position)
                .putLong("playback_monitor_last_duration", duration)
                .putLong("playback_monitor_last_callback_at", System.currentTimeMillis())
                .putString("playback_monitor_last_source", source)
                .apply()
            if (current == PlaybackState.STATE_PLAYING) {
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("playback_monitor_last_playing_at", System.currentTimeMillis())
                    .apply()
            }
            val oldState: PlaybackState? = previousState
            previousState = state
            scheduleCompletionProbe()
            val probe: android.content.SharedPreferences =
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            val pendingTitle: String = probe.getString("direct_open_target_title", "") ?: ""
            if (!pendingTitle.isBlank()) {
                if (pendingTitle == title(metadata)) {
                    if (current == PlaybackState.STATE_PLAYING) {
                        probe.edit().remove("direct_open_target_title").apply()
                        recordTransition("playing")
                        transitionStartedAt = 0L
                    } else if (probe.getBoolean("companion_queue_active", false)) {
                        ensurePlaying()
                    }
                }
                return
            }
            if (
                (current == PlaybackState.STATE_PLAYING &&
                    probe.getBoolean("companion_queue_suspended", false) &&
                    (("initial" == source ||
                        oldState == null ||
                        oldState!!.getState() != PlaybackState.STATE_PLAYING)) &&
                    QueueStore.findTitle(this@DedaoSessionListener, title(metadata)) != null)
            ) {
                probe
                    .edit()
                    .putBoolean("companion_queue_suspended", false)
                    .putBoolean("companion_queue_active", true)
                    .apply()
                PlaybackGuardService.start(this@DedaoSessionListener)
                scheduleCompletionProbe()
            }
            if (
                (current == PlaybackState.STATE_PAUSED &&
                    !reachedEnd &&
                    probe.getBoolean("companion_queue_active", false) &&
                    (("initial" == source ||
                        ((oldState != null &&
                            oldState!!.getState() == PlaybackState.STATE_PLAYING)))))
            ) {
                probe
                    .edit()
                    .putBoolean("companion_queue_active", false)
                    .putBoolean("companion_queue_suspended", true)
                    .putString("playback_monitor_status", "连续播放已暂停")
                    .apply()
                ++openGeneration
                PlaybackGuardService.stop(this@DedaoSessionListener)
                handler.removeCallbacks(completionProbe)
                return
            }
            if (!playbackSeenForTitle || !terminalState || !reachedEnd || completionHandled) return

            completionHandled = true
            playbackSeenForTitle = false
            val finishedTitle: String = title(metadata)
            val queueActive: Boolean =
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false)
            if (queueActive) advanceFrom(finishedTitle, "")
        }

        private fun estimatedPosition(state: PlaybackState): Long {
            val position: Long = Math.max(0L, state.getPosition())
            if (state.getState() != PlaybackState.STATE_PLAYING) return position
            val updatedAt: Long = state.getLastPositionUpdateTime()
            if (updatedAt <= 0L) return position
            val elapsed: Long = Math.max(0L, SystemClock.elapsedRealtime() - updatedAt)
            return Math.max(0L, position + (elapsed * state.getPlaybackSpeed()).toLong())
        }

        private fun scheduleCompletionProbe() {
            handler.removeCallbacks(completionProbe)
            if (
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .getBoolean("companion_queue_active", false)
            ) {
                val duration: Long =
                    if (metadata == null) 0L
                    else metadata!!.getLong(MediaMetadata.METADATA_KEY_DURATION)
                val position: Long =
                    if (previousState == null) 0L else estimatedPosition(previousState!!)
                handler.postDelayed(
                    completionProbe,
                    PlaybackCompletionPolicy.probeDelay(position, duration),
                )
            }
        }

        private fun pollCompletion() {
            observeExternalSignal("poll")
        }

        private fun advanceFrom(finishedTitle: String, alreadyPlayingTitle: String) {
            val now: Long = android.os.SystemClock.elapsedRealtime()
            if (now - lastAdvanceAt < 1_200L) return
            lastAdvanceAt = now
            transitionStartedAt = now
            recordTransition("completed")
            QueueStore.selectTitle(this@DedaoSessionListener, finishedTitle)
            val next: RedPacketItem? = QueueStore.advance(this@DedaoSessionListener)
            if (next != null && next!!.title == alreadyPlayingTitle) {
                completionHandled = false
                ensurePlaying()
                recordTransition("native_next")
                transitionStartedAt = 0L
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString("playback_monitor_status", "正在播放：" + next!!.title)
                    .apply()
                return
            }
            if (next != null && next!!.audioId != null && !next!!.audioId.isBlank()) {
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString("direct_open_target_title", next!!.title)
                    .commit()
            }
            if (
                (next != null &&
                    next!!.audioId != null &&
                    !next!!.audioId.isBlank() &&
                    OfficialPlayerControl.open(this@DedaoSessionListener, next!!.audioId))
            ) {
                completionHandled = false
                getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString("playback_monitor_status", "正在衔接下一条：" + next!!.title)
                    .apply()
                confirmOpenedItem(next, ++openGeneration, 0)
                return
            }
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .remove("direct_open_target_title")
                .apply()
            controller.getTransportControls().pause()
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("companion_queue_active", false)
                .putString(
                    "playback_monitor_status",
                    if (next == null) "当前队列已全部播完" else "下一条打开失败，请点播放重试",
                )
                .apply()
            PlaybackGuardService.stop(this@DedaoSessionListener)
        }

        private fun confirmOpenedItem(item: RedPacketItem, generation: Int, attempt: Int) {
            handler.postDelayed(
                {
                    if (generation != openGeneration) return@postDelayed
                    val probe: android.content.SharedPreferences =
                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    // Pause, removal, and a newer manual selection must cancel queued retries.
                    if (
                        (!probe.getBoolean("companion_queue_active", false) ||
                            item.title != probe.getString("direct_open_target_title", ""))
                    )
                        return@postDelayed
                    val currentTitle: String = title(controller.getMetadata())
                    if (item.title == currentTitle) {
                        val state: PlaybackState? = controller.getPlaybackState()
                        val playing: Boolean =
                            (state != null && state!!.getState() == PlaybackState.STATE_PLAYING)
                        if (playing) {
                            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                                .edit()
                                .remove("direct_open_target_title")
                                .putString("playback_monitor_status", "正在播放：" + item.title)
                                .putInt("playback_monitor_open_attempts", attempt + 1)
                                .apply()
                            recordTransition("playing")
                            transitionStartedAt = 0L
                            return@postDelayed
                        }
                        ensurePlaying()
                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                            .edit()
                            .putString("playback_monitor_status", "正在启动下一条：" + item.title)
                            .putInt("playback_monitor_open_attempts", attempt + 1)
                            .apply()
                    }
                    if (attempt >= 40) {
                        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                            .edit()
                            .remove("direct_open_target_title")
                            .putBoolean("companion_queue_active", false)
                            .putBoolean("companion_queue_suspended", true)
                            .putString("playback_monitor_status", "下一条暂未开始，请点播放重试")
                            .putInt("playback_monitor_open_attempts", attempt + 1)
                            .apply()
                        recordTransition("timeout")
                        PlaybackGuardService.stop(this@DedaoSessionListener)
                        return@postDelayed
                    }
                    if (attempt == 24 && item.title != currentTitle) {
                        OfficialPlayerControl.open(this@DedaoSessionListener, item.audioId)
                    }
                    confirmOpenedItem(item, generation, attempt + 1)
                },
                if (attempt == 0) 100L else 250L,
            )
        }

        private fun ensurePlaying() {
            val state: PlaybackState? = controller.getPlaybackState()
            if (
                (state != null &&
                    ((state!!.getState() == PlaybackState.STATE_PLAYING ||
                        state!!.getState() == PlaybackState.STATE_BUFFERING ||
                        state!!.getState() == PlaybackState.STATE_CONNECTING)))
            )
                return
            val now: Long = SystemClock.elapsedRealtime()
            if (now - lastPlayRequestAt < 1_000L) return
            lastPlayRequestAt = now
            controller.getTransportControls().play()
        }

        private fun recordTransition(stage: String) {
            if (transitionStartedAt <= 0L) return
            val elapsed: Long = SystemClock.elapsedRealtime() - transitionStartedAt
            getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("playback_transition_stage", stage)
                .putLong("playback_transition_ms", elapsed)
                .putLong("playback_transition_at", System.currentTimeMillis())
                .apply()
            android.util.Log.i(
                "CompanionPlayback",
                "transition=" + stage + " elapsed_ms=" + elapsed,
            )
        }

        fun dispose() {
            handler.removeCallbacks(completionProbe)
            ++openGeneration
            controller.unregisterCallback(this)
        }

        private fun title(value: MediaMetadata?): String {
            if (value == null) return ""
            val title: String? = value!!.getString(MediaMetadata.METADATA_KEY_TITLE)
            return if (title == null) "" else title
        }
    }

    public override fun onListenerDisconnected() {
        isConnected = false
        getSharedPreferences("page_probe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("playback_monitor_connected", false)
            .putLong("playback_monitor_disconnected_at", System.currentTimeMillis())
            .putString("playback_monitor_status", "连续播放监控已断开，正在重连")
            .commit()
        if (manager != null) manager!!.removeOnActiveSessionsChangedListener(sessionsChanged)
        for (observer: SessionObserver in observed.values) {
            observer.dispose()
        }
        observed.clear()
        manager = null
        handler.postDelayed({ requestReconnect(this@DedaoSessionListener) }, 1_000L)
    }

    companion object {
        private val DEDAO_PACKAGE: String = "com.luojilab.player"
        @Volatile
        var isConnected: Boolean = false
            private set

        fun ensureConnected(context: android.content.Context): Boolean {
            if (isConnected) return true
            requestReconnect(context)
            return false
        }

        fun repairBinding(context: Context?) {
            if (context == null || isConnected) return
            val component: ComponentName = ComponentName(context, DedaoSessionListener::class.java)
            context!!
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putLong("playback_monitor_repair_at", System.currentTimeMillis())
                .putString("playback_monitor_status", "正在自动修复连续播放监控")
                .apply()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    NotificationListenerService.requestUnbind(component)
                }
            } catch (ignored: Exception) {}

            Handler(Looper.getMainLooper()).postDelayed({ requestReconnect(context) }, 500L)
        }

        private fun requestReconnect(context: android.content.Context?) {
            if (context == null) return
            val component: ComponentName = ComponentName(context, DedaoSessionListener::class.java)
            try {
                NotificationListenerService.requestRebind(component)
                context!!
                    .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("playback_monitor_rebind_requested_at", System.currentTimeMillis())
                    .putString("playback_monitor_status", "正在连接连续播放监控")
                    .apply()
            } catch (error: Exception) {
                context!!
                    .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                    .edit()
                    .putString(
                        "playback_monitor_status",
                        "连续播放监控重连失败：" + error.javaClass.getSimpleName(),
                    )
                    .apply()
            }
        }
    }
}
