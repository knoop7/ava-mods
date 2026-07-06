package com.ava.mods.phicomm;

import android.util.Log;

import com.phicomm.speaker.player.light.LedLight;

import java.io.File;

/**
 * Access to EchoService {@code libledLight-jni.so} — direct RGB ring control (bypasses
 * msgcenter 4096). All native calls go through {@link LedLight}, which keeps the stock
 * class name the .so binds its single {@code set_1color} symbol to.
 */
final class PhicommLedLightJni {
    private static final String TAG = "PhicommLedLightJni";

    private static final String[] LIB_PATHS = {
        "/system/lib/libledLight-jni.so",
        "/vendor/lib/libledLight-jni.so",
        "/system/lib64/libledLight-jni.so",
        "/vendor/lib64/libledLight-jni.so",
        "/system/app/EchoService/lib/arm/libledLight-jni.so",
    };

    private static Boolean probed;
    private static boolean libraryLoaded;

    private PhicommLedLightJni() {
    }

    static boolean isAvailable() {
        return probe();
    }

    static String backendLabel() {
        return probe() ? "jni" : "msgcenter";
    }

    /** RGB ring (LEDs 1–15) solid color. */
    static void setRingColor(int argb) {
        if (!probe()) {
            return;
        }
        LedLight.setColorForce(PhicommLedBitmask.MASK_RGB_RING, argb & 0xFFFFFF);
    }

    /** Stock-visualizer masked write with stock color de-dup. */
    static void setVisualizerColor(long mask, int argb) {
        if (!probe()) {
            return;
        }
        LedLight.setColor(mask, argb & 0xFFFFFF);
    }

    /** Directional white wake LED — {@code index} is stock msgcenter id 1–24. */
    static void setSegmentColor(int index, int argb) {
        if (!probe()) {
            return;
        }
        long mask = PhicommLedBitmask.wakeSegmentMask(index);
        if (mask == 0L) {
            return;
        }
        LedLight.setColorForce(mask, argb & 0xFFFFFF);
    }

    static void clearWakeSegments() {
        if (!probe()) {
            return;
        }
        LedLight.setColorForce(PhicommLedBitmask.MASK_WHITE_RING, 0);
    }

    static void clearVoiceLoadingRing() {
        if (!probe()) {
            return;
        }
        LedLight.setColorForce(PhicommLedBitmask.MASK_RGB_RING, 0);
    }

    /** Full reset after music RGB — do not use for voice session teardown. */
    static void clearMusicRing() {
        if (!probe()) {
            return;
        }
        LedLight.setColorForce(PhicommLedBitmask.MASK_RGB_RING, 0);
        LedLight.setColorForce(PhicommLedBitmask.MASK_ALL, 0);
    }

    private static boolean probe() {
        if (probed != null) {
            return probed.booleanValue();
        }
        synchronized (PhicommLedLightJni.class) {
            if (probed != null) {
                return probed.booleanValue();
            }
            if (!loadLibrary()) {
                probed = Boolean.FALSE;
                Log.i(TAG, "libledLight-jni not available — using msgcenter fallback");
                return false;
            }
            try {
                LedLight.setColorForce(PhicommLedBitmask.MASK_RGB_RING, 0);
                probed = Boolean.TRUE;
                Log.i(TAG, "libledLight-jni probe OK");
            } catch (Throwable t) {
                probed = Boolean.FALSE;
                Log.i(TAG, "libledLight-jni probe failed: " + t.getMessage());
            }
            return probed.booleanValue();
        }
    }

    private static boolean loadLibrary() {
        if (libraryLoaded) {
            return true;
        }
        for (String path : LIB_PATHS) {
            if (!new File(path).exists()) {
                continue;
            }
            try {
                System.load(path);
                libraryLoaded = true;
                Log.i(TAG, "loaded " + path);
                return true;
            } catch (UnsatisfiedLinkError e) {
                Log.d(TAG, "load failed " + path + ": " + e.getMessage());
            }
        }
        try {
            System.loadLibrary("ledLight-jni");
            libraryLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "loadLibrary ledLight-jni failed: " + e.getMessage());
            return false;
        }
    }
}
