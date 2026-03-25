package com.ava.mods.qualcomm.audio;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * Qualcomm Audio Concurrency Fix
 *
 * When wake sound plays during VOICE_ASSISTANT_STT_START, the HAL driver blocks
 * microphone access on devices that disallow concurrent audio sessions.
 *
 * Errors observed:
 *   platform_stdev_check_and_update_concurrency: session_allowed 0
 *   ACDB-LOADER: Error: ACDB AFE returned = -19 (ENODEV)
 *   msm_gpioset_activate: gpio set name does not exist
 *
 * See https://github.com/knoop7/Ava/issues/56
 * Original solution by @pantherale0
 *
 * Supported: Qualcomm SoC devices (msm8953, msm8996, msm8998, sdm*, sm8*, etc.)
 * Requires: Root access
 */
public class AudioConcurrencyFix {
    private static final String TAG = "QualcommAudioFix";
    
    private static final String SOUND_TRIGGER_CONFIG = "/vendor/etc/sound_trigger_platform_info.xml";
    private static final String VENDOR_BUILD_PROP = "/vendor/build.prop";
    
    public static class FixResult {
        public boolean success;
        public String message;
        public boolean requiresReboot;
        
        public FixResult(boolean success, String message, boolean requiresReboot) {
            this.success = success;
            this.message = message;
            this.requiresReboot = requiresReboot;
        }
    }
    
    private static final String[] KNOWN_QCOM_PREFIXES = {
        "msm8909", "msm8916", "msm8937", "msm8940", "msm8952", "msm8953",
        "msm8956", "msm8976", "msm8996", "msm8998",
        "sdm429", "sdm439", "sdm450", "sdm630", "sdm636", "sdm660",
        "sdm670", "sdm710", "sdm845",
        "sm6115", "sm6125", "sm6150", "sm6225", "sm6250", "sm6350", "sm6375",
        "sm7125", "sm7150", "sm7225", "sm7250", "sm7325", "sm7450",
        "sm8150", "sm8250", "sm8350", "sm8450", "sm8475", "sm8550", "sm8650",
        "qcs403", "qcs605",
        "qcom"
    };

    private static final String[][] DEVICE_MIXER_OVERRIDES = {
        {"thinksmart", "SLIM_0_TX", "MI2S_TX", "SLIMBUS_0_TX", "TERT_MI2S_TX"},
    };

    public static boolean isCompatibleDevice() {
        String cards = getSoundCardInfo();
        if (cards == null || cards.isEmpty()) return false;
        String lower = cards.toLowerCase();
        for (String prefix : KNOWN_QCOM_PREFIXES) {
            if (lower.contains(prefix)) return true;
        }
        return false;
    }
    
    /**
     * Get current sound card info.
     */
    public static String getSoundCardInfo() {
        try {
            File cardsFile = new File("/proc/asound/cards");
            if (!cardsFile.exists()) {
                return "No sound cards found";
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(cardsFile));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString().trim();
        } catch (Exception e) {
            return "Error reading sound cards: " + e.getMessage();
        }
    }
    
