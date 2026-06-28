package com.ava.mods.connectivity;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Reads settings via ContentResolver / SystemProperties (no root).
 * Writes use ContentResolver when allowed; root/Shizuku only for privileged shell commands.
 */
final class PrivilegedShell {

    private static final String TAG = "ConnKeepAlive";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String HOST_MAIN_ACTIVITY = "com.example.ava.MainActivity";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1003;
    private static final long SHIZUKU_DIALOG_DELAY_MS = 500L;

    private static final int ROOT_UNKNOWN = 0;
    private static final int ROOT_AVAILABLE = 1;
    private static final int ROOT_UNAVAILABLE = 2;

    private static volatile boolean shizukuPrompted;
    private static volatile int rootState = ROOT_UNKNOWN;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    PrivilegedShell(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Read global setting — no root. */
    String readSetting(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            String value = Settings.Global.getString(context.getContentResolver(), key);
            if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
                return null;
            }
            return value;
        } catch (Exception e) {
            Log.w(TAG, "Settings.Global.getString(" + key + ") failed: " + e.getMessage());
            return null;
        }
    }

    /** Read system property — no root. */
    String getSystemProperty(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method get = clazz.getMethod("get", String.class);
            Object result = get.invoke(null, key);
            if (!(result instanceof String)) {
                return null;
            }
            String value = ((String) result).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            Log.w(TAG, "SystemProperties.get(" + key + ") failed: " + e.getMessage());
            return null;
        }
    }

    boolean setGlobalSetting(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        try {
            if (Settings.Global.putString(context.getContentResolver(), key, value)) {
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Settings.Global.putString(" + key + ") failed: " + e.getMessage());
        }
        return execute("settings put global " + key + " " + value) == 0;
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

    int executePrivilegedBatch(String[] commands) {
        if (commands == null || commands.length == 0) {
            return -1;
        }
        if (isRootAvailable()) {
            return tryRootExecBatch(commands);
        }
        if (isShizukuGranted()) {
            for (String command : commands) {
                if (tryShizukuExec(command) != 0) {
                    return -1;
                }
            }
            return 0;
        }
        return -1;
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
        Boolean granted = isShizukuGrantedState();
        return granted != null && granted;
    }

    boolean isShizukuRunning() {
        Boolean running = isShizukuRunningState();
        return running != null && running;
    }

    boolean hasPrivilegedAccess() {
        return isRootAvailable() || isShizukuGranted();
    }

    void ensurePrivilegedAccess() {
        if (isRootAvailable() || isShizukuGranted()) {
            return;
        }
        requestShizukuAccessOnce();
    }

    private void requestShizukuAccessOnce() {
        if (shizukuPrompted) {
            return;
        }
        shizukuPrompted = true;

        if (!isShizukuRunning()) {
            Log.i(TAG, "Shizuku not running — launching Shizuku app once");
            launchShizukuApp();
            scheduleShizukuPermissionRequest();
            return;
        }

        scheduleShizukuPermissionRequest();
    }

    private void scheduleShizukuPermissionRequest() {
        if (isShizukuGranted()) {
            return;
        }
        bringHostActivityToFront();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                requestShizukuPermissionOnMainThread();
            }
        }, SHIZUKU_DIALOG_DELAY_MS);
    }

    private void requestShizukuPermissionOnMainThread() {
        if (isRootAvailable() || isShizukuGranted()) {
            return;
        }
        if (!isShizukuRunning()) {
            Log.i(TAG, "Shizuku still not running after one-time prompt");
            return;
        }
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                Log.w(TAG, "ShizukuUtils instance unavailable");
                return;
            }
            ensureShizukuInitialized(shizukuUtils, instance);
            Method requestMethod = shizukuUtils.getMethod("requestPermission", int.class);
            requestMethod.invoke(instance, SHIZUKU_PERMISSION_REQUEST_CODE);
            Log.i(TAG, "one-time ShizukuUtils.requestPermission(" + SHIZUKU_PERMISSION_REQUEST_CODE + ")");
        } catch (Exception e) {
            Log.w(TAG, "ShizukuUtils.requestPermission failed: " + e.getMessage());
        }
    }

    private void ensureShizukuInitialized(Class<?> shizukuUtils, Object instance) {
        try {
            shizukuUtils.getMethod("init", String.class).invoke(instance, context.getPackageName());
        } catch (NoSuchMethodException e) {
            try {
                shizukuUtils.getMethod("init").invoke(instance);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "ShizukuUtils.init failed: " + e.getMessage());
        }
    }

    private void bringHostActivityToFront() {
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), HOST_MAIN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(intent);
            Log.i(TAG, "brought MainActivity to front for one-time Shizuku authorization");
        } catch (Exception e) {
            Log.w(TAG, "failed to bring MainActivity to front: " + e.getMessage());
        }
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

    private int tryRootExecBatch(String[] commands) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            for (String command : commands) {
                os.writeBytes(command);
                os.writeBytes("\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            return process.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "root batch exec failed: " + e.getMessage());
            return -1;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
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
        } catch (ClassNotFoundException e) {
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "shizuku exec failed: " + e.getMessage());
            return -1;
        }
    }

    private void launchShizukuApp() {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "launched Shizuku app");
            }
        } catch (Exception e) {
            Log.w(TAG, "Shizuku launch failed: " + e.getMessage());
        }
    }

    private Boolean isShizukuRunningState() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                return null;
            }
            return (Boolean) shizukuUtils.getMethod("isShizukuRunning").invoke(instance);
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean isShizukuGrantedState() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                return null;
            }
            return (Boolean) shizukuUtils.getMethod("isShizukuPermissionGranted").invoke(instance);
        } catch (Exception e) {
            return null;
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
