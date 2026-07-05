package com.ava.mods.phicomm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;

/**
 * Volume ring LEDs — mirrors stock {@code AudioService} MSG_START_VOLUME_LEDS (4096, 300+n / 400+n).
 * Disabled by default because 官改 {@code AudioService} usually already sends these messages.
 */
final class PhicommVolumeLightWatcher {
    private static final String TAG = "PhicommVolLight";
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final int VOLUME_LED_UP_BASE = 300;
    private static final int VOLUME_LED_DOWN_BASE = 400;
    private static final int VOLUME_LED_STEPS = 15;

    private final Context appContext;
    private final PhicommLightController lights;
    private final AudioManager audioManager;
    private volatile boolean enabled;
    private volatile boolean started;
    private int lastVolume = -1;
    private BroadcastReceiver receiver;

    PhicommVolumeLightWatcher(Context context, PhicommLightController lights) {
        appContext = context.getApplicationContext();
        this.lights = lights;
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void start() {
        if (started || audioManager == null) {
            return;
        }
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!enabled || intent == null) {
                    return;
                }
                if (VOLUME_CHANGED_ACTION.equals(intent.getAction())) {
                    int streamType = intent.getIntExtra(
                        EXTRA_VOLUME_STREAM_TYPE,
                        AudioManager.STREAM_MUSIC
                    );
                    if (streamType != AudioManager.STREAM_MUSIC) {
                        return;
                    }
                    int newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, lastVolume);
                    showVolumeLed(lastVolume, newVolume);
                    lastVolume = newVolume;
                }
            }
        };
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        appContext.registerReceiver(receiver, filter);
        started = true;
        Log.i(TAG, "volume light watcher started enabled=" + enabled);
    }

    void stop() {
        if (!started || receiver == null) {
            return;
        }
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregister failed", t);
        }
        receiver = null;
        started = false;
    }

    private void showVolumeLed(int oldVolume, int newVolume) {
        if (oldVolume < 0 || newVolume == oldVolume) {
            return;
        }
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) {
            return;
        }
        int index = (newVolume * VOLUME_LED_STEPS) / max;
        if (index < 0) {
            index = 0;
        } else if (index > VOLUME_LED_STEPS) {
            index = VOLUME_LED_STEPS;
        }
        boolean increasing = newVolume > oldVolume;
        int lightId = (increasing ? VOLUME_LED_UP_BASE : VOLUME_LED_DOWN_BASE) + index;
        lights.showVolumeLed(lightId);
        Log.d(TAG, "volume LED id=" + lightId + " vol=" + newVolume + "/" + max);
    }
}
