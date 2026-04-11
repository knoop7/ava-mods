package com.ava.mods.tuyas8e;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;

public class TuyaS8EManager {
    private static final String TAG = "TuyaS8EManager";
    private static final String SCREEN_BACKLIGHT_PATH = "/sys/class/backlight/backlight/bl_power";
    private static final String SMALL_SCREEN_BACKLIGHT_PATH = "/sys/class/gpio/gpio115/value";
    private static final String TEMPERATURE_PATH = "/sys/devices/platform/twi.1/i2c-1/1-0040/temp1_input";
    private static final String HUMIDITY_PATH = "/sys/devices/platform/twi.1/i2c-1/1-0040/humidity1_input";
    private static final float TEMPERATURE_OFFSET = -4.0f;
    private static final float HUMIDITY_OFFSET = 4.0f;

    private static volatile TuyaS8EManager instance;
    private final Context context;
    private volatile float lastTemperature = 0.0f;
    private volatile float lastHumidity = 0.0f;
    private volatile boolean hasTemperatureReading = false;
    private volatile boolean hasHumidityReading = false;

    private TuyaS8EManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static TuyaS8EManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TuyaS8EManager.class) {
                if (instance == null) {
                    instance = new TuyaS8EManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isSupported() {
        return hasScreenBacklightNode() && hasSmallScreenBacklightNode();
    }

    public boolean isSupported(Context context) {
        return isSupported();
    }

    public boolean setScreenBacklight(boolean enabled) {
        return writeSysfs(SCREEN_BACKLIGHT_PATH, enabled ? "0" : "1");
    }

    public boolean setSmallScreenBacklight(boolean enabled) {
        return writeSysfs(SMALL_SCREEN_BACKLIGHT_PATH, enabled ? "1" : "0");
    }

    public boolean isScreenBacklightOn() {
        String value = readSysfs(SCREEN_BACKLIGHT_PATH);
        return "0".equals(value);
    }

    public boolean isSmallScreenBacklightOn() {
        String value = readSysfs(SMALL_SCREEN_BACKLIGHT_PATH);
        return "1".equals(value);
    }

    public boolean hasScreenBacklightNode() {
        return new File(SCREEN_BACKLIGHT_PATH).exists();
    }

    public boolean hasSmallScreenBacklightNode() {
        return new File(SMALL_SCREEN_BACKLIGHT_PATH).exists();
    }

    public boolean hasTemperatureNode() {
        return new File(TEMPERATURE_PATH).exists();
    }

    public boolean hasHumidityNode() {
        return new File(HUMIDITY_PATH).exists();
    }

    public String getScreenBacklightRaw() {
        return readSysfs(SCREEN_BACKLIGHT_PATH);
    }

    public String getSmallScreenBacklightRaw() {
        return readSysfs(SMALL_SCREEN_BACKLIGHT_PATH);
    }

    public float getTemperature() {
        String raw = readSysfs(TEMPERATURE_PATH);
        Float parsed = parseTemperature(raw);
        if (parsed != null) {
            lastTemperature = parsed;
            hasTemperatureReading = true;
            return parsed;
        }
        return lastTemperature;
    }

    public float getHumidity() {
        String raw = readSysfs(HUMIDITY_PATH);
        Float parsed = parseHumidity(raw);
        if (parsed != null) {
            lastHumidity = parsed;
            hasHumidityReading = true;
            return parsed;
        }
        return lastHumidity;
    }

    public boolean hasTemperatureReading() {
        if (hasTemperatureReading) {
            return true;
        }
        return parseTemperature(readSysfs(TEMPERATURE_PATH)) != null;
    }

    public boolean hasHumidityReading() {
        if (hasHumidityReading) {
            return true;
        }
        return parseHumidity(readSysfs(HUMIDITY_PATH)) != null;
    }

    public String getDeviceModel() {
        return Build.MODEL == null ? "" : Build.MODEL;
    }

    private boolean writeSysfs(String path, String value) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("echo " + value + " > " + path + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            int result = process.waitFor();
            if (result != 0) {
                Log.e(TAG, "Failed to write sysfs: " + path + " value=" + value + " code=" + result);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write sysfs: " + path, e);
            return false;
        }
    }

    private String readSysfs(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return "";
        }
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            reader.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read sysfs: " + path, e);
            return "";
        }
    }

    private Float parseTemperature(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty() || normalized.length() < 3) {
            return null;
        }
        try {
            String formatted = normalized.substring(0, 2) + "." + normalized.substring(2);
            float value = Float.parseFloat(formatted);
            return roundToTwoDecimals(value + TEMPERATURE_OFFSET);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse temperature: " + raw, e);
            return null;
        }
    }

    private Float parseHumidity(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            String formatted;
            if (normalized.length() == 4) {
                formatted = normalized.substring(0, 1) + "." + normalized.substring(1);
            } else if (normalized.length() == 5) {
                formatted = normalized.substring(0, 2) + "." + normalized.substring(2, 4);
            } else {
                return null;
            }
            float value = Float.parseFloat(formatted);
            return roundToTwoDecimals(value + HUMIDITY_OFFSET);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse humidity: " + raw, e);
            return null;
        }
    }

    private float roundToTwoDecimals(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }
}
