package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

/**
 * Ported from com.phicomm.speaker.device.custom.lights.PhicommLightListener.
 * Implements the four stock voice-session light callbacks only.
 */
public final class PhicommVoiceLightListener {
    private static final String TAG = "PhicommVoiceLightListener";

    private final PhicommLightController controller;
    private int sessionAccentRgb;

    public PhicommVoiceLightListener(Context context) {
        controller = new PhicommLightController(context);
    }

    public boolean isAvailable() {
        return controller.isAvailable();
    }

    public void setSessionAccentColor(int accentRgb) {
        sessionAccentRgb = accentRgb & 0xFFFFFF;
    }

    /** ASR 3201 / DefaultLightsHandler.onWakeupResult */
    public void onWakeupSuccess(int doaAngle) {
        onWakeupSuccess(doaAngle, sessionAccentRgb);
    }

    public void onWakeupSuccess(int doaAngle, int accentRgb) {
        if (accentRgb != 0) {
            sessionAccentRgb = accentRgb & 0xFFFFFF;
        }
        if (doaAngle < 0 || doaAngle >= 360) {
            // No DOA source — a fixed direction (stock maps invalid to index 19) is misleading.
            Log.d(TAG, "onWakeupSuccess without DOA — skip directional LED");
            return;
        }
        int index = PhicommLightIndexProcessor.getIndex(doaAngle);
        Log.d(TAG, "onWakeupSuccess doa=" + doaAngle + " index=" + index
            + " accent=#" + Integer.toHexString(sessionAccentRgb));
        controller.turnOnWakeupIndexLight(index, sessionAccentRgb);
    }

    /** ASR 1102 RECORDING_STOP / DefaultLightsHandler.onASREventRecordingStop */
    public void onRecognizeStart() {
        Log.d(TAG, "onRecognizeStart accent=#" + Integer.toHexString(sessionAccentRgb));
        controller.turnOffALLWakeupLight();
        controller.turnOnLoadingLight(sessionAccentRgb);
    }

    /** TTS 2107 PLAYING_END / DefaultLightsHandler.onTTSEventPlayingEnd */
    public void onTTSEnd() {
        Log.d(TAG, "onTTSEnd");
        controller.turnOffLoadingLight();
    }

    /** doInterrupt / TurnOffWakeLightEvent */
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt");
        controller.turnOffALLWakeupLight();
        controller.turnOffLoadingLight();
    }
}
