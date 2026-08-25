package com.ava.mods.vision;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Drives Ava's screensaver from mod-side face detection.
 *
 * The host disables its own luma person-wake probe while a camera_stream mod owns
 * Camera2, so this bridge replaces it with real face detection: presence pokes
 * onUserInteraction (dismiss + reset idle), continued presence keeps resetting the
 * idle timer, and absence simply stops poking so the normal countdown resumes.
 */
public final class ScreensaverBridge {

    private static final String TAG = "VisionScreensaver";
    private static final String CONTROLLER = "com.example.ava.services.ScreensaverController";

    private static final long PRESENT_POKE_INTERVAL_MS = 4000L;
    private static final long WAKE_DEBOUNCE_MS = 1500L;

    private final ClassLoader loader;
    private volatile Object controller;
    private volatile Method onUserInteraction;
    private volatile Method resetIdleTimer;
    private volatile Method isVisible;
    private volatile boolean resolved;
    private volatile boolean available;

    private volatile boolean lastPresent;
    private volatile long lastWakeAt;
    private volatile long lastPokeAt;
    private volatile int wakeCount;

    public ScreensaverBridge(ClassLoader loader) {
        this.loader = loader;
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    public int getWakeCount() {
        return wakeCount;
    }

    private void resolve() {
        if (resolved) return;
        synchronized (this) {
            if (resolved) return;
            resolved = true;
            try {
                Class<?> cls = Class.forName(CONTROLLER, false, loader);
                Field instance = cls.getField("INSTANCE");
                controller = instance.get(null);
                onUserInteraction = cls.getMethod("onUserInteraction");
                resetIdleTimer = cls.getMethod("resetIdleTimer");
                try {
                    isVisible = cls.getMethod("isScreensaverVisible");
                } catch (NoSuchMethodException ignored) {
                    isVisible = null;
                }
                available = controller != null;
                Log.i(TAG, "Screensaver bridge ready");
            } catch (Throwable t) {
                available = false;
                Log.w(TAG, "Screensaver bridge unavailable: " + t.getMessage());
            }
        }
    }

    private boolean screensaverVisible() {
        Method m = isVisible;
        if (m == null) return false;
        try {
            Object v = m.invoke(controller);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Report the current person-presence verdict from face detection.
     *
     * @param present true while at least one face is in frame
     */
    public void onPresence(boolean present) {
        resolve();
        if (!available) return;

        long now = System.currentTimeMillis();
        boolean rising = present && !lastPresent;
        lastPresent = present;

        if (!present) return;

        if (rising || screensaverVisible()) {
            if (now - lastWakeAt < WAKE_DEBOUNCE_MS) return;
            lastWakeAt = now;
            lastPokeAt = now;
            invoke(onUserInteraction, "onUserInteraction");
            wakeCount++;
            return;
        }

        if (now - lastPokeAt >= PRESENT_POKE_INTERVAL_MS) {
            lastPokeAt = now;
            invoke(resetIdleTimer, "resetIdleTimer");
        }
    }

    public void reset() {
        lastPresent = false;
        lastWakeAt = 0L;
        lastPokeAt = 0L;
    }

    private void invoke(Method method, String label) {
        if (method == null) return;
        try {
            method.invoke(controller);
        } catch (Throwable t) {
            Log.w(TAG, label + " failed: " + t.getMessage());
        }
    }
}
