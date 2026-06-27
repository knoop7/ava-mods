package com.ava.mods.connectivity;

import android.content.Context;
import android.util.Log;

/**
 * WiFi + ADB keep-alive mod manager.
 *
 * Four switches (all default off):
 * - enable_wifi_keepalive / enable_adb_keepalive: mod settings, gate HA entities
 * - wifi_keepalive / adb_keepalive: Home Assistant runtime guards
 *
 * Safety: disabling a guard only stops monitoring. This mod never turns WiFi or ADB off.
 */
public class ConnectivityKeepAliveManager {

    private static final String TAG = "ConnKeepAlive";
    private static volatile ConnectivityKeepAliveManager instance;

    private final Context context;
    private final PrivilegedShell shell;
    private final WifiKeepAliveGuard wifiGuard;
    private final AdbKeepAliveGuard adbGuard;

    private volatile boolean enableWifiKeepalive;
    private volatile boolean enableAdbKeepalive;
    private volatile boolean wifiKeepaliveActive;
    private volatile boolean adbKeepaliveActive;

    private ConnectivityKeepAliveManager(Context context) {
        this.context = context.getApplicationContext();
        this.shell = new PrivilegedShell(this.context);
        this.wifiGuard = new WifiKeepAliveGuard(this.context, shell);
        this.adbGuard = new AdbKeepAliveGuard(this.context, shell);
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
                updateWifiSubsystem();
                break;
            case "enable_adb_keepalive":
                enableAdbKeepalive = parseBoolean(value);
                updateAdbSubsystem();
                break;
            default:
                break;
        }
    }

    public void setWifiKeepalive(String enabled) {
        wifiKeepaliveActive = parseBoolean(enabled);
        updateWifiSubsystem();
    }

    public boolean isWifiKeepaliveEnabled() {
        return wifiKeepaliveActive;
    }

    public void setAdbKeepalive(String enabled) {
        adbKeepaliveActive = parseBoolean(enabled);
        updateAdbSubsystem();
    }

    public boolean isAdbKeepaliveEnabled() {
        return adbKeepaliveActive;
    }

    private void updateWifiSubsystem() {
        boolean shouldRun = enableWifiKeepalive && wifiKeepaliveActive;
        if (shouldRun && !wifiGuard.isRunning()) {
            if (!shell.hasPrivilegedAccess()) {
                Log.w(TAG, "WiFi keep-alive needs root or Shizuku — requesting Shizuku authorization");
                shell.ensurePrivilegedAccess();
            }
            wifiGuard.start();
        } else if (!shouldRun && wifiGuard.isRunning()) {
            wifiGuard.stop();
        }
    }

    private void updateAdbSubsystem() {
        boolean shouldRun = enableAdbKeepalive && adbKeepaliveActive;
        if (shouldRun && !adbGuard.isRunning()) {
            if (!shell.hasPrivilegedAccess()) {
                Log.w(TAG, "ADB keep-alive needs root or Shizuku — requesting Shizuku authorization");
                shell.ensurePrivilegedAccess();
            }
            adbGuard.start();
        } else if (!shouldRun && adbGuard.isRunning()) {
            adbGuard.stop();
        }
    }

    private boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }
}
