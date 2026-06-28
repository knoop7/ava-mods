package com.ava.mods.connectivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

/**
 * Observes global ADB settings; poll is a 60s fallback only.
 * Reads via ContentResolver — root/Shizuku only when re-enabling ADB.
 */
final class AdbKeepAliveGuard {

    private static final String TAG = "ConnKeepAlive";
    private static final String PREFS = "connectivity_keepalive_adb";
    private static final String KEY_WIRELESS = "wireless_adb_enabled";
    private static final String KEY_WIRELESS_PORT = "wireless_adb_port";
    private static final String ADB_KEYS_PATH = "/data/misc/adb/adb_keys";
    private static final long POLL_INTERVAL_MS = 60_000L;

    private final Context context;
    private final PrivilegedShell shell;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean lastKnownAdbEnabled;
    private ContentObserver settingsObserver;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick("poll");
            handler.postDelayed(this, POLL_INTERVAL_MS);
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
        lastKnownAdbEnabled = isAdbEnabled();
        registerSettingsObserver();
        snapshotAdbState();
        handler.post(pollRunnable);
        if (!lastKnownAdbEnabled) {
            handler.post(() -> tick("start"));
        }
        Log.i(TAG, "ADB keep-alive started (observe + 60s poll, no su on read)");
    }

    void stop() {
        running = false;
        unregisterSettingsObserver();
        handler.removeCallbacks(pollRunnable);
        Log.i(TAG, "ADB keep-alive stopped (ADB left unchanged)");
    }

    boolean isRunning() {
        return running;
    }

    private void registerSettingsObserver() {
        if (settingsObserver != null) {
            return;
        }
        settingsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (!running) {
                    return;
                }
                boolean enabled = isAdbEnabled();
                if (!enabled && lastKnownAdbEnabled) {
                    Log.w(TAG, "ADB turned off — reacting immediately");
                    tick("settings_observer");
                } else if (enabled) {
                    snapshotAdbState();
                }
                lastKnownAdbEnabled = enabled;
            }
        };

        try {
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                    false,
                    settingsObserver);
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("adb_wifi_enabled"),
                    false,
                    settingsObserver);
        } catch (Exception e) {
            Log.w(TAG, "settings observer registration failed: " + e.getMessage());
        }
    }

    private void unregisterSettingsObserver() {
        if (settingsObserver != null) {
            try {
                context.getContentResolver().unregisterContentObserver(settingsObserver);
            } catch (Exception ignored) {
            }
            settingsObserver = null;
        }
    }

    private void tick(String reason) {
        if (!running) {
            return;
        }

        if (isAdbEnabled()) {
            lastKnownAdbEnabled = true;
            return;
        }

        Log.w(TAG, "ADB off (" + reason + ") — re-enabling");
        enableAdb();
        if (prefs.getBoolean(KEY_WIRELESS, false)) {
            ensureWirelessAdb();
        }
        ensureAuthorizationKeys();
        lastKnownAdbEnabled = true;
    }

    private void snapshotAdbState() {
        if (!isAdbEnabled()) {
            return;
        }
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
        lastKnownAdbEnabled = true;
    }

    private void enableAdb() {
        shell.setGlobalSetting(Settings.Global.ADB_ENABLED, "1");
        shell.setGlobalSetting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1");
        shell.executePrivilegedBatch(new String[]{
                "setprop persist.sys.usb.config adb",
                "setprop sys.usb.config adb"
        });
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
        if (!new File(ADB_KEYS_PATH).exists()) {
            return;
        }
        shell.executePrivilegedBatch(new String[]{
                "chmod 640 " + ADB_KEYS_PATH,
                "chown system:shell " + ADB_KEYS_PATH,
                "restorecon " + ADB_KEYS_PATH
        });
    }

    private boolean isAdbEnabled() {
        if ("1".equals(shell.readSetting(Settings.Global.ADB_ENABLED))) {
            return true;
        }
        return "running".equals(shell.getSystemProperty("init.svc.adbd"));
    }
}
