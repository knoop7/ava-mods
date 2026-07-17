package com.ava.mods.airplay;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Claims media UI exclusivity against Ava host Sendspin / VinylCover FAB.
 * AirPlay cinema and Sendspin vinyl are two separate surfaces — when AirPlay
 * owns playback, hide the host FAB and stop Sendspin transport.
 */
final class HostMediaExclusive {

    private static final String TAG = "AirPlayHostExclusive";
    private static final String VINYL_SERVICE = "com.example.ava.services.VinylCoverService";
    private static final String ACTION_HIDE_VINYL = "com.example.ava.HIDE_VINYL";
    private static final String EXTRA_FORCE_HIDE = "force_hide";
    private static final String VOICE_SERVICE = "com.example.ava.services.VoiceSatelliteService";

    private HostMediaExclusive() {}

    static void claim(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        hideVinylFab(app);
        stopSendspin(app);
    }

    private static void hideVinylFab(Context app) {
        boolean ok = false;
        try {
            Intent intent = new Intent(ACTION_HIDE_VINYL);
            intent.setClassName(app.getPackageName(), VINYL_SERVICE);
            intent.putExtra(EXTRA_FORCE_HIDE, true);
            app.startService(intent);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "hide vinyl Intent failed", t);
        }
        // Kotlin Companion.hide(Context, boolean)
        try {
            Class<?> cls = Class.forName(VINYL_SERVICE);
            Object companion = cls.getField("Companion").get(null);
            companion.getClass()
                    .getMethod("hide", Context.class, boolean.class)
                    .invoke(companion, app, true);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "hide vinyl Companion failed", t);
        }
        if (ok) Log.i(TAG, "VinylCover hide requested");
    }

    private static void stopSendspin(Context app) {
        try {
            Class<?> svcCls = Class.forName(VOICE_SERVICE);
            // Kotlin companion without @JvmStatic → Companion.getInstance()
            Object companion = svcCls.getField("Companion").get(null);
            Object svc = companion.getClass().getMethod("getInstance").invoke(companion);
            if (svc == null) {
                Log.w(TAG, "VoiceSatelliteService instance null");
                return;
            }
            java.lang.reflect.Field field = svcCls.getDeclaredField("sendspinManager");
            field.setAccessible(true);
            Object manager = field.get(svc);
            if (manager == null) {
                Log.w(TAG, "sendspinManager null");
                return;
            }
            manager.getClass().getMethod("stopPlayback").invoke(manager);
            Log.i(TAG, "Sendspin stopPlayback requested");
        } catch (Throwable t) {
            Log.w(TAG, "stop Sendspin failed", t);
        }
    }
}
