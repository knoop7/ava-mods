package com.ava.mods.phicomm;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Fallback DOA when OEM {@link Uni4micHalJNI} is unavailable.
 * Captures multi-channel PCM in a ring buffer and estimates direction at wake time.
 */
final class PhicommDoaEstimator {
    private static final String TAG = "PhicommDoaEstimator";
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int RING_MS = 400;
    private static final int[] CHANNEL_CONFIGS = {
        0x8000000F, // 4-channel index mask (device-specific, common on RK boards)
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.CHANNEL_IN_MONO,
    };

    private enum State {
        UNTESTED,
        AVAILABLE,
        UNAVAILABLE
    }

    private static State state = State.UNTESTED;
    private static AudioRecord record;
    private static int channelCount = 1;
    private static byte[] ringBuffer;
    private static int ringCapacity;
    private static int ringWritePos;
    private static int ringFilled;
    private static Thread captureThread;
    private static volatile boolean captureRunning;

    private PhicommDoaEstimator() {
    }

    static boolean isAvailable() {
        ensureStarted();
        return state == State.AVAILABLE;
    }

    static int readAngle() {
        if (state != State.AVAILABLE) {
            return -1;
        }
        byte[] snapshot = snapshotRing();
        if (snapshot == null || snapshot.length < channelCount * 2 * 64) {
            return -1;
        }
        if (channelCount >= 4) {
            return estimateFromFourChannels(snapshot);
        }
        if (channelCount == 2) {
            return estimateFromStereo(snapshot);
        }
        return -1;
    }

    static void ensureStarted() {
        if (state != State.UNTESTED) {
            return;
        }
        synchronized (PhicommDoaEstimator.class) {
            if (state != State.UNTESTED) {
                return;
            }
            if (PhicommOemDoaReader.isAvailable()) {
                state = State.UNAVAILABLE;
                Log.i(TAG, "skipped (OEM HAL owns mic)");
                return;
            }
            AudioRecord opened = openCapture();
            if (opened == null) {
                state = State.UNAVAILABLE;
                Log.i(TAG, "no usable AudioRecord for DOA");
                return;
            }
            record = opened;
            ringCapacity = SAMPLE_RATE_HZ * 2 * channelCount * RING_MS / 1000;
            ringBuffer = new byte[ringCapacity];
            ringWritePos = 0;
            ringFilled = 0;
            startCaptureThread();
            state = State.AVAILABLE;
            Log.i(TAG, "fallback DOA capture ready channels=" + channelCount);
        }
    }

    static void shutdown() {
        synchronized (PhicommDoaEstimator.class) {
            captureRunning = false;
            if (captureThread != null) {
                captureThread.interrupt();
                captureThread = null;
            }
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    record.release();
                } catch (Throwable ignored) {
                }
                record = null;
            }
            ringBuffer = null;
            state = State.UNTESTED;
        }
    }

    private static AudioRecord openCapture() {
        for (int channelConfig : CHANNEL_CONFIGS) {
            int channels = channelCountForConfig(channelConfig);
            int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuffer <= 0) {
                continue;
            }
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2
                );
                if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                    candidate.release();
                    continue;
                }
                candidate.startRecording();
                if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    candidate.release();
                    continue;
                }
                channelCount = channels;
                return candidate;
            } catch (Throwable t) {
                Log.d(TAG, "AudioRecord probe failed config=0x"
                    + Integer.toHexString(channelConfig) + " " + t.getMessage());
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static int channelCountForConfig(int channelConfig) {
        if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) {
            return 2;
        }
        if (channelConfig == AudioFormat.CHANNEL_IN_MONO) {
            return 1;
        }
        return 4;
    }

    private static void startCaptureThread() {
        captureRunning = true;
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int frameBytes = channelCount * 2;
                byte[] chunk = new byte[frameBytes * 256];
                while (captureRunning && record != null) {
                    int read = record.read(chunk, 0, chunk.length);
                    if (read > 0) {
                        appendRing(chunk, read);
                    }
                }
            }
        }, "PhicommDoaCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private static void appendRing(byte[] data, int length) {
        synchronized (PhicommDoaEstimator.class) {
            if (ringBuffer == null || length <= 0) {
                return;
            }
            int offset = 0;
            while (offset < length) {
                int space = ringCapacity - ringWritePos;
                int copy = Math.min(space, length - offset);
                System.arraycopy(data, offset, ringBuffer, ringWritePos, copy);
                ringWritePos = (ringWritePos + copy) % ringCapacity;
                ringFilled = Math.min(ringCapacity, ringFilled + copy);
                offset += copy;
            }
        }
    }

    private static byte[] snapshotRing() {
        synchronized (PhicommDoaEstimator.class) {
            if (ringBuffer == null || ringFilled == 0) {
                return null;
            }
            int size = ringFilled;
            byte[] copy = new byte[size];
            int start = (ringWritePos - size + ringCapacity) % ringCapacity;
            if (start + size <= ringCapacity) {
                System.arraycopy(ringBuffer, start, copy, 0, size);
            } else {
                int first = ringCapacity - start;
                System.arraycopy(ringBuffer, start, copy, 0, first);
                System.arraycopy(ringBuffer, 0, copy, first, size - first);
            }
            return copy;
        }
    }

    /** Four mics on the ring at 0/90/180/270 degrees — energy vector sum. */
    private static int estimateFromFourChannels(byte[] interleaved) {
        double[] energy = new double[4];
        int frames = interleaved.length / 8;
        for (int i = 0; i < frames; i++) {
            int base = i * 8;
            for (int ch = 0; ch < 4; ch++) {
                int sample = (short) ((interleaved[base + ch * 2 + 1] << 8)
                    | (interleaved[base + ch * 2] & 0xFF));
                energy[ch] += sample * sample;
            }
        }
        double x = energy[0] - energy[2];
        double y = energy[1] - energy[3];
        if (x == 0.0 && y == 0.0) {
            return -1;
        }
        int angle = (int) Math.round(Math.toDegrees(Math.atan2(y, x)));
        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }

    /** Stereo GCC-PHAT lite: lag sign maps to left/right hemisphere. */
    private static int estimateFromStereo(byte[] interleaved) {
        int frames = interleaved.length / 4;
        if (frames < 128) {
            return -1;
        }
        int maxLag = Math.min(32, frames / 4);
        double bestCorr = Double.NEGATIVE_INFINITY;
        int bestLag = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double corr = 0.0;
            for (int i = maxLag; i < frames - maxLag; i++) {
                int left = sampleAt(interleaved, i, 0);
                int rightIndex = i + lag;
                if (rightIndex < 0 || rightIndex >= frames) {
                    continue;
                }
                int right = sampleAt(interleaved, rightIndex, 1);
                corr += left * right;
            }
            if (corr > bestCorr) {
                bestCorr = corr;
                bestLag = lag;
            }
        }
        if (bestLag == 0) {
            return 0;
        }
        return bestLag > 0 ? 90 : 270;
    }

    private static int sampleAt(byte[] data, int frameIndex, int channel) {
        int base = frameIndex * 4 + channel * 2;
        return (short) ((data[base + 1] << 8) | (data[base] & 0xFF));
    }
}
