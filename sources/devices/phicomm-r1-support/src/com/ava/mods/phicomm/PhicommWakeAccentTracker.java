package com.ava.mods.phicomm;

import android.os.Bundle;

/**
 * Tracks wake-word slot (1 = green, 2 = blue) from pipeline extras.
 * Uses Ava {@code accent_color} when available, with {@code wake_word_id} learning as fallback.
 */
final class PhicommWakeAccentTracker {
    /** Matches {@code VoiceAccentColors.WAKE_WORD_1}. */
    static final int DEFAULT_WAKE_WORD_1_RGB = 0x00FF88;
    /** Matches {@code VoiceAccentColors.WAKE_WORD_2}. */
    static final int DEFAULT_WAKE_WORD_2_RGB = 0x00D4FF;

    private String primaryWakeWordId;
    private String secondaryWakeWordId;
    private String pendingWakeWordId = "";
    private int pendingDoa;
    private int sessionAccentRgb;

    void reset() {
        pendingWakeWordId = "";
        pendingDoa = 0;
        sessionAccentRgb = 0;
    }

    void onWakeDetected(Bundle extras, int doa) {
        pendingDoa = doa;
        pendingWakeWordId = extras != null ? extras.getString("wake_word_id", "") : "";
        if (pendingWakeWordId == null) {
            pendingWakeWordId = "";
        }
        sessionAccentRgb = guessAccentFromWakeWordId();
    }

    int onListeningStarted(Bundle extras) {
        int accent = extras != null ? extras.getInt("accent_color", 0) : 0;
        if (accent != 0) {
            sessionAccentRgb = accent & 0xFFFFFF;
            learnWakeWordSlot(pendingWakeWordId, sessionAccentRgb);
        } else if (sessionAccentRgb == 0) {
            sessionAccentRgb = guessAccentFromWakeWordId();
        }
        return sessionAccentRgb;
    }

    int getPendingDoa() {
        return pendingDoa;
    }

    int getSessionAccentRgb() {
        return sessionAccentRgb;
    }

    private int guessAccentFromWakeWordId() {
        return resolveWakeWordSlot(pendingWakeWordId) == 0
            ? DEFAULT_WAKE_WORD_1_RGB
            : DEFAULT_WAKE_WORD_2_RGB;
    }

    private int resolveWakeWordSlot(String wakeWordId) {
        if (wakeWordId == null || wakeWordId.isEmpty()) {
            return 0;
        }
        if (wakeWordId.equals(secondaryWakeWordId)) {
            return 1;
        }
        if (wakeWordId.equals(primaryWakeWordId)) {
            return 0;
        }
        return 0;
    }

    private void learnWakeWordSlot(String wakeWordId, int accentRgb) {
        if (wakeWordId == null || wakeWordId.isEmpty()) {
            return;
        }
        if (isWakeWord2Accent(accentRgb)) {
            secondaryWakeWordId = wakeWordId;
        } else {
            primaryWakeWordId = wakeWordId;
        }
    }

    static boolean isWakeWord2Accent(int rgb) {
        int color = rgb & 0xFFFFFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int dist1 = colorDistance(color, DEFAULT_WAKE_WORD_1_RGB);
        int dist2 = colorDistance(color, DEFAULT_WAKE_WORD_2_RGB);
        if (dist2 + 48 < dist1) {
            return true;
        }
        return b > r && b >= g;
    }

    private static int colorDistance(int a, int b) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int dr = ar - br;
        int dg = ag - bg;
        int db = ab - bb;
        return dr * dr + dg * dg + db * db;
    }
}
