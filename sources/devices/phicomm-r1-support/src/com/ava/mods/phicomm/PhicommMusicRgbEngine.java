package com.ava.mods.phicomm;

import android.content.Context;
import android.graphics.Color;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Music light engine — port of EchoService {@code PlayerVisualizer} modes 0–3.
 *
 * Stock behavior replicated:
 * <ul>
 *   <li>msgcenter 519 is requested while the visualizer runs — in the real
 *       {@code lights_effects.conf} it blanks the RGB ring and holds channel 0 so
 *       {@code LightsEffectService} does not draw over the JNI writes;</li>
 *   <li>mode 0: whole-ring HSV breathing; modes 1–3: five-band spectrum segment effects
 *       on the stock visualizer index space (24–38).</li>
 * </ul>
 * Requires {@code libledLight-jni.so}; without it the engine stays idle (the old msgcenter
 * "pulse 519" fallback only flashed black and has been removed).
 */
final class PhicommMusicRgbEngine implements Visualizer.OnDataCaptureListener {
    private static final String TAG = "PhicommMusicRgb";
    private static final float CHROMA_INCRE_STEP = 0.001f;
    private static final int CHROMA_GAIN = 4;
    private static final int LUMA_GAIN = 100;

    /** Stock PlayerVisualizer five-band FFT bin ranges. */
    private static final int[][] BAND_IDS = {
        {1, 4}, {5, 11}, {12, 20}, {21, 30}, {31, 512},
    };

    private final PhicommLightController lights;
    private Visualizer visualizer;
    private volatile int mode;
    private float hue;
    private float amp1;
    private float amp2;
    private float amp3;
    private boolean running;
    private float lastReportedAmp;

    PhicommMusicRgbEngine(Context context, PhicommLightController lights) {
        this.lights = lights;
    }

    void setMode(int mode) {
        if (mode >= 0 && mode <= 3) {
            this.mode = mode;
        }
    }

    int getMode() {
        return mode;
    }

    boolean start() {
        if (running) {
            return true;
        }
        if (!PhicommLedLightJni.isAvailable()) {
            Log.i(TAG, "libledLight-jni unavailable — music light disabled");
            return false;
        }
        try {
            visualizer = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(range[range.length - 1]);
            int rate = Visualizer.getMaxCaptureRate();
            visualizer.setDataCaptureListener(this, (int) (rate / 1.17073f), false, true);
            visualizer.setEnabled(true);
            running = true;
            // Stock parity: claim/blank the ring channel while the JNI visualizer draws.
            lights.showPlayingMusicLight();
            Log.i(TAG, "Visualizer started session=0 mode=" + mode);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Visualizer start failed", t);
            releaseVisualizer();
            return false;
        }
    }

    void stop() {
        boolean wasRunning = running;
        running = false;
        releaseVisualizer();
        if (wasRunning) {
            lights.turnOffPlayingMusicLight();
            PhicommLedLightJni.clearMusicRing();
        }
        lastReportedAmp = 0f;
    }

    String backendLabel() {
        return PhicommLedLightJni.backendLabel();
    }

