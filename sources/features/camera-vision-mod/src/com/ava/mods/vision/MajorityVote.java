package com.ava.mods.vision;

import java.util.Arrays;

/**
 * Sliding-window vote over per-frame detection results.
 *
 * Single frames are noisy enough that publishing them straight to Home Assistant
 * makes a sensor flicker between neighbouring values, so a new result has to win
 * a majority of the window before it replaces the last stable one.
 */
final class MajorityVote {

    private final int[] window;
    private final int minAgreeing;
    private final int lowest;
    private final int highest;
    private final int initial;

    private int pos;
    private int stable;

    /**
     * @param size        frames kept in the window
     * @param minAgreeing how many of them must agree to publish a new value
     * @param lowest      smallest value that can be voted on, also the initial state
     * @param highest     largest value that can be voted on
     */
    MajorityVote(int size, int minAgreeing, int lowest, int highest) {
        this.window = new int[size];
        this.minAgreeing = minAgreeing;
        this.lowest = lowest;
        this.highest = highest;
        this.initial = lowest;
        reset();
    }

    int offer(int value) {
        window[pos] = value;
        pos = (pos + 1) % window.length;

        int best = initial;
        int bestCount = 0;
        for (int candidate = lowest; candidate <= highest; candidate++) {
            int seen = 0;
            for (int recorded : window) {
                if (recorded == candidate) seen++;
            }
            if (seen > bestCount) {
                bestCount = seen;
                best = candidate;
            }
        }
        if (bestCount >= minAgreeing) {
            stable = best;
        }
        return stable;
    }

    int stable() {
        return stable;
    }

    void reset() {
        Arrays.fill(window, initial);
        pos = 0;
        stable = initial;
    }
}
