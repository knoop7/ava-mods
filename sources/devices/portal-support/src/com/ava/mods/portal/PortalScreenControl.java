package com.ava.mods.portal;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.util.Log;

import java.io.DataOutputStream;
import java.lang.reflect.Method;

class PortalScreenControl {

    private static final String TAG = "PortalSupport";

    static void wake(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "PortalSupport:Wake");
        wakeLock.acquire(500L);
        Log.i(TAG, "wake: FULL_WAKE_LOCK acquired");
    }

    static boolean sleep(Context context) {
        if (tryAccessibilityLockScreen()) {
            Log.i(TAG, "sleep: AccessibilityBridge lock");
            return true;
        }
        if (tryShellSleep()) {
            Log.i(TAG, "sleep: shell keyevent");
            return true;
        }
        Log.w(TAG, "sleep: no method available — grant WRITE_SECURE_SETTINGS and enable accessibility, or use root");
        return false;
    }

    static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean tryAccessibilityLockScreen() {
        String[] classNames = new String[] {
                "com.example.ava.services.AccessibilityBridge"
        };
        String[] methodNames = new String[] {
                "lockScreen", "sleepScreen", "turnScreenOff", "performLockScreen"
        };
        for (String className : classNames) {
            for (String methodName : methodNames) {
                try {
                    Class<?> bridgeClass = Class.forName(className);
                    Object bridge = getKotlinObjectInstance(bridgeClass);
                    Method method = bridgeClass.getMethod(methodName);
                    Object result = method.invoke(bridge);
                    if (result instanceof Boolean) {
                        return (Boolean) result;
                    }
                    return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private static Object getKotlinObjectInstance(Class<?> bridgeClass) throws Exception {
        try {
            return bridgeClass.getField("INSTANCE").get(null);
        } catch (Exception e) {
            return bridgeClass.getDeclaredConstructor().newInstance();
        }
    }

    private static boolean tryShellSleep() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                os.writeBytes("input keyevent 26\n");
                os.writeBytes("exit\n");
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
