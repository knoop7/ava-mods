package com.ava.mods.tuyas8e;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TuyaS8EManager {
    private static final String TAG = "TuyaS8EManager";
    private static final String SCREEN_BACKLIGHT_PATH = "/sys/class/backlight/backlight/bl_power";
    private static final String SMALL_SCREEN_BACKLIGHT_PATH = "/sys/class/gpio/gpio115/value";
    private static final String TEMPERATURE_PATH = "/sys/devices/platform/twi.1/i2c-1/1-0040/temp1_input";
    private static final String HUMIDITY_PATH = "/sys/devices/platform/twi.1/i2c-1/1-0040/humidity1_input";
    private static final String ROTARY_EVENT_PATH = "/dev/input/event4";
    private static final String TOUCHPAD_EVENT_PATH = "/dev/input/event2";
    private static final float TEMPERATURE_OFFSET = -4.0f;
    private static final float HUMIDITY_OFFSET = 4.0f;
    private static final int GESTURE_THRESHOLD = 20;

    private static volatile TuyaS8EManager instance;
    private final Context context;
    private final ExecutorService listenerExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean listenersStarted = new AtomicBoolean(false);
    private final AtomicInteger rotaryPosition = new AtomicInteger(0);
    private volatile float lastTemperature = 0.0f;
    private volatile float lastHumidity = 0.0f;
    private volatile boolean hasTemperatureReading = false;
    private volatile boolean hasHumidityReading = false;
    private volatile String lastGestureDirection = "idle";

    private TuyaS8EManager(Context context) {
        this.context = context.getApplicationContext();
        startListenersIfNeeded();
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

    public int getRotaryPosition() {
        startListenersIfNeeded();
        return rotaryPosition.get();
    }

    public String getGestureDirection() {
        startListenersIfNeeded();
        return lastGestureDirection;
    }

    public String getDeviceModel() {
        return Build.MODEL == null ? "" : Build.MODEL;
    }

    private void startListenersIfNeeded() {
        if (!listenersStarted.compareAndSet(false, true)) {
            return;
        }
        listenerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                listenRotaryEvents();
            }
        });
        listenerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                listenTouchpadEvents();
            }
        });
    }

    private void listenRotaryEvents() {
        while (true) {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "getevent -lt " + ROTARY_EVENT_PATH
                });
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("EV_KEY")) {
                        continue;
                    }
                    if (line.contains("KEY_F2") && line.contains("DOWN")) {
                        rotaryPosition.incrementAndGet();
                    } else if (line.contains("KEY_F3") && line.contains("DOWN")) {
                        rotaryPosition.decrementAndGet();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Rotary listener failed", e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
            sleepQuietly(1000);
        }
    }

    private void listenTouchpadEvents() {
        while (true) {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "getevent -lt " + TOUCHPAD_EVENT_PATH
                });
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                Integer startX = null;
                Integer startY = null;
                Integer lastX = null;
                Integer lastY = null;
                boolean touchActive = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("ABS_MT_TRACKING_ID")) {
                        if (line.trim().endsWith("ffffffff")) {
                            if (touchActive && startX != null && startY != null && lastX != null && lastY != null) {
                                updateGestureDirection(startX, startY, lastX, lastY);
                            }
                            touchActive = false;
                            startX = null;
                            startY = null;
                            lastX = null;
                            lastY = null;
                            continue;
                        }
                        touchActive = true;
                    }

                    if (line.contains("ABS_MT_POSITION_X")) {
                        Integer parsed = parseHexValue(line);
                        if (parsed != null) {
                            if (startX == null) {
                                startX = parsed;
                            }
                            lastX = parsed;
                        }
                    } else if (line.contains("ABS_MT_POSITION_Y")) {
                        Integer parsed = parseHexValue(line);
                        if (parsed != null) {
                            if (startY == null) {
                                startY = parsed;
                            }
                            lastY = parsed;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Touchpad listener failed", e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
            sleepQuietly(1000);
        }
    }

    private void updateGestureDirection(int startX, int startY, int endX, int endY) {
        int dx = endX - startX;
        int dy = endY - startY;
        if (Math.abs(dx) < GESTURE_THRESHOLD && Math.abs(dy) < GESTURE_THRESHOLD) {
            return;
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            lastGestureDirection = dx > 0 ? "right" : "left";
        } else {
            lastGestureDirection = dy > 0 ? "down" : "up";
        }
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

    private Integer parseHexValue(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0) {
                return null;
            }
            String hex = parts[parts.length - 1];
            return Integer.parseInt(hex, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
