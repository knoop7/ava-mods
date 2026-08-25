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

public final class FaceEngine {

    private static final String TAG = "FaceEngine";
    private static final String MODEL_SHORT = "face_detection_short_range.tflite";
    private static final String MODEL_SPARSE = "face_detection_full_range_sparse.tflite";
    private static final int INPUT_SIZE = 128;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float IOU_THRESHOLD = 0.3f;

    private Interpreter interpreter;
    private final List<float[]> anchors;
    private final int inputSize;

    public static final class Result {
        public final int count;
        public Result(int count) { this.count = count; }
    }

    public FaceEngine(Context context, String range) {
        inputSize = INPUT_SIZE;
        String modelName = "sparse".equals(range) ? MODEL_SPARSE : MODEL_SHORT;
        anchors = generateAnchors();
        try {
            java.io.File modelFile = resolveModelFile(context, modelName);
            if (modelFile == null || !modelFile.exists()) {
                Log.e(TAG, "Model not found: " + modelName);
                interpreter = null;
                return;
            }
            MappedByteBuffer model = loadModelFromFile(modelFile);
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(2);
            opts.setUseXNNPACK(true);
            interpreter = new Interpreter(model, opts);
        } catch (Exception e) {
            Log.e(TAG, "Model load failed: " + e.getMessage());
            interpreter = null;
        }
    }

    public Result detect(Bitmap bitmap) {
        if (interpreter == null) return new Result(0);

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        ByteBuffer input = toByteBuffer(scaled);
        if (scaled != bitmap) scaled.recycle();

        float[][][] regressors = new float[1][896][16];
        float[][][] classifiers = new float[1][896][1];

        Object[] inputs = { input };
        java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, regressors);
        outputs.put(1, classifiers);

        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs);
        } catch (Exception e) {
            Log.w(TAG, "Inference error: " + e.getMessage());
            return new Result(0);
        }

        List<float[]> detections = new ArrayList<>();
        for (int i = 0; i < Math.min(896, anchors.size()); i++) {
            float score = sigmoid(classifiers[0][i][0]);
            if (score < CONFIDENCE_THRESHOLD) continue;

            float[] anchor = anchors.get(i);
            float cx = (anchor[0] + regressors[0][i][0]) / inputSize;
            float cy = (anchor[1] + regressors[0][i][1]) / inputSize;
            float w = regressors[0][i][2] / inputSize;
            float h = regressors[0][i][3] / inputSize;

            float left = cx - w / 2f;
            float top = cy - h / 2f;
            float right = cx + w / 2f;
            float bottom = cy + h / 2f;

            if (right > left && bottom > top && w > 0.02f && h > 0.02f) {
                detections.add(new float[]{ score, left, top, right, bottom });
            }
        }

        List<float[]> nms = nms(detections);
        return new Result(nms.size());
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    private List<float[]> generateAnchors() {
        List<float[]> result = new ArrayList<>();
        int[] strides = { 8, 16 };
        int[] anchorCounts = { 2, 6 };
        for (int idx = 0; idx < strides.length; idx++) {
            int stride = strides[idx];
            int gridSize = inputSize / stride;
            int count = anchorCounts[idx];
            for (int y = 0; y < gridSize; y++) {
                for (int x = 0; x < gridSize; x++) {
                    float cx = (x + 0.5f) * stride;
                    float cy = (y + 0.5f) * stride;
                    for (int n = 0; n < count; n++) {
                        result.add(new float[]{ cx, cy });
                    }
                }
            }
        }
        return result;
    }

    private ByteBuffer toByteBuffer(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[inputSize * inputSize];
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);
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

    private static List<float[]> nms(List<float[]> detections) {
        if (detections.isEmpty()) return detections;
        detections.sort((a, b) -> Float.compare(b[0], a[0]));
        List<float[]> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                if (iou(detections.get(i), detections.get(j)) > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private static float iou(float[] a, float[] b) {
        float interLeft = Math.max(a[1], b[1]);
        float interTop = Math.max(a[2], b[2]);
        float interRight = Math.min(a[3], b[3]);
        float interBottom = Math.min(a[4], b[4]);
        if (interRight <= interLeft || interBottom <= interTop) return 0f;
        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float aArea = (a[3] - a[1]) * (a[4] - a[2]);
        float bArea = (b[3] - b[1]) * (b[4] - b[2]);
        return interArea / (aArea + bArea - interArea);
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
        FileInputStream fis = new FileInputStream(file);
        FileChannel channel = fis.getChannel();
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
        fis.close();
        return buffer;
    }
}
