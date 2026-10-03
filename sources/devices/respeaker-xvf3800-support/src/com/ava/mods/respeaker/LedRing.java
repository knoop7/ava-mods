package com.ava.mods.respeaker;

import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shows Ava's voice session on the reSpeaker's LED ring.
 *
 * The factory effect is direction of arrival: every sound lights a green LED
 * in its direction. In a kitchen with music playing that blinks all the
 * time. So the ring stays dark while idle and only shows what Ava is doing.
 *
 * The firmware's own effects (breathe, single colour) looked too plain. The
 * animations are computed here instead and written as ring colours at 20
 * frames per second - the same approach FormatBCE uses on the ESP32.
 *
 * All calls run on the BeamTracker's worker thread; the class itself is not
 * thread-safe.
 */
final class LedRing {

    private static final String TAG = "ReSpeaker";

    static final String[] MODES = {"Voice status", "Voice status and direction", "Do not control"};
    static final int MODE_STATUS = 0;
    static final int MODE_STATUS_AND_DIRECTION = 1;
    static final int MODE_HANDS_OFF = 2;

    static final String[] BRIGHTNESS_OPTIONS = {"10 %", "25 %", "50 %", "100 %"};
    private static final int[] BRIGHTNESS_PERCENT = {10, 25, 50, 100};

    static final String[] SPEED_OPTIONS = {"Slow", "Medium", "Fast"};
    private static final float[] SPEED_FACTOR = {0.6f, 1.0f, 1.6f};

    private static final int EFFECT_OFF = 0;
    private static final int EFFECT_DOA = 4;
    private static final int EFFECT_RING = 5;

    private static final int LEDS = 12;
    /** Same as FormatBCE on this board: LED index = angle / 30 + 5. */
    private static final int LED_OFFSET = 5;
    private static final long FRAME_MS = 50L;
    /** Without a pipeline event for this long, the ring fades out. */
    private static final long IDLE_TIMEOUT_MS = 45000L;
    private static final float FADE_S = 0.5f;

    private static final int CYAN = 0x00E5FF;
    private static final int BLUE = 0x1040FF;
    private static final int VIOLET = 0x8A2BE2;
    private static final int MAGENTA = 0xFF00C8;
    private static final int TURQUOISE = 0x00FFC0;
    private static final int SKY = 0x0090FF;
    private static final int RED = 0xFF1010;

    private enum Anim { NONE, WAKE, LISTEN, THINK, SPEAK, ERROR, FADE }

    private final XvfLink link;
    private final ScheduledExecutorService worker;

    private int mode = MODE_STATUS;
    private int brightness = 1;
    private int speed = 0;

    private Anim anim = Anim.NONE;
    private long animStart;
    private long lastEvent;
    private ScheduledFuture<?> ticker;
    private boolean ringEffectActive;
    private int[] lastFrame;
    private int[] fadeFrame;

    /** Speaker direction as LED position, followed smoothly. -1 = unknown. */
    private float targetLed = -1f;
    private float centerLed = -1f;

    LedRing(XvfLink link, ScheduledExecutorService worker) {
        this.link = link;
        this.worker = worker;
    }

    // ---- Settings -------------------------------------------------------------

    int getMode() {
        return mode;
    }

    int getBrightness() {
        return brightness;
    }

    int getSpeed() {
        return speed;
    }

    void load(int mode, int brightness, int speed) {
        if (mode >= 0 && mode < MODES.length) {
            this.mode = mode;
        }
        if (brightness >= 0 && brightness < BRIGHTNESS_PERCENT.length) {
            this.brightness = brightness;
        }
        if (speed >= 0 && speed < SPEED_FACTOR.length) {
            this.speed = speed;
        }
    }

    void setMode(int next, boolean inSession) {
        if (next < 0 || next >= MODES.length || next == mode) {
            return;
        }
        int previous = mode;
        mode = next;
        if (next == MODE_HANDS_OFF) {
            stopTicker();
            anim = Anim.NONE;
            if (previous != MODE_HANDS_OFF) {
                writeEffect(EFFECT_DOA);
            }
            return;
        }
        if (!inSession) {
            anim = Anim.NONE;
            idleNow();
        }
    }

