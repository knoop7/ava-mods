package com.ava.mods.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-stage hand pipeline: BlazePalm finds the hand box, then the landmark model
 * returns 21 points from that crop. Tensor geometry is read from the models so a
 * different lite/full variant cannot silently break inference.
 */
public final class GestureEngine {

    private static final String TAG = "GestureEngine";
    private static final float PALM_THRESHOLD = 0.5f;
    private static final int LANDMARK_POINTS = 21;

    /**
     * BlazePalm returns a box around the palm only. MediaPipe's palm-to-hand
     * transform grows it 2.6x and shifts it up half a height so the fingers are
     * inside the crop the landmark model sees.
     */
    private static final float HAND_SCALE = 2.6f;
    private static final float HAND_SHIFT_Y = -0.5f;

    /** Index triples of tip / dip / pip per finger, thumb first. */
    private static final int[][] FINGERS = {
            { 4, 3, 2 },
            { 8, 7, 6 },
            { 12, 11, 10 },
            { 16, 15, 14 },
            { 20, 19, 18 }
    };

    private Interpreter palm;
    private Interpreter landmark;
    private List<float[]> palmAnchorCenters;
    private int palmInputSize;
    private int palmAnchorCount;
    private int palmStride;
    private int landmarkInputSize;
    private int landmarkValues;
    private String error = "";

    public static final class Result {
        public final String gesture;
        public final boolean openPalm;
        public final int fingerCount;

        public Result(String gesture, boolean openPalm, int fingerCount) {
            this.gesture = gesture;
            this.openPalm = openPalm;
            this.fingerCount = fingerCount;
        }
    }

    public GestureEngine(Context context) {
        File palmFile = ModelStore.require(context, ModelStore.PALM);
        File landmarkFile = ModelStore.require(context, ModelStore.HAND);
        if (palmFile == null || landmarkFile == null) {
            error = "Hand models unavailable";
            Log.e(TAG, error);
            return;
        }
        try {
            palm = new Interpreter(map(palmFile), buildOptions());
            int[] pin = palm.getInputTensor(0).shape();
            int[] pout = palm.getOutputTensor(0).shape();
            palmInputSize = pin[1];
            palmAnchorCount = pout[1];
            palmStride = pout[2];
            palmAnchorCenters = SsdAnchors.forModel(palmInputSize, palmAnchorCount);
            if (palmAnchorCenters == null) {
                error = "No anchor layout for palm " + palmInputSize + "/" + palmAnchorCount;
                Log.e(TAG, error);
                close();
                return;
            }

            landmark = new Interpreter(map(landmarkFile), buildOptions());
            int[] lin = landmark.getInputTensor(0).shape();
            int[] lout = landmark.getOutputTensor(0).shape();
            landmarkInputSize = lin[1];
            landmarkValues = lout[lout.length - 1];

            if (landmarkValues < LANDMARK_POINTS * 3) {
                error = "Landmark model returns " + landmarkValues + " values";
                Log.e(TAG, error);
                close();
                return;
            }
            Log.i(TAG, "Hand models palm=" + palmInputSize + "px/" + palmAnchorCount
                    + " landmark=" + landmarkInputSize + "px/" + landmarkValues);
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            Log.e(TAG, "Hand model load failed", e);
            close();
        }
    }

    public boolean isReady() {
        return palm != null && landmark != null;
    }

    public String getError() {
        return error;
    }

    public Result detect(Bitmap bitmap) {
        if (!isReady()) return new Result("", false, 0);

        float[] box = detectPalm(bitmap);
        if (box == null) return new Result("none", false, 0);

        float[][] points = detectLandmarks(bitmap, box);
        if (points == null) return new Result("none", false, 0);

        int fingers = countExtendedFingers(points);
        return new Result(gestureName(fingers), fingers == 5, fingers);
    }

    private static String gestureName(int fingers) {
        switch (fingers) {
            case 0: return "fist";
            case 1: return "pointing";
            case 2: return "peace";
            case 3: return "three";
            case 4: return "four";
            case 5: return "open_palm";
            default: return "unknown";
        }
    }

    private float[] detectPalm(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, palmInputSize, palmInputSize, true);
        ByteBuffer input = toByteBuffer(scaled, palmInputSize);
        if (scaled != bitmap) scaled.recycle();

