package com.ava.mods.bleadv;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.util.Log;

/**
 * Self-probe: privileged shell + real on-air FAKE_ADV burst (raw HCI/MGMT or AdvertiseData).
 */
final class BleAdvCapabilityProbe {
    private static final String TAG = "BleAdvCapability";
    private static final int PROBE_BURST_MS = 100;

    /** ha-ble-adv BluetoothHCIAdapter.FAKE_ADV — safe probe PDU padded to 31 bytes. */
    static final byte[] FAKE_ADV_PDU = buildFakeAdvPdu();

    interface ExclusiveRunner {
        void runExclusive(Runnable task);
    }

    private final Context context;
    private final BleAdvPermissionHelper permissionHelper;
    private final RawHciAdvertiser rawHciAdvertiser;

    BleAdvCapabilityProbe(Context context, BleAdvPermissionHelper permissionHelper,
                          RawHciAdvertiser rawHciAdvertiser) {
        this.context = context.getApplicationContext();
        this.permissionHelper = permissionHelper;
        this.rawHciAdvertiser = rawHciAdvertiser;
    }

    Report probe(boolean rawHciEnabled, boolean useMaxTxPower, ExclusiveRunner exclusiveRunner) {
        Report report = new Report();
        report.probedAtMs = System.currentTimeMillis();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        report.bluetoothEnabled = adapter != null && adapter.isEnabled();
        report.helperPresent = rawHciAdvertiser.hasHelperBinary();
        report.privilegedShell = permissionHelper.getPrivilegedShellLabel();

        if (!report.bluetoothEnabled) {
            report.rawTransport = "unavailable";
            report.fidelityMode = "none";
            report.summary = buildSummary(report);
            return report;
        }

        if (!rawHciEnabled) {
            report.rawTransport = "disabled";
            report.fidelityMode = probeAdvertiseDataTransmit(useMaxTxPower, exclusiveRunner, report);
            report.summary = buildSummary(report);
            return report;
        }

        permissionHelper.ensurePrivilegedAccess();
        report.privilegedShell = permissionHelper.getPrivilegedShellLabel();

        if (report.helperPresent) {
            String transport = rawHciAdvertiser.probeTransport();
            report.rawTransport = transport != null ? transport : "unavailable";
            if ("mgmt".equals(transport) || "hci".equals(transport)) {
                report.fidelityMode = "raw_1to1";
                report.advertiseDataOk = true;
                report.summary = buildSummary(report);
                Log.i(TAG, report.summary);
                return report;
            }
        } else {
            report.rawTransport = "unavailable";
        }

        /* AdvertiseData grabs MGMT instance 0 on Android and causes 0x14 BUSY for raw TX. */
        if (rawHciEnabled) {
            report.fidelityMode = "raw_failed";
            report.summary = buildSummary(report);
            Log.w(TAG, report.summary + " (skipped AdvertiseData probe; holds MGMT)");
            return report;
        }

        report.fidelityMode = probeAdvertiseDataTransmit(useMaxTxPower, exclusiveRunner, report);
        report.summary = buildSummary(report);
        Log.i(TAG, report.summary);
        return report;
    }

    /** Real AdvertiseData burst — validates the Android API fallback path on-air. */
    private String probeAdvertiseDataTransmit(
            boolean useMaxTxPower,
            ExclusiveRunner exclusiveRunner,
            Report report
    ) {
        final String[] mode = new String[]{"none"};
        Runnable task = new Runnable() {
            @Override
            public void run() {
                BleAdvTransmitter transmitter = new BleAdvTransmitter(context, useMaxTxPower);
                String err = transmitter.transmitBlocking(FAKE_ADV_PDU, PROBE_BURST_MS);
                if (err == null || err.isEmpty()) {
                    mode[0] = "advertisedata";
                    report.advertiseDataOk = true;
                    Log.i(TAG, "AdvertiseData probe TX ok");
                    return;
                }
                Log.w(TAG, "AdvertiseData probe TX failed: " + err);
                try {
                    RawAdvParser.toMappedAdv(FAKE_ADV_PDU);
                    mode[0] = "advertisedata_partial";
                } catch (Exception e) {
                    mode[0] = "none";
                }
            }
        };
        if (exclusiveRunner != null) {
            exclusiveRunner.runExclusive(task);
        } else {
            task.run();
        }
        return mode[0];
    }

    private static String buildSummary(Report report) {
        return "capability shell=" + report.privilegedShell
                + " transport=" + report.rawTransport
                + " fidelity=" + report.fidelityMode
                + " bt=" + (report.bluetoothEnabled ? "on" : "off")
                + " helper=" + (report.helperPresent ? "yes" : "no")
                + (report.advertiseDataOk ? " advdata_tx=ok" : "");
    }

    private static byte[] buildFakeAdvPdu() {
        byte[] pdu = new byte[31];
        pdu[0] = 0x1D;
        pdu[1] = (byte) 0xFF;
        pdu[2] = (byte) 0xFF;
        pdu[3] = (byte) 0xFF;
        return pdu;
    }

    static final class Report {
        String privilegedShell = "none";
        String rawTransport = "unavailable";
        String fidelityMode = "none";
        boolean bluetoothEnabled;
        boolean helperPresent;
        boolean advertiseDataOk;
        long probedAtMs;
        String summary = "";
    }
}
