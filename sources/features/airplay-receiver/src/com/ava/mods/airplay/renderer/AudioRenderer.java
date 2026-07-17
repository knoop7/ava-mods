package com.ava.mods.airplay.renderer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.ava.mods.airplay.audio.PcmLevelMeter;
import com.ava.mods.airplay.bridge.NativeBridge;

import java.nio.ByteBuffer;

public final class AudioRenderer {

    private static final String TAG = "AudioRenderer";
    public static final int CT_ALAC = 2;
    public static final int CT_AAC_LC = 4;
    public static final int CT_AAC_ELD = 8;

    private MediaCodec codec;
    private AudioTrack track;
    private int currentCt = -1;
    private int failedCt = -1;
    private long swAlacHandle = 0L;

    public volatile boolean swAlacEnabled = true;
    public volatile int audioBufferMultiplier = 4;
    public volatile boolean realtimeDecoderPriority = true;
    private volatile float volume = 1.0f;
    private volatile String codecLabel = "";
    private volatile boolean pendingReset = false;
    private volatile boolean playbackPaused = false;
    private volatile long framesWritten = 0L;

    private final PcmLevelMeter levelMeter = new PcmLevelMeter();
    private final float[] levelScratch = new float[32];
    private volatile PcmLevelMeter.Listener levelListener;
    private int levelEmitCounter;

    public float getVolume() { return volume; }
    public String getCodecLabel() { return codecLabel; }

    public int getAudioSessionId() {
        AudioTrack t = track;
        return t != null ? t.getAudioSessionId() : 0;
    }

    public void setLevelListener(PcmLevelMeter.Listener listener) {
        levelListener = listener;
        if (listener == null) {
            levelMeter.reset();
        }
    }

    public int getLevelBandCount() {
        return levelMeter.bandCount();
    }

