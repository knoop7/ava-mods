package com.ava.mods.phicomm;

import android.util.Log;

/**
 * Stock playing-music preset via msgcenter when {@link PhicommLedLightJni} is unavailable.
 * Uses light id 519 ({@code Constant.LIGTH_EFFECT_PLAYING_MUSIC}) + amplitude pulsing.
 */
final class PhicommMusicLightFallback {
    private static final String TAG = "PhicommMusicFallback";
    private static final int LIGHT_PLAYING_MUSIC = 519;
    private static final float PULSE_ON_THRESHOLD = 0.08f;

    private final PhicommLightController lights;
    private boolean effectOn;
    private boolean pulsedOn;

    PhicommMusicLightFallback(PhicommLightController lights) {
        this.lights = lights;
    }

    void startEffect() {
        if (effectOn) {
            return;
        }
        lights.showPlayingMusicLight();
        effectOn = true;
        pulsedOn = true;
        Log.d(TAG, "playing-music effect ON (519)");
    }

    void stopEffect() {
        if (!effectOn) {
            return;
        }
        lights.turnOffPlayingMusicLight();
        effectOn = false;
        pulsedOn = false;
        Log.d(TAG, "playing-music effect OFF");
    }

    /** Amplitude-driven breathe approximation without RGB JNI. */
    void updateAmplitude(float normalizedAmp) {
        if (!effectOn) {
            return;
        }
        boolean shouldPulse = normalizedAmp >= PULSE_ON_THRESHOLD;
        if (shouldPulse == pulsedOn) {
            return;
        }
        pulsedOn = shouldPulse;
        if (shouldPulse) {
            lights.showPlayingMusicLight();
        } else {
            lights.turnOffPlayingMusicLight();
        }
    }

    void clear() {
        stopEffect();
    }
}
