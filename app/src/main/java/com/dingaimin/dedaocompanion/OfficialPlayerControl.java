package com.dingaimin.dedaocompanion;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

final class OfficialPlayerControl {
    private static final String DEDAO_PACKAGE = "com.luojilab.player";
    private static final String DEDAO_PLAYER_SERVICE =
            "com.luojilab.service.DDPlayerService";

    static final int PLAY = 3;
    static final int PAUSE = 4;
    static final int PREVIOUS = 7;
    static final int NEXT = 8;

    static boolean send(Context context, int command) {
        if (context == null) return false;
        String action = serviceAction(command);
        if (action != null && sendToExportedService(context, action)) return true;
        return sendLegacyBroadcast(context, command);
    }

    static boolean skip(Context context, int index) {
        if (context == null || index < 0) return false;
        try {
            Intent intent = new Intent("skip");
            intent.setComponent(new ComponentName(DEDAO_PACKAGE, DEDAO_PLAYER_SERVICE));
            intent.putExtra("index", index);
            ComponentName started = context.startService(intent);
            record(context, "skip", started == null ? "service_null" : "service", "");
            context.getSharedPreferences("page_probe", Context.MODE_PRIVATE).edit()
                    .putInt("official_control_index", index)
                    .apply();
            return started != null;
        } catch (Exception error) {
            record(context, "skip", "service_failed", error.getClass().getSimpleName());
            return false;
        }
    }

    static boolean open(Context context, String audioId) {
        if (context == null || audioId == null || audioId.isBlank()) return false;
        try {
            Intent intent = new Intent("open");
            intent.setComponent(new ComponentName(DEDAO_PACKAGE, DEDAO_PLAYER_SERVICE));
            intent.putExtra("AUDIO_ID", audioId);
            ComponentName started = context.startService(intent);
            // Never persist or log the selector itself. It is cached only with the
            // sanitized red-packet snapshot that needs it for later playback.
            record(context, "open", started == null ? "service_null" : "service", "");
            return started != null;
        } catch (Exception error) {
            record(context, "open", "service_failed", error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean sendToExportedService(Context context, String action) {
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(DEDAO_PACKAGE, DEDAO_PLAYER_SERVICE));
            ComponentName started = context.startService(intent);
            record(context, action, started == null ? "service_null" : "service", "");
            return started != null;
        } catch (Exception error) {
            record(context, action, "service_failed", error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean sendLegacyBroadcast(Context context, int command) {
        try {
            Intent intent = new Intent("PlayNotifyReceiver");
            intent.setPackage(DEDAO_PACKAGE);
            intent.putExtra("want_do", command);
            context.sendBroadcast(intent);
            record(context, serviceAction(command), "broadcast_fallback", "");
            return true;
        } catch (Exception error) {
            record(context, serviceAction(command), "failed", error.getClass().getSimpleName());
            return false;
        }
    }

    private static String serviceAction(int command) {
        switch (command) {
            case PLAY: return "play";
            case PAUSE: return "pause";
            case PREVIOUS: return "prev";
            case NEXT: return "next";
            default: return null;
        }
    }

    private static void record(Context context, String action, String channel, String error) {
        context.getSharedPreferences("page_probe", Context.MODE_PRIVATE).edit()
                .putString("official_control_action", action == null ? "" : action)
                .putString("official_control_channel", channel)
                .putString("official_control_error", error)
                .putLong("official_control_at", System.currentTimeMillis())
                .apply();
    }

    private OfficialPlayerControl() {
    }
}
