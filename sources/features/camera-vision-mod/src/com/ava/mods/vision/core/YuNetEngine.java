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
 * YuNet face detector (libfacedetection, BSD/Apache) — 76K parameters yet far
 * stronger on small faces than BlazeFace: trained down to ~10px, so a person
 * 4-5m from a wall panel still registers where BlazeFace has long gone blind.
 *
 * Anchor-free head over strides {8,16,32}. The TFLite export carries twelve
 * outputs — cls, obj, bbox and landmarks per stride — which are identified here
 * by shape rather than index so a re-export cannot silently scramble them. The
 * final score is cls*obj (both already sigmoid), commutative, so telling cls
 * from obj apart does not matter.
 */
public final class YuNetEngine implements FaceBackend {

    private static final String TAG = "YuNetEngine";

    /** Threshold on the cls*obj product; 0.45 ≈ 0.67 in single-score terms. */
    private static final float SCORE_THRESHOLD = 0.45f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final int[] STRIDES = {8, 16, 32};

    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static final class Head {
        final int stride;
        final int cellsPerRow;
        final int cells;
        float[][][] scoreA;
        float[][][] scoreB;
        float[][][] box;

        Head(int stride, int inputSize) {
            this.stride = stride;
            this.cellsPerRow = inputSize / stride;
            this.cells = cellsPerRow * cellsPerRow;
        }

        boolean complete() {
            return scoreA != null && scoreB != null && box != null;
        }
    }

    private Interpreter interpreter;
    private int inputSize;
    private String error = "";
    private final Head[] heads = new Head[STRIDES.length];
    private final Map<Integer, Object> outputs = new HashMap<>();
    private final FaceStabilizer stabilizer = new FaceStabilizer();

    private Bitmap inputFrame;
    private Canvas inputCanvas;
    private ByteBuffer inputBuffer;
    private int[] pixelScratch;

    public YuNetEngine(Context context) {
        if (!TfLiteRuntime.ensureLoaded(context)) {
            error = TfLiteRuntime.getError();
            return;
        }
        File modelFile = ModelStore.require(context, ModelStore.FACE_YUNET);
        if (modelFile == null) {
            error = "Face model unavailable: " + ModelStore.FACE_YUNET;
            Log.e(TAG, error);
            return;
        }
        try {
            Interpreter candidate = new Interpreter(map(modelFile), buildOptions());
            int[] in = candidate.getInputTensor(0).shape();
            if (in.length != 4 || in[1] != in[2]) {
                candidate.close();
                error = "Unexpected YuNet input " + java.util.Arrays.toString(in);
                Log.e(TAG, error);
                return;
            }
            inputSize = in[1];
            for (int i = 0; i < STRIDES.length; i++) {
                heads[i] = new Head(STRIDES[i], inputSize);
            }
            if (!bindOutputs(candidate)) {
                candidate.close();
                return;
            }
            inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            pixelScratch = new int[inputSize * inputSize];
            interpreter = candidate;
            Log.i(TAG, "YuNet loaded, input " + inputSize + "px");
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            Log.e(TAG, "YuNet load failed", e);
            interpreter = null;
        }
    }

    /** Landmark tensors (C==10) are left unmapped; TFLite skips copying them out. */
    private boolean bindOutputs(Interpreter candidate) {
        for (int i = 0; i < candidate.getOutputTensorCount(); i++) {
            int[] shape = candidate.getOutputTensor(i).shape();
            if (shape.length != 3) continue;
            Head head = headFor(shape[1]);
            if (head == null) continue;
            int channels = shape[2];
            if (channels == 1) {
                float[][][] arr = new float[1][head.cells][1];
                if (head.scoreA == null) {
                    head.scoreA = arr;
                } else if (head.scoreB == null) {
                    head.scoreB = arr;
                } else {
                    continue;
                }
                outputs.put(i, arr);
            } else if (channels == 4) {
                head.box = new float[1][head.cells][4];
                outputs.put(i, head.box);
            }
        }
        for (Head head : heads) {
            if (!head.complete()) {
                error = "YuNet outputs incomplete for stride " + head.stride;
                Log.e(TAG, error);
                return false;
            }
        }
        return true;
    }

    private Head headFor(int cells) {
        for (Head head : heads) {
            if (head.cells == cells) return head;
        }
        return null;
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
            Log.w(TAG, "YuNet inference error: " + e.getMessage());
            return stabilizer.current();
        }

        List<float[]> hits = new ArrayList<>();
        for (Head head : heads) {
            for (int i = 0; i < head.cells; i++) {
                float score = head.scoreA[0][i][0] * head.scoreB[0][i][0];
                if (score < SCORE_THRESHOLD) continue;
                int col = i % head.cellsPerRow;
                int row = i / head.cellsPerRow;
                float cx = (col + head.box[0][i][0]) * head.stride;
                float cy = (row + head.box[0][i][1]) * head.stride;
                float w = (float) Math.exp(head.box[0][i][2]) * head.stride;
                float h = (float) Math.exp(head.box[0][i][3]) * head.stride;
                if (w < 4f || h < 4f) continue;
                hits.add(new float[] {
                        score, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f
                });
            }
        }

        return stabilizer.offer(nms(hits).size());
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

    /** YuNet expects BGR channel order at 0-255 with no normalization. */
    private ByteBuffer fillInput(Bitmap bitmap) {
        ByteBuffer buffer = inputBuffer;
        buffer.clear();
        int[] pixels = pixelScratch;
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);
        for (int pixel : pixels) {
            buffer.putFloat(pixel & 0xFF);
            buffer.putFloat((pixel >> 8) & 0xFF);
            buffer.putFloat((pixel >> 16) & 0xFF);
        }
        buffer.rewind();
        return buffer;
    }

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
