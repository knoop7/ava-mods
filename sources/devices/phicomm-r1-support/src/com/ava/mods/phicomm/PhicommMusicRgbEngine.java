package com.ava.mods.phicomm;

import android.content.Context;
import android.graphics.Color;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * RGB music breathing — port of EchoService {@code PlayerVisualizer} mode 0 (HSV ring via JNI).
 */
final class PhicommMusicRgbEngine implements Visualizer.OnDataCaptureListener {
    private static final String TAG = "PhicommMusicRgb";
    private static final float CHROMA_INCRE_STEP = 0.001f;
    private static final int CHROMA_GAIN = 4;
    private static final int LUMA_GAIN = 100;

    private final Context appContext;
    private final PhicommMusicLightFallback fallback;
    private Visualizer visualizer;
    private float hue;
    private float amp1;
    private float amp2;
    private float amp3;
    private boolean running;
    private boolean useJni;
    private float lastReportedAmp;

    PhicommMusicRgbEngine(Context context, PhicommMusicLightFallback fallback) {
        appContext = context.getApplicationContext();
        this.fallback = fallback;
        useJni = PhicommLedLightJni.isAvailable();
    }

    boolean start() {
        if (running) {
            return true;
        }
        useJni = PhicommLedLightJni.isAvailable();
        try {
            visualizer = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(range[range.length - 1]);
            int rate = Visualizer.getMaxCaptureRate();
            visualizer.setDataCaptureListener(this, (int) (rate / 1.17073f), false, true);
            visualizer.setEnabled(true);
            running = true;
            fallback.startEffect();
            Log.i(TAG, "Visualizer started session=0 backend=" + backendLabel());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Visualizer start failed — static msgcenter fallback", t);
            useJni = false;
            fallback.startEffect();
            running = true;
            return false;
        }
    }

    void stop() {
        running = false;
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable t) {
                Log.w(TAG, "Visualizer release failed", t);
            }
            visualizer = null;
        }
        fallback.clear();
        if (useJni) {
            PhicommLedLightJni.clearMusicRing();
        }
        lastReportedAmp = 0f;
    }

    String backendLabel() {
        return useJni ? "jni" : "msgcenter";
    }

    @Override
    public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
    }

    @Override
    public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
        if (!running || fft == null || fft.length < 4) {
            return;
        }
        float[] magnitudes = new float[(fft.length / 2) + 1];
        magnitudes[0] = Math.abs(fft[0]);
        int idx = 2;
        for (int j = 1; j < magnitudes.length - 1; j++) {
            magnitudes[j] = (float) Math.hypot(fft[idx], fft[idx + 1]);
            idx += 2;
        }

        float ampMax = 0f;
        for (int i = 1; i < magnitudes.length; i++) {
            if (magnitudes[i] > ampMax) {
                ampMax = magnitudes[i];
            }
        }

        float brightness = move5Avg(ampMax) / 180.0f;
        hue = (hue + (CHROMA_GAIN * CHROMA_INCRE_STEP)) % 1.0f;
        float scaled = brightness * (LUMA_GAIN / 100.0f);
        lastReportedAmp = scaled;

        if (useJni) {
            if (scaled <= 0.001f) {
                PhicommLedLightJni.clearVoiceLoadingRing();
            } else {
                int rgb = Color.HSVToColor(new float[] { hue, 1.0f, scaled });
                PhicommLedLightJni.setRingColor(rgb);
            }
        } else {
            fallback.updateAmplitude(scaled);
        }
    }

    float getLastAmplitude() {
        return lastReportedAmp;
    }

    private float move5Avg(float amp) {
        amp1 = amp2;
        amp2 = amp3;
        amp3 = amp;
        return (amp1 + amp2 + amp3) / 3.0f;
    }
}
