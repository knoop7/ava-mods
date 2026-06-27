package com.ava.mods.connectivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * WiFi + ADB keep-alive mod manager.
 *
 * Two mod-setting switches only (no Home Assistant entities).
 * Guards start immediately when enabled and restore on manager init.
 */
public class ConnectivityKeepAliveManager {

    private static final String TAG = "ConnKeepAlive";
    private static final String PREFS = "connectivity_keepalive_manager";
    private static final String KEY_ENABLE_WIFI = "enable_wifi_keepalive";
    private static final String KEY_ENABLE_ADB = "enable_adb_keepalive";

    private static volatile ConnectivityKeepAliveManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final PrivilegedShell shell;
    private final WifiKeepAliveGuard wifiGuard;
    private final AdbKeepAliveGuard adbGuard;

    private volatile boolean enableWifiKeepalive;
    private volatile boolean enableAdbKeepalive;

    private ConnectivityKeepAliveManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.shell = new PrivilegedShell(this.context);
        this.wifiGuard = new WifiKeepAliveGuard(this.context, shell);
        this.adbGuard = new AdbKeepAliveGuard(this.context, shell);
        migrateLegacyState();
        restorePersistedState();
    }

    public static ConnectivityKeepAliveManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ConnectivityKeepAliveManager.class) {
                if (instance == null) {
                    instance = new ConnectivityKeepAliveManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        switch (key) {
            case "enable_wifi_keepalive":
                enableWifiKeepalive = parseBoolean(value);
                prefs.edit().putBoolean(KEY_ENABLE_WIFI, enableWifiKeepalive).apply();
                updateWifiSubsystem();
                break;
            case "enable_adb_keepalive":
                enableAdbKeepalive = parseBoolean(value);
                prefs.edit().putBoolean(KEY_ENABLE_ADB, enableAdbKeepalive).apply();
                updateAdbSubsystem();
                break;
            default:
                break;
        }
    }

    private void migrateLegacyState() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean migrated = false;
        boolean needApply = false;
        if (prefs.contains("wifi_keepalive_active")) {
            if (prefs.getBoolean("wifi_keepalive_active", false)
                    && !prefs.getBoolean(KEY_ENABLE_WIFI, false)) {
                editor.putBoolean(KEY_ENABLE_WIFI, true);
                migrated = true;
            }
            editor.remove("wifi_keepalive_active");
            needApply = true;
        }
        if (prefs.contains("adb_keepalive_active")) {
            if (prefs.getBoolean("adb_keepalive_active", false)
                    && !prefs.getBoolean(KEY_ENABLE_ADB, false)) {
                editor.putBoolean(KEY_ENABLE_ADB, true);
                migrated = true;
            }
            editor.remove("adb_keepalive_active");
            needApply = true;
        }
        if (needApply) {
            editor.apply();
        }
        if (migrated) {
            Log.i(TAG, "migrated legacy HA switch state into mod settings");
        }
    }

    private void restorePersistedState() {
        enableWifiKeepalive = prefs.getBoolean(KEY_ENABLE_WIFI, false);
        enableAdbKeepalive = prefs.getBoolean(KEY_ENABLE_ADB, false);
        Log.i(TAG, "restored state wifi=" + enableWifiKeepalive + " adb=" + enableAdbKeepalive);
        updateWifiSubsystem();
        updateAdbSubsystem();
    }

    private void updateWifiSubsystem() {
        if (enableWifiKeepalive && !wifiGuard.isRunning()) {
            if (!shell.hasPrivilegedAccess()) {
                shell.ensurePrivilegedAccess();
            }
            wifiGuard.start();
        } else if (!enableWifiKeepalive && wifiGuard.isRunning()) {
            wifiGuard.stop();
        }
    }

    private void updateAdbSubsystem() {
        if (enableAdbKeepalive && !adbGuard.isRunning()) {
            if (!shell.hasPrivilegedAccess()) {
                shell.ensurePrivilegedAccess();
            }
            adbGuard.start();
        } else if (!enableAdbKeepalive && adbGuard.isRunning()) {
            adbGuard.stop();
        }
    }

    private boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }
}
