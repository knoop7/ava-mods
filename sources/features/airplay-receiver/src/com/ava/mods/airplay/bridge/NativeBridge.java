package com.ava.mods.airplay.bridge;

import java.util.Map;

public final class NativeBridge {

    static {
        // OpenSSL is linked shared; load before airplay_native.
        System.loadLibrary("crypto");
        System.loadLibrary("c++_shared");
        System.loadLibrary("airplay_native");
    }

    private NativeBridge() {}

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
