package com.ava.mods.bleadv;

import android.util.Log;

import java.io.File;

/**
 * Detects whether kernel HCI/MGMT sockets are likely available (vs QCOM HAL-only phones).
 */
final class BleAdvTransportProbe {
    private static final String TAG = "BleAdvTransport";
    private static volatile Boolean kernelHciPresent;

    private BleAdvTransportProbe() {
    }

    /** True when {@code /sys/class/bluetooth/hciN} exists (kernel transport exposed). */
    static boolean hasKernelHci() {
        Boolean cached = kernelHciPresent;
        if (cached != null) {
            return cached;
        }
        boolean present = probeKernelHci();
        kernelHciPresent = present;
        Log.i(TAG, "kernel HCI sysfs present=" + present);
        return present;
    }

    /**
     * JNI MGMT in the app process is unreliable on HAL-only devices; prefer privileged shell.
     */
    static boolean preferPrivilegedShell(BleAdvPermissionHelper permissionHelper) {
        if (hasKernelHci()) {
            return false;
        }
        return permissionHelper.isPrivilegedAvailable();
    }

    private static boolean probeKernelHci() {
        File dir = new File("/sys/class/bluetooth");
        File[] entries = dir.listFiles();
        if (entries == null) {
            return false;
        }
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith("hci")) {
                return true;
            }
        }
        return false;
    }
}
