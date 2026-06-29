package com.ava.mods.echoshow;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/**
 * Echo Show screensaver dark-off sleep/wake. Only invoked via ModDeviceSupport hooks
 * when echo-show-support mod is enabled and isSupported() is true.
 */
final class EchoShowScreenControl {
    private static final String TAG = "EchoShowSupport";
    private static final int MIN_BRIGHTNESS = 10;

    private static volatile int cachedBrightness = 128;

    private EchoShowScreenControl() {
    }

    static boolean sleepForDark(Context context) {
        Context appContext = context.getApplicationContext();
        if (!EchoShowPrivilegedShell.isShizukuGranted() && !EchoShowPrivilegedShell.isRootAvailable()) {
            Log.w(TAG, "sleepForDark: no Shizuku or root available");
            return false;
        }

        cacheCurrentBrightness();

        if (EchoShowPrivilegedShell.setDisplayPower(0)) {
            Log.i(TAG, "sleepForDark: display power off (Shizuku)");
            return true;
        }

        if (EchoShowPrivilegedShell.execShell("input keyevent 223") == 0) {
            Log.i(TAG, "sleepForDark: display sleep keyevent 223");
            return true;
        }

        if (EchoShowPrivilegedShell.execShell("input keyevent 26") == 0) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null || !pm.isInteractive()) {
                Log.i(TAG, "sleepForDark: power key sleep");
                return true;
            }
        }

        if (EchoShowPrivilegedShell.writeBacklightBrightness(MIN_BRIGHTNESS)) {
            Log.i(TAG, "sleepForDark: fallback min brightness " + MIN_BRIGHTNESS);
            return true;
        }

        Log.w(TAG, "sleepForDark: all strategies failed");
        return false;
    }

    static boolean wakeFromDark(Context context) {
        Context appContext = context.getApplicationContext();

        if (EchoShowPrivilegedShell.setDisplayPower(2)) {
            Log.i(TAG, "wakeFromDark: display power on (Shizuku)");
            restoreBrightness();
            return true;
        }

        if (EchoShowPrivilegedShell.execShell("input keyevent 26") == 0) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isInteractive()) {
                Log.i(TAG, "wakeFromDark: power key wake");
                restoreBrightness();
                return true;
            }
        }

        int target = cachedBrightness > MIN_BRIGHTNESS ? cachedBrightness : 128;
        if (EchoShowPrivilegedShell.writeBacklightBrightness(target)) {
            Log.i(TAG, "wakeFromDark: restored brightness " + target);
            return true;
        }

        Log.w(TAG, "wakeFromDark: all strategies failed");
        return false;
    }

    private static void cacheCurrentBrightness() {
        try {
            Class<?> rootUtils = Class.forName("com.example.ava.utils.RootUtils");
            Object instance = rootUtils.getField("INSTANCE").get(null);
            if (instance != null) {
                Integer value = (Integer) rootUtils.getMethod("readBacklightBrightness").invoke(instance);
                if (value != null && value > 0) {
                    cachedBrightness = value;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void restoreBrightness() {
        int target = cachedBrightness > MIN_BRIGHTNESS ? cachedBrightness : 128;
        EchoShowPrivilegedShell.writeBacklightBrightness(target);
    }
}
