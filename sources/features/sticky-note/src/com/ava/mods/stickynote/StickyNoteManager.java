package com.ava.mods.stickynote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * On-screen sticky note controlled from Home Assistant. Overlay uses {@code overlay_z_order}
 * so it stays above Ava voice and notification layers, same tier as Screen Color Filter.
 */
public class StickyNoteManager {

    private static final String TAG = "StickyNoteManager";
    private static final String PREFS = "sticky_note_mod";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_COLOR = "color";
    private static final String KEY_POS_X = "pos_x_ratio";
    private static final String KEY_POS_Y = "pos_y_ratio";
    private static final String ENTITY_MESSAGE = "message";
    private static final String ENTITY_COLOR = "color";
    private static final String DEFAULT_COLOR = "yellow";

    private static final List<String> COLOR_OPTIONS = Arrays.asList(
            "yellow", "pink", "blue", "green", "orange", "purple", "dark"
    );
    private static final Set<String> COLOR_OPTION_SET = new HashSet<>(COLOR_OPTIONS);

    private static volatile StickyNoteManager instance;

    private final Context context;
    private final Handler mainHandler;
    private final SharedPreferences prefs;
    private final StickyNoteOverlay overlay;
    private final CopyOnWriteArrayList<Object> messageListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Object> colorListeners = new CopyOnWriteArrayList<>();

    private String message = "";
    private String color = DEFAULT_COLOR;

    private StickyNoteManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.message = prefs.getString(KEY_MESSAGE, "");
        this.color = normalizeColor(prefs.getString(KEY_COLOR, DEFAULT_COLOR));
        float posX = prefs.getFloat(KEY_POS_X, 0.5f);
        float posY = prefs.getFloat(KEY_POS_Y, 0.5f);
        this.overlay = new StickyNoteOverlay(this.context, (centerXRatio, centerYRatio) ->
                prefs.edit()
                        .putFloat(KEY_POS_X, centerXRatio)
                        .putFloat(KEY_POS_Y, centerYRatio)
                        .apply()
        );
        this.overlay.setPositionRatios(posX, posY);
        applyOnMainThread();
    }

    public static StickyNoteManager getInstance(Context context) {
        if (instance == null) {
            synchronized (StickyNoteManager.class) {
                if (instance == null) {
                    instance = new StickyNoteManager(context);
                }
            }
        }
        return instance;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String text) {
        String next = text == null ? "" : text;
        message = next;
        prefs.edit().putString(KEY_MESSAGE, next).apply();
        applyOnMainThread();
        notifyMessageListeners(next);
    }

    public String getColor() {
        return color;
    }

    public void setColor(String theme) {
        String normalized = normalizeColor(theme);
        if (normalized.equals(color)) {
            return;
        }
        color = normalized;
        prefs.edit().putString(KEY_COLOR, normalized).apply();
        applyOnMainThread();
        notifyColorListeners(normalized);
    }

    public void clearNote() {
        setMessage("");
    }

    /**
     * Called by Ava core so the note stays globally on top with other overlay_z_order mods.
     */
    public void bringOverlayToFrontIfActive(Context ignoredContext) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        runOnMain(overlay::bringToFrontIfActive);
    }

    public void onDestroy() {
        runOnMain(overlay::hide);
        synchronized (StickyNoteManager.class) {
            instance = null;
        }
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (callback == null) {
            return false;
        }
        if (ENTITY_MESSAGE.equals(entityId)) {
            if (!messageListeners.contains(callback)) {
                messageListeners.add(callback);
            }
            notifySingleListener(callback, message);
            return true;
        }
        if (ENTITY_COLOR.equals(entityId)) {
            if (!colorListeners.contains(callback)) {
                colorListeners.add(callback);
            }
            notifySingleListener(callback, color);
            return true;
        }
        return false;
    }

    private void applyOnMainThread() {
        runOnMain(this::applyInternal);
    }

    private void applyInternal() {
        if (message == null || message.trim().isEmpty()) {
            overlay.hide();
            return;
        }
        int[] colors = resolveThemeColors(color);
        if (overlay.isVisible()) {
            overlay.updateTheme(colors[0], colors[1]);
            overlay.show(message, colors[0], colors[1]);
        } else {
            overlay.show(message, colors[0], colors[1]);
        }
    }

    private int[] resolveThemeColors(String theme) {
        switch (normalizeColor(theme)) {
            case "pink":
                return new int[] {0xCCF8BBD0, 0xFF880E4F};
            case "blue":
                return new int[] {0xCCBBDEFB, 0xFF0D47A1};
            case "green":
                return new int[] {0xCCC8E6C9, 0xFF1B5E20};
            case "orange":
                return new int[] {0xCCFFE0B2, 0xFFE65100};
            case "purple":
                return new int[] {0xCCE1BEE7, 0xFF4A148C};
            case "dark":
                return new int[] {0xCC424242, 0xFFFFFFFF};
            case "yellow":
            default:
                return new int[] {0xCCFFF59D, 0xFF4E342E};
        }
    }

    private String normalizeColor(String value) {
        if (value == null) {
            return DEFAULT_COLOR;
        }
        String normalized = value.trim().toLowerCase();
        return COLOR_OPTION_SET.contains(normalized) ? normalized : DEFAULT_COLOR;
    }

    private void notifyMessageListeners(String value) {
        for (Object listener : messageListeners) {
            notifySingleListener(listener, value);
        }
    }

    private void notifyColorListeners(String value) {
        for (Object listener : colorListeners) {
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
