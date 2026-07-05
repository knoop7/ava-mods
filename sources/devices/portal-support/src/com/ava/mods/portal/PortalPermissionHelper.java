package com.ava.mods.portal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

final class PortalPermissionHelper {

    private static final String TAG = "PortalSupport";

    private static volatile boolean shizukuPrompted = false;

    private static final String[] GRANT_PERMISSIONS = {
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.READ_LOGS"
    };

    private static final String[] GRANT_APPOPS = {
            "WRITE_SETTINGS",
            "SYSTEM_ALERT_WINDOW"
    };

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private final Context context;

    PortalPermissionHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean grantAll() {
        ensureHostShizukuInit();
        String pkg = context.getPackageName();
        if (allRuntimePermissionsGranted()) {
            ensureAppOps();
            return true;
        }
        if (tryShizukuGrant(pkg)) {
            ensureAppOps();
        } else if (tryRootGrant(pkg)) {
            ensureAppOps();
        } else {
            requestShizukuPermissionIfNeeded();
        }
        boolean ok = allRuntimePermissionsGranted();
        if (!ok) {
            Log.w(TAG, "grantAll incomplete — need Shizuku/root for "
                    + "WRITE_SECURE_SETTINGS, RECORD_AUDIO, CAMERA");
        }
        return ok;
    }

    boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    boolean ensurePermission(String permission) {
        if (hasPermission(permission)) {
            return true;
        }
        ensureHostShizukuInit();
        requestHostGrant(permission);
        String pkg = context.getPackageName();
        String cmd = "pm grant " + pkg + " " + permission;
        if (execPrivileged(cmd) == 0 && hasPermission(permission)) {
            return true;
        }
        requestShizukuPermissionIfNeeded();
        return hasPermission(permission);
    }

    /** Shizuku first, root fallback — only when the privileged shell actually succeeded. */
    private int execPrivileged(String command) {
        int code = tryShizukuExec(command);
        if (code == 0) {
            return 0;
        }
        return tryRootExec(command);
    }

    int executeShell(String command) {
        return execPrivileged(command);
    }

    /**
     * Start a long-running command with a privileged shell identity that already holds
     * READ_LOGS (Shizuku shell uid or root), returning the live {@link Process} so the
     * caller can stream stdout. This is how presence monitoring reads {@code logcat}
     * without granting READ_LOGS to the app process (a runtime grant only takes effect
     * after the app restarts, because the {@code log} gid is injected at process fork).
     *
     * @return a running process, or {@code null} when no privileged channel is available.
     */
    Process newPrivilegedProcess(String[] command) {
        Process process = tryShizukuProcess(command);
        if (process != null) {
            return process;
        }
        return tryRootProcess(command);
    }

    /** Prime Shizuku and prompt for authorization before presence logcat starts. */
    void ensurePresencePrivilegedShell() {
        ensureHostShizukuInit();
        if (isShizukuReady() || isRootAvailable()) {
            return;
        }
        Log.w(TAG, "presence needs Shizuku or root — requesting authorization");
        requestShizukuPermissionIfNeeded();
    }

    private Process tryShizukuProcess(String[] command) {
        if (!isShizukuReady()) {
            return null;
        }
        try {
            Class<?> shizukuClass = loadHostClass("rikka.shizuku.Shizuku");
            // Shizuku.newProcess(String[], String[], String) is private static — the shell
            // spawned by it already holds READ_LOGS, so reach it via reflection.
            Method newProcess = shizukuClass.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Object process = newProcess.invoke(null, new Object[]{command, null, null});
            if (process instanceof Process) {
                Log.i(TAG, "presence logcat via Shizuku shell");
                return (Process) process;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku newProcess unavailable: " + t.getMessage());
        }
        return null;
    }

    private Process tryRootProcess(String[] command) {
        if (!isRootAvailable()) {
            return null;
        }
        try {
            StringBuilder joined = new StringBuilder();
            for (String part : command) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(part);
            }
            Process process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", joined.toString()});
            Log.i(TAG, "presence logcat via root shell");
            return process;
        } catch (Exception e) {
            Log.w(TAG, "root process failed: " + e.getMessage());
            return null;
        }
    }

