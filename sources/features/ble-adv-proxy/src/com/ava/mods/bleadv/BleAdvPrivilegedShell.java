package com.ava.mods.bleadv;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Privileged shell for raw HCI/MGMT — {@code Shizuku.newProcess} first, {@code su} fallback.
 */
final class BleAdvPrivilegedShell {
    private static final String TAG = "BleAdvPrivShell";

    private final BleAdvPermissionHelper permissionHelper;

    BleAdvPrivilegedShell(BleAdvPermissionHelper permissionHelper) {
        this.permissionHelper = permissionHelper;
    }

    boolean isPrivilegedAvailable() {
        return permissionHelper.isPrivilegedAvailable();
    }

    String getPrivilegedShellLabel() {
        return permissionHelper.getPrivilegedShellLabel();
    }

    void ensurePrivilegedAccess() {
        permissionHelper.ensurePrivilegedAccess();
    }

    ExecResult execCapture(String command) {
        if (command == null || command.isEmpty()) {
            return new ExecResult(-1, "");
        }
        permissionHelper.ensurePrivilegedAccess();
        ExecResult shizuku = execViaProcess(permissionHelper.newShizukuProcess(
                new String[]{"sh", "-c", command}));
        if (shizuku != null) {
            return shizuku;
        }
        return execViaProcess(permissionHelper.newRootProcess(command));
    }

    private ExecResult execViaProcess(Process process) {
        if (process == null) {
            return null;
        }
        try {
            String out = readStreams(process);
            int code = process.waitFor();
            Log.d(TAG, "privileged shell exit=" + code);
            return new ExecResult(code, out);
        } catch (Exception e) {
            Log.w(TAG, "privileged exec failed: " + e.getMessage());
            return new ExecResult(-1, e.getMessage() != null ? e.getMessage() : "exec_failed");
        } finally {
            process.destroy();
        }
    }

    private static String readStreams(Process process) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = out.readLine()) != null) {
                sb.append(line).append('\n');
            }
            while ((line = err.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    static final class ExecResult {
        final int exitCode;
        final String output;

        ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output != null ? output : "";
        }
    }
}
