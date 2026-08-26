package com.ava.mods.vision.core;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;

/**
 * Loads the mod's own bundled TFLite JNI library.
 *
 * The host app ships TFLite too, but R8 renames its Java API, so mod dex
 * references like Lorg/tensorflow/lite/Interpreter; fail to resolve against the
 * host classloader. The mod therefore carries the whole runtime: the Java
 * classes are dexed into camera-vision.jar and the native library is extracted
 * from it here. System.load binds the copy to this classloader's own linker
 * namespace, so it cannot clash with the host's identically named library.
 */
final class TfLiteRuntime {

    private static final String TAG = "VisionTfLite";
    private static final String LIB_NAME = "libtensorflowlite_jni.so";

    private static volatile boolean loaded;
    private static volatile String error = "";

    private TfLiteRuntime() {
    }

    static synchronized boolean ensureLoaded(Context context) {
        if (loaded) return true;
        String abi = pickAbi();
        if (abi == null) {
            error = "No bundled TFLite for ABIs " + String.join(",", Build.SUPPORTED_ABIS);
            Log.e(TAG, error);
            return false;
        }
        try {
            File dest = new File(ModelStore.dir(context), abi + "-" + LIB_NAME);
            File so = ModelStore.extract(context, "jni/" + abi + "/" + LIB_NAME, dest);
            if (so == null) {
                error = "TFLite JNI extraction failed for " + abi;
                Log.e(TAG, error);
                return false;
            }
            System.load(so.getAbsolutePath());
            loaded = true;
            Log.i(TAG, "Loaded bundled TFLite JNI (" + abi + ")");
            return true;
        } catch (Throwable t) {
            error = "TFLite JNI load failed: " + t.getMessage();
            Log.e(TAG, error, t);
            return false;
        }
    }

    static String getError() {
        return error;
    }

    /** The ABI must match the host process bitness, not just what the device supports. */
    private static String pickAbi() {
        boolean is64 = Process.is64Bit();
        String[] candidates = is64 ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : candidates) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) return abi;
        }
        return null;
    }
}
