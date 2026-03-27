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
    private long menuButtonDownTime = 0;
    private Runnable longPressRunnable = null;
    private Runnable menuLongPressRunnable = null;
    private boolean isScreenDimmed = false;
    private int savedBrightness = MAX_BRIGHTNESS;

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

    public boolean onKeyDown(Context ctx, int keyCode, Object event) {
        if (!isA64Device()) {
            return false;
        }
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                VolumeControlService.volumeUp(ctx);
                return true;
                
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                VolumeControlService.volumeDown(ctx);
                return true;
                
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                mainButtonDownTime = System.currentTimeMillis();
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                }
                longPressRunnable = new Runnable() {
                    @Override
                    public void run() {
                        toggleBrightness();
                    }
                };
                handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD);
                return true;
                
            case KeyEvent.KEYCODE_MENU:
                menuButtonDownTime = System.currentTimeMillis();
                if (menuLongPressRunnable != null) {
                    handler.removeCallbacks(menuLongPressRunnable);
                }
                menuLongPressRunnable = new Runnable() {
                    @Override
                    public void run() {
                        toggleBrightness();
                    }
                };
                handler.postDelayed(menuLongPressRunnable, LONG_PRESS_THRESHOLD);
                return true;
                
            default:
                return false;
        }
    }

    public boolean onKeyUp(Context ctx, int keyCode, Object event) {
        if (!isA64Device()) {
            return false;
        }
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return true;
                
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
                long pressDuration = System.currentTimeMillis() - mainButtonDownTime;
                mainButtonDownTime = 0;
                return pressDuration < LONG_PRESS_THRESHOLD;
                
            case KeyEvent.KEYCODE_MENU:
                if (menuLongPressRunnable != null) {
                    handler.removeCallbacks(menuLongPressRunnable);
                    menuLongPressRunnable = null;
                }
                long menuPressDuration = System.currentTimeMillis() - menuButtonDownTime;
                menuButtonDownTime = 0;
                return menuPressDuration < LONG_PRESS_THRESHOLD;
                
            default:
                return false;
        }
    }

    public void cleanup() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
        if (menuLongPressRunnable != null) {
            handler.removeCallbacks(menuLongPressRunnable);
            menuLongPressRunnable = null;
        }
        mainButtonDownTime = 0;
        menuButtonDownTime = 0;
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
            if (isScreenDimmed) {
                setSystemBrightness(savedBrightness);
                isScreenDimmed = false;
            } else {
                savedBrightness = getSystemBrightness();
                if (savedBrightness <= 0) {
                    savedBrightness = MAX_BRIGHTNESS;
                }
                setSystemBrightness(MIN_BRIGHTNESS);
                isScreenDimmed = true;
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private int getSystemBrightness() {
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"su", "-c", "settings get system screen_brightness"});
            BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            if (line != null) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    private boolean setSystemBrightness(int brightness) {
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"su", "-c", "settings put system screen_brightness " + brightness});
            return process.waitFor() == 0;
        } catch (Exception e) {
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
}
