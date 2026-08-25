package com.ava.mods.vision;

import java.util.ArrayList;
import java.util.List;

/**
 * SSD anchor centers for the MediaPipe detection models.
 *
 * Every variant declares its own grid, so the layout is chosen by matching the
 * anchor count the loaded model reports rather than being hardcoded:
 * BlazeFace short is 128px/896, BlazeFace full-range sparse is 192px/2304, and
 * BlazePalm lite is 192px/2016.
 */
final class SsdAnchors {

    private static final int[][][] LAYOUTS = {
            { { 8, 16 }, { 2, 6 } },
            { { 4 }, { 1 } },
            { { 8, 16, 32 }, { 2, 6, 6 } },
            { { 16 }, { 1 } },
    };

    private SsdAnchors() {
    }

    /**
     * @return anchor centers in input pixels, or null when no known grid matches
     */
    static List<float[]> forModel(int inputSize, int anchorCount) {
        for (int[][] layout : LAYOUTS) {
            List<float[]> candidate = grid(inputSize, layout[0], layout[1]);
            if (candidate.size() == anchorCount) return candidate;
        }
        return null;
    }

    private static List<float[]> grid(int inputSize, int[] strides, int[] counts) {
        List<float[]> out = new ArrayList<>();
        for (int s = 0; s < strides.length; s++) {
            int stride = strides[s];
            int cells = inputSize / stride;
            for (int y = 0; y < cells; y++) {
                for (int x = 0; x < cells; x++) {
                    float cx = (x + 0.5f) * stride;
                    float cy = (y + 0.5f) * stride;
                    for (int n = 0; n < counts[s]; n++) {
                        out.add(new float[] { cx, cy });
                    }
                }
            }
        }
        return out;
    }
}
