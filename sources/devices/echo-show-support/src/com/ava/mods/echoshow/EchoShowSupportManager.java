package com.ava.mods.echoshow;

import android.content.Context;
import android.os.Build;
import java.io.DataOutputStream;

public class EchoShowSupportManager {
    private static volatile EchoShowSupportManager instance;
    private final Context context;

    private static final String[] ECHO_SHOW_CODENAMES = new String[] {
        "crown", "checkers", "cronos"
    };

    private EchoShowSupportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static EchoShowSupportManager getInstance(Context context) {
        if (instance == null) {
            synchronized (EchoShowSupportManager.class) {
                if (instance == null) {
                    instance = new EchoShowSupportManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isSupported() {
        String model = safeLower(Build.MODEL);
        String board = safeLower(Build.BOARD);
        String device = safeLower(Build.DEVICE);

        for (String codename : ECHO_SHOW_CODENAMES) {
            if (model.contains(codename) || board.contains(codename) || device.contains(codename)) {
                return true;
            }
        }

        return model.contains("amazon") || (model.contains("echo") && model.contains("show"));
    }

    public int getMinBrightness() {
        return 10;
    }

    public boolean isLowEndBleChip() {
        return true;
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        if (!isSupported()) {
            return false;
        }

        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                os.writeBytes("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow\n");
                os.writeBytes("exit\n");
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean grantOverlayPermissionIfNeeded() {
        return grantOverlayPermissionIfNeeded(context);
    }

    /**
     * ModDeviceSupport hook: screensaver "turn off in dark".
     * Only runs when this mod is enabled and {@link #isSupported()} is true.
     * Tries Shizuku display power off, then root keyevents, then min brightness.
     */
    public boolean sleepScreenForDark(Context context) {
        if (!isSupported()) {
            return false;
        }
        return EchoShowScreenControl.sleepForDark(context);
    }

    /**
     * ModDeviceSupport hook: restore screen after dark sleep.
     */
    public boolean wakeScreenFromDark(Context context) {
        if (!isSupported()) {
            return false;
        }
        return EchoShowScreenControl.wakeFromDark(context);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
