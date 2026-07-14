package com.ava.mods.echoshow;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;

/**
 * Echo Show screensaver dark-off sleep/wake. Only invoked via ModDeviceSupport hooks
 * when echo-show-support mod is enabled and isSupported() is true.
 *
 * After dark sleep, keeps a renewing PARTIAL_WAKE_LOCK and watches ambient light via:
 * 1) Privileged JSA1214 sysfs lux polling (works while panel is blanked)
 * 2) SensorManager TYPE_LIGHT as a secondary path when still delivering events
 *
 * Kernel reference: amazon-oss/android_kernel_amazon_mt8163 alsps/jsa1214 — TYPE_LIGHT
 * often stalls after display power-off; sysfs lux + enable is the reliable restore path.
 */
final class EchoShowScreenControl {
    private static final String TAG = "EchoShowSupport";
    private static final int MIN_BRIGHTNESS = 10;
    /** Match Ava ScreensaverController LIGHT_RESTORE_THRESHOLD_LUX. */
    private static final float LIGHT_RESTORE_LUX = 4.0f;
    private static final long LIGHT_DEBOUNCE_MS = 1500L;
    private static final long ALS_POLL_INTERVAL_MS = 10_000L;
    private static final int ALS_BRIGHT_POLLS_NEEDED = 2;
    private static final long WAKELOCK_CHUNK_MS = 25L * 60L * 1000L;
    private static final long WAKELOCK_RENEW_MS = 20L * 60L * 1000L;

    private static volatile int cachedBrightness = 128;

