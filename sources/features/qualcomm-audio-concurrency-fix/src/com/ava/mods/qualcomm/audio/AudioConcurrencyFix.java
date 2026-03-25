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
 * Fixes audio concurrency issues on Qualcomm devices where certain platforms/ROMs
 * do not allow concurrent audio sessions. This prevents the voice assistant from
 * hearing input when wake sounds are played.
 * 
 * Supported chipsets: msm8953-snd-card-mtp and similar Qualcomm audio cards
 * 
 * Original solution by @pantherale0
 * https://github.com/AvaAI/Ava/issues/XXX
 * 
 * Required: Root access
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
    
    /**
     * Check if device has a compatible Qualcomm sound card.
     */
    public static boolean isCompatibleDevice() {
        try {
            File cardsFile = new File("/proc/asound/cards");
            if (!cardsFile.exists()) {
                return false;
            }
            BufferedReader reader = new BufferedReader(new FileReader(cardsFile));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("msm8953") || line.contains("msm8996") || 
                    line.contains("msm8998") || line.contains("sdm") ||
                    line.contains("sm8") || line.contains("qcom")) {
                    reader.close();
                    return true;
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to check sound card compatibility", e);
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
    
    /**
     * Apply the audio concurrency fix.
     * Requires root access.
     */
    public static FixResult applyFix(Context context) {
        if (!isRootAvailable()) {
            return new FixResult(false, "Root access required", false);
        }
        
        if (!isCompatibleDevice()) {
            return new FixResult(false, "Device not compatible (no Qualcomm sound card detected)", false);
        }
        
        try {
            // Remount vendor as read-write
            execRoot("mount -o rw,remount /vendor");
            
            // Update sound trigger platform config
            String[] soundTriggerFixes = {
                "sed -i 's/<param rx_concurrency_disabled=\"true\" \\/>/<param rx_concurrency_disabled=\"false\" \\/>/g' " + SOUND_TRIGGER_CONFIG,
                "sed -i 's/<param concurrent_capture=\"false\" \\/>/<param concurrent_capture=\"true\" \\/>/g' " + SOUND_TRIGGER_CONFIG,
                "sed -i 's/<param concurrent_voip_call=\"false\" \\/>/<param concurrent_voip_call=\"true\" \\/>/g' " + SOUND_TRIGGER_CONFIG,
                "sed -i 's/<param concurrent_voice_call=\"false\" \\/>/<param concurrent_voice_call=\"true\" \\/>/g' " + SOUND_TRIGGER_CONFIG
            };
            
            for (String cmd : soundTriggerFixes) {
                execRoot(cmd);
            }
            
            // Update build properties
            String[] buildPropFixes = {
                "sed -i 's/vendor.voice.playback.conc.disabled=true/vendor.voice.playback.conc.disabled=false/g' " + VENDOR_BUILD_PROP,
                "sed -i 's/vendor.voice.voip.conc.disabled=true/vendor.voice.voip.conc.disabled=false/g' " + VENDOR_BUILD_PROP
            };
            
            for (String cmd : buildPropFixes) {
                execRoot(cmd);
            }
            
            // Update mixer paths (for ThinkSmart View and similar devices)
            String[] mixerFixes = {
                "sed -i 's/SLIM_0_TX/MI2S_TX/g' " + SOUND_TRIGGER_CONFIG,
                "sed -i 's/SLIMBUS_0_TX/TERT_MI2S_TX/g' " + SOUND_TRIGGER_CONFIG
            };
            
            for (String cmd : mixerFixes) {
                execRoot(cmd);
            }
            
            // Remount vendor as read-only
            execRoot("mount -o ro,remount /vendor");
            
            // Verify fix was applied
            if (isConcurrencyEnabled()) {
                return new FixResult(true, "Audio concurrency fix applied successfully. Reboot required.", true);
            } else {
                return new FixResult(false, "Fix commands executed but verification failed", true);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply audio concurrency fix", e);
            return new FixResult(false, "Error: " + e.getMessage(), false);
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
    
    /**
     * Get diagnostic info for troubleshooting.
     */
    public static String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Qualcomm Audio Concurrency Diagnostic ===\n\n");
        
        sb.append("Sound Cards:\n");
        sb.append(getSoundCardInfo()).append("\n\n");
        
        sb.append("Compatible Device: ").append(isCompatibleDevice()).append("\n");
        sb.append("Root Available: ").append(isRootAvailable()).append("\n");
        sb.append("Concurrency Enabled: ").append(isConcurrencyEnabled()).append("\n\n");
        
        // Check config file existence
        sb.append("Config Files:\n");
        sb.append("  sound_trigger_platform_info.xml: ").append(new File(SOUND_TRIGGER_CONFIG).exists()).append("\n");
        sb.append("  vendor/build.prop: ").append(new File(VENDOR_BUILD_PROP).exists()).append("\n");
        
        return sb.toString();
    }
}
