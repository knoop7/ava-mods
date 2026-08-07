package com.ava.mods.portal;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Portal system chrome (status / nav bar) via {@code Settings.Global.policy_control}.
 * Same approach as Immortal's provisioner ({@code immersive.status=*}) and the
 * Browser Display kiosk discussion in Ava #156 — swipe-from-edge can still
 * transiently reveal bars without Lock Task / device owner.
 */
final class PortalSystemUiController {

    private static final String TAG = "PortalSupport";
    private static final String POLICY_CONTROL = "policy_control";

    static final String MODE_OFF = "off";
    /** Hide status bar (Portal top Back / Wi‑Fi chrome). Immortal default. */
    static final String MODE_STATUS = "status";
    /** Hide status + navigation bars. */
    static final String MODE_FULL = "full";

    private PortalSystemUiController() {
    }

    static String normalizeMode(String mode) {
        if (mode == null) {
            return MODE_OFF;
        }
        String trimmed = mode.trim().toLowerCase();
        if (MODE_OFF.equals(trimmed) || MODE_STATUS.equals(trimmed) || MODE_FULL.equals(trimmed)) {
            return trimmed;
        }
        return MODE_OFF;
    }

    static boolean apply(Context context, PortalPermissionHelper helper, String mode) {
        String normalized = normalizeMode(mode);
        String value;
        if (MODE_STATUS.equals(normalized)) {
            value = "immersive.status=*";
        } else if (MODE_FULL.equals(normalized)) {
            value = "immersive.full=*";
        } else {
            value = null;
        }

        if (writeSettings(context, value)) {
            Log.i(TAG, "system UI mode -> " + normalized + " (Settings.Global)");
            return true;
        }
        if (helper != null && writeShell(helper, value)) {
            Log.i(TAG, "system UI mode -> " + normalized + " (shell)");
            return true;
        }
        Log.w(TAG, "system UI mode failed — need WRITE_SECURE_SETTINGS or Shizuku/root");
        return false;
    }

    private static boolean writeSettings(Context context, String value) {
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            if (value == null || value.isEmpty()) {
                return Settings.Global.putString(context.getContentResolver(), POLICY_CONTROL, null)
                        || Settings.Global.putString(context.getContentResolver(), POLICY_CONTROL, "");
            }
            return Settings.Global.putString(context.getContentResolver(), POLICY_CONTROL, value);
        } catch (SecurityException e) {
            Log.d(TAG, "policy_control Settings write blocked: " + e.getMessage());
            return false;
        }
    }

    private static boolean writeShell(PortalPermissionHelper helper, String value) {
        String cmd;
        if (value == null || value.isEmpty()) {
            cmd = "settings delete global " + POLICY_CONTROL;
        } else {
            // Quote so the shell keeps the literal trailing *
            cmd = "settings put global " + POLICY_CONTROL + " '" + value + "'";
        }
        return helper.executeShell(cmd) == 0;
    }
}
