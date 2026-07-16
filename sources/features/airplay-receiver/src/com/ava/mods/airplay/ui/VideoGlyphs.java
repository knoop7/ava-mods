package com.ava.mods.airplay.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Material-Rounded-style glyphs for AirPlay HLS video chrome (upstream uses
 * Compose {@code Icons.Rounded.*}; we draw the same shapes into a 24×24 viewport).
 */
public final class VideoGlyphs {

    public static final int VIEWPORT = 24;

    private VideoGlyphs() {}

    public static Path arrowBack() {
        Path p = new Path();
        p.moveTo(20f, 11f);
        p.lineTo(7.83f, 11f);
        p.lineTo(13.42f, 5.41f);
        p.lineTo(12f, 4f);
        p.lineTo(4f, 12f);
        p.lineTo(12f, 20f);
        p.lineTo(13.41f, 18.59f);
        p.lineTo(7.83f, 13f);
        p.lineTo(20f, 13f);
        p.close();
        return p;
    }

    public static Path playArrow() {
        Path p = new Path();
        p.moveTo(8f, 5f);
        p.lineTo(8f, 19f);
        p.lineTo(19f, 12f);
        p.close();
        return p;
    }

    public static Path pause() {
        Path p = new Path();
        p.addRect(6f, 5f, 10f, 19f, Path.Direction.CW);
        p.addRect(14f, 5f, 18f, 19f, Path.Direction.CW);
        return p;
    }

    public static Path speed() {
        // Speedometer: arc + needle (approximates Icons.Rounded.Speed)
        Path p = new Path();
        p.addArc(new RectF(3f, 3f, 21f, 21f), 140f, 260f);
        p.moveTo(12f, 12f);
        p.lineTo(17.5f, 7.5f);
        p.lineTo(18.2f, 8.2f);
        p.lineTo(12.7f, 12.7f);
        p.close();
        p.addCircle(12f, 12f, 1.6f, Path.Direction.CW);
        return p;
    }

    public static Path contentCopy() {
        Path p = new Path();
        p.addRect(8f, 6f, 20f, 20f, Path.Direction.CW);
        p.moveTo(4f, 16f);
        p.lineTo(4f, 4f);
        p.lineTo(16f, 4f);
        p.lineTo(16f, 6f);
        p.lineTo(6f, 6f);
        p.lineTo(6f, 16f);
        p.close();
        return p;
    }

    public static Path download() {
        Path p = new Path();
        p.moveTo(5f, 20f);
        p.lineTo(19f, 20f);
        p.lineTo(19f, 18f);
        p.lineTo(5f, 18f);
        p.close();
        p.moveTo(12f, 16f);
        p.lineTo(5f, 9f);
        p.lineTo(9f, 9f);
        p.lineTo(9f, 3f);
        p.lineTo(15f, 3f);
        p.lineTo(15f, 9f);
        p.lineTo(19f, 9f);
        p.close();
        return p;
    }

    public static Path screenRotation() {
        Path p = new Path();
        p.moveTo(16.48f, 2.52f);
        p.lineTo(14.86f, 4.14f);
        p.cubicTo(18.22f, 5.55f, 20.5f, 8.82f, 20.5f, 12.5f);
        p.lineTo(23f, 12.5f);
        p.cubicTo(23f, 7.55f, 19.96f, 3.34f, 16.48f, 2.52f);
        p.close();
        p.moveTo(7.52f, 21.48f);
        p.lineTo(9.14f, 19.86f);
        p.cubicTo(5.78f, 18.45f, 3.5f, 15.18f, 3.5f, 11.5f);
        p.lineTo(1f, 11.5f);
        p.cubicTo(1f, 16.45f, 4.04f, 20.66f, 7.52f, 21.48f);
        p.close();
        p.addRect(7f, 6f, 17f, 18f, Path.Direction.CW);
        return p;
    }

    public static Path lockOpen() {
        Path p = new Path();
        p.addRect(6f, 10f, 18f, 22f, Path.Direction.CW);
        p.moveTo(8.9f, 10f);
        p.lineTo(8.9f, 6.5f);
        p.cubicTo(8.9f, 4.57f, 10.47f, 3f, 12.4f, 3f);
        p.cubicTo(14.33f, 3f, 15.9f, 4.57f, 15.9f, 6.5f);
        p.lineTo(15.9f, 8f);
        p.lineTo(17.9f, 8f);
        p.lineTo(17.9f, 6.5f);
        p.cubicTo(17.9f, 3.46f, 15.44f, 1f, 12.4f, 1f);
        p.cubicTo(9.36f, 1f, 6.9f, 3.46f, 6.9f, 6.5f);
        p.lineTo(6.9f, 10f);
        p.close();
        return p;
    }

    public static Path lock() {
        Path p = new Path();
        p.addRect(6f, 10f, 18f, 22f, Path.Direction.CW);
        p.moveTo(12f, 1f);
        p.cubicTo(9.24f, 1f, 7f, 3.24f, 7f, 6f);
        p.lineTo(7f, 10f);
        p.lineTo(9f, 10f);
        p.lineTo(9f, 6f);
        p.cubicTo(9f, 4.34f, 10.34f, 3f, 12f, 3f);
        p.cubicTo(13.66f, 3f, 15f, 4.34f, 15f, 6f);
        p.lineTo(15f, 10f);
        p.lineTo(17f, 10f);
        p.lineTo(17f, 6f);
        p.cubicTo(17f, 3.24f, 14.76f, 1f, 12f, 1f);
        p.close();
        return p;
    }

