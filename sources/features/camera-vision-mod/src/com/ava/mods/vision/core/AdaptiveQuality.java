package com.ava.mods.vision.core;

/**
 * Steps the capture resolution down the ladder while the measured frame rate
 * stays under half the target, and climbs back once delivery is healthy again.
 * Pure state machine — the caller feeds one sample per FPS window (~2s) and
 * applies the returned action, so the logic is testable without a camera.
 *
 * Flapping is damped two ways: no two steps within MIN_HOLD_MS, and a step
 * down that follows a recent step up doubles the good-sample count required
 * before the next climb, up to a few minutes.
 */
public final class AdaptiveQuality {

    public enum Action { NONE, STEP_DOWN, STEP_UP }

    static final int[] LADDER = {1080, 720, 480, 360, 240};

    private static final int BAD_SAMPLES_TO_DEGRADE = 3;
    private static final int GOOD_SAMPLES_TO_RECOVER_MIN = 15;
    private static final int GOOD_SAMPLES_TO_RECOVER_MAX = 120;
    private static final long MIN_HOLD_MS = 10_000;
    private static final long FLAP_WINDOW_MS = 90_000;

    private boolean enabled = true;
    private int degradeSteps;
    private int badStreak;
    private int goodStreak;
    private int goodNeeded = GOOD_SAMPLES_TO_RECOVER_MIN;
    private long lastChangeAt;
    private long lastStepUpAt;

    public synchronized void setEnabled(boolean on) {
        enabled = on;
        if (!on) reset();
    }

    public synchronized void reset() {
        degradeSteps = 0;
        badStreak = 0;
        goodStreak = 0;
        goodNeeded = GOOD_SAMPLES_TO_RECOVER_MIN;
        lastChangeAt = 0;
        lastStepUpAt = 0;
    }

    public synchronized boolean isDegraded() {
        return degradeSteps > 0;
    }

    /** Short edge the camera should capture at, given the user's configured one. */
    public synchronized int appliedResolution(int userResolution) {
        int idx = Math.min(ladderIndex(userResolution) + degradeSteps, LADDER.length - 1);
        return LADDER[idx];
    }

    public synchronized Action onFpsSample(int measuredFps, int targetFps,
            int userResolution, long nowMs) {
        if (!enabled || targetFps <= 0 || measuredFps <= 0) return Action.NONE;

        boolean bad = measuredFps < Math.max(1, Math.round(targetFps * 0.5f));
        boolean good = measuredFps >= Math.max(1, Math.round(targetFps * 0.8f));
        boolean atFloor = appliedResolution(userResolution) == LADDER[LADDER.length - 1];

        if (bad) {
            goodStreak = 0;
            badStreak++;
            if (badStreak >= BAD_SAMPLES_TO_DEGRADE && !atFloor
                    && nowMs - lastChangeAt >= MIN_HOLD_MS) {
                badStreak = 0;
                degradeSteps++;
                if (lastStepUpAt > 0 && nowMs - lastStepUpAt < FLAP_WINDOW_MS) {
                    goodNeeded = Math.min(goodNeeded * 2, GOOD_SAMPLES_TO_RECOVER_MAX);
                }
                lastChangeAt = nowMs;
                return Action.STEP_DOWN;
            }
        } else if (good) {
            badStreak = 0;
            if (degradeSteps > 0) {
                goodStreak++;
                if (goodStreak >= goodNeeded && nowMs - lastChangeAt >= MIN_HOLD_MS) {
                    goodStreak = 0;
                    degradeSteps--;
                    lastStepUpAt = nowMs;
                    lastChangeAt = nowMs;
                    return Action.STEP_UP;
                }
            }
        } else {
            // Borderline delivery is neither evidence for recovery nor for
            // further degradation; both streaks restart from clean readings.
            badStreak = 0;
            goodStreak = 0;
        }
        return Action.NONE;
    }

    private static int ladderIndex(int resolution) {
        for (int i = 0; i < LADDER.length; i++) {
            if (LADDER[i] <= resolution) return i;
        }
        return LADDER.length - 1;
    }
}
