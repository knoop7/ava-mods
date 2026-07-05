package com.ava.mods.bleadv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * True 1:1 raw advertising via a privileged native helper.
 *
 * <p>Reproduces the FULL raw PDU — including the codec Flags AD that {@link RawAdvParser} /
 * {@code AdvertiseData} cannot emit — mirroring esphome-ble_adv_proxy's
 * {@code esp_ble_gap_config_adv_data_raw()} and ha-ble-adv's {@code BluetoothHCIAdapter}.
 *
 * <p>Privileged execution order: Shizuku shell (Portal) → {@code su} (root).
 */
final class RawHciAdvertiser {
    private static final String TAG = "BleAdvRawHci";
    private static final String HELPER_NAME = "ble_adv_hci";
    private static final String TMP_PATH = "/data/local/tmp/ava_ble_adv_hci";
    /** Shell-writable; app-readable on most devices (Shizuku runs as shell). */
    private static final String OUT_PATH = "/data/local/tmp/ava_ble_hci_last_out.txt";

    private static final int PRIV_UNKNOWN = 0;
    private static final int PRIV_AVAILABLE = 1;
    private static final int PRIV_UNAVAILABLE = 2;

    private final Context context;
    private volatile int privState = PRIV_UNKNOWN;
    private volatile String extractedPath;
    private volatile String deployedFingerprint;

    RawHciAdvertiser(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isAvailable() {
        return isPrivilegedAvailable() && resolveAbiResource() != null;
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
        if (!isPrivilegedAvailable()) {
            return "no_privileged_shell";
        }
        String helper = ensureExtracted();
        if (helper == null) {
            return "no_helper_binary";
        }

        String hex = RawAdvParser.toHex(fullPdu);
        String helperCmd = TMP_PATH + " " + hciIndex + " " + mode + " " + durationMs + " " + hex;
        String staged = "cp -f '" + helper + "' " + TMP_PATH + " && chmod 755 " + TMP_PATH;

        if (tryShizukuTransmit(staged, helperCmd)) {
            return null;
        }
        if (trySuTransmit(staged, helperCmd)) {
            return null;
        }
        return "privileged_exec_failed";
    }

    private boolean tryShizukuTransmit(String staged, String helperCmd) {
        if (!isShizukuGranted()) {
            return false;
        }
        String cmd = staged + " && " + helperCmd + " > " + OUT_PATH + " 2>&1";
        try {
            Object shizuku = loadHostKotlinObject("com.example.ava.utils.ShizukuUtils");
            if (shizuku == null) {
                return false;
            }
            Method execute = shizuku.getClass().getMethod("executeCommand", String.class);
            Object result = execute.invoke(shizuku, cmd);
            int code = pairFirstInt(result);
            String out = readFile(new File(OUT_PATH));
            if (code == 0 && (out.contains("OK") || out.isEmpty())) {
                if (!out.isEmpty()) {
                    logTransport(out);
                } else {
                    Log.d(TAG, "raw HCI Shizuku exit 0 (output not readable from app)");
                }
                return true;
            }
            Log.w(TAG, "Shizuku raw HCI failed code=" + code + " out=" + out.trim());
        } catch (Exception e) {
            Log.w(TAG, "Shizuku raw HCI exec error", e);
        }
        return false;
    }

    private boolean trySuTransmit(String staged, String helperCmd) {
        if (!isRootAvailable()) {
            return false;
        }
        String cmd = staged + " && " + helperCmd;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            String out = readFully(process);
            int code = process.waitFor();
            if (code == 0 && out.contains("OK")) {
                logTransport(out);
                return true;
            }
            String reason = out.isEmpty() ? ("exit_" + code) : out.trim();
            Log.w(TAG, "su raw HCI failed: " + reason);
        } catch (Exception e) {
            Log.w(TAG, "su raw HCI exec error", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }

    private void logTransport(String out) {
        if (out.contains("mgmt")) {
            Log.d(TAG, "raw injected via MGMT Add Advertising (1:1)");
        } else {
            Log.d(TAG, "raw injected via HCI LE Set Advertising Data (1:1)");
        }
    }

    private boolean isPrivilegedAvailable() {
        return isShizukuGranted() || isRootAvailable();
    }

    private boolean isShizukuGranted() {
        try {
            Object shizuku = loadHostKotlinObject("com.example.ava.utils.ShizukuUtils");
            if (shizuku == null) {
                return false;
            }
            Method method = shizuku.getClass().getMethod("isShizukuPermissionGranted");
            Boolean granted = (Boolean) method.invoke(shizuku);
            return granted != null && granted;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRootAvailable() {
        if (privState == PRIV_AVAILABLE) {
            return true;
        }
        if (privState == PRIV_UNAVAILABLE) {
            return false;
        }
        try {
            Class<?> rootUtils = loadHostClass("com.example.ava.utils.RootUtils");
            Object instance = kotlinObject(rootUtils);
            if (instance != null) {
                Boolean binaryPresent = (Boolean) rootUtils.getMethod("isRootBinaryPresent").invoke(instance);
                if (binaryPresent != null && !binaryPresent) {
                    privState = PRIV_UNAVAILABLE;
                    return false;
                }
                Boolean available = (Boolean) rootUtils.getMethod("isRootAvailable").invoke(instance);
                if (available != null) {
                    privState = available ? PRIV_AVAILABLE : PRIV_UNAVAILABLE;
                    return available;
                }
            }
        } catch (Exception ignored) {
        }
        boolean probed = probeRootOnce();
        privState = probed ? PRIV_AVAILABLE : PRIV_UNAVAILABLE;
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

    private static String readFile(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
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

    private Object loadHostKotlinObject(String name) {
        try {
            return kotlinObject(loadHostClass(name));
        } catch (Exception e) {
            return null;
        }
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

    private static int pairFirstInt(Object pair) {
        if (pair == null) {
            return -1;
        }
        try {
            Method getFirst = pair.getClass().getMethod("getFirst");
            Object first = getFirst.invoke(pair);
            if (first instanceof Number) {
                return ((Number) first).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
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
