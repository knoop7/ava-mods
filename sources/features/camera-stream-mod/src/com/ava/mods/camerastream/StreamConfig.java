package com.ava.mods.camerastream;

/** Mutable stream settings controlled from Ava mod config. */
public final class StreamConfig {

    public static final String FORMAT_MJPEG = "mjpeg";
    public static final String FORMAT_RTSP = "rtsp";

    /** RTSP H.264 encoder preference (ignored for MJPEG). */
    public static final String ENCODER_AUTO = "auto";
    public static final String ENCODER_HARDWARE = "hardware";
    public static final String ENCODER_SOFTWARE = "software";

    public volatile String format = FORMAT_MJPEG;
    public volatile String encoder = ENCODER_AUTO;
    public volatile int port = 8554;
    public volatile String path = "ava";
    public volatile String token = "";
    public volatile boolean useFrontCamera = true;
    public volatile int fps = 5;
    public volatile int resolution = 480;
    public volatile int jpegQuality = 75;
    public volatile int bitrateKbps = 800;

    public int frameIntervalMs() {
        int safeFps = Math.max(1, Math.min(fps, 15));
        return Math.max(66, 1000 / safeFps);
    }

    public String normalizedFormat() {
        if (FORMAT_RTSP.equalsIgnoreCase(format)) {
            return FORMAT_RTSP;
        }
        return FORMAT_MJPEG;
    }

    public boolean isRtsp() {
        return FORMAT_RTSP.equals(normalizedFormat());
    }

    public String normalizedEncoder() {
        if (encoder == null) return ENCODER_AUTO;
        String e = encoder.trim().toLowerCase();
        if (ENCODER_HARDWARE.equals(e) || ENCODER_SOFTWARE.equals(e)) {
            return e;
        }
        return ENCODER_AUTO;
    }

    public String streamPath() {
        String p = path == null ? "ava" : path.trim();
        if (p.isEmpty()) p = "ava";
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
