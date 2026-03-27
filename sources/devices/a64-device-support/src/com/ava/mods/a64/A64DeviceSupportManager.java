package com.ava.mods.a64;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import org.json.JSONObject;

/**
 * A64 Device Support Manager
 * 
 * Provides device compatibility hooks for Allwinner A64 chip devices
 * (such as Ococci tablets, smart screens, etc.)
 * 
 * Features:
 * - A64 device detection (cpuinfo, model, board, hardware)
 * - GPIO AEC control for audio processing
 * - Physical key handling (volume, menu, brightness)
 * - Screen brightness control via root
 * - CPU core management for performance
 */
public class A64DeviceSupportManager {
    private static final String TAG = "A64DeviceSupport";
    
    private static final String OCOCCI_GPIO_KEY = "persist.sys.gpio116";
    private static final long LONG_PRESS_THRESHOLD = 2000L;
    private static final int MIN_BRIGHTNESS = 10;
    private static final int MAX_BRIGHTNESS = 255;
    
    private static volatile A64DeviceSupportManager instance;
    private final Context context;
    private final Handler handler;
    
    private Boolean cachedIsA64Device = null;
    private Boolean cachedIsRootAvailable = null;
    
    private long mainButtonDownTime = 0;
    private Runnable longPressRunnable = null;
    private boolean isScreenDimmed = false;
    private int savedBrightness = MAX_BRIGHTNESS;
    
    // Config keys
    private static final String CONFIG_HOME_SHORT = "home_short_press";
    private static final String CONFIG_HOME_LONG = "home_long_press";
    private static final String CONFIG_INDUCTOR = "inductor_sensor";
    
    // Action values
    private static final String ACTION_SCREEN_TOGGLE = "screen_toggle";
    private static final String ACTION_VOICE_WAKE = "voice_wake";
    private static final String ACTION_SERVICE_TOGGLE = "service_toggle";
    private static final String ACTION_NONE = "none";
    
    // Cached config
    private String homeShortAction = ACTION_SCREEN_TOGGLE;
    private String homeLongAction = ACTION_VOICE_WAKE;
    private String inductorAction = ACTION_SCREEN_TOGGLE;

    private A64DeviceSupportManager(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
    }

    public static A64DeviceSupportManager getInstance(Context context) {
        if (instance == null) {
            synchronized (A64DeviceSupportManager.class) {
                if (instance == null) {
                    instance = new A64DeviceSupportManager(context);
                }
            }
        }
        return instance;
    }

    // ==================== Config Loading ====================

    public void loadConfig(Context ctx) {
        if (!isA64Device()) return;
        
        try {
            File configFile = new File(ctx.getFilesDir(), "mod_configs/a64-device-support.json");
            if (!configFile.exists()) return;
            
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            JSONObject config = new JSONObject(sb.toString());
            homeShortAction = config.optString(CONFIG_HOME_SHORT, ACTION_SCREEN_TOGGLE);
            homeLongAction = config.optString(CONFIG_HOME_LONG, ACTION_VOICE_WAKE);
            inductorAction = config.optString(CONFIG_INDUCTOR, ACTION_SCREEN_TOGGLE);
        } catch (Exception e) {
            // Use defaults
        }
    }

    public String getConfigValue(String key) {
        switch (key) {
            case CONFIG_HOME_SHORT: return homeShortAction;
            case CONFIG_HOME_LONG: return homeLongAction;
            case CONFIG_INDUCTOR: return inductorAction;
            default: return "";
        }
    }

    // ==================== Device Detection ====================

    public boolean isSupported() {
        return isA64Device();
    }

    public boolean isSupported(Context context) {
        return isA64Device();
    }