    /**
     * Check if concurrency fix is already applied.
     */
    public static boolean isConcurrencyEnabled() {
        try {
            File configFile = new File(SOUND_TRIGGER_CONFIG);
            if (!configFile.exists()) {
                return false;
            }
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            boolean rxEnabled = false;
            boolean concurrentCapture = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("rx_concurrency_disabled=\"false\"")) {
                    rxEnabled = true;
                }
                if (line.contains("concurrent_capture=\"true\"")) {
                    concurrentCapture = true;
                }
            }
            reader.close();
            return rxEnabled && concurrentCapture;
        } catch (Exception e) {
            Log.w(TAG, "Failed to check concurrency status", e);
            return false;
        }
    }
    
    public static FixResult applyFix(Context context) {
        if (!isRootAvailable()) {
            return new FixResult(false, "Root access required", false);
        }
        if (!isCompatibleDevice()) {
            return new FixResult(false, "No compatible Qualcomm sound card", false);
        }
        if (!new File(SOUND_TRIGGER_CONFIG).exists()) {
            return new FixResult(false, SOUND_TRIGGER_CONFIG + " not found", false);
        }

        try {
            execRoot("mount -o rw,remount /vendor");

            String[] concurrencyFixes = {
                sedReplace("rx_concurrency_disabled", "true", "false", SOUND_TRIGGER_CONFIG),
                sedReplace("concurrent_capture", "false", "true", SOUND_TRIGGER_CONFIG),
                sedReplace("concurrent_voip_call", "false", "true", SOUND_TRIGGER_CONFIG),
                sedReplace("concurrent_voice_call", "false", "true", SOUND_TRIGGER_CONFIG),
            };
            for (String cmd : concurrencyFixes) execRoot(cmd);

            if (new File(VENDOR_BUILD_PROP).exists()) {
                String[] propFixes = {
                    "sed -i 's/vendor.voice.playback.conc.disabled=true/vendor.voice.playback.conc.disabled=false/g' " + VENDOR_BUILD_PROP,
                    "sed -i 's/vendor.voice.voip.conc.disabled=true/vendor.voice.voip.conc.disabled=false/g' " + VENDOR_BUILD_PROP,
                };
                for (String cmd : propFixes) execRoot(cmd);
            }

            applyDeviceMixerOverrides();

            execRoot("mount -o ro,remount /vendor");

            if (isConcurrencyEnabled()) {
                return new FixResult(true, "Applied. Reboot required.", true);
            }
            return new FixResult(false, "Commands ran but verification failed", true);
        } catch (Exception e) {
            Log.e(TAG, "applyFix failed", e);
            try { execRoot("mount -o ro,remount /vendor"); } catch (Exception ignored) {}
            return new FixResult(false, "Error: " + e.getMessage(), false);
        }
    }

    private static String sedReplace(String param, String from, String to, String file) {
        return "sed -i 's/<param " + param + "=\"" + from + "\" \\/>/<param " + param + "=\"" + to + "\" \\/>/g' " + file;
    }

    private static void applyDeviceMixerOverrides() throws Exception {
        String model = android.os.Build.MODEL.toLowerCase();
        String product = android.os.Build.PRODUCT.toLowerCase();
        String device = android.os.Build.DEVICE.toLowerCase();
        String id = model + " " + product + " " + device;

        for (String[] override : DEVICE_MIXER_OVERRIDES) {
            if (id.contains(override[0])) {
                for (int i = 1; i < override.length - 1; i += 2) {
                    execRoot("sed -i 's/" + override[i] + "/" + override[i + 1] + "/g' " + SOUND_TRIGGER_CONFIG);
                }
            }
        }
    }
    
    /**
     * Check if root access is available.
     */
    public static boolean isRootAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Execute a command as root.
     */
    private static String execRoot(String command) throws Exception {
        Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
        reader.close();
        
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        while ((line = errorReader.readLine()) != null) {
            Log.w(TAG, "stderr: " + line);
        }
        errorReader.close();
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            Log.w(TAG, "Command exited with code " + exitCode + ": " + command);
        }
        return output.toString();
    }
    
    public static String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Qualcomm Audio Concurrency Diagnostic ===\n");
        sb.append("Issue: https://github.com/knoop7/Ava/issues/56\n\n");
        sb.append("Device: ").append(android.os.Build.MODEL).append(" (").append(android.os.Build.DEVICE).append(")\n");
        sb.append("Product: ").append(android.os.Build.PRODUCT).append("\n\n");
        sb.append("Sound Cards:\n").append(getSoundCardInfo()).append("\n\n");
        sb.append("Compatible: ").append(isCompatibleDevice()).append("\n");
        sb.append("Root: ").append(isRootAvailable()).append("\n");
        sb.append("Concurrency: ").append(isConcurrencyEnabled()).append("\n\n");
        sb.append("Files:\n");
        sb.append("  ").append(SOUND_TRIGGER_CONFIG).append(": ").append(new File(SOUND_TRIGGER_CONFIG).exists()).append("\n");
        sb.append("  ").append(VENDOR_BUILD_PROP).append(": ").append(new File(VENDOR_BUILD_PROP).exists()).append("\n");
        return sb.toString();
    }
}
