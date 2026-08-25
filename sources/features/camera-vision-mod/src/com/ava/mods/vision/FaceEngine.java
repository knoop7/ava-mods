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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BlazeFace detector. Input size and anchor count are read from the model at load
 * time: short-range is 128x128 with 896 anchors, full-range sparse is 192x192 with
 * 2304, and hardcoding either one silently breaks the other.
 */
public final class FaceEngine {

    private static final String TAG = "FaceEngine";
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float IOU_THRESHOLD = 0.3f;

    private Interpreter interpreter;
    private List<float[]> anchors;
    private int inputSize;
    private int numAnchors;
    private int regressorStride;
    private String error = "";

    public static final class Result {
        public final int count;

        public Result(int count) {
            this.count = count;
        }
    }

    public FaceEngine(Context context, String range) {
        String name = "short".equals(range) ? ModelStore.FACE_SHORT : ModelStore.FACE_SPARSE;
        File modelFile = ModelStore.require(context, name);
        if (modelFile == null) {
            error = "Face model unavailable: " + name;
            Log.e(TAG, error);
            return;
        }
        try {
            Interpreter candidate = new Interpreter(map(modelFile), buildOptions());
            int[] in = candidate.getInputTensor(0).shape();
            int[] out = candidate.getOutputTensor(0).shape();
            if (in.length < 3 || out.length < 3) {
                candidate.close();
                error = "Unexpected face model tensors";
                Log.e(TAG, error);
                return;
            }
            inputSize = in[1];
            numAnchors = out[1];
            regressorStride = out[2];
            anchors = SsdAnchors.forModel(inputSize, numAnchors);
            if (anchors == null) {
                candidate.close();
                error = "No anchor layout for " + inputSize + "/" + numAnchors;
                Log.e(TAG, error);
                return;
            }
            interpreter = candidate;
            Log.i(TAG, "Face model " + name + " " + inputSize + "px anchors=" + numAnchors);
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            Log.e(TAG, "Face model load failed", e);
            interpreter = null;
        }
    }

    public boolean isReady() {
        return interpreter != null;
    }

    public String getError() {
        return error;
    }

    public Result detect(Bitmap bitmap) {
        Interpreter local = interpreter;
        if (local == null) return new Result(0);

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        ByteBuffer input = toByteBuffer(scaled, inputSize);
        if (scaled != bitmap) scaled.recycle();

        float[][][] regressors = new float[1][numAnchors][regressorStride];
        float[][][] scores = new float[1][numAnchors][1];

        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, regressors);
        outputs.put(1, scores);

        try {
            local.runForMultipleInputsOutputs(new Object[] { input }, outputs);
        } catch (Exception e) {
            Log.w(TAG, "Face inference error: " + e.getMessage());
            return new Result(0);
        }

        List<float[]> hits = new ArrayList<>();
        for (int i = 0; i < numAnchors; i++) {
            float score = sigmoid(scores[0][i][0]);
            if (score < CONFIDENCE_THRESHOLD) continue;

            float[] anchor = anchors.get(i);
            float cx = (anchor[0] + regressors[0][i][0]) / inputSize;
            float cy = (anchor[1] + regressors[0][i][1]) / inputSize;
            float w = regressors[0][i][2] / inputSize;
            float h = regressors[0][i][3] / inputSize;
            if (w <= 0.02f || h <= 0.02f) continue;

            hits.add(new float[] {
                    score, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f
            });
        }

        return new Result(nms(hits).size());
    }

    public void close() {
        Interpreter local = interpreter;
        interpreter = null;
        if (local != null) local.close();
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

    private static List<float[]> nms(List<float[]> boxes) {
        if (boxes.size() < 2) return boxes;
        boxes.sort((a, b) -> Float.compare(b[0], a[0]));
        List<float[]> kept = new ArrayList<>();
        boolean[] dead = new boolean[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            if (dead[i]) continue;
            kept.add(boxes.get(i));
            for (int j = i + 1; j < boxes.size(); j++) {
                if (!dead[j] && iou(boxes.get(i), boxes.get(j)) > IOU_THRESHOLD) {
                    dead[j] = true;
                }
            }
        }
        return kept;
    }

    private static float iou(float[] a, float[] b) {
        float left = Math.max(a[1], b[1]);
        float top = Math.max(a[2], b[2]);
        float right = Math.min(a[3], b[3]);
        float bottom = Math.min(a[4], b[4]);
        if (right <= left || bottom <= top) return 0f;
        float inter = (right - left) * (bottom - top);
        float areaA = (a[3] - a[1]) * (a[4] - a[2]);
        float areaB = (b[3] - b[1]) * (b[4] - b[2]);
        return inter / (areaA + areaB - inter);
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