    public boolean isA64Device() {
        if (cachedIsA64Device != null) {
            return cachedIsA64Device;
        }
        
        try {
            String cpuInfo = readCpuInfo();
            String cpuInfoLower = cpuInfo.toLowerCase();
            String modelLower = safeLower(Build.MODEL);
            String boardLower = safeLower(Build.BOARD);
            String hardwareLower = safeLower(Build.HARDWARE);
            
            cachedIsA64Device = cpuInfoLower.contains("a64") ||
                cpuInfoLower.contains("sun50i") ||
                cpuInfoLower.contains("allwinner") ||
                modelLower.contains("a64") ||
                modelLower.contains("ococci") ||
                boardLower.contains("a64") ||
                boardLower.contains("sun50i") ||
                hardwareLower.contains("a64") ||
                hardwareLower.contains("sun50i") ||
                isQuadCoreA64Device();
        } catch (Exception e) {
            cachedIsA64Device = false;
        }
        
        return cachedIsA64Device;
    }

    public boolean isQuadCoreA64Device() {
        String model = Build.MODEL;
        return model.toUpperCase().contains("QUAD-CORE A64") || 
               model.toLowerCase().contains("ococci");
    }

    private String readCpuInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            File file = new File("/proc/cpuinfo");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
            }
        } catch (Exception e) {
            // ignore
        }
        return sb.toString();
    }

    // ==================== Device Compatibility Hooks ====================

    public int getMinBrightness() {
        return MIN_BRIGHTNESS;
    }

    public int getMinBrightness(Context context) {
        return MIN_BRIGHTNESS;
    }

    public boolean isLowEndBleChip() {
        return true;
    }

    public boolean isLowEndBleChip(Context context) {
        return true;
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        if (!isSupported()) {
            return false;
        }
        
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean grantOverlayPermissionIfNeeded() {
        return grantOverlayPermissionIfNeeded(context);
    }

    // ==================== GPIO AEC Control ====================

    public boolean isGpioAecSupported() {
        return isQuadCoreA64Device();
    }

    public boolean activateAEC() {
        if (!isQuadCoreA64Device()) {
            return false;
        }
        
        if (isRootAvailable()) {
            return activateAecViaRoot();
        }
        return setGpioProperty(OCOCCI_GPIO_KEY, "1");
    }

    public boolean activateBeamforming() {
        if (!isQuadCoreA64Device()) {
            return false;
        }
        
        if (isRootAvailable()) {
            return deactivateAecViaRoot();
        }
        return setGpioProperty(OCOCCI_GPIO_KEY, "0");
    }

    public boolean isAecActive() {
        String value = getSystemProperty(OCOCCI_GPIO_KEY, "0");
        return "1".equals(value);
    }

    private boolean activateAecViaRoot() {
        try {
            Runtime.getRuntime().exec("su -c echo 1 > /sys/class/gpio/gpio116/value").waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deactivateAecViaRoot() {
        try {
            Runtime.getRuntime().exec("su -c echo 0 > /sys/class/gpio/gpio116/value").waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Key Handling ====================

    public boolean onKeyDown(Context ctx, int keyCode, android.view.KeyEvent event) {
        if (!isA64Device()) {
            return false;
        }
        
        loadConfig(ctx);
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                invokeAvaVolumeControl(ctx, true);
                return true;
                
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                invokeAvaVolumeControl(ctx, false);
                return true;
                
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MENU:
                mainButtonDownTime = System.currentTimeMillis();
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                }
                final Context finalCtx = ctx;
                longPressRunnable = new Runnable() {
                    @Override
                    public void run() {
                        executeAction(finalCtx, homeLongAction);
                    }
                };
                handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD);
                return true;
                
            default:
                return false;
        }
    }

    public boolean onKeyUp(Context ctx, int keyCode, android.view.KeyEvent event) {
        if (!isA64Device()) {
            return false;
        }
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return true;
                
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MENU:
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
                long pressDuration = System.currentTimeMillis() - mainButtonDownTime;
                mainButtonDownTime = 0;
                if (pressDuration < LONG_PRESS_THRESHOLD) {
                    executeAction(ctx, homeShortAction);
                }
                return true;
                
            default:
                return false;
        }
    }

    public void cleanup() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
        mainButtonDownTime = 0;
    }

    private void executeAction(Context ctx, String action) {
        android.util.Log.d(TAG, "executeAction: " + action);
        if (action == null || ACTION_NONE.equals(action)) {
            return;
        }
        
        switch (action) {
            case ACTION_SCREEN_TOGGLE:
                android.util.Log.d(TAG, "toggleBrightness called, isScreenDimmed=" + isScreenDimmed);
                toggleBrightness();
                break;
            case ACTION_VOICE_WAKE:
                invokeAvaVoiceWake(ctx);
                break;
            case ACTION_SERVICE_TOGGLE:
                invokeAvaServiceToggle(ctx);
                break;
        }
    }

    private void invokeAvaVoiceWake(Context ctx) {
        try {
            Class<?> serviceClass = Class.forName("com.example.ava.services.VoiceSatelliteService");
            java.lang.reflect.Method getInstanceMethod = serviceClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            if (instance != null) {
                java.lang.reflect.Method wakeMethod = serviceClass.getMethod("triggerWakeWord");
                wakeMethod.invoke(instance);
            }
        } catch (Exception e) {
            // Fallback: send broadcast
            try {
                android.content.Intent intent = new android.content.Intent("com.example.ava.VOICE_WAKE");
                ctx.sendBroadcast(intent);
            } catch (Exception ignored) {}
        }
    }

    private void invokeAvaServiceToggle(Context ctx) {
        try {
            Class<?> serviceClass = Class.forName("com.example.ava.services.VoiceSatelliteService");
            java.lang.reflect.Method getInstanceMethod = serviceClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            if (instance != null) {
                java.lang.reflect.Method stopMethod = serviceClass.getMethod("stopVoiceSatellite");
                stopMethod.invoke(instance);
            } else {
                android.content.Intent intent = new android.content.Intent(ctx, serviceClass);
                ctx.startService(intent);
            }
        } catch (Exception ignored) {}
    }

    // ==================== Screen Control ====================

    public boolean setScreenState(boolean screenOn) {
        if (!isA64Device() || !isRootAvailable()) {
            return false;
        }
        
        try {
            if (screenOn) {
                int targetBrightness = savedBrightness > 0 ? savedBrightness : 128;
                return setSystemBrightness(targetBrightness);
            } else {
                int currentBrightness = getSystemBrightness();
                if (currentBrightness > 0) {
                    savedBrightness = currentBrightness;
                }
                return setSystemBrightness(MIN_BRIGHTNESS);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void toggleBrightness() {
        try {
            int currentBrightness = getSystemBrightness();
            android.util.Log.d(TAG, "toggleBrightness: current=" + currentBrightness + ", saved=" + savedBrightness + ", dimmed=" + isScreenDimmed);
            
            // 根据当前亮度判断状态，而不是依赖 isScreenDimmed 变量
            if (currentBrightness <= MIN_BRIGHTNESS) {
                // 当前是暗屏，需要亮屏
                int targetBrightness = savedBrightness > MIN_BRIGHTNESS ? savedBrightness : 128;
                setSystemBrightness(targetBrightness);
                isScreenDimmed = false;
                android.util.Log.d(TAG, "Screen ON: brightness=" + targetBrightness);
            } else {
                // 当前是亮屏，需要暗屏
                savedBrightness = currentBrightness;
                setSystemBrightness(MIN_BRIGHTNESS);
                isScreenDimmed = true;
                android.util.Log.d(TAG, "Screen OFF: saved=" + savedBrightness);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "toggleBrightness error", e);
        }
    }

    private int getSystemBrightness() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("settings get system screen_brightness\n");
            os.writeBytes("exit\n");
            os.flush();
            
            BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            os.close();
            process.waitFor();
            
            if (line != null && !line.isEmpty()) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "getSystemBrightness error", e);
        }
        return -1;
    }

    private boolean setSystemBrightness(int brightness) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("settings put system screen_brightness " + brightness + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            int result = process.waitFor();
            android.util.Log.d(TAG, "setSystemBrightness " + brightness + " result=" + result);
            return result == 0;
        } catch (Exception e) {
            android.util.Log.e(TAG, "setSystemBrightness error", e);
            return false;
        }
    }

    // ==================== CPU Core Management ====================

    public boolean enableAllCpuCores() {
        if (!isQuadCoreA64Device() || !isRootAvailable()) {
            return false;
        }
        
        try {
            String[] commands = {
                "echo 0 > /sys/kernel/autohotplug/enable",
                "echo 1 > /sys/devices/system/cpu/cpu1/online",
                "echo 1 > /sys/devices/system/cpu/cpu2/online",
                "echo 1 > /sys/devices/system/cpu/cpu3/online"
            };
            
            for (String cmd : commands) {
                Runtime.getRuntime().exec("su -c " + cmd).waitFor();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Root Utilities ====================

    public boolean isRootAvailable() {
        if (cachedIsRootAvailable != null) {
            return cachedIsRootAvailable;
        }
        
        try {
            Process process = Runtime.getRuntime().exec("su -c ls");
            cachedIsRootAvailable = process.waitFor() == 0;
        } catch (Exception e) {
            cachedIsRootAvailable = false;
        }
        
        return cachedIsRootAvailable;
    }

    // ==================== System Property Utilities ====================

    private String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean setGpioProperty(String key, String value) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = systemProperties.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    // ==================== Volume Control ====================

    public void volumeUp() {
        if (!isA64Device()) return;
        adjustVolume(true);
    }

    public void volumeDown() {
        if (!isA64Device()) return;
        adjustVolume(false);
    }

    private void adjustVolume(boolean isUp) {
        try {
            android.media.AudioManager audioManager = (android.media.AudioManager) 
                context.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager == null) return;
            
            int maxVolume = 15;
            int currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int newVolume;
            if (isUp) {
                newVolume = Math.min(currentVolume + 2, maxVolume);
            } else {
                newVolume = Math.max(currentVolume - 2, 1);
            }
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVolume, 0);
        } catch (Exception e) {
            // ignore
        }
    }

    // ==================== Bluetooth Hook Support ====================

    public boolean isBluetoothHookSupported() {
        return isA64Device();
    }

    public boolean isBluetoothHookSupported(Context context) {
        return isA64Device();
    }

    public String getChipVendor() {
        return "Allwinner";
    }

    public String getChipVendor(Context context) {
        return "Allwinner";
    }

    public String getChipModel() {
        return "A64";
    }

    public String getChipModel(Context context) {
        return "A64";
    }

    public int getMaxRealConnections() {
        return 3;
    }

    public int getMaxRealConnections(Context context) {
        return 3;
    }

    public java.util.Set<String> getScannerQuirks() {
        java.util.Set<String> quirks = new java.util.HashSet<>();
        quirks.add("PASSIVE_SCAN_BROKEN");
        quirks.add("BATCH_SCAN_BROKEN");
        quirks.add("NEEDS_LOCATION_ENABLED");
        return quirks;
    }

    public java.util.Set<String> getScannerQuirks(Context context) {
        return getScannerQuirks();
    }

    // ==================== Ava VolumeControlService Bridge ====================

    private void invokeAvaVolumeControl(Context ctx, boolean isUp) {
        try {
            Class<?> volumeServiceClass = Class.forName("com.example.ava.services.VolumeControlService");
            Class<?> companionClass = Class.forName("com.example.ava.services.VolumeControlService$Companion");
            
            java.lang.reflect.Field companionField = volumeServiceClass.getDeclaredField("Companion");
            Object companion = companionField.get(null);
            
            String methodName = isUp ? "volumeUp" : "volumeDown";
            java.lang.reflect.Method method = companionClass.getMethod(methodName, Context.class);
            method.invoke(companion, ctx);
        } catch (Exception e) {
            // Fallback to direct audio control
            adjustVolume(isUp);
        }
    }
}
