package com.ava.mods.camerastream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H.264 encoder fed by a Camera2 Surface.
 * Preference: auto (hardware → system → software), hardware-only, or software-only.
 */
public final class H264Encoder {

    public interface NalListener {
        void onSpsPps(byte[] sps, byte[] pps);

        void onNal(byte[] annexB, boolean isKeyFrame, long ptsUs);
    }

    private static final String TAG = "H264Encoder";
    private static final String MIME = "video/avc";
    /** Sentinel: use {@link MediaCodec#createEncoderByType(String)}. */
    private static final String SYSTEM_DEFAULT = "";

    private final StreamConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread drainThread;
    private NalListener listener;
    private byte[] sps;
    private byte[] pps;
    private int width = 640;
    private int height = 480;
    private String activeCodecName = "";

    public H264Encoder(StreamConfig config) {
        this.config = config;
    }

    public void setListener(NalListener listener) {
        this.listener = listener;
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public String getActiveCodecName() {
        return activeCodecName == null ? "" : activeCodecName;
    }

    public byte[] getSps() {
        return sps;
    }

    public byte[] getPps() {
        return pps;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public synchronized boolean start() {
        if (running.get()) return inputSurface != null;
        int shortEdge = Math.max(240, Math.min(config.resolution, 720));
        // 4:3 capture size commonly available
        width = shortEdge * 4 / 3;
        height = shortEdge;

        String mode = config.normalizedEncoder();
        List<String> candidates = buildCandidates(mode);
        Exception lastError = null;
        for (String name : candidates) {
            try {
                if (tryStart(name)) {
                    Log.i(TAG, "Encoder started mode=" + mode
                            + " codec=" + activeCodecName
                            + " " + width + "x" + height + "@" + config.fps);
                    return true;
                }
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Encoder candidate failed: "
                        + (name.isEmpty() ? "system-default" : name)
                        + " — " + e.getMessage());
                releaseQuietly();
            }
        }
        Log.e(TAG, "No usable H.264 encoder for mode=" + mode, lastError);
        releaseQuietly();
        return false;
    }

    private boolean tryStart(String codecName) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,
                Math.max(200_000, config.bitrateKbps * 1000));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, Math.min(config.fps, 15)));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        MediaCodec created;
        if (codecName == null || codecName.isEmpty()) {
            created = MediaCodec.createEncoderByType(MIME);
        } else {
            created = MediaCodec.createByCodecName(codecName);
        }
        codec = created;
        activeCodecName = created.getName();
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
        running.set(true);
        drainThread = new Thread(this::drainLoop, "ava-h264-drain");
        drainThread.start();
        return true;
    }

    /**
     * Build an ordered, de-duplicated candidate list.
     * Empty string means system {@code createEncoderByType}.
     */
    private static List<String> buildCandidates(String mode) {
        List<String> hardware = new ArrayList<>();
        List<String> software = new ArrayList<>();
        collectAvcSurfaceEncoders(hardware, software);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (StreamConfig.ENCODER_SOFTWARE.equals(mode)) {
            out.addAll(software);
            // Common software names if MediaCodecList missed Surface capability.
            out.add("c2.android.avc.encoder");
            out.add("OMX.google.h264.encoder");
        } else if (StreamConfig.ENCODER_HARDWARE.equals(mode)) {
            out.addAll(hardware);
            // System default usually picks vendor HW — try only after explicit HW names.
            if (!hardware.isEmpty()) {
                out.add(SYSTEM_DEFAULT);
            } else {
                // No classified HW codec; still try system default once.
                out.add(SYSTEM_DEFAULT);
            }
        } else {
            // auto: hardware → system default → software
            out.addAll(hardware);
            out.add(SYSTEM_DEFAULT);
            out.addAll(software);
            out.add("c2.android.avc.encoder");
            out.add("OMX.google.h264.encoder");
        }
        return new ArrayList<>(out);
    }

    private static void collectAvcSurfaceEncoders(List<String> hardware, List<String> software) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                String[] types = info.getSupportedTypes();
                if (types == null) continue;
                boolean supportsAvc = false;
                for (String t : types) {
                    if (MIME.equalsIgnoreCase(t)) {
                        supportsAvc = true;
                        break;
                    }
                }
                if (!supportsAvc) continue;
                if (!supportsSurfaceInput(info)) continue;

                String name = info.getName();
                if (isSoftwareEncoder(info, name)) {
                    software.add(name);
                } else if (isHardwareEncoder(info, name)) {
                    hardware.add(name);
                } else {
                    // Unknown — treat as hardware-leaning so auto still tries it early.
                    hardware.add(name);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaCodecList scan failed: " + t.getMessage());
        }
    }

    private static boolean supportsSurfaceInput(MediaCodecInfo info) {
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
            if (caps == null || caps.colorFormats == null) return false;
            for (int fmt : caps.colorFormats) {
                if (fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isSoftwareEncoder(MediaCodecInfo info, String name) {
        Boolean flagged = invokeBool(info, "isSoftwareOnly");
        if (flagged != null) return flagged;
        String n = name.toLowerCase(Locale.US);
        return n.contains("google")
                || n.contains("c2.android")
                || n.contains("omx.google")
                || n.contains(".sw.")
                || n.contains("soft");
    }

    private static boolean isHardwareEncoder(MediaCodecInfo info, String name) {
        Boolean flagged = invokeBool(info, "isHardwareAccelerated");
        if (flagged != null) return flagged;
        // If not software by name, assume vendor/hardware.
        return !isSoftwareEncoder(info, name);
    }

    private static Boolean invokeBool(MediaCodecInfo info, String method) {
        try {
            Object v = MediaCodecInfo.class.getMethod(method).invoke(info);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    public synchronized void requestKeyFrame() {
        if (codec == null || !running.get()) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (drainThread != null) {
            try {
                drainThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        releaseQuietly();
        activeCodecName = "";
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get()) {
            MediaCodec local = codec;
            if (local == null) break;
            try {
                int outIndex = local.dequeueOutputBuffer(info, 20_000);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt = local.getOutputFormat();
                    ByteBuffer spsBuf = fmt.getByteBuffer("csd-0");
                    ByteBuffer ppsBuf = fmt.getByteBuffer("csd-1");
                    if (spsBuf != null) sps = toArray(spsBuf);
                    if (ppsBuf != null) pps = toArray(ppsBuf);
                    if (listener != null && sps != null && pps != null) {
                        listener.onSpsPps(stripStartCode(sps), stripStartCode(pps));
                    }
                    continue;
                }
                if (outIndex < 0) continue;

                ByteBuffer buffer = local.getOutputBuffer(outIndex);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    buffer.get(data);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        parseCodecConfig(data);
                    } else if (listener != null) {
                        listener.onNal(data, key, info.presentationTimeUs);
                    }
                }
                local.releaseOutputBuffer(outIndex, false);
            } catch (IllegalStateException e) {
                if (running.get()) {
                    Log.w(TAG, "drain: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void parseCodecConfig(byte[] data) {
        // Annex-B config may contain SPS+PPS
        int i = 0;
        byte[] foundSps = null;
        byte[] foundPps = null;
        while (i + 4 < data.length) {
            int sc = startCodeLength(data, i);
            if (sc == 0) {
                i++;
                continue;
            }
            int nalStart = i + sc;
            int next = findStartCode(data, nalStart);
            if (next < 0) next = data.length;
            int nalType = data[nalStart] & 0x1f;
            byte[] nal = new byte[next - nalStart];
            System.arraycopy(data, nalStart, nal, 0, nal.length);
            if (nalType == 7) foundSps = nal;
            if (nalType == 8) foundPps = nal;
            i = next;
        }
        if (foundSps != null) sps = prependStartCode(foundSps);
        if (foundPps != null) pps = prependStartCode(foundPps);
        if (listener != null && foundSps != null && foundPps != null) {
            listener.onSpsPps(foundSps, foundPps);
        }
    }

    private void releaseQuietly() {
        running.set(false);
        try {
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Exception ignored) {
        }
        inputSurface = null;
        try {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
        } catch (Exception ignored) {
        }
        codec = null;
    }

    private static byte[] toArray(ByteBuffer buffer) {
        buffer = buffer.duplicate();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private static byte[] stripStartCode(byte[] data) {
        int sc = startCodeLength(data, 0);
        if (sc == 0) return data;
        byte[] out = new byte[data.length - sc];
        System.arraycopy(data, sc, out, 0, out.length);
        return out;
    }

    private static byte[] prependStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static int startCodeLength(byte[] data, int i) {
        if (i + 3 < data.length
                && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
            return 4;
        }
        if (i + 2 < data.length
                && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            return 3;
        }
        return 0;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = from; i + 3 < data.length; i++) {
            if (startCodeLength(data, i) > 0) return i;
        }
        return -1;
    }
}
