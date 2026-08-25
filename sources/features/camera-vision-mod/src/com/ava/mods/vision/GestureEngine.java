package com.ava.mods.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public final class GestureEngine {

    private static final String TAG = "GestureEngine";
    private static final String PALM_MODEL = "palm_detection_lite.tflite";
    private static final String LANDMARK_MODEL = "hand_landmark_lite.tflite";
    private static final int PALM_INPUT_SIZE = 192;
    private static final int LANDMARK_INPUT_SIZE = 224;
    private static final float PALM_THRESHOLD = 0.5f;

    private static final int[][] FINGER_TIPS = {
            {4, 3, 2},
            {8, 7, 6},
            {12, 11, 10},
            {16, 15, 14},
            {20, 19, 18}
    };

    private Interpreter palmInterpreter;
    private Interpreter landmarkInterpreter;
    private boolean initialized;

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
        try {
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(2);
            opts.setUseXNNPACK(true);

            java.io.File palmFile = resolveModelFile(context, PALM_MODEL);
            java.io.File lmFile = resolveModelFile(context, LANDMARK_MODEL);

            if (palmFile == null || !palmFile.exists()) {
                Log.e(TAG, "Palm model not found: " + PALM_MODEL);
                initialized = false;
                return;
            }
            if (lmFile == null || !lmFile.exists()) {
                Log.e(TAG, "Landmark model not found: " + LANDMARK_MODEL);
                initialized = false;
                return;
            }

            MappedByteBuffer palmModel = loadModelFromFile(palmFile);
            palmInterpreter = new Interpreter(palmModel, opts);

            MappedByteBuffer lmModel = loadModelFromFile(lmFile);
            landmarkInterpreter = new Interpreter(lmModel, opts);

            initialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Model load failed: " + e.getMessage());
            initialized = false;
        }
    }

    public Result detect(Bitmap bitmap) {
        if (!initialized) return new Result("", false, 0);

        float[] palmBox = detectPalm(bitmap);
        if (palmBox == null) return new Result("none", false, 0);

        float[][] landmarks = detectLandmarks(bitmap, palmBox);
        if (landmarks == null) return new Result("none", false, 0);

        int fingers = countExtendedFingers(landmarks);
        boolean open = fingers == 5;
        String gesture;
        switch (fingers) {
            case 0: gesture = "fist"; break;
            case 1: gesture = "pointing"; break;
            case 2: gesture = "peace"; break;
            case 3: gesture = "three"; break;
            case 4: gesture = "four"; break;
            case 5: gesture = "open_palm"; break;
            default: gesture = "unknown"; break;
        }

        return new Result(gesture, open, fingers);
    }

    private float[] detectPalm(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, PALM_INPUT_SIZE, PALM_INPUT_SIZE, true);
        ByteBuffer input = toByteBuffer(scaled, PALM_INPUT_SIZE);
        if (scaled != bitmap) scaled.recycle();

        float[][][] boxes = new float[1][2016][18];
        float[][][] scores = new float[1][2016][1];

        Object[] inputs = { input };
        java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, boxes);
        outputs.put(1, scores);

        try {
            palmInterpreter.runForMultipleInputsOutputs(inputs, outputs);
        } catch (Exception e) {
            Log.w(TAG, "Palm inference error: " + e.getMessage());
            return null;
        }

        int bestIdx = -1;
        float bestScore = PALM_THRESHOLD;
        for (int i = 0; i < 2016; i++) {
            float s = sigmoid(scores[0][i][0]);
            if (s > bestScore) {
                bestScore = s;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;

        float cx = boxes[0][bestIdx][0] / PALM_INPUT_SIZE;
        float cy = boxes[0][bestIdx][1] / PALM_INPUT_SIZE;
        float w = boxes[0][bestIdx][2] / PALM_INPUT_SIZE;
        float h = boxes[0][bestIdx][3] / PALM_INPUT_SIZE;

        float left = Math.max(0, cx - w / 2f - 0.1f);
        float top = Math.max(0, cy - h / 2f - 0.1f);
        float right = Math.min(1, cx + w / 2f + 0.1f);
        float bottom = Math.min(1, cy + h / 2f + 0.1f);

        return new float[]{ left, top, right, bottom };
    }

    private float[][] detectLandmarks(Bitmap bitmap, float[] box) {
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        int x = (int) (box[0] * bw);
        int y = (int) (box[1] * bh);
        int w = (int) ((box[2] - box[0]) * bw);
        int h = (int) ((box[3] - box[1]) * bh);
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, bw - x);
        h = Math.min(h, bh - y);
        if (w <= 0 || h <= 0) return null;

        Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, w, h);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, LANDMARK_INPUT_SIZE, LANDMARK_INPUT_SIZE, true);
        if (cropped != bitmap) cropped.recycle();

        ByteBuffer input = toByteBuffer(scaled, LANDMARK_INPUT_SIZE);
        scaled.recycle();

        float[][] landmarks = new float[1][63];
        float[][] handedness = new float[1][1];

        Object[] inputs = { input };
        java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, landmarks);
        outputs.put(1, handedness);

        try {
            landmarkInterpreter.runForMultipleInputsOutputs(inputs, outputs);
        } catch (Exception e) {
            Log.w(TAG, "Landmark inference error: " + e.getMessage());
            return null;
        }

        float[][] points = new float[21][3];
        for (int i = 0; i < 21; i++) {
            points[i][0] = landmarks[0][i * 3] / LANDMARK_INPUT_SIZE;
            points[i][1] = landmarks[0][i * 3 + 1] / LANDMARK_INPUT_SIZE;
            points[i][2] = landmarks[0][i * 3 + 2];
        }
        return points;
    }

    private int countExtendedFingers(float[][] landmarks) {
        int count = 0;

        float thumbTipX = landmarks[4][0];
        float thumbIpX = landmarks[3][0];
        float wristX = landmarks[0][0];
        boolean thumbExtended = (wristX < 0.5f)
                ? (thumbTipX > thumbIpX + 0.02f)
                : (thumbTipX < thumbIpX - 0.02f);
        if (thumbExtended) count++;

        for (int f = 1; f < 5; f++) {
            int tip = FINGER_TIPS[f][0];
            int pip = FINGER_TIPS[f][2];
            if (landmarks[tip][1] < landmarks[pip][1] - 0.02f) {
                count++;
            }
        }
        return count;
    }

    public void close() {
        initialized = false;
        if (palmInterpreter != null) { palmInterpreter.close(); palmInterpreter = null; }
        if (landmarkInterpreter != null) { landmarkInterpreter.close(); landmarkInterpreter = null; }
    }

    private ByteBuffer toByteBuffer(Bitmap bitmap, int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 * size * size * 3 * 4);
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

    private static java.io.File resolveModelFile(Context context, String name) {
        java.io.File modDir = new java.io.File(context.getFilesDir(), "mods/camera-vision-mod/models");
        java.io.File f = new java.io.File(modDir, name);
        if (f.exists()) return f;
        java.io.File alt = new java.io.File(modDir.getParentFile(), name);
        if (alt.exists()) return alt;
        return f;
    }

    private static MappedByteBuffer loadModelFromFile(java.io.File file) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        FileChannel channel = fis.getChannel();
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
        fis.close();
        return buffer;
    }
}
