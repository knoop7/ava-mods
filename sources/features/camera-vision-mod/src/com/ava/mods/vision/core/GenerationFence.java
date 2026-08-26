package com.ava.mods.vision.core;

/**
 * Process-wide generation claim shared across classloaders.
 *
 * The host hot-reloads this mod in place (store updates, satellite restarts):
 * it builds a fresh classloader and a fresh manager, but the old instance is
 * only torn down when the host's onDestroy call actually lands. Any missed
 * teardown leaves a zombie generation whose camera thread and watchdog fight
 * the new one for the device — the "toggle the camera until it works" symptom.
 *
 * Statics cannot bridge generations (each classloader gets its own), so the
 * claim lives in the System properties, which are process-global. Every core
 * writes a unique token on construction; an instance whose token no longer
 * matches has been superseded and must shut itself down.
 */
public final class GenerationFence {

    private final String key;
    private final String token;

    public GenerationFence(String key) {
        this.key = key;
        this.token = Long.toHexString(System.nanoTime())
                + "." + Integer.toHexString(System.identityHashCode(this));
        System.setProperty(key, token);
    }

    public boolean isCurrent() {
        return token.equals(System.getProperty(key));
    }

    /** Releases the claim, but never a newer generation's. */
    public void releaseIfCurrent() {
        if (isCurrent()) {
            System.clearProperty(key);
        }
    }
}
