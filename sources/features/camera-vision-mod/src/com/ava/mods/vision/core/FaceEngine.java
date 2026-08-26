package com.ava.mods.vision.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
public final class FaceEngine implements FaceBackend {

    private static final String TAG = "FaceEngine";
    private static final float CONFIDENCE_THRESHOLD = 0.6f;
    private static final float IOU_THRESHOLD = 0.3f;

    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Interpreter interpreter;
    private List<float[]> anchors;
    private int inputSize;
    private int numAnchors;
    private int regressorStride;
    private String error = "";

    private final FaceStabilizer stabilizer = new FaceStabilizer();

    private Bitmap inputFrame;
    private Canvas inputCanvas;

    /**
     * Allocated once: a fresh direct ByteBuffer plus fresh output arrays per frame
     * (~600KB combined) made every detection pay allocation and GC cost, which
     * showed up as user-visible recognition lag.
     */
    private ByteBuffer inputBuffer;
    private int[] pixelScratch;
    private float[][][] regressors;
    private float[][][] scores;
    private final Map<Integer, Object> outputs = new HashMap<>();

    public FaceEngine(Context context, String range) {
        if (!TfLiteRuntime.ensureLoaded(context)) {
            error = TfLiteRuntime.getError();
            return;
        }
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
            inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            pixelScratch = new int[inputSize * inputSize];
            regressors = new float[1][numAnchors][regressorStride];
            scores = new float[1][numAnchors][1];
            outputs.put(0, regressors);
            outputs.put(1, scores);
            interpreter = candidate;
            Log.i(TAG, "Face model " + name + " " + inputSize + "px anchors=" + numAnchors);
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            Log.e(TAG, "Face model load failed", e);
            interpreter = null;
        }
    }

    @Override
    public boolean isReady() {
        return interpreter != null;
    }

    @Override
    public String getError() {
        return error;
    }

    @Override
    public FaceResult detect(Bitmap bitmap) {
        Interpreter local = interpreter;
        if (local == null) return new FaceResult(0, false);

        ByteBuffer input = fillInput(letterbox(bitmap));

        try {
            local.runForMultipleInputsOutputs(new Object[] { input }, outputs);
        } catch (Exception e) {
            Log.w(TAG, "Face inference error: " + e.getMessage());
            return stabilizer.current();
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

        return stabilizer.offer(nms(hits).size());
    }

    /**
     * Scales the frame into a square, padding rather than stretching. BlazeFace is
     * trained on square letterboxed input, so squashing a 4:3 frame widens every
     * face and measurably cuts detection range. Only the face count leaves this
     * class, so the padding never has to be mapped back out.
     */
    private Bitmap letterbox(Bitmap src) {
        if (inputFrame == null) {
            inputFrame = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888);
            inputCanvas = new Canvas(inputFrame);
        }
        inputCanvas.drawColor(Color.BLACK);
        float scale = Math.min(
                (float) inputSize / src.getWidth(),
                (float) inputSize / src.getHeight());
        float w = src.getWidth() * scale;
        float h = src.getHeight() * scale;
        float left = (inputSize - w) / 2f;
        float top = (inputSize - h) / 2f;
        inputCanvas.drawBitmap(src, null, new RectF(left, top, left + w, top + h), FILTER);
        return inputFrame;
    }

    @Override
    public void close() {
        Interpreter local = interpreter;
        interpreter = null;
        if (local != null) local.close();
        Bitmap frame = inputFrame;
        inputFrame = null;
        inputCanvas = null;
        if (frame != null) frame.recycle();
    }

    /**
     * Four threads instead of two: inference runs in short bursts a few times a
     * second, so the extra cores cut per-frame latency without a sustained load.
     */
    private static Interpreter.Options buildOptions() {
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(4);
        try {
            opts.setUseXNNPACK(true);
        } catch (Throwable t) {
            Log.w(TAG, "XNNPACK unavailable: " + t.getMessage());
        }
        return opts;
    }

    private ByteBuffer fillInput(Bitmap bitmap) {
        ByteBuffer buffer = inputBuffer;
        buffer.clear();
        int[] pixels = pixelScratch;
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
