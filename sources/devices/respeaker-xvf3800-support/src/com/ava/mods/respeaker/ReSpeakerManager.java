package com.ava.mods.respeaker;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point of the mod; Ava loads this class through "manager" in
 * manifest.json and calls it by reflection.
 *
 * Two jobs on a reSpeaker XVF3800 running USB firmware:
 * - the LED ring shows Ava's voice session (always on unless set to
 *   "Do not control"),
 * - the optional beam lock holds the beamformer on the person who said the
 *   wake word for the duration of the command.
 */
public class ReSpeakerManager implements BeamTracker.Reporter {

    private static final String TAG = "ReSpeaker";

    private static final String ENTITY_BEAM_LOCK = "beam_lock";
    private static final String ENTITY_LINK_STATE = "link_state";
    private static final String ENTITY_AZIMUTH = "locked_azimuth";
    private static final String ENTITY_LAST_EVENT = "last_event";
    private static final String ENTITY_LEARNED_ZONES = "learned_zones";
    private static final String ENTITY_SPEAKER_MAP = "speaker_map";
    private static final String ENTITY_IDLE_ROUTE = "idle_route";
    private static final String ENTITY_LED_MODE = "led_mode";
    private static final String ENTITY_LED_BRIGHTNESS = "led_brightness";
    private static final String ENTITY_LED_SPEED = "led_speed";
    private static final String[] ENTITY_INTERFERER =
        {"interferer_1", "interferer_2", "interferer_3"};

    private static volatile ReSpeakerManager instance;

    private final XvfLink link;
    private final BeamTracker tracker;
    private final Map<String, CopyOnWriteArrayList<Object>> stateListeners =
        new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();
    private final Deque<String> events = new ArrayDeque<String>();

    /**
     * Off on a fresh install: measured, it rarely beats the chip's own
     * auto-select beam. After that the tracker's state file remembers it.
     */
    private volatile boolean beamLockEnabled;

    private ReSpeakerManager(Context context) {
        link = new XvfLink(context);
        tracker = new BeamTracker(link, this, context.getFilesDir());
        beamLockEnabled = tracker.isBeamLockActive();
    }

    /**
     * Ava calls this when the mod is disabled or removed. Without it a running
     * lock, the poll thread and the USB receiver would stay behind.
     */
    public void onDestroy() {
        tracker.shutdown();
        link.release();
        synchronized (ReSpeakerManager.class) {
            if (instance == this) {
                instance = null;
            }
        }
        Log.i(TAG, "Mod stopped");
    }

