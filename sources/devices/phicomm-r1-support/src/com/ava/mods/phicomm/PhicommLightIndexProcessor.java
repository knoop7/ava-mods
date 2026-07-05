package com.ava.mods.phicomm;

/**
 * Ported verbatim from com.phicomm.speaker.device.custom.lights.PhicommLightIndexProcessor.
 * Maps 4-mic DOA angle (0-359) to directional white LED index (1-24).
 */
public final class PhicommLightIndexProcessor {
    private static final int WHITE_LIGHT_NUMBER = 24;
    private static final int WHITE_LIGHT_TAG = 15;

    private PhicommLightIndexProcessor() {
    }

    public static int getIndex(int angle) {
        return getIndexFromAngle(perHandleAngle(angle));
    }

    private static int getIndexFromAngle(int angle) {
        int index = angle % WHITE_LIGHT_TAG > 7 ? (angle / WHITE_LIGHT_TAG) + 1 + 1 : (angle / WHITE_LIGHT_TAG) + 1;
        return index > WHITE_LIGHT_NUMBER ? index - WHITE_LIGHT_NUMBER : index;
    }

    private static int perHandleAngle(int angle) {
        if (angle < 0 || angle >= 360) {
            angle = 0;
        }
        int rotated = Math.abs(angle - 360) + 270;
        return rotated >= 360 ? rotated - 360 : rotated;
    }
}