    @Override
    public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
    }

    @Override
    public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
        if (!running || fft == null || fft.length < 4) {
            return;
        }
        float[] m = new float[(fft.length / 2) + 1];
        m[0] = Math.abs(fft[0]);
        int idx = 2;
        int zeroCount = 0;
        for (int j = 1; j < m.length - 1; j++) {
            m[j] = (float) Math.hypot(fft[idx], fft[idx + 1]);
            if (m[j] == 0.0f) {
                zeroCount++;
            }
            idx += 2;
        }
        if (zeroCount == m.length - 1) {
            PhicommLedLightJni.setVisualizerColor(PhicommLedBitmask.MASK_ALL, 0);
            return;
        }
        switch (mode) {
            case 1:
                effect1(m);
                break;
            case 2:
                effect2(m);
                break;
            case 3:
                effect3(m);
                break;
            default:
                effect0(m);
                break;
        }
    }

    float getLastAmplitude() {
        return lastReportedAmp;
    }

    /** Stock effect(): whole-ring HSV breathing. */
    private void effect0(float[] m) {
        float ampMax = 0f;
        for (int i = 1; i < m.length; i++) {
            if (m[i] > ampMax) {
                ampMax = m[i];
            }
        }
        float brightness = move5Avg(ampMax) / 180.0f;
        hue = (hue + (CHROMA_GAIN * CHROMA_INCRE_STEP)) % 1.0f;
        float scaled = brightness * (LUMA_GAIN / 100.0f);
        lastReportedAmp = scaled;
        PhicommLedLightJni.setVisualizerColor(
            PhicommLedBitmask.MASK_RGB_RING, hsbToColor(hue, 1.0f, scaled));
    }

    /** Stock effect1(): per-LED rainbow columns, 5 bands x 3 LEDs. */
    private void effect1(float[] m) {
        int ia = 0;
        float color = 0.06f;
        float brightness = 0.0f;
        for (int i = 0; i < BAND_IDS.length; i++) {
            float max = bandMax(m, i);
            boolean rand;
            if (max > 18.0f) {
                float b2 = ((max + 60.0f) / 180.0f) / 100.0f;
                brightness = b2 * LUMA_GAIN;
                rand = false;
            } else {
                rand = true;
            }
            for (int ii = 0; ii < 3; ii++) {
                if (rand) {
                    brightness = (float) (1.0E-6d + (Math.random() * 0.01d));
                }
                color += 0.06f;
                PhicommLedLightJni.setVisualizerColor(
                    PhicommLedBitmask.visualizerMake1(ia + 24 + ii),
                    hsbToColor(color, 1.0f, brightness));
            }
            ia += 3;
        }
        lastReportedAmp = brightness;
    }

    /** Stock effect2(): 3-LED band segments, hue shifts with band energy. */
    private void effect2(float[] m) {
        int ia = 0;
        float color = 0.06f;
        float brightness = 0.0f;
        for (int i = 0; i < BAND_IDS.length; i++) {
            float max = bandMax(m, i);
            float max1 = 0.0f;
            boolean rand;
            if (max > 13.0f) {
                max1 = (max / 180.0f) * 100.0f;
                float b2 = (max + 60.0f) / 180.0f;
                brightness = b2 * (LUMA_GAIN / 100.0f);
                rand = false;
            } else {
                rand = true;
            }
            if (rand) {
                brightness = (float) (1.0E-6d + (Math.random() * 0.01d));
            }
            PhicommLedLightJni.setVisualizerColor(
                PhicommLedBitmask.visualizerMake(ia + 24, ia + 24 + 2),
                hsbToColor((0.002f * max1) + color, 1.0f, brightness));
            color += 0.2f;
            ia += 3;
        }
        lastReportedAmp = brightness;
    }

    /** Stock effect3(): 3-LED band segments, hue purely energy-driven. */
    private void effect3(float[] m) {
        int ia = 0;
        float brightness = 0.0f;
        for (int i = 0; i < BAND_IDS.length; i++) {
            float max = bandMax(m, i);
            float max1 = 0.0f;
            boolean rand;
            if (max > CHROMA_INCRE_STEP) {
                max1 = (max / 180.0f) * 100.0f;
                float b2 = (max + 60.0f) / 180.0f;
                brightness = b2 * (LUMA_GAIN / 100.0f);
                rand = false;
            } else {
                rand = true;
            }
            if (rand) {
                brightness = (float) (1.0E-6d + (Math.random() * 0.01d));
            }
            PhicommLedLightJni.setVisualizerColor(
                PhicommLedBitmask.visualizerMake(ia + 24, ia + 24 + 2),
                hsbToColor(0.01f * (max1 + 10.0f), 1.0f, brightness));
            ia += 3;
        }
        lastReportedAmp = brightness;
    }

    private static float bandMax(float[] m, int band) {
        float max = 0.0f;
        int hi = Math.min(BAND_IDS[band][1] + 1, m.length);
        for (int ii = BAND_IDS[band][0]; ii < hi; ii++) {
            if (m[ii] > max) {
                max = m[ii];
            }
        }
        return max;
    }

    /** {@code com.xiaofei.Color.HSBtoColor} equivalent (hue in [0,1)), masked to RGB. */
    private static int hsbToColor(float hue, float saturation, float brightness) {
        float h = hue - (float) Math.floor(hue);
        float b = brightness;
        if (b < 0f) {
            b = 0f;
        } else if (b > 1f) {
            b = 1f;
        }
        return 0xFFFFFF & Color.HSVToColor(new float[] { h * 360f, saturation, b });
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable t) {
                Log.w(TAG, "Visualizer release failed", t);
            }
            visualizer = null;
        }
    }

    private float move5Avg(float amp) {
        amp1 = amp2;
        amp2 = amp3;
        amp3 = amp;
        return (amp1 + amp2 + amp3) / 3.0f;
    }
}
