package com.ava.mods.airplay.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Material-style glyphs for <b>video chrome only</b> (upstream Compose Icons.Rounded look).
 * <p>
 * Drawn via {@link Path} — DexClassLoader cannot inflate {@code <vector><group>} XML
 * ({@code InflateException: Class not found group}).
 * <p>
 * Path data taken verbatim from Google's official Material Icons Rounded 24 px SVGs
 * (https://fonts.gstatic.com/s/i/materialiconsround/{name}/v{n}/24px.svg).
 * <p>
 * Audio / cinema UI must not use these; it keeps DLNA text transport controls.
 */
public final class VideoIcons {

    private static final String TAG = "VideoIcons";
    private static final float VP = 24f;
    private static final Map<String, Path[]> PATHS = new HashMap<>();
    private static final Map<String, Drawable.ConstantState> CACHE = new HashMap<>();

    static {
        // Official Google Material Icons Rounded 24dp — verbatim SVG path data.
        // Some icons have two <path> elements (ignoring fill="none" bounding boxes).
        put("play_arrow",
            "M8 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18c.62-.39.62-1.29 0-1.69L9.54 5.98C8.87 5.55 8 6.03 8 6.82z");
        put("pause",
            "M8 19c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2v10c0 1.1.9 2 2 2zm6-12v10c0 1.1.9 2 2 2s2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2z");
        // Filled skip glyphs (match Ava / Material transport).
        put("skip_previous",
            "M6 6h2v12H6zm3.5 6 8.5 6V6z");
        put("skip_next",
            "M16 6h2v12h-2zM6 18l8.5-6L6 6z");
        put("arrow_back",
            "M19 11H7.83l4.88-4.88c.39-.39.39-1.03 0-1.42-.39-.39-1.02-.39-1.41 0l-6.59 6.59c-.39.39-.39 1.02 0 1.41l6.59 6.59c.39.39 1.02.39 1.41 0 .39-.39.39-1.02 0-1.41L7.83 13H19c.55 0 1-.45 1-1s-.45-1-1-1z");
        put("speed",
            "M19.46 10a1 1 0 0 0-.07 1 7.55 7.55 0 0 1 .52 1.81 8 8 0 0 1-.69 4.73 1 1 0 0 1-.89.53H5.68a1 1 0 0 1-.89-.54A8 8 0 0 1 13 6.06a7.69 7.69 0 0 1 2.11.56 1 1 0 0 0 1-.07 1 1 0 0 0-.17-1.76A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0 .55-8.89 1 1 0 0 0-1.75-.11z",
            "M10.59 12.59a2 2 0 0 0 2.83 2.83l5.66-8.49z");
        put("content_copy",
            "M15,20H5V7c0-0.55-0.45-1-1-1h0C3.45,6,3,6.45,3,7v13c0,1.1,0.9,2,2,2h10c0.55,0,1-0.45,1-1v0C16,20.45,15.55,20,15,20z M20,16V4c0-1.1-0.9-2-2-2H9C7.9,2,7,2.9,7,4v12c0,1.1,0.9,2,2,2h9C19.1,18,20,17.1,20,16z M18,16H9V4h9V16z");
        put("download",
            "M16.59 9H15V4c0-.55-.45-1-1-1h-4c-.55 0-1 .45-1 1v5H7.41c-.89 0-1.34 1.08-.71 1.71l4.59 4.59c.39.39 1.02.39 1.41 0l4.59-4.59c.63-.63.19-1.71-.7-1.71zM5 19c0 .55.45 1 1 1h12c.55 0 1-.45 1-1s-.45-1-1-1H6c-.55 0-1 .45-1 1z");
        put("screen_rotation",
            "M10.23 1.75c-.59-.59-1.54-.59-2.12 0L1.75 8.11c-.59.59-.59 1.54 0 2.12l12.02 12.02c.59.59 1.54.59 2.12 0l6.36-6.36c.59-.59.59-1.54 0-2.12L10.23 1.75zm3.89 18.73L3.52 9.88c-.39-.39-.39-1.02 0-1.41l4.95-4.95c.39-.39 1.02-.39 1.41 0l10.61 10.61c.39.39.39 1.02 0 1.41l-4.95 4.95c-.39.38-1.03.38-1.42-.01z",
            "M17.61 1.4C16.04.57 14.06-.03 11.81.02c-.18 0-.26.22-.14.35l3.48 3.48 1.33-1.33c3.09 1.46 5.34 4.37 5.89 7.86.06.41.44.69.86.62.41-.06.69-.45.62-.86-.6-3.8-2.96-7-6.24-8.74z",
            "M8.85 20.16l-1.33 1.33c-3.09-1.46-5.34-4.37-5.89-7.86-.06-.41-.44-.69-.86-.62-.41.06-.69.45-.62.86.6 3.81 2.96 7.01 6.24 8.75 1.57.83 3.55 1.43 5.8 1.38.18 0 .26-.22.14-.35l-3.48-3.49z");
        put("lock",
            "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM9 8V6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9z");
        put("lock_open",
            "M12 13c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6-5h-1V6c0-2.76-2.24-5-5-5-2.28 0-4.27 1.54-4.84 3.75-.14.54.18 1.08.72 1.22.53.14 1.08-.18 1.22-.72C9.44 3.93 10.63 3 12 3c1.65 0 3 1.35 3 3v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm0 11c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-8c0-.55.45-1 1-1h10c.55 0 1 .45 1 1v8z");
        put("picture_in_picture_alt",
            "M18 11h-6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1zm5 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-3 .02H4c-.55 0-1-.45-1-1V5.97c0-.55.45-1 1-1h16c.55 0 1 .45 1 1v12.05c0 .55-.45 1-1 1z");
        put("fit_screen",
            "M18,4h2c1.1,0,2,0.9,2,2v2c0,0.55-0.45,1-1,1h0c-0.55,0-1-0.45-1-1V6h-2c-0.55,0-1-0.45-1-1v0C17,4.45,17.45,4,18,4z M4,8l0-2h2c0.55,0,1-0.45,1-1v0c0-0.55-0.45-1-1-1H4C2.9,4,2,4.9,2,6l0,2c0,0.55,0.45,1,1,1h0C3.55,9,4,8.55,4,8z M20,16v2h-2c-0.55,0-1,0.45-1,1v0c0,0.55,0.45,1,1,1h2c1.1,0,2-0.9,2-2v-2c0-0.55-0.45-1-1-1h0C20.45,15,20,15.45,20,16z M6,18H4v-2c0-0.55-0.45-1-1-1h0c-0.55,0-1,0.45-1,1v2c0,1.1,0.9,2,2,2h2c0.55,0,1-0.45,1-1v0C7,18.45,6.55,18,6,18z M16,8H8c-1.1,0-2,0.9-2,2v4c0,1.1,0.9,2,2,2h8c1.1,0,2-0.9,2-2v-4C18,8.9,17.1,8,16,8z");
        put("crop_landscape",
            "M19 5H5c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-1 12H6c-.55 0-1-.45-1-1V8c0-.55.45-1 1-1h12c.55 0 1 .45 1 1v8c0 .55-.45 1-1 1z");
        put("aspect_ratio",
            "M18 12c-.55 0-1 .45-1 1v2h-2c-.55 0-1 .45-1 1s.45 1 1 1h3c.55 0 1-.45 1-1v-3c0-.55-.45-1-1-1zM7 9h2c.55 0 1-.45 1-1s-.45-1-1-1H6c-.55 0-1 .45-1 1v3c0 .55.45 1 1 1s1-.45 1-1V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-1 16.01H4c-.55 0-1-.45-1-1V5.99c0-.55.45-1 1-1h16c.55 0 1 .45 1 1v12.02c0 .55-.45 1-1 1z");
        put("photo_size_select_actual",
            "M21 3H3C2 3 1 4 1 5v14c0 1.1.9 2 2 2h18c1 0 2-1 2-2V5c0-1-1-2-2-2zM5.63 16.19l2.49-3.2c.2-.25.58-.26.78-.01l2.1 2.53 3.1-3.99c.2-.26.6-.26.8.01l3.51 4.68c.25.33.01.8-.4.8H6.02c-.41-.01-.65-.49-.39-.82z");
        put("add",
            "M18 13h-5v5c0 .55-.45 1-1 1s-1-.45-1-1v-5H6c-.55 0-1-.45-1-1s.45-1 1-1h5V6c0-.55.45-1 1-1s1 .45 1 1v5h5c.55 0 1 .45 1 1s-.45 1-1 1z");
        put("remove",
            "M18 13H6c-.55 0-1-.45-1-1s.45-1 1-1h12c.55 0 1 .45 1 1s-.45 1-1 1z");
        put("refresh",
            "M17.65 6.35c-1.63-1.63-3.94-2.57-6.48-2.31-3.67.37-6.69 3.35-7.1 7.02C3.52 15.91 7.27 20 12 20c3.19 0 5.93-1.87 7.21-4.56.32-.67-.16-1.44-.9-1.44-.37 0-.72.2-.88.53-1.13 2.43-3.84 3.97-6.8 3.31-2.22-.49-4.01-2.3-4.48-4.52C5.31 9.44 8.26 6 12 6c1.66 0 3.14.69 4.22 1.78l-1.51 1.51c-.63.63-.19 1.71.7 1.71H19c.55 0 1-.45 1-1V6.41c0-.89-1.08-1.34-1.71-.71l-.64.65z");
    }

    private VideoIcons() {}

    private static void put(String name, String... pathDataArr) {
        Path[] paths = new Path[pathDataArr.length];
        for (int p = 0; p < pathDataArr.length; p++) {
            paths[p] = PathParser.createPathFromPathData(pathDataArr[p]);
            if (paths[p] == null) {
                Log.w(TAG, "failed to parse glyph: " + name + " (part " + p + ")");
                return;
            }
        }
        PATHS.put(name, paths);
    }

    public static Drawable load(Context context, String name) {
        if (name == null) return null;
        String key = normalize(name);
        synchronized (CACHE) {
            Drawable.ConstantState cs = CACHE.get(key);
            if (cs != null) {
                Drawable d = cs.newDrawable();
                d.mutate();
                return d;
            }
        }
        Path[] paths = PATHS.get(key);
        if (paths == null) {
            Log.w(TAG, "missing glyph: " + key);
            return null;
        }
        Path combined = new Path();
        for (Path p : paths) combined.addPath(p);
        GlyphDrawable d = new GlyphDrawable(combined);
        synchronized (CACHE) {
            CACHE.put(key, d.getConstantState());
        }
        return d;
    }

    private static String normalize(String name) {
        String key = name.trim().toLowerCase(Locale.US);
        if (key.startsWith("ic_")) key = key.substring(3);
        if (key.endsWith(".xml")) key = key.substring(0, key.length() - 4);
        return key;
    }

    public static FrameLayout iconButton(Context context, String asset, final Runnable onClick) {
        return iconButton(context, asset, 48, 24, onClick);
    }

    public static FrameLayout iconButton(Context context, String asset,
                                        int touchDp, int iconDp, final Runnable onClick) {
        float density = context.getResources().getDisplayMetrics().density;
        int touch = Math.round(touchDp * density);
        int icon = Math.round(iconDp * density);
        FrameLayout wrap = new FrameLayout(context);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(touch, touch));
        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable d = load(context, asset);
        if (d != null) iv.setImageDrawable(d);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER);
        wrap.addView(iv, ilp);
        if (onClick != null) {
            wrap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClick.run();
                }
            });
        }
        wrap.setTag(iv);
        return wrap;
    }

    public static void setIcon(View iconButton, Context context, String asset) {
        if (!(iconButton instanceof FrameLayout)) return;
        View child = ((FrameLayout) iconButton).getChildAt(0);
        if (!(child instanceof ImageView)) return;
        Drawable d = load(context, asset);
        if (d != null) ((ImageView) child).setImageDrawable(d);
    }

    public static ImageView glyphView(Context context, String asset, int iconDp) {
        float density = context.getResources().getDisplayMetrics().density;
        int icon = Math.round(iconDp * density);
        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        Drawable d = load(context, asset);
        if (d != null) iv.setImageDrawable(d);
        return iv;
    }

    private static final class GlyphDrawable extends Drawable {
        private final Path src;
        private final Path scaled = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final ConstantState state;

        GlyphDrawable(Path src) {
            this.src = src;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            this.state = new ConstantState() {
                @Override public Drawable newDrawable() { return new GlyphDrawable(new Path(src)); }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            if (bounds.isEmpty()) return;
            matrix.reset();
            matrix.setScale(bounds.width() / VP, bounds.height() / VP);
            matrix.postTranslate(bounds.left, bounds.top);
            scaled.reset();
            src.transform(matrix, scaled);
        }

        @Override public void draw(Canvas canvas) { canvas.drawPath(scaled, paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public ConstantState getConstantState() { return state; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }

    /**
     * Full SVG pathData parser: M L H V C S Q T A Z (+ relative lowercase).
     * Handles implicit repeated commands, negative-sign separators, and multi-dot tokens.
     */
    static final class PathParser {
        private PathParser() {}

        static Path createPathFromPathData(String pathData) {
            if (pathData == null) return null;
            try {
                return parse(pathData);
            } catch (Throwable t) {
                Log.w(TAG, "path parse failed: " + t.getMessage());
                return null;
            }
        }

        private static final String CMDS = "MmLlHhVvCcSsQqTtAaZz";

        private static boolean isCmd(char c) { return CMDS.indexOf(c) >= 0; }

        private static Path parse(String s) {
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            float cx = 0, cy = 0, sx = 0, sy = 0;
            float lastCx2 = 0, lastCy2 = 0;
            char lastOp = ' ';
            int i = 0, n = s.length();
            char cmd = 'M';
            while (i < n) {
                i = skipWs(s, i, n);
                if (i >= n) break;
                if (isCmd(s.charAt(i))) {
                    cmd = s.charAt(i++);
                } else if (!isNumberStart(s, i)) {
                    i++;
                    continue;
                }
                boolean rel = Character.isLowerCase(cmd);
                char op = Character.toUpperCase(cmd);
                switch (op) {
                    case 'M': {
                        float[] a = readFloats(s, i, 2); i = (int) a[2];
                        float x = a[0], y = a[1];
                        if (rel) { x += cx; y += cy; }
                        path.moveTo(x, y);
                        cx = sx = x; cy = sy = y;
                        cmd = rel ? 'l' : 'L';
                        while (hasNumber(s, i, n)) {
                            a = readFloats(s, i, 2); i = (int) a[2];
                            x = a[0]; y = a[1];
                            if (rel) { x += cx; y += cy; }
                            path.lineTo(x, y);
                            cx = x; cy = y;
                        }
                        lastOp = 'M';
                        break;
                    }
                    case 'L': {
                        do {
                            float[] a = readFloats(s, i, 2); i = (int) a[2];
                            float x = a[0], y = a[1];
                            if (rel) { x += cx; y += cy; }
                            path.lineTo(x, y);
                            cx = x; cy = y;
                        } while (hasNumber(s, i, n));
                        lastOp = 'L';
                        break;
                    }
                    case 'H': {
                        do {
                            float[] a = readFloats(s, i, 1); i = (int) a[1];
                            float x = a[0];
                            if (rel) x += cx;
                            path.lineTo(x, cy);
                            cx = x;
                        } while (hasNumber(s, i, n));
                        lastOp = 'H';
                        break;
                    }
                    case 'V': {
                        do {
                            float[] a = readFloats(s, i, 1); i = (int) a[1];
                            float y = a[0];
                            if (rel) y += cy;
                            path.lineTo(cx, y);
                            cy = y;
                        } while (hasNumber(s, i, n));
                        lastOp = 'V';
                        break;
                    }
                    case 'C': {
                        do {
                            float[] a = readFloats(s, i, 6); i = (int) a[6];
                            float x1 = a[0], y1 = a[1], x2 = a[2], y2 = a[3], x = a[4], y = a[5];
                            if (rel) { x1+=cx; y1+=cy; x2+=cx; y2+=cy; x+=cx; y+=cy; }
                            path.cubicTo(x1, y1, x2, y2, x, y);
                            lastCx2 = x2; lastCy2 = y2;
                            cx = x; cy = y;
                        } while (hasNumber(s, i, n));
                        lastOp = 'C';
                        break;
                    }
                    case 'S': {
                        do {
                            float[] a = readFloats(s, i, 4); i = (int) a[4];
                            float x2 = a[0], y2 = a[1], x = a[2], y = a[3];
                            if (rel) { x2+=cx; y2+=cy; x+=cx; y+=cy; }
                            float x1, y1;
                            if (lastOp == 'C' || lastOp == 'S') {
                                x1 = 2*cx - lastCx2; y1 = 2*cy - lastCy2;
                            } else {
                                x1 = cx; y1 = cy;
                            }
                            path.cubicTo(x1, y1, x2, y2, x, y);
                            lastCx2 = x2; lastCy2 = y2;
                            cx = x; cy = y;
                            lastOp = 'S';
                        } while (hasNumber(s, i, n));
                        break;
                    }
                    case 'Q': {
                        do {
                            float[] a = readFloats(s, i, 4); i = (int) a[4];
                            float x1 = a[0], y1 = a[1], x = a[2], y = a[3];
                            if (rel) { x1+=cx; y1+=cy; x+=cx; y+=cy; }
                            path.quadTo(x1, y1, x, y);
                            lastCx2 = x1; lastCy2 = y1;
                            cx = x; cy = y;
                        } while (hasNumber(s, i, n));
                        lastOp = 'Q';
                        break;
                    }
                    case 'T': {
                        do {
                            float[] a = readFloats(s, i, 2); i = (int) a[2];
                            float x = a[0], y = a[1];
                            if (rel) { x+=cx; y+=cy; }
                            float x1, y1;
                            if (lastOp == 'Q' || lastOp == 'T') {
                                x1 = 2*cx - lastCx2; y1 = 2*cy - lastCy2;
                            } else {
                                x1 = cx; y1 = cy;
                            }
                            path.quadTo(x1, y1, x, y);
                            lastCx2 = x1; lastCy2 = y1;
                            cx = x; cy = y;
                            lastOp = 'T';
                        } while (hasNumber(s, i, n));
                        break;
                    }
                    case 'A': {
                        do {
                            float[] a = readFloats(s, i, 7); i = (int) a[7];
                            float rx = a[0], ry = a[1], xRot = a[2];
                            int largeArc = Math.round(a[3]), sweep = Math.round(a[4]);
                            float x = a[5], y = a[6];
                            if (rel) { x+=cx; y+=cy; }
                            arcToCubics(path, cx, cy, x, y, rx, ry, xRot, largeArc != 0, sweep != 0);
                            cx = x; cy = y;
                        } while (hasNumber(s, i, n));
                        lastOp = 'A';
                        break;
                    }
                    case 'Z': {
                        path.close();
                        cx = sx; cy = sy;
                        lastOp = 'Z';
                        break;
                    }
                    default:
                        lastOp = op;
                        break;
                }
                if (op != 'C' && op != 'S' && op != 'Q' && op != 'T') {
                    if (op != 'Z') lastOp = op;
                }
            }
            return path;
        }

        /** Convert SVG arc to cubic bezier segments. */
        private static void arcToCubics(Path path, float x1, float y1, float x2, float y2,
                                        float rx, float ry, float phi,
                                        boolean largeArc, boolean sweepFlag) {
            if (rx == 0 || ry == 0) { path.lineTo(x2, y2); return; }
            rx = Math.abs(rx); ry = Math.abs(ry);
            double phiRad = Math.toRadians(phi);
            double cosPhi = Math.cos(phiRad), sinPhi = Math.sin(phiRad);
            double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
            double x1p = cosPhi * dx2 + sinPhi * dy2;
            double y1p = -sinPhi * dx2 + cosPhi * dy2;
            double rxSq = (double) rx * rx, rySq = (double) ry * ry;
            double x1pSq = x1p * x1p, y1pSq = y1p * y1p;
            double lambda = x1pSq / rxSq + y1pSq / rySq;
            if (lambda > 1) {
                double s = Math.sqrt(lambda);
                rx *= s; ry *= s;
                rxSq = (double) rx * rx; rySq = (double) ry * ry;
            }
            double num = rxSq * rySq - rxSq * y1pSq - rySq * x1pSq;
            double den = rxSq * y1pSq + rySq * x1pSq;
            double sq = Math.max(0, num / den);
            double root = Math.sqrt(sq);
            if (largeArc == sweepFlag) root = -root;
            double cxp = root * rx * y1p / ry;
            double cyp = -root * ry * x1p / rx;
            double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
            double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;
            double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
            double dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                    (-x1p - cxp) / rx, (-y1p - cyp) / ry);
            if (!sweepFlag && dTheta > 0) dTheta -= 2 * Math.PI;
            else if (sweepFlag && dTheta < 0) dTheta += 2 * Math.PI;
            int segs = (int) Math.ceil(Math.abs(dTheta) / (Math.PI / 2.0));
            if (segs == 0) segs = 1;
            double segAngle = dTheta / segs;
            for (int seg = 0; seg < segs; seg++) {
                double t1 = theta1 + seg * segAngle;
                double t2 = t1 + segAngle;
                double alpha = Math.sin(segAngle) * (Math.sqrt(4 + 3 * Math.pow(Math.tan(segAngle / 2), 2)) - 1) / 3.0;
                double cosT1 = Math.cos(t1), sinT1 = Math.sin(t1);
                double cosT2 = Math.cos(t2), sinT2 = Math.sin(t2);
                double ep1x = rx * cosT1, ep1y = ry * sinT1;
                double ep2x = rx * cosT2, ep2y = ry * sinT2;
                double q1x = ep1x - alpha * rx * sinT1;
                double q1y = ep1y + alpha * ry * cosT1;
                double q2x = ep2x + alpha * rx * sinT2;
                double q2y = ep2y - alpha * ry * cosT2;
                float bx1 = (float)(cosPhi * q1x - sinPhi * q1y + cx);
                float by1 = (float)(sinPhi * q1x + cosPhi * q1y + cy);
                float bx2 = (float)(cosPhi * q2x - sinPhi * q2y + cx);
                float by2 = (float)(sinPhi * q2x + cosPhi * q2y + cy);
                float bx  = (float)(cosPhi * ep2x - sinPhi * ep2y + cx);
                float by  = (float)(sinPhi * ep2x + cosPhi * ep2y + cy);
                path.cubicTo(bx1, by1, bx2, by2, bx, by);
            }
        }

        private static double angle(double ux, double uy, double vx, double vy) {
            double dot = ux * vx + uy * vy;
            double len = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy);
            double cos = Math.max(-1, Math.min(1, dot / len));
            double a = Math.acos(cos);
            if (ux * vy - uy * vx < 0) a = -a;
            return a;
        }

        private static int skipWs(String s, int i, int n) {
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',')) i++;
            return i;
        }

        private static boolean isNumberStart(String s, int i) {
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
        }

        private static boolean hasNumber(String s, int i, int n) {
            i = skipWs(s, i, n);
            return i < n && isNumberStart(s, i);
        }

        private static float[] readFloats(String s, int start, int count) {
            float[] out = new float[count + 1];
            int i = start;
            int n = s.length();
            for (int k = 0; k < count; k++) {
                i = skipWs(s, i, n);
                int j = i;
                if (j < n && (s.charAt(j) == '-' || s.charAt(j) == '+')) j++;
                boolean seenDot = false;
                while (j < n) {
                    char c = s.charAt(j);
                    if (c >= '0' && c <= '9') { j++; }
                    else if (c == '.' && !seenDot) { seenDot = true; j++; }
                    else if (c == 'e' || c == 'E') {
                        j++;
                        if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                        while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') j++;
                        break;
                    }
                    else break;
                }
                if (j == i) {
                    if (i < n && s.charAt(i) == '-') {
                        j = i + 1;
                        while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') j++;
                    }
                }
                out[k] = j > i ? Float.parseFloat(s.substring(i, j)) : 0f;
                i = j;
            }
            out[count] = i;
            return out;
        }
    }
}
