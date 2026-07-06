package com.ava.mods.stickynote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Small draggable sticky-note window. Uses TYPE_APPLICATION_OVERLAY with WRAP_CONTENT
 * so only the note intercepts touches; the rest of the screen stays interactive.
 */
final class StickyNoteOverlay {

    private static final String TAG = "StickyNoteOverlay";
    private static final float DEFAULT_POS_RATIO = 0.5f;

    interface PositionListener {
        void onPositionChanged(float centerXRatio, float centerYRatio);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final PositionListener positionListener;

    private FrameLayout rootView;
    private TextView textView;
    private WindowManager.LayoutParams layoutParams;
    private float posXRatio = DEFAULT_POS_RATIO;
    private float posYRatio = DEFAULT_POS_RATIO;
    private int screenWidth;
    private int screenHeight;
    private float density;
    private boolean visible;

    private float dragTouchX;
    private float dragTouchY;
    private int dragStartParamX;
    private int dragStartParamY;

    StickyNoteOverlay(Context context, PositionListener positionListener) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.positionListener = positionListener;
        readDisplayMetrics();
    }

    void setPositionRatios(float xRatio, float yRatio) {
        posXRatio = clampRatio(xRatio);
        posYRatio = clampRatio(yRatio);
        if (visible) {
            applyPositionFromRatios();
        }
    }

    float getPosXRatio() {
        return posXRatio;
    }

    float getPosYRatio() {
        return posYRatio;
    }

    boolean isVisible() {
        return visible;
    }

    void show(String message, int backgroundColor, int textColor) {
        if (message == null || message.trim().isEmpty()) {
            hide();
            return;
        }
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission not granted; sticky note not shown");
            return;
        }

        readDisplayMetrics();
        ensureViewTree();
        applyTheme(backgroundColor, textColor);
        textView.setText(message.trim());

        if (!visible) {
            layoutParams = createLayoutParams();
            try {
                windowManager.addView(rootView, layoutParams);
                visible = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to add sticky note overlay", e);
                resetState();
                return;
            }
        }

        rootView.setVisibility(View.VISIBLE);
        rootView.post(this::applyPositionFromRatios);
    }

    void updateTheme(int backgroundColor, int textColor) {
        if (!visible || textView == null) {
            return;
        }
        applyTheme(backgroundColor, textColor);
    }

    void hide() {
        if (!visible || rootView == null) {
            visible = false;
            return;
        }
        try {
            if (rootView.isAttachedToWindow()) {
                windowManager.removeView(rootView);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove sticky note overlay", e);
        }
        rootView = null;
        textView = null;
        layoutParams = null;
        visible = false;
    }

    void bringToFrontIfActive() {
        if (!visible || rootView == null || layoutParams == null || !rootView.isAttachedToWindow()) {
            return;
        }
        try {
            int visibility = rootView.getVisibility();
            float alpha = rootView.getAlpha();
            windowManager.removeView(rootView);
            windowManager.addView(rootView, layoutParams);
            rootView.setVisibility(visibility);
            rootView.setAlpha(alpha);
        } catch (Exception e) {
            Log.w(TAG, "Failed to bring sticky note to front", e);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void ensureViewTree() {
        if (rootView != null) {
            return;
        }

        float minDim = Math.min(screenWidth, screenHeight);
        float textSp = (minDim / density) * 0.042f;
        int paddingPx = Math.round(textSp * density * 0.65f);
        int cornerPx = Math.round(12f * density);
        int maxWidthPx = Math.round(screenWidth * 0.78f);

        rootView = new FrameLayout(context);
        textView = new TextView(context);
        textView.setMaxWidth(maxWidthPx);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textView.setLineSpacing(0f, 1.12f);
        textView.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        textView.setIncludeFontPadding(false);

        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        rootView.addView(textView, textLp);

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(cornerPx);
        rootView.setBackground(background);

        rootView.setOnTouchListener((v, event) -> {
            if (layoutParams == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragTouchX = event.getRawX();
                    dragTouchY = event.getRawY();
                    dragStartParamX = layoutParams.x;
                    dragStartParamY = layoutParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = dragStartParamX + Math.round(event.getRawX() - dragTouchX);
                    layoutParams.y = dragStartParamY + Math.round(event.getRawY() - dragTouchY);
                    clampLayoutPosition();
                    try {
                        windowManager.updateViewLayout(rootView, layoutParams);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to update sticky note position", e);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePositionFromLayout();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void applyTheme(int backgroundColor, int textColor) {
        if (rootView == null || textView == null) {
            return;
        }
        if (rootView.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) rootView.getBackground()).setColor(backgroundColor);
        }
        textView.setTextColor(textColor);
    }

    private void applyPositionFromRatios() {
        if (rootView == null || layoutParams == null) {
            return;
        }
        int width = rootView.getWidth();
        int height = rootView.getHeight();
        if (width <= 0 || height <= 0) {
            rootView.post(this::applyPositionFromRatios);
            return;
        }
        layoutParams.x = Math.round(posXRatio * screenWidth - width / 2f);
        layoutParams.y = Math.round(posYRatio * screenHeight - height / 2f);
        clampLayoutPosition();
        try {
            windowManager.updateViewLayout(rootView, layoutParams);
        } catch (Exception e) {
            Log.w(TAG, "Failed to position sticky note", e);
        }
    }

    private void savePositionFromLayout() {
        if (rootView == null || layoutParams == null) {
            return;
        }
        int width = rootView.getWidth();
        int height = rootView.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float centerX = layoutParams.x + width / 2f;
        float centerY = layoutParams.y + height / 2f;
        posXRatio = clampRatio(centerX / screenWidth);
        posYRatio = clampRatio(centerY / screenHeight);
        if (positionListener != null) {
            positionListener.onPositionChanged(posXRatio, posYRatio);
        }
    }

    private void clampLayoutPosition() {
        if (rootView == null || layoutParams == null) {
            return;
        }
        int width = Math.max(rootView.getWidth(), 1);
        int height = Math.max(rootView.getHeight(), 1);
        int maxX = Math.max(0, screenWidth - width);
        int maxY = Math.max(0, screenHeight - height);
        if (layoutParams.x < 0) {
            layoutParams.x = 0;
        } else if (layoutParams.x > maxX) {
            layoutParams.x = maxX;
        }
        if (layoutParams.y < 0) {
            layoutParams.y = 0;
        } else if (layoutParams.y > maxY) {
            layoutParams.y = maxY;
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private void readDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
            } else {
                windowManager.getDefaultDisplay().getMetrics(metrics);
            }
        }
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        density = metrics.density;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }

    private void resetState() {
        rootView = null;
        textView = null;
        layoutParams = null;
        visible = false;
    }

    private static float clampRatio(float value) {
        if (value < 0.08f) {
            return 0.08f;
        }
        if (value > 0.92f) {
            return 0.92f;
        }
        return value;
    }
}
