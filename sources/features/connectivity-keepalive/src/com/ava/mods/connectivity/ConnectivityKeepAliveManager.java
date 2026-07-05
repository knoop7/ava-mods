package com.ava.mods.connectivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * WiFi + ADB keep-alive mod manager.
 *
 * No Home Assistant entities — Ava only calls applyConfig when entities exist,
 * so we bootstrap via ModDeviceSupport hooks and read mod_configs/*.json directly.
 */
public class ConnectivityKeepAliveManager {

    private static final String TAG = "ConnKeepAlive";
    private static final String MOD_ID = "connectivity-keepalive";
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
        bootstrapFromAvaConfig();
        Log.i(TAG, "manager ready wifi=" + enableWifiKeepalive + " adb=" + enableAdbKeepalive);
    }

    public static ConnectivityKeepAliveManager getInstance(Context context) {
        ConnectivityKeepAliveManager current = instance;
        if (current == null) {
            synchronized (ConnectivityKeepAliveManager.class) {
                current = instance;
                if (current == null) {
                    current = new ConnectivityKeepAliveManager(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    /** ModDeviceSupport hook — keeps manager alive without HA entities. */
    public boolean isSupported() {
        return true;
    }

    public boolean isSupported(Context context) {
        return true;
    }

    /** Called during VoiceSatelliteService startup; re-sync config after restarts. */
    public boolean grantOverlayPermissionIfNeeded() {
        bootstrapFromAvaConfig();
        return false;
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        return grantOverlayPermissionIfNeeded();
    }

    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        wifiGuard.stop();
        adbGuard.stop();
        instance = null;
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

    private void bootstrapFromAvaConfig() {
        if (syncFromAvaConfigStore()) {
            return;
        }
        restorePersistedState();
    }

    private boolean syncFromAvaConfigStore() {
        File configFile = new File(context.getFilesDir(), "mod_configs/" + MOD_ID + ".json");
        if (!configFile.exists()) {
            return false;
        }

        try {
            String json = readAll(configFile);
            if (json.trim().isEmpty()) {
                return false;
            }

            JSONObject root = new JSONObject(json);
            boolean wifi = root.has(KEY_ENABLE_WIFI)
                    ? parseBoolean(root.getString(KEY_ENABLE_WIFI))
                    : prefs.getBoolean(KEY_ENABLE_WIFI, false);
            boolean adb = root.has(KEY_ENABLE_ADB)
                    ? parseBoolean(root.getString(KEY_ENABLE_ADB))
                    : prefs.getBoolean(KEY_ENABLE_ADB, false);

            boolean changed = wifi != enableWifiKeepalive || adb != enableAdbKeepalive;
            enableWifiKeepalive = wifi;
            enableAdbKeepalive = adb;
            prefs.edit()
                    .putBoolean(KEY_ENABLE_WIFI, wifi)
                    .putBoolean(KEY_ENABLE_ADB, adb)
                    .apply();

            if (changed) {
                Log.i(TAG, "synced Ava mod config wifi=" + wifi + " adb=" + adb);
            }
            updateWifiSubsystem();
            updateAdbSubsystem();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "failed to read Ava mod config: " + e.getMessage());
            return false;
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
        Log.i(TAG, "restored prefs wifi=" + enableWifiKeepalive + " adb=" + enableAdbKeepalive);
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

    private static String readAll(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }
}
