package com.ava.mods.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CameraVisionManager {

    private static final String TAG = "CameraVisionManager";

    private static volatile CameraVisionManager instance;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Object> listeners = new ConcurrentHashMap<>();

    private final AtomicBoolean qrEnabled = new AtomicBoolean(false);
    private final AtomicBoolean faceEnabled = new AtomicBoolean(false);
    private final AtomicBoolean gestureEnabled = new AtomicBoolean(false);

    private volatile QrScanner qrScanner;
    private volatile FaceEngine faceEngine;
    private volatile GestureEngine gestureEngine;

    private volatile String lastQr = "";
    private volatile String lastTagId = "";
    private volatile int qrScanCount = 0;
    private volatile boolean facePresent = false;
    private volatile int faceCount = 0;
    private volatile String gesture = "";
    private volatile boolean openPalm = false;

    private volatile int qrCooldownSec = 5;
    private volatile String faceRange = "sparse";
    private volatile boolean parseHaTags = true;
    private volatile String lastError = "";

    private volatile long lastQrTime = 0;
    private volatile String lastQrContent = "";
    private volatile long lastFaceFrameTime = 0;
    private volatile long lastGestureFrameTime = 0;
    private volatile long lastQrFrameTime = 0;

    private static final long FACE_INTERVAL_MS = 333;
    private static final long GESTURE_INTERVAL_MS = 333;
    private static final long QR_INTERVAL_MS = 500;

    private CameraVisionManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static CameraVisionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (CameraVisionManager.class) {
                if (instance == null) {
                    instance = new CameraVisionManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) return;
        switch (key) {
            case "qr_enabled":
                if ("true".equalsIgnoreCase(value)) enableQr(); else disableQr();
                break;
            case "face_enabled":
                if ("true".equalsIgnoreCase(value)) enableFace(); else disableFace();
                break;
            case "gesture_enabled":
                if ("true".equalsIgnoreCase(value)) enableGesture(); else disableGesture();
                break;
            case "qr_cooldown_sec":
                try { qrCooldownSec = Math.max(1, Math.min(60, Integer.parseInt(value.trim()))); } catch (Exception ignored) {}
                break;
            case "face_range":
                String prev = faceRange;
                faceRange = "short".equalsIgnoreCase(value.trim()) ? "short" : "sparse";
                if (!faceRange.equals(prev) && faceEnabled.get()) {
                    executor.execute(this::reloadFaceModel);
                }
                break;
            case "parse_ha_tags":
                parseHaTags = "true".equalsIgnoreCase(value);
                break;
        }
    }

    public void enableQr() {
        if (qrEnabled.compareAndSet(false, true)) {
            executor.execute(() -> {
                if (qrScanner == null) {
                    qrScanner = new QrScanner();
                }
            });
            notifyState("qr_scanning", true);
        }
    }

    public void disableQr() {
        if (qrEnabled.compareAndSet(true, false)) {
            notifyState("qr_scanning", false);
        }
    }

    public void enableFace() {
        if (faceEnabled.compareAndSet(false, true)) {
            executor.execute(() -> {
                if (faceEngine == null) {
                    faceEngine = new FaceEngine(context, faceRange);
                }
            });
            notifyState("face_detection", true);
        }
    }

    public void disableFace() {
        if (faceEnabled.compareAndSet(true, false)) {
            executor.execute(() -> {
                if (faceEngine != null) {
                    faceEngine.close();
                    faceEngine = null;
                }
            });
            notifyState("face_detection", false);
        }
    }

    public void enableGesture() {
        if (gestureEnabled.compareAndSet(false, true)) {
            executor.execute(() -> {
                if (gestureEngine == null) {
                    gestureEngine = new GestureEngine(context);
                }
            });
            notifyState("gesture_detection", true);
        }
    }

    public void disableGesture() {
        if (gestureEnabled.compareAndSet(true, false)) {
            executor.execute(() -> {
                if (gestureEngine != null) {
                    gestureEngine.close();
                    gestureEngine = null;
                }
            });
            notifyState("gesture_detection", false);
        }
    }

    public boolean isQrEnabled() { return qrEnabled.get(); }
    public boolean isFaceEnabled() { return faceEnabled.get(); }
    public boolean isGestureEnabled() { return gestureEnabled.get(); }
    public String getLastQr() { return lastQr; }
    public String getLastTagId() { return lastTagId; }
    public int getQrScanCount() { return qrScanCount; }
    public boolean hasFace() { return facePresent; }
    public int getFaceCount() { return faceCount; }
    public String getGesture() { return gesture; }
    public boolean hasOpenPalm() { return openPalm; }
    public String getLastError() { return lastError; }

    public void setLastError(String error) {
        lastError = error == null ? "" : error;
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || callback == null) return false;
        listeners.put(entityId, callback);
        return true;
    }

    public byte[] onFrame(byte[] jpegData) {
        if (jpegData == null || jpegData.length == 0) return jpegData;
        long now = System.currentTimeMillis();

        if (qrEnabled.get() && now - lastQrFrameTime >= QR_INTERVAL_MS) {
            lastQrFrameTime = now;
            processQr(jpegData);
        }

        if (faceEnabled.get() && now - lastFaceFrameTime >= FACE_INTERVAL_MS) {
            lastFaceFrameTime = now;
            processFace(jpegData);
        }

        if (gestureEnabled.get() && now - lastGestureFrameTime >= GESTURE_INTERVAL_MS) {
            lastGestureFrameTime = now;
            processGesture(jpegData);
        }

        return jpegData;
    }

    private void processQr(byte[] jpegData) {
        QrScanner scanner = qrScanner;
        if (scanner == null) return;
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bmp == null) return;
            String result = scanner.decode(bmp);
            bmp.recycle();
            if (result == null || result.isEmpty()) return;

            long now = System.currentTimeMillis();
            boolean sameTooSoon = result.equals(lastQrContent)
                    && (now - lastQrTime < qrCooldownSec * 1000L);
            if (sameTooSoon) return;

            lastQrContent = result;
            lastQrTime = now;
            lastQr = result;
            qrScanCount++;
            notifyState("last_qr", lastQr);
            notifyState("qr_scan_count", qrScanCount);

            if (parseHaTags) {
                String tag = extractHaTag(result);
                if (tag != null && !tag.isEmpty()) {
                    lastTagId = tag;
                    notifyState("ha_tag_id", lastTagId);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "QR error: " + e.getMessage());
        }
    }

    private void processFace(byte[] jpegData) {
        FaceEngine engine = faceEngine;
        if (engine == null) return;
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bmp == null) return;
            FaceEngine.Result result = engine.detect(bmp);
            bmp.recycle();

            if (result.count != faceCount) {
                faceCount = result.count;
                notifyState("face_count", faceCount);
            }
            boolean present = result.count > 0;
            if (present != facePresent) {
                facePresent = present;
                notifyState("face_detected", facePresent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Face error: " + e.getMessage());
        }
    }

    private void processGesture(byte[] jpegData) {
        GestureEngine engine = gestureEngine;
        if (engine == null) return;
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bmp == null) return;
            GestureEngine.Result result = engine.detect(bmp);
            bmp.recycle();

            if (!result.gesture.equals(gesture)) {
                gesture = result.gesture;
                notifyState("gesture", gesture);
            }
            if (result.openPalm != openPalm) {
                openPalm = result.openPalm;
                notifyState("open_palm", openPalm);
            }
        } catch (Exception e) {
            Log.w(TAG, "Gesture error: " + e.getMessage());
        }
    }

    private void reloadFaceModel() {
        if (faceEngine != null) {
            faceEngine.close();
        }
        faceEngine = new FaceEngine(context, faceRange);
    }

    private String extractHaTag(String url) {
        if (url == null) return null;
        int idx = url.indexOf("home-assistant.io/tag/");
        if (idx < 0) idx = url.indexOf("ha-tag://");
        if (idx < 0) return null;
        String after;
        if (url.contains("home-assistant.io/tag/")) {
            after = url.substring(url.indexOf("/tag/") + 5);
        } else {
            after = url.substring(url.indexOf("ha-tag://") + 9);
        }
        int end = after.indexOf('?');
        if (end < 0) end = after.indexOf('#');
        if (end < 0) end = after.length();
        String tag = after.substring(0, end).trim();
        while (tag.endsWith("/")) tag = tag.substring(0, tag.length() - 1);
        return tag.isEmpty() ? null : tag;
    }

    private void notifyState(String entityId, Object value) {
        Object cb = listeners.get(entityId);
        if (cb == null) return;
        try {
            Method m = cb.getClass().getMethod("onStateChanged", Object.class);
            m.invoke(cb, value);
        } catch (Exception ignored) {}
    }

    public void onDestroy() {
        qrEnabled.set(false);
        faceEnabled.set(false);
        gestureEnabled.set(false);
        if (faceEngine != null) { faceEngine.close(); faceEngine = null; }
        if (gestureEngine != null) { gestureEngine.close(); gestureEngine = null; }
        qrScanner = null;
        listeners.clear();
    }
}
