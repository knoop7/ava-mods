package com.ava.mods.phicomm;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/**
 * Resolves wake DOA angle (0–359°) for directional white LEDs.
 *
 * Priority:
 * 1. Ava pipeline extra {@code wake_doa_angle} (optional, Ava does not send today)
 * 2. Mod-owned stock {@link Uni4micHalJNI} 4-mic HAL (init + readData pump + DOA)
 * 3. Mod fallback estimator from live PCM ring buffer
 */
public final class PhicommDoaResolver {
    private static final String TAG = "PhicommDoaResolver";
    static final String EXTRA_WAKE_DOA_ANGLE = "wake_doa_angle";

    private static volatile boolean started;
    private static volatile boolean reverseAngle;

    private PhicommDoaResolver() {
    }

    /** User calibration knob: mirror the resolved angle when the board reports reversed DOA. */
    public static void setReverseAngle(boolean reverse) {
        reverseAngle = reverse;
    }

    public static void start(Context context, PhicommPrivilegedShell shell) {
        if (started || context == null) {
            return;
        }
        synchronized (PhicommDoaResolver.class) {
            if (started) {
                return;
            }
            PhicommOemDoaReader.configure(context, shell);
            PhicommOemDoaReader.ensureStarted();
            if (!PhicommOemDoaReader.isAvailable()) {
                PhicommDoaEstimator.ensureStarted();
            }
            started = true;
            Log.i(TAG, "started oem=" + PhicommOemDoaReader.isAvailable()
                + " board=" + PhicommOemDoaReader.getBoardVersion()
                + " fallback=" + PhicommDoaEstimator.isAvailable());
        }
    }

    public static void stop() {
        synchronized (PhicommDoaResolver.class) {
            if (!started) {
                return;
            }
            PhicommOemDoaReader.shutdown();
            PhicommDoaEstimator.shutdown();
            started = false;
            Log.i(TAG, "stopped");
        }
    }

    public static boolean isStarted() {
        return started;
    }

    public static int resolve(Bundle extras) {
        if (extras != null && extras.containsKey(EXTRA_WAKE_DOA_ANGLE)) {
            int angle = extras.getInt(EXTRA_WAKE_DOA_ANGLE, -1);
            if (isValidAngle(angle)) {
                return finish("pipeline", angle);
            }
        }

        if (!started) {
            return -1;
        }

        if (PhicommOemDoaReader.isAvailable()) {
            PhicommOemDoaReader.onWakeDetected();
            int oemAngle = PhicommOemDoaReader.readAngle();
            if (isValidAngle(oemAngle)) {
                return finish("oem", oemAngle);
            }
        }

        int estimated = PhicommDoaEstimator.readAngle();
        if (isValidAngle(estimated)) {
            return finish("fallback", estimated);
        }

        Log.d(TAG, "DOA unavailable");
        return -1;
    }

    private static int finish(String source, int angle) {
        if (reverseAngle) {
            angle = (360 - angle) % 360;
        }
        logResolved(source, angle);
        return angle;
    }

    private static void logResolved(String source, int angle) {
        Log.d(TAG, "wake DOA source=" + source
            + " angle=" + angle
            + " index=" + PhicommLightIndexProcessor.getIndex(angle));
    }

    private static boolean isValidAngle(int angle) {
        return angle >= 0 && angle < 360;
    }
}
