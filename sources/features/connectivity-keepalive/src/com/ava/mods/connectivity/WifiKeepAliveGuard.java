package com.ava.mods.connectivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps the WiFi radio on when something turns it off.
 * Does not force reconnect — Android reconnects to saved networks on its own.
 */
final class WifiKeepAliveGuard {

    private static final String TAG = "ConnKeepAlive";
    private static final long POLL_INTERVAL_MS = 60_000L;

    private final Context context;
    private final WifiManager wifiManager;
    private final PrivilegedShell shell;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean wifiManagerUsable = true;
    private BroadcastReceiver wifiReceiver;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            safeEnsureWifiEnabled("poll");
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    WifiKeepAliveGuard(Context context, PrivilegedShell shell) {
        this.context = context.getApplicationContext();
        this.shell = shell;
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        registerWifiStateListener();
        safeEnsureWifiEnabled("start");
        handler.post(pollRunnable);
        Log.i(TAG, "WiFi keep-alive started (radio only, 60s poll fallback)");
    }

    void stop() {
        running = false;
        unregisterWifiStateListener();
        handler.removeCallbacks(pollRunnable);
        Log.i(TAG, "WiFi keep-alive stopped (WiFi left unchanged)");
    }

    boolean isRunning() {
        return running;
    }

    private void registerWifiStateListener() {
        if (wifiReceiver != null) {
            return;
        }
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!running || intent == null) {
                    return;
                }
                if (!WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                    return;
                }
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                    Log.w(TAG, "WiFi radio turned off — re-enabling, system will reconnect");
                    handler.post(() -> safeEnsureWifiEnabled("wifi_off"));
                }
            }
        };

        IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(wifiReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "wifi state listener registration failed: " + e.getMessage());
        }
    }

    private void unregisterWifiStateListener() {
        if (wifiReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(wifiReceiver);
        } catch (Exception ignored) {
        }
        wifiReceiver = null;
    }

    private void safeEnsureWifiEnabled(String reason) {
        try {
            ensureWifiEnabled(reason);
        } catch (Exception e) {
            Log.w(TAG, "ensureWifiEnabled failed (" + reason + "): " + e.getMessage());
        }
    }

    private void ensureWifiEnabled(String reason) {
        if (!running || isWifiEnabled()) {
            return;
        }
        Log.w(TAG, "WiFi radio off (" + reason + ") — turning on, reconnect left to system");
        enableWifiRadio();
    }

    private void enableWifiRadio() {
        if (wifiManagerUsable && wifiManager != null) {
            try {
                if (wifiManager.setWifiEnabled(true)) {
                    return;
                }
            } catch (SecurityException e) {
                wifiManagerUsable = false;
                Log.w(TAG, "setWifiEnabled denied: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "setWifiEnabled failed: " + e.getMessage());
            }
        }
        shell.execute("svc wifi enable");
        shell.setGlobalSetting("wifi_on", "1");
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
        return "1".equals(shell.readSetting("wifi_on"));
    }
}
