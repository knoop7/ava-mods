package com.ava.mods.respeaker;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Holds the beamformer on the speaker and switches what Ava hears.
 *
 * Lessons from measuring on a real setup, in the order they were learned:
 *
 * 1. INTERFERENCE ZONES. At the wake word the interfering source (TV, PC
 *    speakers) is often the strongest direction. Without exclusion zones the
 *    lock went to the speakers.
 * 2. BOTH CHANNELS. Ava records mono - Android folds left and right into one.
 *    Both outputs must always be switched.
 * 3. PEAK HOLD. Speech energy comes in bursts; a single reading hits the gap
 *    between two syllables.
 * 4. GATING OFF by default. In the near field it switches beams, not
 *    directions.
 * 5. LOOKBACK. By the time wake_detected arrives the speech energy has
 *    already decayed (measured E=[0,0,0,0]) - it was there WHILE the wake word
 *    was spoken. So a background poll keeps measuring, and the lock takes the
 *    direction from the window before the event. Same idea as FormatBCE's
 *    lock_beam from its 10 Hz poll.
 * 6. SPEAKER MAP instead of a single seat. People in a kitchen move. Every
 *    lock adds weight to its direction, which decays over days; interference
 *    zones learn more slowly there instead of not at all.
 * 7. PERSISTENCE CRITERION. An interfering source is stationary and lasting.
 *    A direction only learns when its surroundings were active in most of the
 *    last 30 ticks - a person who moves or pauses in conversation does not
 *    reach that.
 */
final class BeamTracker {

    private static final String TAG = "ReSpeaker";

    /** Back channel to the manager: event log and entity updates. */
    interface Reporter {
        void event(String text);
        void stateChanged();
    }

    private static final float RAD = (float) (Math.PI / 180.0);

    private static final int SAMPLES_LOCK = 12;
    private static final int SAMPLES_TRACK = 3;
    private static final int SAMPLES_SPEECH_START = 6;
    private static final long TRACK_INTERVAL_MS = 120L;
    private static final long SEARCH_INTERVAL_MS = 150L;
    private static final float MIN_CORRECTION_DEG = 1.0f;
    /**
     * Only these beams estimate the direction themselves. Index 0 and 1 report
     * the set points of the fixed beams - even after release - and because of
     * the weak directivity they hear every voice almost fully. The lookback
     * once locked twice onto the opposite beam (speaker + 180 degrees) of the
     * previous lock that way.
     */
    private static final int[] FREE_BEAMS = {2, 3};

    /** Channel routing during a command: ASR output of fixed beam 0. */
    private static final int[] COMMAND_ROUTE = {7, 0};
    /** Factory routing, used when the real idle routing is no longer known. */
    private static final int[] FACTORY_R = {7, 3};
    private static final int[] FACTORY_L = {8, 0};

    /**
     * What Ava hears while idle, i.e. while the wake word has to be detected.
     * The factory routing mixes the ASR auto-select beam (right) with the
     * conference output including noise suppression (left). ESPHome lets
     * microWakeWord listen on the ASR channel only. With a TV running at a
     * distance the wake word barely got through in the factory routing.
     */
    static final String[] IDLE_MODES = {"Factory default", "Both ASR", "Both conference"};
    private static final int[] ASR_AUTO = {7, 3};
    private static final int[] CONF_AUTO = {8, 0};

    // Background poll and lookback.
    private static final long POLL_MS = 150L;
    private static final int BUFFER_SIZE = 48;
    /** Window in which the wake word was spoken. */
    private static final long LOOKBACK_MS = 1500L;
    /** Baseline before it: whatever was already playing is not the wake word. */
    private static final long BASELINE_MS = 4500L;
    /** How much stronger the best onset must be to count as unambiguous. */
    private static final double ONSET_MARGIN = 2.0;
    private static final long RECONNECT_PAUSE_MS = 2000L;

    // Interference map: 24 sectors of 15 degrees each.
    private static final int SECTORS = 24;
    private static final float SECTOR_DEG = 360f / SECTORS;
    private static final float ZONE_LIMIT = 20f;
    private static final float DECAY = 0.995f;
    private static final long LEARN_INTERVAL_MS = 2000L;
    private static final int HISTORY_TICKS = 30;
    private static final int HISTORY_MASK = (1 << HISTORY_TICKS) - 1;
    // 21 was sized for the near field. A TV at a distance was active in only
    // 13 to 19 ticks and never became a zone.
    private static final int PERSIST_MIN_TICKS = 18;
    private static final int TICK_MIN_HITS = 2;
    private static final float TICK_MIN_SHARE = 0.15f;

    // Speaker map.
    private static final float SPEAKER_GAIN = 1f;
    private static final float SPEAKER_MAX = 10f;
    private static final float SPEAKER_BRAKE = 2f;
    private static final long SPEAKER_HALF_LIFE_MS = 3L * 24L * 3600L * 1000L;
    private static final float SPEAKER_MIN_TOTAL = 0.5f;

    /** For this long after a lock its direction applies to follow-up questions. */
    private static final long FOLLOW_UP_MS = 20000L;
    /** Emergency brake in case a pipeline end is never reported. */
    private static final long PIPELINE_MAX_MS = 120000L;

    private final XvfLink link;
    private final Reporter reporter;
    private final ScheduledExecutorService worker =
        Executors.newSingleThreadScheduledExecutor();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private ScheduledFuture<?> trackTask;
    private ScheduledFuture<?> watchdogTask;
    private ScheduledFuture<?> searchTask;
    private ScheduledFuture<?> pollTask;
    private ScheduledFuture<?> learnTask;

    // Configuration.
    private volatile boolean beamLockActive = false;
    private volatile boolean trackingEnabled = true;
    private volatile boolean gatingEnabled = false;
    private volatile boolean channelSwitchEnabled = true;
    private volatile boolean fastLockEnabled = true;
    private volatile boolean lookbackEnabled = true;
    private volatile boolean releaseAfterStt = true;
    private volatile boolean correctOnSpeech = false;
    private int learnTicks;
    private volatile boolean zoneLearningEnabled = true;
    private volatile float energyThreshold = 1000f;
    private volatile float trackWindowDeg = 35f;
    private volatile float maxStepDeg = 6f;
    private volatile long maxLockMs = 30000L;
    private volatile long searchMs = 8000L;

