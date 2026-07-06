package com.ava.mods.phicomm;

import android.content.Context;

/**
 * Stock Phicomm LED IPC ({@code 4096}) plus optional JNI overrides where noted.
 */
public final class PhicommLightController {
    public static final int LIGHT_WHAT = 4096;
    public static final int LIGHT_ACTION_OPEN = 0;
    public static final int LIGHT_ACTION_CLOSE = 1;

    public static final int LIGHT_ID_DORMANT = 100;
    public static final int LIGHT_ID_LOADING = 203;
    public static final int LIGHT_ID_NET_DISCONNECT = 254;

    public static final int WAKEUP_LIGHT_MIN = 1;
    public static final int WAKEUP_LIGHT_MAX = 24;

    private static int lastLightIndex = -1;

    private final MsgCenterBridge bridge;

    public PhicommLightController(Context context) {
        bridge = new MsgCenterBridge(context);
    }

    public boolean isAvailable() {
        return bridge.isAvailable();
    }

    public void turnOffALLWakeupLight() {
        if (PhicommLedLightJni.isAvailable()) {
            PhicommLedLightJni.clearWakeSegments();
        }
        for (int i = WAKEUP_LIGHT_MIN; i <= WAKEUP_LIGHT_MAX; i++) {
            bridge.sendMessage(LIGHT_WHAT, i, LIGHT_ACTION_CLOSE, null);
        }
    }

    public void turnOffDormantLight() {
        bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_DORMANT, LIGHT_ACTION_CLOSE, null);
    }

    public void turnOffLoadingLight() {
        PhicommRingBreather.stop();
        if (PhicommLedLightJni.isAvailable()) {
            PhicommLedLightJni.clearVoiceLoadingRing();
        }
        bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_LOADING, LIGHT_ACTION_CLOSE, null);
    }

    public void turnOffNetDisconnectedLight() {
        bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_NET_DISCONNECT, LIGHT_ACTION_CLOSE, null);
    }

    public void turnOnDormantLight() {
        bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_DORMANT, LIGHT_ACTION_OPEN, null);
    }

    public void turnOnLoadingLight(int accentRgb) {
        if (PhicommLedLightJni.isAvailable()) {
            // Breathing animation like stock 203 (1500 ms ramp), tinted with the wake accent.
            bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_LOADING, LIGHT_ACTION_CLOSE, null);
            int rgb = accentRgb != 0
                ? accentRgb & 0xFFFFFF
                : PhicommWakeAccentTracker.DEFAULT_WAKE_WORD_1_RGB;
            PhicommRingBreather.start(rgb);
            return;
        }
        // Stock parity: 203 is the blue breathing loading effect and must always show.
        bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_LOADING, LIGHT_ACTION_OPEN, null);
    }

    public void turnOnNetDisconnectedLight() {
        bridge.sendMessage(LIGHT_WHAT, LIGHT_ID_NET_DISCONNECT, LIGHT_ACTION_OPEN, null);
    }

    public void turnOnWakeupIndexLight(int index) {
        turnOnWakeupIndexLight(index, 0);
    }

    public void turnOnWakeupIndexLight(int index, int accentRgb) {
        lastLightIndex = index;
        if (PhicommLedLightJni.isAvailable()) {
            PhicommLedLightJni.clearWakeSegments();
        }
        bridge.sendMessage(LIGHT_WHAT, index, LIGHT_ACTION_OPEN, null);
    }

    public void turnOnWakeupLastLight() {
        if (lastLightIndex >= WAKEUP_LIGHT_MIN && lastLightIndex <= WAKEUP_LIGHT_MAX) {
            bridge.sendMessage(LIGHT_WHAT, lastLightIndex, LIGHT_ACTION_OPEN, null);
        }
    }

    /** Stock playing-music preset (4096, 519). */
    public void showPlayingMusicLight() {
        bridge.sendMessage(LIGHT_WHAT, 519, LIGHT_ACTION_OPEN, null);
    }

    public void turnOffPlayingMusicLight() {
        bridge.sendMessage(LIGHT_WHAT, 519, LIGHT_ACTION_CLOSE, null);
    }

    /** Stock AudioService volume ring segment (4096, 300+n or 400+n, 0). */
    public void showVolumeLed(int lightId) {
        bridge.sendMessage(LIGHT_WHAT, lightId, LIGHT_ACTION_OPEN, null);
    }

    public int getLastLightIndex() {
        return lastLightIndex;
    }
}