    /**
     * PCM still sitting in the AudioTrack buffer (ms). Heard audio lags
     * {@code write()} by roughly this amount.
     */
    public long getBufferedMs() {
        AudioTrack t = track;
        if (t == null) return 0L;
        try {
            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            long buffered = framesWritten - head;
            if (buffered < 0L) buffered = 0L;
            return buffered * 1000L / 44100L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public void markSessionEnded() {
        pendingReset = true;
    }

    public void feedAudio(byte[] data, int ct, long ntpTimeNs) {
        if (playbackPaused) return;
        if (pendingReset) {
            pendingReset = false;
            stop();
        }
        if (ct != currentCt || (codec == null && swAlacHandle == 0L)) {
            if (ct == failedCt) {
                if (!ensureSoftwareAlac(ct)) return;
            } else {
                stop();
                start(ct);
            }
        }

        if (swAlacHandle != 0L && ct == CT_ALAC) {
            feedSoftwareAlac(data);
            return;
        }

        MediaCodec c = codec;
        if (c == null) return;
        try {
            int idx = c.dequeueInputBuffer(5000);
            if (idx >= 0) {
                ByteBuffer buf = c.getInputBuffer(idx);
                if (buf == null) return;
                buf.clear();
                buf.put(data);
                c.queueInputBuffer(idx, 0, data.length, ntpTimeNs / 1000, 0);
            }
            drainOutput();
        } catch (IllegalStateException e) {
            codec = null;
            failedCt = ct;
            if (ensureSoftwareAlac(ct)) feedSoftwareAlac(data);
        }
    }

    private boolean ensureSoftwareAlac(int ct) {
        if (ct != CT_ALAC || !swAlacEnabled) return false;
        if (swAlacHandle != 0L) return true;
        startSoftwareAlac();
        return swAlacHandle != 0L;
    }

    private void feedSoftwareAlac(byte[] data) {
        byte[] pcm = NativeBridge.nativeAlacDecode(swAlacHandle, data);
        if (pcm == null) return;
        writePcm(pcm);
    }

    private void writePcm(byte[] pcm) {
        AudioTrack t = track;
        if (t == null || pcm == null || pcm.length == 0) return;
        t.write(pcm, 0, pcm.length);
        // stereo 16-bit → frames
        framesWritten += pcm.length / 4;
        emitLevels(pcm, pcm.length);
    }

    private void emitLevels(byte[] pcm, int length) {
        PcmLevelMeter.Listener listener = levelListener;
        if (listener == null) return;
        levelMeter.processStereoPcm16(pcm, length);
        // ~ every 3rd buffer keeps UI ~30–50fps without flooding the main thread.
        if ((++levelEmitCounter % 3) != 0) return;
        levelMeter.copyBands(levelScratch);
        listener.onLevels(levelScratch);
    }

    private void startSoftwareAlac() {
        Log.i(TAG, "Starting software ALAC decoder");
        swAlacHandle = NativeBridge.nativeAlacInit(352, 2, 16, 40, 10, 14);
        if (swAlacHandle == 0L) {
            Log.e(TAG, "Failed to init software ALAC decoder");
            return;
        }
        currentCt = CT_ALAC;
        codecLabel = "ALAC (SW)";
        ensureAudioTrack();
    }

    private void start(int ct) {
        currentCt = ct;
        switch (ct) {
            case CT_ALAC: codecLabel = "ALAC"; break;
            case CT_AAC_LC: codecLabel = "AAC-LC"; break;
            case CT_AAC_ELD: codecLabel = "AAC-ELD"; break;
            default: codecLabel = "?"; break;
        }
        MediaFormat format;
        switch (ct) {
            case CT_AAC_ELD: {
                format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, 39);
                format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{
                        (byte) 0xF8, (byte) 0xE8, (byte) 0x50, (byte) 0x00}));
                break;
            }
            case CT_AAC_LC: {
                format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
                format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{0x12, 0x10}));
                break;
            }
            case CT_ALAC: {
                format = MediaFormat.createAudioFormat("audio/alac", 44100, 2);
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
                byte[] csd = {
                        0x00, 0x00, 0x00, 0x24,
                        0x61, 0x6C, 0x61, 0x63,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x01, 0x60,
                        0x00, 0x10, 0x28, 0x0A, 0x0E, 0x02,
                        0x00, (byte) 0xFF,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, (byte) 0xAC, 0x44,
                };
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                break;
            }
            default: {
                Log.w(TAG, "Unknown audio codec type: " + ct);
                return;
            }
        }

        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }

        try {
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return;
            MediaCodec c = MediaCodec.createDecoderByType(mime);
            c.configure(format, null, null, 0);
            c.start();
            codec = c;
        } catch (Exception e) {
            Log.e(TAG, "Failed to init audio codec for ct=" + ct, e);
            codec = null;
            failedCt = ct;
            if (ct == CT_ALAC && swAlacEnabled) {
                startSoftwareAlac();
                return;
            }
            return;
        }

        ensureAudioTrack();
        Log.i(TAG, "Audio started: ct=" + ct);
    }

    private void ensureAudioTrack() {
        if (track != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(44100)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        int bufSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int mult = audioBufferMultiplier;
        if (mult < 4) mult = 4;
        if (mult > 8) mult = 8;
        AudioTrack t = new AudioTrack(attrs, fmt, bufSize * mult, AudioTrack.MODE_STREAM, 0);
        t.setVolume(volume);
        t.play();
        track = t;
    }

    private void drainOutput() {
        MediaCodec c = codec;
        AudioTrack t = track;
        if (c == null || t == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = c.dequeueOutputBuffer(info, 0);
            if (idx < 0) break;
            ByteBuffer buf = c.getOutputBuffer(idx);
            if (buf == null) break;
            byte[] pcm = new byte[info.size];
            buf.get(pcm);
            writePcm(pcm);
            c.releaseOutputBuffer(idx, false);
        }
    }

    public void setVolume(float vol) {
        float v;
        if (vol <= -144f) v = 0f;
        else if (vol >= 0f) v = 1f;
        else v = (float) Math.pow(10.0, vol / 20.0);
        volume = v;
        AudioTrack t = track;
        if (t != null) t.setVolume(v);
    }

    public void setPlaybackPaused(boolean paused) {
        playbackPaused = paused;
        AudioTrack t = track;
        if (t == null) return;
        try {
            if (paused) t.pause();
            else t.play();
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack pause/play failed", e);
        }
    }

    public void stop() {
        if (track != null) {
            try { track.stop(); track.release(); } catch (Exception ignored) {}
            track = null;
        }
        if (codec != null) {
            try { codec.stop(); codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (swAlacHandle != 0L) {
            NativeBridge.nativeAlacDestroy(swAlacHandle);
            swAlacHandle = 0L;
        }
        currentCt = -1;
        failedCt = -1;
        codecLabel = "";
        playbackPaused = false;
        framesWritten = 0L;
    }

    public void release() {
        stop();
    }
}