    void setBrightness(int index) {
        if (index >= 0 && index < BRIGHTNESS_PERCENT.length) {
            brightness = index;
        }
    }

    void setSpeed(int index) {
        if (index >= 0 && index < SPEED_FACTOR.length) {
            speed = index;
        }
    }

    // ---- Connection -----------------------------------------------------------

    /** Once per connection into the log: what was set in the chip before? */
    void logFirmwareState() {
        try {
            int effect = link.readInts(XvfParam.LED_EFFECT)[0];
            int ledSpeed = link.readInts(XvfParam.LED_SPEED)[0];
            Log.i(TAG, "LED found: effect " + effect + ", speed " + ledSpeed);
        } catch (XvfLink.XvfException e) {
            Log.w(TAG, "LED state not readable: " + e.getMessage());
        }
    }

    /** After every new connection nothing about the ring is known anymore. */
    void forget() {
        stopTicker();
        anim = Anim.NONE;
        ringEffectActive = false;
        lastFrame = null;
    }

    /** When the mod shuts down: hand the firmware's direction effect back. */
    void restoreFactory() {
        stopTicker();
        anim = Anim.NONE;
        if (mode != MODE_HANDS_OFF) {
            writeEffect(EFFECT_DOA);
        }
    }

    // ---- States -----------------------------------------------------------------

    void wake() {
        if (anim == Anim.WAKE || anim == Anim.LISTEN) {
            touch();
            return;
        }
        start(Anim.WAKE);
    }

    /** The speaker was found or has moved. */
    void direction(float deg) {
        float led = ((BeamTracker.wrapDeg(deg) / 30f) + LED_OFFSET) % LEDS;
        targetLed = led;
        if (centerLed < 0f) {
            centerLed = led;
        }
        if (anim != Anim.LISTEN) {
            start(Anim.LISTEN);
        } else {
            touch();
        }
    }

    void think() {
        if (anim != Anim.THINK) {
            start(Anim.THINK);
        } else {
            touch();
        }
    }

    void speak() {
        if (anim != Anim.SPEAK) {
            start(Anim.SPEAK);
        } else {
            touch();
        }
    }

    void error() {
        start(Anim.ERROR);
    }

    /** Fade out gently; afterwards off or the direction effect, by mode. */
    void idle() {
        if (!controlling()) {
            return;
        }
        if (anim == Anim.NONE || anim == Anim.FADE) {
            if (anim == Anim.NONE) {
                idleNow();
            }
            return;
        }
        fadeFrame = lastFrame;
        start(Anim.FADE);
    }

    private void touch() {
        lastEvent = System.currentTimeMillis();
    }

