package com.ava.mods.airplay.renderer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

public final class VideoRenderer {

    private static final String TAG = "VideoRenderer";
    private static final String BENCH_TAG = "BENCHMARK";
    private static final long FEED_WAIT_US = 20_000L;
    private static final int FEED_RETRIES = 10;

    public interface LogCallback {
        void log(String msg);
    }

    private final Object lock = new Object();
    private final VideoPipeline pipeline = new VideoPipeline();
    private MediaCodec codec;
    private Surface displaySurface;
    private boolean currentH265 = false;
    private int videoWidth = 0;
    private int videoHeight = 0;

    private volatile int fps = 0;
    private volatile long bitrateBps = 0L;
    private volatile long frameCount = 0L;
    private volatile String codecName = "";
    private volatile long droppedFrames = 0L;
    private volatile long framePacingJitterUs = 0L;

    public boolean enforceSdr = true;
    public boolean keyAllowFrameDrop = true;
    public boolean realtimeDecoderPriority = true;
    public boolean operatingRateHint = false;
    public boolean scheduledOutputBufferRelease = true;
    public boolean benchmarkLog = false;
    public LogCallback benchmarkLogCallback;

    private int framesThisSec = 0;
    private long bytesThisSec = 0L;
    private long lastStatReset = 0L;
    private final long[] frameIntervalsNs = new long[120];
    private int frameIntervalIdx = 0;
    private int frameIntervalCount = 0;
    private long lastOutputFrameNs = 0L;
    private long ptsBaseUs = Long.MIN_VALUE;
    private long wallBaseNs = 0L;

    public int getFps() { return fps; }
    public long getBitrateBps() { return bitrateBps; }
    public long getFrameCount() { return frameCount; }
    public String getCodecName() { return codecName; }
    public long getDroppedFrames() { return droppedFrames; }
    public long getFramePacingJitterUs() { return framePacingJitterUs; }

    public void setResolution(int w, int h) {
        videoWidth = w;
        videoHeight = h;
        pipeline.setVideoSize(w, h);
    }

    public void setSurface(Surface surface) {
        synchronized (lock) {
            displaySurface = surface;
            pipeline.setDisplaySurface(surface);
        }
    }

    public void clearSurface(Surface surface) {
        synchronized (lock) {
            if (displaySurface != surface) return;
            displaySurface = null;
            pipeline.setDisplaySurface(null);
        }
    }

    public void feedFrame(byte[] data, long ntpTimeNs, boolean isH265) {
        updateStats(data.length);

        synchronized (lock) {
            if (videoWidth == 0 || videoHeight == 0) return;

            if (codec == null || isH265 != currentH265) {
                if (!isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec();
                    return;
                }
                stopCodec();
            }

            try {
                if (codec == null) startCodec(isH265);
                feedToCodec(data, ntpTimeNs);
                drainOutput();
            } catch (Exception e) {
                Log.w(TAG, "Codec error, resetting", e);
                stopCodec();
            }
        }
    }

    private void updateStats(int size) {
        long now = System.currentTimeMillis();
        if (now - lastStatReset >= 1000) {
            fps = framesThisSec;
            bitrateBps = bytesThisSec * 8L;
            framePacingJitterUs = computeFramePacingJitterUs();
            framesThisSec = 0;
            bytesThisSec = 0;
            lastStatReset = now;
            if (benchmarkLog) emitBenchmarkLine();
        }
        framesThisSec++;
        bytesThisSec += size;
        frameCount++;
    }

    private void emitBenchmarkLine() {
        String msg = "fps=" + fps + " bitrate=" + (bitrateBps / 1000) + "kbps " +
                "jitter=" + framePacingJitterUs + "us frames=" + frameCount + " " +
                "dropped=" + droppedFrames + " codec=" + codecName + " " +
                "res=" + videoWidth + "x" + videoHeight;
        Log.i(BENCH_TAG, msg);
        LogCallback cb = benchmarkLogCallback;
        if (cb != null) cb.log(msg);
    }

