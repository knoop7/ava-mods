package com.phicomm.speaker.player.light;

/**
 * JNI binding class for stock {@code libledLight-jni.so}.
 *
 * The library exports exactly one symbol:
 * {@code Java_com_phicomm_speaker_player_light_LedLight_set_1color} — JNI resolves natives by
 * fully-qualified class name, so this class MUST keep the stock package/name. Library loading is
 * handled by {@code PhicommLedLightJni} (no static loader here so a missing .so cannot poison
 * class initialization).
 */
public final class LedLight {
    private static int lastColor = Integer.MIN_VALUE;

    private LedLight() {
    }

    /** Stock EchoService de-dup wrapper: skip hardware write when color is unchanged. */
    public static synchronized void setColor(long mask, int color) {
        if (lastColor != color) {
            set_color(mask, color);
            lastColor = color;
        }
    }

    /** Direct write without de-dup (needed when the same color targets a different mask). */
    public static synchronized void setColorForce(long mask, int color) {
        set_color(mask, color);
        lastColor = color;
    }

    public static native void set_color(long mask, int color);
}
