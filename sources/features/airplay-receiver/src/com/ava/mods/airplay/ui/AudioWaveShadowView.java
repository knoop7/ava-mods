package com.ava.mods.airplay.ui;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * Centered background wave shadow — soft gradient + blur, not a hard edge.
 * Left/right levels are mirrored so the ridge stays visually centered.
 */
public final class AudioWaveShadowView extends View {

    private static final int POINTS = 32;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final float[] levels = new float[POINTS];
    private final float[] draw = new float[POINTS];
    private float energy;
    private float drawEnergy;
    private LinearGradient baseGrad;
    private int gradW;
    private int gradH;

    public AudioWaveShadowView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        // BlurMaskFilter requires software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        basePaint.setStyle(Paint.Style.FILL);
        wavePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    public void setLevels(float[] bands01) {
        if (bands01 == null || bands01.length == 0) return;
        // Resample then mirror L/R so the wave is always centered.
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

    private void ensureBase(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (baseGrad != null && gradW == w && gradH == h) return;
        gradW = w;
        gradH = h;
        // Soft cinema lift — always centered fade, no hard cut.
        baseGrad = new LinearGradient(
                0, h, 0, 0,
                new int[]{0xE6000000, 0x99000000, 0x40000000, 0x00000000},
                new float[]{0f, 0.42f, 0.78f, 1f},
                Shader.TileMode.CLAMP);
        basePaint.setShader(baseGrad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureBase(w, h);

        drawEnergy = drawEnergy * 0.85f + energy * 0.15f;
        for (int i = 0; i < POINTS; i++) {
            draw[i] = draw[i] * 0.78f + levels[i] * 0.22f;
        }

        // 1) Soft full bottom gradient (static chrome readability).
        canvas.drawRect(0, 0, w, h, basePaint);

        // 2) Centered radial glow — breathes with loudness (blurred puddle).
        float glowR = Math.max(w, h) * (0.55f + 0.25f * drawEnergy);
        float glowCy = h * (0.92f - 0.08f * drawEnergy);
        int glowA = Math.round(90 + 110 * drawEnergy);
        glowPaint.setShader(new RadialGradient(
                w * 0.5f, glowCy, glowR,
                new int[]{(glowA << 24), 0x00000000},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            glowPaint.setMaskFilter(new BlurMaskFilter(h * 0.08f, BlurMaskFilter.Blur.NORMAL));
        }
        canvas.drawCircle(w * 0.5f, glowCy, glowR, glowPaint);

        // 3) Soft blurred wave ridge (symmetric), feathered into the gradient.
        float baseY = h * (0.70f - 0.10f * drawEnergy);
        float amp = h * (0.06f + 0.20f * drawEnergy);
        buildWavePath(w, h, baseY, amp);

        // Draw several translucent blurred passes for 渐变模糊.
        float[] blurs = {h * 0.10f, h * 0.055f, h * 0.028f};
        int[] alphas = {
                Math.round(55 + 50 * drawEnergy),
                Math.round(70 + 70 * drawEnergy),
                Math.round(90 + 90 * drawEnergy)
        };
        for (int p = 0; p < blurs.length; p++) {
            wavePaint.setShader(new LinearGradient(
                    0, h, 0, baseY - amp,
                    new int[]{(alphas[p] << 24), (alphas[p] << 24) & 0x00FFFFFF},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                wavePaint.setMaskFilter(new BlurMaskFilter(blurs[p], BlurMaskFilter.Blur.NORMAL));
            }
            canvas.drawPath(wavePath, wavePaint);
        }
    }

    private void buildWavePath(int w, int h, float baseY, float amp) {
        wavePath.reset();
        wavePath.moveTo(0, h);
        float x0 = 0f;
        float y0 = sampleY(0, baseY, amp);
        wavePath.lineTo(x0, y0);
        for (int i = 1; i < POINTS; i++) {
            float x = (i / (float) (POINTS - 1)) * w;
            float y = sampleY(i, baseY, amp);
            float px = ((i - 1) / (float) (POINTS - 1)) * w;
            float py = sampleY(i - 1, baseY, amp);
            float cpx = (px + x) * 0.5f;
            float cpy = (py + y) * 0.5f;
            wavePath.quadTo(px, py, cpx, cpy);
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
        // Center-weighted envelope so sides stay lower → feels centered.
        float t = i / (float) (POINTS - 1);
        float center = 1f - Math.abs(t - 0.5f) * 1.35f;
        if (center < 0.35f) center = 0.35f;
        float carrier = (float) (Math.sin(i * 0.42 + drawEnergy * 1.6) * 0.04);
        return baseY - amp * (0.10f + smooth * 0.90f * center + carrier);
    }
}
