package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code ble_adv_hci} in the Ava app process first (correct MGMT identity), then
 * {@code run-as}, then Shizuku/root shell fallbacks.
 */
final class BleAdvPrivilegedShell {
    private static final String TAG = "BleAdvPrivShell";
    private static final Pattern OK_CTRL_INST =
            Pattern.compile("OK mgmt ctrl=(\\d+) inst=(\\d+)");

    private final Context context;
    private final BleAdvPermissionHelper permissionHelper;
    private volatile int cachedCtrl = -1;
    private volatile int cachedInst = -1;

    BleAdvPrivilegedShell(Context context, BleAdvPermissionHelper permissionHelper) {
        this.context = context.getApplicationContext();
        this.permissionHelper = permissionHelper;
    }

    boolean canRunHelper() {
        return true;
    }

    String getPrivilegedShellLabel() {
        return permissionHelper.getPrivilegedShellLabel();
    }

    void ensurePrivilegedAccess() {
        permissionHelper.ensurePrivilegedAccess();
    }

    /** Run helper with argv; prefers in-process exec over Shizuku shell (uid 2000). */
    ExecResult execHelper(String helperPath, String[] args) {
        if (helperPath == null || args == null || args.length == 0) {
            return new ExecResult(-1, "");
        }

        ExecResult direct = execHelperDirect(helperPath, args);
        if (direct.exitCode == 0) {
            noteCacheFromOutput(direct.output);
            return direct;
        }

        ExecResult runAs = execHelperRunAs(helperPath, args);
        if (runAs != null && runAs.exitCode == 0) {
            noteCacheFromOutput(runAs.output);
            return runAs;
        }

        permissionHelper.ensurePrivilegedAccess();
        String shellCmd = buildShellCommand(helperPath, args);
        ExecResult shizuku = execViaProcess(permissionHelper.newShizukuProcess(
                new String[]{"sh", "-c", shellCmd}));
        if (shizuku != null) {
            if (shizuku.exitCode == 0) {
                noteCacheFromOutput(shizuku.output);
            }
            return shizuku;
        }

        ExecResult root = execViaProcess(permissionHelper.newRootProcess(shellCmd));
        if (root != null && root.exitCode == 0) {
            noteCacheFromOutput(root.output);
        }
        return root != null ? root : direct;
    }

    int getCachedCtrl() {
        return cachedCtrl;
    }

    int getCachedInst() {
        return cachedInst;
    }

    private ExecResult execHelperDirect(String helperPath, String[] args) {
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(helperPath);
            for (String arg : args) {
                cmd.add(arg);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            applyHintEnv(pb.environment());
            pb.redirectErrorStream(true);
            process = pb.start();
            ExecResult result = readProcess(process, "app");
            Log.d(TAG, "helper app-process exit=" + result.exitCode);
            return result;
        } catch (Exception e) {
            Log.d(TAG, "helper app-process unavailable: " + e.getMessage());
            return new ExecResult(-1, "app_exec_failed:" + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private ExecResult execHelperRunAs(String helperPath, String[] args) {
        String pkg = context.getPackageName();
        StringBuilder sb = new StringBuilder();
        sb.append("run-as ").append(pkg).append(' ');
        sb.append(helperPath);
        for (String arg : args) {
            sb.append(' ').append(shellQuote(arg));
        }
        ExecResult result = execViaProcess(permissionHelper.newShizukuProcess(
                new String[]{"sh", "-c", sb.toString()}));
        if (result != null) {
            Log.d(TAG, "helper run-as exit=" + result.exitCode);
        }
        return result;
    }

    private String buildShellCommand(String helperPath, String[] args) {
        String tmp = "/data/local/tmp/ava_ble_adv_hci";
        StringBuilder sb = new StringBuilder();
        sb.append("cp -f ").append(shellQuote(helperPath)).append(' ')
                .append(shellQuote(tmp)).append(" && chmod 755 ")
                .append(shellQuote(tmp)).append(" && ");
        if (cachedCtrl >= 0) {
            sb.append("BLE_ADV_HINT_CTRL=").append(cachedCtrl).append(' ');
        }
        if (cachedInst >= 0) {
            sb.append("BLE_ADV_HINT_INST=").append(cachedInst).append(' ');
        }
        sb.append(shellQuote(tmp));
        for (String arg : args) {
            sb.append(' ').append(shellQuote(arg));
        }
        return sb.toString();
    }

    private void applyHintEnv(Map<String, String> env) {
        if (cachedCtrl >= 0) {
            env.put("BLE_ADV_HINT_CTRL", String.valueOf(cachedCtrl));
        }
        if (cachedInst >= 0) {
            env.put("BLE_ADV_HINT_INST", String.valueOf(cachedInst));
        }
    }

    private void noteCacheFromOutput(String output) {
        if (output == null) {
            return;
        }
        Matcher m = OK_CTRL_INST.matcher(output);
        if (m.find()) {
            try {
                cachedCtrl = Integer.parseInt(m.group(1));
                cachedInst = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static String shellQuote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private ExecResult execViaProcess(Process process) {
        if (process == null) {
            return null;
        }
        try {
            return readProcess(process, "shell");
        } catch (Exception e) {
            Log.w(TAG, "privileged exec failed: " + e.getMessage());
            return new ExecResult(-1, e.getMessage() != null ? e.getMessage() : "exec_failed");
        } finally {
            process.destroy();
        }
    }

    private static ExecResult readProcess(Process process, String via) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        int code = process.waitFor();
        return new ExecResult(code, sb.toString());
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
