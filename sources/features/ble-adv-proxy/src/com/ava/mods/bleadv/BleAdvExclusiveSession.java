package com.ava.mods.bleadv;

import android.util.Log;

/**
 * Standalone exclusive BLE window — pauses mod-owned LE scan before MGMT/HCI TX
 * without relying on Ava {@code BleOperationCoordinator}.
 */
final class BleAdvExclusiveSession {
    private static final String TAG = "BleAdvExclusive";
    private static final int ENTER_SETTLE_MS = 400;
    private static final int EXIT_SETTLE_MS = 150;
    private static final int LIGHT_ENTER_SETTLE_MS = 80;
    private static final int LIGHT_EXIT_SETTLE_MS = 40;

    private final Object lock = new Object();
    private final BleAdvLeScanner scanner;
    private final Runnable prepController;
    private volatile boolean active;

    BleAdvExclusiveSession(BleAdvLeScanner scanner, Runnable prepController) {
        this.scanner = scanner;
        this.prepController = prepController;
    }

    boolean isActive() {
        return active;
    }

    void runExclusive(Runnable task) {
        runExclusive(task, false);
    }

    /** {@code lightWindow=true} for broadcast-only TX — shorter scan pause, no raw prep. */
    void runExclusive(Runnable task, boolean lightWindow) {
        if (task == null) {
            return;
        }
        int enterMs = lightWindow ? LIGHT_ENTER_SETTLE_MS : ENTER_SETTLE_MS;
        int exitMs = lightWindow ? LIGHT_EXIT_SETTLE_MS : EXIT_SETTLE_MS;
        synchronized (lock) {
            active = true;
            scanner.pauseForExclusive(lightWindow);
            settle(enterMs);
            if (!lightWindow && prepController != null) {
                try {
                    prepController.run();
                } catch (Exception e) {
                    Log.w(TAG, "prepController failed", e);
                }
            }
            try {
                task.run();
            } finally {
                settle(exitMs);
                scanner.resumeAfterExclusive();
                active = false;
            }
        }
    }

    private static void settle(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "exclusive settle interrupted");
        }
    }
}
