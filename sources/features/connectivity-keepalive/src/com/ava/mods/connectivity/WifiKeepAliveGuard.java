package com.ava.mods.connectivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps WiFi enabled and reconnects to the last known network after crashes or reboots.
 * Turning the guard off only stops monitoring; it never disables WiFi.
 */
final class WifiKeepAliveGuard {

    private static final String TAG = "WifiKeepAlive";
    private static final String PREFS = "connectivity_keepalive_wifi";
    private static final String KEY_SSID = "last_ssid";
    private static final String KEY_NETWORK_ID = "last_network_id";
    private static final long CHECK_INTERVAL_MS = 30_000L;

    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
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

    WifiKeepAliveGuard(Context context, PrivilegedShell shell) {
        this.context = context.getApplicationContext();
        this.shell = shell;
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        rememberCurrentNetwork();
        handler.post(tickRunnable);
        Log.i(TAG, "WiFi keep-alive started");
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tickRunnable);
        Log.i(TAG, "WiFi keep-alive stopped (WiFi left unchanged)");
    }

    boolean isRunning() {
        return running;
    }

    private void tick() {
        rememberCurrentNetwork();

        if (!isWifiEnabled()) {
            Log.w(TAG, "WiFi is off — re-enabling");
            enableWifi();
            handler.postDelayed(this::attemptReconnect, 3_000L);
            return;
        }

        if (!isWifiConnected()) {
            Log.w(TAG, "WiFi on but disconnected — reconnecting to saved network");
            attemptReconnect();
        }
    }

    private void rememberCurrentNetwork() {
        if (!isWifiConnected()) {
            return;
        }
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            return;
        }
        String ssid = normalizeSsid(info.getSSID());
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) {
            return;
        }
        prefs.edit()
                .putString(KEY_SSID, ssid)
                .putInt(KEY_NETWORK_ID, info.getNetworkId())
                .apply();
    }

    private void enableWifi() {
        try {
            wifiManager.setWifiEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "WifiManager.setWifiEnabled failed: " + e.getMessage());
        }
        shell.execute("svc wifi enable");
        shell.setGlobalSetting("wifi_on", "1");
    }

    private void attemptReconnect() {
        String savedSsid = prefs.getString(KEY_SSID, null);
        int savedNetworkId = prefs.getInt(KEY_NETWORK_ID, -1);

        if (savedNetworkId >= 0) {
            try {
                boolean enabled = wifiManager.enableNetwork(savedNetworkId, true);
                if (enabled) {
                    wifiManager.reconnect();
                    Log.i(TAG, "reconnect via saved networkId=" + savedNetworkId);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "enableNetwork failed: " + e.getMessage());
            }
        }

        if (savedSsid != null && !savedSsid.isEmpty()) {
            shell.execute("cmd wifi reconnect");
            shell.execute("cmd -w wifi reconnect");
            if (shell.hasPrivilegedAccess()) {
                shell.execute("cmd wifi connect-network \"" + escapeShell(savedSsid) + "\" open");
            }
            try {
                wifiManager.reconnect();
            } catch (Exception e) {
                Log.w(TAG, "wifiManager.reconnect failed: " + e.getMessage());
            }
            Log.i(TAG, "reconnect requested for ssid=" + savedSsid);
        }
    }

    private boolean isWifiEnabled() {
        try {
            return wifiManager.isWifiEnabled();
        } catch (Exception e) {
            String value = shell.readSetting("wifi_on");
            return "1".equals(value);
        }
    }

    private boolean isWifiConnected() {
        try {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) {
                return false;
            }
            String ssid = normalizeSsid(info.getSSID());
            return ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid);
        }
    }

    private String normalizeSsid(String raw) {
        if (raw == null) {
            return null;
        }
        String ssid = raw.trim();
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private String escapeShell(String value) {
        return value.replace("\"", "\\\"");
    }
}
