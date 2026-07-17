package com.ava.mods.airplay.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Cover-tinted ambient wave wash.
 * Soft edges: low-res offscreen stack-blur of the tint blob (Ava AmbientBitmapBlur style),
 * not View software-layer + BlurMaskFilter (that OOMs / skips draw on phones).
 */
public final class AudioWaveShadowView extends View {

    private static final int POINTS = 28;
    private static final int BLUR_MAX_EDGE = 120;
    private static final int BLUR_RADIUS = 18;

    private final Paint ambientPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final float[] levels = new float[POINTS];
    private final float[] draw = new float[POINTS];
    private float energy;
    private float drawEnergy;

    private int ambientColor = CoverAmbientColor.FALLBACK;
    private int colorDark;
    private int colorMid;
    private int colorLight;
    private Bitmap softAmbient;
    private int softW;
    private int softH;
    private int softColor;

    public AudioWaveShadowView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        wavePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        applyDerivedColors(ambientColor);
    }

    /** Tint from album cover — rebuilds soft edge blob. */
    public void setAmbientFromCover(Bitmap cover) {
        setAmbientColor(CoverAmbientColor.extract(cover));
    }

    public void setAmbientColor(int color) {
        int rgb = (color & 0x00FFFFFF) == 0 ? CoverAmbientColor.FALLBACK : (0xFF000000 | (color & 0x00FFFFFF));
        if (rgb == ambientColor) return;
        ambientColor = rgb;
        applyDerivedColors(rgb);
        recycleSoft();
        invalidate();
    }

    public void clearAmbient() {
        setAmbientColor(CoverAmbientColor.FALLBACK);
    }

    private void applyDerivedColors(int rgb) {
        colorDark = CoverAmbientColor.darken(rgb, 0.38f);
        colorMid = rgb;
        colorLight = CoverAmbientColor.lighten(rgb, 0.28f);
    }

    public void setLevels(float[] bands01) {
        if (bands01 == null || bands01.length == 0) return;
        float[] raw = new float[POINTS];
        float sum = 0f;
        for (int i = 0; i < POINTS; i++) {
            float t = i / (float) (POINTS - 1);
            float src = t * (bands01.length - 1);
            int i0 = (int) src;
            int i1 = Math.min(bands01.length - 1, i0 + 1);
            float f = src - i0;
            float v = bands01[i0] * (1f - f) + bands01[i1] * f;
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            raw[i] = v;
            sum += v;
        }
        for (int i = 0; i < POINTS / 2; i++) {
            float m = (raw[i] + raw[POINTS - 1 - i]) * 0.5f;
            levels[i] = m;
            levels[POINTS - 1 - i] = m;
        }
        if ((POINTS & 1) == 1) {
            levels[POINTS / 2] = raw[POINTS / 2];
        }
        energy = sum / POINTS;
        invalidate();
    }

    public void clearLevels() {
        for (int i = 0; i < POINTS; i++) levels[i] = 0f;
        energy = 0f;
        invalidate();
    }

    private void ensureSoftAmbient(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (softAmbient != null && !softAmbient.isRecycled()
                && softW == w && softH == h && softColor == ambientColor) {
            return;
        }
        recycleSoft();
        softW = w;
        softH = h;
        softColor = ambientColor;

        // Tiny offscreen — fullscreen view but blur buffer stays small (no software-layer OOM).
        float scale = Math.min(BLUR_MAX_EDGE / (float) w, BLUR_MAX_EDGE / (float) h);
        if (scale > 1f) scale = 1f;
        int bw = Math.max(8, Math.round(w * scale));
        int bh = Math.max(8, Math.round(h * scale));
        Bitmap working = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(working);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Full-bleed cover wash: strongest at bottom, soft fade to clear at top.
        fill.setShader(new LinearGradient(
                0, bh, 0, 0,
                new int[]{
                        CoverAmbientColor.withAlpha(colorDark, 160),
                        CoverAmbientColor.withAlpha(colorMid, 110),
                        CoverAmbientColor.withAlpha(colorMid, 55),
                        CoverAmbientColor.withAlpha(colorLight, 22),
                        0x00000000
                },
                new float[]{0f, 0.28f, 0.52f, 0.78f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, fill);

        // Bottom center bloom — cover-tinted ambient element.
        fill.setShader(new RadialGradient(
                bw * 0.5f, bh * 0.88f, Math.max(bw, bh) * 0.72f,
                new int[]{
                        CoverAmbientColor.withAlpha(colorLight, 100),
                        CoverAmbientColor.withAlpha(colorMid, 40),
                        0x00000000
                },
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, fill);

        stackBlurInPlace(working, BLUR_RADIUS);
        softAmbient = working;
    }

    private void recycleSoft() {
        if (softAmbient != null && !softAmbient.isRecycled()) {
            softAmbient.recycle();
        }
        softAmbient = null;
        softW = 0;
        softH = 0;
        softColor = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        recycleSoft();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recycleSoft();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        drawEnergy = drawEnergy * 0.85f + energy * 0.15f;
        for (int i = 0; i < POINTS; i++) {
            draw[i] = draw[i] * 0.78f + levels[i] * 0.22f;
        }

        ensureSoftAmbient(w, h);

        // 1) Full-bleed soft cover wash
        if (softAmbient != null && !softAmbient.isRecycled()) {
            ambientPaint.setAlpha(Math.round(165 + 40 * drawEnergy));
            canvas.drawBitmap(softAmbient, null,
                    new android.graphics.RectF(0, 0, w, h), ambientPaint);
        }

        // 2) Live radial glow near bottom
        float glowR = Math.max(w, h) * (0.38f + 0.16f * drawEnergy);
        float glowCy = h * (0.92f - 0.04f * drawEnergy);
        int a0 = Math.round(60 + 50 * drawEnergy);
        int a1 = Math.round(22 + 28 * drawEnergy);
        glowPaint.setShader(new RadialGradient(
                w * 0.5f, glowCy, glowR,
                new int[]{
                        CoverAmbientColor.withAlpha(colorLight, a0),
                        CoverAmbientColor.withAlpha(colorMid, a1),
                        0x00000000
                },
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(w * 0.5f, glowCy, glowR, glowPaint);

        // 3) Wave body near bottom — cover tones
        float baseY = h * (0.82f - 0.06f * drawEnergy);
        float amp = h * (0.035f + 0.10f * drawEnergy);
        float[] lift = {0.08f, 0.045f, 0.018f};
        int[] alphas = {
                Math.round(50 + 45 * drawEnergy),
                Math.round(32 + 32 * drawEnergy),
                Math.round(18 + 22 * drawEnergy)
        };
        for (int p = 0; p < lift.length; p++) {
            buildWavePath(w, h, baseY - h * lift[p], amp * (1f - p * 0.18f));
            float top = baseY - amp - h * lift[p];
            int deep = CoverAmbientColor.withAlpha(colorDark, alphas[p]);
            int mid = CoverAmbientColor.withAlpha(colorMid, Math.round(alphas[p] * 0.55f));
            wavePaint.setShader(new LinearGradient(
                    0, h, 0, top,
                    new int[]{deep, mid, 0x00000000},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(wavePath, wavePaint);
        }
    }

    private void buildWavePath(int w, int h, float baseY, float amp) {
        wavePath.reset();
        wavePath.moveTo(0, h);
        wavePath.lineTo(0, sampleY(0, baseY, amp));
        for (int i = 1; i < POINTS; i++) {
            float x = (i / (float) (POINTS - 1)) * w;
            float y = sampleY(i, baseY, amp);
            float px = ((i - 1) / (float) (POINTS - 1)) * w;
            float py = sampleY(i - 1, baseY, amp);
            wavePath.quadTo(px, py, (px + x) * 0.5f, (py + y) * 0.5f);
        }
        wavePath.lineTo(w, sampleY(POINTS - 1, baseY, amp));
        wavePath.lineTo(w, h);
        wavePath.close();
    }

    private float sampleY(int i, float baseY, float amp) {
        float prev = draw[Math.max(0, i - 1)];
        float cur = draw[i];
        float next = draw[Math.min(POINTS - 1, i + 1)];
        float smooth = prev * 0.25f + cur * 0.5f + next * 0.25f;
        float t = i / (float) (POINTS - 1);
        float center = 1f - Math.abs(t - 0.5f) * 1.35f;
        if (center < 0.35f) center = 0.35f;
        float carrier = (float) (Math.sin(i * 0.42 + drawEnergy * 1.6) * 0.04);
        return baseY - amp * (0.10f + smooth * 0.90f * center + carrier);
    }

    /** Mario Klingemann stack blur — tiny bitmaps only. */
    private static void stackBlurInPlace(Bitmap bitmap, int radius) {
        if (radius < 1) return;
        if (radius > 25) radius = 25;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        int div = radius + radius + 1;
        int divsum = (div + 1) >> 1;
        int divsumSq = divsum * divsum;
        int[] dv = new int[256 * divsumSq];
        for (int i = 0; i < dv.length; i++) dv[i] = i / divsumSq;

        int[] r = new int[w * h];
        int[] g = new int[w * h];
        int[] b = new int[w * h];
        int[] a = new int[w * h];
        int[] vmin = new int[Math.max(w, h)];
        int[] stack = new int[div * 4];

        int yi = 0;
        for (int y = 0; y < h; y++) {
            int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            int rOut = 0, gOut = 0, bOut = 0, aOut = 0;
            int rIn = 0, gIn = 0, bIn = 0, aIn = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pixels[yi + Math.min(w - 1, Math.max(i, 0))];
                int si = (i + radius) * 4;
                stack[si] = (p >> 16) & 0xff;
                stack[si + 1] = (p >> 8) & 0xff;
                stack[si + 2] = p & 0xff;
                stack[si + 3] = (p >>> 24) & 0xff;
                int rbs = radius + 1 - Math.abs(i);
                rSum += stack[si] * rbs;
                gSum += stack[si + 1] * rbs;
                bSum += stack[si + 2] * rbs;
                aSum += stack[si + 3] * rbs;
                if (i > 0) {
                    rIn += stack[si];
                    gIn += stack[si + 1];
                    bIn += stack[si + 2];
                    aIn += stack[si + 3];
                } else {
                    rOut += stack[si];
                    gOut += stack[si + 1];
                    bOut += stack[si + 2];
                    aOut += stack[si + 3];
                }
            }
            int stackPointer = radius;
            for (int x = 0; x < w; x++) {
                r[yi + x] = dv[rSum];
                g[yi + x] = dv[gSum];
                b[yi + x] = dv[bSum];
                a[yi + x] = dv[aSum];

                rSum -= rOut;
                gSum -= gOut;
                bSum -= bOut;
                aSum -= aOut;

                int stackStart = ((stackPointer - radius + div) % div) * 4;
                rOut -= stack[stackStart];
                gOut -= stack[stackStart + 1];
                bOut -= stack[stackStart + 2];
                aOut -= stack[stackStart + 3];

                if (y == 0) vmin[x] = Math.min(x + radius + 1, w - 1);
                int p = pixels[yi + vmin[x]];
                stack[stackStart] = (p >> 16) & 0xff;
                stack[stackStart + 1] = (p >> 8) & 0xff;
                stack[stackStart + 2] = p & 0xff;
                stack[stackStart + 3] = (p >>> 24) & 0xff;

                rIn += stack[stackStart];
                gIn += stack[stackStart + 1];
                bIn += stack[stackStart + 2];
                aIn += stack[stackStart + 3];

                rSum += rIn;
                gSum += gIn;
                bSum += bIn;
                aSum += aIn;

                stackPointer = (stackPointer + 1) % div;
                int sir = stackPointer * 4;
                rOut += stack[sir];
                gOut += stack[sir + 1];
                bOut += stack[sir + 2];
                aOut += stack[sir + 3];
                rIn -= stack[sir];
                gIn -= stack[sir + 1];
                bIn -= stack[sir + 2];
                aIn -= stack[sir + 3];
            }
            yi += w;
        }

        for (int x = 0; x < w; x++) {
            int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            int rOut = 0, gOut = 0, bOut = 0, aOut = 0;
            int rIn = 0, gIn = 0, bIn = 0, aIn = 0;
            int yp = -radius * w;
            for (int i = -radius; i <= radius; i++) {
                int yi2 = Math.max(0, yp) + x;
                int si = (i + radius) * 4;
                stack[si] = r[yi2];
                stack[si + 1] = g[yi2];
                stack[si + 2] = b[yi2];
                stack[si + 3] = a[yi2];
                int rbs = radius + 1 - Math.abs(i);
                rSum += r[yi2] * rbs;
                gSum += g[yi2] * rbs;
                bSum += b[yi2] * rbs;
                aSum += a[yi2] * rbs;
                if (i > 0) {
                    rIn += stack[si];
                    gIn += stack[si + 1];
                    bIn += stack[si + 2];
                    aIn += stack[si + 3];
                } else {
                    rOut += stack[si];
                    gOut += stack[si + 1];
                    bOut += stack[si + 2];
                    aOut += stack[si + 3];
                }
                if (i < h - 1) yp += w;
            }
            int yi3 = x;
            int stackPointer = radius;
            for (int y = 0; y < h; y++) {
                pixels[yi3] = (dv[aSum] << 24) | (dv[rSum] << 16) | (dv[gSum] << 8) | dv[bSum];

                rSum -= rOut;
                gSum -= gOut;
                bSum -= bOut;
                aSum -= aOut;

                int stackStart = ((stackPointer - radius + div) % div) * 4;
                rOut -= stack[stackStart];
                gOut -= stack[stackStart + 1];
                bOut -= stack[stackStart + 2];
                aOut -= stack[stackStart + 3];

                if (x == 0) vmin[y] = Math.min(y + radius + 1, h - 1) * w;
                int p = x + vmin[y];
                stack[stackStart] = r[p];
                stack[stackStart + 1] = g[p];
                stack[stackStart + 2] = b[p];
                stack[stackStart + 3] = a[p];

                rIn += stack[stackStart];
                gIn += stack[stackStart + 1];
                bIn += stack[stackStart + 2];
                aIn += stack[stackStart + 3];

                rSum += rIn;
                gSum += gIn;
                bSum += bIn;
                aSum += aIn;

                stackPointer = (stackPointer + 1) % div;
                int sir = stackPointer * 4;
                rOut += stack[sir];
                gOut += stack[sir + 1];
                bOut += stack[sir + 2];
                aOut += stack[sir + 3];
                rIn -= stack[sir];
                gIn -= stack[sir + 1];
                bIn -= stack[sir + 2];
                aIn -= stack[sir + 3];

                yi3 += w;
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
}
