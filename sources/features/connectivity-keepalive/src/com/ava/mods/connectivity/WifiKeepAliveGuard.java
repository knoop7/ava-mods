package com.ava.mods.connectivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Listens for WiFi off/disconnect events and polls as backup.
 * Re-enables WiFi and reconnects to the last known network immediately.
 */
final class WifiKeepAliveGuard {

    private static final String TAG = "WifiKeepAlive";
    private static final String PREFS = "connectivity_keepalive_wifi";
    private static final String KEY_SSID = "last_ssid";
    private static final String KEY_NETWORK_ID = "last_network_id";
    private static final long POLL_INTERVAL_MS = 10_000L;

    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private final PrivilegedShell shell;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private BroadcastReceiver wifiReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;

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
        registerListeners();
        rememberCurrentNetwork();
        handler.post(pollRunnable);
        handler.post(() -> tick("start"));
        Log.i(TAG, "WiFi keep-alive started (listen + poll)");
    }

    void stop() {
        running = false;
        unregisterListeners();
        handler.removeCallbacks(pollRunnable);
        Log.i(TAG, "WiFi keep-alive stopped (WiFi left unchanged)");
    }

    boolean isRunning() {
        return running;
    }

    private void registerListeners() {
        if (wifiReceiver == null) {
            wifiReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (!running || intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                        int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                        if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                            Log.w(TAG, "WiFi switch turned off — reacting immediately");
                            handler.post(() -> tick("wifi_off"));
                        } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                            handler.postDelayed(() -> tick("wifi_on"), 2_000L);
                        }
                    } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                        handler.post(() -> {
                            if (running && isWifiEnabled() && !isWifiConnected()) {
                                tick("network_lost");
                            } else {
                                rememberCurrentNetwork();
                            }
                        });
                    }
                }
            };
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(wifiReceiver, filter);
        }

        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLost(Network network) {
                    if (running) {
                        handler.post(() -> tick("network_callback_lost"));
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (running && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        handler.post(() -> rememberCurrentNetwork());
                    }
                }
            };
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback, handler);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback, handler);
            }
        } catch (Exception e) {
            Log.w(TAG, "network callback registration failed: " + e.getMessage());
        }
    }

    private void unregisterListeners() {
        if (wifiReceiver != null) {
            try {
                context.unregisterReceiver(wifiReceiver);
            } catch (Exception ignored) {
            }
        }
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
    }

    private void tick(String reason) {
        if (!running) {
            return;
        }
        rememberCurrentNetwork();

        if (!isWifiEnabled()) {
            Log.w(TAG, "WiFi off (" + reason + ") — re-enabling");
            enableWifi();
            handler.postDelayed(() -> attemptReconnect("after_enable"), 2_000L);
            return;
        }

        if (!isWifiConnected()) {
            Log.w(TAG, "WiFi disconnected (" + reason + ") — reconnecting");
            attemptReconnect(reason);
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

    private void attemptReconnect(String reason) {
        String savedSsid = prefs.getString(KEY_SSID, null);
        int savedNetworkId = prefs.getInt(KEY_NETWORK_ID, -1);

        if (savedNetworkId >= 0) {
            try {
                if (wifiManager.enableNetwork(savedNetworkId, true)) {
                    wifiManager.reconnect();
                    Log.i(TAG, "reconnect via networkId=" + savedNetworkId + " (" + reason + ")");
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
            Log.i(TAG, "reconnect requested for ssid=" + savedSsid + " (" + reason + ")");
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
