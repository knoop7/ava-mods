package com.ava.mods.vision;

import android.content.Context;
import android.util.Log;

import com.ava.mods.vision.api.VisionApi;

import java.io.File;

/**
 * Facade the host reflects on. The host resolves methods on this class (loaded
 * by its own mod classloader) and invokes them on the getInstance result, so
 * this class must own the singleton and the full entity method surface. All
 * real work happens in VisionCore, loaded through ChildFirstLoader so the
 * bundled TFLite and ZXing never mix with the host's copies.
 */
public final class CameraVisionManager {

    private static final String TAG = "CameraVisionManager";
    private static final String CORE_CLASS = "com.ava.mods.vision.core.VisionCore";
    private static final String JAR_PATH = "mods/camera-vision-mod/libs/camera-vision.jar";

    private static volatile CameraVisionManager instance;

    private final VisionApi core;
    private final String initError;

    private CameraVisionManager(Context context) {
        VisionApi loaded = null;
        String failure = "";
        try {
            File jar = new File(context.getFilesDir(), JAR_PATH);
            if (!jar.isFile()) {
                throw new IllegalStateException("Mod JAR missing at " + jar);
            }
            File cache = new File(context.getCodeCacheDir(), "camera-vision-core");
            if (!cache.isDirectory() && !cache.mkdirs()) {
                throw new IllegalStateException("Cannot create dex cache " + cache);
            }
            ClassLoader child = new ChildFirstLoader(
                    jar.getAbsolutePath(),
                    cache.getAbsolutePath(),
                    CameraVisionManager.class.getClassLoader());
            Class<?> coreClass = child.loadClass(CORE_CLASS);
            loaded = (VisionApi) coreClass
                    .getConstructor(Context.class)
                    .newInstance(context.getApplicationContext());
            Log.i(TAG, "Vision core loaded child-first from " + jar.getName());
        } catch (Throwable t) {
            failure = "Vision core load failed: " + t;
            Log.e(TAG, failure, t);
        }
        this.core = loaded;
        this.initError = failure;
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
        if (core != null) core.applyConfig(key, value);
    }

    public void enableCamera() {
        if (core != null) core.enableCamera();
    }

    public void disableCamera() {
        if (core != null) core.disableCamera();
    }

    public boolean isCameraEnabled() {
        return core != null && core.isCameraEnabled();
    }

    public void enableQr() {
        if (core != null) core.enableQr();
    }

    public void disableQr() {
        if (core != null) core.disableQr();
    }

    public boolean isQrEnabled() {
        return core != null && core.isQrEnabled();
    }

    public void enableFace() {
        if (core != null) core.enableFace();
    }

    public void disableFace() {
        if (core != null) core.disableFace();
    }

    public boolean isFaceEnabled() {
        return core != null && core.isFaceEnabled();
    }

    public void enableGesture() {
        if (core != null) core.enableGesture();
    }

    public void disableGesture() {
        if (core != null) core.disableGesture();
    }

    public boolean isGestureEnabled() {
        return core != null && core.isGestureEnabled();
    }

    public void enableScreensaverWake() {
        if (core != null) core.enableScreensaverWake();
    }

    public void disableScreensaverWake() {
        if (core != null) core.disableScreensaverWake();
    }

    public boolean isScreensaverWakeEnabled() {
        return core != null && core.isScreensaverWakeEnabled();
    }

    public String getLastQr() {
        return core != null ? core.getLastQr() : "";
    }

    public String getLastTagId() {
        return core != null ? core.getLastTagId() : "";
    }

    public int getQrScanCount() {
        return core != null ? core.getQrScanCount() : 0;
    }

    public boolean hasFace() {
        return core != null && core.hasFace();
    }

    public int getFaceCount() {
        return core != null ? core.getFaceCount() : 0;
    }

    public String getGesture() {
        return core != null ? core.getGesture() : "";
    }

    public boolean hasOpenPalm() {
        return core != null && core.hasOpenPalm();
    }

    public byte[] getLastJpeg() {
        return core != null ? core.getLastJpeg() : null;
    }

    public int getFps() {
        return core != null ? core.getFps() : 0;
    }

    public int getScreensaverWakes() {
        return core != null ? core.getScreensaverWakes() : 0;
    }

    public String getCameraFacing() {
        return core != null ? core.getCameraFacing() : "";
    }

    public String getLastError() {
        return core != null ? core.getLastError() : initError;
    }

    public void setLastError(String error) {
        if (core != null) core.setLastError(error);
    }

    public boolean registerStateListener(String entityId, Object callback) {
        return core != null && core.registerStateListener(entityId, callback);
    }

    public void onDestroy() {
        if (core != null) core.onDestroy();
    }
}
