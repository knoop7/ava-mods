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

    public PhicommVoiceLightListener(Context context) {
        controller = new PhicommLightController(context);
    }

    public boolean isAvailable() {
        return controller.isAvailable();
    }

    /** ASR 3201 / DefaultLightsHandler.onWakeupResult */
    public void onWakeupSuccess(int doaAngle) {
        // Stock passes raw DOA even when -1; perHandleAngle() clamps invalid to 0°.
        int index = PhicommLightIndexProcessor.getIndex(doaAngle);
        Log.d(TAG, "onWakeupSuccess doa=" + doaAngle + " index=" + index);
        controller.turnOnWakeupIndexLight(index);
    }

    /** ASR 1102 RECORDING_STOP / DefaultLightsHandler.onASREventRecordingStop */
    public void onRecognizeStart() {
        Log.d(TAG, "onRecognizeStart");
        controller.turnOffALLWakeupLight();
        controller.turnOnLoadingLight();
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
