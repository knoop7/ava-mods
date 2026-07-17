package com.ava.mods.airplay.bridge;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Map;

/**
 * JNI entry. Native .so files cannot be rebound to a new {@link dalvik.system.DexClassLoader}
 * in the same process ("already opened by ClassLoader"). We therefore:
 * <ul>
 *   <li>lazy-load (avoid poisoning this class via failed {@code <clinit>})</li>
 *   <li>on "already opened", copy libs to a unique code-cache path and {@link System#load}</li>
 * </ul>
 */
public final class NativeBridge {

    private static final String TAG = "AirPlayNativeBridge";
    private static final String[] LIBS = {"crypto", "c++_shared", "airplay_native"};

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile String loadError;

    private NativeBridge() {}

    /** Call before any native* method. Safe to call repeatedly. */
    public static void ensureLoaded(Context context) {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            if (context == null) {
                throw new UnsatisfiedLinkError("AirPlay native load needs Context");
            }
            Context app = context.getApplicationContext();
            try {
                loadAll(app);
                loaded = true;
                loadError = null;
                Log.i(TAG, "native libs ready");
            } catch (UnsatisfiedLinkError e) {
                loadError = e.getMessage();
                Log.e(TAG, "native load failed (update mod then toggle off/on, or reopen Ava once)", e);
                throw e;
            }
        }
    }

    public static String lastLoadError() {
        return loadError;
    }

    private static void loadAll(Context app) {
        UnsatisfiedLinkError first = null;
        try {
            for (String name : LIBS) {
                System.loadLibrary(name);
            }
            return;
        } catch (UnsatisfiedLinkError e) {
            first = e;
            if (!isAlreadyOpened(e)) {
                throw e;
            }
            Log.w(TAG, "loadLibrary hit already-opened ClassLoader; staging unique copies");
        }

        File abiDir = findAbiJniDir(app);
        if (abiDir == null) {
            throw new UnsatisfiedLinkError(
                    "AirPlay jni dir missing under files/mods/airplay-receiver/libs/jni "
                            + "(cause: " + first.getMessage() + ")");
        }
        File stage = new File(app.getCodeCacheDir(),
                "airplay-so/" + Integer.toHexString(System.identityHashCode(NativeBridge.class))
                        + "-" + Long.toHexString(System.nanoTime()));
        if (!stage.mkdirs() && !stage.isDirectory()) {
            throw new UnsatisfiedLinkError("Cannot create native stage dir: " + stage);
        }
        try {
            for (String name : LIBS) {
                File src = new File(abiDir, "lib" + name + ".so");
                if (!src.isFile()) {
                    throw new UnsatisfiedLinkError("Missing " + src.getAbsolutePath());
                }
                File dst = new File(stage, src.getName());
                copyFile(src, dst);
                System.load(dst.getAbsolutePath());
            }
        } catch (IOException ioe) {
            UnsatisfiedLinkError ule = new UnsatisfiedLinkError(
                    "Failed staging AirPlay natives: " + ioe.getMessage());
            ule.initCause(ioe);
            throw ule;
        }
    }

    private static boolean isAlreadyOpened(Throwable t) {
        String msg = t.getMessage();
        return msg != null && msg.contains("already opened");
    }

    private static File findAbiJniDir(Context app) {
        File base = new File(app.getFilesDir(), "mods/airplay-receiver/libs/jni");
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if (abi == null) continue;
                File dir = new File(base, abi);
                if (new File(dir, "libairplay_native.so").isFile()) return dir;
            }
        }
        File arm64 = new File(base, "arm64-v8a");
        if (new File(arm64, "libairplay_native.so").isFile()) return arm64;
        File arm32 = new File(base, "armeabi-v7a");
        if (new File(arm32, "libairplay_native.so").isFile()) return arm32;
        return null;
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                FileChannel ic = in.getChannel();
                FileChannel oc = out.getChannel();
                long size = ic.size();
                long pos = 0;
                while (pos < size) {
                    pos += ic.transferTo(pos, size - pos, oc);
                }
                out.getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    public static native long nativeInit(
            RaopCallbackHandler callback,
            byte[] hwAddr,
            String name,
            String keyFile,
            boolean nohold,
            boolean requirePin);

    public static native int nativeStart(long handle, int port);
    public static native void nativeStop(long handle);
    public static native void nativeDestroy(long handle);

    public static native void nativeSetDisplaySize(long handle, int w, int h, int fps);
    public static native void nativeSetPlist(long handle, String key, int value);
    public static native void nativeSetH265Enabled(long handle, boolean enabled);
    public static native void nativeSetCodecs(long handle, boolean alac, boolean aac);
    public static native void nativeSetHlsEnabled(long handle, boolean enabled);
    public static native void nativeSetAudioEnabled(long handle, boolean enabled);
    public static native void nativeUpdatePlaybackInfo(
            long handle, float position, float duration, float rate, boolean readyToPlay);

    public static native Map<String, String> nativeGetRaopTxtRecords(long handle);
    public static native Map<String, String> nativeGetAirplayTxtRecords(long handle);
    public static native String nativeGetRaopServiceName(long handle);
    public static native String nativeGetServerName(long handle);

    public static native long nativeAlacInit(int frameLength, int numChannels, int bitDepth,
                                             int pb, int mb, int kb);
    public static native byte[] nativeAlacDecode(long handle, byte[] input);
    public static native void nativeAlacDestroy(long handle);
}
