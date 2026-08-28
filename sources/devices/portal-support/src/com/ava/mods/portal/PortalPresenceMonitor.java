package com.ava.mods.portal;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Face presence via Meta's own detection: tails the PresenceManager /
 * aloha.CameraServiceController logcat heartbeat that Portal emits while a person
 * is in view (same source as portal-ha-bridge).
 *
 * Unlike a pure liveness tail, lines are classified by content (knoop7/Ava#205):
 * the platform also logs presence *engine* chatter under the same tags — camera
 * arbitration ("pausing presence", "resuming presence"), state changes
 * ("presence: false"), and lifecycle noise. Counting those as person-detected
 * heartbeats held the sensor at DETECTED forever. Explicit negatives now clear
 * the state immediately, lifecycle chatter is ignored, and only the remaining
 * presence lines arm the heartbeat window.
 */
class PortalPresenceMonitor {

    private static final String TAG = "PortalSupport";
    /** A beat older than this (by its own log timestamp) is startup backlog. */
    private static final long FRESH_MS = 45_000L;
    /** Reject beats stamped in the future (clock stepped back after boot). */
    private static final long FUTURE_SKEW_MS = 5_000L;
    /** Declare absent once the newest beat is older than this (~one 30s beat + margin). */
    private static final long ABSENT_MS = 50_000L;
    private static final long CHECK_INTERVAL_MS = 10_000L;
    /** Diagnostic beat-line echo at most this often (plus the first beat after absent). */
    private static final long BEAT_LOG_INTERVAL_MS = 300_000L;
    private static final long RETRY_BACKOFF_MIN_MS = 5_000L;
    private static final long RETRY_BACKOFF_MAX_MS = 60_000L;
    /** A logcat attach that survives this long resets the retry backoff. */
    private static final long HEALTHY_ATTACH_MS = 60_000L;

    private static final String[] LOGCAT_CMD = {
            "logcat", "-v", "epoch",
            "-s", "PresenceManager:I", "aloha.CameraServiceController:I"
    };

    /** Explicit "person is there" content — always a beat. Checked first. */
    private static final String[] POSITIVE_MARKERS = {
            "true", "detected", "found"
    };
    /**
     * Explicit "person is NOT there / engine went quiet" content — clears the
     * heartbeat immediately instead of arming it.
     */
    private static final String[] NEGATIVE_MARKERS = {
            "false", "absent", "not present", "no presence", "no face",
            "lost", "stop", "paus", "disabl", "clear", "idle", "away"
    };
    /**
     * Presence *engine* lifecycle / camera-arbitration chatter. Says nothing about
     * whether a person is in view, so it neither beats nor clears.
     */
    private static final String[] LIFECYCLE_MARKERS = {
            "start", "resum", "enable", "register", "unregister", "subscrib",
            "connect", "disconnect", "bind", "init", "creat", "destroy", "request"
    };
    /** "presence: 0" / "present=0.0" style negatives ("0.85" scores don't match). */
    private static final Pattern NUMERIC_FALSE = Pattern.compile("[=:]\\s*0(?:\\.0+)?(?![0-9.])");
    /** "presence: 1" / "present=1" style positives. */
    private static final Pattern NUMERIC_TRUE = Pattern.compile("[=:]\\s*1(?:\\.0+)?(?![0-9.])");

    interface Listener {
        void onPresenceChanged(boolean present);

        /** Periodic re-evaluation hook (e.g. enhanced-sound hold expiry). */
        void onPresenceTick();
    }

    private final Listener listener;
    private final android.content.Context context;
    private volatile boolean running;
    private volatile long lastBeatMs;
    private volatile long lastBeatLogMs;
    private volatile boolean present;
    private volatile Process process;
    private Thread readerThread;
    private final HandlerThread checkThread = new HandlerThread("portal-presence");
    private Handler checkHandler;

    PortalPresenceMonitor(android.content.Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isPresent() {
        return present;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        lastBeatMs = 0L;
        lastBeatLogMs = 0L;
        present = false;
        if (!checkThread.isAlive()) {
            checkThread.start();
        }
        checkHandler = new Handler(checkThread.getLooper());
        readerThread = new Thread(this::readLoop, "portal-presence-log");
        readerThread.setDaemon(true);
        readerThread.start();
        checkHandler.post(checkRunnable);
        Log.i(TAG, "PresenceMonitor started");
    }

    void stop() {
        running = false;
        if (checkHandler != null) {
            checkHandler.removeCallbacks(checkRunnable);
        }
        try {
            if (process != null) {
                process.destroy();
            }
        } catch (Exception ignored) {
        }
        process = null;
        Thread reader = readerThread;
        readerThread = null;
        if (reader != null) {
            reader.interrupt();
        }
        if (present) {
            present = false;
            listener.onPresenceChanged(false);
        }
        Log.i(TAG, "PresenceMonitor stopped");
    }

    void release() {
        stop();
        if (checkThread.isAlive()) {
            checkThread.quitSafely();
        }
    }

    /**
     * Tail logcat while running; if the process dies (Shizuku restart, logd hiccup)
     * re-attach with backoff instead of going silently deaf until the next toggle.
     */
    private void readLoop() {
        long backoff = RETRY_BACKOFF_MIN_MS;
        boolean warnedNoAccess = false;
        while (running) {
            Process proc = startLogcatProcess(LOGCAT_CMD);
            if (proc == null) {
                if (!warnedNoAccess) {
                    warnedNoAccess = true;
                    Log.w(TAG, "PresenceMonitor: no logcat access — grant READ_LOGS "
                            + "(provision.sh / Shizuku / adb) and restart Ava");
                }
                if (!sleepQuietly(backoff)) {
                    return;
                }
                backoff = Math.min(backoff * 2, RETRY_BACKOFF_MAX_MS);
                continue;
            }
            warnedNoAccess = false;
            process = proc;
            long attachedAt = System.currentTimeMillis();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream())
                );
                String line;
                while (running && (line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "PresenceMonitor reader error: " + e.getMessage());
                }
            } finally {
                try {
                    proc.destroy();
                } catch (Exception ignored) {
                }
                if (process == proc) {
                    process = null;
                }
            }
            if (!running) {
                return;
            }
            if (System.currentTimeMillis() - attachedAt >= HEALTHY_ATTACH_MS) {
                backoff = RETRY_BACKOFF_MIN_MS;
            }
            Log.w(TAG, "presence logcat ended — re-attaching in " + (backoff / 1000) + "s");
            if (!sleepQuietly(backoff)) {
                return;
            }
            backoff = Math.min(backoff * 2, RETRY_BACKOFF_MAX_MS);
        }
    }

    private void handleLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.contains("presence")) {
            return;
        }
        String epochText = line.trim().split(" ", 2)[0];
        double epoch;
        try {
            epoch = Double.parseDouble(epochText);
        } catch (NumberFormatException e) {
            return;
        }
        long beatMs = (long) (epoch * 1000L);
        long now = System.currentTimeMillis();
        long age = now - beatMs;
        if (age >= FRESH_MS || age < -FUTURE_SKEW_MS) {
            return; // startup backlog or bogus future timestamp
        }
        if (containsAny(lower, POSITIVE_MARKERS) || NUMERIC_TRUE.matcher(lower).find()) {
            recordBeat(now, line);
            return;
        }
        if (containsAny(lower, NEGATIVE_MARKERS) || NUMERIC_FALSE.matcher(lower).find()) {
            if (lastBeatMs != 0L) {
                Log.i(TAG, "presence clear line: " + line.trim());
            }
            lastBeatMs = 0L;
            return;
        }
        if (containsAny(lower, LIFECYCLE_MARKERS)) {
            return; // engine/arbitration chatter — not evidence either way
        }
        recordBeat(now, line);
    }

    private void recordBeat(long now, String line) {
        if (lastBeatMs == 0L || now - lastBeatLogMs >= BEAT_LOG_INTERVAL_MS) {
            lastBeatLogMs = now;
            Log.i(TAG, "presence beat: " + line.trim());
        }
        lastBeatMs = now;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** @return false when interrupted (monitor stopping). */
    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Same order as portal-ha-bridge: in-app logcat when READ_LOGS is granted to Ava,
     * then Shizuku/root shell as fallback.
     *
     * A pm grant shows as GRANTED immediately, but the running process only gains
     * actual logd read access after an app restart — in that window in-app logcat
     * silently sees just Ava's own lines. Detect that and prefer the privileged
     * shell until the restart.
     */
    private Process startLogcatProcess(String[] cmd) {
        boolean granted = context != null
                && context.checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        boolean grantEffective = granted && inAppLogcatEffective();
        if (grantEffective) {
            Process proc = newInAppLogcat(cmd);
            if (proc != null) {
                return proc;
            }
        }
        Process proc = new PortalPermissionHelper(context).newPrivilegedProcess(cmd);
        if (proc != null) {
            if (granted && !grantEffective) {
                Log.w(TAG, "READ_LOGS granted but not effective in this process yet — "
                        + "using privileged shell; restart Ava for in-app logcat");
            }
            return proc;
        }
        if (granted && !grantEffective) {
            // No shell available; the probe can misread a quiet log buffer, so still
            // try in-app rather than giving up outright.
            Log.w(TAG, "READ_LOGS granted but likely inactive until Ava restarts — "
                    + "trying in-app logcat anyway");
            return newInAppLogcat(cmd);
        }
        return null;
    }

    private Process newInAppLogcat(String[] cmd) {
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            Process proc = builder.start();
            Log.i(TAG, "presence logcat in-app (READ_LOGS)");
            return proc;
        } catch (Exception e) {
            Log.w(TAG, "in-app logcat failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * True when in-app logcat can see lines from other processes — i.e. the
     * READ_LOGS grant is live for this process, not just recorded in the PM.
     */
    private boolean inAppLogcatEffective() {
        Process proc = null;
        try {
            proc = new ProcessBuilder("logcat", "-d", "-t", "300", "-v", "epoch")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream())
            );
            int myPid = android.os.Process.myPid();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 3) {
                    continue;
                }
                try {
                    if (Integer.parseInt(tokens[1]) != myPid) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // "--------- beginning of main" separators etc.
                }
            }
            return false;
        } catch (Exception e) {
            // Probe failure says nothing about the grant — assume effective.
            return true;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private final Runnable checkRunnable = new PresenceCheckRunnable();

    private class PresenceCheckRunnable implements Runnable {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            boolean live = lastBeatMs != 0L && System.currentTimeMillis() - lastBeatMs < ABSENT_MS;
            if (live != present) {
                present = live;
                Log.i(TAG, "presence -> " + (live ? "DETECTED" : "CLEAR"));
                listener.onPresenceChanged(live);
            } else {
                listener.onPresenceTick();
            }
            if (checkHandler != null) {
                checkHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    }
}
