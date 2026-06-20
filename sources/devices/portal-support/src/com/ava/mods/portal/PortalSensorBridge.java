package com.ava.mods.portal;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

class PortalSensorBridge implements SensorEventListener {

    private static final String TAG = "PortalSupport";
    private static final int RGB_TYPE = 65537;
    private static final long TAP_COOLDOWN_MS = 800L;
    private static final long TAP_RESET_MS = 1500L;
    private static final long LIGHT_THROTTLE_MS = 2000L;
    private static final float LIGHT_MIN_DELTA = 1.5f;
    private static final long ACCEL_THROTTLE_MS = 5000L;
    private static final float GRAVITY_ALPHA = 0.85f;
    private static final long TEMP_THROTTLE_MS = 30_000L;
    private static final float TEMP_MIN_DELTA = 0.2f;

    interface Listener {
        void onAmbientLight(float lux);
        void onRgb(float r, float g, float b);
        void onTemperature(float celsius);
        void onAccelerometer(float x, float y, float z);
        void onTapTilt(String direction);
    }

    private final Context context;
    private final Listener listener;
    private final SensorManager sensorManager;
    private final HandlerThread thread = new HandlerThread("portal-sensors");
    private Handler handler;
    private final boolean isCipher = "cipher".equalsIgnoreCase(Build.DEVICE);
    private final float tapScale = isCipher ? 0.25f : 1f;

    private volatile float tapThreshold = 4.0f;
    private volatile float tempOffset = 0.0f;
    private volatile boolean enableAmbientLight;
    private volatile boolean enableRgb;
    private volatile boolean enableTemperature;
    private volatile boolean enableTapTilt;
    private volatile boolean enableAccelerometer;

    private boolean hasRgb;
    private boolean hasTemperature;
    private boolean running;

    private float gravX;
    private float gravY;
    private float gravZ;
    private boolean gravInit;
    private long lastTapMs;
    private long lastLightMs;
    private float lastLux = Float.MIN_VALUE;
    private long lastAccelMs;
    private long lastRgbMs;
    private long lastTempMs;
    private float lastTemp = Float.MIN_VALUE;
    private float lastR;
    private float lastG;
    private float lastB;
    private float lastAccelX;
    private float lastAccelY;
    private float lastAccelZ;
    private String lastTapDirection = "none";

    private final Object tapResetToken = new Object();
    private final Runnable tapResetRunnable = new TapResetRunnable();

    PortalSensorBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    boolean hasRgbSensor() {
        return hasRgb;
    }

    boolean hasTemperatureSensor() {
        return hasTemperature;
    }

    String getLastTapDirection() {
        return lastTapDirection;
    }

    float getLastLux() {
        return lastLux == Float.MIN_VALUE ? 0f : lastLux;
    }

    float getLastR() {
        return lastR;
    }

    float getLastG() {
        return lastG;
    }

    float getLastB() {
        return lastB;
    }

    float getLastTemperature() {
        return lastTemp == Float.MIN_VALUE ? 0f : lastTemp + tempOffset;
    }

    float getLastAccelX() {
        return lastAccelX;
    }

    float getLastAccelY() {
        return lastAccelY;
    }

    float getLastAccelZ() {
        return lastAccelZ;
    }

    void setTapThreshold(float threshold) {
        tapThreshold = threshold;
    }

    void setTempOffset(float offset) {
        tempOffset = offset;
        if (lastTemp != Float.MIN_VALUE && enableTemperature) {
            listener.onTemperature(lastTemp + tempOffset);
        }
    }

    void updateFlags(boolean ambientLight, boolean rgb, boolean temperature, boolean tapTilt, boolean accelerometer) {
        enableAmbientLight = ambientLight;
        enableRgb = rgb;
        enableTemperature = temperature;
        enableTapTilt = tapTilt;
        enableAccelerometer = accelerometer;
        if (running) {
            restart();
        }
    }

    void start(boolean ambientLight, boolean rgb, boolean temperature, boolean tapTilt, boolean accelerometer) {
        enableAmbientLight = ambientLight;
        enableRgb = rgb;
        enableTemperature = temperature;
        enableTapTilt = tapTilt;
        enableAccelerometer = accelerometer;
        if (running) {
            return;
        }
        running = true;
        thread.start();
        handler = new Handler(thread.getLooper());
        detectHardware();
        registerSensors();
        Log.i(TAG, "sensors: rgb=" + hasRgb + " temperature=" + hasTemperature);
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        sensorManager.unregisterListener(this);
        thread.quitSafely();
        Log.i(TAG, "SensorBridge stopped");
    }

    private void restart() {
        sensorManager.unregisterListener(this);
        registerSensors();
    }

