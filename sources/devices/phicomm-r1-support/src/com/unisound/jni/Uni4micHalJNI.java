package com.unisound.jni;

/**
 * Minimal OEM 4-mic HAL binding (same JNI symbols as stock 小讯).
 * Used only when {@code libUni4micHalJNI.so} is present on the device.
 */
public class Uni4micHalJNI {

    private static Uni4micHalJNI instance;
    private boolean initialized;

    private Uni4micHalJNI() {
    }

    public static Uni4micHalJNI getInstance() {
        if (instance == null) {
            instance = new Uni4micHalJNI();
        }
        return instance;
    }

    private native int initHal(int boardType);

    private native int releaseHal();

    public native int close4MicAlgorithm(int flag);

    public native int closeAudioIn(long handle);

    public native String get4MicBoardVersion();

    public native int get4MicDoaResult();

    public native int set4MicDebugMode(int mode);

    public native int set4MicWakeUpStatus(int status);

    public int init(int boardType) {
        if (initialized) {
            return 0;
        }
        int status = initHal(boardType);
        if (status == 0) {
            initialized = true;
        }
        return status;
    }

    public native long openAudioIn(int mode);

    public native int readData(long handle, byte[] buffer, int length);

    public int release() {
        if (!initialized) {
            return -1;
        }
        int status = releaseHal();
        if (status == 0) {
            initialized = false;
        }
        return status;
    }

    public native int startRecorder(long handle);

    public native int stopRecorder(long handle);
}
