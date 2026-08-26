package com.ava.mods.vision.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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

    private static final float HAND_PRESENCE_THRESHOLD = 0.5f;

    private static final int WRIST = 0;
    private static final int THUMB_MCP = 2;
    private static final int THUMB_TIP = 4;
    private static final int MIDDLE_MCP = 9;
    private static final int PINKY_MCP = 17;

    /** Tip and PIP joint of index, middle, ring and pinky. */
    private static final int[][] FINGER_TIP_PIP = {
            { 8, 6 },
            { 12, 10 },
            { 16, 14 },
            { 20, 18 }
    };

    private static final float FINGER_MARGIN = 0.15f;
    private static final float THUMB_MARGIN = 0.20f;

    /**
     * A single bad frame must not reach Home Assistant, so a finger count is only
     * published once it wins a majority of the recent frames.
     */
    private static final int VOTE_WINDOW = 5;
    private static final int VOTE_MIN = 3;
    private static final int NO_HAND = -1;

    private Interpreter palm;
    private Interpreter landmark;
    private List<float[]> palmAnchorCenters;
    private int palmInputSize;
    private int palmAnchorCount;
    private int palmStride;
    private int landmarkInputSize;
    private int landmarkValues;
    private String error = "";

    /** Starts at NO_HAND so an empty window cannot vote itself a fist. */
    private final MajorityVote votes = new MajorityVote(VOTE_WINDOW, VOTE_MIN, NO_HAND, 5);
    private boolean loggedScalars;

    /**
     * Allocated once: both stages previously created a scaled Bitmap, a direct
     * ByteBuffer (~450-600KB) and fresh output arrays on every frame, and that
     * allocation plus GC cost was a real share of the per-recognition latency.
     */
    private Bitmap palmFrame;
    private Canvas palmCanvas;
    private RectF palmDst;
    private ByteBuffer palmInput;
    private int[] palmPixels;
    private float[][][] palmBoxes;
    private float[][][] palmScores;
    private final Map<Integer, Object> palmOutputs = new HashMap<>();

    private Bitmap landmarkFrame;
    private Canvas landmarkCanvas;
    private RectF landmarkDst;
    private ByteBuffer landmarkInput;
    private int[] landmarkPixels;
    private float[][] landmarkRaw;
    private float[][] landmarkPresence;
    private float[][] landmarkHandedness;
    private final Map<Integer, Object> landmarkOutputs = new HashMap<>();
    private float[][] landmarkPoints;
    private final Rect cropSrc = new Rect();

    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);

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
        if (!TfLiteRuntime.ensureLoaded(context)) {
            error = TfLiteRuntime.getError();
            return;
        }
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

            palmFrame = Bitmap.createBitmap(palmInputSize, palmInputSize, Bitmap.Config.ARGB_8888);
            palmCanvas = new Canvas(palmFrame);
            palmDst = new RectF(0, 0, palmInputSize, palmInputSize);
            palmInput = ByteBuffer.allocateDirect(palmInputSize * palmInputSize * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            palmPixels = new int[palmInputSize * palmInputSize];
            palmBoxes = new float[1][palmAnchorCount][palmStride];
            palmScores = new float[1][palmAnchorCount][1];
            palmOutputs.put(0, palmBoxes);
            palmOutputs.put(1, palmScores);

            landmarkFrame = Bitmap.createBitmap(
                    landmarkInputSize, landmarkInputSize, Bitmap.Config.ARGB_8888);
            landmarkCanvas = new Canvas(landmarkFrame);
            landmarkDst = new RectF(0, 0, landmarkInputSize, landmarkInputSize);
            landmarkInput = ByteBuffer.allocateDirect(landmarkInputSize * landmarkInputSize * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            landmarkPixels = new int[landmarkInputSize * landmarkInputSize];
            landmarkRaw = new float[1][landmarkValues];
            landmarkPresence = new float[1][1];
            landmarkHandedness = new float[1][1];
            landmarkOutputs.put(0, landmarkRaw);
            landmarkOutputs.put(1, landmarkPresence);
            landmarkOutputs.put(2, landmarkHandedness);
            landmarkPoints = new float[LANDMARK_POINTS][3];

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
        return stabilize(rawFingerCount(bitmap));
    }

    private int rawFingerCount(Bitmap bitmap) {
        float[] box = detectPalm(bitmap);
        if (box == null) return NO_HAND;

        float[][] points = detectLandmarks(bitmap, box);
        if (points == null) return NO_HAND;

        return countExtendedFingers(points);
    }

    private Result stabilize(int fingers) {
        int stable = votes.offer(fingers);
        if (stable == NO_HAND) return new Result("none", false, 0);
        return new Result(gestureName(stable), stable == 5, stable);
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
        palmCanvas.drawBitmap(bitmap, null, palmDst, FILTER);
        fillBuffer(palmInput, palmFrame, palmPixels, palmInputSize);

        try {
            palm.runForMultipleInputsOutputs(new Object[] { palmInput }, palmOutputs);
        } catch (Exception e) {
            Log.w(TAG, "Palm inference error: " + e.getMessage());
            return null;
        }

        int best = -1;
        float bestScore = PALM_THRESHOLD;
        for (int i = 0; i < palmAnchorCount; i++) {
            float s = sigmoid(palmScores[0][i][0]);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) return null;

        float[] anchor = palmAnchorCenters.get(best);
        float cx = (anchor[0] + palmBoxes[0][best][0]) / palmInputSize;
        float cy = (anchor[1] + palmBoxes[0][best][1]) / palmInputSize;
        float w = palmBoxes[0][best][2] / palmInputSize;
        float h = palmBoxes[0][best][3] / palmInputSize;
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

        // The palm box is normalised against a square model input, so applying it
        // straight to a 4:3 frame and rescaling to a square would squash the hand
        // and skew every landmark distance. Crop a square in pixel space instead.
        float centerX = (box[0] + box[2]) / 2f * bw;
        float centerY = (box[1] + box[3]) / 2f * bh;
        float sidePx = Math.max((box[2] - box[0]) * bw, (box[3] - box[1]) * bh);
        if (sidePx < 2f) return null;

        int side = Math.round(sidePx);
        int x = Math.max(0, Math.min(Math.round(centerX - sidePx / 2f), bw - 1));
        int y = Math.max(0, Math.min(Math.round(centerY - sidePx / 2f), bh - 1));
        int w = Math.min(side, bw - x);
        int h = Math.min(side, bh - y);
        if (w <= 1 || h <= 1) return null;

        try {
            cropSrc.set(x, y, x + w, y + h);
            landmarkCanvas.drawBitmap(bitmap, cropSrc, landmarkDst, FILTER);
            fillBuffer(landmarkInput, landmarkFrame, landmarkPixels, landmarkInputSize);

            landmark.runForMultipleInputsOutputs(new Object[] { landmarkInput }, landmarkOutputs);

            // The model already applies sigmoid: on-device the raw value for a real
            // hand reads ~0.98. Wrapping it in another sigmoid would squeeze [0,1]
            // into [0.5,0.73] and let almost any positive score through the gate.
            float handScore = probability(landmarkPresence[0][0]);
            if (!loggedScalars) {
                loggedScalars = true;
                Log.i(TAG, "landmark scalars presence=" + handScore
                        + " handedness=" + probability(landmarkHandedness[0][0]));
            }

            // Without this gate the palm detector's false positives still yield a
            // full set of landmarks, which then read as a confident random gesture.
            if (handScore < HAND_PRESENCE_THRESHOLD) return null;

            float[][] points = landmarkPoints;
            for (int i = 0; i < LANDMARK_POINTS; i++) {
                points[i][0] = landmarkRaw[0][i * 3] / landmarkInputSize;
                points[i][1] = landmarkRaw[0][i * 3 + 1] / landmarkInputSize;
                points[i][2] = landmarkRaw[0][i * 3 + 2];
            }
            return points;
        } catch (Exception e) {
            Log.w(TAG, "Landmark inference error: " + e.getMessage());
            return null;
        }
    }

    /**
     * A finger counts as extended when its tip is farther from the wrist than its
     * middle joint. Comparing distances instead of screen direction keeps the count
     * correct when the hand is rotated or upside down. The thumb folds sideways
     * rather than toward the wrist, so it is measured against the pinky knuckle.
     * Both margins scale with the palm size so distance from the camera cancels out.
     */
    private int countExtendedFingers(float[][] p) {
        float palmSize = dist(p[WRIST], p[MIDDLE_MCP]);
        if (palmSize <= 0f) return 0;

        int count = 0;
        float thumbReach = dist(p[THUMB_TIP], p[PINKY_MCP]) - dist(p[THUMB_MCP], p[PINKY_MCP]);
        if (thumbReach > palmSize * THUMB_MARGIN) count++;

        for (int[] finger : FINGER_TIP_PIP) {
            float reach = dist(p[WRIST], p[finger[0]]) - dist(p[WRIST], p[finger[1]]);
            if (reach > palmSize * FINGER_MARGIN) count++;
        }
        return count;
    }

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public void close() {
        Interpreter p = palm;
        Interpreter l = landmark;
        palm = null;
        landmark = null;
        if (p != null) p.close();
        if (l != null) l.close();
        Bitmap pf = palmFrame;
        Bitmap lf = landmarkFrame;
        palmFrame = null;
        landmarkFrame = null;
        palmCanvas = null;
        landmarkCanvas = null;
        if (pf != null) pf.recycle();
        if (lf != null) lf.recycle();
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

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * MediaPipe's hand graph feeds both the palm detector and the landmark model
     * pixels in [0,1] — unlike BlazeFace, which takes [-1,1]. Feeding [-1,1] here
     * collapses every palm score below the threshold and no hand is ever found.
     */
    private static void fillBuffer(ByteBuffer buffer, Bitmap bitmap, int[] pixels, int size) {
        buffer.clear();
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255f);
            buffer.putFloat((pixel & 0xFF) / 255f);
        }
        buffer.rewind();
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    /** Accepts either a probability or a logit, for robustness across model conversions. */
    private static float probability(float x) {
        return (x >= 0f && x <= 1f) ? x : sigmoid(x);
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
