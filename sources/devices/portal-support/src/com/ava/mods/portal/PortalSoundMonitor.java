package com.ava.mods.portal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

class PortalSoundMonitor {

    private static final String TAG = "PortalSupport";
    private static final int SAMPLE_RATE = 16000;
    private static final long PUBLISH_MS = 2000L;

    interface Listener {
        void onSoundLevel(int level);
    }

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;
    private volatile int lastLevel;

    PortalSoundMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    int getLastLevel() {
        return lastLevel;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — requesting and retrying");
            new PortalPermissionHelper(context).ensurePermission(Manifest.permission.RECORD_AUDIO);
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                running.set(false);
                return;
            }
        }
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            running.set(false);
            Log.w(TAG, "AudioRecord not supported");
            return;
        }
        final int bufSize = minBuf * 4;
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop(bufSize);
            }
        }, "portal-sound");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void captureLoop(int bufSize) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        short[] buf = new short[bufSize / 2];
        AudioRecord recorder = null;
        double sumSq = 0.0;
        int count = 0;
        long lastPublish = System.currentTimeMillis();

        try {
            while (running.get()) {
                int mode = audioManager != null ? audioManager.getMode() : AudioManager.MODE_NORMAL;
                boolean inCall = mode == AudioManager.MODE_IN_COMMUNICATION
                        || mode == AudioManager.MODE_IN_CALL
                        || mode == AudioManager.MODE_RINGTONE;
                if (inCall) {
                    if (recorder != null) {
                        Log.i(TAG, "sound: releasing mic for call (mode=" + mode + ")");
                        releaseRecorder(recorder);
                        recorder = null;
                        sumSq = 0.0;
                        count = 0;
                    }
                    sleepQuietly(500);
                    continue;
                }

                if (recorder == null) {
                    recorder = openRecorder(bufSize);
                    if (recorder == null) {
                        sleepQuietly(1000);
                        continue;
                    }
                    lastPublish = System.currentTimeMillis();
                    Log.i(TAG, "sound: mic acquired");
                }

                int read = recorder.read(buf, 0, buf.length);
                if (read > 0) {
                    for (int i = 0; i < read; i++) {
                        sumSq += (double) buf[i] * buf[i];
                    }
                    count += read;
                    long now = System.currentTimeMillis();
                    if (now - lastPublish >= PUBLISH_MS && count > 0) {
                        lastPublish = now;
                        double rms = Math.sqrt(sumSq / count);
                        double dbfs = rms > 1.0 ? 20.0 * Math.log10(rms / 32768.0) : -90.0;
                        int level = (int) Math.max(0, Math.min(100, ((dbfs + 60.0) / 60.0) * 100.0));
                        lastLevel = level;
                        listener.onSoundLevel(level);
                        sumSq = 0.0;
                        count = 0;
                    }
                } else {
                    Log.i(TAG, "sound: read failed, mic busy — backing off");
                    releaseRecorder(recorder);
                    recorder = null;
                    sumSq = 0.0;
                    count = 0;
                    sleepQuietly(2500);
                }
            }
        } finally {
            releaseRecorder(recorder);
        }
    }

    private AudioRecord openRecorder(int bufSize) {
        try {
            AudioRecord record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
            );
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder(record);
                return null;
            }
            record.startRecording();
            return record;
        } catch (Exception e) {
            Log.w(TAG, "sound: failed to open mic", e);
            return null;
        }
    }

    private void releaseRecorder(AudioRecord recorder) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
