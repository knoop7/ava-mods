package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

/**
 * Best-effort pause of Ava built-in BLE presence advertising before raw MGMT inject.
 */
final class HostBlePause {
    private static final String TAG = "BleAdvHostPause";

    private HostBlePause() {
    }

    static void pausePresenceAdvertising(Context context) {
        try {
            Class<?> mgr = load(context, "com.example.ava.bluetooth.BluetoothPresenceManager");
            Object instance = mgr.getMethod("getInstance", Context.class)
                    .invoke(null, context.getApplicationContext());
            mgr.getMethod("stopAdvertising").invoke(instance);
            Log.d(TAG, "stopped host presence advertising");
            Thread.sleep(80L);
        } catch (Exception e) {
            Log.d(TAG, "stopAdvertising unavailable: " + e.getMessage());
        }
    }

    private static Class<?> load(Context context, String name) throws ClassNotFoundException {
        ClassLoader loader = context.getClassLoader();
        if (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(name);
    }
}
