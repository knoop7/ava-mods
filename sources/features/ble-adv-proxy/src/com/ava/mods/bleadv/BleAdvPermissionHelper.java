package com.ava.mods.bleadv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Self-authorization for raw HCI/MGMT — mirrors portal-support's Shizuku flow without
 * depending on Ava ShellService output capture.
 */
final class BleAdvPermissionHelper {
    private static final String TAG = "BleAdvPermission";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static volatile boolean shizukuPrompted = false;

    private final Context context;

    BleAdvPermissionHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Prime host ShizukuUtils and prompt once when privileged access is needed. */
    void ensurePrivilegedAccess() {
        ensureHostShizukuInit();
        if (isShizukuReady() || isRootAvailable()) {
            return;
        }
        requestShizukuPermissionIfNeeded();
    }

    String getPrivilegedShellLabel() {
        if (isShizukuReady()) {
            return "shizuku";
        }
        if (isRootAvailable()) {
            return "root";
        }
        Boolean running = isShizukuRunningState();
        if (running != null && running && !isShizukuGrantedDirect()) {
            return "shizuku_pending";
        }
        return "none";
    }

    boolean isPrivilegedAvailable() {
        return isShizukuReady() || isRootAvailable();
    }

    boolean isShizukuReady() {
        Boolean running = isShizukuRunningState();
        if (running == null || !running) {
            return false;
        }
        return isShizukuGrantedDirect();
    }

    boolean isRootAvailable() {
        try {
            Class<?> rootUtils = loadHostClass("com.example.ava.utils.RootUtils");
            Object instance = kotlinObject(rootUtils);
            if (instance != null) {
                Boolean binaryPresent = (Boolean) rootUtils.getMethod("isRootBinaryPresent").invoke(instance);
                if (binaryPresent != null && !binaryPresent) {
                    return false;
                }
                Boolean available = (Boolean) rootUtils.getMethod("isRootAvailable").invoke(instance);
                if (available != null && available) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return probeRootOnce();
    }

    Process newShizukuProcess(String[] command) {
        if (!isShizukuReady()) {
            return null;
        }
        try {
            Class<?> shizuku = loadHostClass("rikka.shizuku.Shizuku");
            Method newProcess = shizuku.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Object raw = newProcess.invoke(null, new Object[]{command, null, null});
            if (raw instanceof Process) {
                return (Process) raw;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku newProcess failed: " + t.getMessage());
        }
        return null;
    }

    Process newRootProcess(String shellCommand) {
        if (!isRootAvailable()) {
            return null;
        }
        try {
            return Runtime.getRuntime().exec(new String[]{"su", "-c", shellCommand});
        } catch (Exception e) {
            Log.w(TAG, "su process failed: " + e.getMessage());
            return null;
        }
    }

    private void ensureHostShizukuInit() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = kotlinObject(shizukuUtils);
            if (instance == null) {
                return;
            }
            Method init = shizukuUtils.getMethod("init", String.class);
            init.invoke(instance, context.getPackageName());
        } catch (Exception e) {
            Log.d(TAG, "ShizukuUtils.init unavailable: " + e.getMessage());
        }
    }

    private void requestShizukuPermissionIfNeeded() {
        if (shizukuPrompted) {
            return;
        }
        shizukuPrompted = true;
        Boolean running = isShizukuRunningState();
        if (running == null || !running) {
            launchShizukuApp();
            return;
        }
        if (!isShizukuGrantedDirect()) {
            tryRequestShizukuPermission();
        }
    }

    private void launchShizukuApp() {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "launched Shizuku for raw HCI authorization");
            } else {
                launchAppDetails();
            }
        } catch (Exception e) {
            Log.w(TAG, "Shizuku launch failed: " + e.getMessage());
            launchAppDetails();
        }
    }

    private void launchAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void tryRequestShizukuPermission() {
        try {
            Class<?> shizuku = loadHostClass("rikka.shizuku.Shizuku");
            Method request = shizuku.getMethod("requestPermission", int.class);
            request.invoke(null, 0);
            Log.i(TAG, "Shizuku.requestPermission sent");
            return;
        } catch (Exception e) {
            Log.d(TAG, "Shizuku.requestPermission failed: " + e.getMessage());
        }
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = kotlinObject(shizukuUtils);
            if (instance != null) {
                try {
                    shizukuUtils.getMethod("requestPermission").invoke(instance);
                    Log.i(TAG, "ShizukuUtils.requestPermission sent");
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        launchShizukuApp();
    }

    private Boolean isShizukuRunningState() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = kotlinObject(shizukuUtils);
            if (instance != null) {
                return (Boolean) shizukuUtils.getMethod("isShizukuRunning").invoke(instance);
            }
        } catch (Exception ignored) {
        }
        try {
            Class<?> shizuku = loadHostClass("rikka.shizuku.Shizuku");
            return (Boolean) shizuku.getMethod("pingBinder").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isShizukuGrantedDirect() {
        try {
            Class<?> shizuku = loadHostClass("rikka.shizuku.Shizuku");
            Method ping = shizuku.getMethod("pingBinder");
            Boolean alive = (Boolean) ping.invoke(null);
            if (alive == null || !alive) {
                return false;
            }
            Method check = shizuku.getMethod("checkSelfPermission");
            Object result = check.invoke(null);
            int code = result instanceof Integer ? (Integer) result : -1;
            return code == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            try {
                Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
                Object instance = kotlinObject(shizukuUtils);
                if (instance != null) {
                    Boolean granted = (Boolean) shizukuUtils
                            .getMethod("isShizukuPermissionGranted").invoke(instance);
                    return granted != null && granted;
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private boolean probeRootOnce() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readLine(process);
            int code = process.waitFor();
            return code == 0 || (out != null && out.contains("uid=0"));
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readLine(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        try {
            return reader.readLine();
        } finally {
            reader.close();
        }
    }

    private Class<?> loadHostClass(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = new ClassLoader[] {
                context.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                context.getApplicationContext().getClassLoader(),
                RawHciAdvertiser.class.getClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
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
}
