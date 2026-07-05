package com.ava.mods.bleadv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * True 1:1 raw advertising via native helper + mod-owned privileged shell.
 */
final class RawHciAdvertiser {
    private static final String TAG = "BleAdvRawHci";
    private static final String HELPER_NAME = "ble_adv_hci";
    private static final String TMP_PATH = "/data/local/tmp/ava_ble_adv_hci";

    private final Context context;
    private final BleAdvPrivilegedShell privilegedShell;
    private volatile String extractedPath;
    private volatile String deployedFingerprint;
    private volatile int hciIndex = 0;
    private volatile String lastTransport = "unavailable";

    RawHciAdvertiser(Context context, BleAdvPermissionHelper permissionHelper) {
        this.context = context.getApplicationContext();
        this.privilegedShell = new BleAdvPrivilegedShell(permissionHelper);
        resolveHciIndex();
    }

    boolean hasHelperBinary() {
        return resolveAbiResource() != null;
    }

    boolean isAvailable() {
        return privilegedShell.isPrivilegedAvailable() && hasHelperBinary();
    }

    String getLastTransport() {
        return lastTransport != null ? lastTransport : "unavailable";
    }

    /**
     * Detect MGMT vs HCI, then emit ha-ble-adv FAKE_ADV on-air for {@code probe_ms}.
     */
    String probeTransport() {
        if (!privilegedShell.isPrivilegedAvailable() || !hasHelperBinary()) {
            lastTransport = "unavailable";
            return lastTransport;
        }
        String helper = ensureExtracted();
        if (helper == null) {
            lastTransport = "unavailable";
            return lastTransport;
        }
        String staged = "cp -f '" + helper + "' " + TMP_PATH + " && chmod 755 " + TMP_PATH;
        String command = staged + " && " + TMP_PATH + " " + hciIndex + " probe 20 "
                + RawAdvParser.toHex(BleAdvCapabilityProbe.FAKE_ADV_PDU);
        BleAdvPrivilegedShell.ExecResult result = privilegedShell.execCapture(command);
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

    synchronized String transmit(int hciIndexArg, String mode, int durationMs, byte[] fullPdu) {
        if (fullPdu == null || fullPdu.length == 0) {
            return "empty_pdu";
        }
        privilegedShell.ensurePrivilegedAccess();
        if (!privilegedShell.isPrivilegedAvailable()) {
            return "no_privileged_shell:" + privilegedShell.getPrivilegedShellLabel();
        }
        String helper = ensureExtracted();
        if (helper == null) {
            return "no_helper_binary";
        }

        int dev = hciIndexArg >= 0 ? hciIndexArg : hciIndex;
        String effectiveMode = (mode == null || mode.isEmpty()) ? "auto" : mode;
        String hex = RawAdvParser.toHex(fullPdu);
        String helperCmd = TMP_PATH + " " + dev + " " + effectiveMode + " " + durationMs + " " + hex;
        String staged = "cp -f '" + helper + "' " + TMP_PATH + " && chmod 755 " + TMP_PATH;
        String command = staged + " && " + helperCmd;

        BleAdvPrivilegedShell.ExecResult result = privilegedShell.execCapture(command);
        if (result.exitCode == 0 && containsOk(result.output)) {
            updateTransportFromOutput(result.output);
            logTransport(result.output);
            return null;
        }
        String reason = result.output.isEmpty()
                ? ("exit_" + result.exitCode)
                : result.output.trim();
        Log.w(TAG, "raw HCI failed code=" + result.exitCode + " out=" + reason);
        return reason.isEmpty() ? "privileged_exec_failed" : reason;
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
        File out = new File(context.getFilesDir(), HELPER_NAME);
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
