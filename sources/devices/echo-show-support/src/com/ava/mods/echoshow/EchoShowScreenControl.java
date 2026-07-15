package com.ava.mods.echoshow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;

/**
 * Echo Show screensaver dark-off sleep/wake.
 *
 * Host Ava keeps {@code isScreenOffByDark=true} until lux rises. If the user presses power
 * while still dark, Ava never re-enters the sleep path — this class re-arms dark sleep after
 * a short grace period so the panel can turn off again without waiting for morning light.
 */
final class EchoShowScreenControl {
    private static final String TAG = "EchoShowSupport";
    private static final int MIN_BRIGHTNESS = 10;
    /** Match Ava ScreensaverController thresholds. */
    private static final float DARK_OFF_LUX = 1.5f;
    private static final float LIGHT_RESTORE_LUX = 4.0f;
    private static final long LIGHT_DEBOUNCE_MS = 1500L;
    private static final long DARK_DEBOUNCE_MS = 1500L;
    /** Let the user look at the panel after pressing power before we dark-sleep again. */
    private static final long MANUAL_WAKE_GRACE_MS = 90_000L;
    private static final long ALS_POLL_INTERVAL_MS = 10_000L;
    private static final int ALS_BRIGHT_POLLS_NEEDED = 2;
    private static final int ALS_DARK_POLLS_NEEDED = 2;
    private static final long WAKELOCK_CHUNK_MS = 25L * 60L * 1000L;
    private static final long WAKELOCK_RENEW_MS = 20L * 60L * 1000L;
    private static final long PANEL_ON_DETECT_SETTLE_MS = 5_000L;

    private static final int MODE_IDLE = 0;
    private static final int MODE_WAIT_LIGHT = 1;
    private static final int MODE_WAIT_REDARK = 2;

    private static volatile int cachedBrightness = 128;

    private static final Object watchLock = new Object();
    private static volatile int watchMode = MODE_IDLE;
    private static Context watchContext;
    private static HandlerThread watchThread;
    private static Handler watchHandler;
    private static SensorManager sensorManager;
    private static Sensor lightSensor;
    private static SensorEventListener lightListener;
    private static PowerManager.WakeLock watchWakeLock;
    private static BroadcastReceiver screenReceiver;
    private static Long lightCandidateSinceMs;
    private static Long darkCandidateSinceMs;
    private static int consecutiveBrightPolls;
    private static int consecutiveDarkPolls;
    private static volatile boolean selfWaking;
    private static volatile long redarkArmedAtMs;
    private static volatile long waitLightStartedAtMs;

