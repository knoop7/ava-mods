package com.ava.mods.echoshow;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.io.DataOutputStream;

public class EchoShowSupportManager {
    private static final String TAG = "EchoShowSupport";
    private static final long BLUETOOTH_RECOVERY_COOLDOWN_MS = 10 * 60 * 1000L;
    private static final long BLUETOOTH_OFF_TIMEOUT_MS = 12_000L;
    private static final long BLUETOOTH_ON_TIMEOUT_MS = 20_000L;

    private static volatile EchoShowSupportManager instance;
    private static final Object BLUETOOTH_RECOVERY_LOCK = new Object();
    private static volatile long lastBluetoothRecoveryAttemptMs;
    private final Context context;

    private static final String[] ECHO_SHOW_CODENAMES = new String[] {
        "crown", "checkers", "cronos"
    };

    private EchoShowSupportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static EchoShowSupportManager getInstance(Context context) {
        if (instance == null) {
            synchronized (EchoShowSupportManager.class) {
                if (instance == null) {
                    instance = new EchoShowSupportManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isSupported() {
        String model = safeLower(Build.MODEL);
        String board = safeLower(Build.BOARD);
        String device = safeLower(Build.DEVICE);

        for (String codename : ECHO_SHOW_CODENAMES) {
            if (model.contains(codename) || board.contains(codename) || device.contains(codename)) {
                return true;
            }
        }

        return model.contains("amazon") || (model.contains("echo") && model.contains("show"));
    }

    public int getMinBrightness() {
        return 10;
    }

    public boolean isLowEndBleChip() {
        return true;
    }

    /** Free the controller's single legacy advertising slot while proxy scanning. */
    public boolean suppressHostBleAdvertisingDuringProxy() {
        return true;
    }

    /** Give the device Bluetooth stack time to release the previous scanner. */
    public int getBleProxyHandoverDelayMs() {
        return 1000;
    }

    /**
     * Recover the device's GATT binding after a proxy scan registration failure.
     * The MediaTek transport is owned by Android's Bluetooth HAL, so this deliberately never
     * opens /dev/stpbt or injects HCI commands alongside the system stack.
     */
    public boolean recoverBluetoothProxyScanFailure(Context context, int errorCode) {
        if (!isCrownMt76x8Rooted()) {
            return false;
        }

        synchronized (BLUETOOTH_RECOVERY_LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (lastBluetoothRecoveryAttemptMs != 0
                    && now - lastBluetoothRecoveryAttemptMs < BLUETOOTH_RECOVERY_COOLDOWN_MS) {
                Log.w(TAG, "BLE proxy recovery is cooling down");
                return false;
            }

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || adapter.getState() != BluetoothAdapter.STATE_ON) {
                Log.w(TAG, "BLE proxy recovery skipped: adapter is not fully on");
                return false;
            }

            lastBluetoothRecoveryAttemptMs = now;
            boolean bluetoothWasDisabled = false;
            Log.w(TAG, "Recovering Crown Bluetooth GATT service after proxy scan error " + errorCode);
            if (EchoShowPrivilegedShell.execRoot("svc bluetooth disable") != 0) {
                Log.e(TAG, "BLE proxy recovery failed to request Bluetooth disable");
                return false;
            }
            bluetoothWasDisabled = true;

            try {
                boolean reachedOff = waitForBluetoothState(
                        adapter,
                        BluetoothAdapter.STATE_OFF,
                        BLUETOOTH_OFF_TIMEOUT_MS
                );
                if (!reachedOff) {
                    Log.e(TAG, "BLE proxy recovery timed out waiting for Bluetooth off");
                }

                SystemClock.sleep(750);
                if (EchoShowPrivilegedShell.execRoot("svc bluetooth enable") != 0) {
                    Log.e(TAG, "BLE proxy recovery failed to restore Bluetooth");
                    return false;
                }

                boolean reachedOn = waitForBluetoothState(
                        adapter,
                        BluetoothAdapter.STATE_ON,
                        BLUETOOTH_ON_TIMEOUT_MS
                );
                if (!reachedOn) {
                    Log.e(TAG, "BLE proxy recovery timed out waiting for Bluetooth on");
                    return false;
                }
                bluetoothWasDisabled = false;

                // STATE_ON precedes the asynchronous GattService bind on this ROM.
                SystemClock.sleep(2_000);
                Log.i(TAG, "Crown Bluetooth GATT recovery completed");
                return true;
            } finally {
                if (bluetoothWasDisabled && adapter.getState() != BluetoothAdapter.STATE_ON) {
                    Log.w(TAG, "Restoring Bluetooth after an incomplete proxy recovery");
                    EchoShowPrivilegedShell.execRoot("svc bluetooth enable");
                    waitForBluetoothState(adapter, BluetoothAdapter.STATE_ON, BLUETOOTH_ON_TIMEOUT_MS);
                }
            }
        }
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        if (!isSupported()) {
            return false;
        }

        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                os.writeBytes("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow\n");
                os.writeBytes("exit\n");
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean grantOverlayPermissionIfNeeded() {
        return grantOverlayPermissionIfNeeded(context);
    }

    /**
     * ModDeviceSupport hook: screensaver "turn off in dark".
     * Only runs when this mod is enabled and {@link #isSupported()} is true.
     * Tries Shizuku display power off, then root keyevents, then min brightness.
     */
    public boolean sleepScreenForDark(Context context) {
        if (!isSupported()) {
            return false;
        }
        return EchoShowScreenControl.sleepForDark(context);
    }

    /**
     * ModDeviceSupport hook: restore screen after dark sleep.
     */
    public boolean wakeScreenFromDark(Context context) {
        if (!isSupported()) {
            return false;
        }
        return EchoShowScreenControl.wakeFromDark(context);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private boolean isCrownMt76x8Rooted() {
        String model = safeLower(Build.MODEL);
        String board = safeLower(Build.BOARD);
        String device = safeLower(Build.DEVICE);
        if (!model.contains("crown") && !board.contains("crown") && !device.contains("crown")) {
            return false;
        }
        if (!EchoShowPrivilegedShell.isRootAvailable()) {
            return false;
        }
        String transport = EchoShowPrivilegedShell.execRootOutput(
                "if [ -c /dev/stpbt ]; then echo mt76x8; fi"
        );
        return "mt76x8".equals(transport);
    }

    private boolean waitForBluetoothState(BluetoothAdapter adapter, int expectedState, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (adapter.getState() == expectedState) {
                return true;
            }
            SystemClock.sleep(250);
        }
        return adapter.getState() == expectedState;
    }
}
