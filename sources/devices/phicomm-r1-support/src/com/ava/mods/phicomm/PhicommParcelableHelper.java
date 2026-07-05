package com.ava.mods.phicomm;

import android.os.Parcelable;
import android.util.Log;

/**
 * Reflects framework {@code android.os.ParcelableUtil} for HIVOICE status payloads.
 */
final class PhicommParcelableHelper {
    private static final String TAG = "PhicommParcelable";

    private PhicommParcelableHelper() {
    }

    static Parcelable obtainInt(int value) {
        try {
            Class<?> clazz = Class.forName("android.os.ParcelableUtil");
            return (Parcelable) clazz.getMethod("obtain", int.class).invoke(null, value);
        } catch (Throwable t) {
            Log.w(TAG, "ParcelableUtil.obtain(int) failed value=" + value, t);
            return null;
        }
    }
}
