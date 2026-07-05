package com.ava.mods.phicomm;

import java.math.BigInteger;

/**
 * EchoService {@code PlayerVisualizer.get_make1()} — maps a logical LED index to a JNI bitmask.
 */
final class PhicommLedBitmask {
    private static final int BIT_COUNT = 39;

    private PhicommLedBitmask() {
    }

    static long getMake1(int index) {
        String[] bits = new String[BIT_COUNT];
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
        for (int i = 0; i < bits.length; i++) {
            bits[map[i]] = i == index ? "1" : "0";
        }
        StringBuilder sb = new StringBuilder(BIT_COUNT);
        for (String bit : bits) {
            sb.append(bit);
        }
        return new BigInteger(sb.toString(), 2).longValue();
    }
}
