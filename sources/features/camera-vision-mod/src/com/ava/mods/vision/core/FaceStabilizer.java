package com.ava.mods.vision.core;

/**
 * Temporal smoothing shared by every face backend: majority-voted face count
 * plus asymmetric presence hysteresis. Presence drives the screensaver, so it
 * reacts quickly when somebody walks up but requires a longer run of empty
 * frames before declaring the room empty — a brief head turn must not blank
 * the screen.
 */
public final class FaceStabilizer {

    private static final int VOTE_WINDOW = 5;
    private static final int VOTE_MIN = 3;
    private static final int MAX_VOTED_FACES = 8;
    private static final int FRAMES_TO_APPEAR = 2;
    private static final int FRAMES_TO_VANISH = 5;

    private final MajorityVote votes = new MajorityVote(VOTE_WINDOW, VOTE_MIN, 0, MAX_VOTED_FACES);
    private int appearStreak;
    private int vanishStreak;
    private boolean present;

    public FaceResult offer(int rawCount) {
        if (rawCount > 0) {
            appearStreak++;
            vanishStreak = 0;
            if (appearStreak >= FRAMES_TO_APPEAR) present = true;
        } else {
            vanishStreak++;
            appearStreak = 0;
            if (vanishStreak >= FRAMES_TO_VANISH) present = false;
        }
        return result(votes.offer(Math.min(rawCount, MAX_VOTED_FACES)));
    }

    /** State without consuming a frame, for backends that hit an inference error. */
    public FaceResult current() {
        return result(votes.stable());
    }

    /**
     * Presence needs fewer frames than the count vote, so clamp the two together
     * to avoid briefly reporting a detected person alongside a face count of zero.
     */
    private FaceResult result(int voted) {
        if (!present) return new FaceResult(0, false);
        return new FaceResult(Math.max(voted, 1), true);
    }
}
