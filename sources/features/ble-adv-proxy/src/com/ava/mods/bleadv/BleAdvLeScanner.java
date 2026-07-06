package com.ava.mods.bleadv;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone mod-owned BLE LE scan — feeds {@link BleAdvProxyManager} without Ava proxy scan.
 *
 * <p>Uses a process-wide stable {@link ScanCallback} so {@link #stopScan(ScanCallback)} can tear
 * down zombie registrations after Bluetooth stack restarts (Mi 9 / QCOM HAL).
 */
final class BleAdvLeScanner {
    private static final String TAG = "BleAdvLeScanner";
    private static final long RESTART_DELAY_MS = 1500L;
    private static final long STOP_SETTLE_MS = 150L;
    private static final long LIGHT_STOP_SETTLE_MS = 40L;

    /** Stable callback object — must not be recreated or stopScan cannot match scannerId N-1. */
    private static final ScanCallback STABLE_CALLBACK = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BleAdvLeScanner owner = activeOwner;
            if (owner != null) {
                owner.handleScanResult(result);
            }
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            BleAdvLeScanner owner = activeOwner;
            if (owner == null) {
                return;
            }
            for (ScanResult result : results) {
                owner.handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            BleAdvLeScanner owner = activeOwner;
            if (owner != null) {
                owner.handleScanFailed(errorCode);
            }
        }
    };

    private static volatile BleAdvLeScanner activeOwner;

    interface ResultHandler {
        void onScanResult(String mac, int rssi, byte[] raw);
    }

    private final Context context;
    private final BleAdvPermissionHelper permissionHelper;
    private final AtomicBoolean desiredRunning = new AtomicBoolean(false);
    private final AtomicBoolean hardwareRunning = new AtomicBoolean(false);
    private final AtomicBoolean pausedForExclusive = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);

    private volatile ResultHandler resultHandler;

    BleAdvLeScanner(Context context, BleAdvPermissionHelper permissionHelper) {
        this.context = context.getApplicationContext();
        this.permissionHelper = permissionHelper;
    }

    void setResultHandler(ResultHandler handler) {
        this.resultHandler = handler;
    }

    @SuppressLint("MissingPermission")
    void start() {
        desiredRunning.set(true);
        activeOwner = this;
        if (pausedForExclusive.get()) {
            Log.d(TAG, "start deferred (exclusive active)");
            return;
        }
        if (!permissionHelper.hasBluetoothScanPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN/location not granted — cannot start mod scan");
            return;
        }
        BluetoothLeScanner leScanner = resolveScanner();
        if (leScanner == null) {
            return;
        }
        forceStopScan(leScanner);
        try {
            leScanner.startScan(null, buildSettings(), STABLE_CALLBACK);
            hardwareRunning.set(true);
            Log.i(TAG, "standalone LE scan started");
        } catch (Exception e) {
            Log.e(TAG, "startScan failed", e);
            hardwareRunning.set(false);
            scheduleRestart();
        }
    }

    @SuppressLint("MissingPermission")
    void stop() {
        desiredRunning.set(false);
        pausedForExclusive.set(false);
        restartScheduled.set(false);
        if (activeOwner == this) {
            activeOwner = null;
        }
        BluetoothLeScanner leScanner = resolveScanner();
        if (leScanner != null) {
            forceStopScan(leScanner);
        } else {
            hardwareRunning.set(false);
        }
        Log.i(TAG, "standalone LE scan stopped");
    }

    @SuppressLint("MissingPermission")
    void pauseForExclusive() {
        pauseForExclusive(false);
    }

    @SuppressLint("MissingPermission")
    void pauseForExclusive(boolean lightWindow) {
        pausedForExclusive.set(true);
        BluetoothLeScanner leScanner = resolveScanner();
        if (leScanner != null) {
            forceStopScan(leScanner, lightWindow);
        } else {
            hardwareRunning.set(false);
        }
        Log.d(TAG, "scan paused for exclusive TX");
    }

    @SuppressLint("MissingPermission")
    void resumeAfterExclusive() {
        pausedForExclusive.set(false);
        if (desiredRunning.get()) {
            start();
        }
    }

    boolean isDesiredRunning() {
        return desiredRunning.get();
    }

    @SuppressLint("MissingPermission")
    private void forceStopScan(BluetoothLeScanner leScanner) {
        forceStopScan(leScanner, false);
    }

    @SuppressLint("MissingPermission")
    private void forceStopScan(BluetoothLeScanner leScanner, boolean lightWindow) {
        try {
            leScanner.stopScan(STABLE_CALLBACK);
        } catch (Exception e) {
            Log.w(TAG, "stopScan failed: " + e.getMessage());
        }
        hardwareRunning.set(false);
        settle(lightWindow ? LIGHT_STOP_SETTLE_MS : STOP_SETTLE_MS);
    }

    private BluetoothLeScanner resolveScanner() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter unavailable");
            return null;
        }
        BluetoothLeScanner leScanner = adapter.getBluetoothLeScanner();
        if (leScanner == null) {
            Log.w(TAG, "BluetoothLeScanner unavailable");
        }
        return leScanner;
    }

    private void handleScanResult(ScanResult result) {
        if (pausedForExclusive.get()) {
            return;
        }
        deliver(result);
    }

    private void handleScanFailed(int errorCode) {
        hardwareRunning.set(false);
        Log.e(TAG, "scan failed error=" + errorCode);
        BluetoothLeScanner leScanner = resolveScanner();
        if (leScanner != null) {
            forceStopScan(leScanner);
        }
        scheduleRestart();
    }

    private ScanSettings buildSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
        }
        return builder.build();
    }

    private void deliver(ScanResult result) {
        if (result == null || result.getScanRecord() == null) {
            return;
        }
        byte[] raw = result.getScanRecord().getBytes();
        if (raw == null || raw.length < 5) {
            return;
        }
        String mac = result.getDevice() != null ? result.getDevice().getAddress() : null;
        if (mac == null || mac.isEmpty()) {
            return;
        }
        ResultHandler handler = resultHandler;
        if (handler != null) {
            handler.onScanResult(mac, result.getRssi(), raw);
        }
    }

    private void scheduleRestart() {
        if (!desiredRunning.get() || pausedForExclusive.get()) {
            return;
        }
        if (!restartScheduled.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(RESTART_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    restartScheduled.set(false);
                    return;
                }
                restartScheduled.set(false);
                if (desiredRunning.get() && !pausedForExclusive.get()) {
                    start();
                }
            }
        }, "BleAdvScanRestart");
        t.setDaemon(true);
        t.start();
    }

    private static void settle(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
