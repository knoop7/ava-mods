package com.ava.mods.phicomm;

/**
 * JNI accent-color breathing on the RGB ring — replicates stock loading effect 203 timing
 * ({@code 000000 → color} ramp over 1500 ms, then back, endless) but with a configurable color.
 * Stock 203 is always blue {@code 1414ff}; the accent variant is an intentional Ava extension.
 */
final class PhicommRingBreather {
    private static final long RAMP_MS = 1500L;
    private static final long FRAME_MS = 50L;

    private static Thread thread;
    private static volatile boolean running;
    private static volatile int targetRgb;

    private PhicommRingBreather() {
    }

    static synchronized void start(int rgb) {
        targetRgb = rgb & 0xFFFFFF;
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                while (running) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long phase = elapsed % (RAMP_MS * 2);
                    float level = phase < RAMP_MS
                        ? phase / (float) RAMP_MS
                        : (RAMP_MS * 2 - phase) / (float) RAMP_MS;
                    PhicommLedLightJni.setRingColor(scale(targetRgb, level));
                    try {
                        Thread.sleep(FRAME_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                PhicommLedLightJni.clearVoiceLoadingRing();
            }
        }, "PhicommRingBreather");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    static boolean isRunning() {
        return running;
    }

    private static int scale(int rgb, float level) {
        if (level < 0f) {
            level = 0f;
        } else if (level > 1f) {
            level = 1f;
        }
        int r = (int) (((rgb >> 16) & 0xFF) * level);
        int g = (int) (((rgb >> 8) & 0xFF) * level);
        int b = (int) ((rgb & 0xFF) * level);
        return (r << 16) | (g << 8) | b;
    }
}
