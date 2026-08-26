package com.ava.mods.vision.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks resources bundled inside camera-vision.jar (tflite models and the
 * TFLite JNI library).
 *
 * The host only downloads entries listed under the manifest "libs" key, and
 * ModCameraStreamBridge refuses camera ownership unless every libs entry ends in
 * .jar — so nothing can ship as separate package files. Everything travels as
 * resources inside the JAR and is extracted on first use.
 */
public final class ModelStore {

    private static final String TAG = "VisionModelStore";
    private static final String DIR_NAME = "camera-vision-models";

    static final String FACE_SPARSE = "face_detection_full_range_sparse.tflite";
    static final String FACE_SHORT = "face_detection_short_range.tflite";
    static final String PALM = "palm_detection_lite.tflite";
    static final String HAND = "hand_landmark_lite.tflite";

    /** Test harnesses without a Context can point extraction at a plain directory. */
    private static volatile File baseDirOverride;

    private ModelStore() {
    }

    public static void setBaseDir(File dir) {
        baseDirOverride = dir;
    }

    public static File dir(Context context) {
        File override = baseDirOverride;
        if (override != null) return new File(override, DIR_NAME);
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * @return the on-disk model file, or null when it could not be provided
     */
    public static File require(Context context, String name) {
        return extract(context, "models/" + name, new File(dir(context), name));
    }

    /**
     * Copies one JAR resource to disk if not already present.
     *
     * @return the extracted file, or null on failure
     */
    static File extract(Context context, String resourcePath, File dest) {
        if (dest.isFile() && dest.length() > 0) return dest;
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.e(TAG, "Cannot create " + parent);
            return null;
        }
        File tmp = new File(parent, dest.getName() + ".part");
        InputStream in = null;
        OutputStream out = null;
        ZipFile zip = null;
        try {
            ClassLoader loader = ModelStore.class.getClassLoader();
            if (loader != null) {
                in = loader.getResourceAsStream(resourcePath);
            }
            if (in == null) {
                // DexClassLoader does not always expose non-class JAR entries; read
                // the installed JAR directly as a plain zip instead.
                File jar = installedJar(context);
                if (jar == null) {
                    Log.e(TAG, "Cannot locate mod JAR for " + resourcePath);
                    return null;
                }
                zip = new ZipFile(jar);
                ZipEntry entry = zip.getEntry(resourcePath);
                if (entry == null) {
                    Log.e(TAG, "Resource missing from JAR: " + resourcePath);
                    return null;
                }
                in = zip.getInputStream(entry);
            }
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            if (tmp.length() <= 0 || !tmp.renameTo(dest)) {
                Log.e(TAG, "Failed to finalize " + resourcePath);
                tmp.delete();
                return null;
            }
            Log.i(TAG, "Extracted " + resourcePath + " (" + dest.length() + " bytes)");
            return dest;
        } catch (Exception e) {
            Log.e(TAG, "Extract failed for " + resourcePath + ": " + e.getMessage());
            tmp.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(zip);
        }
    }

    private static File installedJar(Context context) {
        if (context == null) return null;
        File jar = new File(context.getFilesDir(),
                "mods/camera-vision-mod/libs/camera-vision.jar");
        return jar.isFile() ? jar : null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