    private static final Runnable renewWakeLockRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (watchLock) {
                if (watchMode == MODE_IDLE || watchHandler == null) {
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
            int mode;
            synchronized (watchLock) {
                if (watchMode == MODE_IDLE || watchHandler == null) {
                    return;
                }
                mode = watchMode;
                ctx = watchContext;
            }
            Float lux = EchoShowPrivilegedShell.readAlsLux();
            if (lux != null) {
                Log.d(TAG, "ALS sysfs poll lux=" + lux + " mode=" + mode);
                if (mode == MODE_WAIT_LIGHT) {
                    long sinceSleep;
                    synchronized (watchLock) {
                        sinceSleep = System.currentTimeMillis() - waitLightStartedAtMs;
                    }
                    if (sinceSleep >= PANEL_ON_DETECT_SETTLE_MS
                            && lux < LIGHT_RESTORE_LUX
                            && EchoShowPrivilegedShell.isDisplayLikelyOn()) {
                        Log.i(TAG, "panel on while waiting for light (lux=" + lux + ") — manual wake");
                        onManualWakeWhileDarkSession(ctx);
                    } else {
                        onPrivilegedLuxForWake(lux, ctx);
                    }
                } else if (mode == MODE_WAIT_REDARK) {
                    if (lux >= LIGHT_RESTORE_LUX) {
                        Log.i(TAG, "light returned during re-dark arm — ending dark session");
                        wakeFromDark(ctx);
                    } else {
                        onPrivilegedLuxForResleep(lux, ctx);
                    }
                }
            } else {
                synchronized (watchLock) {
                    consecutiveBrightPolls = 0;
                    consecutiveDarkPolls = 0;
                }
            }
            synchronized (watchLock) {
                if (watchMode != MODE_IDLE && watchHandler != null) {
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

        startWatch(appContext, MODE_WAIT_LIGHT);
        return true;
    }

    static boolean wakeFromDark(Context context) {
        Context appContext = context.getApplicationContext();
        selfWaking = true;
        try {
            stopWatch();

            boolean woke = false;

            if (EchoShowPrivilegedShell.setDisplayPower(2)) {
                Log.i(TAG, "wakeFromDark: display power on (Shizuku)");
                woke = true;
            }

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
        } finally {
            selfWaking = false;
        }
    }

    private static void startWatch(Context appContext, int mode) {
        synchronized (watchLock) {
            stopWatchLocked();
            watchContext = appContext;
            watchMode = mode;
            consecutiveBrightPolls = 0;
            consecutiveDarkPolls = 0;
            lightCandidateSinceMs = null;
            darkCandidateSinceMs = null;
            redarkArmedAtMs = mode == MODE_WAIT_REDARK ? System.currentTimeMillis() : 0L;
            waitLightStartedAtMs = mode == MODE_WAIT_LIGHT ? System.currentTimeMillis() : 0L;

            watchThread = new HandlerThread("EchoShowDarkLightWatch");
            watchThread.start();
            watchHandler = new Handler(watchThread.getLooper());

            acquireWatchWakeLockLocked();
            watchHandler.postDelayed(renewWakeLockRunnable, WAKELOCK_RENEW_MS);
            watchHandler.post(alsPollRunnable);

            registerScreenReceiverLocked(appContext);
            registerTypeLightLocked(appContext);

            Log.i(TAG, "dark watch started mode=" + modeName(mode));
        }
    }

    private static void registerScreenReceiverLocked(Context appContext) {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    return;
                }
                if (selfWaking) {
                    return;
                }
                int mode;
                synchronized (watchLock) {
                    mode = watchMode;
                }
                if (mode == MODE_WAIT_LIGHT) {
                    Log.i(TAG, "SCREEN_ON while waiting for light — treating as manual power wake");
                    onManualWakeWhileDarkSession(appContext.getApplicationContext());
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(screenReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "register SCREEN_ON failed: " + e.getMessage());
            screenReceiver = null;
        }
    }

    private static void registerTypeLightLocked(Context appContext) {
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
                : null;
        if (lightSensor == null) {
            Log.w(TAG, "no TYPE_LIGHT (sysfs poll still active)");
            return;
        }
        lightListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event == null || event.values == null || event.values.length == 0) {
                    return;
                }
                float lux = event.values[0];
                int mode;
                Context ctx;
                synchronized (watchLock) {
                    mode = watchMode;
                    ctx = watchContext;
                }
                if (mode == MODE_WAIT_LIGHT) {
                    onWatchLuxForWake(lux);
                } else if (mode == MODE_WAIT_REDARK && ctx != null) {
                    onSensorLuxForResleep(lux, ctx);
                }
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
        if (!registered) {
            Log.w(TAG, "TYPE_LIGHT registerListener failed");
        }
    }

    private static void onManualWakeWhileDarkSession(Context appContext) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_LIGHT) {
                return;
            }
        }
        Log.i(TAG, "manual wake: re-arming dark sleep after " + MANUAL_WAKE_GRACE_MS + "ms grace");
        startWatch(appContext, MODE_WAIT_REDARK);
    }

    private static void onPrivilegedLuxForWake(float lux, Context ctx) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_LIGHT) {
                return;
            }
            if (lux < LIGHT_RESTORE_LUX) {
                consecutiveBrightPolls = 0;
                lightCandidateSinceMs = null;
                return;
            }
            consecutiveBrightPolls++;
            if (consecutiveBrightPolls < ALS_BRIGHT_POLLS_NEEDED) {
                return;
            }
            Log.i(TAG, "ALS sysfs: ambient light restored (lux=" + lux + "), waking");
        }
        wakeFromDark(ctx);
    }

    private static void onPrivilegedLuxForResleep(float lux, Context ctx) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_REDARK) {
                return;
            }
            if (System.currentTimeMillis() - redarkArmedAtMs < MANUAL_WAKE_GRACE_MS) {
                consecutiveDarkPolls = 0;
                darkCandidateSinceMs = null;
                return;
            }
            if (lux > DARK_OFF_LUX) {
                consecutiveDarkPolls = 0;
                darkCandidateSinceMs = null;
                return;
            }
            consecutiveDarkPolls++;
            if (consecutiveDarkPolls < ALS_DARK_POLLS_NEEDED) {
                return;
            }
            Log.i(TAG, "ALS sysfs: still dark after manual wake (lux=" + lux + "), sleeping again");
        }
        sleepForDark(ctx);
    }

    private static void onWatchLuxForWake(float lux) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_LIGHT || watchHandler == null) {
                return;
            }
            if (lux < LIGHT_RESTORE_LUX) {
                lightCandidateSinceMs = null;
                return;
            }
            if (lightCandidateSinceMs == null) {
                lightCandidateSinceMs = System.currentTimeMillis();
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

    private static void onSensorLuxForResleep(float lux, Context ctx) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_REDARK || watchHandler == null) {
                return;
            }
            if (System.currentTimeMillis() - redarkArmedAtMs < MANUAL_WAKE_GRACE_MS) {
                darkCandidateSinceMs = null;
                return;
            }
            if (lux > DARK_OFF_LUX) {
                darkCandidateSinceMs = null;
                return;
            }
            if (darkCandidateSinceMs == null) {
                darkCandidateSinceMs = System.currentTimeMillis();
                final Context wakeCtx = ctx;
                watchHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        maybeResleepFromWatch(wakeCtx);
                    }
                }, DARK_DEBOUNCE_MS);
            }
        }
    }

    private static void maybeWakeFromWatch(Context ctx) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_LIGHT || ctx == null) {
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

    private static void maybeResleepFromWatch(Context ctx) {
        synchronized (watchLock) {
            if (watchMode != MODE_WAIT_REDARK || ctx == null) {
                return;
            }
            if (darkCandidateSinceMs == null) {
                return;
            }
            if (System.currentTimeMillis() - darkCandidateSinceMs < DARK_DEBOUNCE_MS - 50L) {
                return;
            }
            if (System.currentTimeMillis() - redarkArmedAtMs < MANUAL_WAKE_GRACE_MS) {
                return;
            }
        }
        Log.i(TAG, "re-dark watch: still dark after manual wake, sleeping again");
        sleepForDark(ctx);
    }

    private static void stopWatch() {
        synchronized (watchLock) {
            stopWatchLocked();
        }
    }

    private static void stopWatchLocked() {
        watchMode = MODE_IDLE;
        lightCandidateSinceMs = null;
        darkCandidateSinceMs = null;
        consecutiveBrightPolls = 0;
        consecutiveDarkPolls = 0;
        redarkArmedAtMs = 0L;
        waitLightStartedAtMs = 0L;
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
        if (screenReceiver != null && watchContext != null) {
            try {
                watchContext.unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
        }
        screenReceiver = null;
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

    private static String modeName(int mode) {
        switch (mode) {
            case MODE_WAIT_LIGHT:
                return "WAIT_LIGHT";
            case MODE_WAIT_REDARK:
                return "WAIT_REDARK";
            default:
                return "IDLE";
        }
    }
}
