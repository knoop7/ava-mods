package com.ava.mods.phicomm;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Detects Ava/Sendspin/DLNA music playback via {@link AudioManager#isMusicActive()}.
 * This tracks the same mixed output path Ava uses for media (not EchoService msgcenter player).
 */
final class AvaPlaybackMonitor {
    private static final String TAG = "AvaPlaybackMonitor";
    private static final long POLL_MS = 400L;

    interface Listener {
        void onMusicActiveChanged(boolean active);
    }

    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean started;
    private boolean lastActive;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            boolean active = false;
            try {
                active = audioManager != null && audioManager.isMusicActive();
            } catch (Throwable t) {
                Log.w(TAG, "isMusicActive failed", t);
            }
            if (active != lastActive) {
                lastActive = active;
                if (listener != null) {
                    listener.onMusicActiveChanged(active);
                }
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    AvaPlaybackMonitor(Context context) {
        audioManager = (AudioManager) context.getApplicationContext()
            .getSystemService(Context.AUDIO_SERVICE);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        lastActive = audioManager != null && audioManager.isMusicActive();
        handler.post(pollRunnable);
        Log.i(TAG, "started lastActive=" + lastActive);
    }

    void stop() {
        started = false;
        handler.removeCallbacks(pollRunnable);
    }

    boolean isMusicActive() {
        try {
            return audioManager != null && audioManager.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }
}
