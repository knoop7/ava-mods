package com.ava.mods.echoshow;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Repairs the missing BLE feature declaration on affected Echo Show LineageOS builds. */
final class EchoShowBleFeatureRepair {
    private static final String TAG = "EchoShowBleRepair";
    private static final String BLE_FEATURE = "android.hardware.bluetooth_le";
    private static final String TARGET_XML =
            "/vendor/etc/permissions/android.hardware.bluetooth_le.xml";
    private static final long ROOT_COMMAND_TIMEOUT_SECONDS = 30L;

    private static final String INSTALL_SCRIPT =
            "TARGET='" + TARGET_XML + "'\n" +
            "TMP='/data/local/tmp/ava_bluetooth_le.xml.'$$\n" +
            "STAGED=\"${TARGET}.ava-new.$$\"\n" +
            "MOUNT_POINT='/'\n" +
            "WAS_RO=0\n" +
            "REMOUNTED=0\n" +
            "cleanup() {\n" +
            "  code=$?\n" +
            "  trap - EXIT HUP INT TERM\n" +
            "  rm -f \"$TMP\" \"$STAGED\"\n" +
            "  if [ \"$REMOUNTED\" = 1 ] && [ \"$WAS_RO\" = 1 ]; then\n" +
            "    mount -o remount,ro \"$MOUNT_POINT\" >/dev/null 2>&1 || code=29\n" +
            "  fi\n" +
            "  exit \"$code\"\n" +
            "}\n" +
            "trap cleanup EXIT HUP INT TERM\n" +
            "if [ -e \"$TARGET\" ]; then echo 'AVA_BLE_ALREADY_EXISTS'; exit 10; fi\n" +
            "if awk '$2 == \"/vendor\" { found=1 } END { exit !found }' /proc/mounts; then\n" +
            "  MOUNT_POINT='/vendor'\n" +
            "fi\n" +
            "if awk -v mp=\"$MOUNT_POINT\" '$2 == mp && (\",\" $4 \",\") ~ /,ro,/ { found=1 } END { exit !found }' /proc/mounts; then\n" +
            "  WAS_RO=1\n" +
            "fi\n" +
            "cat > \"$TMP\" <<'AVA_BLE_XML'\n" +
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<permissions>\n" +
            "    <feature name=\"android.hardware.bluetooth_le\" />\n" +
            "</permissions>\n" +
            "AVA_BLE_XML\n" +
            "chown root:root \"$TMP\" || exit 20\n" +
            "chmod 0644 \"$TMP\" || exit 20\n" +
            "if [ \"$WAS_RO\" = 1 ]; then\n" +
            "  mount -o remount,rw \"$MOUNT_POINT\" || exit 21\n" +
            "  REMOUNTED=1\n" +
            "fi\n" +
            "if [ -e \"$TARGET\" ]; then echo 'AVA_BLE_ALREADY_EXISTS'; exit 10; fi\n" +
            "cp \"$TMP\" \"$STAGED\" || exit 22\n" +
            "chown root:root \"$STAGED\" || exit 23\n" +
            "chmod 0644 \"$STAGED\" || exit 23\n" +
            "if command -v restorecon >/dev/null 2>&1; then\n" +
            "  restorecon \"$STAGED\" >/dev/null 2>&1 || true\n" +
            "fi\n" +
            "chcon u:object_r:vendor_configs_file:s0 \"$STAGED\" || exit 24\n" +
            "if [ -e \"$TARGET\" ]; then echo 'AVA_BLE_ALREADY_EXISTS'; exit 10; fi\n" +
            "mv \"$STAGED\" \"$TARGET\" || exit 25\n" +
            "grep -Fq '<feature name=\"android.hardware.bluetooth_le\" />' \"$TARGET\" || exit 26\n" +
            "[ \"$(stat -c %u:%g \"$TARGET\")\" = '0:0' ] || exit 27\n" +
            "[ \"$(stat -c %a \"$TARGET\")\" = '644' ] || exit 27\n" +
            "if [ \"$(getenforce 2>/dev/null)\" = 'Enforcing' ]; then\n" +
            "  ls -Zd \"$TARGET\" | grep -q 'vendor_configs_file' || exit 28\n" +
            "fi\n" +
            "sync \"$TARGET\" >/dev/null 2>&1 || sync\n" +
            "if [ \"$REMOUNTED\" = 1 ] && [ \"$WAS_RO\" = 1 ]; then\n" +
            "  mount -o remount,ro \"$MOUNT_POINT\" || exit 29\n" +
            "  REMOUNTED=0\n" +
            "fi\n" +
            "rm -f \"$TMP\"\n" +
            "trap - EXIT HUP INT TERM\n" +
            "echo 'AVA_BLE_INSTALLED'\n";

    enum Outcome {
        NOT_APPLICABLE("Not applicable"),
        FEATURE_AVAILABLE("BLE feature available"),
        XML_PRESENT_RESTART_REQUIRED("BLE feature XML present; restart required"),
        INSTALLED_RESTART_REQUIRED("BLE feature repair installed; restart required"),
        ROOT_REQUIRED("BLE feature XML missing; root required"),
        EXISTING_XML_INVALID("Existing BLE feature XML is invalid; not modified"),
        INSTALL_FAILED("BLE feature repair failed; inspect logs before retry");

