package com.ava.mods.portal;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

class PortalPresenceMonitor {

    private static final String TAG = "PortalSupport";
    private static final long FRESH_MS = 45_000L;
    private static final long ABSENT_MS = 50_000L;
    private static final long CHECK_INTERVAL_MS = 10_000L;

    interface Listener {
        void onPresenceChanged(boolean present);
    }

    private final Listener listener;
    private volatile boolean running;
    private volatile long lastBeatMs;
    private volatile boolean present;
    private Process process;
    private Thread readerThread;
    private final HandlerThread checkThread = new HandlerThread("portal-presence");
    private Handler checkHandler;

    PortalPresenceMonitor(Listener listener) {
        this.listener = listener;
    }

    boolean isPresent() {
        return present;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        lastBeatMs = 0L;
        present = false;
        checkThread.start();
        checkHandler = new Handler(checkThread.getLooper());
        readerThread = new Thread(this::readLoop, "portal-presence-log");
        readerThread.setDaemon(true);
        readerThread.start();
        checkHandler.post(checkRunnable);
        Log.i(TAG, "PresenceMonitor started");
    }

    void stop() {
        running = false;
        if (checkHandler != null) {
            checkHandler.removeCallbacks(checkRunnable);
        }
        try {
            if (process != null) {
                process.destroy();
            }
        } catch (Exception ignored) {
        }
        process = null;
        if (present) {
            present = false;
            listener.onPresenceChanged(false);
        }
        Log.i(TAG, "PresenceMonitor stopped");
    }

    void release() {
        stop();
        if (checkThread.isAlive()) {
            checkThread.quitSafely();
        }
    }

    private void readLoop() {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "logcat", "-v", "epoch",
                    "-s", "PresenceManager:I", "aloha.CameraServiceController:I"
            );
            builder.redirectErrorStream(true);
            process = builder.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream())
            );
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.toLowerCase().contains("presence")) {
                    continue;
                }
                String epochText = line.trim().split(" ", 2)[0];
                double epoch;
                try {
                    epoch = Double.parseDouble(epochText);
                } catch (NumberFormatException e) {
                    continue;
                }
                long beatMs = (long) (epoch * 1000L);
                if (System.currentTimeMillis() - beatMs < FRESH_MS) {
                    lastBeatMs = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "PresenceMonitor reader stopped: " + e.getMessage() + " (READ_LOGS granted?)");
        }
    }

    private final Runnable checkRunnable = new PresenceCheckRunnable();

    private class PresenceCheckRunnable implements Runnable {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            boolean live = lastBeatMs != 0L && System.currentTimeMillis() - lastBeatMs < ABSENT_MS;
            if (live != present) {
                present = live;
                Log.i(TAG, "presence -> " + (live ? "DETECTED" : "CLEAR"));
                listener.onPresenceChanged(live);
            }
            if (checkHandler != null) {
                checkHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    }
}
