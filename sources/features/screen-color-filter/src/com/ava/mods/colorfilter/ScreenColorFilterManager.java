package com.ava.mods.colorfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScreenColorFilterManager {

    private static final String TAG = "ScreenColorFilter";
    private static final String PREFS = "screen_color_filter";
    private static final String KEY_COLOR = "filter_color";
    private static final String KEY_OPACITY = "opacity_percent";
    private static final String ENTITY_COLOR_FILTER = "color_filter";
    private static final String OFF = "off";
    private static final int DEFAULT_OPACITY_PERCENT = 30;

    private static final List<String> OPTIONS = Arrays.asList(
            OFF, "red", "blue", "dark", "yellow", "green", "gray"
    );
    private static final Set<String> OPTION_SET = new HashSet<>(OPTIONS);

    private static volatile ScreenColorFilterManager instance;

    private final Context context;
    private final Handler mainHandler;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Object> colorFilterListeners = new CopyOnWriteArrayList<>();

    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private String currentColor = OFF;
    private int opacityPercent = DEFAULT_OPACITY_PERCENT;

    private ScreenColorFilterManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.currentColor = normalizeColor(prefs.getString(KEY_COLOR, OFF));
        this.opacityPercent = clampOpacity(prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY_PERCENT));
        applyFilterOnMainThread();
    }

    public static ScreenColorFilterManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ScreenColorFilterManager.class) {
                if (instance == null) {
                    instance = new ScreenColorFilterManager(context);
                }
            }
        }
        return instance;
    }

    public String getFilterColor() {
        return currentColor;
    }

    public void setFilterColor(String color) {
        String normalized = normalizeColor(color);
        currentColor = normalized;
        prefs.edit().putString(KEY_COLOR, normalized).apply();
        applyFilterOnMainThread();
        notifyColorFilterListeners(normalized);
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        if ("opacity".equals(key)) {
            int next = clampOpacity(parseInt(value, DEFAULT_OPACITY_PERCENT));
            if (next != opacityPercent) {
                opacityPercent = next;
                prefs.edit().putInt(KEY_OPACITY, next).apply();
                applyFilterOnMainThread();
            }
        }
    }

    /**
     * Called by Ava core after foreground overlays are reasserted so the filter stays globally on top.
     */
    public void bringOverlayToFrontIfActive(Context ignoredContext) {
        if (OFF.equals(currentColor)) {
            return;
        }
        runOnMain(this::bringOverlayToFrontInternal);
    }

    public void onDestroy() {
        runOnMain(this::removeOverlayInternal);
        synchronized (ScreenColorFilterManager.class) {
            instance = null;
        }
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (!ENTITY_COLOR_FILTER.equals(entityId) || callback == null) {
            return false;
        }
        if (!colorFilterListeners.contains(callback)) {
            colorFilterListeners.add(callback);
        }
        notifySingleListener(callback, currentColor);
        return true;
    }

    private void applyFilterOnMainThread() {
        runOnMain(this::applyFilterInternal);
    }

    private void applyFilterInternal() {
        if (OFF.equals(currentColor) || opacityPercent <= 0) {
            removeOverlayInternal();
            return;
        }
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission not granted; screen filter not shown");
            return;
        }

        int color = resolveOverlayColor(currentColor, opacityPercent);
        if (overlayView == null) {
            overlayView = new View(context);
            overlayParams = createLayoutParams();
            overlayView.setBackgroundColor(color);
            try {
                windowManager.addView(overlayView, overlayParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add screen filter overlay", e);
                overlayView = null;
                overlayParams = null;
            }
            return;
        }

        overlayView.setBackgroundColor(color);
        if (!overlayView.isAttachedToWindow()) {
            try {
                windowManager.addView(overlayView, overlayParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to reattach screen filter overlay", e);
            }
        }
    }

    private void bringOverlayToFrontInternal() {
        if (overlayView == null || overlayParams == null || !overlayView.isAttachedToWindow()) {
            return;
        }
        try {
            int visibility = overlayView.getVisibility();
            windowManager.removeView(overlayView);
            windowManager.addView(overlayView, overlayParams);
            overlayView.setVisibility(visibility);
        } catch (Exception e) {
            Log.w(TAG, "Failed to bring screen filter overlay to front", e);
        }
    }

    private void removeOverlayInternal() {
        if (overlayView == null) {
            return;
        }
        try {
            if (overlayView.isAttachedToWindow()) {
                windowManager.removeView(overlayView);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove screen filter overlay", e);
        }
        overlayView = null;
        overlayParams = null;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private int resolveOverlayColor(String filterColor, int opacity) {
        int alpha = Math.round((opacity / 100f) * 255f);
        switch (filterColor) {
            case "red":
                return Color.argb(alpha, 255, 0, 0);
            case "blue":
                return Color.argb(alpha, 0, 0, 255);
            case "dark":
                return Color.argb(alpha, 0, 0, 0);
            case "yellow":
                return Color.argb(alpha, 255, 255, 0);
            case "green":
                return Color.argb(alpha, 0, 255, 0);
            case "gray":
                return Color.argb(alpha, 128, 128, 128);
            default:
                return Color.TRANSPARENT;
        }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }

    private String normalizeColor(String color) {
        if (color == null) {
            return OFF;
        }
        String normalized = color.trim().toLowerCase();
        return OPTION_SET.contains(normalized) ? normalized : OFF;
    }

    private int clampOpacity(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void notifyColorFilterListeners(String value) {
        for (Object listener : colorFilterListeners) {
            notifySingleListener(listener, value);
        }
    }

    private void notifySingleListener(Object listener, Object value) {
        try {
            Method method = listener.getClass().getMethod("onStateChanged", Object.class);
            method.invoke(listener, value);
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify state listener", e);
        }
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }
}
