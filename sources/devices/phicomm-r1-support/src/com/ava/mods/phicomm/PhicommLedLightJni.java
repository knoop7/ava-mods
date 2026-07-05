package com.ava.mods.phicomm;

import android.util.Log;

import java.io.File;

/**
 * EchoService {@code libledLight-jni.so} — direct RGB ring control (bypasses msgcenter 4096).
 */
final class PhicommLedLightJni {
    private static final String TAG = "PhicommLedLightJni";
    private static final long MASK_RING = 32767L;
    private static final long MASK_FULL_RING = 549755813887L;

    private static final String[] LIB_PATHS = {
        "/system/lib/libledLight-jni.so",
        "/vendor/lib/libledLight-jni.so",
        "/system/lib64/libledLight-jni.so",
        "/vendor/lib64/libledLight-jni.so",
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

    static void setRingColor(int argb) {
        if (!probe()) {
            return;
        }
        set_color(MASK_RING, argb & 0xFFFFFF);
    }

    /** Directional wake segment — {@code index} is stock msgcenter id 1–24. */
    static void setSegmentColor(int index, int argb) {
        if (!probe() || index < PhicommLightController.WAKEUP_LIGHT_MIN
            || index > PhicommLightController.WAKEUP_LIGHT_MAX) {
            return;
        }
        set_color(PhicommLedBitmask.getMake1(index), argb & 0xFFFFFF);
    }

    static void clearSegment(int index) {
        setSegmentColor(index, 0);
    }

    static void clearWakeSegments() {
        if (!probe()) {
            return;
        }
        for (int i = PhicommLightController.WAKEUP_LIGHT_MIN;
             i <= PhicommLightController.WAKEUP_LIGHT_MAX; i++) {
            clearSegment(i);
        }
    }

    static void clearRing() {
        if (!probe()) {
            return;
        }
        set_color(MASK_RING, 0);
        set_color(MASK_FULL_RING, 0);
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
                set_color(MASK_RING, 0);
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

    private static native void set_color(long bitmask, int color);
}
