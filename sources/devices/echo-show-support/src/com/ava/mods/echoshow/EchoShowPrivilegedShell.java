package com.ava.mods.echoshow;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Best-effort Shizuku / root shell for Echo Show device hooks only.
 * Does not depend on Ava classes at compile time; reflects host helpers when present.
 */
final class EchoShowPrivilegedShell {
    private static final String TAG = "EchoShowSupport";

    private EchoShowPrivilegedShell() {
    }

    static boolean isRootAvailable() {
        try {
            Class<?> rootUtils = Class.forName("com.example.ava.utils.RootUtils");
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

    static boolean isShizukuGranted() {
        try {
            Class<?> shizukuUtils = Class.forName("com.example.ava.utils.ShizukuUtils");
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

    static int execShell(String command) {
        int code = tryShizukuExec(command);
        if (code == 0) {
            return 0;
        }
        return tryRootExec(command);
    }

    static boolean setDisplayPower(int mode) {
        if (isShizukuGranted()) {
            try {
                Class<?> shizukuUtils = Class.forName("com.example.ava.utils.ShizukuUtils");
                Object instance = getKotlinObjectInstance(shizukuUtils);
                if (instance != null) {
                    Boolean ok = (Boolean) shizukuUtils.getMethod("setDisplayPower", int.class)
                            .invoke(instance, mode);
                    if (ok != null && ok) {
                        Log.i(TAG, "setDisplayPower(" + mode + ") via Shizuku");
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Shizuku setDisplayPower failed: " + e.getMessage());
            }
        }
        return false;
    }

    static boolean writeBacklightBrightness(int brightness) {
        try {
            Class<?> rootUtils = Class.forName("com.example.ava.utils.RootUtils");
            Object instance = getKotlinObjectInstance(rootUtils);
            if (instance != null) {
                Boolean ok = (Boolean) rootUtils.getMethod("writeBacklightBrightness", int.class)
                        .invoke(instance, brightness);
                if (ok != null && ok) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        // LineageOS Echo Show (MTK) may expose either class/backlight or platform leds-mt65xx.
        for (String path : BACKLIGHT_BRIGHTNESS_PATHS) {
            if (execShell("echo " + brightness + " > " + path) == 0) {
                Log.i(TAG, "writeBacklightBrightness via " + path);
                return true;
            }
        }
        return false;
    }

    /**
     * Known Echo Show lcd-backlight sysfs nodes.
     * Crown / Lineage 18.1 devices often use the leds-mt65xx platform path instead of
     * /sys/class/backlight/lcd-backlight (see device reports + amazon-oss local_manifests).
     */
    private static final String[] BACKLIGHT_BRIGHTNESS_PATHS = new String[]{
            "/sys/class/backlight/lcd-backlight/brightness",
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/class/leds/lcd-backlight/brightness",
    };

    private static int tryShizukuExec(String command) {
        if (!isShizukuGranted()) {
            return -1;
        }
        try {
            Class<?> shizukuUtils = Class.forName("com.example.ava.utils.ShizukuUtils");
            Object instance = getKotlinObjectInstance(shizukuUtils);
            if (instance == null) {
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

    private static int tryRootExec(String command) {
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

    private static boolean probeRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            int code = process.waitFor();
            return code == 0 && line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static Object getKotlinObjectInstance(Class<?> clazz) {
        try {
            return clazz.getField("INSTANCE").get(null);
        } catch (Exception e) {
            return null;
        }
    }
}
