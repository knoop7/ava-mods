package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

/**
 * Music RGB breathing coordinated with Ava playback + voice pipeline ducking.
 */
final class PhicommMusicLightController implements AvaPlaybackMonitor.Listener {
    private static final String TAG = "PhicommMusicLight";

    private final Context appContext;
    private final PhicommLightController lights;
    private final PhicommStatusBridge statusBridge;
    private final AvaPlaybackMonitor playbackMonitor;
    private final PhicommMusicLightFallback fallback;
    private final PhicommMusicRgbEngine rgbEngine;

    private volatile boolean enabled = true;
    private volatile boolean started;
    private volatile boolean musicActive;
    private volatile boolean voiceInteractionActive;
    private volatile boolean dormantActive;
    private volatile boolean visualizerRunning;

    PhicommMusicLightController(Context context) {
        appContext = context.getApplicationContext();
        lights = new PhicommLightController(appContext);
        statusBridge = new PhicommStatusBridge(appContext);
        fallback = new PhicommMusicLightFallback(lights);
        rgbEngine = new PhicommMusicRgbEngine(appContext, fallback);
        playbackMonitor = new AvaPlaybackMonitor(appContext);
        playbackMonitor.setListener(this);
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stopVisualizer();
            statusBridge.syncDeviceStatus(PhicommStatusBridge.STATUS_SPEECH);
        } else if (started && musicActive && canRunVisualizer()) {
            startVisualizer();
        }
        Log.i(TAG, "enabled=" + enabled + " backend=" + getBackendLabel());
    }

    void setDormantActive(boolean dormant) {
        dormantActive = dormant;
        if (dormant) {
            stopVisualizer();
        } else if (musicActive && canRunVisualizer()) {
            startVisualizer();
        }
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        PhicommLedLightJni.isAvailable();
        playbackMonitor.start();
        Log.i(TAG, "started backend=" + getBackendLabel());
    }

    void stop() {
        started = false;
        playbackMonitor.stop();
        stopVisualizer();
    }

    String getBackendLabel() {
        return rgbEngine.backendLabel();
    }

    boolean isMusicActive() {
        return musicActive;
    }

    /** Voice pipeline ducking — same events as DLNA mod voice ducking. */
    void onVoicePipelineEvent(String event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case "wake_detected":
            case "listening_started":
            case "run_start":
            case "stt_vad_start":
            case "tts_start":
            case "tts_playback_started":
            case "responding":
            case "processing_started":
                voiceInteractionActive = true;
                stopVisualizer();
                break;

            case "session_ended":
            case "run_end":
            case "pipeline_error":
                voiceInteractionActive = false;
                resumeIfNeeded();
                break;

            case "tts_finished":
                voiceInteractionActive = false;
                resumeIfNeeded();
                break;

            default:
                break;
        }
    }

    @Override
    public void onMusicActiveChanged(boolean active) {
        musicActive = active;
        Log.d(TAG, "Ava music active=" + active);
        if (!enabled || dormantActive) {
            if (!active) {
                stopVisualizer();
                if (!dormantActive) {
                    statusBridge.syncDeviceStatus(PhicommStatusBridge.STATUS_SPEECH);
                }
            }
            return;
        }
        if (active) {
            statusBridge.syncDeviceStatus(PhicommStatusBridge.STATUS_MUSIC);
            if (canRunVisualizer()) {
                startVisualizer();
            }
        } else {
            stopVisualizer();
            statusBridge.syncDeviceStatus(PhicommStatusBridge.STATUS_SPEECH);
        }
    }

    private void resumeIfNeeded() {
        if (musicActive && canRunVisualizer()) {
            startVisualizer();
        }
    }

    private boolean canRunVisualizer() {
        return enabled && !dormantActive && !voiceInteractionActive && musicActive;
    }

    private void startVisualizer() {
        if (visualizerRunning) {
            return;
        }
        if (rgbEngine.start()) {
            visualizerRunning = true;
        }
    }

    private void stopVisualizer() {
        if (!visualizerRunning) {
            rgbEngine.stop();
            return;
        }
        rgbEngine.stop();
        visualizerRunning = false;
    }
}
