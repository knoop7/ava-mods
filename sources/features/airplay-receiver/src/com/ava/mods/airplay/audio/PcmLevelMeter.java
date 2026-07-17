package com.ava.mods.airplay.audio;

/**
 * Lightweight PCM → multi-band levels for cinema bars.
 * <p>
 * AirPlay mods cannot reliably depend on {@code android.media.audiofx.Visualizer}
 * (needs {@code RECORD_AUDIO} + host grant). Tapping the decoded PCM16 stream
 * instead — same idea as most open-source bar visualizers, without the AAR.
 */
public final class PcmLevelMeter {

    private static final int BANDS = 32;
    private static final float SMOOTH = 0.72f;
    private static final float NORM = 1f / 9000f;

    private final float[] bands = new float[BANDS];
    private final float[] scratch = new float[BANDS];

    public interface Listener {
        void onLevels(float[] bands01);
    }

    public int bandCount() {
        return BANDS;
    }

    /** Feed interleaved stereo PCM16 LE; updates smoothed band energies in 0..1. */
    public synchronized void processStereoPcm16(byte[] pcm, int length) {
        if (pcm == null || length < 8) return;
        int frames = length / 4; // L+R 16-bit
        if (frames < BANDS) return;

        int chunk = frames / BANDS;
        for (int b = 0; b < BANDS; b++) {
            double acc = 0;
            int start = b * chunk;
            int end = start + chunk;
            for (int f = start; f < end; f++) {
                int i = f * 4;
                if (i + 3 >= length) break;
                short left = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                short right = (short) ((pcm[i + 2] & 0xff) | (pcm[i + 3] << 8));
                float m = 0.5f * (left + right);
                acc += m * m;
            }
            float rms = (float) Math.sqrt(acc / Math.max(1, chunk));
            // Mild log curve so soft tracks still move.
            float v = (float) Math.min(1.0, Math.log1p(rms * NORM * 12.0) / Math.log1p(12.0));
            // Favor mid/high bars slightly for a livelier look.
            float bias = 0.85f + 0.30f * (b / (float) (BANDS - 1));
            scratch[b] = Math.min(1f, v * bias);
        }

        for (int b = 0; b < BANDS; b++) {
            float target = scratch[b];
            if (target > bands[b]) {
                bands[b] = bands[b] * 0.35f + target * 0.65f; // attack
            } else {
                bands[b] = bands[b] * SMOOTH + target * (1f - SMOOTH); // release
            }
        }
    }

    public synchronized void copyBands(float[] out) {
        if (out == null || out.length < BANDS) return;
        System.arraycopy(bands, 0, out, 0, BANDS);
    }

    public synchronized void reset() {
        for (int i = 0; i < BANDS; i++) bands[i] = 0f;
    }
}
