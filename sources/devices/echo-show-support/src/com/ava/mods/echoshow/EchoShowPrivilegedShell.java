package com.ava.mods.echoshow;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Best-effort Shizuku / root shell for Echo Show device hooks only.
 * Does not depend on Ava classes at compile time; reflects host helpers when present.
 */
final class EchoShowPrivilegedShell {
    private static final String TAG = "EchoShowSupport";

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

    /**
     * JSA1214 ALS lux nodes exposed by amazon-oss mt8163 alsps driver (DRIVER_ATTR lux).
     * TYPE_LIGHT often stops while the panel is blanked; these nodes still work with privilege.
     */
    private static final String[] ALS_LUX_CANDIDATES = new String[]{
            "/sys/bus/platform/drivers/als_ps/lux",
            "/sys/devices/platform/als_ps/lux",
            "/sys/bus/platform/drivers/alsps/lux",
            "/sys/class/misc/als_ps/lux",
    };

    private static final String SHELL_OUT = "/data/local/tmp/ava_echo_show_shell.out";

    private static volatile String cachedAlsLuxPath;

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

    /**
     * Run a shell command and return trimmed stdout, or null on failure.
     * Prefers root (captures stdout). Shizuku falls back to redirecting into a temp file.
     */
    static String execShellOutput(String command) {
        String fromRoot = tryRootExecOutput(command);
        if (fromRoot != null) {
            return fromRoot;
        }
        if (!isShizukuGranted()) {
            return null;
        }
        String wrapped = "(" + command + ") > " + SHELL_OUT + " 2>/dev/null";
        if (tryShizukuExec(wrapped) != 0) {
            return null;
        }
        String fromFile = tryRootExecOutput("cat " + SHELL_OUT);
        if (fromFile != null) {
            return fromFile;
        }
        return readFileUnprivileged(SHELL_OUT);
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
        for (String path : BACKLIGHT_BRIGHTNESS_PATHS) {
            if (execShell("echo " + brightness + " > " + path) == 0) {
                Log.i(TAG, "writeBacklightBrightness via " + path);
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort lux from JSA1214 alsps sysfs. Returns null if unavailable.
     * Re-enables ALS before read — SensorService / blanked display may have disabled it.
     */
    static Float readAlsLux() {
        String path = resolveAlsLuxPath();
        if (path == null) {
            return null;
        }
        String enablePath = path.endsWith("/lux")
                ? path.substring(0, path.length() - 4) + "/enable"
                : null;
        if (enablePath != null) {
            execShell("echo 1 > " + enablePath);
        }
        String raw = execShellOutput("cat " + path);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // Driver may print "als lux = 12" or a bare number.
        String number = raw.replaceAll("(?s).*?(-?\\d+(?:\\.\\d+)?).*", "$1");
        try {
            return Float.parseFloat(number.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "readAlsLux: unparsable '" + raw + "' from " + path);
            return null;
        }
    }

    private static String resolveAlsLuxPath() {
        String cached = cachedAlsLuxPath;
        if (cached != null) {
            if ("none".equals(cached)) {
                return null;
            }
            return cached;
        }
        for (String candidate : ALS_LUX_CANDIDATES) {
            String probe = execShellOutput("if [ -r " + candidate + " ]; then echo " + candidate + "; fi");
            if (probe != null && probe.contains("/")) {
                cachedAlsLuxPath = probe.trim().split("\\s+")[0];
                Log.i(TAG, "ALS lux path: " + cachedAlsLuxPath);
                return cachedAlsLuxPath;
            }
        }
        String found = execShellOutput(
                "find /sys/bus/platform /sys/devices/platform /sys/class -name lux 2>/dev/null | head -n 1"
        );
        if (found != null && found.contains("/")) {
            cachedAlsLuxPath = found.trim().split("\\s+")[0];
            Log.i(TAG, "ALS lux path (find): " + cachedAlsLuxPath);
            return cachedAlsLuxPath;
        }
        cachedAlsLuxPath = "none";
        Log.w(TAG, "ALS lux sysfs node not found");
        return null;
    }

    /**
     * Best-effort: true when the panel appears powered on (manual power wake after blanking).
     */
    static boolean isDisplayLikelyOn() {
        String state = execShellOutput(
                "dumpsys display 2>/dev/null | grep -E 'mScreenState=' | head -n 1"
        );
        if (state != null) {
            String upper = state.toUpperCase();
            if (upper.contains("SCREEN_STATE_OFF") || upper.contains("STATE_OFF")) {
                return false;
            }
            if (upper.contains("SCREEN_STATE_ON") || upper.contains("STATE_ON")) {
                return true;
            }
        }
        String power = execShellOutput(
                "dumpsys power 2>/dev/null | grep -E 'Display Power: state=|mScreenOn=' | head -n 2"
        );
        if (power != null) {
            String upper = power.toUpperCase();
            if (upper.contains("STATE=OFF") || upper.contains("MSCREENON=FALSE")) {
                return false;
            }
            if (upper.contains("STATE=ON") || upper.contains("MSCREENON=TRUE")) {
                return true;
            }
        }
        return false;
    }

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

    private static String tryRootExecOutput(String command) {
        if (!isRootAvailable()) {
            return null;
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
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
            int code = process.waitFor();
            if (code != 0) {
                return null;
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readFileUnprivileged(String path) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "cat " + path});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            int code = process.waitFor();
            if (code != 0 || line == null) {
                return null;
            }
            return line.trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
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
