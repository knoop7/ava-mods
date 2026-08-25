package com.ava.mods.vision;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks the .tflite files bundled inside camera-vision.jar.
 *
 * The host only downloads entries listed under the manifest "libs" key, and
 * ModCameraStreamBridge refuses camera ownership unless every libs entry ends in
 * .jar — so the models cannot be shipped as separate package files. They travel
 * as resources inside the JAR instead and are extracted once on first use.
 */
public final class ModelStore {

    private static final String TAG = "VisionModelStore";
    private static final String RESOURCE_PREFIX = "models/";
    private static final String DIR_NAME = "camera-vision-models";

    static final String FACE_SPARSE = "face_detection_full_range_sparse.tflite";
    static final String FACE_SHORT = "face_detection_short_range.tflite";
    static final String PALM = "palm_detection_lite.tflite";
    static final String HAND = "hand_landmark_lite.tflite";

    private static final String[] ALL = { FACE_SPARSE, FACE_SHORT, PALM, HAND };

    private static volatile boolean extracted;

    private ModelStore() {
    }

    public static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * @return the on-disk model file, or null when it could not be provided
     */
    public static File require(Context context, String name) {
        ensureExtracted(context);
        File f = new File(dir(context), name);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    private static void ensureExtracted(Context context) {
        if (extracted) return;
        synchronized (ModelStore.class) {
            if (extracted) return;
            File target = dir(context);
            if (!target.isDirectory() && !target.mkdirs()) {
                Log.e(TAG, "Cannot create " + target);
                return;
            }
            int ok = 0;
            for (String name : ALL) {
                if (extractOne(context, name, new File(target, name))) ok++;
            }
            extracted = ok == ALL.length;
            Log.i(TAG, "Extracted " + ok + "/" + ALL.length + " models to " + target);
        }
    }

    private static boolean extractOne(Context context, String name, File dest) {
        if (dest.isFile() && dest.length() > 0) return true;
        File tmp = new File(dest.getParentFile(), name + ".part");
        InputStream in = null;
        OutputStream out = null;
        ZipFile zip = null;
        try {
            ClassLoader loader = ModelStore.class.getClassLoader();
            if (loader != null) {
                in = loader.getResourceAsStream(RESOURCE_PREFIX + name);
            }
            if (in == null) {
                // DexClassLoader does not always expose non-class JAR entries; read
                // the installed JAR directly as a plain zip instead.
                File jar = installedJar(context);
                if (jar == null) {
                    Log.e(TAG, "Cannot locate mod JAR for " + name);
                    return false;
                }
                zip = new ZipFile(jar);
                ZipEntry entry = zip.getEntry(RESOURCE_PREFIX + name);
                if (entry == null) {
                    Log.e(TAG, "Model missing from JAR: " + name);
                    return false;
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
                Log.e(TAG, "Failed to finalize " + name);
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Extract failed for " + name + ": " + e.getMessage());
            tmp.delete();
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(zip);
        }
    }

    private static File installedJar(Context context) {
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
