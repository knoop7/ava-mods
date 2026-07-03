package com.ava.mods.dlna;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.jupnp.support.model.TransportState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Ava mod manager for the DLNA Media Renderer.
 *
 * Makes the device discoverable as a standard UPnP/DLNA MediaRenderer:1 so any
 * DLNA controller (BubbleUPnP, foobar2000, NAS apps, Windows "Cast to Device")
 * can push audio directly to Ava - no Music Assistant required.
 *
 * Playback is preemptive: a new SetAVTransportURI replaces the current stream,
 * the same model the Sendspin protocol uses. Coexistence with Ava's own audio
 * uses Android audio focus plus voice-pipeline ducking events.
 */
public class DlnaRendererManager {
    private static final String TAG = "DlnaRendererManager";
    private static volatile DlnaRendererManager instance;

    private static final String PREFS_NAME = "dlna_renderer_prefs";
    private static final String KEY_SHUFFLE = "shuffle_enabled";
    private static final String KEY_REPEAT_MODE = "repeat_mode";

    /** Bounds the in-memory play history used for Previous/Next/shuffle/repeat. */
    private static final int MAX_HISTORY = 50;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DlnaRenderer");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, error) -> {
            Log.e(TAG, "DlnaRenderer worker died; scheduling recovery", error);
            mainHandler.post(() -> {
                if (!destroyed) {
                    scheduleSyncRetry("worker uncaught: " + error.getClass().getSimpleName());
                }
            });
        });
        return t;
    });
    private final PlaybackEngine playbackEngine;
    private final DlnaUpnpEngine upnpEngine;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Object>> stateListeners =
            new ConcurrentHashMap<>();

    // Config (applyConfig keys mirror manifest.json)
    private volatile String deviceName = "Ava";
    private volatile boolean autoStart = false;
    private volatile boolean allowVolumeControl = true;
    private volatile boolean voiceDucking = true;
    private volatile boolean showCinemaOverlay = false;
    private volatile boolean keepControlsVisible = true;
    private volatile boolean dualOutputEnabled = false;
    private volatile boolean autoStartConfigured = false;
    /** Set by voice_pipeline events; suppresses z-order reassert during wake/TTS. */
    private volatile boolean voiceSessionActive = false;
    private volatile String runningFingerprint = "";
    private volatile int syncRetryCount = 0;
    private volatile boolean syncRetryScheduled = false;
    private volatile boolean networkReceiverRegistered = false;
    private volatile String lastNetworkFingerprint = "";
    private volatile boolean healthWatchdogScheduled = false;
    private static final int FAST_SYNC_RETRY_LIMIT = 20;
    private static final long FAST_SYNC_RETRY_MS = 1500L;
    private static final long SLOW_SYNC_RETRY_MS = 15000L;
    private static final long HEALTH_WATCHDOG_MS = 45000L;

    private volatile String nowPlaying = "";
    private final CinemaOverlay cinemaOverlay;

    // Local play history so on-screen Previous/Next/shuffle/repeat can do
    // something real: DLNA MediaRenderer:1 has no notion of a queue owned by
    // the renderer (the controller pushes one URI at a time via
    // SetAVTransportURI), so we keep our own trail of what's been played and
    // navigate it browser-history style.
    private final Object historyLock = new Object();
    private final List<HistoryEntry> history = new ArrayList<>();
    private int historyCursor = -1;
    private volatile boolean shuffleEnabled;
    private volatile String repeatMode = "off";
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleNetworkChanged(intent != null ? intent.getAction() : "unknown");
        }
    };

    private DlnaRendererManager(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences prefs = prefs();
        this.shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false);
        this.repeatMode = prefs.getString(KEY_REPEAT_MODE, "off");
        this.cinemaOverlay = new CinemaOverlay(this.context, new CinemaOverlay.Callback() {
            @Override
            public void onPlayPause() {
                playbackEngine.togglePlayPause();
            }

            @Override
            public void onStop() {
                playbackEngine.stop();
            }

            @Override
            public void onSeek(long positionMs) {
                playbackEngine.seekTo(positionMs);
            }

            @Override
            public void onShuffleChanged(boolean enabled) {
                shuffleEnabled = enabled;
                prefs().edit().putBoolean(KEY_SHUFFLE, enabled).apply();
                Log.d(TAG, "Shuffle: " + enabled);
            }

            @Override
            public void onRepeatModeChanged(String mode) {
                repeatMode = mode;
                prefs().edit().putString(KEY_REPEAT_MODE, mode).apply();
                Log.d(TAG, "Repeat: " + mode);
            }

            @Override
            public void onSkipPrevious() {
                skipToPrevious();
            }

            @Override
            public void onSkipNext() {
                skipToNext();
            }
        });
        this.cinemaOverlay.setInitialShuffle(shuffleEnabled);
        this.cinemaOverlay.setInitialRepeatMode(repeatMode);
        this.cinemaOverlay.setKeepControlsVisible(keepControlsVisible);
        this.playbackEngine = new PlaybackEngine(this.context, cinemaOverlay, new PlaybackEngine.Listener() {
            @Override
            public void onStateChanged(TransportState state) {
                upnpEngine.notifyTransportState(state);
                notifyStateListeners("playback_state", stateDisplay(state));
                if (state == TransportState.STOPPED || state == TransportState.NO_MEDIA_PRESENT) {
                    // Keep last track title visible while paused; clear when stopped.
                    if (state == TransportState.NO_MEDIA_PRESENT) {
                        nowPlaying = "";
                        notifyStateListeners("now_playing", nowPlaying);
                    }
                }
            }

            @Override
            public void onTrackCompleted() {
                if ("one".equals(repeatMode)) {
                    HistoryEntry current = currentHistoryEntry();
                    if (current != null) {
                        playHistoryEntry(current);
                        return;
                    }
                }
                AvaAVTransportService av = upnpEngine.getAvTransport();
                if (av != null && av.advanceToNextIfAvailable()) {
                    return;
                }
                if (shuffleEnabled && playRandomFromHistory()) {
                    return;
                }
                if ("all".equals(repeatMode) && loopHistoryFromStart()) {
                    return;
                }
                nowPlaying = "";
                notifyStateListeners("now_playing", nowPlaying);
                playbackEngine.scheduleOverlayHideAfterQueueEnd();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Playback error: " + message);
            }
        });
        this.playbackEngine.setDualOutputEnabled(dualOutputEnabled);
        this.upnpEngine = new DlnaUpnpEngine(this.context, playbackEngine, this);
        registerNetworkReceiver();
        lastNetworkFingerprint = currentNetworkFingerprint();
        scheduleHealthWatchdog();
    }

    public static DlnaRendererManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DlnaRendererManager.class) {
                if (instance == null) {
                    instance = new DlnaRendererManager(context);
                }
            }
        }
        return instance;
    }

    public void onDestroy() {
        destroyed = true;
        runningFingerprint = "";
        mainHandler.removeCallbacksAndMessages(null);
        unregisterNetworkReceiver();
        executor.shutdown();
        synchronized (DlnaUpnpEngine.LIFECYCLE_LOCK) {
            playbackEngine.shutdown();
            upnpEngine.stop();
            notifyStateListeners("server_running", Boolean.FALSE);
        }
        try {
            if (!executor.awaitTermination(8, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        synchronized (DlnaRendererManager.class) {
            instance = null;
        }
        Log.i(TAG, "Manager destroyed");
    }

    /**
     * ModDeviceSupport hook — VoiceSatelliteService deferred startup (~1.8s).
     * Does not grant overlay permission (device is already authorized); only
     * nudges DLNA to sync once the voice satellite is up.
     */
    public boolean grantOverlayPermissionIfNeeded(Context ignored) {
        requestSyncServer();
        return false;
    }

    public boolean isSupported() {
        return true;
    }

    public boolean isSupported(Context ignored) {
        return true;
    }

    // ------------------------------------------------------------------
    // Config (called by Ava core via reflection)
    // ------------------------------------------------------------------

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        switch (key) {
            case "device_name": {
                String name = value.trim();
                if (name.isEmpty()) {
                    name = "Ava";
                }
                deviceName = name;
                requestSyncServer();
                break;
            }
            case "auto_start": {
                boolean parsed = "true".equalsIgnoreCase(value);
                autoStartConfigured = true;
                autoStart = parsed;
                requestSyncServer();
                break;
            }
            case "allow_volume_control": {
                allowVolumeControl = "true".equalsIgnoreCase(value);
                requestSyncServer();
                break;
            }
            case "voice_ducking":
                voiceDucking = "true".equalsIgnoreCase(value);
                break;
            case "show_cinema_overlay": {
                boolean parsed = "true".equalsIgnoreCase(value);
                if (parsed != showCinemaOverlay) {
                    showCinemaOverlay = parsed;
                    playbackEngine.setShowOverlay(parsed);
                }
                break;
            }
            case "keep_controls_visible": {
                keepControlsVisible = "true".equalsIgnoreCase(value);
                cinemaOverlay.setKeepControlsVisible(keepControlsVisible);
                break;
            }
            case "dual_output_earpiece_speaker": {
                dualOutputEnabled = "true".equalsIgnoreCase(value);
                playbackEngine.setDualOutputEnabled(dualOutputEnabled);
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Voice pipeline hook (manifest: "voice_pipeline": true)
    // ------------------------------------------------------------------

    /**
     * Opt-in overlay hook (manifest {@code overlay_below_voice}: true).
     * Reasserts Cinema above the dashboard but below wake ripple / voice UI.
     * Skipped while a voice session is active so the wake animation stays visible.
     */
    public void bringOverlayToFrontIfActive(Context ignored) {
        if (!showCinemaOverlay || voiceSessionActive) {
            return;
        }
        try {
            cinemaOverlay.bringToFrontIfActive(ignored);
        } catch (Throwable t) {
            Log.w(TAG, "bringOverlayToFrontIfActive failed", t);
        }
    }

    public void onVoicePipelineEvent(Context context, String event, Bundle extras) {
        if (event == null) {
            return;
        }
        switch (event) {
            case "wake_detected":
            case "run_start":
            case "listening_started":
            case "stt_vad_start":
            case "processing_started":
            case "tts_start":
            case "tts_playback_started":
                voiceSessionActive = true;
                if (voiceDucking) {
                    playbackEngine.duck();
                }
                break;
            case "tts_finished":
                if (voiceDucking) {
                    playbackEngine.unDuck();
                }
                break;
            case "session_ended":
            case "run_end":
            case "pipeline_error":
                voiceSessionActive = false;
                if (voiceDucking) {
                    playbackEngine.unDuck();
                }
                playbackEngine.resumeAfterVoiceSession();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Software AEC playback-reference hooks (manifest: "playback_reference": true)
    // ------------------------------------------------------------------

    /**
     * True while DLNA audio is actively playing — gates Ava's session-scoped
     * [AudioPlaybackCapture] so reference is only fed when this mod is outputting.
     */
    public boolean isPlaybackReferenceActive(Context context) {
        return playbackEngine.getState() == TransportState.PLAYING;
    }

    /**
     * MediaPlayer session id for playback capture; 0 when idle so Ava does not
     * start capture and avoids leaking capture threads when nothing is playing.
     */
    public int getPlaybackAudioSessionId(Context context) {
        if (!isPlaybackReferenceActive(context)) {
            return 0;
        }
        return playbackEngine.getAudioSessionId();
    }

    // ------------------------------------------------------------------
    // Entity reads / actions (manifest entities)
    // ------------------------------------------------------------------

    public boolean isServerRunning() {
        return upnpEngine.isHealthy();
    }

    public String getNowPlaying() {
        return nowPlaying;
    }

    public String getPlaybackState() {
        return stateDisplay(playbackEngine.getState());
    }

    public boolean restartServer() {
        runningFingerprint = "";
        requestSyncServer();
        return true;
    }

    public boolean stopPlayback() {
        playbackEngine.stop();
        return true;
    }

    // ------------------------------------------------------------------
    // Internal wiring
    // ------------------------------------------------------------------

    /** Called by AvaAVTransportService when a controller pushes a new track. */
    void onTrackChanged(DidlMetadata didl, String uri) {
        recordHistory(didl, uri);
        publishTrack(didl, uri);
    }

    private void publishTrack(DidlMetadata didl, String uri) {
        String display = didl.displayText();
        nowPlaying = display.isEmpty() ? uri : display;
        notifyStateListeners("now_playing", nowPlaying);
        if (showCinemaOverlay && cinemaOverlay.isVisible()) {
            cinemaOverlay.updateMetadata(didl, didl.mediaKind(uri));
        }
    }

    // ------------------------------------------------------------------
    // Play history (Previous/Next/shuffle/repeat) — see field comment above.
    // ------------------------------------------------------------------

    private static final class HistoryEntry {
        final String uri;
        final DidlMetadata didl;

        HistoryEntry(String uri, DidlMetadata didl) {
            this.uri = uri;
            this.didl = didl;
        }
    }

    /** Controller-driven track change: appends to history, truncating any stale "redo" tail. */
    private void recordHistory(DidlMetadata didl, String uri) {
        synchronized (historyLock) {
            if (historyCursor >= 0 && historyCursor < history.size() - 1) {
                history.subList(historyCursor + 1, history.size()).clear();
            }
            history.add(new HistoryEntry(uri, didl));
            historyCursor = history.size() - 1;
            if (history.size() > MAX_HISTORY) {
                history.remove(0);
                historyCursor--;
            }
        }
    }

    private HistoryEntry currentHistoryEntry() {
        synchronized (historyLock) {
            if (historyCursor >= 0 && historyCursor < history.size()) {
                return history.get(historyCursor);
            }
            return null;
        }
    }

    /** Steps back to the previous track we've already played, if any. */
    void skipToPrevious() {
        HistoryEntry entry;
        synchronized (historyLock) {
            if (historyCursor <= 0) {
                Log.d(TAG, "Skip previous: no earlier track in history");
                return;
            }
            historyCursor--;
            entry = history.get(historyCursor);
        }
        playHistoryEntry(entry);
    }

    /**
     * Advances forward. Prefers our own forward history (so Previous, then
     * Next, returns to where you were); otherwise honors a controller-supplied
     * SetNextAVTransportURI; otherwise falls back to shuffle/repeat if enabled.
     */
    void skipToNext() {
        HistoryEntry entry;
        synchronized (historyLock) {
            if (historyCursor >= 0 && historyCursor < history.size() - 1) {
                historyCursor++;
                entry = history.get(historyCursor);
            } else {
                entry = null;
            }
        }
        if (entry != null) {
            playHistoryEntry(entry);
            return;
        }
        AvaAVTransportService av = upnpEngine.getAvTransport();
        if (av != null && av.advanceToNextIfAvailable()) {
            return;
        }
        if (shuffleEnabled && playRandomFromHistory()) {
            return;
        }
        if ("all".equals(repeatMode) && loopHistoryFromStart()) {
            return;
        }
        Log.d(TAG, "Skip next: no queued next track");
    }

    private boolean playRandomFromHistory() {
        HistoryEntry entry;
        synchronized (historyLock) {
            if (history.isEmpty()) {
                return false;
            }
            int idx = history.size() == 1 ? 0 : ThreadLocalRandom.current().nextInt(history.size());
            historyCursor = idx;
            entry = history.get(idx);
        }
        playHistoryEntry(entry);
        return true;
    }

    private boolean loopHistoryFromStart() {
        HistoryEntry entry;
        synchronized (historyLock) {
            if (history.isEmpty()) {
                return false;
            }
            historyCursor = 0;
            entry = history.get(0);
        }
        playHistoryEntry(entry);
        return true;
    }

    /**
     * Replays a track from our own history without disturbing the
     * history/cursor. Always auto-plays: the user (or repeat/shuffle logic)
     * explicitly asked to jump to this track.
     */
    private void playHistoryEntry(HistoryEntry entry) {
        playbackEngine.setUri(entry.uri, entry.didl, true);
        publishTrack(entry.didl, entry.uri);
    }

    private void requestSyncServer() {
        if (destroyed) {
            return;
        }
        try {
            executor.execute(this::safeSyncServerLifecycle);
        } catch (RejectedExecutionException ignored) {
            // Manager is shutting down — fall back to main-thread retry.
            if (!destroyed) {
                scheduleSyncRetry("executor rejected");
            }
        }
    }

    private void safeSyncServerLifecycle() {
        try {
            syncServerLifecycle();
        } catch (Throwable t) {
            Log.e(TAG, "syncServerLifecycle failed", t);
            scheduleSyncRetry("lifecycle error");
        }
    }

    /**
     * Start/stop UPnP only when Ava's voice satellite is active and auto_start
     * is enabled. Prevents duplicate SSDP registration and "runs on its own"
     * while the central service is down or the mod is reloading.
     */
    private void syncServerLifecycle() {
        synchronized (DlnaUpnpEngine.LIFECYCLE_LOCK) {
            syncRetryScheduled = false;
            if (destroyed) {
                stopServerLocked();
                return;
            }
            if (!autoStartConfigured || !autoStart) {
                syncRetryCount = 0;
                stopServerLocked();
                return;
            }
            if (!isNetworkReady()) {
                stopServerLocked();
                scheduleSyncRetry("network unavailable");
                return;
            }
            if (!isVoiceSatelliteActive()) {
                if (!upnpEngine.isRunning()) {
                    scheduleSyncRetry("voice satellite inactive");
                    return;
                }
                Log.d(TAG, "Voice satellite inactive but DMR still running; keeping SSDP alive");
            }
            syncRetryCount = 0;

            String fingerprint = deviceName + "|" + allowVolumeControl;
            if (upnpEngine.isHealthy() && fingerprint.equals(runningFingerprint)) {
                return;
            }

            if (upnpEngine.isRunning()) {
                stopServerLocked();
            }

            upnpEngine.start(deviceName, allowVolumeControl);
            runningFingerprint = fingerprint;
            notifyStateListeners("server_running", upnpEngine.isHealthy());
            pushInitialVolume();
            if (!upnpEngine.isHealthy()) {
                scheduleSyncRetry("upnp start failed");
            } else {
                scheduleHealthWatchdog();
            }
        }
    }

    /**
     * DLNA discovery should self-heal forever, not only for a 30s boot window.
     * Use a short retry burst during startup, then back off to a low-frequency
     * maintenance loop until the stack can come online again.
     */
    private void scheduleSyncRetry(String reason) {
        if (destroyed || !autoStartConfigured || !autoStart) {
            return;
        }
        if (syncRetryScheduled) {
            return;
        }
        syncRetryScheduled = true;
        syncRetryCount++;
        final long delayMs = syncRetryCount <= FAST_SYNC_RETRY_LIMIT
                ? FAST_SYNC_RETRY_MS
                : SLOW_SYNC_RETRY_MS;
        if (syncRetryCount == FAST_SYNC_RETRY_LIMIT + 1) {
            Log.w(TAG, "DLNA retrying in low-frequency self-heal mode: " + reason);
        } else if (syncRetryCount == 1 || syncRetryCount % 5 == 0) {
            Log.d(TAG, "DLNA sync retry in " + delayMs + "ms: " + reason);
        }
        mainHandler.postDelayed(() -> {
            syncRetryScheduled = false;
            if (!destroyed) {
                requestSyncServer();
            }
        }, delayMs);
    }

    /**
     * Low-frequency watchdog: if auto_start is on but the UPnP stack died quietly,
     * kick syncServerLifecycle without blocking the worker thread on sleep().
     */
    private void scheduleHealthWatchdog() {
        if (destroyed || healthWatchdogScheduled) {
            return;
        }
        healthWatchdogScheduled = true;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                healthWatchdogScheduled = false;
                if (destroyed || !autoStartConfigured || !autoStart) {
                    return;
                }
                if (!isNetworkReady()) {
                    scheduleHealthWatchdog();
                    return;
                }
                if (!upnpEngine.isHealthy()) {
                    Log.w(TAG, "DLNA health watchdog: stack unhealthy, resyncing");
                    runningFingerprint = "";
                    requestSyncServer();
                }
                scheduleHealthWatchdog();
            }
        }, HEALTH_WATCHDOG_MS);
    }

    private void stopServerLocked() {
        if (!upnpEngine.isRunning() && runningFingerprint.isEmpty()) {
            return;
        }
        playbackEngine.stop();
        upnpEngine.stop();
        runningFingerprint = "";
        notifyStateListeners("server_running", Boolean.FALSE);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void pushInitialVolume() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max > 0) {
                upnpEngine.notifyVolume(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max);
            }
        }
    }

    private void registerNetworkReceiver() {
        if (networkReceiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
            context.registerReceiver(networkReceiver, filter);
            networkReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to register network receiver", e);
        }
    }

    private void unregisterNetworkReceiver() {
        if (!networkReceiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(networkReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister network receiver", e);
        } finally {
            networkReceiverRegistered = false;
        }
    }

    private void handleNetworkChanged(String reason) {
        String fingerprint = currentNetworkFingerprint();
        boolean changed = !fingerprint.equals(lastNetworkFingerprint);
        if (changed) {
            Log.i(TAG, "Network changed (" + reason + "): " + lastNetworkFingerprint + " -> " + fingerprint);
            lastNetworkFingerprint = fingerprint;
            runningFingerprint = "";
        }
        if (changed || !upnpEngine.isHealthy()) {
            requestSyncServer();
        }
    }

    private boolean isNetworkReady() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) {
                    return false;
                }
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            Log.w(TAG, "Active network check failed", e);
            return true;
        }
    }

    private String currentNetworkFingerprint() {
        StringBuilder sb = new StringBuilder();
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null) {
                    sb.append(info.getTypeName()).append('|')
                            .append(info.getSubtypeName()).append('|')
                            .append(info.isConnected()).append('|');
                } else {
                    sb.append("no-active-network|");
                }
            } catch (Exception e) {
                sb.append("networkinfo-error|");
            }
        }
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            try {
                WifiInfo wifiInfo = wm.getConnectionInfo();
                if (wifiInfo != null) {
                    sb.append(wifiInfo.getSSID()).append('|')
                            .append(wifiInfo.getBSSID()).append('|')
                            .append(wifiInfo.getNetworkId()).append('|')
                            .append(wifiInfo.getIpAddress());
                } else {
                    sb.append("no-wifi-info");
                }
            } catch (Exception e) {
                sb.append("wifiinfo-error");
            }
        }
        return sb.toString();
    }

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

    private static String stateDisplay(TransportState state) {
        switch (state) {
            case PLAYING:
                return "playing";
            case PAUSED_PLAYBACK:
                return "paused";
            case TRANSITIONING:
                return "loading";
            case STOPPED:
                return "stopped";
            default:
                return "idle";
        }
    }

    // ------------------------------------------------------------------
    // State listeners (Ava entity push updates)
    // ------------------------------------------------------------------

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        pushCurrentState(entityId, callback);
        return true;
    }

    private void pushCurrentState(String entityId, Object callback) {
        if ("server_running".equals(entityId)) {
            notifySingleListener(callback, upnpEngine.isHealthy());
        } else if ("now_playing".equals(entityId)) {
            notifySingleListener(callback, nowPlaying);
        } else if ("playback_state".equals(entityId)) {
            notifySingleListener(callback, getPlaybackState());
        }
    }

    private void notifyStateListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            return;
        }
        for (Object callback : listeners) {
            notifySingleListener(callback, value);
        }
    }

    private void notifySingleListener(Object callback, Object value) {
        // Ava core uses two callback shapes: ModStateCallback.onStateChanged(Object)
        // (entity factory) and onState(Object) (bridge/status panel).
        try {
            Method method;
            try {
                method = callback.getClass().getMethod("onStateChanged", Object.class);
            } catch (NoSuchMethodException e) {
                method = callback.getClass().getMethod("onState", Object.class);
            }
            method.invoke(callback, value);
        } catch (Exception e) {
            Log.w(TAG, "State callback failed", e);
        }
    }
}
