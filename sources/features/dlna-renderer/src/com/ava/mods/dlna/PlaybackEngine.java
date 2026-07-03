package com.ava.mods.dlna;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import org.jupnp.support.model.TransportState;

/**
 * DLNA playback with Cinema overlay (Style B).
 *
 * Audio and video both use the full-screen Cinema overlay. Video renders on a
 * TextureView surface; audio shows centered cover art with the same chrome.
 */
public class PlaybackEngine {
    private static final String TAG = "DlnaPlayback";
    private static final float DUCK_VOLUME = 0.12f;
    /** Keep the Cinema overlay on screen for a bit after the whole queue ends, instead of vanishing instantly. */
    private static final long OVERLAY_END_OF_QUEUE_HIDE_MS = CinemaOverlay.OVERLAY_END_OF_QUEUE_HIDE_MS;

    public interface Listener {
        void onStateChanged(TransportState state);

        void onTrackCompleted();

        void onError(String message);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final CinemaOverlay cinemaOverlay;

    private MediaPlayer player;
    private AudioFocusRequest focusRequest;
    private volatile TransportState state = TransportState.NO_MEDIA_PRESENT;
    private volatile String currentUri = "";
    private volatile DidlMetadata metadata = DidlMetadata.EMPTY;
    private volatile MediaKind currentKind = MediaKind.AUDIO;
    private volatile boolean preparing = false;
    private volatile boolean playWhenReady = false;
    private volatile boolean ducked = false;
    private volatile float volumeMultiplier = 1.0f;
    private volatile boolean showOverlay = false;
    private volatile boolean dualOutputEnabled = false;
    private volatile boolean focusHeld = false;

    private boolean routeOverrideApplied = false;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean previousSpeakerphone = false;
    /** Applied in onPrepared when the user scrubs before prepare finishes. */
    private long pendingSeekMs = -1L;