        float[][][] boxes = new float[1][palmAnchorCount][palmStride];
        float[][][] scores = new float[1][palmAnchorCount][1];

        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, boxes);
        outputs.put(1, scores);

        try {
            palm.runForMultipleInputsOutputs(new Object[] { input }, outputs);
        } catch (Exception e) {
            Log.w(TAG, "Palm inference error: " + e.getMessage());
            return null;
        }

        int best = -1;
        float bestScore = PALM_THRESHOLD;
        for (int i = 0; i < palmAnchorCount; i++) {
            float s = sigmoid(scores[0][i][0]);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) return null;

        float[] anchor = palmAnchorCenters.get(best);
        float cx = (anchor[0] + boxes[0][best][0]) / palmInputSize;
        float cy = (anchor[1] + boxes[0][best][1]) / palmInputSize;
        float w = boxes[0][best][2] / palmInputSize;
        float h = boxes[0][best][3] / palmInputSize;
        if (w <= 0f || h <= 0f) return null;

        cy += h * HAND_SHIFT_Y;
        float side = Math.max(w, h) * HAND_SCALE;

        return new float[] {
                clamp01(cx - side / 2f),
                clamp01(cy - side / 2f),
                clamp01(cx + side / 2f),
                clamp01(cy + side / 2f)
        };
    }

    private float[][] detectLandmarks(Bitmap bitmap, float[] box) {
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        int x = Math.max(0, (int) (box[0] * bw));
        int y = Math.max(0, (int) (box[1] * bh));
        int w = Math.min((int) ((box[2] - box[0]) * bw), bw - x);
        int h = Math.min((int) ((box[3] - box[1]) * bh), bh - y);
        if (w <= 1 || h <= 1) return null;

        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            cropped = Bitmap.createBitmap(bitmap, x, y, w, h);
            scaled = Bitmap.createScaledBitmap(cropped, landmarkInputSize, landmarkInputSize, true);
            ByteBuffer input = toByteBuffer(scaled, landmarkInputSize);

            float[][] raw = new float[1][landmarkValues];
            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(0, raw);

            landmark.runForMultipleInputsOutputs(new Object[] { input }, outputs);

            float[][] points = new float[LANDMARK_POINTS][3];
            for (int i = 0; i < LANDMARK_POINTS; i++) {
                points[i][0] = raw[0][i * 3] / landmarkInputSize;
                points[i][1] = raw[0][i * 3 + 1] / landmarkInputSize;
                points[i][2] = raw[0][i * 3 + 2];
            }
            return points;
        } catch (Exception e) {
            Log.w(TAG, "Landmark inference error: " + e.getMessage());
            return null;
        } finally {
            if (scaled != null && scaled != bitmap) scaled.recycle();
            if (cropped != null && cropped != bitmap) cropped.recycle();
        }
    }

    /**
     * Thumb extension is measured horizontally away from the wrist; the other four
     * fingers count as extended when the tip sits above the middle joint.
     */
    private int countExtendedFingers(float[][] p) {
        int count = 0;
        boolean leftHalf = p[0][0] < 0.5f;
        float thumbTip = p[FINGERS[0][0]][0];
        float thumbDip = p[FINGERS[0][1]][0];
        boolean thumbOut = leftHalf
                ? thumbTip > thumbDip + 0.02f
                : thumbTip < thumbDip - 0.02f;
        if (thumbOut) count++;

        for (int f = 1; f < FINGERS.length; f++) {
            if (p[FINGERS[f][0]][1] < p[FINGERS[f][2]][1] - 0.02f) count++;
        }
        return count;
    }

    public void close() {
        Interpreter p = palm;
        Interpreter l = landmark;
        palm = null;
        landmark = null;
        if (p != null) p.close();
        if (l != null) l.close();
    }

    private static Interpreter.Options buildOptions() {
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(2);
        try {
            opts.setUseXNNPACK(true);
        } catch (Throwable t) {
            Log.w(TAG, "XNNPACK unavailable: " + t.getMessage());
        }
        return opts;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static ByteBuffer toByteBuffer(Bitmap bitmap, int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(size * size * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[size * size];
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 127.5f - 1f);
            buffer.putFloat(((pixel >> 8) & 0xFF) / 127.5f - 1f);
            buffer.putFloat((pixel & 0xFF) / 127.5f - 1f);
        }
        buffer.rewind();
        return buffer;
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    private static MappedByteBuffer map(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        try {
            FileChannel channel = fis.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
        } finally {
            fis.close();
        }
    }
}