        final String displayText;

        Outcome(String displayText) {
            this.displayText = displayText;
        }
    }

    private enum XmlState {
        MISSING,
        VALID,
        INVALID
    }

    private static volatile Outcome lastOutcome = Outcome.NOT_APPLICABLE;
    private static final AtomicBoolean autoRepairStarted = new AtomicBoolean(false);

    private EchoShowBleFeatureRepair() {
    }

    static void scheduleAutoRepair(Context context) {
        if (!isSupportedEchoShow() || !autoRepairStarted.compareAndSet(false, true)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Log.i(TAG, "Scheduling manager-load BLE feature check");
        Thread worker = new Thread(
                () -> runAutoRepair(appContext),
                "EchoShowBleFeatureRepair"
        );
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized Outcome inspectAndRepair(Context context) {
        Outcome inspected = inspect(context);
        if (inspected != Outcome.ROOT_REQUIRED) {
            lastOutcome = inspected;
            Log.i(TAG, "BLE feature check: " + inspected.displayText);
            return inspected;
        }
        if (!EchoShowPrivilegedShell.isRootAvailable()) {
            lastOutcome = Outcome.ROOT_REQUIRED;
            Log.w(TAG, lastOutcome.displayText);
            return lastOutcome;
        }

        RootCommandResult command = runRootCommand(INSTALL_SCRIPT);
        if (command.exitCode == 10 && command.output.contains("AVA_BLE_ALREADY_EXISTS")) {
            lastOutcome = inspect(context);
        } else if (command.exitCode == 0 && command.output.contains("AVA_BLE_INSTALLED")) {
            lastOutcome = inspectXml() == XmlState.VALID
                    ? Outcome.INSTALLED_RESTART_REQUIRED
                    : Outcome.INSTALL_FAILED;
        } else {
            lastOutcome = Outcome.INSTALL_FAILED;
            Log.e(TAG, "BLE feature repair failed with exit " + command.exitCode
                    + ": " + command.output);
        }
        Log.i(TAG, lastOutcome.displayText);
        return lastOutcome;
    }

    private static void runAutoRepair(Context context) {
        try {
            inspectAndRepair(context);
        } catch (RuntimeException e) {
            lastOutcome = Outcome.INSTALL_FAILED;
            Log.e(TAG, "Automatic BLE feature check failed", e);
        }
    }

    static Outcome inspect(Context context) {
        if (!isSupportedEchoShow()) {
            return Outcome.NOT_APPLICABLE;
        }
        PackageManager packageManager = context.getPackageManager();
        if (packageManager.hasSystemFeature(BLE_FEATURE)) {
            return Outcome.FEATURE_AVAILABLE;
        }
        XmlState xmlState = inspectXml();
        if (xmlState == XmlState.VALID) {
            return Outcome.XML_PRESENT_RESTART_REQUIRED;
        }
        if (xmlState == XmlState.INVALID) {
            return Outcome.EXISTING_XML_INVALID;
        }
        return Outcome.ROOT_REQUIRED;
    }

    static String getStatus(Context context) {
        Outcome inspected = inspect(context);
        if (lastOutcome == Outcome.INSTALLED_RESTART_REQUIRED
                && inspected == Outcome.XML_PRESENT_RESTART_REQUIRED) {
            return lastOutcome.displayText;
        }
        if (lastOutcome == Outcome.INSTALL_FAILED && inspected == Outcome.ROOT_REQUIRED) {
            return lastOutcome.displayText;
        }
        lastOutcome = inspected;
        return inspected.displayText;
    }

    private static XmlState inspectXml() {
        File file = new File(TARGET_XML);
        if (!file.exists()) {
            return XmlState.MISSING;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "utf-8");
            boolean permissionsRootSeen = false;
            boolean bleFeatureSeen = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                if (parser.getDepth() == 1) {
                    if (!"permissions".equals(parser.getName())) {
                        return XmlState.INVALID;
                    }
                    permissionsRootSeen = true;
                } else if (permissionsRootSeen
                        && parser.getDepth() == 2
                        && "feature".equals(parser.getName())
                        && BLE_FEATURE.equals(parser.getAttributeValue(null, "name"))) {
                    bleFeatureSeen = true;
                }
            }
            return permissionsRootSeen && bleFeatureSeen ? XmlState.VALID : XmlState.INVALID;
        } catch (Exception e) {
            Log.w(TAG, "Existing BLE feature XML could not be validated", e);
            return XmlState.INVALID;
        }
    }

    private static boolean isSupportedEchoShow() {
        return containsSupportedCodename(Build.MODEL)
                || containsSupportedCodename(Build.BOARD)
                || containsSupportedCodename(Build.DEVICE)
                || containsSupportedCodename(Build.PRODUCT);
    }

    private static boolean containsSupportedCodename(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("crown")
                || normalized.contains("checkers")
                || normalized.contains("cronos");
    }

    private static RootCommandResult runRootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new RootCommandResult(-2, "root command timed out");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            return new RootCommandResult(process.exitValue(), output.toString().trim());
        } catch (Exception e) {
            return new RootCommandResult(-1, e.getMessage() == null ? "root execution failed" : e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static final class RootCommandResult {
        final int exitCode;
        final String output;

        RootCommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
