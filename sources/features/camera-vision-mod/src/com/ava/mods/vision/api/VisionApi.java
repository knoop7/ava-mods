package com.ava.mods.vision.api;

/**
 * The only type shared between the facade's classloader and the child-first
 * loader that runs the vision core. ChildFirstLoader always delegates this
 * package to its parent so both sides see the same Class object; everything
 * else (TFLite, ZXing, the core) stays isolated inside the child loader.
 */
public interface VisionApi {

    void applyConfig(String key, String value);

    void enableCamera();

    void disableCamera();

    boolean isCameraEnabled();

    void enableQr();

    void disableQr();

    boolean isQrEnabled();

    void enableFace();

    void disableFace();

    boolean isFaceEnabled();

    void enableGesture();

    void disableGesture();

    boolean isGestureEnabled();

    void enableScreensaverWake();

    void disableScreensaverWake();

    boolean isScreensaverWakeEnabled();

    String getLastQr();

    String getLastTagId();

    int getQrScanCount();

    boolean hasFace();

    int getFaceCount();

    String getGesture();

    boolean hasOpenPalm();

    byte[] getLastJpeg();

    int getFps();

    int getScreensaverWakes();

    String getCameraFacing();

    String getStreamHealth();

    String getLastError();

    void setLastError(String error);

    boolean registerStateListener(String entityId, Object callback);

    void onDestroy();
}
