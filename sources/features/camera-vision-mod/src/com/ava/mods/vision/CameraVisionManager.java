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

public final class CameraVisionManager {

    private static final String TAG = "CameraVisionManager";

    private static volatile CameraVisionManager instance;

    private final Context context;
    private final VisionConfig config = new VisionConfig();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService detectExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Object> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean detectBusy = new AtomicBoolean(false);

    private volatile VisionCamera camera;
    private volatile QrScanner qrScanner;
    private volatile FaceEngine faceEngine;
    private volatile GestureEngine gestureEngine;
    private volatile ScreensaverBridge screensaver;

    private volatile String lastQr = "";
    private volatile String lastTagId = "";
    private volatile int qrScanCount = 0;
    private volatile boolean facePresent = false;
    private volatile int faceCount = 0;
    private volatile String gesture = "";
    private volatile boolean openPalm = false;
    private volatile byte[] lastJpeg;
    private volatile String lastError = "";

    private volatile long lastQrTime = 0;
    private volatile String lastQrContent = "";
    private volatile long lastFaceFrameTime = 0;
    private volatile long lastGestureFrameTime = 0;
    private volatile long lastQrFrameTime = 0;

    private static final long FACE_INTERVAL_MS = 333;
    private static final long GESTURE_INTERVAL_MS = 500;
    private static final long QR_INTERVAL_MS = 500;

    private CameraVisionManager(Context context) {
        this.context = context.getApplicationContext();
        this.screensaver = new ScreensaverBridge(this.context.getClassLoader());
        executor.execute(this::autoStart);
    }

    /**
     * The host skips applyConfig entirely when a fresh install has no stored config
     * values, so manifest defaults never reach us. Act on our own defaults instead.
     */
    private void autoStart() {
        ensureEngines();
        if (config.cameraEnabled) {
            startCameraSync();
        }
        Log.i(TAG, "autoStart camera=" + config.cameraEnabled
                + " face=" + config.faceEnabled
                + " gesture=" + config.gestureEnabled
                + " qr=" + config.qrEnabled);
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
        boolean restartCamera = false;
        switch (key) {
            case "camera_enabled":
                if ("true".equalsIgnoreCase(value)) enableCamera(); else disableCamera();
                return;
            case "use_front_camera": {
                boolean next = "true".equalsIgnoreCase(value);
                if (next == config.useFrontCamera) return;
                config.useFrontCamera = next;
                restartCamera = true;
                break;
            }
            case "fps": {
                int next = clamp(parseInt(value, 5), 1, 30);
                if (next == config.fps) return;
                config.fps = next;
                break;
            }
            case "resolution": {
                int next = clamp(parseInt(value, 480), 240, 1080);
                if (next == config.resolution) return;
                config.resolution = next;
                restartCamera = true;
                break;
            }
            case "frame_rotation": {
                int next = "auto".equalsIgnoreCase(value.trim())
                        ? -1
                        : ((parseInt(value, 0) % 360) + 360) % 360;
                if (next == config.frameRotation) return;
                config.frameRotation = next;
                restartCamera = true;
                break;
            }
            case "jpeg_quality": {
                int next = clamp(parseInt(value, 75), 40, 95);
                if (next == config.jpegQuality) return;
                config.jpegQuality = next;
                break;
            }
            case "qr_enabled":
                if ("true".equalsIgnoreCase(value)) enableQr(); else disableQr();
                return;
            case "face_enabled":
                if ("true".equalsIgnoreCase(value)) enableFace(); else disableFace();
                return;
            case "gesture_enabled":
                if ("true".equalsIgnoreCase(value)) enableGesture(); else disableGesture();
                return;
            case "qr_cooldown_sec":
                config.qrCooldownSec = clamp(parseInt(value, 5), 1, 60);
                return;
            case "face_range": {
                String next = "short".equalsIgnoreCase(value.trim()) ? "short" : "sparse";
                if (next.equals(config.faceRange)) return;
                config.faceRange = next;
                if (config.faceEnabled) {
                    executor.execute(this::reloadFaceModel);
                }
                return;
            }
            case "parse_ha_tags":
                config.parseHaTags = "true".equalsIgnoreCase(value);
                return;
            case "screensaver_wake": {
                boolean next = "true".equalsIgnoreCase(value);
                config.screensaverWake = next;
                ScreensaverBridge bridge = screensaver;
                if (!next && bridge != null) bridge.reset();
                notifyState("screensaver_wake", next);
                return;
            }
            default:
                return;
        }
        if (restartCamera && config.cameraEnabled) {
            executor.execute(this::restartCameraSync);
        }
    }

