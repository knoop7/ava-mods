package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

import com.unisound.jni.Uni4micHalJNI;

/**
 * Stock 4-mic array via {@link Uni4micHalJNI} — same bring-up as 小讯 FourMicAudioManagerInterface.
 * Keeps {@code openAudioIn + readData} pump running so board DOA stays live while Ava uses mono wake/STT.
 */
final class PhicommOemDoaReader {
    private static final String TAG = "PhicommOemDoa";
    private static final int BOARD_TYPE = 1;
    private static final int OPEN_AUDIO_MODE = 2;
    /** Stock {@code ASR_BEST_RESULT_RETURN * 2} bytes per read. */
    private static final int READ_CHUNK_BYTES = 2400;

    private enum State {
        UNTESTED,
        AVAILABLE,
        UNAVAILABLE
    }

    private static State state = State.UNTESTED;
    private static Context appContext;
    private static PhicommPrivilegedShell privilegedShell;
    private static Uni4micHalJNI hal;
    private static long audioInHandle;
    private static Thread pumpThread;
    private static volatile boolean pumpRunning;
    private static String boardVersion = "";

    private PhicommOemDoaReader() {
    }

    static void configure(Context context, PhicommPrivilegedShell shell) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (shell != null) {
            privilegedShell = shell;
        }
    }

    static boolean isAvailable() {
        ensureStarted();
        return state == State.AVAILABLE;
    }

    static String getBoardVersion() {
        return boardVersion;
    }

    /** Tell the board HAL that a wake event happened (stock option 4444 / 10104 path). */
    static void onWakeDetected() {
        if (state != State.AVAILABLE || hal == null) {
            return;
        }
        try {
            hal.set4MicWakeUpStatus(1);
        } catch (Throwable t) {
            Log.w(TAG, "set4MicWakeUpStatus(1) failed", t);
        }
    }

    static int readAngle() {
        if (state != State.AVAILABLE || hal == null) {
            return -1;
        }
        try {
            int angle = hal.get4MicDoaResult();
            resetWakeStatusQuietly();
            if (isValidAngle(angle)) {
                return angle;
            }
        } catch (Throwable t) {
            Log.w(TAG, "get4MicDoaResult failed", t);
        }
        return -1;
    }

    /** Stock toggles wakeup status per session (option 10104); re-arm for the next wake. */
    private static void resetWakeStatusQuietly() {
        try {
            hal.set4MicWakeUpStatus(0);
        } catch (Throwable t) {
            Log.d(TAG, "set4MicWakeUpStatus(0) failed: " + t.getMessage());
        }
    }

    static void ensureStarted() {
        if (state != State.UNTESTED) {
            return;
        }
        synchronized (PhicommOemDoaReader.class) {
            if (state != State.UNTESTED) {
                return;
            }
            if (appContext == null) {
                state = State.UNAVAILABLE;
                Log.i(TAG, "4-mic HAL skipped (no context)");
                return;
            }
            if (!PhicommFourMicLoader.load(appContext, privilegedShell)) {
                state = State.UNAVAILABLE;
                Log.i(TAG, "4-mic HAL library unavailable");
                return;
            }
            try {
                hal = Uni4micHalJNI.getInstance();
                int initStatus = hal.init(BOARD_TYPE);
                if (initStatus != 0) {
                    state = State.UNAVAILABLE;
                    Log.i(TAG, "4-mic HAL init failed status=" + initStatus);
                    return;
                }
                applyStockOptions();
                audioInHandle = hal.openAudioIn(OPEN_AUDIO_MODE);
                if (audioInHandle == 0L) {
                    releaseHalQuietly();
                    state = State.UNAVAILABLE;
                    Log.i(TAG, "4-mic HAL openAudioIn failed");
                    return;
                }
                int startStatus = hal.startRecorder(audioInHandle);
                if (startStatus != 0) {
                    closeAudioInQuietly();
                    releaseHalQuietly();
                    state = State.UNAVAILABLE;
                    Log.i(TAG, "4-mic HAL startRecorder failed status=" + startStatus);
                    return;
                }
                startPumpThread();
                state = State.AVAILABLE;
                Log.i(TAG, "4-mic array ready board=" + boardVersion);
            } catch (UnsatisfiedLinkError e) {
                state = State.UNAVAILABLE;
                Log.i(TAG, "4-mic HAL link error: " + e.getMessage());
            } catch (Throwable t) {
                state = State.UNAVAILABLE;
                Log.w(TAG, "4-mic HAL setup failed", t);
            }
        }
    }

    static void shutdown() {
        synchronized (PhicommOemDoaReader.class) {
            pumpRunning = false;
            if (pumpThread != null) {
                pumpThread.interrupt();
                pumpThread = null;
            }
            closeAudioInQuietly();
            releaseHalQuietly();
            hal = null;
            state = State.UNTESTED;
        }
    }

    /** Mirrors {@code FourMicAudioManagerInterface.c()} after {@code init(boardType)}. */
    private static void applyStockOptions() {
        try {
            hal.set4MicDebugMode(0);
            hal.close4MicAlgorithm(0);
            hal.set4MicWakeUpStatus(0);
            boardVersion = safeBoardVersion();
        } catch (Throwable t) {
            Log.w(TAG, "applyStockOptions failed", t);
            boardVersion = "";
        }
    }

    private static String safeBoardVersion() {
        try {
            String version = hal.get4MicBoardVersion();
            return version != null ? version : "";
        } catch (Throwable t) {
            Log.d(TAG, "get4MicBoardVersion failed: " + t.getMessage());
            return "";
        }
    }

    private static void startPumpThread() {
        pumpRunning = true;
        pumpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[READ_CHUNK_BYTES];
                while (pumpRunning) {
                    try {
                        int read = hal.readData(audioInHandle, buffer, buffer.length);
                        if (read < 0 && pumpRunning) {
                            Log.w(TAG, "4-mic readData returned " + read);
                            break;
                        }
                    } catch (Throwable t) {
                        if (pumpRunning) {
                            Log.w(TAG, "4-mic readData error", t);
                        }
                        break;
                    }
                }
            }
        }, "PhicommFourMicPump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private static void closeAudioInQuietly() {
        if (hal == null || audioInHandle == 0L) {
            audioInHandle = 0L;
            return;
        }
        try {
            hal.stopRecorder(audioInHandle);
        } catch (Throwable ignored) {
        }
        try {
            hal.closeAudioIn(audioInHandle);
        } catch (Throwable ignored) {
        }
        audioInHandle = 0L;
    }

    private static void releaseHalQuietly() {
        if (hal == null) {
            return;
        }
        try {
            hal.release();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isValidAngle(int angle) {
        return angle >= 0 && angle < 360;
    }
}
