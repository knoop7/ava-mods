package com.ava.mods.bleadv;

/** JNI entry for in-process MGMT/HCI (Ava app uid). */
final class BleAdvNative {
    private BleAdvNative() {
    }

    static native String nativeRun(int dev, String mode, int durationMs, byte[] pdu);

    static native int nativePrepController(int dev);
}