    public void enableCamera() {
        if (config.cameraEnabled && camera != null && camera.isRunning()) return;
        config.cameraEnabled = true;
        notifyState("camera_switch", true);
        executor.execute(this::startCameraSync);
    }

    public void disableCamera() {
        config.cameraEnabled = false;
        notifyState("camera_switch", false);
        executor.execute(this::stopCameraSync);
    }

    public boolean isCameraEnabled() {
        VisionCamera c = camera;
        return config.cameraEnabled && c != null && c.isRunning();
    }

    public void enableQr() {
        config.qrEnabled = true;
        notifyState("qr_scanning", true);
        executor.execute(this::ensureEngines);
        ensureCameraForDetection();
    }

    public void disableQr() {
        config.qrEnabled = false;
        notifyState("qr_scanning", false);
    }

    public void enableFace() {
        config.faceEnabled = true;
        notifyState("face_detection", true);
        executor.execute(this::ensureEngines);
        ensureCameraForDetection();
    }

    public void disableFace() {
        config.faceEnabled = false;
        notifyState("face_detection", false);
        executor.execute(() -> {
            if (faceEngine != null) {
                faceEngine.close();
                faceEngine = null;
            }
        });
        resetFaceState();
    }

    public void enableGesture() {
        config.gestureEnabled = true;
        notifyState("gesture_detection", true);
        executor.execute(this::ensureEngines);
        ensureCameraForDetection();
    }

    public void disableGesture() {
        config.gestureEnabled = false;
        notifyState("gesture_detection", false);
        executor.execute(() -> {
            if (gestureEngine != null) {
                gestureEngine.close();
                gestureEngine = null;
            }
        });
        resetGestureState();
    }

    public boolean isQrEnabled() { return config.qrEnabled; }
    public boolean isFaceEnabled() { return config.faceEnabled; }
    public boolean isGestureEnabled() { return config.gestureEnabled; }
    public String getLastQr() { return lastQr; }
    public String getLastTagId() { return lastTagId; }
    public int getQrScanCount() { return qrScanCount; }
    public boolean hasFace() { return facePresent; }
    public int getFaceCount() { return faceCount; }
    public String getGesture() { return gesture; }
    public boolean hasOpenPalm() { return openPalm; }
    public byte[] getLastJpeg() { return lastJpeg; }

    public int getFps() {
        VisionCamera c = camera;
        return c != null ? c.getMeasuredFps() : 0;
    }

    public void enableScreensaverWake() {
        config.screensaverWake = true;
        notifyState("screensaver_wake", true);
    }

    public void disableScreensaverWake() {
        config.screensaverWake = false;
        ScreensaverBridge bridge = screensaver;
        if (bridge != null) bridge.reset();
        notifyState("screensaver_wake", false);
    }

    public boolean isScreensaverWakeEnabled() {
        return config.screensaverWake;
    }

    public int getScreensaverWakes() {
        ScreensaverBridge bridge = screensaver;
        return bridge != null ? bridge.getWakeCount() : 0;
    }

    public String getCameraFacing() {
        return config.useFrontCamera ? "front" : "back";
    }

    public String getLastError() {
        VisionCamera c = camera;
        String camErr = c != null ? c.getLastError() : "";
        if (camErr != null && !camErr.isEmpty()) return camErr;
        return lastError;
    }

