package com.ava.mods.bleadv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * True 1:1 raw advertising via a privileged native helper (root only).
 *
 * <p>Reproduces the FULL raw PDU — including the codec Flags AD that {@link RawAdvParser} /
 * {@code AdvertiseData} cannot emit — by shipping a small ELF ({@code native/<abi>/ble_adv_hci})
 * that injects the raw bytes through an HCI ({@code LE Set Advertising Data}) or MGMT
 * ({@code Add Advertising}) socket, mirroring esphome-ble_adv_proxy and ha-ble-adv's
 * {@code BluetoothHCIAdapter}.
 *
 * <p>Requires root (CAP_NET_ADMIN) and a device whose Bluetooth uses the kernel HCI transport.
 * Where that is unavailable the caller falls back to {@link BleAdvTransmitter}.
 */
final class RawHciAdvertiser {
    private static final String TAG = "BleAdvRawHci";
    private static final String HELPER_NAME = "ble_adv_hci";
    private static final String TMP_PATH = "/data/local/tmp/ava_ble_adv_hci";

    private static final int ROOT_UNKNOWN = 0;
    private static final int ROOT_AVAILABLE = 1;
    private static final int ROOT_UNAVAILABLE = 2;

    private final Context context;
    private volatile int rootState = ROOT_UNKNOWN;
    private volatile String extractedPath;
    private volatile String deployedFingerprint;

    RawHciAdvertiser(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isAvailable() {
        return isRootAvailable() && resolveAbiResource() != null;
    }

    /**
     * Transmit {@code fullPdu} verbatim (including Flags) for {@code durationMs}.
     *
     * @return null on success, otherwise a short error reason (caller should fall back).
     */
    synchronized String transmit(int hciIndex, String mode, int durationMs, byte[] fullPdu) {
        if (fullPdu == null || fullPdu.length == 0) {
            return "empty_pdu";
        }
        if (!isRootAvailable()) {
            return "no_root";
        }
        String helper = ensureExtracted();
        if (helper == null) {
            return "no_helper_binary";
        }

        String hex = RawAdvParser.toHex(fullPdu);
        String cmd = "cp -f '" + helper + "' " + TMP_PATH
                + " && chmod 755 " + TMP_PATH
                + " && " + TMP_PATH + " " + hciIndex + " " + mode + " " + durationMs + " " + hex;

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            String out = readFully(process);
            int code = process.waitFor();
            if (code == 0 && out.contains("OK")) {
                if (out.contains("mgmt")) {
                    Log.d(TAG, "raw injected via MGMT Add Advertising (1:1)");
                } else {
                    Log.d(TAG, "raw injected via HCI LE Set Advertising Data (1:1)");
                }
                return null;
            }
            String reason = out.isEmpty() ? ("exit_" + code) : out.trim();
            Log.w(TAG, "raw HCI helper failed: " + reason);
            return "helper_failed:" + reason;
        } catch (Exception e) {
            Log.w(TAG, "raw HCI helper exec error", e);
            return "exec_error";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private boolean isRootAvailable() {
        if (rootState == ROOT_AVAILABLE) {
            return true;
        }
        if (rootState == ROOT_UNAVAILABLE) {
            return false;
        }
        // Prefer the host's own root detection to avoid an extra su prompt.
        try {
            Class<?> rootUtils = loadHostClass("com.example.ava.utils.RootUtils");
            Object instance = kotlinObject(rootUtils);
            if (instance != null) {
                Boolean binaryPresent = (Boolean) rootUtils.getMethod("isRootBinaryPresent").invoke(instance);
                if (binaryPresent != null && !binaryPresent) {
                    rootState = ROOT_UNAVAILABLE;
                    return false;
                }
                Boolean available = (Boolean) rootUtils.getMethod("isRootAvailable").invoke(instance);
                if (available != null) {
                    rootState = available ? ROOT_AVAILABLE : ROOT_UNAVAILABLE;
                    return available;
                }
            }
        } catch (Exception ignored) {
        }
        boolean probed = probeRootOnce();
        rootState = probed ? ROOT_AVAILABLE : ROOT_UNAVAILABLE;
        return probed;
    }

    private boolean probeRootOnce() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readFully(process);
            int code = process.waitFor();
            return code == 0 || out.contains("uid=0");
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String ensureExtracted() {
        String resource = resolveAbiResource();
        if (resource == null) {
            return null;
        }
        String fingerprint = resource;
        if (extractedPath != null && fingerprint.equals(deployedFingerprint) && new File(extractedPath).exists()) {
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
            deployedFingerprint = fingerprint;
            return extractedPath;
        } catch (Exception e) {
            Log.w(TAG, "helper extraction failed", e);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(fos);
        }
    }

    /** Find the first bundled ABI resource matching the device. */
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

    private static String readFully(Process process) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private Class<?> loadHostClass(String name) throws ClassNotFoundException {
        ClassLoader loader = context.getClassLoader();
        if (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(name);
    }

    private static Object kotlinObject(Class<?> clazz) {
        try {
            return clazz.getField("INSTANCE").get(null);
        } catch (Exception e) {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ignored) {
                return null;
            }
        }
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
