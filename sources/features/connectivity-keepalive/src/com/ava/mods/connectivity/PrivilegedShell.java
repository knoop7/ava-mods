package com.ava.mods.connectivity;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Executes shell commands with root first, then Shizuku.
 * Never runs commands that disable WiFi or ADB.
 */
final class PrivilegedShell {

    private static final String TAG = "ConnKeepAlive";

    private final Context context;

    PrivilegedShell(Context context) {
        this.context = context.getApplicationContext();
    }

    int execute(String command) {
        if (command == null || command.trim().isEmpty()) {
            return -1;
        }
        int code = tryRootExec(command);
        if (code >= 0) {
            return code;
        }
        return tryShizukuExec(command);
    }

    String readSetting(String key) {
        String output = captureOutput("settings get global " + key);
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    boolean setGlobalSetting(String key, String value) {
        return execute("settings put global " + key + " " + value) == 0;
    }

    boolean isRootAvailable() {
        try {
            Class<?> rootUtils = loadHostClass("com.example.ava.utils.RootUtils");
            Object instance = getKotlinObjectInstance(rootUtils);
            if (instance != null) {
                Boolean available = (Boolean) rootUtils.getMethod("isRootAvailable").invoke(instance);
                if (available != null && available) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return probeRoot();
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

    boolean hasPrivilegedAccess() {
        return isRootAvailable() || isShizukuGranted();
    }

    String captureOutput(String command) {
        String rootOutput = tryRootCapture(command);
        if (rootOutput != null) {
            return rootOutput;
        }
        return tryShizukuCapture(command);
    }

    private int tryRootExec(String command) {
        if (!isRootAvailable()) {
            return -1;
        }
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "root exec failed: " + e.getMessage());
            return -1;
        }
    }

    private String tryRootCapture(String command) {
        if (!isRootAvailable()) {
            return null;
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            String output = readProcessOutput(process);
            if (process.waitFor() == 0) {
                return output;
            }
        } catch (Exception e) {
            Log.w(TAG, "root capture failed: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return null;
    }

    private int tryShizukuExec(String command) {
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
            Object pair = shizukuUtils.getMethod("executeCommand", String.class).invoke(instance, command);
            if (pair == null) {
                return -1;
            }
            Integer code = (Integer) pair.getClass().getMethod("getFirst").invoke(pair);
            return code != null ? code : -1;
        } catch (Exception e) {
            Log.w(TAG, "shizuku exec failed: " + e.getMessage());
            return -1;
        }
    }

    private String tryShizukuCapture(String command) {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                return null;
            }
            Boolean granted = (Boolean) shizukuUtils.getMethod("isShizukuPermissionGranted").invoke(instance);
            if (granted == null || !granted) {
                return null;
            }
            Object pair = shizukuUtils.getMethod("executeCommand", String.class).invoke(instance, command);
            if (pair == null) {
                return null;
            }
            Integer code = (Integer) pair.getClass().getMethod("getFirst").invoke(pair);
            String output = (String) pair.getClass().getMethod("getSecond").invoke(pair);
            if (code != null && code == 0) {
                return output;
            }
        } catch (Exception e) {
            Log.w(TAG, "shizuku capture failed: " + e.getMessage());
        }
        return null;
    }

    private boolean probeRoot() {
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

    private String readProcessOutput(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        reader.close();
        return sb.toString();
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
