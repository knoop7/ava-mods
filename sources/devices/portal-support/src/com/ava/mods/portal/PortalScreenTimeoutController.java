package com.ava.mods.portal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;

class PortalScreenTimeoutController {

    private static final String TAG = "PortalSupport";
    private static final long CHECK_INTERVAL_MS = 15_000L;

    interface PresenceState {
        boolean isPresent();

        /** Re-evaluate combined presence (e.g. enhanced-sound hold expiry). */
        void onPresenceTick();
    }

    private final Context context;
    private final PresenceState presenceState;
    private final HandlerThread thread = new HandlerThread("portal-screen-timeout");
    private Handler handler;
    private BroadcastReceiver screenReceiver;
    private volatile boolean running;
    private volatile boolean enabled;
    private volatile int timeoutMinutes = 5;
    private volatile boolean screenOn = true;
    private volatile long lastActivityMs = System.currentTimeMillis();

    PortalScreenTimeoutController(Context context, PresenceState presenceState) {
        this.context = context.getApplicationContext();
        this.presenceState = presenceState;
    }

    void start(boolean enabled, int timeoutMinutes) {
        this.enabled = enabled;
        this.timeoutMinutes = clampMinutes(timeoutMinutes);
        if (running) {
            lastActivityMs = System.currentTimeMillis();
            return;
        }
        running = true;
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        screenOn = powerManager == null || powerManager.isInteractive();
        lastActivityMs = System.currentTimeMillis();
        thread.start();
        handler = new Handler(thread.getLooper());
        registerScreenReceiver();
        handler.post(checkRunnable);
        Log.i(TAG, "ScreenTimeout started enabled=" + enabled + " minutes=" + this.timeoutMinutes);
    }

    void stop() {
        running = false;
        if (handler != null) {
            handler.removeCallbacks(checkRunnable);
        }
        unregisterScreenReceiver();
        Log.i(TAG, "ScreenTimeout stopped");
    }

    void release() {
        stop();
        if (thread.isAlive()) {
            thread.quitSafely();
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        lastActivityMs = System.currentTimeMillis();
    }

    void setTimeoutMinutes(int minutes) {
        this.timeoutMinutes = clampMinutes(minutes);
        lastActivityMs = System.currentTimeMillis();
    }

    boolean isEnabled() {
        return enabled;
    }

    int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    void onPresenceActivity() {
        lastActivityMs = System.currentTimeMillis();
    }

    private final Runnable checkRunnable = new TimeoutCheckRunnable();

    private class TimeoutCheckRunnable implements Runnable {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            checkScreenTimeout();
            if (handler != null) {
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    }

    private void checkScreenTimeout() {
        if (!enabled || !screenOn) {
            return;
        }
        if (presenceState != null) {
            presenceState.onPresenceTick();
            if (presenceState.isPresent()) {
                lastActivityMs = System.currentTimeMillis();
                return;
            }
        }
        long idleMs = System.currentTimeMillis() - lastActivityMs;
        if (idleMs >= timeoutMinutes * 60_000L) {
            Log.i(TAG, "screen timeout: " + timeoutMinutes + "m idle — sleeping screen");
            PortalScreenControl.sleep(context);
            lastActivityMs = System.currentTimeMillis();
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn = true;
                    lastActivityMs = System.currentTimeMillis();
                } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenOn = false;
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screenReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        screenReceiver = null;
    }

    private int clampMinutes(int minutes) {
        if (minutes < 1) {
            return 1;
        }
        if (minutes > 240) {
            return 240;
        }
        return minutes;
    }
}
