package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Loads stock {@code libUni4micHalJNI.so} for the 4-mic board HAL.
 * System/vendor paths first, then legacy 小讯 APK lib dirs, then root-assisted find + cache copy.
 */
final class PhicommFourMicLoader {
    private static final String TAG = "PhicommFourMicLoader";
    private static final String LIB_NAME = "libUni4micHalJNI.so";
    private static final String STOCK_PACKAGE = "com.phicomm.speaker.device";

    private static final String[] SYSTEM_PATHS = {
        "/system/lib/" + LIB_NAME,
        "/vendor/lib/" + LIB_NAME,
        "/system/lib64/" + LIB_NAME,
        "/vendor/lib64/" + LIB_NAME,
    };

    private static volatile boolean loaded;

    private PhicommFourMicLoader() {
    }

    static boolean isLoaded() {
        return loaded;
    }

    static boolean load(Context context, PhicommPrivilegedShell shell) {
        if (loaded) {
            return true;
        }
        synchronized (PhicommFourMicLoader.class) {
            if (loaded) {
                return true;
            }
            for (String path : SYSTEM_PATHS) {
                if (tryLoad(path)) {
                    return true;
                }
            }

            String stockLib = findStockApkLibPath();
            if (stockLib != null && tryLoad(stockLib)) {
                return true;
            }

            if (shell != null && shell.isRootAvailable()) {
                String found = findViaRoot(shell);
                if (found != null) {
                    if (tryLoad(found)) {
                        return true;
                    }
                    File cached = copyToCache(context, found);
                    if (cached != null && tryLoad(cached.getAbsolutePath())) {
                        return true;
                    }
                }
            }

            Log.i(TAG, "libUni4micHalJNI not found on device");
            return false;
        }
    }

    private static String findStockApkLibPath() {
        String[] roots = {
            "/data/app-lib/" + STOCK_PACKAGE + "/lib",
            "/data/data/" + STOCK_PACKAGE + "/lib",
        };
        for (String root : roots) {
            String path = pickArmLib(new File(root));
            if (path != null) {
                return path;
            }
        }

        File dataApp = new File("/data/app");
        File[] entries = dataApp.listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            if (!entry.isDirectory() || !entry.getName().startsWith(STOCK_PACKAGE)) {
                continue;
            }
            String path = pickArmLib(new File(entry, "lib"));
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static String pickArmLib(File libDir) {
        if (libDir == null || !libDir.isDirectory()) {
            return null;
        }
        String[] abis = {"arm", "armeabi", "armeabi-v7a"};
        for (String abi : abis) {
            File candidate = new File(libDir, abi + "/" + LIB_NAME);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findViaRoot(PhicommPrivilegedShell shell) {
        String output = shell.captureOutput(
            "find /system /vendor /data/app /data/app-lib -name '" + LIB_NAME + "' 2>/dev/null | head -n 1"
        );
        if (output == null) {
            return null;
        }
        String path = output.trim();
        return path.isEmpty() ? null : path;
    }

    private static File copyToCache(Context context, String sourcePath) {
        if (context == null || sourcePath == null) {
            return null;
        }
        File source = new File(sourcePath);
        if (!source.isFile()) {
            return null;
        }
        File dest = new File(context.getDir("native", Context.MODE_PRIVATE), LIB_NAME);
        if (dest.isFile() && dest.length() == source.length() && dest.lastModified() >= source.lastModified()) {
            return dest;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(dest, false);
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            out.flush();
            dest.setReadable(true, false);
            dest.setExecutable(true, false);
            Log.i(TAG, "cached HAL to " + dest.getAbsolutePath());
            return dest;
        } catch (Throwable t) {
            Log.w(TAG, "cache copy failed for " + sourcePath, t);
            if (dest.isFile()) {
                dest.delete();
            }
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static boolean tryLoad(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return false;
        }
        try {
            System.load(path);
            loaded = true;
            Log.i(TAG, "loaded HAL from " + path);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "System.load failed for " + path + ": " + e.getMessage());
            return false;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