    private void start(Anim next) {
        if (!controlling()) {
            return;
        }
        // The direction only applies to the current session.
        if (next == Anim.WAKE) {
            targetLed = -1f;
            centerLed = -1f;
        }
        anim = next;
        animStart = System.currentTimeMillis();
        touch();
        if (!ringEffectActive) {
            if (!writeEffect(EFFECT_RING)) {
                return;
            }
            ringEffectActive = true;
        }
        if (ticker == null) {
            ticker = worker.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    frame();
                }
            }, 0, FRAME_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void idleNow() {
        stopTicker();
        anim = Anim.NONE;
        lastFrame = null;
        ringEffectActive = false;
        writeEffect(mode == MODE_STATUS_AND_DIRECTION ? EFFECT_DOA : EFFECT_OFF);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    // ---- Animation --------------------------------------------------------------

    private void frame() {
        if (!controlling()) {
            stopTicker();
            return;
        }
        long now = System.currentTimeMillis();
        if (anim != Anim.FADE && anim != Anim.ERROR
                && now - lastEvent > IDLE_TIMEOUT_MS) {
            fadeFrame = lastFrame;
            anim = Anim.FADE;
            animStart = now;
        }
        float t = (now - animStart) / 1000f;
        float tt = t * SPEED_FACTOR[speed];
        float[][] rgb = new float[LEDS][3];

        switch (anim) {
            case WAKE:
                comet(rgb, tt * 1.6f, cometStart(), CYAN, 6f, Math.min(1f, t / 0.15f));
                break;
            case LISTEN:
                followCenter();
                cone(rgb, tt);
                break;
            case THINK:
                swirl(rgb, tt);
                break;
            case SPEAK:
                followCenter();
                waves(rgb, tt);
                break;
            case ERROR:
                if (errorFrame(rgb, t)) {
                    idleNow();
                    return;
                }
                break;
            case FADE:
                if (t >= FADE_S || fadeFrame == null) {
                    idleNow();
                    return;
                }
                writeFrame(dim(fadeFrame, 1f - t / FADE_S));
                return;
            default:
                stopTicker();
                return;
        }
        writeFrame(toColors(rgb));
    }

    /** Where the comet starts: where someone last spoke. */
    private float cometStart() {
        return centerLed >= 0f ? centerLed : 0f;
    }

    /** The centre glides towards the target by at most one LED per 100 ms. */
    private void followCenter() {
        if (targetLed < 0f) {
            return;
        }
        if (centerLed < 0f) {
            centerLed = targetLed;
            return;
        }
        float d = ringDelta(centerLed, targetLed);
        float step = 0.5f;
        centerLed = Math.abs(d) <= step ? targetLed : wrap(centerLed + Math.signum(d) * step);
    }

    /** A soft, breathing cone of light pointing at the speaker. */
    private void cone(float[][] rgb, float tt) {
        float pulse = 0.78f + 0.22f * (float) Math.sin(2 * Math.PI * tt / 1.8);
        fill(rgb, BLUE, 0.035f);
        if (centerLed < 0f) {
            comet(rgb, tt * 1.6f, 0f, CYAN, 4.5f, 1f);
            return;
        }
        for (int i = 0; i < LEDS; i++) {
            float d = Math.abs(ringDelta(centerLed, i));
            float w = (float) Math.exp(-(d * d) / (2 * 0.9 * 0.9));
            if (w < 0.02f) {
                continue;
            }
            int colour = mix(BLUE, CYAN, w);
            add(rgb, i, colour, w * pulse);
        }
    }

    /** Waves running across the ring from the speaker's direction. */
    private void waves(float[][] rgb, float tt) {
        float center = centerLed >= 0f ? centerLed : 0f;
        for (int i = 0; i < LEDS; i++) {
            float d = Math.abs(ringDelta(center, i));
            float phase = (float) (2 * Math.PI * (tt / 1.1f) - d * 0.9f);
            float w = 0.18f + 0.82f * (0.5f + 0.5f * (float) Math.sin(phase));
            float share = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * (tt * 0.15f + i / (float) LEDS));
            add(rgb, i, mix(TURQUOISE, SKY, share), w);
        }
    }

    /**
     * A colour swirl over the whole ring: a gradient of violet, magenta and
     * blue turns slowly, with a bright wave and a long tail running over it.
     * Two comets looked like two single LEDs.
     */
    private void swirl(float[][] rgb, float tt) {
        for (int i = 0; i < LEDS; i++) {
            float pos = i / (float) LEDS;
            int colour = gradient(wrap01(pos + tt * 0.25f), VIOLET, MAGENTA, BLUE);
            float wave = 0.5f + 0.5f * (float) Math.cos(2 * Math.PI * (pos - tt * 0.8f));
            float w = 0.22f + 0.78f * (float) Math.pow(wave, 1.6);
            add(rgb, i, colour, w);
        }
    }

    /** Cyclic gradient through three colours, pos in [0, 1). */
    private static int gradient(float pos, int a, int b, int c) {
        float x = pos * 3f;
        if (x < 1f) {
            return mix(a, b, x);
        }
        if (x < 2f) {
            return mix(b, c, x - 1f);
        }
        return mix(c, a, x - 2f);
    }

    private static float wrap01(float x) {
        float v = x % 1f;
        return v < 0f ? v + 1f : v;
    }