    public static ReSpeakerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ReSpeakerManager.class) {
                if (instance == null) {
                    instance = new ReSpeakerManager(context);
                }
            }
        }
        return instance;
    }

    // ---- Events from Ava's voice pipeline ---------------------------------------

    // Bundle.get is deprecated, but the type of the extras is not documented.
    @SuppressWarnings("deprecation")
    public void onVoicePipelineEvent(Context context, String event, Bundle extras) {
        if (event == null) {
            return;
        }
        // The LED ring shows Ava's state even when the beam lock is off.
        tracker.ledEvent(event);
        if (!beamLockEnabled) {
            return;
        }
        if ("wake_detected".equals(event)) {
            boolean synthetic = extras != null
                && parseBoolean(String.valueOf(extras.get("synthetic_wake")), false);
            Object confidence = extras == null ? null : extras.get("wake_confidence");
            tracker.onWake(synthetic,
                confidence == null ? "" : " (confidence " + confidence + ")");
            return;
        }
        if ("listening_started".equals(event)) {
            tracker.onListen();
            return;
        }
        if ("stt_vad_start".equals(event)) {
            tracker.onSpeechStart();
            return;
        }
        if ("stt_end".equals(event)) {
            if (tracker.isReleaseAfterStt()) {
                tracker.onSessionEnd("speech recognised", false);
            }
            return;
        }
        if ("session_ended".equals(event)
                || "run_end".equals(event)
                || "pipeline_error".equals(event)) {
            tracker.onSessionEnd("session ended: " + event, true);
        }
    }

    // ---- Back channel from the tracker ------------------------------------------

    @Override
    public void event(String text) {
        synchronized (events) {
            events.addLast(text);
            while (events.size() > 20) {
                events.removeFirst();
            }
        }
        notifyListeners(ENTITY_LAST_EVENT, text);
    }

    @Override
    public void stateChanged() {
        notifyListeners(ENTITY_AZIMUTH, getLockedAzimuth());
        notifyListeners(ENTITY_LINK_STATE, getLinkState());
        notifyListeners(ENTITY_LEARNED_ZONES, getLearnedZones());
        notifyListeners(ENTITY_SPEAKER_MAP, getSpeakerMap());
    }

    public String getLearnedZones() {
        return tracker.learnedZonesText();
    }

    public String getSpeakerMap() {
        return tracker.speakerMapText();
    }

    public void resetMaps() {
        tracker.resetMaps();
    }

    // ---- Entities for Home Assistant --------------------------------------------

    public void setBeamLockEnabled(String value) {
        boolean enabled = parseBoolean(value, false);
        if (enabled == beamLockEnabled) {
            return;
        }
        beamLockEnabled = enabled;
        tracker.setBeamLockActive(enabled);
        if (!enabled) {
            tracker.onSessionEnd("beam lock switched off", true);
        }
        notifyListeners(ENTITY_BEAM_LOCK, Boolean.valueOf(enabled));
        Log.i(TAG, "Beam lock " + (enabled ? "on" : "off"));
    }

    public boolean isBeamLockEnabled() {
        return beamLockEnabled;
    }

    /** What Ava hears while it waits for the wake word. */
    public void setIdleRoute(String value) {
        int i = index(BeamTracker.IDLE_MODES, value);
        if (i >= 0) {
            tracker.setIdleMode(i);
            notifyListeners(ENTITY_IDLE_ROUTE, BeamTracker.IDLE_MODES[i]);
        }
    }

    public String getIdleRoute() {
        return BeamTracker.IDLE_MODES[tracker.getIdleMode()];
    }

    public void setLedMode(String value) {
        int i = index(LedRing.MODES, value);
        if (i >= 0) {
            tracker.setLedMode(i);
            notifyListeners(ENTITY_LED_MODE, LedRing.MODES[i]);
        }
    }

    public String getLedMode() {
        return LedRing.MODES[tracker.getLedMode()];
    }

    public void setLedBrightness(String value) {
        int i = index(LedRing.BRIGHTNESS_OPTIONS, value);
        if (i >= 0) {
            tracker.setLedBrightness(i);
            notifyListeners(ENTITY_LED_BRIGHTNESS, LedRing.BRIGHTNESS_OPTIONS[i]);
        }
    }

    public String getLedBrightness() {
        return LedRing.BRIGHTNESS_OPTIONS[tracker.getLedBrightness()];
    }

    public void setLedSpeed(String value) {
        int i = index(LedRing.SPEED_OPTIONS, value);
        if (i >= 0) {
            tracker.setLedSpeed(i);
            notifyListeners(ENTITY_LED_SPEED, LedRing.SPEED_OPTIONS[i]);
        }
    }

    public String getLedSpeed() {
        return LedRing.SPEED_OPTIONS[tracker.getLedSpeed()];
    }

    private static int index(String[] options, String value) {
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(value.trim())) {
                return i;
            }
        }
        Log.w(TAG, "Unknown option: " + value);
        return -1;
    }

    public String getLinkState() {
        switch (link.getState()) {
            case XvfLink.STATE_CONNECTED:
                return link.getDetail();
            case XvfLink.STATE_AWAITING_PERMISSION:
                return "USB permission needed";
            case XvfLink.STATE_NO_DEVICE:
                return "reSpeaker not found";
            default:
                return "error: " + link.getDetail();
        }
    }

    public String getLockedAzimuth() {
        float azimuth = tracker.getLockedAzimuthDeg();
        return Float.isNaN(azimuth) ? "" : String.valueOf(Math.round(azimuth));
    }

    public String getLastEvent() {
        synchronized (events) {
            return events.isEmpty() ? "" : events.peekLast();
        }
    }

    public void releaseNow() {
        tracker.onSessionEnd("released by hand", true);
    }

    // Interferer directions as number entities; -1 frees the slot.
    public void setInterferer1(String v) { setInterferer(0, v); }
    public void setInterferer2(String v) { setInterferer(1, v); }
    public void setInterferer3(String v) { setInterferer(2, v); }
    public String getInterferer1() { return interfererText(0); }
    public String getInterferer2() { return interfererText(1); }
    public String getInterferer3() { return interfererText(2); }

    private void setInterferer(int index, String value) {
        try {
            float deg = Float.parseFloat(value.trim());
            tracker.setInterferer(index, deg);
            notifyListeners(ENTITY_INTERFERER[index], interfererText(index));
            Log.i(TAG, "Interferer " + (index + 1) + " = " + value);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Unusable interferer direction: " + value);
        }
    }

    private String interfererText(int index) {
        float deg = tracker.getInterferer(index);
        return Float.isNaN(deg) ? "-1"
            : String.format(Locale.US, "%.0f", deg);
    }

    // ---- Settings from Ava's UI -------------------------------------------------

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        try {
            if ("enable_zone_learning".equals(key)) {
                tracker.setZoneLearningEnabled(parseBoolean(value, true));
            } else if ("enable_fast_lock".equals(key)) {
                tracker.setFastLockEnabled(parseBoolean(value, true));
            } else if ("enable_lookback".equals(key)) {
                tracker.setLookbackEnabled(parseBoolean(value, true));
            } else if ("release_after_stt".equals(key)) {
                tracker.setReleaseAfterStt(parseBoolean(value, true));
            } else if ("correct_on_speech".equals(key)) {
                tracker.setCorrectOnSpeech(parseBoolean(value, false));
            } else if ("enable_tracking".equals(key)) {
                tracker.setTrackingEnabled(parseBoolean(value, true));
            } else if ("enable_gating".equals(key)) {
                tracker.setGatingEnabled(parseBoolean(value, false));
            } else if ("enable_channel_switch".equals(key)) {
                tracker.setChannelSwitchEnabled(parseBoolean(value, true));
            } else if ("track_window_deg".equals(key)) {
                tracker.setTrackWindowDeg(Float.parseFloat(value));
            } else if ("energy_threshold".equals(key)) {
                tracker.setEnergyThreshold(Float.parseFloat(value));
            } else if ("max_lock_seconds".equals(key)) {
                tracker.setMaxLockSeconds(Integer.parseInt(value));
            } else if ("search_seconds".equals(key)) {
                tracker.setSearchSeconds(Integer.parseInt(value));
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Unusable value for " + key + ": " + value);
        }
    }

    // ---- Reporting back to Home Assistant ---------------------------------------

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<Object>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        if (ENTITY_BEAM_LOCK.equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(beamLockEnabled));
        } else if (ENTITY_LINK_STATE.equals(entityId)) {
            notifySingleListener(callback, getLinkState());
        } else if (ENTITY_AZIMUTH.equals(entityId)) {
            notifySingleListener(callback, getLockedAzimuth());
        } else if (ENTITY_LAST_EVENT.equals(entityId)) {
            notifySingleListener(callback, getLastEvent());
        } else if (ENTITY_LEARNED_ZONES.equals(entityId)) {
            notifySingleListener(callback, getLearnedZones());
        } else if (ENTITY_SPEAKER_MAP.equals(entityId)) {
            notifySingleListener(callback, getSpeakerMap());
        } else if (ENTITY_IDLE_ROUTE.equals(entityId)) {
            notifySingleListener(callback, getIdleRoute());
        } else if (ENTITY_LED_MODE.equals(entityId)) {
            notifySingleListener(callback, getLedMode());
        } else if (ENTITY_LED_BRIGHTNESS.equals(entityId)) {
            notifySingleListener(callback, getLedBrightness());
        } else if (ENTITY_LED_SPEED.equals(entityId)) {
            notifySingleListener(callback, getLedSpeed());
        } else {
            for (int i = 0; i < ENTITY_INTERFERER.length; i++) {
                if (ENTITY_INTERFERER[i].equals(entityId)) {
                    notifySingleListener(callback, interfererText(i));
                }
            }
        }
        return true;
    }

    private void notifyListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            return;
        }
        for (Object listener : listeners) {
            notifySingleListener(listener, value);
        }
    }

    private static void notifySingleListener(Object listener, Object value) {
        try {
            Method method = listener.getClass().getMethod("onStateChanged", Object.class);
            method.invoke(listener, value);
        } catch (Exception e) {
            Log.w(TAG, "State could not be reported", e);
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return false;
        }
        return fallback;
    }
}
