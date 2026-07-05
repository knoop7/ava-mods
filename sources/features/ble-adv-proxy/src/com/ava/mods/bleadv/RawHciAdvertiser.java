package com.ava.mods.bleadv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * True 1:1 raw advertising via native helper — runs in Ava app process when possible.
 */
final class RawHciAdvertiser {
    private static final String TAG = "BleAdvRawHci";
    private static final String HELPER_NAME = "ble_adv_hci";
    private static final Object HELPER_LOCK = new Object();
    private static final int PRE_TX_SETTLE_MS = 250;
    private static final int TX_RETRY_COUNT = 2;
    private static final int TX_RETRY_GAP_MS = 80;

    private final Context context;
    private final BleAdvPrivilegedShell privilegedShell;
    private volatile String extractedPath;
    private volatile String deployedFingerprint;
    private volatile int hciIndex = 0;
    private volatile String lastTransport = "unavailable";

    RawHciAdvertiser(Context context, BleAdvPermissionHelper permissionHelper) {
        this.context = context.getApplicationContext();
        this.privilegedShell = new BleAdvPrivilegedShell(context, permissionHelper);
        resolveHciIndex();
    }

    boolean hasHelperBinary() {
        return resolveAbiResource() != null;
    }

    boolean isAvailable() {
        return hasHelperBinary();
    }

    String getLastTransport() {
        return lastTransport != null ? lastTransport : "unavailable";
    }

    String probeTransport() {
        if (!hasHelperBinary()) {
            lastTransport = "unavailable";
            return lastTransport;
        }
        String helper = ensureExtracted();
        if (helper == null) {
            lastTransport = "unavailable";
            return lastTransport;
        }
        synchronized (HELPER_LOCK) {
            BleAdvPrivilegedShell.ExecResult result = runHelper(helper,
                    hciIndex, "probe", 20, BleAdvCapabilityProbe.FAKE_ADV_PDU);
            String out = result.output != null ? result.output : "";
            if (out.contains("transport=mgmt") && out.contains("tx=ok")) {
                lastTransport = "mgmt";
            } else if (out.contains("transport=hci") && out.contains("tx=ok")) {
                lastTransport = "hci";
            } else {
                lastTransport = "unavailable";
                Log.w(TAG, "transport probe failed code=" + result.exitCode + " out=" + out.trim());
            }
            Log.i(TAG, "transport probe -> " + lastTransport + " (on-air FAKE_ADV)");
            return lastTransport;
        }
    }

    String transmit(int hciIndexArg, String mode, int durationMs, byte[] fullPdu) {
        if (fullPdu == null || fullPdu.length == 0) {
            return "empty_pdu";
        }
        if (!hasHelperBinary()) {
            return "no_helper_binary";
        }
        String helper = ensureExtracted();
        if (helper == null) {
            return "no_helper_binary";
        }

        int dev = hciIndexArg >= 0 ? hciIndexArg : hciIndex;
        String effectiveMode = resolveTransmitMode(mode);
        settleBeforeTransmit();

        synchronized (HELPER_LOCK) {
            String lastReason = "privileged_exec_failed";
            for (int attempt = 0; attempt < TX_RETRY_COUNT; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(TX_RETRY_GAP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "interrupted";
                    }
                }
                BleAdvPrivilegedShell.ExecResult result = runHelper(
                        helper, dev, effectiveMode, durationMs, fullPdu);
                if (result.exitCode == 0 && containsOk(result.output)) {
                    updateTransportFromOutput(result.output);
                    logTransport(result.output);
                    return null;
                }
                lastReason = result.output.isEmpty()
                        ? ("exit_" + result.exitCode)
                        : result.output.trim();
                Log.w(TAG, "raw TX attempt " + (attempt + 1) + " failed: " + lastReason);
            }
            return lastReason.isEmpty() ? "privileged_exec_failed" : lastReason;
        }
    }

    private BleAdvPrivilegedShell.ExecResult runHelper(
            String helper, int dev, String mode, int durationMs, byte[] pdu) {
        return privilegedShell.execHelper(helper, new String[]{
                String.valueOf(dev),
                mode,
                String.valueOf(durationMs),
                RawAdvParser.toHex(pdu),
        });
    }

    private String resolveTransmitMode(String mode) {
        if (mode != null && !mode.isEmpty() && !"auto".equals(mode)) {
            return mode;
        }
        if ("mgmt".equals(lastTransport)) {
            return "mgmt";
        }
        if ("hci".equals(lastTransport)) {
            return "hci";
        }
        return "mgmt";
    }

    private static void settleBeforeTransmit() {
        try {
            Thread.sleep(PRE_TX_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateTransportFromOutput(String out) {
        if (out != null && out.contains("mgmt")) {
            lastTransport = "mgmt";
        } else if (out != null && out.contains("hci")) {
            lastTransport = "hci";
        }
    }

    private static boolean containsOk(String out) {
        return out != null && (out.contains("OK mgmt") || out.contains("OK hci") || out.contains("OK "));
    }

    private void logTransport(String out) {
        if (out.contains("mgmt")) {
            Log.i(TAG, "raw injected via MGMT Add Advertising (1:1 PDU)");
        } else {
            Log.i(TAG, "raw injected via HCI LE Set Advertising Data (1:1 PDU)");
        }
    }

    private void resolveHciIndex() {
        File dir = new File("/sys/class/bluetooth");
        File[] entries = dir.listFiles();
        if (entries == null) {
            hciIndex = 0;
            return;
        }
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith("hci")) {
                try {
                    hciIndex = Integer.parseInt(name.substring(3));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        hciIndex = 0;
    }

    private String ensureExtracted() {
        String resource = resolveAbiResource();
        if (resource == null) {
            return null;
        }
        if (extractedPath != null && resource.equals(deployedFingerprint) && new File(extractedPath).exists()) {
            return extractedPath;
        }
        File outDir = context.getDir("native", Context.MODE_PRIVATE);
        File out = new File(outDir, HELPER_NAME);
        InputStream in = null;
        OutputStream fos = null;
        try {
            in = getClass().getClassLoader().getResourceAsStream(resource);
            if (in == null) {
                Log.w(TAG, "helper resource missing: " + resource);
                return null;
            }
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            out.setExecutable(true, false);
            extractedPath = out.getAbsolutePath();
            deployedFingerprint = resource;
            return extractedPath;
        } catch (Exception e) {
            Log.w(TAG, "helper extraction failed", e);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(fos);
        }
    }

    private String resolveAbiResource() {
        String[] abis = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS
                : new String[]{Build.CPU_ABI};
        for (String abi : abis) {
            if (abi == null) {
                continue;
            }
            String resource = "native/" + abi + "/" + HELPER_NAME;
            if (getClass().getClassLoader().getResource(resource) != null) {
                return resource;
            }
        }
        return null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
