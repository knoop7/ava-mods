package com.ava.mods.screencapture;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Device-screen capture for Home Assistant via Ava host {@code ModScreenCapture}.
 * Preference: Shizuku/root {@code screencap}, then Accessibility takeScreenshot (API 30+).
 */
public class ScreenCaptureManager {

    private static final String TAG = "ScreenCapture";
    private static final String ENTITY_SCREEN = "screen";
    private static final String ENTITY_LAST = "last_screenshot";
    private static final String PREFS = "screen_capture_mod";
    private static final String KEY_LAST_AT = "last_capture_at_ms";

    private static volatile ScreenCaptureManager instance;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final Map<String, CopyOnWriteArrayList<Object>> listeners = new ConcurrentHashMap<>();

    private volatile boolean enableLastCaptureSensor;
    private volatile byte[] lastJpeg;
    private volatile long lastCaptureAtMs;
    private volatile String lastCaptureIso = "";

    private ScreenCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        long stored = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_AT, 0L);
        if (stored > 0L) {
            lastCaptureAtMs = stored;
            lastCaptureIso = toIso(stored);
        }
    }

    public static ScreenCaptureManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ScreenCaptureManager.class) {
                if (instance == null) {
                    instance = new ScreenCaptureManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if ("enable_last_capture_sensor".equals(key)) {
            enableLastCaptureSensor = parseBoolean(value);
        }
    }

    /** HA button press — capture current device screen. */
    public void takeScreenshot() {
        Log.i(TAG, "takeScreenshot pressed");
        if (!capturing.compareAndSet(false, true)) {
            Log.w(TAG, "capture already in progress");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doCapture(true);
                } finally {
                    capturing.set(false);
                }
            }
        });
    }

    public byte[] getLastJpeg() {
        return lastJpeg;
    }

    public String getLastCaptureIso() {
        return lastCaptureIso == null ? "" : lastCaptureIso;
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        // Soft satellite restart re-registers; replace so orphaned host callbacks do not stack.
        CopyOnWriteArrayList<Object> list = new CopyOnWriteArrayList<>();
        list.add(callback);
        listeners.put(entityId, list);
        if (ENTITY_SCREEN.equals(entityId) && lastJpeg != null) {
            notifyOne(callback, lastJpeg);
        } else if (ENTITY_LAST.equals(entityId) && lastCaptureIso != null && !lastCaptureIso.isEmpty()) {
            notifyOne(callback, lastCaptureIso);
        }
        return true;
    }

    public void onDestroy() {
        listeners.clear();
        capturing.set(false);
        instance = null;
    }

    private void doCapture(boolean openSettingsIfNeeded) {
        ensureHostAccessibility(openSettingsIfNeeded);
        byte[] jpeg = invokeHostCapture();
        if (jpeg == null || jpeg.length < 64) {
            Log.w(TAG, "capture failed: " + invokeHostLastError());
            return;
        }
        long now = System.currentTimeMillis();
        lastJpeg = jpeg;
        lastCaptureAtMs = now;
        lastCaptureIso = toIso(now);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_AT, now)
                .apply();
        Log.i(TAG, "captured " + jpeg.length + " bytes at " + lastCaptureIso);
        notifyListeners(ENTITY_SCREEN, jpeg);
        if (enableLastCaptureSensor) {
            notifyListeners(ENTITY_LAST, lastCaptureIso);
        }
    }

    private byte[] invokeHostCapture() {
        try {
            Class<?> clazz = loadHostClass("com.example.ava.mods.ModScreenCapture");
            Object instance = clazz.getField("INSTANCE").get(null);
            Method method = clazz.getMethod(
                    "captureJpeg",
                    Context.class,
                    boolean.class,
                    int.class,
                    int.class);
            Object result = method.invoke(instance, context, true, 720, 40);
            return result instanceof byte[] ? (byte[]) result : null;
        } catch (Throwable t) {
            Log.w(TAG, "ModScreenCapture.captureJpeg unavailable: " + t.getMessage());
            return null;
        }
    }

    private String invokeHostLastError() {
        try {
            Class<?> clazz = loadHostClass("com.example.ava.mods.ModScreenCapture");
            Object instance = clazz.getField("INSTANCE").get(null);
            Object result = clazz.getMethod("lastError").invoke(instance);
            return result != null ? String.valueOf(result) : "unknown";
        } catch (Throwable t) {
            return t.getMessage() != null ? t.getMessage() : "host_api_missing";
        }
    }

    private void ensureHostAccessibility(boolean openSettingsIfNeeded) {
        try {
            Class<?> clazz = loadHostClass("com.example.ava.mods.ModScreenCapture");
            Object instance = clazz.getField("INSTANCE").get(null);
            Method can = clazz.getMethod("canCapture", Context.class);
            Boolean ok = (Boolean) can.invoke(instance, context);
            if (ok != null && ok) {
                return;
            }
            Method ensure = clazz.getMethod("ensureAccessibility", Context.class, boolean.class);
            ensure.invoke(instance, context, openSettingsIfNeeded);
        } catch (Throwable t) {
            Log.d(TAG, "ensureAccessibility skipped: " + t.getMessage());
        }
    }

    private void notifyListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> list = listeners.get(entityId);
        if (list == null) {
            return;
        }
        for (Object callback : list) {
            notifyOne(callback, value);
        }
    }

    private void notifyOne(Object callback, Object value) {
        try {
            Method method;
            try {
                method = callback.getClass().getMethod("onStateChanged", Object.class);
            } catch (NoSuchMethodException e) {
                method = callback.getClass().getMethod("onState", Object.class);
            }
            method.invoke(callback, value);
        } catch (Exception e) {
            Log.w(TAG, "state callback failed: " + e.getMessage());
        }
    }

    private Class<?> loadHostClass(String className) throws ClassNotFoundException {
        ClassLoader loader = context.getClassLoader();
        if (loader != null) {
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(className);
    }

    private static String toIso(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date(epochMs));
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.US);
        return "true".equals(v) || "1".equals(v) || "on".equals(v) || "yes".equals(v);
    }
}