    // Manual interferer directions in degrees; NaN = slot unused.
    private final float[] interferers = {Float.NaN, Float.NaN, Float.NaN};
    private volatile float excludeDeg = 40f;

    // Interference map.
    private final float[] zoneWeight = new float[SECTORS];
    private final int[] zoneHistory = new int[SECTORS];

    // Speaker map, persistent.
    private final float[] speakerWeight = new float[SECTORS];
    private long speakerMapTime;

    // Ring buffer of the background poll.
    private final long[] bufTime = new long[BUFFER_SIZE];
    private final float[][] bufEnergy = new float[BUFFER_SIZE][];
    private final float[][] bufDir = new float[BUFFER_SIZE][];
    private int bufHead;
    private int bufCount;
    private long lastConnectAttempt;

    // Run state.
    private volatile boolean sessionActive;
    private volatile boolean pipelineActive;
    private long pipelineSince;
    private volatile boolean locked;
    private volatile float lockedAzimuthDeg = Float.NaN;
    private long lockSince;
    private float lastDirection = Float.NaN;
    private long lastRelease;
    private volatile float[][] lastProbe;

    // Idle routing of the channels, and the last one saved.
    private int[] idleRouteR;
    private int[] idleRouteL;
    private int[] savedIdleR;
    private int[] savedIdleL;
    private int checkedConnection = -1;
    private volatile int idleMode;

    private final File stateFile;
    /** State file of the mod's predecessor (XVF3800 Beam-Lock 0.5 to 0.7). */
    private final File legacyStateFile;

    private final LedRing led;
    /** Until then the red error display stays on. */
    private long errorUntil;