    // Anonymous class (not a lambda): a lambda body is checked for definite
    // assignment as if inline in this field initializer, which would flag
    // cinemaOverlay (assigned later, in the constructor body) as unset.
    private final Runnable overlayFadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayEnabled()) {
                cinemaOverlay.fadeOutAndHide();
            }
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = player;
            if (p != null && state == TransportState.PLAYING && overlayEnabled()) {
                cinemaOverlay.updatePlayback(true, getPositionMs(), getDurationMs());
            }
            if (state == TransportState.PLAYING) {
                mainHandler.postDelayed(this, 500);
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            Log.d(TAG, "AUDIOFOCUS_GAIN");
                            focusHeld = true;
                            mainHandler.post(PlaybackEngine.this::onFocusRegained);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Voice/TTS uses TRANSIENT_MAY_DUCK; duck via pipeline + volume.
                            Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (duck, keep playback)");
                            mainHandler.post(PlaybackEngine.this::duck);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Same policy as Sendspin: keep the session alive across wake/TTS.
                            Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT (keep playback)");
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Permanent loss — pause but preserve playWhenReady for regain.
                            Log.d(TAG, "AUDIOFOCUS_LOSS (pause, resume on regain)");
                            focusHeld = false;
                            mainHandler.post(PlaybackEngine.this::pauseForFocusLoss);
                            break;
                        default:
                            break;
                    }
                }
            };

    public PlaybackEngine(Context context, CinemaOverlay cinemaOverlay, Listener listener) {
        this.context = context.getApplicationContext();
        this.cinemaOverlay = cinemaOverlay;
        this.listener = listener;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public synchronized void setUri(String uri, DidlMetadata didl, boolean autoPlay) {
        Log.i(TAG, "setUri (preempt): " + uri);
        // A new track is on the way in - cancel any pending "queue ended, hide
        // the overlay" countdown from a previous track's completion.
        mainHandler.removeCallbacks(overlayFadeRunnable);
        currentUri = uri != null ? uri : "";
        metadata = didl != null ? didl : DidlMetadata.EMPTY;
        currentKind = metadata.mediaKind(currentUri);
        playWhenReady = autoPlay;
        // Prepare audio first — Cinema overlay builds a heavy view tree on the main
        // thread; doing that before setDataSource/prepareAsync routinely costs 2–4s
        // of real playback time at track start (user hears silence / "misses" the intro).
        mainHandler.post(() -> {
            if (playWhenReady) {
                requestFocus();
            }
            prepareCurrentUri();
            // Defer heavy overlay construction to the next main-loop turn so
            // setDataSource/prepareAsync is never blocked behind view inflation.
            mainHandler.post(this::updateOverlayVisibility);
        });
    }

    public void setShowOverlay(boolean enabled) {
        showOverlay = enabled;
        mainHandler.post(this::updateOverlayVisibility);
    }

    public boolean isShowOverlay() {
        return showOverlay;
    }

    /**
     * Best-effort "speaker + earpiece" route hint. Default disabled.
     *
     * On many phone-class OEMs, forcing communication mode with speakerphone on
     * encourages the HAL to drive both bottom speaker and top receiver.
     * Not guaranteed across vendors; harmless no-op on devices without earpiece.
     */
    public void setDualOutputEnabled(boolean enabled) {
        dualOutputEnabled = enabled;
        mainHandler.post(() -> {
            if (!enabled) {
                restoreRouteOverrideIfNeeded();
                return;
            }
            if (state == TransportState.PLAYING || state == TransportState.PAUSED_PLAYBACK) {
                applyRouteOverrideIfNeeded();
            }
        });
    }

    public synchronized void play() {
        playWhenReady = true;
        mainHandler.post(() -> {
            MediaPlayer p = player;
            if (p == null) {
                if (!currentUri.isEmpty()) {
                    prepareCurrentUri();
                }
                return;
            }
            if (preparing) {
                return;
            }
            if (requestFocus()) {
                try {
                    p.start();
                    applyVolume();
                    setState(TransportState.PLAYING);
                    startProgressTick();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "play failed, re-preparing", e);
                    prepareCurrentUri();
                }
            }
        });
    }

    public void pause() {
        playWhenReady = false;
        mainHandler.post(this::pauseInternal);
    }

    public void togglePlayPause() {
        mainHandler.post(() -> {
            if (state == TransportState.PLAYING) {
                pauseInternal();
            } else {
                play();
            }
        });
    }

    public void stop() {
        playWhenReady = false;
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(overlayFadeRunnable);
            stopProgressTick();
            releasePlayer();
            abandonFocus();
            hideOverlay();
            setState(TransportState.STOPPED);
        });
    }

    /**
     * Called once the caller has confirmed there's truly nothing left to play
     * (no queued next URI, no repeat/shuffle fallback) - keeps the overlay
     * visible for {@link #OVERLAY_END_OF_QUEUE_HIDE_MS} instead of yanking it
     * away the instant the last track finishes.
     */
    public void scheduleOverlayHideAfterQueueEnd() {
        mainHandler.removeCallbacks(overlayFadeRunnable);
        mainHandler.postDelayed(overlayFadeRunnable, OVERLAY_END_OF_QUEUE_HIDE_MS);
    }

    public void seekTo(final long positionMs) {
        mainHandler.post(() -> seekToInternal(positionMs));
    }

    private void seekToInternal(long positionMs) {
        MediaPlayer p = player;
        if (p == null) {
            Log.w(TAG, "seek ignored: no player");
            return;
        }
        long durationMs = getDurationMs();
        long targetMs = positionMs;
        if (durationMs > 0) {
            targetMs = Math.max(0L, Math.min(positionMs, durationMs));
        } else {
            targetMs = Math.max(0L, positionMs);
        }
        if (preparing) {
            pendingSeekMs = targetMs;
            Log.d(TAG, "seek queued until prepared -> " + targetMs + "ms");
            if (overlayEnabled()) {
                cinemaOverlay.notifySeekApplied(targetMs, durationMs, state == TransportState.PLAYING);
            }
            return;
        }
        applySeek(p, targetMs, durationMs);
    }

    /**
     * Above this drift (target vs. actual position once MediaPlayer reports the
     * seek "complete") we assume the underlying HTTP source ignored the Range
     * request entirely - common for DLNA servers/transcodes that don't support
     * random access. Nothing left to fix client-side at that point; we log it
     * plainly so it's diagnosable instead of silently looking like a UI bug.
     */
    private static final long SEEK_DRIFT_WARN_MS = 3000L;

    private void applySeek(MediaPlayer p, final long targetMs, long durationMs) {
        try {
            seekToClosestOrLegacy(p, targetMs);
            Log.d(TAG, "seekTo requested -> " + targetMs + "ms (was at " + getPositionMs() + "ms)");
            p.setOnSeekCompleteListener(mp -> mainHandler.post(() -> {
                if (mp != player) {
                    return;
                }
                long actualMs = getPositionMs();
                long drift = Math.abs(actualMs - targetMs);
                if (drift > SEEK_DRIFT_WARN_MS) {
                    Log.w(TAG, "onSeekComplete but actual=" + actualMs + "ms drifted " + drift
                            + "ms from target=" + targetMs
                            + "ms - stream likely doesn't support HTTP Range seeking");
                } else {
                    Log.d(TAG, "onSeekComplete actual=" + actualMs + "ms (target " + targetMs + "ms)");
                }
                if (overlayEnabled()) {
                    cinemaOverlay.notifySeekApplied(actualMs, getDurationMs(), state == TransportState.PLAYING);
                }
            }));
            if (overlayEnabled()) {
                cinemaOverlay.updatePlayback(
                        state == TransportState.PLAYING,
                        targetMs,
                        durationMs);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "seek failed at " + targetMs + "ms", e);
        }
    }

    /** SEEK_CLOSEST is API 26+ and some OEM decoders reject it for live/streamed sources. */
    private void seekToClosestOrLegacy(MediaPlayer p, long targetMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                p.seekTo((int) targetMs, MediaPlayer.SEEK_CLOSEST);
                return;
            } catch (Exception e) {
                Log.w(TAG, "SEEK_CLOSEST rejected, falling back to legacy seekTo", e);
            }
        }
        p.seekTo((int) targetMs);
    }

    private void applyPendingSeekIfNeeded(MediaPlayer p) {
        if (pendingSeekMs < 0) {
            return;
        }
        long targetMs = pendingSeekMs;
        pendingSeekMs = -1L;
        applySeek(p, targetMs, getDurationMs());
    }

    public void shutdown() {
        stop();
        state = TransportState.NO_MEDIA_PRESENT;
        currentUri = "";
        metadata = DidlMetadata.EMPTY;
        hideOverlay();
        if (cinemaOverlay != null) {
            cinemaOverlay.destroy();
        }
    }

    public void duck() {
        ducked = true;
        mainHandler.post(this::applyVolume);
    }

    public void unDuck() {
        ducked = false;
        mainHandler.post(this::applyVolume);
    }

    /** Called when Ava's voice session ends — mirrors Sendspin onFocusRegained fallback. */
    public void resumeAfterVoiceSession() {
        mainHandler.post(this::onFocusRegained);
    }

    public void setVolumeMultiplier(float multiplier) {
        volumeMultiplier = Math.max(0f, Math.min(1f, multiplier));
        mainHandler.post(this::applyVolume);
    }

    public TransportState getState() {
        return state;
    }

    public String getCurrentUri() {
        return currentUri;
    }

    public DidlMetadata getMetadata() {
        return metadata;
    }

    public MediaKind getCurrentKind() {
        return currentKind;
    }

    public long getPositionMs() {
        MediaPlayer p = player;
        if (p == null || preparing) {
            return 0;
        }
        try {
            return p.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /**
     * Prefers the MediaPlayer's own duration once it knows it, but many DLNA
     * streams (progressive HTTP, moov-atom-at-end MP4, some transcoded audio)
     * never report a duration to MediaPlayer at all. Controllers almost always
     * send it in the DIDL-Lite <res duration="..."> attribute, so fall back to
     * that instead of leaving the UI stuck at 0:00.
     */
    public long getDurationMs() {
        MediaPlayer p = player;
        if (p != null && !preparing) {
            try {
                long d = p.getDuration();
                if (d > 0) {
                    return d;
                }
            } catch (IllegalStateException ignored) {
            }
        }
        return metadata.durationMs > 0 ? metadata.durationMs : 0;
    }

    /** Audio session for software AEC playback capture ([ModPlaybackReference] hook). */
    public int getAudioSessionId() {
        MediaPlayer p = player;
        if (p == null || preparing) {
            return 0;
        }
        try {
            return p.getAudioSessionId();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------

    private void prepareCurrentUri() {
        stopProgressTick();
        releasePlayer();
        if (currentUri.isEmpty()) {
            hideOverlay();
            setState(TransportState.NO_MEDIA_PRESENT);
            return;
        }
        MediaPlayer p = new MediaPlayer();
        player = p;
        preparing = true;
        pendingSeekMs = -1L;
        try {
            int contentType = currentKind == MediaKind.VIDEO
                    ? AudioAttributes.CONTENT_TYPE_MOVIE
                    : AudioAttributes.CONTENT_TYPE_MUSIC;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(contentType)
                    .build());
            p.setOnPreparedListener(mp -> {
                preparing = false;
                if (mp != player) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && currentKind == MediaKind.VIDEO) {
                    try {
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    } catch (Exception e) {
                        Log.w(TAG, "setVideoScalingMode failed", e);
                    }
                }
                if (currentKind == MediaKind.VIDEO && overlayEnabled()) {
                    attachVideoSurface(mp);
                }
                applyPendingSeekIfNeeded(mp);
                if (playWhenReady && requestFocus()) {
                    applyRouteOverrideIfNeeded();
                    mp.start();
                    applyVolume();
                    setState(TransportState.PLAYING);
                    startProgressTick();
                    if (overlayEnabled()) {
                        cinemaOverlay.updatePlayback(true, 0, getDurationMs());
                    }
                } else {
                    setState(TransportState.STOPPED);
                    if (overlayEnabled()) {
                        cinemaOverlay.updatePlayback(false, 0, getDurationMs());
                    }
                }
            });
            p.setOnCompletionListener(mp -> {
                if (mp != player) {
                    return;
                }
                stopProgressTick();
                setState(TransportState.STOPPED);
                abandonFocus();
                restoreRouteOverrideIfNeeded();
                if (overlayEnabled()) {
                    cinemaOverlay.updatePlayback(false, getDurationMs(), getDurationMs());
                }
                // Don't fade the overlay out here: the listener may still chain
                // into a queued/history/repeat track via setUri(), which should
                // keep the overlay up without a hide-then-reshow flicker. It's
                // the listener's job to call scheduleOverlayHideAfterQueueEnd()
                // once it has confirmed there's really nothing left to play.
                if (listener != null) {
                    listener.onTrackCompleted();
                }
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) {
                    return true;
                }
                preparing = false;
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra + " uri=" + currentUri);
                stopProgressTick();
                releasePlayer();
                abandonFocus();
                restoreRouteOverrideIfNeeded();
                setState(TransportState.STOPPED);
                if (listener != null) {
                    listener.onError("MediaPlayer error " + what + "/" + extra);
                }
                return true;
            });
            p.setOnVideoSizeChangedListener((mp, width, height) -> Log.d(TAG, "Video size " + width + "x" + height));
            p.setDataSource(context, android.net.Uri.parse(currentUri));
            setState(TransportState.TRANSITIONING);
            p.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            Log.w(TAG, "setDataSource failed: " + currentUri, e);
            releasePlayer();
            setState(TransportState.STOPPED);
            if (listener != null) {
                listener.onError("Cannot open URI: " + e.getMessage());
            }
        }
    }

    private void attachVideoSurface(MediaPlayer p) {
        if (!overlayEnabled()) {
            return;
        }
        tryAttachSurface(p, 20);
    }

    private boolean overlayEnabled() {
        return showOverlay && cinemaOverlay != null;
    }

    private void updateOverlayVisibility() {
        if (cinemaOverlay == null) {
            return;
        }
        if (showOverlay && !currentUri.isEmpty()) {
            cinemaOverlay.show(metadata, currentKind);
            MediaPlayer p = player;
            if (p != null && currentKind == MediaKind.VIDEO && !preparing) {
                attachVideoSurface(p);
            }
            if (state == TransportState.PLAYING || state == TransportState.PAUSED_PLAYBACK) {
                cinemaOverlay.updatePlayback(
                        state == TransportState.PLAYING,
                        getPositionMs(),
                        getDurationMs());
            }
        } else {
            hideOverlay();
        }
    }

    private void hideOverlay() {
        if (cinemaOverlay != null) {
            cinemaOverlay.hide();
        }
    }

    private void tryAttachSurface(final MediaPlayer p, final int retriesLeft) {
        Surface surface = cinemaOverlay.getVideoSurface();
        if (surface != null && surface.isValid()) {
            try {
                p.setSurface(surface);
            } catch (Exception e) {
                Log.w(TAG, "setSurface failed", e);
            }
            return;
        }
        if (retriesLeft > 0) {
            mainHandler.postDelayed(() -> tryAttachSurface(p, retriesLeft - 1), 100);
        }
    }

    private void pauseInternal() {
        MediaPlayer p = player;
        if (p == null || preparing) {
            return;
        }
        try {
            if (p.isPlaying()) {
                p.pause();
            }
            stopProgressTick();
            setState(TransportState.PAUSED_PLAYBACK);
            if (overlayEnabled()) {
                cinemaOverlay.updatePlayback(false, getPositionMs(), getDurationMs());
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "pause failed", e);
        }
    }

    /** Pause for focus loss only — keeps playWhenReady so regain can resume. */
    private void pauseForFocusLoss() {
        pauseInternal();
    }

    /**
     * Sendspin-style regain: restore ducking and resume if the user/controller still
     * wants playback after Ava's voice pipeline releases focus.
     */
    private void onFocusRegained() {
        unDuck();
        if (!playWhenReady) {
            return;
        }
        MediaPlayer p = player;
        if (p == null) {
            if (!currentUri.isEmpty()) {
                prepareCurrentUri();
            }
            return;
        }
        if (preparing) {
            return;
        }
        if (state == TransportState.PAUSED_PLAYBACK) {
            resumeAfterFocusLoss();
        }
    }

    private void resumeAfterFocusLoss() {
        if (!requestFocus()) {
            Log.d(TAG, "resume deferred: audio focus not granted yet");
            return;
        }
        MediaPlayer p = player;
        if (p == null || preparing) {
            return;
        }
        try {
            p.start();
            applyRouteOverrideIfNeeded();
            applyVolume();
            setState(TransportState.PLAYING);
            startProgressTick();
            if (overlayEnabled()) {
                cinemaOverlay.updatePlayback(true, getPositionMs(), getDurationMs());
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "resume after focus loss failed, re-preparing", e);
            prepareCurrentUri();
        }
    }

    private void applyVolume() {
        MediaPlayer p = player;
        if (p == null) {
            return;
        }
        float v = volumeMultiplier * (ducked ? DUCK_VOLUME : 1.0f);
        try {
            p.setVolume(v, v);
        } catch (IllegalStateException ignored) {
        }
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        preparing = false;
        if (p != null) {
            try {
                p.setSurface(null);
            } catch (Exception ignored) {
            }
            try {
                p.reset();
            } catch (Exception ignored) {
            }
            try {
                p.release();
            } catch (Exception ignored) {
            }
        }
        restoreRouteOverrideIfNeeded();
    }

    private void startProgressTick() {
        mainHandler.removeCallbacks(progressTick);
        mainHandler.post(progressTick);
    }

    private void stopProgressTick() {
        mainHandler.removeCallbacks(progressTick);
    }

    private boolean requestFocus() {
        if (audioManager == null) {
            return true;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusHeld;
    }

    private void abandonFocus() {
        if (audioManager == null) {
            return;
        }
        focusHeld = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyRouteOverrideIfNeeded() {
        if (!dualOutputEnabled || routeOverrideApplied || audioManager == null) {
            return;
        }
        if (!hasBuiltInEarpiece()) {
            Log.i(TAG, "Dual output skipped: no built-in earpiece on this device");
            return;
        }
        try {
            previousAudioMode = audioManager.getMode();
        } catch (Exception ignored) {
            previousAudioMode = AudioManager.MODE_NORMAL;
        }
        try {
            previousSpeakerphone = audioManager.isSpeakerphoneOn();
        } catch (Exception ignored) {
            previousSpeakerphone = false;
        }
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            Log.w(TAG, "setMode(MODE_IN_COMMUNICATION) failed", e);
        }
        try {
            audioManager.setSpeakerphoneOn(true);
        } catch (Exception e) {
            Log.w(TAG, "setSpeakerphoneOn(true) failed", e);
        }
        routeOverrideApplied = true;
        Log.i(TAG, "Dual output route hint applied");
    }

    private void restoreRouteOverrideIfNeeded() {
        if (!routeOverrideApplied || audioManager == null) {
            return;
        }
        try {
            audioManager.setSpeakerphoneOn(previousSpeakerphone);
        } catch (Exception ignored) {
        }
        try {
            audioManager.setMode(previousAudioMode);
        } catch (Exception ignored) {
        }
        routeOverrideApplied = false;
        Log.i(TAG, "Dual output route hint restored");
    }

    private boolean hasBuiltInEarpiece() {
        if (audioManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Pre-23 can't enumerate output devices; assume phone-like form factor.
            return true;
        }
        try {
            android.media.AudioDeviceInfo[] outputs =
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (android.media.AudioDeviceInfo info : outputs) {
                if (info != null && info.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio device enumeration failed", e);
        }
        return false;
    }

    private void setState(TransportState newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        Log.d(TAG, "state -> " + newState);
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }
}
