package com.ava.mods.tuyas8e;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final long ROTARY_RESET_DELAY_MS = 3000L;
    private static final long GESTURE_RESET_DELAY_MS = 350L;
    private static final int INPUT_EVENT_SIZE =
            (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) ? 24 : 16;
    private static final int EV_KEY = 0x01;
    private static final int EV_ABS = 0x03;
    private static final int KEY_F2 = 60;
    private static final int KEY_F3 = 61;
    private static final int ABS_MT_POSITION_X = 53;
    private static final int ABS_MT_POSITION_Y = 54;
    private static final int ABS_MT_TRACKING_ID = 57;

    private static volatile TuyaS8EManager instance;
    private final Context context;
    private final ExecutorService listenerExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService resetExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean listenersStarted = new AtomicBoolean(false);
    private final Map<String, CopyOnWriteArrayList<Object>> stateListeners = new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();
    private final AtomicInteger rotaryPosition = new AtomicInteger(0);
    private final AtomicInteger rotaryResetToken = new AtomicInteger(0);
    private final AtomicInteger gestureResetToken = new AtomicInteger(0);
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
        boolean ok = writeSysfs(SCREEN_BACKLIGHT_PATH, enabled ? "0" : "1");
        if (ok) {
            notifyStateListeners("screen_backlight", Boolean.valueOf(enabled));
        }
        return ok;
    }

    public boolean setSmallScreenBacklight(boolean enabled) {
        boolean ok = writeSysfs(SMALL_SCREEN_BACKLIGHT_PATH, enabled ? "1" : "0");
        if (ok) {
            notifyStateListeners("small_screen_backlight", Boolean.valueOf(enabled));
        }
        return ok;
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

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<Object>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        return true;
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
            FileInputStream stream = null;
            try {
                ensureInputDeviceReadable(ROTARY_EVENT_PATH);
                stream = new FileInputStream(ROTARY_EVENT_PATH);
                while (true) {
                    InputEvent event = readInputEvent(stream);
                    if (event == null) {
                        break;
                    }
                    if (event.type != EV_KEY || event.value != 1) {
                        continue;
                    }
                    if (event.code == KEY_F2) {
                        updateRotaryPosition(1);
                    } else if (event.code == KEY_F3) {
                        updateRotaryPosition(-1);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Rotary listener failed", e);
            } finally {
                closeQuietly(stream);
            }
            sleepQuietly(1000);
        }
    }

    private void listenTouchpadEvents() {
        while (true) {
            FileInputStream stream = null;
            try {
                ensureInputDeviceReadable(TOUCHPAD_EVENT_PATH);
                stream = new FileInputStream(TOUCHPAD_EVENT_PATH);
                Integer startX = null;
                Integer startY = null;
                Integer lastX = null;
                Integer lastY = null;
                boolean touchActive = false;
                while (true) {
                    InputEvent event = readInputEvent(stream);
                    if (event == null) {
                        break;
                    }
                    if (event.type != EV_ABS) {
                        continue;
                    }
                    if (event.code == ABS_MT_TRACKING_ID) {
                        if (event.value == -1) {
                            if (touchActive && startX != null && startY != null && lastX != null && lastY != null) {
                                updateGestureDirection(startX, startY, lastX, lastY);
                            }
                            scheduleGestureReset();
                            touchActive = false;
                            startX = null;
                            startY = null;
                            lastX = null;
                            lastY = null;
                            continue;
                        }
                        touchActive = true;
                        gestureResetToken.incrementAndGet();
                    }

                    if (event.code == ABS_MT_POSITION_X) {
                        if (startX == null) {
                            startX = event.value;
                        }
                        lastX = event.value;
                    } else if (event.code == ABS_MT_POSITION_Y) {
                        if (startY == null) {
                            startY = event.value;
                        }
                        lastY = event.value;
                    }

                    if (touchActive && startX != null && startY != null && lastX != null && lastY != null) {
                        updateGestureDirection(startX, startY, lastX, lastY);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Touchpad listener failed", e);
            } finally {
                closeQuietly(stream);
            }
            sleepQuietly(1000);
        }
    }

    private void updateRotaryPosition(int delta) {
        int current = rotaryPosition.addAndGet(delta);
        notifyStateListeners("rotary_position", Integer.valueOf(current));
        final int token = rotaryResetToken.incrementAndGet();
        resetExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (rotaryResetToken.get() == token) {
                    rotaryPosition.set(0);
                    notifyStateListeners("rotary_position", Integer.valueOf(0));
                }
            }
        }, ROTARY_RESET_DELAY_MS, TimeUnit.MILLISECONDS);
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
        notifyStateListeners("gesture_direction", lastGestureDirection);
    }

    private void scheduleGestureReset() {
        final int token = gestureResetToken.get();
        resetExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (gestureResetToken.get() == token) {
                    lastGestureDirection = "idle";
                    notifyStateListeners("gesture_direction", lastGestureDirection);
                }
            }
        }, GESTURE_RESET_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void notifyStateListeners(String entityId, Object value) {
        List<Object> listeners = stateListeners.get(entityId);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Object listener : listeners) {
            try {
                Method method = listener.getClass().getMethod("onStateChanged", Object.class);
                method.invoke(listener, value);
            } catch (Exception e) {
                Log.w(TAG, "Failed to notify state listener for " + entityId, e);
            }
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

    private void ensureInputDeviceReadable(String path) {
        File file = new File(path);
        if (file.canRead()) {
            return;
        }
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("chmod 666 " + path + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Failed to chmod input device: " + path, e);
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private InputEvent readInputEvent(FileInputStream stream) {
        try {
            byte[] buffer = new byte[INPUT_EVENT_SIZE];
            int offset = 0;
            while (offset < buffer.length) {
                int read = stream.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    return null;
                }
                offset += read;
            }

            int base = INPUT_EVENT_SIZE - 8;
            int type = readUInt16LE(buffer, base);
            int code = readUInt16LE(buffer, base + 2);
            int value = readInt32LE(buffer, base + 4);
            return new InputEvent(type, code, value);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read input event", e);
            return null;
        }
    }

    private int readUInt16LE(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    private int readInt32LE(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private void closeQuietly(FileInputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class InputEvent {
        private final int type;
        private final int code;
        private final int value;

        private InputEvent(int type, int code, int value) {
            this.type = type;
            this.code = code;
            this.value = value;
        }
    }
}