    /** A comet with a tail; head position = start + revolutions * 12. */
    private void comet(float[][] rgb, float revolutions, float start, int colour,
                       float tail, float strength) {
        float head = wrap(start + revolutions * LEDS);
        for (int i = 0; i < LEDS; i++) {
            float behind = wrap(head - i);
            float w;
            if (behind <= tail) {
                // Softer than quadratic: on a dimmed ring only the head of
                // the tail stayed visible otherwise.
                float x = 1f - behind / tail;
                w = (float) Math.pow(x, 1.3);
            } else if (behind >= LEDS - 0.6f) {
                // Soft leading edge so the head does not jump.
                w = (behind - (LEDS - 0.6f)) / 0.6f * 0.5f;
            } else {
                continue;
            }
            add(rgb, i, colour, w * strength);
        }
    }

    /** Flash red twice, then fade out. true = done. */
    private boolean errorFrame(float[][] rgb, float t) {
        float w;
        if (t < 0.25f || (t >= 0.45f && t < 0.7f)) {
            w = 1f;
        } else if (t < 0.45f) {
            w = 0.05f;
        } else if (t < 1.2f) {
            w = 1f - (t - 0.7f) / 0.5f;
        } else {
            return true;
        }
        fill(rgb, RED, w);
        return false;
    }

    private static void fill(float[][] rgb, int colour, float w) {
        for (int i = 0; i < LEDS; i++) {
            add(rgb, i, colour, w);
        }
    }

    private static void add(float[][] rgb, int i, int colour, float w) {
        rgb[i][0] = Math.min(255f, rgb[i][0] + ((colour >> 16) & 0xFF) * w);
        rgb[i][1] = Math.min(255f, rgb[i][1] + ((colour >> 8) & 0xFF) * w);
        rgb[i][2] = Math.min(255f, rgb[i][2] + (colour & 0xFF) * w);
    }

    private static int mix(int a, int b, float shareB) {
        float x = Math.max(0f, Math.min(1f, shareB));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - x) + ((b >> 16) & 0xFF) * x);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - x) + ((b >> 8) & 0xFF) * x);
        int bl = Math.round((a & 0xFF) * (1 - x) + (b & 0xFF) * x);
        return (r << 16) | (g << 8) | bl;
    }

    private int[] toColors(float[][] rgb) {
        float h = BRIGHTNESS_PERCENT[brightness] / 100f;
        int[] colours = new int[LEDS];
        for (int i = 0; i < LEDS; i++) {
            int r = Math.round(rgb[i][0] * h);
            int g = Math.round(rgb[i][1] * h);
            int b = Math.round(rgb[i][2] * h);
            colours[i] = (r << 16) | (g << 8) | b;
        }
        return colours;
    }

    private static int[] dim(int[] frame, float factor) {
        int[] next = new int[LEDS];
        for (int i = 0; i < LEDS; i++) {
            int c = frame[i];
            int r = Math.round(((c >> 16) & 0xFF) * factor);
            int g = Math.round(((c >> 8) & 0xFF) * factor);
            int b = Math.round((c & 0xFF) * factor);
            next[i] = (r << 16) | (g << 8) | b;
        }
        return next;
    }

    private static float wrap(float x) {
        float v = x % LEDS;
        return v < 0f ? v + LEDS : v;
    }

    /** Shortest way around the ring, -6 to 6. */
    private static float ringDelta(float from, float to) {
        return wrap(to - from + LEDS / 2f) - LEDS / 2f;
    }

    // ---- Writing --------------------------------------------------------------

    private boolean controlling() {
        return mode != MODE_HANDS_OFF
            && link.getState() == XvfLink.STATE_CONNECTED;
    }

    private void writeFrame(int[] colours) {
        if (lastFrame != null && Arrays.equals(lastFrame, colours)) {
            return;
        }
        try {
            link.writeInts(XvfParam.LED_RING_COLOR, colours);
            lastFrame = colours;
        } catch (XvfLink.XvfException e) {
            Log.w(TAG, "LED ring not writable: " + e.getMessage());
            stopTicker();
        }
    }

    private boolean writeEffect(int effect) {
        if (link.getState() != XvfLink.STATE_CONNECTED) {
            return false;
        }
        try {
            link.writeInt(XvfParam.LED_EFFECT, effect);
            return true;
        } catch (XvfLink.XvfException e) {
            Log.w(TAG, "LED ring not writable: " + e.getMessage());
            return false;
        }
    }
}
