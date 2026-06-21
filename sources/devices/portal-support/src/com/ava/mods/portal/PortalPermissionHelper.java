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
        String pkg = context.getPackageName();
        if (tryShizukuGrant(pkg) || tryRootGrant(pkg)) {
            return true;
        }
        requestShizukuPermissionIfNeeded();
        return false;
    }

    boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    boolean ensurePermission(String permission) {
        if (hasPermission(permission)) {
            return true;
        }
        String pkg = context.getPackageName();
        String cmd = "pm grant " + pkg + " " + permission;
        if (tryShizukuExec(cmd) == 0 && hasPermission(permission)) {
            return true;
        }
        if (tryRootExec(cmd) == 0 && hasPermission(permission)) {
            return true;
        }
        requestShizukuPermissionIfNeeded();
        return false;
    }

    int executeShell(String command) {
        int code = tryShizukuExec(command);
        if (code >= 0) {
            return code;
        }
        return tryRootExec(command);
    }

    void ensureAppOps() {
        String pkg = context.getPackageName();
        if (tryShizukuExec("appops set " + pkg + " WRITE_SETTINGS allow") != 0) {
            tryRootExec("appops set " + pkg + " WRITE_SETTINGS allow");
        }
        if (tryShizukuExec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow") != 0) {
            tryRootExec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        }
        if (!canDrawOverlays()) {
            launchOverlaySettings();
        }
        if (!canWriteSettings()) {
            launchWriteSettings();
        }
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(context);
    }

    private boolean canWriteSettings() {
        return Settings.System.canWrite(context);
    }

    private void launchOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "launched overlay settings");
        } catch (Exception e) {
            Log.w(TAG, "overlay settings launch failed: " + e.getMessage());
        }
    }

    private void launchWriteSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "launched write settings");
        } catch (Exception e) {
            Log.w(TAG, "write settings launch failed: " + e.getMessage());
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
        boolean ok = true;
        for (String perm : GRANT_PERMISSIONS) {
            if (tryShizukuExec("pm grant " + pkg + " " + perm) != 0) {
                ok = false;
            }
        }
        for (String op : GRANT_APPOPS) {
            if (tryShizukuExec("appops set " + pkg + " " + op + " allow") != 0) {
                ok = false;
            }
        }
        return ok;
    }

    private boolean tryRootGrant(String pkg) {
        if (!isRootAvailable()) {
            return false;
        }
        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                for (String perm : GRANT_PERMISSIONS) {
                    os.writeBytes("pm grant " + pkg + " " + perm + "\n");
                }
                for (String op : GRANT_APPOPS) {
                    os.writeBytes("appops set " + pkg + " " + op + " allow\n");
                }
                os.writeBytes("exit\n");
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            Log.w(TAG, "root grant failed: " + e.getMessage());
            return false;
        }
    }

    private void requestShizukuPermissionIfNeeded() {
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
