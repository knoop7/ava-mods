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
    private static final String KEY_IMAGE_SIZE = "image_size";

    /** Longest-edge caps (portrait + landscape). {@code 0} = no downscale. */
    private static final int SIZE_SMALL_SIDE = 480;
    private static final int SIZE_MEDIUM_SIDE = 720;
    private static final int SIZE_ORIGINAL_SIDE = 0;

    private static final int QUALITY_SMALL = 35;
    private static final int QUALITY_MEDIUM = 45;
    private static final int QUALITY_ORIGINAL = 70;

    private static volatile ScreenCaptureManager instance;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final Map<String, CopyOnWriteArrayList<Object>> listeners = new ConcurrentHashMap<>();

    private volatile boolean enableLastCaptureSensor;
    /** Config key {@code image_size}: small | medium | original. Not an HA entity. */
    private volatile String imageSize = "small";
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
        imageSize = normalizeImageSize(
                this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_IMAGE_SIZE, "small"));
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
        } else if (KEY_IMAGE_SIZE.equals(key)) {
            imageSize = normalizeImageSize(value);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_IMAGE_SIZE, imageSize)
                    .apply();
            Log.i(TAG, "image_size=" + imageSize);
        }
    }

    /** HA button press — capture current device screen. */
    public void takeScreenshot() {
        Log.i(TAG, "takeScreenshot pressed size=" + imageSize);
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
        int maxSide = maxSideForSize(imageSize);
        int quality = qualityForSize(imageSize);
        byte[] jpeg = invokeHostCapture(maxSide, quality);
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
        Log.i(TAG, "captured " + jpeg.length + " bytes at " + lastCaptureIso
                + " size=" + imageSize + " maxSide=" + maxSide);
        notifyListeners(ENTITY_SCREEN, jpeg);
        if (enableLastCaptureSensor) {
            notifyListeners(ENTITY_LAST, lastCaptureIso);
        }
    }

    private byte[] invokeHostCapture(int maxSide, int quality) {
        try {
            Class<?> clazz = loadHostClass("com.example.ava.mods.ModScreenCapture");
            Object instance = clazz.getField("INSTANCE").get(null);
            Method method = clazz.getMethod(
                    "captureJpeg",
                    Context.class,
                    boolean.class,
                    int.class,
                    int.class);
            Object result = method.invoke(instance, context, true, maxSide, quality);
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
        if (list == null || list.isEmpty()) {
            Log.w(TAG, "no listeners for " + entityId + " (value=" +
                    (value instanceof byte[] ? ((byte[]) value).length + "B" : String.valueOf(value)) + ")");
            return;
        }
        Log.i(TAG, "notify " + entityId + " → " + list.size() + " listener(s)");
        for (Object callback : list) {
            notifyOne(callback, value);
        }
    }

    private void notifyOne(Object callback, Object value) {
        try {
            Method method = findStateCallbackMethod(callback);
            if (method == null) {
                Log.w(TAG, "no onStateChanged/onState on " + callback.getClass().getName());
                return;
            }
            method.invoke(callback, value);
        } catch (Exception e) {
            Log.w(TAG, "state callback failed: " + e.getMessage(), e);
        }
    }

    private static Method findStateCallbackMethod(Object callback) {
        Class<?> clazz = callback.getClass();
        for (String name : new String[]{"onStateChanged", "onState"}) {
            try {
                Method m = clazz.getMethod(name, Object.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
            for (Method m : clazz.getMethods()) {
                if (!name.equals(m.getName()) || m.getParameterTypes().length != 1) {
                    continue;
                }
                m.setAccessible(true);
                return m;
            }
        }
        return null;
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

    private static String normalizeImageSize(String value) {
        if (value == null) {
            return "small";
        }
        String v = value.trim().toLowerCase(Locale.US);
        if ("medium".equals(v) || "mid".equals(v) || "中".equals(v)) {
            return "medium";
        }
        if ("original".equals(v) || "full".equals(v) || "raw".equals(v) || "原始".equals(v)) {
            return "original";
        }
        return "small";
    }

    private static int maxSideForSize(String size) {
        if ("medium".equals(size)) {
            return SIZE_MEDIUM_SIDE;
        }
        if ("original".equals(size)) {
            return SIZE_ORIGINAL_SIDE;
        }
        return SIZE_SMALL_SIDE;
    }

    private static int qualityForSize(String size) {
        if ("medium".equals(size)) {
            return QUALITY_MEDIUM;
        }
        if ("original".equals(size)) {
            return QUALITY_ORIGINAL;
        }
        return QUALITY_SMALL;
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
