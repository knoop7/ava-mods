package com.ava.mods.irblaster;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Thin root/Shizuku shell used only for best-effort probing of kernel IR nodes
 * (/dev/lirc*, /sys/class/rc/*). USB device enumeration itself needs no privilege.
 *
 * Root detection reflects the host's com.example.ava.utils.RootUtils when present,
 * otherwise probes "su -c id" directly. No privilege is ever required for the mod
 * to function — detection simply reports less when unprivileged.
 */
final class PrivilegedShell {

    private static final String TAG = "IrBlaster";

    private static final int ROOT_UNKNOWN = 0;
    private static final int ROOT_AVAILABLE = 1;
    private static final int ROOT_UNAVAILABLE = 2;

    private static volatile int rootState = ROOT_UNKNOWN;

    private final Context context;

    PrivilegedShell(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean hasPrivilegedAccess() {
        return isRootAvailable() || isShizukuGranted();
    }

    /** Runs a command and returns stdout, or null if it could not run. Root only. */
    String captureOutput(String command) {
        if (command == null || command.trim().isEmpty()) {
            return null;
        }
        if (!isRootAvailable()) {
            return null;
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "captureOutput failed: " + e.getMessage());
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    boolean isRootAvailable() {
        if (rootState == ROOT_AVAILABLE) {
            return true;
        }
        if (rootState == ROOT_UNAVAILABLE) {
            return false;
        }
        try {
            Class<?> rootUtils = loadHostClass("com.example.ava.utils.RootUtils");
            Object instance = getKotlinObjectInstance(rootUtils);
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

    boolean isShizukuGranted() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                return false;
            }
            Boolean granted = (Boolean) shizukuUtils.getMethod("isShizukuPermissionGranted").invoke(instance);
            return granted != null && granted;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs a command through the host's Shizuku ShellService (com.example.ava.utils.ShizukuUtils
     * .executeCommand), which executes as the Shizuku process uid — shell or root, both of which
     * hold TRANSMIT_IR. Returns the command exit code, or -1 when Shizuku is unavailable.
     *
     * This is preferred over raw {@code su}: it reuses the privilege the user already granted to
     * the host app and never triggers a separate root prompt.
     */
    int shizukuExec(String command) {
        if (command == null || command.trim().isEmpty()) {
            return -1;
        }
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                return -1;
            }
            Boolean granted = (Boolean) shizukuUtils.getMethod("isShizukuPermissionGranted").invoke(instance);
            if (granted == null || !granted) {
                return -1;
            }
            // executeCommand(String) returns kotlin.Pair<Integer, String>; first = exit code.
            Object pair = shizukuUtils.getMethod("executeCommand", String.class).invoke(instance, command);
            if (pair == null) {
                return -1;
            }
            Object first = pair.getClass().getMethod("getFirst").invoke(pair);
            return first instanceof Integer ? (Integer) first : -1;
        } catch (Exception e) {
            Log.w(TAG, "shizukuExec failed: " + e.getMessage());
            return -1;
        }
    }

    private boolean probeRootOnce() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            int code = process.waitFor();
            if (code == 0) {
                return true;
            }
            return line != null && (line.contains("uid=0") || line.contains("gid=0"));
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private Object getKotlinObjectInstance(Class<?> clazz) {
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

    private Class<?> loadHostClass(String className) throws ClassNotFoundException {
        ClassLoader loader = context.getClassLoader();
        if (loader != null) {
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(className);
    }
}
