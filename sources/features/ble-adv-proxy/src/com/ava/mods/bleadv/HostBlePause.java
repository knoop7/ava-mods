package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

/**
 * Best-effort pause of Ava built-in BLE presence advertising before raw MGMT inject.
 */
final class HostBlePause {
    private static final String TAG = "BleAdvHostPause";
    private static final String PRESENCE_MGR = "com.example.ava.bluetooth.BluetoothPresenceManager";

    private HostBlePause() {
    }

    static void pausePresenceAdvertising(Context context) {
        try {
            Class<?> mgr = loadHostClass(context, PRESENCE_MGR);
            Object instance = mgr.getMethod("getInstance", Context.class)
                    .invoke(null, context.getApplicationContext());
            mgr.getMethod("stopAdvertising").invoke(instance);
            Log.d(TAG, "stopped host presence advertising");
            Thread.sleep(120L);
        } catch (Exception e) {
            Log.d(TAG, "stopAdvertising unavailable: " + e.getMessage());
        }
    }

    private static Class<?> loadHostClass(Context context, String name) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        ClassLoader loader = context.getClassLoader();
        while (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                last = e;
                loader = loader.getParent();
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ClassNotFoundException(name);
    }
}