    public static Path fitScreen() {
        Path p = new Path();
        p.moveTo(3f, 5f);
        p.lineTo(3f, 9f);
        p.lineTo(5f, 9f);
        p.lineTo(5f, 7f);
        p.lineTo(7f, 7f);
        p.lineTo(7f, 5f);
        p.close();
        p.moveTo(5f, 15f);
        p.lineTo(3f, 15f);
        p.lineTo(3f, 19f);
        p.lineTo(7f, 19f);
        p.lineTo(7f, 17f);
        p.lineTo(5f, 17f);
        p.close();
        p.moveTo(17f, 5f);
        p.lineTo(17f, 7f);
        p.lineTo(19f, 7f);
        p.lineTo(19f, 9f);
        p.lineTo(21f, 9f);
        p.lineTo(21f, 5f);
        p.close();
        p.moveTo(19f, 17f);
        p.lineTo(17f, 17f);
        p.lineTo(17f, 19f);
        p.lineTo(21f, 19f);
        p.lineTo(21f, 15f);
        p.lineTo(19f, 15f);
        p.close();
        p.addRect(8f, 8f, 16f, 16f, Path.Direction.CW);
        return p;
    }

    public static Path aspectRatio() {
        Path p = new Path();
        p.addRect(4f, 6f, 20f, 18f, Path.Direction.CW);
        p.addRect(7f, 9f, 17f, 15f, Path.Direction.CW);
        return p;
    }

    public static Path cropLandscape() {
        Path p = new Path();
        p.addRect(3f, 7f, 21f, 17f, Path.Direction.CW);
        p.moveTo(19f, 3f);
        p.lineTo(19f, 5f);
        p.lineTo(21f, 5f);
        p.lineTo(21f, 3f);
        p.close();
        p.moveTo(3f, 19f);
        p.lineTo(5f, 19f);
        p.lineTo(5f, 21f);
        p.lineTo(3f, 21f);
        p.close();
        return p;
    }

    public static Path photoSize() {
        Path p = new Path();
        p.addRect(4f, 4f, 20f, 20f, Path.Direction.CW);
        p.addCircle(9.5f, 9.5f, 1.5f, Path.Direction.CW);
        p.moveTo(4f, 18f);
        p.lineTo(8f, 12f);
        p.lineTo(11f, 16f);
        p.lineTo(14.5f, 11f);
        p.lineTo(20f, 18f);
        p.close();
        return p;
    }

    public static Path pictureInPicture() {
        Path p = new Path();
        p.addRect(2f, 4f, 22f, 20f, Path.Direction.CW);
        p.addRect(12f, 11f, 20f, 18f, Path.Direction.CW);
        return p;
    }

    public static Path remove() {
        Path p = new Path();
        p.addRect(5f, 11f, 19f, 13f, Path.Direction.CW);
        return p;
    }

    public static Path add() {
        Path p = new Path();
        p.addRect(11f, 5f, 13f, 19f, Path.Direction.CW);
        p.addRect(5f, 11f, 19f, 13f, Path.Direction.CW);
        return p;
    }

    public static Path refresh() {
        Path p = new Path();
        p.moveTo(17.65f, 6.35f);
        p.cubicTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f);
        p.cubicTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f);
        p.cubicTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f);
        p.cubicTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f);
        p.lineTo(17.65f, 14f);
        p.cubicTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f);
        p.cubicTo(8.69f, 18f, 6f, 15.31f, 6f, 12f);
        p.cubicTo(6f, 8.69f, 8.69f, 6f, 12f, 6f);
        p.cubicTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f);
        p.lineTo(13f, 11f);
        p.lineTo(20f, 11f);
        p.lineTo(20f, 4f);
        p.close();
        return p;
    }

    /** 24dp Material viewport glyph, tinted white by default. */
    public static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float iconSizePx;
        private Path glyph;
        private boolean strokeOnly;

        public IconView(Context context, Path path, float iconSizeDp) {
            this(context, path, iconSizeDp, Color.WHITE);
        }

        public IconView(Context context, Path path, float iconSizeDp, int color) {
            super(context);
            this.glyph = path;
            this.iconSizePx = iconSizeDp * context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            setBackgroundColor(Color.TRANSPARENT);
        }

        public void setGlyph(Path path) {
            glyph = path;
            invalidate();
        }

        public void setStrokeOnly(boolean stroke) {
            strokeOnly = stroke;
            paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
            paint.setStrokeWidth(stroke ? 1.8f : 0f);
            invalidate();
        }

        public void setTint(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (glyph == null) return;
            float scale = iconSizePx / VIEWPORT;
            int saved = canvas.save();
            canvas.translate((getWidth() - iconSizePx) / 2f, (getHeight() - iconSizePx) / 2f);
            canvas.scale(scale, scale);
            canvas.drawPath(glyph, paint);
            canvas.restoreToCount(saved);
        }
    }
}
