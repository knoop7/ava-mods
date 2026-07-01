package com.ava.mods.colorfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Global screen tint overlay. Window flags and layout follow Ava overlay services
 * (WeatherOverlayService, QuickEntityOverlayService, WebViewService):
 * MATCH_PARENT, FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_FULLSCREEN,
 * translucent system bars, SHORT_EDGES cutout, gravity TOP|START at (0,0).
 */
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

    private FrameLayout overlayRoot;
    private View tintView;
    private WindowManager.LayoutParams overlayParams;
    private String currentColor = OFF;
    private int opacityPercent = DEFAULT_OPACITY_PERCENT;
    private int realWidth;
    private int realHeight;

    private ScreenColorFilterManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.currentColor = normalizeColor(prefs.getString(KEY_COLOR, OFF));
        this.opacityPercent = clampOpacity(prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY_PERCENT));
        readRealDisplayMetrics();
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

    private void readRealDisplayMetrics() {
        DisplayMetrics realMetrics = new DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.getDefaultDisplay().getRealMetrics(realMetrics);
            } else {
                windowManager.getDefaultDisplay().getMetrics(realMetrics);
            }
        }
        realWidth = realMetrics.widthPixels;
        realHeight = realMetrics.heightPixels;
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

        readRealDisplayMetrics();
        int color = resolveOverlayColor(currentColor, opacityPercent);

        if (overlayRoot == null) {
            overlayRoot = new FrameLayout(context);
            tintView = new View(context);
            tintView.setBackgroundColor(color);
            overlayRoot.addView(
                    tintView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    )
            );
            overlayParams = createLayoutParams();
            try {
                windowManager.addView(overlayRoot, overlayParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add screen filter overlay", e);
                overlayRoot = null;
                tintView = null;
                overlayParams = null;
            }
            return;
        }

        tintView.setBackgroundColor(color);
        if (!overlayRoot.isAttachedToWindow()) {
            overlayParams = createLayoutParams();
            try {
                windowManager.addView(overlayRoot, overlayParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to reattach screen filter overlay", e);
            }
        }
    }

    private void bringOverlayToFrontInternal() {
        if (overlayRoot == null || overlayParams == null || !overlayRoot.isAttachedToWindow()) {
            return;
        }
        try {
            int visibility = overlayRoot.getVisibility();
            float alpha = overlayRoot.getAlpha();
            windowManager.removeView(overlayRoot);
            windowManager.addView(overlayRoot, overlayParams);
            overlayRoot.setVisibility(visibility);
            overlayRoot.setAlpha(alpha);
        } catch (Exception e) {
            Log.w(TAG, "Failed to bring screen filter overlay to front", e);
        }
    }

    private void removeOverlayInternal() {
        if (overlayRoot == null) {
            return;
        }
        try {
            if (overlayRoot.isAttachedToWindow()) {
                windowManager.removeView(overlayRoot);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove screen filter overlay", e);
        }
        overlayRoot = null;
        tintView = null;
        overlayParams = null;
    }

    /**
     * Same full-screen overlay contract as WeatherOverlayService / QuickEntityOverlayService.
     * Adds FLAG_NOT_TOUCHABLE so the tint layer does not intercept touches.
     */
    private WindowManager.LayoutParams createLayoutParams() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
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
