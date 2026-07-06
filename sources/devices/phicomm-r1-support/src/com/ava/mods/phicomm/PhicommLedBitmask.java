package com.ava.mods.phicomm;

import java.math.BigInteger;

/**
 * LED bitmask helpers for {@code libledLight-jni.so} ({@code set_color(long mask, int rgb)}).
 *
 * Physical layout (39 LEDs, verified against stock {@code lights_effects.conf} and
 * {@code PlayerVisualizer.light_1/light_2}):
 * <ul>
 *   <li>bits 0–14  = RGB ring LEDs (conf numbers 1–15), mask {@code 32767}</li>
 *   <li>bits 15–38 = 24 white directional LEDs (conf numbers 16–39), mask {@code 549755781120}</li>
 * </ul>
 */
final class PhicommLedBitmask {
    private static final int BIT_COUNT = 39;

    /** RGB ring (LEDs 1–15) — stock {@code PlayerVisualizer} mask {@code 32767}. */
    static final long MASK_RGB_RING = 32767L;
    /** 24 white directional LEDs (LEDs 16–39) — stock {@code light_1}. */
    static final long MASK_WHITE_RING = 549755781120L;
    /** All 39 LEDs — stock {@code light_2}. */
    static final long MASK_ALL = 549755813887L;

    private PhicommLedBitmask() {
    }

    /**
     * Mask for one directional white LED addressed by stock msgcenter wakeup id (1–24).
     * Conf id N ends its sweep on LED number {@code 15 + N}, i.e. bit {@code 14 + N}.
     */
    static long wakeSegmentMask(int wakeupId) {
        if (wakeupId < PhicommLightController.WAKEUP_LIGHT_MIN
            || wakeupId > PhicommLightController.WAKEUP_LIGHT_MAX) {
            return 0L;
        }
        return 1L << (14 + wakeupId);
    }

    /**
     * Verbatim port of EchoService {@code PlayerVisualizer.get_make1(index)}.
     * Only meaningful for the stock visualizer index space (24–38 = RGB ring segments).
     */
    static long visualizerMake1(int index) {
        String[] bits = new String[BIT_COUNT];
        int[] map = buildStockMap();
        for (int i = 0; i < bits.length; i++) {
            bits[map[i]] = i == index ? "1" : "0";
        }
        return parseBits(bits);
    }

    /**
     * Verbatim port of EchoService {@code PlayerVisualizer.get_make(start, end)}
     * (inclusive-exclusive quirks preserved: sets positions where {@code i+1 > start && i < end+1}).
     */
    static long visualizerMake(int start, int end) {
        String[] bits = new String[BIT_COUNT];
        int[] map = buildStockMap();
        for (int i = 0; i < bits.length; i++) {
            if (i + 1 > start && i < end + 1) {
                bits[map[i]] = "1";
            } else {
                bits[map[i]] = "0";
            }
        }
        return parseBits(bits);
    }

    private static int[] buildStockMap() {
        int[] map = new int[BIT_COUNT];
        for (int i = 0; i < map.length; i++) {
            if (i < 22) {
                map[i] = i + 2;
            } else if (i == 22) {
                map[i] = 1;
            } else if (i == 23) {
                map[i] = 0;
            } else {
                map[i] = i;
            }
        }
        return map;
    }

    private static long parseBits(String[] bits) {
        StringBuilder sb = new StringBuilder(BIT_COUNT);
        for (String bit : bits) {
            sb.append(bit);
        }
        return new BigInteger(sb.toString(), 2).longValue();
    }
}
