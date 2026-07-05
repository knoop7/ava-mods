package com.ava.mods.phicomm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * Network disconnect indicator LED (4096, 254) — stock API exists but was never wired in 小讯.
 */
final class PhicommNetworkLightWatcher {
    private static final String TAG = "PhicommNetLight";

    private final Context appContext;
    private final PhicommLightController lights;
    private volatile boolean enabled = true;
    private volatile boolean started;
    private BroadcastReceiver receiver;

    PhicommNetworkLightWatcher(Context context, PhicommLightController lights) {
        appContext = context.getApplicationContext();
        this.lights = lights;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lights.turnOffNetDisconnectedLight();
        } else if (started) {
            applyCurrentNetworkState();
        }
    }

    void start() {
        if (started) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyCurrentNetworkState();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        appContext.registerReceiver(receiver, filter);
        started = true;
        applyCurrentNetworkState();
        Log.i(TAG, "network light watcher started");
    }

    void stop() {
        if (!started || receiver == null) {
            return;
        }
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregister failed", t);
        }
        receiver = null;
        started = false;
        lights.turnOffNetDisconnectedLight();
    }

    private void applyCurrentNetworkState() {
        if (!enabled) {
            lights.turnOffNetDisconnectedLight();
            return;
        }
        boolean connected = isNetworkConnected();
        if (connected) {
            lights.turnOffNetDisconnectedLight();
        } else {
            lights.turnOnNetDisconnectedLight();
        }
        Log.d(TAG, "network connected=" + connected);
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            Log.w(TAG, "connectivity check failed", t);
            return false;
        }
    }
}
