package com.ava.mods.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.ava.mods.airplay.audio.TrackInfo;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class AirPlayReceiverManager implements AirPlayEngine.Listener {

    private static final String TAG = "AirPlayReceiverManager";

    private static volatile AirPlayReceiverManager instance;

    public static AirPlayReceiverManager getInstance(Context context) {
        AirPlayReceiverManager m = instance;
        if (m != null) return m;
        synchronized (AirPlayReceiverManager.class) {
            if (instance == null) {
                instance = new AirPlayReceiverManager(context.getApplicationContext());
            }
            return instance;
        }
    }

    public static String formatAirPlayName(String deviceName) {
        String name = sanitizeDeviceName(deviceName);
        if (name.isEmpty()) {
            String model = android.os.Build.MODEL;
            name = (model == null || model.trim().isEmpty()) ? "Device" : model.trim();
        }
        return "Ava - " + name + " [Airplay]";
    }

    /**
     * device_name is the middle segment only. Strip accidental full-name pastes
     * and the bare default "Ava" so we never advertise {@code Ava - Ava [Airplay]}.
     */
    static String sanitizeDeviceName(String raw) {
        if (raw == null) return "";
        String name = raw.trim();
        if (name.regionMatches(true, 0, "Ava - ", 0, 6)) {
            name = name.substring(6).trim();
        }
        if (name.matches("(?i).*\\[\\s*airplay\\s*]\\s*$")) {
            name = name.replaceAll("(?i)\\s*\\[\\s*airplay\\s*]\\s*$", "").trim();
        }
        if (name.equalsIgnoreCase("Ava")) return "";
        return name;
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Object>> stateListeners = new ConcurrentHashMap<>();

    private volatile AirPlayEngine engine;
    private final AirPlayOverlay overlay;

    private volatile boolean destroyed = false;
    private volatile String deviceName = "";
    /** False until Ava pushes {@code auto_start} via applyConfig — mirrors DLNA. */
    private volatile boolean autoStartConfigured = false;
    private volatile boolean autoStart = false;
    private volatile boolean requirePin = false;
    private volatile boolean h265Enabled = true;
    private volatile boolean advertiseVideo = true;
    private volatile boolean advertiseAudio = true;
    private volatile boolean showOverlay = true;
    private volatile boolean voiceDucking = true;
    private volatile boolean showDiagnosticEntities = false;
    private volatile boolean voiceSessionActive = false;
    private volatile boolean syncRetryScheduled = false;
    private volatile int syncRetryCount = 0;
    private volatile String lastPin = "";
    private volatile String lastStatus = "idle";

    private static final long FAST_SYNC_RETRY_MS = 1500L;
    private static final long SLOW_SYNC_RETRY_MS = 15_000L;
    private static final int FAST_SYNC_RETRY_LIMIT = 12;

    private final Object syncPrefsLock = new Object();

    private AirPlayReceiverManager(Context context) {
        this.appContext = context;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AvaAirPlay");
                t.setDaemon(true);
                return t;
            }
        });
        this.overlay = new AirPlayOverlay(appContext, new AirPlayOverlay.Callback() {
            @Override
            public void onClose() {
                AirPlayEngine e = engine;
                if (e != null) {
                    if (e.isVideoPlaybackActive()) e.stopVideoPlayback();
                    if (!e.isMirroringActive()) overlay.hide();
                } else {
                    overlay.hide();
                }
            }

            @Override
            public void onPlayPause() {
                AirPlayEngine e = engine;
                if (e == null) return;
                if (e.isVideoPlaybackActive()) {
                    e.setVideoPlaying(!e.getVideoPlaybackInfo().playing);
                } else if (e.isAudioOnly()) {
                    e.togglePlayPause();
                }
            }

            @Override
            public void onStop() {
                // Same contract as DLNA CinemaOverlay back pill → onStop:
                // stop local video playback (if any) and dismiss the overlay.
                AirPlayEngine e = engine;
                if (e != null) e.stopVideoPlayback();
                overlay.hide();
            }

            @Override
            public void onSeek(long positionMs) {
                AirPlayEngine e = engine;
                if (e == null) return;
                if (e.isVideoPlaybackActive()) {
                    e.seekVideoTo(positionMs);
                } else if (e.isAudioOnly()) {
                    e.seekAudioTo(positionMs);
                }
            }

            @Override
            public void onSeekStart() {
                AirPlayEngine e = engine;
                if (e == null) return;
                if (e.isVideoPlaybackActive()) e.setVideoScrubbing(true);
                else if (e.isAudioOnly()) e.setAudioScrubbing(true);
            }

            @Override
            public void onSeekEnd() {
                AirPlayEngine e = engine;
                if (e == null) return;
                if (e.isVideoPlaybackActive()) e.setVideoScrubbing(false);
                else if (e.isAudioOnly()) e.setAudioScrubbing(false);
            }

            @Override
            public void onPrevious() {
                AirPlayEngine e = engine;
                if (e != null) e.skipPrevious();
            }

            @Override
            public void onNext() {
                AirPlayEngine e = engine;
                if (e != null) e.skipNext();
            }

            @Override
            public void onAudioScanBegin(boolean forward) {
                AirPlayEngine e = engine;
                if (e != null) e.audioScanBegin(forward);
            }

            @Override
            public void onAudioScanEnd() {
                AirPlayEngine e = engine;
                if (e != null) e.audioScanEnd();
            }

            @Override
            public void onVolumeDown() {
                AirPlayEngine e = engine;
                if (e != null && e.getDacpController() != null) e.getDacpController().volumeDown();
            }

            @Override
            public void onMuteToggle() {
                AirPlayEngine e = engine;
                if (e != null && e.getDacpController() != null) e.getDacpController().muteToggle();
            }

            @Override
            public void onVolumeUp() {
                AirPlayEngine e = engine;
                if (e != null && e.getDacpController() != null) e.getDacpController().volumeUp();
            }

            @Override
            public void onSpeedClick() {
                // Overlay opens PlaybackSpeedSelector; no cycle here.
            }

            @Override
            public void onSetVideoSpeed(float speed) {
                AirPlayEngine e = engine;
                if (e != null && e.isVideoPlaybackActive()) e.setVideoSpeed(speed);
            }

            @Override
            public void onSetSkipSilence(boolean enabled) {
                AirPlayEngine e = engine;
                if (e != null && e.isVideoPlaybackActive()) e.setVideoSkipSilence(enabled);
            }

            @Override
            public void onCopyUrl() {
                AirPlayEngine e = engine;
                if (e == null) return;
                String loc = e.getVideoLocation();
                if (loc == null || loc.isEmpty()) return;
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AirPlay URL", loc));
                        overlay.showCopyToast(loc);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "copy url failed", t);
                }
            }

            @Override
            public void onDownloadClick() {
                AirPlayEngine e = engine;
                if (e != null) e.toggleVideoDownload();
            }

            @Override
            public void onRotateClick() {
                try {
                    android.app.Activity act = findActivity();
                    if (act == null) return;
                    // Session-only toggle — next video session resets to landscape.
                    boolean landscape = act.getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                    act.setRequestedOrientation(landscape
                            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                } catch (Throwable t) {
                    Log.w(TAG, "rotate failed", t);
                }
            }

            @Override
            public void onApplyDefaultVideoOrientation() {
                applyDefaultVideoOrientation();
            }

            @Override
            public void onReleaseVideoOrientation() {
                releaseVideoOrientationLock();
            }

            @Override
            public void onLockClick() {
                // Overlay owns lock UI state.
            }

            @Override
            public void onContentScaleClick() {
                // Overlay applies VideoContentScale to the SurfaceView.
            }

            @Override
            public void onPipClick() {
                AirPlayOverlay o = overlay;
                if (o != null) o.toggleVideoFloating();
            }

            @Override
            public void onSurfaceReady(Surface surface, boolean forVideoPlayback) {
                AirPlayEngine e = engine;
                if (e == null) return;
                if (forVideoPlayback) e.setVideoPlaybackSurface(surface);
                else e.setVideoSurface(surface);
            }

            @Override
            public void onSurfaceDestroyed(Surface surface, boolean forVideoPlayback) {
                AirPlayEngine e = engine;
                if (e == null) return;
                if (forVideoPlayback) e.clearVideoPlaybackSurface(surface);
                else e.clearVideoSurface(surface);
            }
        });

        // Download progress → overlay spinner
        // (bound after engine created below)
        AirPlayEngine e = new AirPlayEngine(appContext, this);
        engine = e;
        e.getVideoDownloader().setProgressListener(new com.ava.mods.airplay.download.VideoDownloader.ProgressListener() {
            @Override
            public void onProgress(Integer progress) {
                overlay.updateDownloadProgress(progress);
            }
        });
        // MediaSession / receivers must not be created on ModManager worker threads.
        // Do NOT start the AirPlay server here — wait for applyConfig(auto_start) and
        // the Ava voice-satellite master switch (same gate as DLNA).
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                AirPlayEngine live = engine;
                if (live == null) return;
                live.start();
            }
        });
    }

    private android.app.Activity findActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(thread);
            if (map instanceof java.util.Map) {
                for (Object record : ((java.util.Map<?, ?>) map).values()) {
                    Class<?> recCl = record.getClass();
                    java.lang.reflect.Field paused = recCl.getDeclaredField("paused");
                    paused.setAccessible(true);
                    if (paused.getBoolean(record)) continue;
                    java.lang.reflect.Field activity = recCl.getDeclaredField("activity");
                    activity.setAccessible(true);
                    Object a = activity.get(record);
                    if (a instanceof android.app.Activity) return (android.app.Activity) a;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Video defaults to landscape every session. Manual rotate is not persisted;
     * leave video releases with {@link #releaseVideoOrientationLock()}.
     */
    private void applyDefaultVideoOrientation() {
        try {
            android.app.Activity act = findActivity();
            if (act == null) return;
            act.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Throwable t) {
            Log.w(TAG, "default landscape failed", t);
        }
    }

    /**
     * Upstream video screen onDispose: SCREEN_ORIENTATION_UNSPECIFIED.
     * Releases our rotate lock without forcing a portrait/landscape flip, and is not persisted.
     */
    private void releaseVideoOrientationLock() {
        try {
            android.app.Activity act = findActivity();
            if (act == null) return;
            int cur = act.getRequestedOrientation();
            if (cur == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;
            act.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Throwable t) {
            Log.w(TAG, "release orientation failed", t);
        }
    }

    public void applyConfig(String key, String value) {
        if ("device_name".equals(key)) {
            deviceName = sanitizeDeviceName(value);
        } else if ("auto_start".equals(key)) {
            autoStartConfigured = true;
            autoStart = "true".equalsIgnoreCase(value);
        } else if ("require_pin".equals(key)) {
            requirePin = "true".equalsIgnoreCase(value);
        } else if ("h265_enabled".equals(key)) {
            h265Enabled = "true".equalsIgnoreCase(value);
        } else if ("advertise_video".equals(key)) {
            advertiseVideo = "true".equalsIgnoreCase(value);
        } else if ("advertise_audio".equals(key)) {
            advertiseAudio = "true".equalsIgnoreCase(value);
        } else if ("show_overlay".equals(key)) {
            showOverlay = "true".equalsIgnoreCase(value);
        } else if ("voice_ducking".equals(key)) {
            voiceDucking = "true".equalsIgnoreCase(value);
        } else if ("show_diagnostic_entities".equals(key)) {
            showDiagnosticEntities = "true".equalsIgnoreCase(value);
        } else {
            Log.d(TAG, "Unknown config key: " + key);
        }
        scheduleSync("config:" + key);
    }

    public void onVoicePipelineEvent(Context context, String event, Bundle extras) {
        if (!voiceDucking) return;
        if ("wake_detected".equals(event) || "listening_started".equals(event)
                || "processing_started".equals(event) || "tts_start".equals(event)
                || "tts_playback_started".equals(event)) {
            voiceSessionActive = true;
        } else if ("tts_finished".equals(event) || "session_ended".equals(event)
                || "run_end".equals(event) || "pipeline_error".equals(event)) {
            voiceSessionActive = false;
        }
    }

    public void bringOverlayToFrontIfActive(Context context) {
        if (!showOverlay || !overlay.isVisible()) return;
        // PiP must stay above dashboard/voice — never duck below Ava chrome while floating.
        if (overlay.isVideoFloating()) {
            overlay.bringToFront();
            return;
        }
        if (voiceSessionActive) return;
        overlay.bringToFront();
    }

    public boolean isServerRunning() {
        AirPlayEngine e = engine;
        return e != null && e.getServerState() == AirPlayEngine.ServerState.RUNNING;
    }

    public String getPlaybackState() {
        AirPlayEngine e = engine;
        if (e == null) return "stopped";
        if (e.isVideoPlaybackActive()) {
            return e.getVideoPlaybackInfo().playing ? "playing" : "paused";
        }
        if (e.isMirroringActive()) return "mirroring";
        if (e.isAudioOnly()) return e.isPlaying() ? "playing" : "paused";
        return isServerRunning() ? "idle" : "stopped";
    }

    public String getAdvertisedName() {
        AirPlayEngine e = engine;
        return e != null ? e.getAdvertisedName() : formatAirPlayName(deviceName);
    }

    public String getPinCode() { return lastPin; }

    public String getNowPlaying() {
        AirPlayEngine e = engine;
        if (e == null) return "";
        TrackInfo t = e.getTrackInfo();
        if (!t.title.isEmpty() && !t.artist.isEmpty()) return t.artist + " - " + t.title;
        if (!t.title.isEmpty()) return t.title;
        String vt = e.getVideoTitle();
        if (vt != null && !vt.isEmpty()) return vt;
        if (e.isMirroringActive()) return "Screen Mirroring";
        return "";
    }

    public String getStatus() { return lastStatus; }

    public void restartServer() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                stopServerInternal();
                startServerInternal();
            }
        });
    }

    public void stopPlayback() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                AirPlayEngine e = engine;
                if (e != null) e.stopVideoPlayback();
                overlay.hide();
                notifyEntity("playback_state");
                notifyEntity("now_playing");
            }
        });
    }

    public void onDestroy() {
        destroyed = true;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    stopServerInternal();
                    AirPlayEngine e = engine;
                    if (e != null) e.destroy();
                    engine = null;
                }
            });
            executor.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "onDestroy error", t);
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                overlay.hide();
            }
        });
        instance = null;
    }

    public void registerStateListener(String entityId, Object callback) {
        CopyOnWriteArrayList<Object> list = stateListeners.get(entityId);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<Object> existing = stateListeners.putIfAbsent(entityId, list);
            if (existing != null) list = existing;
        }
        list.add(callback);
    }

    @Override
    public void onShowOverlay() {
        if (!showOverlay) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                AirPlayEngine e = engine;
                if (e == null) return;
                boolean already = overlay.isVisible();
                // AirPlay cinema ≠ Sendspin vinyl FAB — claim once when becoming visible.
                if (!already) {
                    HostMediaExclusive.claim(appContext);
                }
                overlay.show(e);
                notifyEntity("playback_state");
            }
        });
    }

    /**
     * Host hook for OverlayZOrderCoordinator / Sendspin: true while AirPlay owns
     * the media surface so Ava must not raise or re-show the vinyl FAB.
     */
    public boolean isExclusiveMediaOverlayActive(Context context) {
        return showOverlay && overlay.isVisible();
    }

    /** AEC playback-reference: active while RAOP audio is playing. */
    public boolean isPlaybackReferenceActive(Context context) {
        AirPlayEngine e = engine;
        return e != null && e.isAudioOnly() && e.isPlaying();
    }

    public int getPlaybackAudioSessionId(Context context) {
        AirPlayEngine e = engine;
        if (e == null || !isPlaybackReferenceActive(context)) return 0;
        return e.getAudioSessionId();
    }

    @Override
    public void onHideOverlay() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                overlay.hide();
                notifyEntity("playback_state");
            }
        });
    }

    @Override
    public void onPin(String pin) {
        lastPin = pin == null ? "" : pin;
        lastStatus = (pin == null || pin.isEmpty()) ? "ready" : ("pin:" + pin);
        notifyEntity("pin_code");
        notifyEntity("status");
    }

    @Override
    public void onStateChanged() {
        AirPlayEngine e = engine;
        AirPlayEngine.ServerState state = e != null ? e.getServerState() : null;
        if (state == AirPlayEngine.ServerState.RUNNING) lastStatus = "running";
        else if (state == AirPlayEngine.ServerState.ERROR) lastStatus = "error";
        else lastStatus = "stopped";
        notifyEntity("server_running");
        notifyEntity("status");
        notifyEntity("advertised_name");
        notifyEntity("playback_state");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                overlay.refreshFromEngine(engine);
            }
        });
    }

    @Override
    public void onTrackChanged() {
        notifyEntity("now_playing");
        notifyEntity("playback_state");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                overlay.refreshFromEngine(engine);
            }
        });
    }

    private void scheduleSync(final String reason) {
        if (destroyed) return;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    try {
                        syncServer(reason);
                    } catch (Throwable t) {
                        Log.e(TAG, "sync failed (" + reason + ")", t);
                        lastStatus = "error";
                        notifyEntity("status");
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "scheduleSync ignored after destroy: " + reason);
        }
    }

    private void syncServer(String reason) {
        Log.i(TAG, "sync (" + reason + ") autoStartConfigured=" + autoStartConfigured
                + " autoStart=" + autoStart
                + " satellite=" + isVoiceSatelliteActive());
        writeEnginePrefs();
        if (destroyed) {
            stopServerInternal();
            return;
        }
        // Match DLNA: do nothing until Ava has pushed auto_start, and only run
        // while the voice-satellite master switch is on.
        if (!autoStartConfigured || !autoStart) {
            syncRetryCount = 0;
            stopServerInternal();
            return;
        }
        if (!isVoiceSatelliteActive()) {
            // Strict master-switch: do not advertise while satellite is off.
            stopServerInternal();
            lastStatus = "waiting_satellite";
            notifyEntity("status");
            scheduleSyncRetry("voice satellite inactive");
            return;
        }
        syncRetryCount = 0;
        startServerInternal();
    }

    private void scheduleSyncRetry(final String reason) {
        if (destroyed || !autoStartConfigured || !autoStart) return;
        if (syncRetryScheduled) return;
        syncRetryScheduled = true;
        syncRetryCount++;
        long delayMs = syncRetryCount <= FAST_SYNC_RETRY_LIMIT
                ? FAST_SYNC_RETRY_MS
                : SLOW_SYNC_RETRY_MS;
        if (syncRetryCount == 1 || syncRetryCount % 5 == 0) {
            Log.d(TAG, "AirPlay sync retry in " + delayMs + "ms: " + reason);
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncRetryScheduled = false;
                if (!destroyed) scheduleSync("retry:" + reason);
            }
        }, delayMs);
    }

    /**
     * Ava voice-satellite master switch. Same reflection hook DLNA uses so the
     * receiver does not advertise while the central service is stopped.
     */
    private boolean isVoiceSatelliteActive() {
        try {
            Class<?> serviceClass = Class.forName("com.example.ava.services.VoiceSatelliteService");
            Object companion = serviceClass.getField("Companion").get(null);
            Method isStarted = companion.getClass().getMethod("isSatelliteStarted");
            Object result = isStarted.invoke(companion);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Log.w(TAG, "VoiceSatellite state check failed", t);
            return false;
        }
    }

    private void startServerInternal() {
        AirPlayEngine e = engine;
        if (e == null) return;
        String wanted = formatAirPlayName(deviceName);
        if (e.getServerState() == AirPlayEngine.ServerState.RUNNING) {
            if (wanted.equals(e.getAdvertisedName())) return;
            stopServerInternal();
        }
        writeEnginePrefs();
        e.start();
        e.startServer(wanted);
        lastStatus = e.getServerState() == AirPlayEngine.ServerState.RUNNING ? "running" : "error";
        notifyEntity("server_running");
        notifyEntity("status");
        notifyEntity("advertised_name");
    }

    private void stopServerInternal() {
        AirPlayEngine e = engine;
        if (e != null) e.stopServer();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                overlay.hide();
            }
        });
        lastStatus = "stopped";
        notifyEntity("server_running");
        notifyEntity("status");
        notifyEntity("playback_state");
    }

    private void writeEnginePrefs() {
        synchronized (syncPrefsLock) {
            SharedPreferences.Editor ed = prefs().edit();
            ed.putString(Prefs.SERVER_NAME, formatAirPlayName(deviceName));
            ed.putBoolean(Prefs.AUTO_START, autoStart);
            ed.putBoolean(Prefs.REQUIRE_PIN, requirePin);
            ed.putBoolean(Prefs.H265_ENABLED, h265Enabled);
            ed.putBoolean(Prefs.ADVERTISE_VIDEO, advertiseVideo);
            ed.putBoolean(Prefs.ADVERTISE_AUDIO, advertiseAudio);
            ed.putBoolean(Prefs.LAUNCH_ON_CONNECT, showOverlay);
            ed.apply();
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    private void notifyEntity(String entityId) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) return;
        for (Object cb : listeners) {
            try {
                Method match = null;
                for (Method m : cb.getClass().getMethods()) {
                    if ("onStateChanged".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        match = m;
                        break;
                    }
                }
                if (match == null) continue;
                match.invoke(cb);
            } catch (Throwable t) {
                Log.w(TAG, "notify " + entityId + " failed", t);
            }
        }
    }
}
