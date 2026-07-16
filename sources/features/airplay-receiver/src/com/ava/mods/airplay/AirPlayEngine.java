package com.ava.mods.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import com.ava.mods.airplay.audio.DacpController;
import com.ava.mods.airplay.audio.DacpPlayer;
import com.ava.mods.airplay.audio.DmapParser;
import com.ava.mods.airplay.audio.TrackInfo;
import com.ava.mods.airplay.bridge.NativeBridge;
import com.ava.mods.airplay.bridge.RaopCallbackHandler;
import com.ava.mods.airplay.discovery.NsdServiceManager;
import com.ava.mods.airplay.download.VideoDownloader;
import com.ava.mods.airplay.renderer.AirPlayVideoPlayer;
import com.ava.mods.airplay.renderer.AudioRenderer;
import com.ava.mods.airplay.renderer.VideoRenderer;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;

public final class AirPlayEngine implements RaopCallbackHandler {

    private static final String TAG = "AirPlayEngine";

    public static final String ACTION_PLAY_PAUSE = "com.ava.mods.airplay.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.ava.mods.airplay.NEXT";
    public static final String ACTION_PREV = "com.ava.mods.airplay.PREV";
    public static final String ACTION_START_SERVER = "com.ava.mods.airplay.START_SERVER";
    public static final long VIDEO_SEEK_STEP_MS = 10_000L;
    public static final long VIDEO_POLL_PENDING_TIMEOUT_MS = 3_000L;

    public enum ServerState { STOPPED, RUNNING, ERROR }

    public interface Listener {
        void onShowOverlay();
        void onHideOverlay();
        void onPin(String pin);
        void onStateChanged();
        void onTrackChanged();
    }

    public interface LogListener {
        void onLog(String msg);
    }

    public interface ModeListener {
        void onMode(boolean audioOnly);
    }

    public static final class VideoPlaybackInfo {
        public final long positionMs;
        public final long durationMs;
        public final boolean playing;
        public final float speed;
        public final boolean skipSilence;
        public final boolean buffering;

        public VideoPlaybackInfo() {
            this(0L, 0L, true, 1f, false, false);
        }

