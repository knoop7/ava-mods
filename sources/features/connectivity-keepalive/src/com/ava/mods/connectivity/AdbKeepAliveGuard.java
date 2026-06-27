package com.ava.mods.connectivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps USB/wireless ADB debugging enabled and preserves authorization state.
 * Turning the guard off only stops monitoring; it never revokes ADB or clears keys.
 */
final class AdbKeepAliveGuard {

    private static final String TAG = "AdbKeepAlive";
    private static final String PREFS = "connectivity_keepalive_adb";
    private static final String KEY_WIRELESS = "wireless_adb_enabled";
    private static final String KEY_WIRELESS_PORT = "wireless_adb_port";
    private static final String ADB_KEYS_PATH = "/data/misc/adb/adb_keys";
    private static final long CHECK_INTERVAL_MS = 30_000L;

    private final Context context;
    private final PrivilegedShell shell;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    AdbKeepAliveGuard(Context context, PrivilegedShell shell) {
        this.context = context.getApplicationContext();
        this.shell = shell;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        snapshotAdbState();
        handler.post(tickRunnable);
        Log.i(TAG, "ADB keep-alive started");
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tickRunnable);
        Log.i(TAG, "ADB keep-alive stopped (ADB left unchanged)");
    }

    boolean isRunning() {
        return running;
    }

    private void tick() {
        snapshotAdbState();

        if (!isAdbEnabled()) {
            Log.w(TAG, "ADB debugging is off — re-enabling");
            enableAdb();
        }

        if (prefs.getBoolean(KEY_WIRELESS, false)) {
            ensureWirelessAdb();
        }

        ensureAuthorizationKeys();
    }

    private void snapshotAdbState() {
        if (isAdbEnabled()) {
            String wireless = shell.readSetting("adb_wifi_enabled");
            boolean wirelessOn = "1".equals(wireless);
            SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_WIRELESS, wirelessOn);
            if (wirelessOn) {
                String port = shell.readSetting("adb_wifi_port");
                if (port != null && !port.isEmpty()) {
                    editor.putString(KEY_WIRELESS_PORT, port);
                }
            }
            editor.apply();
        }
    }

    private void enableAdb() {
        shell.setGlobalSetting("adb_enabled", "1");
        shell.setGlobalSetting("development_settings_enabled", "1");
        shell.execute("setprop persist.sys.usb.config adb");
        shell.execute("setprop sys.usb.config adb");
    }

    private void ensureWirelessAdb() {
        shell.setGlobalSetting("adb_wifi_enabled", "1");
        String port = prefs.getString(KEY_WIRELESS_PORT, null);
        if (port != null && !port.isEmpty()) {
            shell.setGlobalSetting("adb_wifi_port", port);
        }
        shell.execute("cmd connectivity tethering enable adb_wifi");
    }

    private void ensureAuthorizationKeys() {
        if (!shell.hasPrivilegedAccess()) {
            return;
        }
        String exists = shell.captureOutput("test -f " + ADB_KEYS_PATH + " && echo present");
        if (exists == null || !exists.contains("present")) {
            Log.w(TAG, "ADB keys file missing — authorization may require re-approval on next host connect");
            return;
        }
        shell.execute("chmod 640 " + ADB_KEYS_PATH);
        shell.execute("chown system:shell " + ADB_KEYS_PATH);
        shell.execute("restorecon " + ADB_KEYS_PATH);
    }

    private boolean isAdbEnabled() {
        String value = shell.readSetting("adb_enabled");
        if ("1".equals(value)) {
            return true;
        }
        String prop = shell.captureOutput("getprop init.svc.adbd");
        return prop != null && prop.trim().equals("running");
    }
}