    BeamTracker(XvfLink link, Reporter reporter, File directory) {
        this.link = link;
        this.reporter = reporter;
        this.stateFile = directory == null ? null : new File(directory, "respeaker_state.properties");
        this.legacyStateFile = directory == null ? null : new File(directory, "xvfbeam_zustand.properties");
        this.led = new LedRing(link, worker);
        loadState();
        pollTask = worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        learnTask = worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                learnTick();
            }
        }, LEARN_INTERVAL_MS, LEARN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ---- Persistence ------------------------------------------------------------

    private void loadState() {
        long now = System.currentTimeMillis();
        speakerMapTime = now;
        Properties p = new Properties();
        String source = "new";
        if (readProperties(stateFile, p)) {
            source = "loaded";
        } else if (readProperties(legacyStateFile, p)) {
            // The predecessor wrote German keys; translate them once.
            translateLegacyKeys(p);
            source = "taken over from XVF3800 Beam-Lock";
        }
        String map = p.getProperty("speaker_map");
        if (map != null) {
            String[] parts = map.split(",");
            for (int i = 0; i < SECTORS && i < parts.length; i++) {
                try {
                    speakerWeight[i] = Float.parseFloat(parts[i].trim());
                } catch (NumberFormatException ignored) {
                    speakerWeight[i] = 0f;
                }
            }
            try {
                speakerMapTime = Long.parseLong(p.getProperty("speaker_map_time", String.valueOf(now)));
            } catch (NumberFormatException ignored) {
                speakerMapTime = now;
            }
        }
        beamLockActive = "true".equals(p.getProperty("beam_lock", "false").trim());
        savedIdleR = parseRoute(p.getProperty("idle_route_r"));
        savedIdleL = parseRoute(p.getProperty("idle_route_l"));
        try {
            int m = Integer.parseInt(p.getProperty("idle_mode", "0").trim());
            idleMode = m >= 0 && m < IDLE_MODES.length ? m : 0;
        } catch (NumberFormatException ignored) {
            idleMode = 0;
        }
        try {
            led.load(Integer.parseInt(p.getProperty("led_mode", "0").trim()),
                     Integer.parseInt(p.getProperty("led_brightness", "1").trim()),
                     Integer.parseInt(p.getProperty("led_speed", "0").trim()));
        } catch (NumberFormatException ignored) {
            // Defaults stay.
        }
        ageSpeakerMap(now);
        Log.i(TAG, "State " + source + ", speaker map: "
              + speakerMapText() + ", idle R=" + fmt(savedIdleR)
              + " L=" + fmt(savedIdleL));
        if (source.startsWith("taken over")) {
            saveState();
        }
    }

    private static boolean readProperties(File file, Properties p) {
        if (file == null || !file.exists()) {
            return false;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            p.load(in);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "State not readable: " + file.getName(), e);
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private static void translateLegacyKeys(Properties p) {
        String[][] keys = {
            {"sprecher", "speaker_map"},
            {"sprecher_stand", "speaker_map_time"},
            {"ruhe_r", "idle_route_r"},
            {"ruhe_l", "idle_route_l"},
            {"ruhe_modus", "idle_mode"},
            {"led_modus", "led_mode"},
            {"led_helligkeit", "led_brightness"},
            {"led_tempo", "led_speed"},
        };
        for (String[] k : keys) {
            String value = p.getProperty(k[0]);
            if (value != null && p.getProperty(k[1]) == null) {
                p.setProperty(k[1], value);
            }
        }
    }

    private void saveState() {
        if (stateFile == null) {
            return;
        }
        Properties p = new Properties();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SECTORS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.3f", speakerWeight[i]));
        }
        p.setProperty("speaker_map", sb.toString());
        p.setProperty("speaker_map_time", String.valueOf(speakerMapTime));
        p.setProperty("beam_lock", String.valueOf(beamLockActive));
        p.setProperty("idle_mode", String.valueOf(idleMode));
        p.setProperty("led_mode", String.valueOf(led.getMode()));
        p.setProperty("led_brightness", String.valueOf(led.getBrightness()));
        p.setProperty("led_speed", String.valueOf(led.getSpeed()));
        if (savedIdleR != null && savedIdleL != null) {
            p.setProperty("idle_route_r", fmtComma(savedIdleR));
            p.setProperty("idle_route_l", fmtComma(savedIdleL));
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(stateFile);
            p.store(out, "reSpeaker XVF3800 Support");
        } catch (Exception e) {
            Log.w(TAG, "State not writable", e);
        } finally {
            closeQuietly(out);
        }
    }

    private static int[] parseRoute(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // Nothing to save.
            }
        }
    }

    // ---- Connection and leftovers ---------------------------------------------

    /**
     * ensureOpen plus a check of every new connection for leftovers. If Ava
     * dies during a lock (force stop, update, crash), the reSpeaker's channels
     * stay on the fixed beam. Without this check the next start would take
     * exactly that for the idle routing.
     */
    private boolean connectChecked() {
        if (!link.ensureOpen()) {
            return false;
        }
        int n = link.getConnectionCount();
        if (n != checkedConnection && !locked) {
            checkedConnection = n;
            checkIdleState();
            led.logFirmwareState();
            led.forget();
            if (!sessionActive) {
                led.idle();
            }
            reporter.stateChanged();
        }
        return true;
    }

    private void checkIdleState() {
        int[] r;
        int[] l;
        try {
            r = link.readInts(XvfParam.OP_R);
            l = link.readInts(XvfParam.OP_L);
        } catch (XvfLink.XvfException e) {
            report("channels not readable, switching disabled: " + e.getMessage());
            idleRouteR = null;
            idleRouteL = null;
            return;
        }
        int fixed = 0;
        try {
            fixed = link.readInts(XvfParam.FIXED_ONOFF)[0];
        } catch (XvfLink.XvfException e) {
            Log.w(TAG, "FIXED_ONOFF not readable: " + e.getMessage());
        }
        boolean leftover = fixed != 0
            || (same(r, COMMAND_ROUTE) && same(l, COMMAND_ROUTE));
        // Both sides equal on ASR or conference: an idle mode left that
        // behind, not the factory routing. Do not remember it as idle routing.
        boolean modeLeftover = same(r, l) && (same(r, ASR_AUTO) || same(r, CONF_AUTO));
        if (!leftover && !modeLeftover) {
            idleRouteR = r.clone();
            idleRouteL = l.clone();
            if (!same(r, savedIdleR) || !same(l, savedIdleL)) {
                savedIdleR = r.clone();
                savedIdleL = l.clone();
                saveState();
            }
            report("idle channels R=" + fmt(r) + " L=" + fmt(l));
            applyIdleRoute(false);
            return;
        }
        if (!leftover) {
            idleRouteR = (savedIdleR != null ? savedIdleR : FACTORY_R).clone();
            idleRouteL = (savedIdleL != null ? savedIdleL : FACTORY_L).clone();
            report("idle channels: mode routing R=" + fmt(r) + " L=" + fmt(l)
                   + " found, factory routing taken as R=" + fmt(idleRouteR)
                   + " L=" + fmt(idleRouteL));
            applyIdleRoute(true);
            return;
        }
        int[] targetR = savedIdleR != null ? savedIdleR : FACTORY_R;
        int[] targetL = savedIdleL != null ? savedIdleL : FACTORY_L;
        try {
            link.writeBytes(XvfParam.OP_R, targetR);
            link.writeBytes(XvfParam.OP_L, targetL);
            link.writeInt(XvfParam.FIXED_ONOFF, 0);
            idleRouteR = targetR.clone();
            idleRouteL = targetL.clone();
            report("leftover lock cleared: found R=" + fmt(r) + " L=" + fmt(l)
                   + " fixed=" + fixed + ", back to R=" + fmt(targetR)
                   + " L=" + fmt(targetL));
            applyIdleRoute(false);
        } catch (XvfLink.XvfException e) {
            report("leftover lock not clearable, switching disabled: " + e.getMessage());
            idleRouteR = null;
            idleRouteL = null;
        }
    }

    private int[] idleR() {
        return idleMode == 1 ? ASR_AUTO : idleMode == 2 ? CONF_AUTO : idleRouteR;
    }

    private int[] idleL() {
        return idleMode == 1 ? ASR_AUTO : idleMode == 2 ? CONF_AUTO : idleRouteL;
    }

    /**
     * Applies the idle routing of the selected mode. For the factory mode only
     * on explicit request - after the check it is in place anyway.
     */
    private void applyIdleRoute(boolean includeFactory) {
        if ((idleMode == 0 && !includeFactory) || locked || idleRouteR == null) {
            return;
        }
        try {
            link.writeBytes(XvfParam.OP_R, idleR());
            link.writeBytes(XvfParam.OP_L, idleL());
            report("idle channels " + IDLE_MODES[idleMode] + ": R=" + fmt(idleR())
                   + " L=" + fmt(idleL()));
        } catch (XvfLink.XvfException e) {
            report("idle channels not settable: " + e.getMessage());
            link.close();
        }
    }

    /**
     * On shutdown: an idle mode other than the factory one would otherwise
     * stay in the reSpeaker after the mod is gone.
     */
    private void restoreFactoryRouting() {
        if (idleMode == 0 || idleRouteR == null || link.getState() != XvfLink.STATE_CONNECTED) {
            return;
        }
        try {
            link.writeBytes(XvfParam.OP_R, idleRouteR);
            link.writeBytes(XvfParam.OP_L, idleRouteL);
            Log.i(TAG, "channels back to R=" + fmt(idleRouteR) + " L=" + fmt(idleRouteL));
        } catch (XvfLink.XvfException e) {
            Log.w(TAG, "channels not restorable: " + e.getMessage());
        }
    }

    void setIdleMode(final int mode) {
        if (mode < 0 || mode >= IDLE_MODES.length) {
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                idleMode = mode;
                saveState();
                if (locked || link.getState() != XvfLink.STATE_CONNECTED) {
                    report("idle mode " + IDLE_MODES[mode] + " applies from the next release");
                    return;
                }
                applyIdleRoute(true);
            }
        });
    }

    int getIdleMode() {
        return idleMode;
    }

    // ---- LED ring -------------------------------------------------------------

    private static final long ERROR_DISPLAY_MS = 2000L;

    /**
     * Pipeline event for the LED ring. Independent of the beam lock: the ring
     * shows what Ava is doing even when the lock is off.
     */
    void ledEvent(final String event) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (link.getState() != XvfLink.STATE_CONNECTED) {
                    return;
                }
                if ("wake_detected".equals(event) || "listening_started".equals(event)) {
                    led.wake();
                } else if ("stt_end".equals(event) || "processing_started".equals(event)) {
                    led.think();
                } else if ("responding".equals(event) || "tts_start".equals(event)
                        || "tts_playback_started".equals(event)) {
                    led.speak();
                } else if ("pipeline_error".equals(event)) {
                    led.error();
                    errorUntil = System.currentTimeMillis() + ERROR_DISPLAY_MS;
                    worker.schedule(new Runnable() {
                        @Override
                        public void run() {
                            if (!sessionActive) {
                                led.idle();
                            }
                        }
                    }, ERROR_DISPLAY_MS, TimeUnit.MILLISECONDS);
                } else if ("run_end".equals(event) || "session_ended".equals(event)
                        || "tts_finished".equals(event)) {
                    // tts_playback_started often arrives AFTER run_end because
                    // Ava plays the reply itself; only tts_finished is the real
                    // end. Otherwise the reply animation stayed on.
                    if (System.currentTimeMillis() >= errorUntil) {
                        led.idle();
                    }
                }
            }
        });
    }

    void setLedMode(final int mode) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                led.setMode(mode, sessionActive);
                saveState();
            }
        });
    }

    void setLedBrightness(final int index) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                led.setBrightness(index);
                if (!sessionActive) {
                    led.idle();
                }
                saveState();
            }
        });
    }

    int getLedMode() {
        return led.getMode();
    }

    void setLedSpeed(final int index) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                led.setSpeed(index);
                saveState();
            }
        });
    }

    int getLedSpeed() {
        return led.getSpeed();
    }

    int getLedBrightness() {
        return led.getBrightness();
    }

    // ---- Background poll ------------------------------------------------------

    /**
     * Keeps the connection up and, while the beam lock is active, measures
     * direction and energy of all four beams. The lookback lock and the
     * interference map read from this buffer. With the beam lock off only the
     * connection is kept, for the LED ring - no measurement traffic.
     */
    private void poll() {
        if (sessionActive || locked) {
            return;
        }
        if (link.getState() != XvfLink.STATE_CONNECTED) {
            long now = System.currentTimeMillis();
            if (now - lastConnectAttempt < RECONNECT_PAUSE_MS) {
                return;
            }
            lastConnectAttempt = now;
        }
        if (!connectChecked()) {
            return;
        }
        if (!beamLockActive || (!lookbackEnabled && !zoneLearningEnabled)) {
            return;
        }
        float[] az;
        float[] sp;
        try {
            az = link.readFloats(XvfParam.AZIMUTH);
            sp = link.readFloats(XvfParam.SPENERGY);
        } catch (XvfLink.XvfException e) {
            link.close();
            return;
        }
        float[] dir = new float[az.length];
        for (int i = 0; i < az.length; i++) {
            dir[i] = wrapDeg((float) Math.toDegrees(az[i]));
        }
        bufTime[bufHead] = System.currentTimeMillis();
        bufEnergy[bufHead] = sp;
        bufDir[bufHead] = dir;
        bufHead = (bufHead + 1) % BUFFER_SIZE;
        if (bufCount < BUFFER_SIZE) {
            bufCount++;
        }
    }

    // ---- Interference map -----------------------------------------------------

    /**
     * One learning step. Runs only outside a voice session and only once the
     * speaker map knows where people stand - otherwise the map wins the race
     * against the first lock and learns the speaker's seat as interference.
     */
    private void learnTick() {
        long now = System.currentTimeMillis();
        if (pipelineActive && now - pipelineSince > PIPELINE_MAX_MS) {
            pipelineActive = false;
        }
        if (!beamLockActive || !zoneLearningEnabled || sessionActive || locked || pipelineActive) {
            return;
        }
        ageSpeakerMap(now);
        if (sum(speakerWeight) < SPEAKER_MIN_TOTAL) {
            return;
        }
        int[] hits = new int[SECTORS];
        int polls = 0;
        for (int k = 0; k < bufCount; k++) {
            if (now - bufTime[k] > LEARN_INTERVAL_MS) {
                continue;
            }
            polls++;
            boolean[] seen = new boolean[SECTORS];
            float[] e = bufEnergy[k];
            float[] d = bufDir[k];
            for (int b : FREE_BEAMS) {
                if (e[b] < energyThreshold) {
                    continue;
                }
                int s = sector(d[b]);
                if (!seen[s]) {
                    seen[s] = true;
                    hits[s]++;
                }
            }
        }
        if (polls < 5) {
            // No polls in this tick - connection gone or learning just started.
            return;
        }
        int threshold = Math.max(TICK_MIN_HITS, Math.round(TICK_MIN_SHARE * polls));
        boolean[] active = new boolean[SECTORS];
        for (int s = 0; s < SECTORS; s++) {
            // Including neighbours: a TV at a distance spreads over 30 degrees
            // and reaches the threshold in no single sector.
            active[s] = hits[neighbor(s, -1)] + hits[s] + hits[neighbor(s, 1)] >= threshold;
            zoneHistory[s] = ((zoneHistory[s] << 1) | (active[s] ? 1 : 0)) & HISTORY_MASK;
            zoneWeight[s] *= DECAY;
        }
        boolean changed = false;
        for (int s = 0; s < SECTORS; s++) {
            if (!active[s]) {
                continue;
            }
            // Surroundings instead of a single sector: a real source spreads
            // around the sector boundary, a walking person leaves the area.
            int around = zoneHistory[neighbor(s, -1)] | zoneHistory[s] | zoneHistory[neighbor(s, 1)];
            if (Integer.bitCount(around) < PERSIST_MIN_TICKS) {
                continue;
            }
            float gain = 1f / (1f + SPEAKER_BRAKE * speakerNearby(s));
            boolean before = zoneWeight[s] >= ZONE_LIMIT;
            zoneWeight[s] += gain;
            if (!before && zoneWeight[s] >= ZONE_LIMIT) {
                report("interference zone learned: " + Math.round(sectorCenter(s)) + " deg");
                changed = true;
            }
        }
        if (++learnTicks % HISTORY_TICKS == 0) {
            StringBuilder sb = new StringBuilder("learning state (sector: weight/active ticks of "
                                                 + HISTORY_TICKS + "):");
            for (int s = 0; s < SECTORS; s++) {
                int ticks = Integer.bitCount(zoneHistory[s]);
                if (zoneWeight[s] >= 1f || ticks >= 5) {
                    sb.append(' ').append(Math.round(sectorCenter(s))).append(':')
                      .append(String.format(Locale.US, "%.0f", zoneWeight[s]))
                      .append('/').append(ticks);
                }
            }
            Log.i(TAG, sb.toString() + " | " + polls + " readings in tick");
        }
        if (changed) {
            reporter.stateChanged();
        }
    }

    private float speakerNearby(int s) {
        return Math.max(speakerWeight[s],
            Math.max(speakerWeight[neighbor(s, -1)], speakerWeight[neighbor(s, 1)]));
    }

    private void ageSpeakerMap(long now) {
        long dt = now - speakerMapTime;
        if (dt <= 0) {
            return;
        }
        float factor = (float) Math.pow(0.5, (double) dt / SPEAKER_HALF_LIFE_MS);
        for (int i = 0; i < SECTORS; i++) {
            speakerWeight[i] *= factor;
        }
        speakerMapTime = now;
    }

    private void rememberSpeaker(float deg) {
        ageSpeakerMap(System.currentTimeMillis());
        int s = sector(deg);
        speakerWeight[s] = Math.min(SPEAKER_MAX, speakerWeight[s] + SPEAKER_GAIN);
        saveState();
    }

    private static int sector(float deg) {
        int s = (int) (wrapDeg(deg) / SECTOR_DEG);
        return s >= SECTORS ? SECTORS - 1 : s;
    }

    private static int neighbor(int s, int d) {
        return ((s + d) % SECTORS + SECTORS) % SECTORS;
    }

    private static float sectorCenter(int s) {
        return s * SECTOR_DEG + SECTOR_DEG / 2f;
    }

    private static float sum(float[] values) {
        float s = 0f;
        for (float v : values) {
            s += v;
        }
        return s;
    }

    /** Learned zones as text, for the diagnostic entity. */
    String learnedZonesText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SECTORS; i++) {
            if (zoneWeight[i] >= ZONE_LIMIT) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(Math.round(sectorCenter(i)))
                  .append("° (w=").append(Math.round(zoneWeight[i])).append(')');
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** Speaker map as text, for the diagnostic entity. */
    String speakerMapText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SECTORS; i++) {
            if (speakerWeight[i] >= 0.5f) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(Math.round(sectorCenter(i))).append("° (")
                  .append(String.format(Locale.US, "%.1f", speakerWeight[i])).append(')');
            }
        }
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    /** Clears both maps, for example after moving to another room. */
    void resetMaps() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < SECTORS; i++) {
                    zoneWeight[i] = 0f;
                    zoneHistory[i] = 0;
                    speakerWeight[i] = 0f;
                }
                speakerMapTime = System.currentTimeMillis();
                saveState();
                report("maps reset - interference zones learn again after the next lock");
                reporter.stateChanged();
            }
        });
    }

    // ---- State for the entities -----------------------------------------------

    boolean isLocked() {
        return locked;
    }

    float getLockedAzimuthDeg() {
        return lockedAzimuthDeg;
    }

    boolean isReleaseAfterStt() {
        return releaseAfterStt;
    }

    // ---- Configuration --------------------------------------------------------

    /**
     * Persisted here because Ava did not restore the switch state after a
     * restart in testing, and the beam lock is off by default.
     */
    void setBeamLockActive(final boolean v) {
        beamLockActive = v;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                saveState();
            }
        });
    }

    boolean isBeamLockActive() {
        return beamLockActive;
    }
    void setZoneLearningEnabled(boolean v) { zoneLearningEnabled = v; }
    void setTrackingEnabled(boolean v) { trackingEnabled = v; }
    void setFastLockEnabled(boolean v) { fastLockEnabled = v; }
    void setLookbackEnabled(boolean v) { lookbackEnabled = v; }
    void setReleaseAfterStt(boolean v) { releaseAfterStt = v; }
    void setCorrectOnSpeech(boolean v) { correctOnSpeech = v; }
    void setGatingEnabled(boolean v) { gatingEnabled = v; }
    void setChannelSwitchEnabled(boolean v) { channelSwitchEnabled = v; }
    void setEnergyThreshold(float v) { energyThreshold = Math.max(0f, v); }
    void setTrackWindowDeg(float v) { trackWindowDeg = clamp(v, 5f, 180f); }
    void setMaxLockSeconds(int v) { maxLockMs = (long) clamp(v, 5f, 300f) * 1000L; }
    void setSearchSeconds(int v) { searchMs = (long) clamp(v, 0f, 60f) * 1000L; }

    /** Sets interferer 0..2; values below 0 free the slot. */
    void setInterferer(int index, float deg) {
        if (index < 0 || index >= interferers.length) {
            return;
        }
        interferers[index] = deg < 0f ? Float.NaN : wrapDeg(deg);
    }

    float getInterferer(int index) {
        if (index < 0 || index >= interferers.length) {
            return Float.NaN;
        }
        return interferers[index];
    }

    // ---- Pipeline events --------------------------------------------------------

    /** Wake word. synthetic = triggered without a spoken wake word. */
    void onWake(final boolean synthetic, final String info) {
        final long wakeTime = System.currentTimeMillis();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (synthetic) {
                    report("synthetic wake" + info + " - no lookback possible");
                    withoutWakeWord();
                    return;
                }
                wakeSequence(wakeTime, info);
            }
        });
    }

    /**
     * Recording starts. After a wake word the session is already active and
     * the event means nothing. Without a wake word - microphone button,
     * follow-up question in continuous conversation - it is the only entry.
     */
    void onListen() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (sessionActive) {
                    return;
                }
                withoutWakeWord();
            }
        });
    }

    /** Speech recognition detected speech: now the person is talking. */
    void onSpeechStart() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                checkOnSpeechStart();
            }
        });
    }

    /**
     * Release. pipelineEnd = the whole session is over, not just speech
     * recognition.
     */
    void onSessionEnd(final String reason, final boolean pipelineEnd) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                releaseLock(reason);
                if (pipelineEnd) {
                    pipelineActive = false;
                }
            }
        });
    }

    /** Clean-up when the mod is disabled; waits briefly for the release. */
    void shutdown() {
        cancel(pollTask);
        cancel(learnTask);
        try {
            Future<?> f = worker.submit(new Runnable() {
                @Override
                public void run() {
                    releaseLock("mod shutting down");
                    restoreFactoryRouting();
                    led.restoreFactory();
                    saveState();
                }
            });
            f.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Release on shutdown did not complete", e);
        }
        worker.shutdownNow();
    }

    // ---- Sequence ---------------------------------------------------------------

    private void beginSession() {
        sessionActive = true;
        pipelineActive = true;
        pipelineSince = System.currentTimeMillis();
    }

    private void wakeSequence(long wakeTime, String info) {
        beginSession();
        if (!connectChecked()) {
            report("wake word without control channel: " + link.getDetail());
            return;
        }
        Log.i(TAG, "wake word" + info);
        if (lookbackEnabled && tryLookback(wakeTime)) {
            return;
        }
        if (fastLockEnabled && tryFastLock()) {
            return;
        }
        if (!tryLock()) {
            // The raw values belong in the log - without them "no candidate"
            // cannot be told apart from a broken measurement path.
            report("no candidate: " + probeText());
            if (searchMs > 0) {
                startSearch();
            }
        }
    }

    /**
     * Session without a spoken wake word. If a lock was released less than
     * FOLLOW_UP_MS ago, this is a follow-up from the same person: their
     * direction still applies. Otherwise wait until someone speaks.
     */
    private void withoutWakeWord() {
        beginSession();
        if (!connectChecked()) {
            report("session without control channel: " + link.getDetail());
            return;
        }
        if (!Float.isNaN(lastDirection)
                && System.currentTimeMillis() - lastRelease < FOLLOW_UP_MS
                && !inInterfererZone(lastDirection)) {
            fixate(lastDirection, "follow-up lock", -1f, false);
            return;
        }
        report("session without wake word - waiting for speech");
        if (!tryLock() && searchMs > 0) {
            startSearch();
        }
    }

    /**
     * The lookback looks for the ONSET, not the loudest direction. The wake
     * word is a short burst in the last 1.5 s before the event; speakers and
     * TVs were already playing before. Each sector including its neighbours
     * is scored by the mean energy in the wake word window minus the mean in
     * the baseline before it. Plain energy sums locked onto a loudspeaker two
     * times out of three.
     *
     * An onset is unambiguous only when it beats the next best distant one by
     * ONSET_MARGIN. Only unambiguous locks feed the speaker map - otherwise
     * the map learns loudspeakers as people from mistakes and slows down
     * exactly their interference zones.
     */
    private boolean tryLookback(long wakeTime) {
        double[] now = new double[SECTORS];
        double[] before = new double[SECTORS];
        double[] sx = new double[SECTORS];
        double[] sy = new double[SECTORS];
        int nNow = 0;
        int nBefore = 0;
        for (int k = 0; k < bufCount; k++) {
            long age = wakeTime - bufTime[k];
            if (age < 0 || age > LOOKBACK_MS + BASELINE_MS) {
                continue;
            }
            boolean inWindow = age <= LOOKBACK_MS;
            if (inWindow) {
                nNow++;
            } else {
                nBefore++;
            }
            float[] e = bufEnergy[k];
            float[] d = bufDir[k];
            for (int b : FREE_BEAMS) {
                if (e[b] < energyThreshold || inInterfererZone(d[b])) {
                    continue;
                }
                int s = sector(d[b]);
                if (inWindow) {
                    now[s] += e[b];
                    sx[s] += e[b] * Math.cos(d[b] * RAD);
                    sy[s] += e[b] * Math.sin(d[b] * RAD);
                } else {
                    before[s] += e[b];
                }
            }
        }
        if (nNow == 0) {
            report("lookback empty: no reading before the wake word");
            return false;
        }
        double[] level = new double[SECTORS];
        double[] baseline = new double[SECTORS];
        double[] onset = new double[SECTORS];
        for (int s = 0; s < SECTORS; s++) {
            level[s] = around(now, s) / nNow;
            baseline[s] = nBefore > 0 ? around(before, s) / nBefore : 0;
            onset[s] = level[s] - baseline[s];
        }
        int best = -1;
        for (int s = 0; s < SECTORS; s++) {
            if (level[s] >= energyThreshold && onset[s] > 0
                    && (best < 0 || onset[s] > onset[best])) {
                best = s;
            }
        }
        if (best < 0) {
            report("lookback: no onset outside interference zones - "
                   + candidatesText(level, baseline, onset, -1));
            return false;
        }
        int second = -1;
        for (int s = 0; s < SECTORS; s++) {
            int distance = Math.abs(s - best);
            distance = Math.min(distance, SECTORS - distance);
            if (distance > 2 && (second < 0 || onset[s] > onset[second])) {
                second = s;
            }
        }
        boolean clear = second < 0 || onset[second] <= 0
            || onset[best] >= ONSET_MARGIN * onset[second];
        double x = around(sx, best);
        double y = around(sy, best);
        float dir = wrapDeg((float) Math.toDegrees(Math.atan2(y, x)));
        if (inInterfererZone(dir)) {
            report("lookback rejected: mean " + Math.round(dir) + " deg in interference zone");
            return false;
        }
        report("lookback " + (clear ? "clear" : "close") + ": "
               + candidatesText(level, baseline, onset, best)
               + " (baseline " + nBefore + " readings)");
        return fixate(dir, clear ? "lookback lock" : "lookback lock (close)",
                      (float) onset[best], clear);
    }

    private static double around(double[] values, int s) {
        return values[neighbor(s, -1)] + values[s] + values[neighbor(s, 1)];
    }

    /** The three strongest onsets, for the log. */
    private static String candidatesText(double[] level, double[] baseline,
                                         double[] onset, int best) {
        boolean[] taken = new boolean[SECTORS];
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 3; r++) {
            int m = -1;
            for (int s = 0; s < SECTORS; s++) {
                if (!taken[s] && level[s] > 0 && (m < 0 || onset[s] > onset[m])) {
                    m = s;
                }
            }
            if (m < 0) {
                break;
            }
            for (int d = -1; d <= 1; d++) {
                taken[neighbor(m, d)] = true;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(Math.round(sectorCenter(m))).append(m == best ? "*" : "")
              .append(String.format(Locale.US, " %+.0fk (now %.0fk, before %.0fk)",
                                    onset[m] / 1000, level[m] / 1000, baseline[m] / 1000));
        }
        return sb.length() == 0 ? "no energy" : sb.toString();
    }

    /**
     * The direction of the auto-select beam at the wake word. Since the
     * lookback only a fallback, for example when the poll has nothing in the
     * buffer yet.
     */
    private boolean tryFastLock() {
        float[] az;
        try {
            az = link.readFloats(XvfParam.AZIMUTH);
        } catch (XvfLink.XvfException e) {
            report("fast lock reading failed: " + e.getMessage());
            link.close();
            return false;
        }
        float dir = wrapDeg((float) Math.toDegrees(az[3]));
        if (inInterfererZone(dir)) {
            report("fast lock refused: auto beam at "
                   + Math.round(dir) + " deg in interference zone");
            return false;
        }
        return fixate(dir, "fast lock", -1f, false);
    }

    /**
     * At speech start the person is demonstrably talking. If the strongest
     * direction outside the interference zones is farther from the lock than
     * the tracking window, the lock was wrong - move it.
     */
    private void checkOnSpeechStart() {
        if (!locked) {
            return;
        }
        float[][] probe;
        try {
            probe = sample(SAMPLES_SPEECH_START);
        } catch (XvfLink.XvfException e) {
            Log.w(TAG, "speech start reading failed: " + e.getMessage());
            return;
        }
        int best = bestCandidate(probe[0], probe[1]);
        if (best < 0) {
            return;
        }
        float next = probe[1][best];
        float delta = deltaDeg(lockedAzimuthDeg, next);
        if (Math.abs(delta) <= trackWindowDeg) {
            Log.i(TAG, "speech start confirms lock (" + Math.round(delta) + " deg off)");
            return;
        }
        // In the near field the strongest direction at speech start is often
        // the loudspeaker or its reflection. So only move with the switch on;
        // otherwise just log what would have happened.
        if (!correctOnSpeech) {
            report("speech start: strongest direction " + Math.round(next) + " deg (E="
                   + Math.round(probe[0][best]) + "), lock stays at "
                   + Math.round(lockedAzimuthDeg));
            return;
        }
        report("correction at speech start: " + Math.round(lockedAzimuthDeg)
               + " -> " + Math.round(next) + " deg");
        fixate(next, "correction lock", probe[0][best], false);
    }

    private void startSearch() {
        cancel(searchTask);
        final long deadline = System.currentTimeMillis() + searchMs;
        report("searching for up to " + (searchMs / 1000) + " s");
        searchTask = worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (locked) {
                    cancel(searchTask);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    report("search found nothing: " + probeText());
                    cancel(searchTask);
                    return;
                }
                tryLock();
            }
        }, SEARCH_INTERVAL_MS, SEARCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Last reading as text, for diagnosis through the event log. */
    private String probeText() {
        float[][] p = lastProbe;
        if (p == null) {
            return "no reading";
        }
        StringBuilder sb = new StringBuilder("E=[");
        for (int i = 0; i < p[0].length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Math.round(p[0][i]));
        }
        sb.append("] D=[");
        for (int i = 0; i < p[1].length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Math.round(p[1][i]));
        }
        return sb.append(']').toString();
    }

    /** One reading, one decision. true if the beam was locked. */
    private boolean tryLock() {
        float[][] probe;
        try {
            probe = sample(SAMPLES_LOCK);
        } catch (XvfLink.XvfException e) {
            report("reading failed: " + e.getMessage());
            link.close();
            return false;
        }
        lastProbe = probe;
        int best = bestCandidate(probe[0], probe[1]);
        if (best < 0) {
            int amn = amnestyCandidate(probe[0], probe[1]);
            if (amn >= 0) {
                int s = sector(probe[1][amn]);
                for (int d = -1; d <= 1; d++) {
                    zoneWeight[neighbor(s, d)] = 0f;
                    zoneHistory[neighbor(s, d)] = 0;
                }
                report("amnesty: zone at " + Math.round(probe[1][amn])
                       + " deg cleared - only active direction after the wake word");
                return fixate(probe[1][amn], "amnesty lock", probe[0][amn], false);
            }
            return false;
        }
        return fixate(probe[1][best], "lock beam " + best, probe[0][best], false);
    }

    /**
     * The strongest beam that is neither quiet nor inside an interference
     * zone. The threshold alone is not enough - the interfering source plays
     * at voice level.
     */
    private int bestCandidate(float[] energy, float[] dirDeg) {
        int best = -1;
        for (int i : FREE_BEAMS) {
            if (energy[i] < energyThreshold) {
                continue;
            }
            if (inInterfererZone(dirDeg[i])) {
                continue;
            }
            if (best < 0 || energy[i] > energy[best]) {
                best = i;
            }
        }
        return best;
    }

    private boolean inManualZone(float deg) {
        for (float s : interferers) {
            if (!Float.isNaN(s) && Math.abs(deltaDeg(s, deg)) <= excludeDeg) {
                return true;
            }
        }
        return false;
    }

    private boolean inLearnedZone(float deg) {
        if (!zoneLearningEnabled) {
            return false;
        }
        // The sector plus its neighbours, because a real source spreads by
        // about ten degrees.
        int s = sector(deg);
        for (int d = -1; d <= 1; d++) {
            if (zoneWeight[neighbor(s, d)] >= ZONE_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private boolean inInterfererZone(float deg) {
        return inManualZone(deg) || inLearnedZone(deg);
    }

    /**
     * Zone amnesty: a learned zone only falls when the candidate is the ONLY
     * active direction in the room - then the source that created the zone is
     * silent. Manual zones always stay.
     */
    private int amnestyCandidate(float[] energy, float[] dirDeg) {
        int best = -1;
        for (int i : FREE_BEAMS) {
            if (energy[i] >= energyThreshold
                    && (best < 0 || energy[i] > energy[best])) {
                best = i;
            }
        }
        if (best < 0) {
            return -1;
        }
        float dir = dirDeg[best];
        if (inManualZone(dir) || !inLearnedZone(dir)) {
            return -1;
        }
        for (int i : FREE_BEAMS) {
            if (energy[i] >= energyThreshold
                    && Math.abs(deltaDeg(dir, dirDeg[i])) > 25f) {
                return -1;
            }
        }
        return best;
    }

    /**
     * Lock and switch. energy < 0 means decided without an energy reading.
     * feedMap = the direction counts as evidence of a speaker.
     */
    private boolean fixate(float speakerDeg, String kind, float energy, boolean feedMap) {
        try {
            writeBeams(speakerDeg);
            link.writeInt(XvfParam.FIXED_GATING, gatingEnabled ? 1 : 0);
            link.writeInt(XvfParam.FIXED_ONOFF, 1);
            // Both channels, not just one - Ava hears the mono mix.
            if (channelSwitchEnabled && idleRouteR != null) {
                link.writeBytes(XvfParam.OP_R, COMMAND_ROUTE);
                link.writeBytes(XvfParam.OP_L, COMMAND_ROUTE);
            }
        } catch (XvfLink.XvfException e) {
            report("locking failed: " + e.getMessage());
            link.close();
            return false;
        }
        locked = true;
        lockedAzimuthDeg = speakerDeg;
        lockSince = System.currentTimeMillis();
        // Only an unambiguous lock proves a person is standing there. An
        // uncertain one may neither feed the map nor clear zones - otherwise
        // a mistaken lock on a loudspeaker would clear that speaker's zone.
        if (feedMap) {
            int ss = sector(speakerDeg);
            for (int d = -1; d <= 1; d++) {
                zoneWeight[neighbor(ss, d)] = 0f;
                zoneHistory[neighbor(ss, d)] = 0;
            }
            rememberSpeaker(speakerDeg);
        }
        if (energy < 0f) {
            report(String.format(Locale.US, "%s %.1f deg", kind, speakerDeg));
        } else {
            report(String.format(Locale.US, "%s %.1f deg (E=%.0f)", kind, speakerDeg, energy));
        }
        led.direction(speakerDeg);
        startTracking();
        startWatchdog();
        reporter.stateChanged();
        return true;
    }

    private void writeBeams(float speakerDeg) throws XvfLink.XvfException {
        // Both beams on the speaker, as FormatBCE does. An opposite beam at
        // speaker + 180 degrees was a self-made ghost: the auto-select beam
        // chose it, and the lookback locked onto it repeatedly. Beam 1 only
        // gets a task of its own with gating and a manual interferer.
        float second = gatingEnabled && !Float.isNaN(interferers[0])
            ? interferers[0]
            : speakerDeg;
        link.writeFloats(XvfParam.FIXED_AZIMUTH,
                         new float[] {speakerDeg * RAD, second * RAD});
    }

    private void startTracking() {
        cancel(trackTask);
        if (!trackingEnabled) {
            return;
        }
        trackTask = worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                trackOnce();
            }
        }, TRACK_INTERVAL_MS, TRACK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void startWatchdog() {
        cancel(watchdogTask);
        watchdogTask = worker.schedule(new Runnable() {
            @Override
            public void run() {
                if (locked) {
                    releaseLock("watchdog after " + (maxLockMs / 1000) + " s");
                }
            }
        }, maxLockMs, TimeUnit.MILLISECONDS);
    }

    private void trackOnce() {
        if (!locked) {
            return;
        }
        float[][] probe;
        try {
            probe = sample(SAMPLES_TRACK);
        } catch (XvfLink.XvfException e) {
            report("tracking stopped: " + e.getMessage());
            cancel(trackTask);
            return;
        }
        int best = -1;
        for (int i : FREE_BEAMS) {
            if (i < probe[0].length && probe[0][i] >= energyThreshold
                    && !inInterfererZone(probe[1][i])
                    && (best < 0 || probe[0][i] > probe[0][best])) {
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        float delta = deltaDeg(lockedAzimuthDeg, probe[1][best]);
        if (Math.abs(delta) > trackWindowDeg || Math.abs(delta) < MIN_CORRECTION_DEG) {
            return;
        }
        float updated = wrapDeg(lockedAzimuthDeg
                                + clamp(delta, -maxStepDeg, maxStepDeg));
        if (inInterfererZone(updated)) {
            return;
        }
        try {
            writeBeams(updated);
            lockedAzimuthDeg = updated;
            led.direction(updated);
        } catch (XvfLink.XvfException e) {
            report("tracking step failed: " + e.getMessage());
        }
    }

    private void releaseLock(String reason) {
        cancel(searchTask);
        cancel(trackTask);
        cancel(watchdogTask);
        sessionActive = false;
        if (!locked) {
            return;
        }
        lastDirection = lockedAzimuthDeg;
        lastRelease = System.currentTimeMillis();
        locked = false;
        lockedAzimuthDeg = Float.NaN;
        try {
            // Channels back first, then release the beams - the other way
            // round Ava would briefly listen to a beam nothing holds anymore.
            if (channelSwitchEnabled && idleRouteR != null) {
                link.writeBytes(XvfParam.OP_R, idleR());
                link.writeBytes(XvfParam.OP_L, idleL());
            }
            link.writeInt(XvfParam.FIXED_ONOFF, 0);
            report("released (" + reason + ")");
        } catch (XvfLink.XvfException e) {
            report("release failed: " + e.getMessage());
            link.close();
        }
        reporter.stateChanged();
    }

    /** Several readings, holding the peak per beam. [0]=energy, [1]=degrees. */
    private float[][] sample(int rounds) throws XvfLink.XvfException {
        float[] peak = null;
        float[] dir = null;
        for (int r = 0; r < rounds; r++) {
            float[] az = link.readFloats(XvfParam.AZIMUTH);
            float[] sp = link.readFloats(XvfParam.SPENERGY);
            if (peak == null) {
                peak = sp.clone();
                dir = new float[az.length];
                for (int i = 0; i < az.length; i++) {
                    dir[i] = wrapDeg((float) Math.toDegrees(az[i]));
                }
                continue;
            }
            for (int i = 0; i < sp.length; i++) {
                if (sp[i] > peak[i]) {
                    peak[i] = sp[i];
                    dir[i] = wrapDeg((float) Math.toDegrees(az[i]));
                }
            }
        }
        return new float[][] {peak, dir};
    }

    private void report(String text) {
        String line = clock.format(new Date()) + " " + text;
        Log.i(TAG, line);
        reporter.event(line);
    }

    private static boolean same(int[] a, int[] b) {
        return a != null && b != null && a.length == b.length
            && a[0] == b[0] && (a.length < 2 || a[1] == b[1]);
    }

    private static String fmt(int[] v) {
        return v == null ? "?" : v[0] + "/" + v[1];
    }

    private static String fmtComma(int[] v) {
        return v[0] + "," + v[1];
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    static float wrapDeg(float deg) {
        float v = deg % 360f;
        return v < 0f ? v + 360f : v;
    }

    /** Shortest way between two angles, -180 to 180. */
    static float deltaDeg(float from, float to) {
        return wrapDeg(to - from + 180f) - 180f;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
