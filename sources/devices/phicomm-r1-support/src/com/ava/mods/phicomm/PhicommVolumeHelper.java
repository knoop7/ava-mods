package com.ava.mods.phicomm;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * Touch-ring volume fallback when system {@code UserDefInputKeyService} injection is unavailable.
 *
 * Stock 官改 already maps ring swipe → {@code injectInputEvent(VOLUME_UP/DOWN)} in system_server.
 * This helper is for programmatic / HA fallback only:
 * <ul>
 *   <li>Root: {@code input keyevent 24/25} (same as stock injectKey)</li>
 *   <li>No root: {@link AudioManager#adjustStreamVolume} simulation</li>
 * </ul>
 */
public final class PhicommVolumeHelper {
    private static final String TAG = "PhicommVolume";
    private static final int KEYCODE_VOLUME_UP = 24;
    private static final int KEYCODE_VOLUME_DOWN = 25;

    private final Context appContext;
    private final PhicommPrivilegedShell shell;
    private final AudioManager audioManager;
    private volatile String lastMethod = "none";

    PhicommVolumeHelper(Context context, PhicommPrivilegedShell shell) {
        appContext = context.getApplicationContext();
        this.shell = shell;
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    String getLastMethod() {
        return lastMethod;
    }

    public boolean adjustVolumeUp() {
        return adjustVolume(true);
    }

    public boolean adjustVolumeDown() {
        return adjustVolume(false);
    }

    private boolean adjustVolume(boolean up) {
        if (shell.isRootAvailable()) {
            int key = up ? KEYCODE_VOLUME_UP : KEYCODE_VOLUME_DOWN;
            int code = shell.exec("input keyevent " + key);
            if (code == 0) {
                lastMethod = "root_input";
                Log.d(TAG, "volume " + (up ? "up" : "down") + " via input keyevent");
                return true;
            }
            Log.w(TAG, "input keyevent failed code=" + code + " — trying AudioManager");
        }
        if (audioManager == null) {
            lastMethod = "failed";
            return false;
        }
        int direction = up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        );
        lastMethod = "audio_manager";
        Log.d(TAG, "volume " + (up ? "up" : "down") + " via AudioManager");
        return true;
    }
}
