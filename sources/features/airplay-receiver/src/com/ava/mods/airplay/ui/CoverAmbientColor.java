package com.ava.mods.airplay.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

/**
 * Samples a usable ambient tint from album art (no Palette dependency).
 * Prefers saturated mid-tones; falls back to a muted warm gray — never pure black.
 */
public final class CoverAmbientColor {

    /** Soft fallback when cover is missing / too dark. */
    public static final int FALLBACK = 0xFF5A4E46;

    private CoverAmbientColor() {}

    public static int extract(Bitmap source) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return FALLBACK;
        }
        Bitmap soft = source;
        boolean copied = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && source.getConfig() == Bitmap.Config.HARDWARE) {
                soft = source.copy(Bitmap.Config.ARGB_8888, false);
                if (soft == null) return FALLBACK;
                copied = true;
            }
            int sw = soft.getWidth();
            int sh = soft.getHeight();
            int edge = 48;
            float scale = Math.min(edge / (float) sw, edge / (float) sh);
            if (scale > 1f) scale = 1f;
            int w = Math.max(1, Math.round(sw * scale));
            int h = Math.max(1, Math.round(sh * scale));
            Bitmap tiny = Bitmap.createScaledBitmap(soft, w, h, true);
            if (copied && soft != source) soft.recycle();
            soft = null;
            copied = false;

            int[] pixels = new int[w * h];
            tiny.getPixels(pixels, 0, w, 0, 0, w, h);
            if (tiny != source) tiny.recycle();

            float bestScore = -1f;
            int best = FALLBACK;
            double sumR = 0, sumG = 0, sumB = 0, sumW = 0;

            float[] hsv = new float[3];
            for (int p : pixels) {
                int a = (p >>> 24) & 0xff;
                if (a < 32) continue;
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                Color.RGBToHSV(r, g, b, hsv);
                float sat = hsv[1];
                float val = hsv[2];
                // Skip near-black / near-white — useless for tint.
                if (val < 0.12f || val > 0.94f) continue;
                if (sat < 0.08f && val < 0.35f) continue;

                float weight = 0.35f + sat * 1.4f + (val > 0.25f && val < 0.85f ? 0.35f : 0f);
                sumR += r * weight;
                sumG += g * weight;
                sumB += b * weight;
                sumW += weight;

                float score = sat * (0.55f + val * 0.45f);
                if (score > bestScore) {
                    bestScore = score;
                    best = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            if (sumW > 1e-3) {
                int ar = (int) Math.round(sumR / sumW);
                int ag = (int) Math.round(sumG / sumW);
                int ab = (int) Math.round(sumB / sumW);
                // Blend average with most-saturated pick for a richer tint.
                int br = Color.red(best);
                int bg = Color.green(best);
                int bb = Color.blue(best);
                int r = clamp((ar * 2 + br) / 3);
                int g = clamp((ag * 2 + bg) / 3);
                int b = clamp((ab * 2 + bb) / 3);
                return boostForAmbient(r, g, b);
            }
            if (bestScore >= 0f) {
                return boostForAmbient(Color.red(best), Color.green(best), Color.blue(best));
            }
            return FALLBACK;
        } catch (Throwable ignored) {
            if (copied && soft != null && soft != source && !soft.isRecycled()) {
                soft.recycle();
            }
            return FALLBACK;
        }
    }

    /** Lift / saturate slightly so the wash reads on dark UI. */
    private static int boostForAmbient(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.12f + 0.06f);
        hsv[2] = Math.max(0.28f, Math.min(0.72f, hsv[2] * 0.92f + 0.08f));
        return Color.HSVToColor(hsv);
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    public static int withAlpha(int rgb, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    public static int darken(int rgb, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        hsv[2] = Math.max(0.08f, hsv[2] * (1f - amount));
        return Color.HSVToColor(hsv);
    }

    public static int lighten(int rgb, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        hsv[2] = Math.min(1f, hsv[2] + (1f - hsv[2]) * amount);
        hsv[1] = Math.max(0f, hsv[1] * (1f - amount * 0.35f));
        return Color.HSVToColor(hsv);
    }
}