    void ensureAppOps() {
        ensureHostShizukuInit();
        String pkg = context.getPackageName();
        requestHostGrant("appops:WRITE_SETTINGS");
        requestHostGrant("appops:SYSTEM_ALERT_WINDOW");
        if (execPrivileged("appops set " + pkg + " WRITE_SETTINGS allow") != 0) {
            Log.w(TAG, "WRITE_SETTINGS app-op grant failed");
        }
        if (execPrivileged("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow") != 0) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW app-op grant failed");
        }
    }

    private boolean allRuntimePermissionsGranted() {
        for (String perm : GRANT_PERMISSIONS) {
            if (!hasPermission(perm)) {
                return false;
            }
        }
        return true;
    }

    /**
     * VoiceSatelliteService calls grantOverlayPermissionIfNeeded at ~1.8s but ShizukuUtils.init
     * runs at ~2.5s. Prime the host helper first so pm grant / shell exec can bind ShellService.
     */
    private void ensureHostShizukuInit() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
                return;
            }
            Method init = shizukuUtils.getMethod("init", String.class);
            init.invoke(instance, context.getPackageName());
        } catch (Exception e) {
            Log.d(TAG, "ShizukuUtils.init not available: " + e.getMessage());
        }
    }

    /** Fire the host AvaControlReceiver grant actions (same path as adb broadcast). */
    private void requestHostGrant(String permissionOrAppOp) {
        String action;
        if ("android.permission.RECORD_AUDIO".equals(permissionOrAppOp)) {
            action = "com.example.ava.ACTION_GRANT_RECORD_AUDIO";
        } else if ("android.permission.CAMERA".equals(permissionOrAppOp)) {
            action = "com.example.ava.ACTION_GRANT_CAMERA";
        } else if ("android.permission.WRITE_SECURE_SETTINGS".equals(permissionOrAppOp)) {
            action = "com.example.ava.ACTION_GRANT_SECURE_SETTINGS";
        } else if ("appops:WRITE_SETTINGS".equals(permissionOrAppOp)) {
            action = "com.example.ava.ACTION_GRANT_WRITE_SETTINGS";
        } else if ("appops:SYSTEM_ALERT_WINDOW".equals(permissionOrAppOp)) {
            action = "com.example.ava.ACTION_GRANT_OVERLAY";
        } else {
            return;
        }
        try {
            context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()));
        } catch (Exception e) {
            Log.d(TAG, "host grant broadcast failed for " + action + ": " + e.getMessage());
        }
    }

    private void launchAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "launched app details settings");
        } catch (Exception e) {
            Log.w(TAG, "app details launch failed: " + e.getMessage());
        }
    }

    private boolean tryShizukuGrant(String pkg) {
        if (!isShizukuReady()) {
            return false;
        }
        for (String perm : GRANT_PERMISSIONS) {
            if (hasPermission(perm)) {
                continue;
            }
            requestHostGrant(perm);
            if (tryShizukuExec("pm grant " + pkg + " " + perm) != 0) {
                Log.w(TAG, "Shizuku pm grant failed: " + perm);
            }
        }
        for (String op : GRANT_APPOPS) {
            if (tryShizukuExec("appops set " + pkg + " " + op + " allow") != 0) {
                Log.w(TAG, "Shizuku appops set failed: " + op);
            }
        }
        return allRuntimePermissionsGranted();
    }

    private boolean tryRootGrant(String pkg) {
        if (!isRootAvailable()) {
            return false;
        }
        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                for (String perm : GRANT_PERMISSIONS) {
                    if (!hasPermission(perm)) {
                        requestHostGrant(perm);
                        os.writeBytes("pm grant " + pkg + " " + perm + "\n");
                    }
                }
                for (String op : GRANT_APPOPS) {
                    os.writeBytes("appops set " + pkg + " " + op + " allow\n");
                }
                os.writeBytes("exit\n");
            }
            process.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "root grant failed: " + e.getMessage());
            return false;
        }
        return allRuntimePermissionsGranted();
    }

    private void requestShizukuPermissionIfNeeded() {
        if (shizukuPrompted) {
            return;
        }
        shizukuPrompted = true;
        Boolean running = isShizukuRunningState();
        if (running == null) {
            launchShizukuApp();
            return;
        }
        if (!running) {
            launchShizukuApp();
            return;
        }
        Boolean granted = isShizukuGrantedState();
        if (granted == null || !granted) {
            tryRequestShizukuPermission();
        }
    }

    private void launchShizukuApp() {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "launched Shizuku app for authorization");
            } else {
                launchAppDetailsSettings();
            }
        } catch (Exception e) {
            Log.w(TAG, "Shizuku launch failed: " + e.getMessage());
            launchAppDetailsSettings();
        }
    }

    private void tryRequestShizukuPermission() {
        try {
            Class<?> shizukuClass = loadHostClass("rikka.shizuku.Shizuku");
            Method requestMethod = shizukuClass.getMethod("requestPermission", int.class);
            requestMethod.invoke(null, 0);
            Log.i(TAG, "Shizuku permission request sent");
        } catch (Exception e) {
            try {
                Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
                Object instance = getKotlinObjectInstance(shizukuUtils);
                if (instance != null) {
                    try {
                        Method m = shizukuUtils.getMethod("requestPermission");
                        m.invoke(instance);
                        Log.i(TAG, "ShizukuUtils.requestPermission sent");
                        return;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            launchShizukuApp();
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

    private boolean isShizukuReady() {
        Boolean running = isShizukuRunningState();
        if (running == null || !running) {
            return false;
        }
        Boolean granted = isShizukuGrantedState();
        return granted != null && granted;
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

    private boolean isRootAvailable() {
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
