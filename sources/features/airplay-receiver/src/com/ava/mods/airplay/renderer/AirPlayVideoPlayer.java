package com.ava.mods.airplay.renderer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class AirPlayVideoPlayer {

    private static final String TAG = "AirPlayVideoPlayer";
    private static final long REPORT_INTERVAL_MS = 250L;

    public static final class PlaybackSnapshot {
        public final float position;
        public final float duration;
        public final float rate;
        public final boolean ready;
        public final boolean playWhenReady;
        public final float speed;
        public final boolean skipSilence;
        public final boolean buffering;

        public PlaybackSnapshot(float position, float duration, float rate, boolean ready, boolean playWhenReady) {
            this(position, duration, rate, ready, playWhenReady, 1f, false, false);
        }

        public PlaybackSnapshot(float position, float duration, float rate, boolean ready,
                                boolean playWhenReady, float speed, boolean skipSilence, boolean buffering) {
            this.position = position;
            this.duration = duration;
            this.rate = rate;
            this.ready = ready;
            this.playWhenReady = playWhenReady;
            this.speed = speed;
            this.skipSilence = skipSilence;
            this.buffering = buffering;
        }
    }

    public interface PlaybackInfoListener {
        void onPlaybackInfo(PlaybackSnapshot snapshot);
    }

    public interface VideoSizeListener {
        void onVideoSize(int width, int height, float aspect);
    }

    public interface TitleListener {
        void onTitle(String title);
    }

    public interface EndedListener {
        void onEnded();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Player.Listener listener;
    private ExoPlayer player;
    private Surface pendingSurface;
    private String pendingLocation;
    private float pendingStartSeconds;
    private boolean playQueued;

    public PlaybackInfoListener onPlaybackInfo;
    public VideoSizeListener onVideoSize;
    public TitleListener onTitle;
    public EndedListener onEnded;

    private final Runnable reportTick = new Runnable() {
        @Override
        public void run() {
            reportPlaybackInfo();
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS);
        }
    };

    public AirPlayVideoPlayer(Context context) {
        this.context = context;
        // DexClassLoader mods cannot rely on Media3 Player.Listener default methods —
        // host ExoPlayer invokes them and crashes with AbstractMethodError. A Proxy
        // implements every interface method against the host ClassLoader.
        this.listener = createHostSafeListener();
    }

    private Player.Listener createHostSafeListener() {
        final AirPlayVideoPlayer self = this;
        ClassLoader cl = Player.Listener.class.getClassLoader();
        return (Player.Listener) Proxy.newProxyInstance(
                cl,
                new Class<?>[]{Player.Listener.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("toString".equals(name)) {
                            return "AirPlayVideoPlayer.HostSafeListener";
                        }
                        try {
                            if ("onPlayerError".equals(name) && args != null && args.length >= 1
                                    && args[0] instanceof PlaybackException) {
                                Log.w(TAG, "playback error", (PlaybackException) args[0]);
                                EndedListener l = self.onEnded;
                                if (l != null) l.onEnded();
                            } else if ("onPlaybackStateChanged".equals(name)
                                    && args != null && args.length >= 1 && args[0] instanceof Integer) {
                                if ((Integer) args[0] == Player.STATE_ENDED) {
                                    EndedListener l = self.onEnded;
                                    if (l != null) l.onEnded();
                                }
                            } else if ("onTimelineChanged".equals(name)) {
                                self.reportPlaybackInfo();
                            } else if ("onMediaMetadataChanged".equals(name)
                                    && args != null && args.length >= 1
                                    && args[0] instanceof MediaMetadata) {
                                TitleListener l = self.onTitle;
                                if (l != null) {
                                    CharSequence t = ((MediaMetadata) args[0]).title;
                                    l.onTitle(t != null ? t.toString() : null);
                                }
                            } else if ("onVideoSizeChanged".equals(name)
                                    && args != null && args.length >= 1
                                    && args[0] instanceof VideoSize) {
                                VideoSize videoSize = (VideoSize) args[0];
                                if (videoSize.height != 0) {
                                    int width = (int) (videoSize.width * videoSize.pixelWidthHeightRatio);
                                    VideoSizeListener l = self.onVideoSize;
                                    if (l != null) {
                                        l.onVideoSize(width, videoSize.height,
                                                (float) width / videoSize.height);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "listener dispatch failed: " + name, t);
                        }
                        return defaultReturn(method.getReturnType());
                    }
                });
    }

    private static Object defaultReturn(Class<?> rt) {
        if (rt == null || rt == void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return (char) 0;
        return null;
    }

    public void play(final String location, final float startPositionSeconds) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                pendingLocation = location;
                pendingStartSeconds = startPositionSeconds;
                playQueued = true;
                // Wait for a valid overlay surface — starting ExoPlayer without one
                // races SurfaceView creation and commonly crashes on mode switch.
                if (pendingSurface != null && pendingSurface.isValid()) {
                    startQueuedPlayback();
                } else {
                    Log.i(TAG, "play queued until surface ready");
                }
            }
        });
    }

    public void scrub(final float positionSeconds) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (player != null) player.seekTo((long) (positionSeconds * 1000));
            }
        });
    }

    public void setScrubbing(final boolean enabled) {
        // Scrubbing-mode API is optional / unstable across media3 versions.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ExoPlayer p = player;
                if (p == null) return;
                try {
                    p.getClass().getMethod("setScrubbingModeEnabled", boolean.class)
                            .invoke(p, enabled);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void setRate(final float rate) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ExoPlayer p = player;
                if (p == null) return;
                if (rate <= 0f) {
                    p.setPlayWhenReady(false);
                } else {
                    p.setPlaybackParameters(new PlaybackParameters(Math.max(0.1f, rate)));
                    p.setPlayWhenReady(true);
                }
            }
        });
    }

    public void setPlaying(final boolean playing) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                if (player != null) player.setPlayWhenReady(playing);
            }
        });
    }

    public void setSpeed(final float speed) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (player != null) {
                    player.setPlaybackParameters(new PlaybackParameters(Math.max(0.1f, speed)));
                }
            }
        });
    }

    public void setSkipSilence(final boolean enabled) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ExoPlayer p = player;
                if (p == null) return;
                try {
                    p.getClass().getMethod("setSkipSilenceEnabled", boolean.class).invoke(p, enabled);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void seekBy(final long deltaMs) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ExoPlayer p = player;
                if (p == null) return;
                long target = Math.max(0L, p.getCurrentPosition() + deltaMs);
                long duration = p.getDuration();
                if (duration != C.TIME_UNSET) target = Math.min(target, duration);
                p.seekTo(target);
            }
        });
    }

    public void setSurface(final Surface surface) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                pendingSurface = surface;
                if (player != null) {
                    player.setVideoSurface(surface);
                } else if (playQueued && surface != null && surface.isValid()) {
                    startQueuedPlayback();
                }
            }
        });
    }

    public void clearSurface(final Surface surface) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                if (pendingSurface != surface) return;
                pendingSurface = null;
                if (player != null) player.setVideoSurface(null);
            }
        });
    }

    /** Run immediately on the main looper — required before WM detach/reparent. */
    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private void startQueuedPlayback() {
        String location = pendingLocation;
        if (location == null || location.isEmpty()) return;
        if (pendingSurface == null || !pendingSurface.isValid()) return;
        stopInternal(false);
        playQueued = false;
        ExoPlayer p = new ExoPlayer.Builder(context).build();
        p.addListener(listener);
        p.setVideoSurface(pendingSurface);
        player = p;
        p.setMediaItem(MediaItem.fromUri(location), (long) (pendingStartSeconds * 1000));
        p.setPlayWhenReady(true);
        p.prepare();
        PlaybackInfoListener cb = onPlaybackInfo;
        if (cb != null) {
            cb.onPlaybackInfo(new PlaybackSnapshot(pendingStartSeconds, 0f, 0f, false, true));
        }
        mainHandler.postDelayed(reportTick, REPORT_INTERVAL_MS);
        Log.i(TAG, "playback started: " + location);
    }

    public void stop() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                stopInternal(true);
            }
        });
    }

    private void stopInternal(boolean reportStopped) {
        mainHandler.removeCallbacks(reportTick);
        playQueued = false;
        pendingLocation = null;
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
        if (reportStopped) {
            PlaybackInfoListener cb = onPlaybackInfo;
            if (cb != null) cb.onPlaybackInfo(new PlaybackSnapshot(0f, -1f, 0f, false, false));
        }
    }

    private void reportPlaybackInfo() {
        ExoPlayer p = player;
        if (p == null) return;
        long durationMs = p.getDuration();
        float position = p.getCurrentPosition() / 1000f;
        float duration = durationMs == C.TIME_UNSET ? 0f : durationMs / 1000f;
        float rate = (p.getPlayWhenReady() && p.getPlaybackState() == Player.STATE_READY)
                ? p.getPlaybackParameters().speed : 0f;
        boolean live = false;
        try {
            Object v = p.getClass().getMethod("isCurrentMediaItemLive").invoke(p);
            live = v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
        }
        boolean ready = live
                ? p.getPlaybackState() == Player.STATE_READY
                : durationMs != C.TIME_UNSET;
        boolean skipSilence = false;
        try {
            Object v = p.getClass().getMethod("getSkipSilenceEnabled").invoke(p);
            skipSilence = v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
        }
        PlaybackInfoListener cb = onPlaybackInfo;
        if (cb == null) return;
        cb.onPlaybackInfo(new PlaybackSnapshot(
                position,
                duration,
                rate,
                ready,
                p.getPlayWhenReady(),
                p.getPlaybackParameters().speed,
                skipSilence,
                p.getPlaybackState() == Player.STATE_BUFFERING));
    }
}
