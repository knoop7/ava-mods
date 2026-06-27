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
 * Never calls WifiManager without a fallback — Ava host lacks ACCESS_WIFI_STATE.
 */
final class WifiKeepAliveGuard {

    private static final String TAG = "ConnKeepAlive";
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
    private volatile boolean wifiManagerUsable = true;
    private BroadcastReceiver wifiReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            safeTick("poll");
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
        safeRememberCurrentNetwork();
        handler.post(pollRunnable);
        handler.post(() -> safeTick("start"));
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
                    try {
                        String action = intent.getAction();
                        if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                            int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                            if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                                Log.w(TAG, "WiFi switch turned off — reacting immediately");
                                handler.post(() -> safeTick("wifi_off"));
                            } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                                handler.postDelayed(() -> safeTick("wifi_on"), 2_000L);
                            }
                        } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                            handler.post(() -> {
                                if (running && isWifiEnabled() && !isWifiConnected()) {
                                    safeTick("network_lost");
                                } else {
                                    safeRememberCurrentNetwork();
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "wifi broadcast handler failed: " + e.getMessage());
                    }
                }
            };
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(wifiReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "wifi broadcast registration failed: " + e.getMessage());
        }

        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLost(Network network) {
                    if (running) {
                        handler.post(() -> safeTick("network_callback_lost"));
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (running && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        handler.post(() -> safeRememberCurrentNetwork());
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

    private void safeTick(String reason) {
        try {
            tick(reason);
        } catch (Exception e) {
            Log.w(TAG, "tick failed (" + reason + "): " + e.getMessage());
        }
    }

    private void tick(String reason) {
        if (!running) {
            return;
        }
        safeRememberCurrentNetwork();

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

    private void safeRememberCurrentNetwork() {
        try {
            rememberCurrentNetwork();
        } catch (Exception e) {
            Log.w(TAG, "rememberCurrentNetwork failed: " + e.getMessage());
        }
    }

    private void rememberCurrentNetwork() {
        if (!isWifiConnected()) {
            return;
        }

        WifiInfo info = safeGetConnectionInfo();
        if (info != null) {
            String ssid = normalizeSsid(info.getSSID());
            if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                prefs.edit()
                        .putString(KEY_SSID, ssid)
                        .putInt(KEY_NETWORK_ID, info.getNetworkId())
                        .apply();
                return;
            }
        }

        String shellSsid = shell.readConnectedWifiSsid();
        if (shellSsid != null && !shellSsid.isEmpty()) {
            prefs.edit().putString(KEY_SSID, shellSsid).apply();
        }
    }

    private WifiInfo safeGetConnectionInfo() {
        if (!wifiManagerUsable || wifiManager == null) {
            return null;
        }
        try {
            return wifiManager.getConnectionInfo();
        } catch (SecurityException e) {
            wifiManagerUsable = false;
            Log.w(TAG, "WifiManager denied (no ACCESS_WIFI_STATE) — using shell/connectivity fallbacks");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "getConnectionInfo failed: " + e.getMessage());
            return null;
        }
    }

    private void enableWifi() {
        if (wifiManagerUsable && wifiManager != null) {
            try {
                wifiManager.setWifiEnabled(true);
            } catch (SecurityException e) {
                wifiManagerUsable = false;
                Log.w(TAG, "setWifiEnabled denied: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "WifiManager.setWifiEnabled failed: " + e.getMessage());
            }
        }
        shell.execute("svc wifi enable");
        shell.setGlobalSetting("wifi_on", "1");
    }

    private void attemptReconnect(String reason) {
        String savedSsid = prefs.getString(KEY_SSID, null);
        int savedNetworkId = prefs.getInt(KEY_NETWORK_ID, -1);

        if (wifiManagerUsable && wifiManager != null && savedNetworkId >= 0) {
            try {
                if (wifiManager.enableNetwork(savedNetworkId, true)) {
                    wifiManager.reconnect();
                    Log.i(TAG, "reconnect via networkId=" + savedNetworkId + " (" + reason + ")");
                    return;
                }
            } catch (SecurityException e) {
                wifiManagerUsable = false;
                Log.w(TAG, "enableNetwork denied: " + e.getMessage());
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
            if (wifiManagerUsable && wifiManager != null) {
                try {
                    wifiManager.reconnect();
                } catch (SecurityException e) {
                    wifiManagerUsable = false;
                    Log.w(TAG, "reconnect denied: " + e.getMessage());
                } catch (Exception e) {
                    Log.w(TAG, "wifiManager.reconnect failed: " + e.getMessage());
                }
            }
            Log.i(TAG, "reconnect requested for ssid=" + savedSsid + " (" + reason + ")");
        }
    }

    private boolean isWifiEnabled() {
        if (wifiManagerUsable && wifiManager != null) {
            try {
                return wifiManager.isWifiEnabled();
            } catch (SecurityException e) {
                wifiManagerUsable = false;
                Log.w(TAG, "isWifiEnabled denied: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "isWifiEnabled failed: " + e.getMessage());
            }
        }
        String value = shell.readSetting("wifi_on");
        if ("1".equals(value)) {
            return true;
        }
        String status = shell.captureOutput("cmd wifi status");
        return status != null && status.toLowerCase().contains("wifi is enabled");
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
            Log.w(TAG, "connectivity check failed: " + e.getMessage());
            return false;
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