    public void setLastError(String error) {
        lastError = error == null ? "" : error;
        notifyState("last_error", lastError);
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || callback == null) return false;
        listeners.put(entityId, callback);
        switch (entityId) {
            case "camera_switch": invokeState(callback, isCameraEnabled()); break;
            case "qr_scanning": invokeState(callback, config.qrEnabled); break;
            case "face_detection": invokeState(callback, config.faceEnabled); break;
            case "gesture_detection": invokeState(callback, config.gestureEnabled); break;
            case "fps": invokeState(callback, getFps()); break;
            case "camera_facing": invokeState(callback, getCameraFacing()); break;
            case "screensaver_wake": invokeState(callback, config.screensaverWake); break;
            case "screensaver_wakes": invokeState(callback, getScreensaverWakes()); break;
            case "last_error": invokeState(callback, getLastError()); break;
            default: break;
        }
        return true;
    }

    private void ensureCameraForDetection() {
        if (config.cameraEnabled) {
            VisionCamera c = camera;
            if (c == null || !c.isRunning()) {
                executor.execute(this::startCameraSync);
            }
        }
    }

    private synchronized void startCameraSync() {
        if (!config.cameraEnabled) return;
        VisionCamera existing = camera;
        if (existing != null && existing.isRunning()) return;
        try {
            VisionCamera cam = new VisionCamera(context, config);
            cam.setFrameListener(this::onFrame);
            cam.start();
            camera = cam;
            notifyState("camera_switch", true);
            Log.i(TAG, "Vision camera started");
        } catch (Exception e) {
            Log.e(TAG, "startCamera failed", e);
            setLastError(String.valueOf(e.getMessage()));
        }
    }

    private synchronized void stopCameraSync() {
        VisionCamera cam = camera;
        if (cam != null) {
            cam.setFrameListener(null);
            cam.stop();
            camera = null;
        }
        lastJpeg = null;
        resetFaceState();
        resetGestureState();
        notifyState("camera_switch", false);
        notifyState("fps", 0);
        Log.i(TAG, "Vision camera stopped");
    }

    private synchronized void restartCameraSync() {
        stopCameraSync();
        if (config.cameraEnabled) {
            startCameraSync();
        }
        notifyState("camera_facing", getCameraFacing());
    }

    private void onFrame(byte[] jpegData) {
        if (jpegData == null || jpegData.length == 0) return;
        lastJpeg = jpegData;
        notifyState("fps", getFps());

        boolean needsDetect = config.qrEnabled || config.faceEnabled || config.gestureEnabled;
        if (!needsDetect) return;
        if (!detectBusy.compareAndSet(false, true)) return;

        detectExecutor.execute(() -> {
            try {
                runDetectors(jpegData);
            } finally {
                detectBusy.set(false);
            }
        });
    }

    private void runDetectors(byte[] jpegData) {
        long now = System.currentTimeMillis();
        boolean doQr = config.qrEnabled && now - lastQrFrameTime >= QR_INTERVAL_MS;
        boolean doFace = config.faceEnabled && now - lastFaceFrameTime >= FACE_INTERVAL_MS;
        boolean doGesture = config.gestureEnabled && now - lastGestureFrameTime >= GESTURE_INTERVAL_MS;
        if (!doQr && !doFace && !doGesture) return;

        ensureEngines();

        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bmp == null) return;
            if (doQr) {
                lastQrFrameTime = now;
                processQr(bmp);
            }
            if (doFace) {
                lastFaceFrameTime = now;
                processFace(bmp);
            }
            if (doGesture) {
                lastGestureFrameTime = now;
                processGesture(bmp);
            }
        } catch (Exception e) {
            Log.w(TAG, "Detect error: " + e.getMessage());
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private void processQr(Bitmap bmp) {
        QrScanner scanner = qrScanner;
        if (scanner == null) return;
        String result = scanner.decode(bmp);
        if (result == null || result.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (result.equals(lastQrContent) && now - lastQrTime < config.qrCooldownSec * 1000L) {
            return;
        }
        lastQrContent = result;
        lastQrTime = now;
        lastQr = result;
        qrScanCount++;
        notifyState("last_qr", lastQr);
        notifyState("qr_scan_count", qrScanCount);

        if (config.parseHaTags) {
            String tag = extractHaTag(result);
            if (tag != null && !tag.isEmpty()) {
                lastTagId = tag;
                notifyState("ha_tag_id", lastTagId);
            }
        }
    }

    private void processFace(Bitmap bmp) {
        FaceEngine engine = faceEngine;
        if (engine == null) return;
        FaceEngine.Result result = engine.detect(bmp);
        if (result.count != faceCount) {
            faceCount = result.count;
            notifyState("face_count", faceCount);
        }
        boolean present = result.present;
        if (present != facePresent) {
            facePresent = present;
            notifyState("face_detected", facePresent);
        }
        if (config.screensaverWake) {
            ScreensaverBridge bridge = screensaver;
            if (bridge != null) {
                bridge.onPresence(present);
                notifyState("screensaver_wakes", bridge.getWakeCount());
            }
        }
    }

    private void processGesture(Bitmap bmp) {
        GestureEngine engine = gestureEngine;
        if (engine == null) return;
        GestureEngine.Result result = engine.detect(bmp);
        if (!result.gesture.equals(gesture)) {
            gesture = result.gesture;
            notifyState("gesture", gesture);
        }
        if (result.openPalm != openPalm) {
            openPalm = result.openPalm;
            notifyState("open_palm", openPalm);
        }
    }

    /**
     * Build engines on the detect thread if a switch is on but the model never loaded.
     * A failed engine is still kept so a broken model does not rebuild every frame;
     * its reason is published to the Vision Error sensor instead.
     */
    private void ensureEngines() {
        if (config.qrEnabled && qrScanner == null) {
            qrScanner = new QrScanner();
        }
        if (config.faceEnabled && faceEngine == null) {
            FaceEngine engine = new FaceEngine(context, config.faceRange);
            faceEngine = engine;
            if (!engine.isReady()) setLastError(engine.getError());
        }
        if (config.gestureEnabled && gestureEngine == null) {
            GestureEngine engine = new GestureEngine(context);
            gestureEngine = engine;
            if (!engine.isReady()) setLastError(engine.getError());
        }
    }

    private void resetFaceState() {
        if (faceCount != 0) {
            faceCount = 0;
            notifyState("face_count", 0);
        }
        if (facePresent) {
            facePresent = false;
            notifyState("face_detected", false);
        }
        ScreensaverBridge bridge = screensaver;
        if (bridge != null) bridge.reset();
    }

    private void resetGestureState() {
        if (!gesture.isEmpty()) {
            gesture = "";
            notifyState("gesture", "");
        }
        if (openPalm) {
            openPalm = false;
            notifyState("open_palm", false);
        }
    }

    private void reloadFaceModel() {
        FaceEngine old = faceEngine;
        if (old != null) old.close();
        faceEngine = new FaceEngine(context, config.faceRange);
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
        if (cb != null) invokeState(cb, value);
    }

    private static void invokeState(Object callback, Object value) {
        try {
            Method m = callback.getClass().getMethod("onStateChanged", Object.class);
            m.invoke(callback, value);
        } catch (Exception ignored) {
        }
    }

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void onDestroy() {
        config.qrEnabled = false;
        config.faceEnabled = false;
        config.gestureEnabled = false;
        config.cameraEnabled = false;
        stopCameraSync();
        if (faceEngine != null) { faceEngine.close(); faceEngine = null; }
        if (gestureEngine != null) { gestureEngine.close(); gestureEngine = null; }
        qrScanner = null;
        listeners.clear();
        Log.i(TAG, "onDestroy — camera and engines released");
    }
}