    private static final Object watchLock = new Object();
    private static volatile boolean watching;
    private static Context watchContext;
    private static HandlerThread watchThread;
    private static Handler watchHandler;
    private static SensorManager sensorManager;
    private static Sensor lightSensor;
    private static SensorEventListener lightListener;
    private static PowerManager.WakeLock watchWakeLock;
    private static Long lightCandidateSinceMs;
    private static int consecutiveBrightPolls;
    private static final Runnable renewWakeLockRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (watchLock) {
                if (!watching || watchHandler == null) {
                    return;
                }
                acquireWatchWakeLockLocked();
                watchHandler.postDelayed(this, WAKELOCK_RENEW_MS);
            }
        }
    };
    private static final Runnable alsPollRunnable = new Runnable() {
        @Override
        public void run() {
            Context ctx;
            synchronized (watchLock) {
                if (!watching || watchHandler == null) {
                    return;
                }
                ctx = watchContext;
            }
            Float lux = EchoShowPrivilegedShell.readAlsLux();
            if (lux != null) {
                Log.d(TAG, "ALS sysfs poll lux=" + lux);
                onPrivilegedLux(lux, ctx);
            } else {
                synchronized (watchLock) {
                    consecutiveBrightPolls = 0;
                }
            }
            synchronized (watchLock) {
                if (watching && watchHandler != null) {
                    watchHandler.postDelayed(this, ALS_POLL_INTERVAL_MS);
                }
            }
        }
    };

    private EchoShowScreenControl() {
    }

    static boolean sleepForDark(Context context) {
        Context appContext = context.getApplicationContext();
        if (!EchoShowPrivilegedShell.isShizukuGranted() && !EchoShowPrivilegedShell.isRootAvailable()) {
            Log.w(TAG, "sleepForDark: no Shizuku or root available");
            return false;
        }

        cacheCurrentBrightness();

        boolean slept = false;
        if (EchoShowPrivilegedShell.setDisplayPower(0)) {
            Log.i(TAG, "sleepForDark: display power off (Shizuku)");
            slept = true;
        } else if (EchoShowPrivilegedShell.execShell("input keyevent 223") == 0) {
            Log.i(TAG, "sleepForDark: display sleep keyevent 223");
            slept = true;
        } else if (EchoShowPrivilegedShell.execShell("input keyevent 26") == 0) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null || !pm.isInteractive()) {
                Log.i(TAG, "sleepForDark: power key sleep");
                slept = true;
            }
        } else if (EchoShowPrivilegedShell.writeBacklightBrightness(MIN_BRIGHTNESS)) {
            Log.i(TAG, "sleepForDark: fallback min brightness " + MIN_BRIGHTNESS);
            slept = true;
        }

        if (!slept) {
            Log.w(TAG, "sleepForDark: all strategies failed");
            return false;
        }

        startLightRestoreWatch(appContext);
        return true;
    }

    static boolean wakeFromDark(Context context) {
        Context appContext = context.getApplicationContext();
        stopLightRestoreWatch();

        boolean woke = false;

        if (EchoShowPrivilegedShell.setDisplayPower(2)) {
            Log.i(TAG, "wakeFromDark: display power on (Shizuku)");
            woke = true;
        }

        // KEYCODE_WAKEUP (224) — do not lead with power key 26 (toggles / can leave panel off).
        if (EchoShowPrivilegedShell.execShell("input keyevent 224") == 0) {
            Log.i(TAG, "wakeFromDark: wakeup keyevent 224");
            woke = true;
        }

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            if (EchoShowPrivilegedShell.execShell("input keyevent 26") == 0 && pm.isInteractive()) {
                Log.i(TAG, "wakeFromDark: power key wake (was non-interactive)");
                woke = true;
            }
        }

        int target = cachedBrightness > MIN_BRIGHTNESS ? cachedBrightness : 128;
        if (EchoShowPrivilegedShell.writeBacklightBrightness(target)) {
            Log.i(TAG, "wakeFromDark: restored brightness " + target);
            woke = true;
        } else if (woke) {
            restoreBrightness();
        }

        if (woke) {
            return true;
        }

        Log.w(TAG, "wakeFromDark: all strategies failed");
        return false;
    }

    private static void startLightRestoreWatch(Context appContext) {
        synchronized (watchLock) {
            stopLightRestoreWatchLocked();
            watchContext = appContext;
            watching = true;
            consecutiveBrightPolls = 0;

            watchThread = new HandlerThread("EchoShowDarkLightWatch");
            watchThread.start();
            watchHandler = new Handler(watchThread.getLooper());

            acquireWatchWakeLockLocked();
            watchHandler.postDelayed(renewWakeLockRunnable, WAKELOCK_RENEW_MS);

            // Primary path: privileged JSA1214 lux (works after setDisplayPower(0)).
            watchHandler.post(alsPollRunnable);

            // Secondary: SensorManager, if the HAL still delivers events.
            sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
            lightSensor = sensorManager != null
                    ? sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
                    : null;
            if (lightSensor == null) {
                Log.w(TAG, "light restore watch: no TYPE_LIGHT (sysfs poll still active)");
                return;
            }

            lightListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event == null || event.values == null || event.values.length == 0) {
                        return;
                    }
                    onWatchLux(event.values[0]);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };

            boolean registered = sensorManager.registerListener(
                    lightListener,
                    lightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    watchHandler
            );
            if (registered) {
                Log.i(TAG, "light restore watch: TYPE_LIGHT + ALS sysfs poll");
            } else {
                Log.w(TAG, "light restore watch: TYPE_LIGHT register failed; ALS sysfs poll only");
            }
        }
    }

    private static void onPrivilegedLux(float lux, Context ctx) {
        synchronized (watchLock) {
            if (!watching) {
                return;
            }
            if (lux < LIGHT_RESTORE_LUX) {
                consecutiveBrightPolls = 0;
                lightCandidateSinceMs = null;
                return;
            }
            consecutiveBrightPolls++;
            if (consecutiveBrightPolls >= ALS_BRIGHT_POLLS_NEEDED) {
                Log.i(TAG, "ALS sysfs: ambient light restored (lux=" + lux + "), waking");
            } else {
                // Keep SensorManager debounce warm when polls are bright but not yet enough.
                if (lightCandidateSinceMs == null && watchHandler != null) {
                    lightCandidateSinceMs = System.currentTimeMillis();
                    final Context wakeCtx = watchContext;
                    watchHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            maybeWakeFromWatch(wakeCtx);
                        }
                    }, LIGHT_DEBOUNCE_MS);
                }
                return;
            }
        }
        wakeFromDark(ctx);
    }

    private static void onWatchLux(float lux) {
        synchronized (watchLock) {
            if (!watching || watchHandler == null) {
                return;
            }
            if (lux < LIGHT_RESTORE_LUX) {
                lightCandidateSinceMs = null;
                return;
            }
            long now = System.currentTimeMillis();
            if (lightCandidateSinceMs == null) {
                lightCandidateSinceMs = now;
                final Context ctx = watchContext;
                watchHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        maybeWakeFromWatch(ctx);
                    }
                }, LIGHT_DEBOUNCE_MS);
            }
        }
    }

    private static void maybeWakeFromWatch(Context ctx) {
        synchronized (watchLock) {
            if (!watching || ctx == null) {
                return;
            }
            if (lightCandidateSinceMs == null) {
                return;
            }
            if (System.currentTimeMillis() - lightCandidateSinceMs < LIGHT_DEBOUNCE_MS - 50L) {
                return;
            }
        }
        Log.i(TAG, "light restore watch: ambient light restored, waking");
        wakeFromDark(ctx);
    }

    private static void stopLightRestoreWatch() {
        synchronized (watchLock) {
            stopLightRestoreWatchLocked();
        }
    }

    private static void stopLightRestoreWatchLocked() {
        watching = false;
        lightCandidateSinceMs = null;
        consecutiveBrightPolls = 0;
        if (watchHandler != null) {
            watchHandler.removeCallbacksAndMessages(null);
        }
        if (sensorManager != null && lightListener != null) {
            try {
                sensorManager.unregisterListener(lightListener);
            } catch (Exception ignored) {
            }
        }
        sensorManager = null;
        lightSensor = null;
        lightListener = null;
        releaseWatchWakeLockLocked();
        if (watchThread != null) {
            watchThread.quitSafely();
            watchThread = null;
        }
        watchHandler = null;
        watchContext = null;
    }

    @SuppressWarnings("deprecation")
    private static void acquireWatchWakeLockLocked() {
        if (watchContext == null) {
            return;
        }
        PowerManager pm = (PowerManager) watchContext.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        releaseWatchWakeLockLocked();
        watchWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EchoShowSupport:DarkLightWatch");
        watchWakeLock.setReferenceCounted(false);
        watchWakeLock.acquire(WAKELOCK_CHUNK_MS);
        Log.d(TAG, "light restore watch: wake lock acquired (" + WAKELOCK_CHUNK_MS + "ms)");
    }

    private static void releaseWatchWakeLockLocked() {
        if (watchWakeLock != null) {
            try {
                if (watchWakeLock.isHeld()) {
                    watchWakeLock.release();
                }
            } catch (Exception ignored) {
            }
            watchWakeLock = null;
        }
    }

    private static void cacheCurrentBrightness() {
        try {
            Class<?> rootUtils = Class.forName("com.example.ava.utils.RootUtils");
            Object instance = rootUtils.getField("INSTANCE").get(null);
            if (instance != null) {
                Integer value = (Integer) rootUtils.getMethod("readBacklightBrightness").invoke(instance);
                if (value != null && value > 0) {
                    cachedBrightness = value;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void restoreBrightness() {
        int target = cachedBrightness > MIN_BRIGHTNESS ? cachedBrightness : 128;
        EchoShowPrivilegedShell.writeBacklightBrightness(target);
    }
}
