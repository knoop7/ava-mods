package com.ava.mods.portal;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

class PortalScreenControl {

    private static final String TAG = "PortalSupport";
    private static final int GLOBAL_ACTION_LOCK_SCREEN = 8;
    private static final String AVA_ACCESSIBILITY =
            "com.example.ava.services.AvaAccessibilityService";

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
        if (tryAvaAccessibilityLockScreen()) {
            Log.i(TAG, "sleep: Ava accessibility GLOBAL_ACTION_LOCK_SCREEN");
            return true;
        }
        if (tryShizukuDisplayOff()) {
            Log.i(TAG, "sleep: Shizuku setDisplayPower(0)");
            return true;
        }
        PortalPermissionHelper helper = new PortalPermissionHelper(context);
        if (helper.executeShell("input keyevent 223") == 0) {
            Log.i(TAG, "sleep: shell keyevent 223");
            return true;
        }
        if (helper.executeShell("input keyevent 26") == 0) {
            Log.i(TAG, "sleep: shell keyevent 26");
            return true;
        }
        Log.w(TAG, "sleep: no method available — grant WRITE_SECURE_SETTINGS and enable accessibility");
        return false;
    }

    /**
     * Auto-enable Ava's accessibility service via WRITE_SECURE_SETTINGS (same pattern as
     * portal-ha-bridge ScreenControl.enableAccessibility).
     */
    static boolean enableAccessibility(Context context) {
        if (!hasWriteSecureSettings(context)) {
            Log.w(TAG, "enableAccessibility: WRITE_SECURE_SETTINGS not granted");
            return false;
        }
        try {
            String target = context.getPackageName() + "/" + AVA_ACCESSIBILITY;
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) {
                current = "";
            }
            if (!current.contains(target)) {
                String updated = current.isEmpty() ? target : current + ":" + target;
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated);
            }
            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1);
            Log.i(TAG, "enableAccessibility: enabled " + target);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "enableAccessibility: write failed: " + e.getMessage());
            return false;
        }
    }

    static boolean isAccessibilityEnabled(Context context) {
        String flat = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        String target = context.getPackageName() + "/" + AVA_ACCESSIBILITY;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(flat);
        while (splitter.hasNext()) {
            if (target.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean tryAvaAccessibilityLockScreen() {
        try {
            Class<?> bridgeClass = Class.forName("com.example.ava.services.AccessibilityBridge");
            Field serviceField = bridgeClass.getDeclaredField("service");
            serviceField.setAccessible(true);
            Object service = serviceField.get(null);
            if (!(service instanceof AccessibilityService)) {
                return false;
            }
            Method action = AccessibilityService.class.getMethod(
                    "performGlobalAction", int.class);
            Object result = action.invoke(service, GLOBAL_ACTION_LOCK_SCREEN);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            Log.d(TAG, "accessibility lock unavailable: " + e.getMessage());
            return false;
        }
    }

    private static boolean tryShizukuDisplayOff() {
        try {
            Class<?> shizukuUtils = Class.forName("com.example.ava.utils.ShizukuUtils");
            Object instance = shizukuUtils.getField("INSTANCE").get(null);
            Boolean granted = (Boolean) shizukuUtils
                    .getMethod("isShizukuPermissionGranted").invoke(instance);
            if (granted == null || !granted) {
                return false;
            }
            Boolean ok = (Boolean) shizukuUtils
                    .getMethod("setDisplayPower", int.class).invoke(instance, 0);
            return ok != null && ok;
        } catch (Exception e) {
            Log.d(TAG, "Shizuku display off unavailable: " + e.getMessage());
            return false;
        }
    }
}
