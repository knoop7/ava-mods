package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Privileged shell for Phicomm R1 on Android 5 — root ({@code su}) only.
 * Does not call ShizukuUtils or request permissions via Ava ModPermissionCoordinator.
 * {@link com.example.ava.utils.RootUtils} is read-only probe when already root on device.
 */
final class PhicommPrivilegedShell {
    private static final String TAG = "PhicommPrivileged";

    private static final int ROOT_UNKNOWN = 0;
    private static final int ROOT_AVAILABLE = 1;
    private static final int ROOT_UNAVAILABLE = 2;

    private static volatile int rootState = ROOT_UNKNOWN;

    private final Context appContext;

    PhicommPrivilegedShell(Context context) {
        appContext = context.getApplicationContext();
    }

    boolean isRootAvailable() {
        if (rootState == ROOT_AVAILABLE) {
            return true;
        }
        if (rootState == ROOT_UNAVAILABLE) {
            return false;
        }
        if (probeHostRootUtils()) {
            rootState = ROOT_AVAILABLE;
            return true;
        }
        if (probeSuId()) {
            rootState = ROOT_AVAILABLE;
            return true;
        }
        rootState = ROOT_UNAVAILABLE;
        Log.i(TAG, "root unavailable — using non-root fallbacks");
        return false;
    }

    /** Runs {@code su -c command}; returns stdout or null. */
    String captureOutput(String command) {
        if (command == null || command.trim().isEmpty() || !isRootAvailable()) {
            return null;
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"su", "-c", command});
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            process.waitFor();
            return out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "captureOutput failed: " + t.getMessage());
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    int exec(String command) {
        if (command == null || command.trim().isEmpty() || !isRootAvailable()) {
            return -1;
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"su", "-c", command});
            return process.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "exec failed: " + t.getMessage());
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private boolean probeHostRootUtils() {
        try {
            Class<?> rootUtils = Class.forName(
                "com.example.ava.utils.RootUtils",
                false,
                appContext.getClassLoader()
            );
            Object instance = rootUtils.getField("INSTANCE").get(null);
            Boolean available = (Boolean) rootUtils
                .getMethod("isRootAvailable")
                .invoke(instance);
            if (Boolean.TRUE.equals(available)) {
                Log.i(TAG, "root via Ava RootUtils");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean probeSuId() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String line = reader.readLine();
                if (line != null && line.contains("uid=0")) {
                    Log.i(TAG, "root via su id");
                    return true;
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            Log.d(TAG, "su probe failed: " + t.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }
}