    private void feedToCodec(byte[] data, long ntpTimeNs) {
        MediaCodec c = codec;
        if (c == null) return;
        for (int i = 0; i < FEED_RETRIES; i++) {
            int idx = c.dequeueInputBuffer(FEED_WAIT_US);
            if (idx >= 0) {
                ByteBuffer buf = c.getInputBuffer(idx);
                if (buf == null) return;
                buf.clear();
                buf.put(data);
                c.queueInputBuffer(idx, 0, data.length, ntpTimeNs / 1000, 0);
                return;
            }
            drainOutput();
        }
        droppedFrames++;
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=" + droppedFrames);
    }

    private boolean isKeyframe(byte[] data, boolean isH265) {
        if (data.length < 5) return false;
        int i = 0;
        while (i <= data.length - 5) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                boolean key;
                if (isH265) {
                    int type = (data[i + 4] >> 1) & 0x3F;
                    key = type == 19 || type == 20 || type == 21 || type == 32 || type == 33;
                } else {
                    int type = data[i + 4] & 0x1F;
                    key = type == 5 || type == 7;
                }
                if (key) return true;
            }
            i++;
        }
        return false;
    }

    private void startCodec(boolean h265) throws Exception {
        pipeline.start();
        pipeline.setVideoSize(videoWidth, videoHeight);
        Surface s = pipeline.getInputSurface();
        if (s == null) return;
        currentH265 = h265;
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;

        MediaFormat format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight);
        int maxInput = Math.max(videoWidth * videoHeight * 3 / 4, 1024 * 1024);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput);
        if (enforceSdr) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        if (operatingRateHint && Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, keyAllowFrameDrop ? 1 : 0);
        }

        MediaCodec c = MediaCodec.createDecoderByType(mime);
        c.configure(format, s, null, 0);
        c.start();
        codec = c;
        codecName = h265 ? "H.265" : "H.264";
        Log.i(TAG, "Video codec started: " + mime + " " + videoWidth + "x" + videoHeight);
    }

    private void stopCodec() {
        frameIntervalIdx = 0;
        frameIntervalCount = 0;
        lastOutputFrameNs = 0L;
        ptsBaseUs = Long.MIN_VALUE;
        wallBaseNs = 0L;
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Exception ignored) {}
        }
        codec = null;
    }

    private void drainOutput() {
        MediaCodec c = codec;
        if (c == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = c.dequeueOutputBuffer(info, 0);
            if (idx < 0) break;
            recordOutputFrameTime();
            if (scheduledOutputBufferRelease) {
                long ptsUs = info.presentationTimeUs;
                if (ptsBaseUs == Long.MIN_VALUE) {
                    ptsBaseUs = ptsUs;
                    wallBaseNs = System.nanoTime();
                }
                c.releaseOutputBuffer(idx, wallBaseNs + (ptsUs - ptsBaseUs) * 1000L);
            } else {
                c.releaseOutputBuffer(idx, true);
            }
        }
    }

    public void release() {
        synchronized (lock) {
            stopCodec();
            pipeline.release();
            fps = 0;
            bitrateBps = 0;
            frameCount = 0;
            codecName = "";
            droppedFrames = 0;
            framePacingJitterUs = 0;
            framesThisSec = 0;
            bytesThisSec = 0;
            frameIntervalIdx = 0;
            frameIntervalCount = 0;
            lastOutputFrameNs = 0L;
        }
    }

    private void recordOutputFrameTime() {
        long now = System.nanoTime();
        if (lastOutputFrameNs > 0) {
            frameIntervalsNs[frameIntervalIdx % frameIntervalsNs.length] = now - lastOutputFrameNs;
            frameIntervalIdx++;
            frameIntervalCount++;
        }
        lastOutputFrameNs = now;
    }

    private long computeFramePacingJitterUs() {
        int count = Math.min(frameIntervalCount, frameIntervalsNs.length);
        if (count < 2) return 0;
        double sum = 0.0;
        double sumSq = 0.0;
        for (int i = 0; i < count; i++) {
            double interval = (double) frameIntervalsNs[i];
            sum += interval;
            sumSq += interval * interval;
        }
        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);
        if (variance < 0.0) variance = 0.0;
        return (long) (Math.sqrt(variance) / 1000.0);
    }

    public static boolean supportsH265() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) return true;
            }
        }
        return false;
    }
}