    private void detectHardware() {
        hasRgb = false;
        hasTemperature = false;
        if (sensorManager == null) {
            return;
        }
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.getType() == RGB_TYPE) {
                hasRgb = true;
            }
        }
        hasTemperature = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null;
    }

    private void registerSensors() {
        if (sensorManager == null || handler == null) {
            return;
        }
        if (enableAmbientLight) {
            Sensor light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light != null) {
                sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, handler);
            }
        }
        if (enableTapTilt || enableAccelerometer) {
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler);
            }
        }
        if (enableRgb && hasRgb) {
            for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (sensor.getType() == RGB_TYPE) {
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
                    break;
                }
            }
        }
        if (enableTemperature && hasTemperature) {
            Sensor temp = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
            if (temp != null) {
                sensorManager.registerListener(this, temp, SensorManager.SENSOR_DELAY_NORMAL, handler);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_LIGHT) {
            handleLight(event);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            handleAccel(event);
        } else if (type == Sensor.TYPE_AMBIENT_TEMPERATURE) {
            handleTemp(event);
        } else if (type == RGB_TYPE) {
            handleRgb(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void handleTemp(SensorEvent event) {
        if (!enableTemperature) {
            return;
        }
        float c = event.values[0];
        long now = System.currentTimeMillis();
        if (now - lastTempMs < TEMP_THROTTLE_MS && Math.abs(c - lastTemp) < TEMP_MIN_DELTA) {
            return;
        }
        lastTempMs = now;
        lastTemp = c;
        listener.onTemperature(c + tempOffset);
    }

    private void handleLight(SensorEvent event) {
        if (!enableAmbientLight) {
            return;
        }
        float lux = event.values[0];
        long now = System.currentTimeMillis();
        if (now - lastLightMs < LIGHT_THROTTLE_MS && Math.abs(lux - lastLux) < LIGHT_MIN_DELTA) {
            return;
        }
        lastLightMs = now;
        lastLux = lux;
        listener.onAmbientLight(lux);
    }

    private void handleRgb(SensorEvent event) {
        if (!enableRgb) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRgbMs < LIGHT_THROTTLE_MS) {
            return;
        }
        lastRgbMs = now;
        lastR = event.values.length > 0 ? event.values[0] : 0f;
        lastG = event.values.length > 1 ? event.values[1] : 0f;
        lastB = event.values.length > 2 ? event.values[2] : 0f;
        listener.onRgb(lastR, lastG, lastB);
    }

    private void handleAccel(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float alpha = gravInit ? GRAVITY_ALPHA : 0f;
        gravX = alpha * gravX + (1 - alpha) * x;
        gravY = alpha * gravY + (1 - alpha) * y;
        gravZ = alpha * gravZ + (1 - alpha) * z;
        gravInit = true;

        float lx = x - gravX;
        float ly = y - gravY;
        float lz = z - gravZ;
        float force = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);

        long now = System.currentTimeMillis();
        if (enableAccelerometer && now - lastAccelMs >= ACCEL_THROTTLE_MS) {
            lastAccelMs = now;
            lastAccelX = x;
            lastAccelY = y;
            lastAccelZ = z;
            listener.onAccelerometer(x, y, z);
        }

        if (!enableTapTilt) {
            return;
        }

        float threshold = tapThreshold * tapScale;
        if (force > threshold && now - lastTapMs > TAP_COOLDOWN_MS) {
            lastTapMs = now;
            String dir;
            if (Math.abs(lx) >= Math.abs(ly) && Math.abs(lx) >= Math.abs(lz)) {
                dir = lx > 0 ? "right" : "left";
            } else if (Math.abs(ly) >= Math.abs(lx) && Math.abs(ly) >= Math.abs(lz)) {
                dir = ly > 0 ? "down" : "up";
            } else if (isCipher) {
                dir = lz > 0 ? "up" : "down";
            } else {
                dir = lz > 0 ? "front" : "back";
            }
            lastTapDirection = dir;
            Log.i(TAG, "tap: " + dir + "  force=" + String.format("%.1f", force)
                    + "  threshold=" + String.format("%.1f", threshold)
                    + " (scale=" + String.format("%.2f", tapScale) + ")");
            listener.onTapTilt(dir);
            if (handler != null) {
                handler.removeCallbacksAndMessages(tapResetToken);
                handler.postAtTime(tapResetRunnable, tapResetToken, SystemClock.uptimeMillis() + TAP_RESET_MS);
            }
        }
    }

    private class TapResetRunnable implements Runnable {
        @Override
        public void run() {
            lastTapDirection = "none";
            listener.onTapTilt("none");
        }
    }
}
