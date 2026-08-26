package com.ava.mods.vision.core;

public final class VisionConfig {

    public volatile boolean cameraEnabled = true;
    public volatile boolean useFrontCamera = true;
    public volatile int fps = 5;
    public volatile int resolution = 480;
    public volatile int jpegQuality = 75;

    /** Clockwise degrees to straighten frames, or -1 to derive it from the sensor. */
    public volatile int frameRotation = -1;

    public volatile boolean qrEnabled = true;
    public volatile boolean faceEnabled = true;
    public volatile boolean gestureEnabled = true;

    public volatile int qrCooldownSec = 5;
    public volatile String faceRange = "sparse";
    public volatile boolean parseHaTags = true;
    public volatile boolean screensaverWake = true;

    public volatile boolean adaptiveQuality = true;

    /** Cap imposed by AdaptiveQuality while the stream stutters; 0 follows the user setting. */
    public volatile int adaptiveResolution = 0;

    public int frameIntervalMs() {
        int safeFps = Math.max(1, Math.min(fps, 30));
        return Math.max(33, 1000 / safeFps);
    }

    public int safeResolution() {
        int base = Math.max(240, Math.min(resolution, 1080));
        int cap = adaptiveResolution;
        return cap > 0 && cap < base ? cap : base;
    }

    public int safeQuality() {
        return Math.max(40, Math.min(jpegQuality, 95));
    }
}
