package com.ava.mods.portal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Passive STREAM_MUSIC volume observer — no root / no Shizuku.
 * Mirrors Ava core DeviceMusicVolumeMonitor (ContentObserver + VOLUME_CHANGED_ACTION).
 */
final class PortalMediaVolumeMonitor {

    private static final String TAG = "PortalSupport";
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_TYPE_ALIAS =
            "android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS";
    private static final float LEVEL_EPSILON = 0.001f;

    interface Listener {
        void onPhysicalVolume(float level01);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler;
    private final Listener listener;

    private ContentObserver settingsObserver;
    private BroadcastReceiver volumeReceiver;
    private boolean started;
    private float lastLevel = -1f;
    private Runnable pendingDispatch;

    PortalMediaVolumeMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        this.listener = listener;
    }

    float readLevel() {
        if (audioManager == null) {
            return 0f;
        }
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) {
            return 0f;
        }
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float level = cur / (float) max;
        if (level < 0f) {
            return 0f;
        }
        if (level > 1f) {
            return 1f;
        }
        return level;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        lastLevel = readLevel();

        settingsObserver = new ContentObserver(handler) {
            @Override
            public boolean deliverSelfNotifications() {
                return false;
            }

            @Override
            public void onChange(boolean selfChange) {
                scheduleDispatch("content_observer");
            }
        };
        try {
            context.getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI, true, settingsObserver);
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_music"), false, settingsObserver);
        } catch (Exception e) {
            Log.w(TAG, "physical volume: content observer failed", e);
        }

        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !VOLUME_CHANGED_ACTION.equals(intent.getAction())) {
                    return;
                }
                int streamType = intent.getIntExtra(
                        EXTRA_VOLUME_STREAM_TYPE, AudioManager.USE_DEFAULT_STREAM_TYPE);
                int aliasType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE_ALIAS, streamType);
                if (!isMusicStream(streamType) && !isMusicStream(aliasType)) {
                    return;
                }
                scheduleDispatch("broadcast");
            }
        };
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(volumeReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "physical volume: broadcast register failed", e);
        }

        if (listener != null) {
            listener.onPhysicalVolume(lastLevel);
        }
        Log.i(TAG, "physical volume monitor started level=" + lastLevel);
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (pendingDispatch != null) {
            handler.removeCallbacks(pendingDispatch);
            pendingDispatch = null;
        }
        if (settingsObserver != null) {
            try {
                context.getContentResolver().unregisterContentObserver(settingsObserver);
            } catch (Exception ignored) {
            }
            settingsObserver = null;
        }
        if (volumeReceiver != null) {
            try {
                context.unregisterReceiver(volumeReceiver);
            } catch (Exception ignored) {
            }
            volumeReceiver = null;
        }
        Log.i(TAG, "physical volume monitor stopped");
    }

    private void scheduleDispatch(final String source) {
        if (pendingDispatch != null) {
            handler.removeCallbacks(pendingDispatch);
        }
        pendingDispatch = new Runnable() {
            @Override
            public void run() {
                pendingDispatch = null;
                float level = readLevel();
                if (lastLevel >= 0f && Math.abs(lastLevel - level) < LEVEL_EPSILON) {
                    return;
                }
                lastLevel = level;
                Log.d(TAG, "physical volume -> " + level + " (" + source + ")");
                if (listener != null) {
                    listener.onPhysicalVolume(level);
                }
            }
        };
        handler.post(pendingDispatch);
    }

    private static boolean isMusicStream(int streamType) {
        return streamType == AudioManager.STREAM_MUSIC
                || streamType == AudioManager.USE_DEFAULT_STREAM_TYPE;
    }
}