        public VideoPlaybackInfo(long positionMs, long durationMs, boolean playing,
                                 float speed, boolean skipSilence, boolean buffering) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.speed = speed;
            this.skipSilence = skipSilence;
            this.buffering = buffering;
        }
    }

    public static final class VideoSize {
        public final int width;
        public final int height;
        public VideoSize(int w, int h) { this.width = w; this.height = h; }
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean started = false;
    private volatile String advertisedName = Prefs.DEF_SERVER_NAME;

    private long nativeHandle = 0L;
    private NsdServiceManager nsdManager;
    private PowerManager.WakeLock wakeLock;

    public final VideoRenderer videoRenderer = new VideoRenderer();
    public final AudioRenderer audioRenderer = new AudioRenderer();

    private AirPlayVideoPlayer airPlayVideoPlayer;
    private VideoDownloader videoDownloader;

    private volatile String videoLocation;
    private volatile ServerState serverState = ServerState.STOPPED;
    private volatile int connectionCount = 0;
    private volatile float videoAspect = 16f / 9f;
    private volatile String videoResolution = "";
    private volatile boolean audioOnly = false;
    private volatile boolean videoPlaybackActive = false;
    private volatile VideoPlaybackInfo videoPlaybackInfo = new VideoPlaybackInfo();
    private volatile long videoPlaySeq = 0L;
    private volatile float videoPlaybackAspect = 16f / 9f;
    private volatile VideoSize videoPlaybackSize;
    private volatile String videoTitle = "";
    private volatile long lastVideoPollAt = 0L;
    private volatile boolean videoPollSuppressed = false;
    private volatile boolean mirroringActive = false;
    private volatile TrackInfo trackInfo = new TrackInfo();
    private volatile long positionMs = 0L;
    private volatile long durationMs = 0L;
    private volatile boolean playing = true;
    private volatile long progressBaseMs = 0L;
    private volatile long progressBaseTime = 0L;
    /** Wall-clock of last valid onProgress packet. */
    private volatile long lastProgressAt = 0L;
    /**
     * User (or MediaSession) requested pause locally. Holds until explicit play or
     * a new advancing progress packet after local pause (sender resumed).
     */
    private volatile boolean userPaused = false;
    private volatile boolean audioScrubbing = false;
    /** Sender advanced this much past frozen pos ⇒ clear userPaused. */
    private static final long REMOTE_RESUME_SLACK_MS = 1200L;

    private volatile byte[] coverArtBytes;
    private MediaSessionCompat mediaSession;
    private BroadcastReceiver mediaReceiver;

    private DacpController dacpController;
    private DacpPlayer dacpPlayer;

    public LogListener logCallback;
    public ModeListener modeCallback;

    private volatile String lastPin;

    private float lastVideoStateRate = -1f;
    private long lastVideoStatePosMs = 0L;
    private long lastVideoStateAtMs = 0L;

    public AirPlayEngine(Context appContext, Listener listener) {
        this.appContext = appContext.getApplicationContext();
        this.listener = listener;
    }

    public String getAdvertisedName() { return advertisedName; }
    public String getVideoLocation() { return videoLocation; }
    public ServerState getServerState() { return serverState; }
    public int getConnectionCount() { return connectionCount; }
    public float getVideoAspect() { return videoAspect; }
    public String getVideoResolution() { return videoResolution; }
    public boolean isAudioOnly() { return audioOnly; }
    public boolean isVideoPlaybackActive() { return videoPlaybackActive; }
    public VideoPlaybackInfo getVideoPlaybackInfo() { return videoPlaybackInfo; }
    public long getVideoPlaySeq() { return videoPlaySeq; }
    public float getVideoPlaybackAspect() { return videoPlaybackAspect; }
    public VideoSize getVideoPlaybackSize() { return videoPlaybackSize; }
    public String getVideoTitle() { return videoTitle; }
    public boolean isMirroringActive() { return mirroringActive; }
    public TrackInfo getTrackInfo() { return trackInfo; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public boolean isPlaying() {
        return playing;
    }
    public DacpController getDacpController() { return dacpController; }
    public DacpPlayer getDacpPlayer() { return dacpPlayer; }
    public AirPlayVideoPlayer getAirPlayVideoPlayer() { return airPlayVideoPlayer(); }
    public VideoDownloader getVideoDownloader() { return videoDownloader(); }

    private AirPlayVideoPlayer airPlayVideoPlayer() {
        if (airPlayVideoPlayer == null) airPlayVideoPlayer = new AirPlayVideoPlayer(appContext);
        return airPlayVideoPlayer;
    }

    private VideoDownloader videoDownloader() {
        if (videoDownloader == null) videoDownloader = new VideoDownloader(appContext);
        return videoDownloader;
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    public boolean videoSessionPending() {
        return !videoPlaybackActive && !audioOnly && !mirroringActive && !videoPollSuppressed
                && SystemClock.elapsedRealtime() - lastVideoPollAt < VIDEO_POLL_PENDING_TIMEOUT_MS;
    }

    public long currentPositionMs() {
        return liveOrFrozenPositionMs();
    }

    private long liveOrFrozenPositionMs() {
        if (progressBaseTime == 0L || !playing || audioScrubbing) return positionMs;
        long elapsed = SystemClock.elapsedRealtime() - progressBaseTime;
        long computed = progressBaseMs + elapsed;
        if (computed < 0) computed = 0;
        if (durationMs > 0 && computed > durationMs) computed = durationMs;
        return computed;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        LogListener lc = logCallback;
        if (lc != null) lc.onLog(msg);
        if (listener != null) listener.onStateChanged();
    }

    public void start() {
        if (started) return;
        started = true;
        dacpController = new DacpController(appContext);
        dacpPlayer = new DacpPlayer(
                appContext.getMainLooper(),
                new DacpPlayer.DacpSupplier() {
                    @Override
                    public DacpController get() {
                        return dacpController;
                    }
                },
                new DacpPlayer.SnapshotSupplier() {
                    @Override
                    public DacpPlayer.Snapshot get() {
                        return new DacpPlayer.Snapshot(
                                trackInfo,
                                coverArtBytes,
                                durationMs,
                                playing,
                                audioOnly && connectionCount > 0);
                    }
                },
                new DacpPlayer.LongSupplier() {
                    @Override
                    public long get() {
                        return currentPositionMs();
                    }
                },
                new DacpPlayer.PlayingSetter() {
                    @Override
                    public void set(boolean p) {
                        setPlayingInternal(p);
                    }
                });

        mediaSession = new MediaSessionCompat(appContext, "AvaAirPlay");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (videoPlaybackActive) {
                    airPlayVideoPlayer().setPlaying(true);
                    return;
                }
                setPlayingInternal(true);
                if (dacpController != null) dacpController.play();
            }

            @Override
            public void onPause() {
                if (videoPlaybackActive) {
                    airPlayVideoPlayer().setPlaying(false);
                    return;
                }
                setPlayingInternal(false);
                if (dacpController != null) dacpController.pause();
            }

            @Override
            public void onStop() {
                if (videoPlaybackActive) stopVideoPlayback();
            }

            @Override
            public void onFastForward() {
                if (videoPlaybackActive) airPlayVideoPlayer().seekBy(VIDEO_SEEK_STEP_MS);
            }

            @Override
            public void onRewind() {
                if (videoPlaybackActive) airPlayVideoPlayer().seekBy(-VIDEO_SEEK_STEP_MS);
            }

            @Override
            public void onSeekTo(long pos) {
                if (videoPlaybackActive) {
                    airPlayVideoPlayer().scrub(pos / 1000f);
                } else if (audioOnly) {
                    seekAudioTo(pos);
                }
            }

            @Override
            public void onSkipToNext() {
                if (!videoPlaybackActive && dacpController != null) dacpController.nextItem();
            }

            @Override
            public void onSkipToPrevious() {
                if (!videoPlaybackActive && dacpController != null) dacpController.prevItem();
            }
        }, mainHandler);

        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (ACTION_PLAY_PAUSE.equals(action)) {
                    togglePlayPause();
                } else if (ACTION_NEXT.equals(action)) {
                    if (dacpController != null) dacpController.nextItem();
                } else if (ACTION_PREV.equals(action)) {
                    if (dacpController != null) dacpController.prevItem();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(mediaReceiver, filter);
        }

        airPlayVideoPlayer().onVideoSize = new AirPlayVideoPlayer.VideoSizeListener() {
            @Override
            public void onVideoSize(int width, int height, float aspect) {
                videoPlaybackAspect = aspect;
                videoPlaybackSize = new VideoSize(width, height);
            }
        };
        airPlayVideoPlayer().onTitle = new AirPlayVideoPlayer.TitleListener() {
            @Override
            public void onTitle(String title) {
                videoTitle = title != null ? title : "";
            }
        };
        airPlayVideoPlayer().onEnded = new AirPlayVideoPlayer.EndedListener() {
            @Override
            public void onEnded() {
                endVideoPlayback("AirPlay Video stopped (player)");
            }
        };
        airPlayVideoPlayer().onPlaybackInfo = new AirPlayVideoPlayer.PlaybackInfoListener() {
            @Override
            public void onPlaybackInfo(AirPlayVideoPlayer.PlaybackSnapshot snapshot) {
                if (nativeHandle != 0L) {
                    NativeBridge.nativeUpdatePlaybackInfo(nativeHandle,
                            snapshot.position, snapshot.duration, snapshot.rate, snapshot.ready);
                }
                if (videoPlaybackActive) {
                    updateVideoPlaybackState(snapshot.position, snapshot.rate);
                    long durMs = snapshot.duration > 0f ? (long) (snapshot.duration * 1000) : 0L;
                    videoPlaybackInfo = new VideoPlaybackInfo(
                            (long) (snapshot.position * 1000),
                            durMs,
                            snapshot.playWhenReady,
                            snapshot.speed,
                            snapshot.skipSilence,
                            snapshot.buffering);
                }
            }
        };
    }

    public void startServer(String name) {
        if (serverState == ServerState.RUNNING) return;
        String effectiveName = name == null || name.trim().isEmpty() ? Prefs.DEF_SERVER_NAME : name;
        advertisedName = effectiveName;

        SharedPreferences p = prefs();
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server");
        wakeLock.acquire();

        nsdManager = new NsdServiceManager(appContext);
        nsdManager.acquireMulticastLock();

        byte[] hwAddr = getHwAddr();
        String keyFile = new java.io.File(appContext.getFilesDir(), "airplay.pem").getAbsolutePath();
        boolean nohold = p.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN);
        boolean requirePin = p.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN);

        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin);
        if (nativeHandle == 0L) {
            log("Native init failed");
            failStart();
            return;
        }

        int maxFps = p.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS);
        boolean overscanned = p.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED);
        int audioLatencyMs = p.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS);
        boolean h265 = p.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED);
        boolean alac = p.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED);
        boolean aac = p.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED);

        audioRenderer.swAlacEnabled = p.getBoolean(Prefs.SW_ALAC_ENABLED, Prefs.DEF_SW_ALAC_ENABLED);
        audioRenderer.audioBufferMultiplier = p.getInt(Prefs.AUDIO_BUFFER_MULTIPLIER, Prefs.DEF_AUDIO_BUFFER_MULTIPLIER);
        videoRenderer.enforceSdr = p.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR);
        videoRenderer.keyAllowFrameDrop = p.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP);
        boolean realtimePriority = p.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY);
        videoRenderer.realtimeDecoderPriority = realtimePriority;
        videoRenderer.operatingRateHint = p.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE);
        videoRenderer.benchmarkLog = p.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG);
        videoRenderer.benchmarkLogCallback = new VideoRenderer.LogCallback() {
            @Override
            public void log(String msg) {
                LogListener lc = logCallback;
                if (lc != null) lc.onLog(msg);
                if (listener != null) listener.onStateChanged();
            }
        };
        videoRenderer.scheduledOutputBufferRelease = p.getBoolean(
                Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE);
        audioRenderer.realtimeDecoderPriority = realtimePriority;
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265);
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac);
        boolean advertiseVideo = p.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO);
        boolean advertiseAudio = p.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO);
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo);
        NativeBridge.nativeSetAudioEnabled(nativeHandle, advertiseAudio);
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps);
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", overscanned ? 1 : 0);
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000);
        }

        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        String res = p.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION);
        if (res == null) res = Prefs.DEF_RESOLUTION;
        int w, h;
        if (!"auto".equals(res) && res.contains("x")) {
            String[] parts = res.split("x");
            w = Integer.parseInt(parts[0]);
            h = Integer.parseInt(parts[1]);
        } else {
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        videoRenderer.setResolution(w, h);
        videoResolution = w + "x" + h;
        videoAspect = (float) w / h;
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps);

        int requestedPort = p.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT);
        if (requestedPort < 1) requestedPort = 1;
        if (requestedPort > 65535) requestedPort = 65535;
        int port = NativeBridge.nativeStart(nativeHandle, requestedPort);
        if (port < 0) {
            log("Failed to start on port " + requestedPort);
            failStart();
            return;
        }

        Map<String, String> raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle);
        if (raopTxt == null) raopTxt = java.util.Collections.emptyMap();
        Map<String, String> airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle);
        if (airplayTxt == null) airplayTxt = java.util.Collections.emptyMap();
        String raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle);
        if (raopName == null) raopName = "AirPlay";
        String resolvedName = NativeBridge.nativeGetServerName(nativeHandle);
        if (resolvedName == null) resolvedName = effectiveName;

        if (p.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)) {
            nsdManager.registerRaop(raopName, port, raopTxt);
        }
        nsdManager.registerAirplay(resolvedName, port, airplayTxt);

        serverState = ServerState.RUNNING;
        if (listener != null) listener.onStateChanged();
        log("Server started on port " + port);
    }

    private void failStart() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
        if (nsdManager != null) {
            nsdManager.release();
            nsdManager = null;
        }
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        serverState = ServerState.ERROR;
        if (listener != null) listener.onStateChanged();
    }

    public void stopServer() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle);
            NativeBridge.nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
        if (dacpController != null) {
            dacpController.release();
            // Keep instance for late UI events; recreate executor on next start().
            // Null only in destroy().
        }
        if (nsdManager != null) {
            nsdManager.release();
            nsdManager = null;
        }
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        videoRenderer.release();
        audioRenderer.release();
        if (airPlayVideoPlayer != null) airPlayVideoPlayer.stop();
        if (mediaSession != null) mediaSession.setActive(false);
        audioOnly = false;
        videoPlaybackActive = false;
        mirroringActive = false;
        videoPlaybackInfo = new VideoPlaybackInfo();
        lastVideoPollAt = 0L;
        videoPollSuppressed = false;
        coverArtBytes = null;
        trackInfo = new TrackInfo();
        positionMs = 0L;
        durationMs = 0L;
        serverState = ServerState.STOPPED;
        connectionCount = 0;
        refreshDacpPlayer();
        if (listener != null) {
            listener.onHideOverlay();
            listener.onStateChanged();
        }
        log("Server stopped");
    }

    public void setVideoSurface(Surface surface) {
        videoRenderer.setSurface(surface);
    }

    public void clearVideoSurface(Surface surface) {
        videoRenderer.clearSurface(surface);
    }

    public void setVideoPlaybackSurface(Surface surface) {
        airPlayVideoPlayer().setSurface(surface);
    }

    public void clearVideoPlaybackSurface(Surface surface) {
        airPlayVideoPlayer().clearSurface(surface);
    }

    public void setVideoPlaying(boolean playing) {
        VideoPlaybackInfo cur = videoPlaybackInfo;
        videoPlaybackInfo = new VideoPlaybackInfo(
                cur.positionMs,
                cur.durationMs,
                playing,
                cur.speed,
                cur.skipSilence,
                playing && cur.buffering);
        airPlayVideoPlayer().setPlaying(playing);
    }

    public void seekVideoTo(long positionMs) {
        airPlayVideoPlayer().scrub(positionMs / 1000f);
    }

    public void setVideoScrubbing(boolean enabled) {
        airPlayVideoPlayer().setScrubbing(enabled);
    }

    public void setVideoSpeed(float speed) {
        airPlayVideoPlayer().setSpeed(speed);
    }

    public void setVideoSkipSilence(boolean enabled) {
        airPlayVideoPlayer().setSkipSilence(enabled);
    }

    public void seekVideoBy(long deltaMs) {
        airPlayVideoPlayer().seekBy(deltaMs);
    }

    public void stopVideoPlayback() {
        endVideoPlayback("AirPlay Video stopped (local)");
    }

    public void downloadVideo() {
        String loc = videoLocation;
        if (loc != null) videoDownloader().start(loc, getVideoTitle());
    }

    public void toggleVideoDownload() {
        VideoDownloader dl = videoDownloader();
        if (dl.isActive() || dl.getProgress() != null) {
            dl.cancel();
        } else {
            String loc = videoLocation;
            if (loc != null) dl.start(loc, getVideoTitle());
        }
    }

    private void endVideoPlayback(String message) {
        if (!videoPlaybackActive) return;
        videoPollSuppressed = true;
        videoPlaybackActive = false;
        if (airPlayVideoPlayer != null) airPlayVideoPlayer.stop();
        if (!audioOnly && mediaSession != null) mediaSession.setActive(false);
        log(message);
    }

    public void destroy() {
        stopServer();
        if (dacpPlayer != null) dacpPlayer.release();
        if (mediaReceiver != null) {
            try { appContext.unregisterReceiver(mediaReceiver); } catch (Exception ignored) {}
            mediaReceiver = null;
        }
        if (dacpController != null) {
            dacpController.release();
            dacpController = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        started = false;
    }

    // RaopCallbackHandler

    @Override
    public void onVideoData(byte[] data, long ntpTimeNs, boolean isH265) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265);
    }

    @Override
    public void onAudioData(byte[] data, int ct, long ntpTimeNs, int seqNum) {
        audioRenderer.feedAudio(data, ct, ntpTimeNs);
    }

    @Override
    public void onVideoSessionPoll() {
        lastVideoPollAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onVideoPlay(String location, float startPositionSeconds) {
        videoLocation = location;
        videoPlaySeq++;
        videoPollSuppressed = false;
        videoPlaybackInfo = new VideoPlaybackInfo(
                (long) (startPositionSeconds * 1000), 0L, true, 1f, false, false);
        videoPlaybackAspect = 16f / 9f;
        videoPlaybackSize = null;
        videoTitle = "";
        // Video owns the overlay — disarm music chrome so the two UIs never stack.
        disarmAudioOnlyUi();
        videoPlaybackActive = true;
        mirroringActive = false;
        // Show overlay first so the video SurfaceView can create a Surface;
        // AirPlayVideoPlayer waits for setSurface before prepare().
        if (listener != null) listener.onShowOverlay();
        airPlayVideoPlayer().play(location, startPositionSeconds);
        if (mediaSession != null) mediaSession.setActive(true);
        log("AirPlay Video play: " + location + " @ " + startPositionSeconds + "s");
        if (listener != null) listener.onStateChanged();
    }

    @Override
    public void onVideoScrub(float positionSeconds) {
        airPlayVideoPlayer().scrub(positionSeconds);
    }

    @Override
    public void onVideoRate(float rate) {
        airPlayVideoPlayer().setRate(rate);
    }

    @Override
    public void onVideoStop() {
        endVideoPlayback("AirPlay Video stopped");
    }

    @Override
    public void onAudioFormat(int ct, int spf, boolean usingScreen) {
        clearPin();
        // HLS / mirror sessions still emit RAOP audio-format probes with usingScreen=false.
        // Do not flip into music mode while video or mirror owns the overlay.
        if (!usingScreen && !audioOnly && !videoPlaybackActive && !mirroringActive) {
            onAudioOnly(true);
        }
        log("Audio format: ct=" + ct + " spf=" + spf + " screen=" + usingScreen);
    }

    @Override
    public void onVideoSize(float srcW, float srcH, float w, float h) {
        clearPin();
        if (w > 0 && h > 0) {
            videoAspect = w / h;
            videoResolution = ((int) w) + "x" + ((int) h);
            videoRenderer.setResolution((int) w, (int) h);
            // Mirror owns the overlay — same exclusivity as HLS video.
            disarmAudioOnlyUi();
            mirroringActive = true;
            if (listener != null) listener.onShowOverlay();
        }
        log("Video size: " + srcW + "x" + srcH + " -> " + w + "x" + h);
    }

    @Override
    public void onVolumeChange(float volume) {
        audioRenderer.setVolume(volume);
    }

    @Override
    public void onConnectionInit() {
        boolean firstConnection = connectionCount == 0;
        connectionCount++;
        log("Client connected (" + connectionCount + ")");
        if (!firstConnection) return;
        if (requiresPin()) return;
        // Do not show overlay on bare connect — wait for mirroring / video / audio media.
    }

    @Override
    public void onConnectionDestroy() {
        int c = connectionCount - 1;
        if (c < 0) c = 0;
        connectionCount = c;
        if (connectionCount == 0) {
            endVideoPlayback("AirPlay Video stopped (disconnect)");
            audioOnly = false;
            mirroringActive = false;
            lastVideoPollAt = 0L;
            videoPollSuppressed = false;
            coverArtBytes = null;
            trackInfo = new TrackInfo();
            positionMs = 0L;
            durationMs = 0L;
            progressBaseMs = 0L;
            progressBaseTime = 0L;
            lastProgressAt = 0L;
            userPaused = false;
            playing = false;
            audioScrubbing = false;
            if (mediaSession != null) mediaSession.setActive(false);
            audioRenderer.markSessionEnded();
            refreshDacpPlayer();
            if (listener != null) listener.onHideOverlay();
        }
        log("Client disconnected (" + connectionCount + ")");
    }

    @Override
    public void onConnectionReset(int reason) {
        audioRenderer.markSessionEnded();
        log("Connection reset: " + reason);
    }

    @Override
    public void onDisplayPin(String pin) {
        if (pin != null && pin.equals(lastPin)) return;
        lastPin = pin;
        if (listener != null) listener.onPin(pin);
    }

    @Override
    public void onMetadata(byte[] data) {
        Map<String, Object> map = DmapParser.parse(data);
        TrackInfo info = TrackInfo.fromDmap(map, trackInfo.coverArt);
        trackInfo = info;
        if (info.durationMs > 0) durationMs = info.durationMs;
        updateMediaMetadata();
        refreshDacpPlayer();
        log("Track: " + info.artist + " - " + info.title);
        maybeShowAudioOverlay();
        if (listener != null) listener.onTrackChanged();
    }

    @Override
    public void onCoverArt(byte[] data) {
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp == null) return;
        coverArtBytes = data;
        trackInfo = trackInfo.withCoverArt(bmp);
        updateMediaMetadata();
        refreshDacpPlayer();
        maybeShowAudioOverlay();
    }

    @Override
    public void onProgress(long start, long curr, long end) {
        // RTP timestamp units at 44.1 kHz (UxPlay / AirPlay audio progress).
        // Match upstream AirPlayService: any valid progress packet means playing,
        // and re-anchors wall-clock extrapolation. Do NOT auto-pause on "stale"
        // samples — iOS often sends infrequent / flat progress while audio plays.
        if (curr < start || end <= start) return;
        double rate = 44100.0;
        long posMs = (long) (((curr - start) / rate) * 1000.0);
        if (posMs < 0) posMs = 0;
        long durMs = (long) (((end - start) / rate) * 1000.0);
        if (durMs <= 0) return;
        long now = SystemClock.elapsedRealtime();
        lastProgressAt = now;
        durationMs = durMs;

        // Scrubbing: keep absolute sample, never auto-play.
        if (audioScrubbing) {
            positionMs = posMs;
            progressBaseMs = posMs;
            progressBaseTime = 0L;
            updatePlaybackState();
            refreshDacpPlayer();
            return;
        }

        // Local pause: absorb sender position; only clear if sender clearly resumed.
        if (userPaused) {
            long frozenAt = positionMs;
            positionMs = posMs;
            progressBaseMs = posMs;
            progressBaseTime = 0L;
            if (posMs > frozenAt + REMOTE_RESUME_SLACK_MS) {
                userPaused = false;
                playing = true;
                progressBaseTime = now;
                updatePlaybackState();
                refreshDacpPlayer();
                maybeShowAudioOverlay();
                if (listener != null) listener.onTrackChanged();
                return;
            }
            playing = false;
            updatePlaybackState();
            refreshDacpPlayer();
            return;
        }

        boolean wasPlaying = playing;
        positionMs = posMs;
        progressBaseMs = posMs;
        progressBaseTime = now;
        playing = true;

        updatePlaybackState();
        refreshDacpPlayer();
        maybeShowAudioOverlay();
        if (playing != wasPlaying && listener != null) listener.onTrackChanged();
    }

    @Override
    public void onDacpId(String dacpId, String activeRemote) {
        if (dacpController != null) dacpController.update(dacpId, activeRemote);
        log("DACP: " + dacpId);
    }

    @Override
    public void onAudioOnly(boolean audioOnly) {
        boolean prev = this.audioOnly;
        // Never stop HLS video / mirror from a RAOP audio-only flag — Bilibili and
        // similar senders keep an audio session while video_play is active. UI
        // exclusivity is handled by resolveMode (VIDEO > MIRROR > AUDIO).
        if (audioOnly && (videoPlaybackActive || mirroringActive)) {
            log("Audio mode deferred (video/mirror active)");
            return;
        }
        this.audioOnly = audioOnly;
        refreshDacpPlayer();
        if (audioOnly && !prev) {
            if (mediaSession != null) mediaSession.setActive(true);
            if (modeCallback != null) modeCallback.onMode(true);
            log("Audio mode");
            // Overlay waits for metadata / progress / cover — not bare audio-only flag.
            if (listener != null) listener.onTrackChanged();
        } else if (!audioOnly && prev) {
            if (mediaSession != null) mediaSession.setActive(false);
            coverArtBytes = null;
            trackInfo = new TrackInfo();
            positionMs = 0L;
            durationMs = 0L;
            if (modeCallback != null) modeCallback.onMode(false);
            log("Mirror mode");
            if (!mirroringActive && !videoPlaybackActive && listener != null) {
                listener.onHideOverlay();
            }
        }
    }

    /**
     * Leave music chrome disarmed without hiding the overlay.
     * Used when video/mirror takes over so stale track art cannot reappear later.
     */
    private void disarmAudioOnlyUi() {
        if (!audioOnly) return;
        audioOnly = false;
        coverArtBytes = null;
        trackInfo = new TrackInfo();
        positionMs = 0L;
        durationMs = 0L;
        progressBaseMs = 0L;
        progressBaseTime = 0L;
        lastProgressAt = 0L;
        userPaused = false;
        playing = false;
        audioScrubbing = false;
        if (modeCallback != null) modeCallback.onMode(false);
    }

    /** Show cinema UI only once audio actually has a track or playback progress. */
    private void maybeShowAudioOverlay() {
        if (!audioOnly || videoPlaybackActive || mirroringActive) return;
        TrackInfo info = trackInfo;
        boolean hasTrack = info != null && (
                (info.title != null && !info.title.isEmpty())
                        || (info.artist != null && !info.artist.isEmpty())
                        || info.coverArt != null);
        boolean hasProgress = durationMs > 0 && playing;
        if (!hasTrack && !hasProgress) return;
        if (listener != null) listener.onShowOverlay();
    }

    private void updateMediaMetadata() {
        TrackInfo info = trackInfo;
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (info.coverArt != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, info.coverArt);
        }
        if (mediaSession != null) mediaSession.setMetadata(builder.build());
    }

    public void seekAudioTo(long positionMs) {
        long target = Math.max(0L, positionMs);
        if (durationMs > 0) target = Math.min(target, durationMs);
        this.positionMs = target;
        progressBaseMs = target;
        progressBaseTime = playing ? SystemClock.elapsedRealtime() : 0L;
        lastProgressAt = SystemClock.elapsedRealtime();
        updatePlaybackState();
        refreshDacpPlayer();
        if (dacpController != null) dacpController.seekTo(target);
        if (listener != null) listener.onTrackChanged();
    }

    public void setAudioScrubbing(boolean scrubbing) {
        if (scrubbing) {
            // Freeze extrapolation while the user drags.
            positionMs = currentPositionMs();
            progressBaseTime = 0L;
        }
        audioScrubbing = scrubbing;
        if (!scrubbing && playing) {
            progressBaseMs = positionMs;
            progressBaseTime = SystemClock.elapsedRealtime();
            lastProgressAt = progressBaseTime;
        }
        updatePlaybackState();
    }

    public void togglePlayPause() {
        boolean nowPlaying = !isPlaying();
        setPlayingInternal(nowPlaying);
        if (dacpController != null) {
            if (nowPlaying) dacpController.play();
            else dacpController.pause();
        }
        if (listener != null) listener.onTrackChanged();
    }

    public void skipNext() {
        if (dacpController != null) dacpController.nextItem();
    }

    public void skipPrevious() {
        if (dacpController != null) dacpController.prevItem();
    }

    public void audioScanBegin(boolean forward) {
        if (dacpController == null) return;
        if (forward) dacpController.beginFastForward();
        else dacpController.beginRewind();
    }

    public void audioScanEnd() {
        if (dacpController != null) dacpController.playResume();
    }

    private void setPlayingInternal(boolean p) {
        if (!p) {
            // Capture live position then freeze.
            if (playing && progressBaseTime != 0L) {
                long elapsed = SystemClock.elapsedRealtime() - progressBaseTime;
                positionMs = Math.min(durationMs > 0 ? durationMs : Long.MAX_VALUE,
                        Math.max(0L, progressBaseMs + elapsed));
            }
            playing = false;
            userPaused = true;
            progressBaseMs = positionMs;
            progressBaseTime = 0L;
        } else {
            playing = true;
            userPaused = false;
            progressBaseMs = positionMs;
            progressBaseTime = SystemClock.elapsedRealtime();
            lastProgressAt = progressBaseTime;
        }
        refreshDacpPlayer();
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        updatePlaybackStateLockedPos();
    }

    private void updatePlaybackStateLockedPos() {
        if (videoPlaybackActive) return;
        boolean isPlaying = playing;
        int pbState = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        float speed = isPlaying ? 1f : 0f;
        long pos = liveOrFrozenPositionMs();
        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(pbState, pos, speed, SystemClock.elapsedRealtime())
                .build();
        if (mediaSession != null) mediaSession.setPlaybackState(state);
    }

    private void updateVideoPlaybackState(float positionSeconds, float rate) {
        boolean isPlaying = rate > 0f;
        long posMs = (long) (positionSeconds * 1000);
        long now = SystemClock.elapsedRealtime();
        long expectedMs = lastVideoStatePosMs
                + (lastVideoStateRate > 0f ? (long) ((now - lastVideoStateAtMs) * lastVideoStateRate) : 0L);
        if (rate == lastVideoStateRate && Math.abs(posMs - expectedMs) < 1000) return;
        lastVideoStateRate = rate;
        lastVideoStatePosMs = posMs;
        lastVideoStateAtMs = now;
        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_FAST_FORWARD
                        | PlaybackStateCompat.ACTION_REWIND
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(
                        isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        posMs,
                        rate,
                        now)
                .build();
        if (mediaSession != null) mediaSession.setPlaybackState(state);
    }

    private void refreshDacpPlayer() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (dacpPlayer != null) dacpPlayer.refresh();
            }
        });
    }

    private void clearPin() {
        lastPin = null;
        if (listener != null) listener.onPin(null);
    }

    public DebugInfo collectDebugInfo() {
        return new DebugInfo(
                videoRenderer.getCodecName(),
                videoResolution,
                videoRenderer.getFps(),
                videoRenderer.getBitrateBps(),
                videoRenderer.getFrameCount(),
                videoRenderer.getDroppedFrames(),
                videoRenderer.getFramePacingJitterUs(),
                audioRenderer.getCodecLabel(),
                (int) (audioRenderer.getVolume() * 100),
                connectionCount);
    }

    private byte[] getHwAddr() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                String n = iface.getName();
                if (n != null && (n.startsWith("wlan") || n.startsWith("eth"))) {
                    byte[] mac = iface.getHardwareAddress();
                    if (mac != null && mac.length == 6) return mac;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get hardware address", e);
        }
        return new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF};
    }

    private boolean requiresPin() {
        return prefs().getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN);
    }
}
