package com.ava.mods.connectivity;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Executes shell commands with root first, then Shizuku.
 * Shizuku authorization brings Ava MainActivity to the foreground, then calls
 * ShizukuUtils.requestPermission(requestCode) on the main thread (required for
 * the system dialog to appear above kiosk overlays).
 */
final class PrivilegedShell {

    private static final String TAG = "ConnKeepAlive";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String HOST_MAIN_ACTIVITY = "com.example.ava.MainActivity";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1003;
    private static final long SHIZUKU_PROMPT_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final long SHIZUKU_DIALOG_DELAY_MS = 500L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastShizukuPromptAt;

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
        if (!isShizukuGranted()) {
            requestShizukuAccessIfNeeded();
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
        if (isRootAvailable()) {
            return;
        }
        requestShizukuAccessIfNeeded(true);
    }

    void requestShizukuAccessIfNeeded() {
        requestShizukuAccessIfNeeded(false);
    }

    private void requestShizukuAccessIfNeeded(boolean force) {
        if (isRootAvailable() || isShizukuGranted()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastShizukuPromptAt < SHIZUKU_PROMPT_COOLDOWN_MS) {
            return;
        }
        lastShizukuPromptAt = now;

        if (!isShizukuRunning()) {
            Log.i(TAG, "Shizuku not running — launching Shizuku app");
            launchShizukuApp();
            scheduleShizukuPermissionRequest();
            return;
        }

        scheduleShizukuPermissionRequest();
    }

    private void scheduleShizukuPermissionRequest() {
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
            Log.i(TAG, "Shizuku still not running after prompt");
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
            Log.i(TAG, "ShizukuUtils.requestPermission(" + SHIZUKU_PERMISSION_REQUEST_CODE + ") on main thread");
        } catch (Exception e) {
            Log.w(TAG, "ShizukuUtils.requestPermission failed: " + e.getMessage());
            launchShizukuApp();
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
            Log.i(TAG, "brought MainActivity to front for Shizuku authorization");
        } catch (Exception e) {
            Log.w(TAG, "failed to bring MainActivity to front: " + e.getMessage());
        }
    }

    String captureOutput(String command) {
        String rootOutput = tryRootCapture(command);
        if (rootOutput != null) {
            return rootOutput;
        }
        if (!isShizukuGranted()) {
            requestShizukuAccessIfNeeded();
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
        } catch (ClassNotFoundException e) {
            return -1;
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
