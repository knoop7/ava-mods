package com.ava.mods.airplay;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.ava.mods.airplay.audio.TrackInfo;
import com.ava.mods.airplay.audio.PcmLevelMeter;
import com.ava.mods.airplay.ui.AudioWaveShadowView;
import com.ava.mods.airplay.ui.CautiousMarqueeText;
import com.ava.mods.airplay.ui.VideoContentScale;
import com.ava.mods.airplay.ui.VideoIcons;

import java.io.InputStream;
import java.util.Locale;

/**
 * AirPlay overlay chrome:
 * <ul>
 *   <li>HLS video — video controls (separate from audio)</li>
 *   <li>Mirroring fullscreen</li>
 *   <li>Audio — 1:1 with DLNA {@code CinemaOverlay} AUDIO layout</li>
 * </ul>
 */
public final class AirPlayOverlay {

    private static final String TAG = "AirPlayOverlay";
    /** Same asset path convention as DLNA CinemaOverlay ({@code assets/…} in the mod jar). */
    private static final String BADGE_ASSET = "assets/airplay-icon.png";
    private static final float BADGE_SIZE_VMIN = 0.11f;
    private static final float BADGE_EDGE_VMIN = 0.05f;
    private static final float BADGE_SIZE_MIN_DP = 32f;
    private static final float BADGE_SIZE_MAX_DP = 48f;
    private static final float BADGE_EDGE_MIN_DP = 32f;
    private static final int BADGE_ALPHA = 64;
    /** Match DLNA CinemaOverlay bottom bar side margin. */
    private static final int BOTTOM_BAR_SIDE_MARGIN_DP = 20;
    /**
     * Minimum L/R clip inset for title/artist marquee (scaled up on large screens).
     */
    private static final int AUDIO_META_INSET_MIN_DP = 40;
    private static final String HOME_PREFS_NAME = "ava_home_prefs";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final int ACCENT_BLUE = 0xFF0417E0;
    private static final int ACCENT_BROWN = 0xFFA78B73;
    /** Upstream {@code VIDEO_OVERLAY_HIDE_MS}. */
    private static final long VIDEO_OVERLAY_HIDE_MS = 4000L;
    /** Upstream FullscreenVideo control auto-hide. */
    private static final long MIRROR_CONTROLS_HIDE_MS = 8000L;
    private static final long AUDIO_CHROME_HIDE_MS = 4000L;
    private static final long PROGRESS_TICK_MS = 250L;
    /** Fade for root overlay container (match DLNA CinemaOverlay). */
    private static final long OVERLAY_FADE_MS = 380L;
    /** Soft chrome fade — gentle hide/show for all video controls. */
    private static final long CHROME_FADE_MS = 340L;
    /** Soft cover crossfade between tracks. */
    private static final int COVER_CROSSFADE_MS = 450;
    /** Hide floating window after this idle while audio is not playing. */
    private static final long AUDIO_IDLE_HIDE_MS = 30_000L;
    /** Custom PiP shell (overlay cannot use Activity Picture-in-Picture). */
    private static final float FLOAT_WIDTH_FRACTION = 0.38f;
    private static final int FLOAT_MIN_WIDTH_DP = 180;
    private static final int FLOAT_MAX_WIDTH_DP = 320;
    private static final int FLOAT_MARGIN_DP = 12;
    private static final int FLOAT_CORNER_DP = 16;
    /** Re-pin dedicated PiP window above Ava overlays while floating. */
    private static final long FLOAT_Z_ORDER_KEEPALIVE_MS = 900L;
    private static final String ACTION_REASSERT_FOREGROUND_OVERLAYS =
            "com.example.ava.action.REASSERT_FOREGROUND_OVERLAYS";
    private static final String AVA_PACKAGE = "com.example.ava";
    private static final long OVERLAY_TOAST_MS = 2800L;
    private static final int OVERLAY_TOAST_BG = 0xCC000000;
    private static final int OVERLAY_TOAST_CORNER_DP = 14;
    private static final int OVERLAY_TOAST_MAX_WIDTH_DP = 320;

    public interface Callback {
        void onClose();
        void onPlayPause();
        void onStop();
        void onSeek(long positionMs);
        void onSeekStart();
        void onSeekEnd();
        void onPrevious();
        void onNext();
        void onAudioScanBegin(boolean forward);
        void onAudioScanEnd();
        void onVolumeDown();
        void onMuteToggle();
        void onVolumeUp();
        /** Open speed sheet (overlay handles UI); Manager may no-op. */
        void onSpeedClick();
        void onSetVideoSpeed(float speed);
        void onSetSkipSilence(boolean enabled);
        void onCopyUrl();
        void onDownloadClick();
        void onRotateClick();
        /** Enter video: default landscape (not remembered across sessions). */
        void onApplyDefaultVideoOrientation();
        /** Leave video UI: unlock Activity orientation without forcing a flip (not persisted). */
        void onReleaseVideoOrientation();
        void onLockClick();
        void onContentScaleClick();
        void onPipClick();
        void onSurfaceReady(Surface surface, boolean forVideoPlayback);
        void onSurfaceDestroyed(Surface surface, boolean forVideoPlayback);
    }

    private enum Mode { IDLE, MIRRORING, VIDEO, AUDIO }

    private final Context appContext;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;

    private FrameLayout root;
    /** Dedicated WM shell for video PiP — separate addView stack entry, always raised on top. */
    private FrameLayout floatRoot;
    private SurfaceView mirrorSurface;
    /** HLS video — TextureView like DLNA so WM remove+add can raise z-order safely. */
    private TextureView videoTexture;
    private WindowManager.LayoutParams layoutParams;
    private WindowManager.LayoutParams floatLayoutParams;
    private boolean floatWindowAttached = false;

    // --- mode containers (only one VISIBLE) ---
    private FrameLayout videoChrome;      // HLS video controls
    private FrameLayout mirrorChrome;     // FullscreenVideo chrome
    private FrameLayout nowPlaying;      // DLNA Cinema AUDIO layout (not upstream NowPlaying card)

    // video chrome
    private View videoDim;
    private TextView videoTitleView;
    private View videoPlayPauseBtn;
    private ImageView videoPlayPauseIcon;
    private TextView videoTimeView;
    private TextView videoDurationView;
    private SeekBar videoSeekBar;
    private View videoTopBar;
    private View videoBottomBar;
    private View videoCenterPlay;
    private View videoCopyBtn;
    private View videoDownloadBtn;
    private ProgressBar videoDownloadSpinner;
    private ProgressBar videoBuffering;
    private View videoUnlockBtn;
    private ImageView videoScaleIcon;
    private View videoPipBtn;
    private FrameLayout videoSpeedSheet;
    private TextView videoSpeedValue;
    private SeekBar videoSpeedSeek;
    private LinearLayout videoSpeedPresets;
    private Switch videoSkipSilence;
    private boolean showRemaining = false;
    private boolean videoChromeVisible = true;
    private boolean videoControlsLocked = false;
    /** Unlock pill shown by tap while locked; independent of chrome auto-hide. */
    private boolean unlockAffordanceVisible = false;
    private boolean videoSpeedSheetVisible = false;
    private boolean videoFloating = false;
    /** True while swapping WM shells — suppress duplicate surface teardown. */
    private boolean surfaceWindowSwapInProgress = false;
    private boolean resumePlaybackAfterSurfaceSwap = false;
    private View floatDragOverlay;
    private RoundCornerMaskView floatCornerMask;
    private float floatDownRawX;
    private float floatDownRawY;
    private int floatDownParamX;
    private int floatDownParamY;
    private boolean floatMoved;
    private VideoContentScale videoContentScale = VideoContentScale.BEST_FIT;

    // mirror chrome
    private View mirrorTopRow;
    private TextView mirrorResLabel;
    private boolean mirrorChromeVisible = true;

    // now playing
    private ImageView coverView;
    private View coverVignette;
    /** Flat dark scrim for classic MA layout (no gradient). */
    private View audioClassicDim;
    private AudioWaveShadowView audioWaveShadow;
    private View audioChromeStrip;
    private LinearLayout audioBottomBar;
    private LinearLayout audioControlsRow;
    private CautiousMarqueeText npTitle;
    private CautiousMarqueeText npSubtitle;
    private final float[] audioLevelUi = new float[32];
    /** Cinema transport — FrameLayout + Material glyphs. */
    private View npPlayPause;
    private View npPrev;
    private View npNext;
    private boolean audioChromeLandscape;
    private TextView npPos;
    private TextView npDur;
    private SeekBar npSeek;
    private LinearLayout audioScrubRow;
    private View audioControlSpacerStart;
    private View audioControlSpacerEnd;
    private boolean audioCenteredLayoutApplied;
    private int audioBarSafeInsetPx;

    /** Top-end brand badge — same placement/alpha math as DLNA CinemaOverlay. */
    private ImageView badgeView;
    private View overlayToast;
    private TextView overlayToastTitle;
    private TextView overlayToastHost;
    private TextView overlayToastDetail;
    private final Runnable hideOverlayToast = new Runnable() {
        @Override
        public void run() {
            View t = overlayToast;
            if (t == null) return;
            t.animate().cancel();
            t.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (overlayToast != null) overlayToast.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    };
    private Bitmap airplayBadgeBitmap;

    private volatile boolean isVisible = false;
    private volatile boolean userSeeking = false;
    private volatile Mode mode = Mode.IDLE;
    private volatile AirPlayEngine boundEngine;

    private Surface mirrorSurfaceRef;
    private Surface videoSurfaceRef;

    private final Runnable hideVideoChrome = new Runnable() {
        @Override
        public void run() {
            setVideoChromeVisible(false);
        }
    };
    private final Runnable hideUnlockAffordance = new Runnable() {
        @Override
        public void run() {
            if (!videoControlsLocked || videoUnlockBtn == null) return;
            unlockAffordanceVisible = false;
            fadeOverlayView(videoUnlockBtn, false, true, 1f, new WantVisible() {
                @Override public boolean get() {
                    return unlockAffordanceVisible;
                }
            });
        }
    };
    private final Runnable hideMirrorChrome = new Runnable() {
        @Override
        public void run() {
            setMirrorChromeVisible(false);
        }
    };
    private final Runnable hideAudioChrome = new Runnable() {
        @Override
        public void run() {
            setAudioChromeVisible(false);
        }
    };
    private boolean audioChromeVisible = false;
    private boolean audioIdleHideArmed = false;
    private Boolean lastVideoPlayingIcon;
    private Boolean lastAudioPlayingIcon;
    private boolean lastCenterPlayShown = false;
    private boolean lastBufferingShown = false;
    private int lastTextureLayoutW = -1;
    private int lastTextureLayoutH = -1;
    private Bitmap lastCoverBitmap;
    private final Runnable hideAudioIdle = new Runnable() {
        @Override
        public void run() {
            if (!isVisible || mode != Mode.AUDIO) return;
            AirPlayEngine e = boundEngine;
            if (e != null && e.isPlaying()) return;
            fadeOutAndHide();
        }
    };
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (!isVisible) return;
            refreshProgress();
            mainHandler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };
    private final Runnable floatZOrderKeeper = new Runnable() {
        @Override
        public void run() {
            if (!videoFloating || !isVisible || !floatWindowAttached) return;
            // Never remove+add PiP while decoding — only nudge Ava's top-tier reassert.
            requestAvaOverlayReassert();
            mainHandler.postDelayed(this, FLOAT_Z_ORDER_KEEPALIVE_MS);
        }
    };

    public AirPlayOverlay(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        this.videoContentScale = loadVideoContentScale();
    }

    public boolean isVisible() { return isVisible; }

    public void show(final AirPlayEngine engine) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                showInternal(engine);
            }
        });
    }

    public void hide() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                fadeOutAndHide();
            }
        });
    }

    /** Instant hide without fade — used when tearing down the window tree. */
    public void hideImmediate() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                hideInternal();
            }
        });
    }

    public void fadeOutAndHide() {
        mainHandler.removeCallbacks(hideAudioIdle);
        audioIdleHideArmed = false;
        FrameLayout r = root;
        if (r == null || !isVisible) {
            hideInternal();
            return;
        }
        if (!r.isAttachedToWindow()) {
            hideInternal();
            return;
        }
        r.animate().cancel();
        r.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        hideInternal();
                    }
                })
                .start();
    }

    public void bringToFront() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (videoFloating && floatWindowAttached) {
                    bringFloatWindowToFront(false);
                    return;
                }
                FrameLayout r = root;
                WindowManager.LayoutParams lp = layoutParams;
                if (r == null || lp == null || !isVisible) return;
                try {
                    if (!r.isAttachedToWindow()) {
                    windowManager.addView(r, lp);
                        applyImmersive(r);
                        return;
                    }
                    if (hasLiveDecodeSurface()) {
                        raiseOverlayWindowZOrderSafely(r, lp);
                    } else {
                        raiseOverlayWindowZOrder(r, lp);
                    }
                    applyImmersive(r);
                } catch (Throwable t) {
                    Log.w(TAG, "bringToFront failed", t);
                }
            }
        });
    }

    /** True while HLS video or mirror has an active Surface bound to a decoder. */
    private boolean hasLiveDecodeSurface() {
        if (mode == Mode.MIRRORING && mirrorSurfaceRef != null) return true;
        if (mode == Mode.VIDEO && videoSurfaceRef != null) return true;
        return false;
    }

    /**
     * Proactively drop decoder surfaces before WM detach so MediaCodec never renders
     * into a disconnected target (EINVAL / surface disconnected).
     */
    private void detachLiveSurfacesBeforeWindowReattach() {
        if (mode == Mode.VIDEO && videoSurfaceRef != null) {
            Surface s = videoSurfaceRef;
            videoSurfaceRef = null;
            callback.onSurfaceDestroyed(s, true);
        }
        if (mode == Mode.MIRRORING && mirrorSurfaceRef != null) {
            Surface s = mirrorSurfaceRef;
            mirrorSurfaceRef = null;
            callback.onSurfaceDestroyed(s, false);
        }
    }

    /**
     * Pause decode and synchronously clear the player surface before any WM
     * reparent/remove — posted clearSurface would race TextureView teardown.
     */
    private void beginWindowSurfaceSwap() {
        resumePlaybackAfterSurfaceSwap = boundEngine != null
                && boundEngine.getVideoPlaybackInfo().playing;
        surfaceWindowSwapInProgress = true;
        if (resumePlaybackAfterSurfaceSwap && boundEngine != null) {
            boundEngine.setVideoPlaying(false);
        }
        detachLiveSurfacesBeforeWindowReattach();
    }

    private void finishWindowSurfaceSwapIfNeeded() {
        if (!surfaceWindowSwapInProgress) return;
        surfaceWindowSwapInProgress = false;
        if (resumePlaybackAfterSurfaceSwap && boundEngine != null) {
            resumePlaybackAfterSurfaceSwap = false;
            boundEngine.setVideoPlaying(true);
        } else {
            resumePlaybackAfterSurfaceSwap = false;
        }
    }

    /** Rebind when TextureView survives reparent without firing Available. */
    private void rebindVideoSurfaceIfNeeded() {
        if (mode != Mode.VIDEO || videoTexture == null || videoSurfaceRef != null) return;
        if (!videoTexture.isAvailable()) return;
        android.graphics.SurfaceTexture st = videoTexture.getSurfaceTexture();
        if (st == null) return;
        Surface s = new Surface(st);
        videoSurfaceRef = s;
        callback.onSurfaceReady(s, true);
        finishWindowSurfaceSwapIfNeeded();
    }

    /** Raise z-order via remove+add — audio/idle chrome only. */
    private void raiseOverlayWindowZOrder(FrameLayout r, WindowManager.LayoutParams lp) {
        if (r == null || lp == null || !r.isAttachedToWindow()) return;
        int vis = r.getVisibility();
        float alpha = r.getAlpha();
        windowManager.removeView(r);
        windowManager.addView(r, lp);
        r.setVisibility(vis);
        r.setAlpha(alpha);
    }

    /** remove+add with explicit surface teardown for live HLS / mirror decode. */
    private void raiseOverlayWindowZOrderSafely(FrameLayout r, WindowManager.LayoutParams lp) {
        if (r == null || lp == null || !r.isAttachedToWindow()) return;
        detachLiveSurfacesBeforeWindowReattach();
        raiseOverlayWindowZOrder(r, lp);
    }

    public void reassertImmersive() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (root != null && isVisible) applyImmersive(root);
            }
        });
    }

    public void refreshFromEngine(final AirPlayEngine engine) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isVisible || engine == null) return;
                boundEngine = engine;
                updateMode(engine);
            }
        });
    }

    private void showInternal(AirPlayEngine engine) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted");
            return;
        }
        boundEngine = engine;
        if (root == null) {
            buildUi();
            layoutParams = createLayoutParams();
        }
        FrameLayout r = root;
        WindowManager.LayoutParams lp = layoutParams;
        if (r == null || lp == null) return;
        r.animate().cancel();
        if (!r.isAttachedToWindow()) {
            try {
                r.setAlpha(0f);
                windowManager.addView(r, lp);
                isVisible = true;
                r.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
            } catch (Throwable t) {
                Log.e(TAG, "addView failed", t);
                return;
            }
        } else {
            isVisible = true;
            if (r.getAlpha() < 1f) {
                r.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
            }
        }
        applyImmersive(r);
        updateMode(engine);
        mainHandler.removeCallbacks(progressTick);
        mainHandler.post(progressTick);
        scheduleAudioIdleHide(engine);
    }

    private void hideInternal() {
        mainHandler.removeCallbacks(hideOverlayToast);
        mainHandler.removeCallbacks(hideVideoChrome);
        mainHandler.removeCallbacks(hideUnlockAffordance);
        mainHandler.removeCallbacks(hideMirrorChrome);
        mainHandler.removeCallbacks(hideAudioChrome);
        mainHandler.removeCallbacks(hideAudioIdle);
        mainHandler.removeCallbacks(progressTick);
        stopFloatZOrderKeeper();
        audioIdleHideArmed = false;
        if (mode == Mode.VIDEO) {
            callback.onReleaseVideoOrientation();
        }
        detachFloatWindowInternal();
        FrameLayout r = root;
        if (r != null) {
            r.animate().cancel();
        try {
                if (r.isAttachedToWindow()) windowManager.removeView(r);
        } catch (Throwable ignored) {}
        }
        root = null;
        layoutParams = null;
        floatRoot = null;
        floatLayoutParams = null;
        floatWindowAttached = false;
        mirrorSurface = null;
        videoTexture = null;
        videoChrome = null;
        mirrorChrome = null;
        nowPlaying = null;
        coverView = null;
        badgeView = null;
        overlayToast = null;
        floatDragOverlay = null;
        floatCornerMask = null;
        videoSpeedSeek = null;
        videoSpeedPresets = null;
        lastCoverBitmap = null;
        mirrorSurfaceRef = null;
        videoSurfaceRef = null;
        isVisible = false;
        unbindAudioLevelMeter();
        boundEngine = null;
        mode = Mode.IDLE;
        videoControlsLocked = false;
        unlockAffordanceVisible = false;
        videoSpeedSheetVisible = false;
        videoFloating = false;
    }

    private void updateMode(AirPlayEngine engine) {
        Mode next = resolveMode(engine);
        Mode prev = mode;
        mode = next;
        if (prev == Mode.VIDEO && next != Mode.VIDEO) {
            // Match upstream DisposableEffect onDispose: release lock, keep current form.
            callback.onReleaseVideoOrientation();
        }
        if (next != Mode.VIDEO && videoFloating) {
            setVideoFloating(false);
        }
        applySurfaceVisibility(next);
        applyExclusiveChrome(next);

        if (next == Mode.VIDEO) {
            bindVideoChrome(engine);
            applyVideoContentScale();
            if (prev != Mode.VIDEO) {
                // Each video session starts landscape; manual rotate is session-only.
                callback.onApplyDefaultVideoOrientation();
                videoControlsLocked = loadVideoControlsLocked();
                if (!videoFloating) {
                    if (videoControlsLocked) {
                        applyVideoLockState();
                    } else {
                        setVideoChromeVisible(true);
                        scheduleVideoChromeHide(engine);
                    }
                }
            }
            // Logo is music-only — never on video.
            updateBadgeVisibility(false, false);
        } else if (next == Mode.MIRRORING) {
            bindMirrorChrome(engine);
            if (prev != Mode.MIRRORING) {
                setMirrorChromeVisible(true);
                scheduleMirrorChromeHide();
            }
            updateBadgeVisibility(false, false);
        } else if (next == Mode.AUDIO) {
            bindNowPlaying(engine);
            // DLNA AUDIO: bottom bar always on; top strip can idle-hide; badge always on.
            if (audioBottomBar != null) {
                audioBottomBar.setVisibility(View.VISIBLE);
                audioBottomBar.setAlpha(1f);
                audioBottomBar.setTranslationY(0f);
            }
            if (prev != Mode.AUDIO) {
                setAudioChromeVisible(true);
                scheduleAudioChromeHide();
            }
            updateBadgeVisibility(true, false);
        } else {
            updateBadgeVisibility(false, false);
        }
        refreshProgress();
    }

    /**
     * One presentation only: video chrome / mirror chrome / music cinema never paint together.
     * SurfaceView z-order makes dual-visible layers fight on device.
     */
    private void applyExclusiveChrome(Mode next) {
        boolean showVideo = next == Mode.VIDEO;
        boolean showMirror = next == Mode.MIRRORING;
        boolean showAudio = next == Mode.AUDIO;

        if (videoChrome != null) {
            videoChrome.setVisibility(showVideo && !videoFloating ? View.VISIBLE : View.GONE);
        }
        if (videoUnlockBtn != null && !showVideo) {
            videoUnlockBtn.setVisibility(View.GONE);
        }
        if (videoSpeedSheet != null && !showVideo) {
            videoSpeedSheet.setVisibility(View.GONE);
            videoSpeedSheetVisible = false;
        }
        if (mirrorChrome != null) {
            mirrorChrome.setVisibility(showMirror ? View.VISIBLE : View.GONE);
        }
        if (nowPlaying != null) {
            nowPlaying.setVisibility(showAudio ? View.VISIBLE : View.GONE);
        }
        if (!showAudio) {
            mainHandler.removeCallbacks(hideAudioChrome);
            mainHandler.removeCallbacks(hideAudioIdle);
            audioIdleHideArmed = false;
        }
        if (!showVideo) {
            mainHandler.removeCallbacks(hideVideoChrome);
            if (floatCornerMask != null) floatCornerMask.setVisibility(View.GONE);
            if (floatDragOverlay != null) floatDragOverlay.setVisibility(View.GONE);
        }
        if (!showMirror) {
            mainHandler.removeCallbacks(hideMirrorChrome);
        }
    }

    private Mode resolveMode(AirPlayEngine engine) {
        // Match upstream MainScreen: video session wins over music so UIs never stack.
        if (engine.isVideoPlaybackActive() || engine.videoSessionPending()) return Mode.VIDEO;
        if (engine.isMirroringActive()) return Mode.MIRRORING;
        if (engine.isAudioOnly()) return Mode.AUDIO;
        return Mode.IDLE;
    }

    private void applySurfaceVisibility(Mode next) {
        boolean wantMirror = next == Mode.MIRRORING;
        boolean wantVideo = next == Mode.VIDEO;
        if (mirrorSurface != null) {
            if (!wantMirror) {
                if (mirrorSurface.getVisibility() == View.VISIBLE || mirrorSurfaceRef != null) {
                    Surface s = mirrorSurfaceRef;
                    if (s != null) callback.onSurfaceDestroyed(s, false);
                    mirrorSurfaceRef = null;
                }
                mirrorSurface.setVisibility(View.GONE);
            } else if (mirrorSurface.getVisibility() != View.VISIBLE) {
                mirrorSurface.setVisibility(View.VISIBLE);
            }
        }
        if (videoTexture != null) {
            if (!wantVideo) {
                if (videoTexture.getVisibility() == View.VISIBLE || videoSurfaceRef != null) {
                    Surface s = videoSurfaceRef;
                    if (s != null) callback.onSurfaceDestroyed(s, true);
                    videoSurfaceRef = null;
                }
                videoTexture.setVisibility(View.GONE);
            } else if (videoTexture.getVisibility() != View.VISIBLE) {
                videoTexture.setVisibility(View.VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------
    // Bind metadata — same fields as upstream Compose
    // ------------------------------------------------------------------

    private void bindVideoChrome(AirPlayEngine engine) {
        String title = engine.getVideoTitle();
        if (title == null || title.isEmpty()) {
            TrackInfo t = engine.getTrackInfo();
            title = t.title.isEmpty() ? "AirPlay Video" : t.title;
        }
        if (videoTitleView != null) videoTitleView.setText(title);
        AirPlayEngine.VideoPlaybackInfo info = engine.getVideoPlaybackInfo();
        updateVideoPlayPauseIcon(info);
        String loc = engine.getVideoLocation();
        if (videoCopyBtn != null) {
            videoCopyBtn.setVisibility(loc != null && !loc.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (videoDownloadBtn != null) {
            videoDownloadBtn.setVisibility(info.durationMs > 0 ? View.VISIBLE : View.GONE);
        }
        Integer dl = null;
        try {
            dl = engine.getVideoDownloader().getProgress();
        } catch (Throwable ignored) {}
        updateDownloadProgress(dl);
        updateVideoPlayPauseIcon(info);
        syncVideoCenterPlayAndBuffering(false);
    }

    private void updateVideoPlayPauseIcon(AirPlayEngine.VideoPlaybackInfo info) {
        if (videoPlayPauseIcon == null || info == null) return;
        if (lastVideoPlayingIcon != null && lastVideoPlayingIcon == info.playing) return;
        lastVideoPlayingIcon = info.playing;
        Drawable d = VideoIcons.load(appContext, info.playing ? "pause" : "play_arrow");
        if (d != null) videoPlayPauseIcon.setImageDrawable(d);
    }

    private void bindMirrorChrome(AirPlayEngine engine) {
        String res = engine.getVideoResolution();
        if (mirrorResLabel != null) {
            if (res != null && !res.isEmpty()) {
                mirrorResLabel.setText(res);
                mirrorResLabel.setVisibility(View.VISIBLE);
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mirrorResLabel != null && mode == Mode.MIRRORING) {
                            mirrorResLabel.setVisibility(View.GONE);
                        }
                    }
                }, 5000);
        } else {
                mirrorResLabel.setVisibility(View.GONE);
            }
        }
    }

    /** Bind audio cinema: centered title / artist, no scrub (MA + iOS same UI). */
    private void bindNowPlaying(AirPlayEngine engine) {
        applyCenteredAudioLayout();
        TrackInfo track = engine.getTrackInfo();
        if (npTitle != null) {
            npTitle.setText(track.title.isEmpty() ? "AirPlay" : track.title);
        }
        if (npSubtitle != null) {
            String artist = track.artist;
            String name = engine.getAdvertisedName();
            npSubtitle.setText(artist != null && !artist.isEmpty()
                    ? artist
                    : (name == null || name.isEmpty() ? "AirPlay" : name));
        }
        if (coverView != null) {
            if (track.coverArt != null) {
                applyCoverArt(track.coverArt);
                coverView.setBackgroundColor(Color.TRANSPARENT);
            } else if (lastCoverBitmap == null) {
                coverView.setImageDrawable(null);
                coverView.setBackgroundColor(0xFF1A1A1A);
            }
            coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverView.setVisibility(View.VISIBLE);
        }
        if (npPlayPause != null) {
            lastAudioPlayingIcon = engine.isPlaying();
            setAudioPlayPauseIcon(engine.isPlaying());
        }
        bindAudioLevelMeter(engine);
        if (audioBottomBar != null) {
            audioBottomBar.setVisibility(View.VISIBLE);
            audioBottomBar.setAlpha(1f);
            audioBottomBar.setTranslationY(0f);
        }
        scheduleAudioIdleHide(engine);
    }

    private void bindAudioLevelMeter(AirPlayEngine engine) {
        if (engine == null) {
            unbindAudioLevelMeter();
            return;
        }
        engine.audioRenderer.setLevelListener(new PcmLevelMeter.Listener() {
            @Override
            public void onLevels(float[] bands01) {
                if (bands01 == null) return;
                int n = Math.min(audioLevelUi.length, bands01.length);
                System.arraycopy(bands01, 0, audioLevelUi, 0, n);
                mainHandler.post(updateAudioWaveUi);
            }
        });
        if (audioWaveShadow != null) {
            audioWaveShadow.setVisibility(View.VISIBLE);
        }
    }

    private void unbindAudioLevelMeter() {
        if (boundEngine != null) {
            boundEngine.audioRenderer.setLevelListener(null);
        }
        if (audioWaveShadow != null) {
            audioWaveShadow.clearLevels();
        }
    }

    private final Runnable updateAudioWaveUi = new Runnable() {
        @Override
        public void run() {
            if (audioWaveShadow != null) {
                audioWaveShadow.setLevels(audioLevelUi);
            }
        }
    };

    /** Soft crossfade like DLNA CinemaOverlay — never blank the cover first. */
    private void applyCoverArt(Bitmap bitmap) {
        if (coverView == null || bitmap == null) return;
        if (bitmap == lastCoverBitmap) return;
        Drawable previous = coverView.getDrawable();
        Drawable next = new BitmapDrawable(appContext.getResources(), bitmap);
        lastCoverBitmap = bitmap;
        if (previous == null
                || (previous instanceof BitmapDrawable
                && ((BitmapDrawable) previous).getBitmap() == null)) {
            coverView.setImageDrawable(next);
            return;
        }
        // Unwrap previous TransitionDrawable end frame if needed.
        if (previous instanceof TransitionDrawable) {
            TransitionDrawable td = (TransitionDrawable) previous;
            previous = td.getDrawable(td.getNumberOfLayers() - 1);
        }
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{previous, next});
        transition.setCrossFadeEnabled(true);
        coverView.setImageDrawable(transition);
        transition.startTransition(COVER_CROSSFADE_MS);
    }

    private void scheduleAudioIdleHide(AirPlayEngine engine) {
        if (engine == null || mode != Mode.AUDIO || engine.isPlaying()) {
            mainHandler.removeCallbacks(hideAudioIdle);
            audioIdleHideArmed = false;
            return;
        }
        if (audioIdleHideArmed) return;
        audioIdleHideArmed = true;
        mainHandler.postDelayed(hideAudioIdle, AUDIO_IDLE_HIDE_MS);
    }

    private void refreshProgress() {
        if (userSeeking || boundEngine == null) return;
        if (mode == Mode.VIDEO) {
            AirPlayEngine.VideoPlaybackInfo info = boundEngine.getVideoPlaybackInfo();
            long pos = info.positionMs;
            long dur = info.durationMs;
            if (videoTimeView != null) {
                videoTimeView.setText(showRemaining && dur > 0
                        ? ("-" + formatVideoTime(Math.max(0, dur - pos)))
                        : formatVideoTime(pos));
            }
            if (videoDurationView != null) videoDurationView.setText(formatVideoTime(dur));
            if (videoSeekBar != null) {
                // Same normalized 0..1000 scrub model as music.
                videoSeekBar.setMax(1000);
                if (dur > 0) {
                    int p = (int) Math.min(1000L, Math.max(0L, pos * 1000L / dur));
                    videoSeekBar.setProgress(p);
                } else {
                    videoSeekBar.setProgress(0);
                }
            }
            // Video icons: only swap when play state changes (Path glyphs, not XML assets).
            updateVideoPlayPauseIcon(info);
            syncVideoCenterPlayAndBuffering(false);
            if (videoDownloadBtn != null) {
                videoDownloadBtn.setVisibility(info.durationMs > 0 ? View.VISIBLE : View.GONE);
            }
        } else if (mode == Mode.AUDIO) {
            // Unified MA/iOS chrome: no scrub — only play-state icon refresh.
            applyCenteredAudioLayout();
            boolean playing = boundEngine.isPlaying();
            if (npPlayPause != null
                    && (lastAudioPlayingIcon == null || lastAudioPlayingIcon != playing)) {
                lastAudioPlayingIcon = playing;
                setAudioPlayPauseIcon(playing);
            }
            applyMusicChromeLayout(false);
            scheduleAudioIdleHide(boundEngine);
        }
    }

    // ------------------------------------------------------------------
    // Chrome visibility (upstream auto-hide)
    // ------------------------------------------------------------------

    private void setVideoChromeVisible(boolean visible) {
        if (videoFloating) {
            // Upstream isInPip: surface only — no chrome.
            visible = false;
        }
        if (videoControlsLocked) {
            // Locked: chrome auto-hide must not reset the unlock affordance fade.
            if (!visible) ensureVideoChromeHiddenWhileLocked(true);
            return;
        }
        videoChromeVisible = visible;
        if (!visible) lastCenterPlayShown = false;
        // Soft fade for all chrome layers (dim / bars / center play).
        fadeOverlayView(videoDim, visible, true, 1f, new WantVisible() {
            @Override public boolean get() {
                return videoChromeVisible && !videoControlsLocked && !videoFloating;
            }
        });
        fadeOverlayView(videoTopBar, visible, true, 1f, new WantVisible() {
            @Override public boolean get() {
                return videoChromeVisible && !videoControlsLocked && !videoFloating;
            }
        });
        fadeOverlayView(videoBottomBar, visible, true, 1f, new WantVisible() {
            @Override public boolean get() {
                return videoChromeVisible && !videoControlsLocked && !videoFloating;
            }
        });
        // Center play fades with chrome; buffering ring stays above chrome hide.
        syncVideoCenterPlayAndBuffering(true);
        // Logo is music-only.
        if (mode == Mode.VIDEO) updateBadgeVisibility(false, false);
    }

    private interface WantVisible {
        boolean get();
    }

    /** Soft alpha fade for chrome / center controls. */
    private void fadeOverlayView(final View view, boolean want, boolean animated,
                                 final float shownAlpha, final WantVisible stillWant) {
        if (view == null) return;
        view.animate().cancel();
        if (want) {
            if (view.getVisibility() == View.VISIBLE
                    && view.getAlpha() >= shownAlpha * 0.99f) {
                return;
            }
            boolean wasHidden = view.getVisibility() != View.VISIBLE;
            view.setVisibility(View.VISIBLE);
            if (animated) {
                // Fresh show: fade from 0. Re-show while partially visible: continue from current alpha.
                if (wasHidden || view.getAlpha() <= 0.05f) {
                    view.setAlpha(0f);
                }
                view.animate()
                        .alpha(shownAlpha)
                        .setDuration(CHROME_FADE_MS)
                        .withEndAction(null)
                        .start();
            } else {
                view.setAlpha(shownAlpha);
            }
            return;
        }
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            return;
        }
        if (!animated || view.getAlpha() <= 0.01f) {
            view.setAlpha(0f);
            view.setVisibility(View.GONE);
            return;
        }
        view.animate()
                .alpha(0f)
                .setDuration(CHROME_FADE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (view == null) return;
                        if (stillWant == null || !stillWant.get()) {
                            view.setVisibility(View.GONE);
                            view.setAlpha(0f);
                        }
                    }
                })
                .start();
    }

    /**
     * Upstream: buffering spinner always wins center slot; PlayPause only when
     * chrome is up and not buffering, with fadeIn/fadeOut.
     */
    private void syncVideoCenterPlayAndBuffering(boolean animated) {
        boolean buffering = mode == Mode.VIDEO
                && boundEngine != null
                && boundEngine.getVideoPlaybackInfo().buffering;
        if (videoBuffering != null && buffering != lastBufferingShown) {
            videoBuffering.setVisibility(buffering ? View.VISIBLE : View.GONE);
            lastBufferingShown = buffering;
        }
        boolean wantPlay = mode == Mode.VIDEO
                && videoChromeVisible
                && !buffering
                && !videoControlsLocked
                && !videoFloating;
        boolean stateChanged = wantPlay != lastCenterPlayShown;
        setCenterPlayVisible(wantPlay, animated && stateChanged);
        lastCenterPlayShown = wantPlay;
    }

    private void setCenterPlayVisible(final boolean want, boolean animated) {
        fadeOverlayView(videoCenterPlay, want, animated, 1f, new WantVisible() {
            @Override
            public boolean get() {
                return mode == Mode.VIDEO
                        && videoChromeVisible
                        && !videoControlsLocked
                        && !videoFloating
                        && !(boundEngine != null
                        && boundEngine.getVideoPlaybackInfo().buffering);
            }
        });
    }

    public void updateDownloadProgress(Integer progress) {
        if (videoDownloadSpinner == null || videoDownloadBtn == null) return;
        View icon = ((FrameLayout) videoDownloadBtn).getChildAt(0);
        if (progress == null) {
            videoDownloadSpinner.setVisibility(View.GONE);
            if (icon != null) icon.setVisibility(View.VISIBLE);
        } else {
            videoDownloadSpinner.setVisibility(View.VISIBLE);
            if (icon != null) icon.setVisibility(View.GONE);
            if (progress >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                videoDownloadSpinner.setIndeterminate(false);
                videoDownloadSpinner.setMax(100);
                videoDownloadSpinner.setProgress(progress);
            } else {
                videoDownloadSpinner.setIndeterminate(true);
            }
        }
    }

    private void setMirrorChromeVisible(boolean visible) {
        mirrorChromeVisible = visible;
        fadeOverlayView(mirrorTopRow, visible, true, 1f, new WantVisible() {
            @Override public boolean get() {
                return mirrorChromeVisible && mode == Mode.MIRRORING;
            }
        });
        // Logo is music-only.
        if (mode == Mode.MIRRORING) updateBadgeVisibility(false, false);
    }

    private void scheduleVideoChromeHide(AirPlayEngine engine) {
        mainHandler.removeCallbacks(hideVideoChrome);
        if (engine != null && engine.getVideoPlaybackInfo().playing) {
            mainHandler.postDelayed(hideVideoChrome, VIDEO_OVERLAY_HIDE_MS);
        }
    }

    private void scheduleMirrorChromeHide() {
        mainHandler.removeCallbacks(hideMirrorChrome);
        mainHandler.postDelayed(hideMirrorChrome, MIRROR_CONTROLS_HIDE_MS);
    }

    private void onRootTap() {
        if (videoFloating) {
            setVideoFloating(false);
            return;
        }
        if (mode == Mode.VIDEO) {
            if (videoSpeedSheetVisible) {
                setVideoSpeedSheetVisible(false);
                return;
            }
            if (videoControlsLocked) {
                // Locked: tap fades in unlock; idle hides it again.
                showUnlockAffordance();
                return;
            }
            setVideoChromeVisible(!videoChromeVisible);
            if (videoChromeVisible) scheduleVideoChromeHide(boundEngine);
            else mainHandler.removeCallbacks(hideVideoChrome);
        } else if (mode == Mode.MIRRORING) {
            setMirrorChromeVisible(!mirrorChromeVisible);
            if (mirrorChromeVisible) scheduleMirrorChromeHide();
            else mainHandler.removeCallbacks(hideMirrorChrome);
        } else if (mode == Mode.AUDIO) {
            // DLNA AUDIO: toggle top strip; bottom bar stays persistent.
            setAudioChromeVisible(!audioChromeVisible);
            if (audioChromeVisible) scheduleAudioChromeHide();
            else mainHandler.removeCallbacks(hideAudioChrome);
            // Touch resets idle hide; re-arm if still paused.
            mainHandler.removeCallbacks(hideAudioIdle);
            audioIdleHideArmed = false;
            scheduleAudioIdleHide(boundEngine);
        }
    }

    private void setAudioChromeVisible(boolean show) {
        audioChromeVisible = show;
        if (audioChromeStrip == null) return;
        audioChromeStrip.animate().cancel();
        if (show) {
            audioChromeStrip.setVisibility(View.VISIBLE);
            audioChromeStrip.setAlpha(1f);
            audioChromeStrip.setTranslationY(0f);
        } else {
            audioChromeStrip.setVisibility(View.GONE);
        }
        // Bottom bar never hides in AUDIO (DLNA keepControlsVisible).
        if (audioBottomBar != null) {
            audioBottomBar.setVisibility(View.VISIBLE);
            audioBottomBar.setAlpha(1f);
            audioBottomBar.setTranslationY(0f);
        }
    }

    private void scheduleAudioChromeHide() {
        mainHandler.removeCallbacks(hideAudioChrome);
        mainHandler.postDelayed(hideAudioChrome, AUDIO_CHROME_HIDE_MS);
    }

    // ------------------------------------------------------------------
    // UI construction — ports MainScreen / VideoControls / NowPlayingContent
    // ------------------------------------------------------------------

    private void buildUi() {
        final int safe = safeInsetPx();

        FrameLayout frame = new FrameLayout(appContext);
        frame.setBackgroundColor(Color.BLACK);
        frame.setKeepScreenOn(true);
        frame.setFitsSystemWindows(false);
        applyImmersive(frame);
        frame.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if (root != null) applyImmersive(root);
            }
        });
        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRootTap();
            }
        });

        // Mirror SurfaceView (native decode — do not remove+add this window while live)
        SurfaceView mirror = new SurfaceView(appContext);
        mirror.setVisibility(View.GONE);
        mirror.getHolder().addCallback(surfaceCb(false));
        frame.addView(mirror, matchParent());

        // HLS video TextureView — same as DLNA Cinema; WM remove+add can raise z-order.
        TextureView video = new TextureView(appContext);
        video.setVisibility(View.GONE);
        video.setSurfaceTextureListener(videoTextureListener);
        video.setOpaque(true);
        frame.addView(video, matchParent());

        // Floating mini-window: corner mask + drag layer
        floatCornerMask = new RoundCornerMaskView(appContext);
        floatCornerMask.setVisibility(View.GONE);
        floatCornerMask.setClickable(false);
        floatCornerMask.setFocusable(false);
        frame.addView(floatCornerMask, matchParent());

        floatDragOverlay = new View(appContext);
        floatDragOverlay.setVisibility(View.GONE);
        floatDragOverlay.setClickable(true);
        floatDragOverlay.setOnTouchListener(floatingTouch);
        frame.addView(floatDragOverlay, matchParent());

        // Mode chrome layers
        videoChrome = buildVideoChrome(safe);
        videoChrome.setVisibility(View.GONE);
        frame.addView(videoChrome, matchParent());

        mirrorChrome = buildMirrorChrome(safe);
        mirrorChrome.setVisibility(View.GONE);
        frame.addView(mirrorChrome, matchParent());

        nowPlaying = buildCinemaAudio(safe);
        nowPlaying.setVisibility(View.GONE);
        frame.addView(nowPlaying, matchParent());

        // Brand badge (top-end) — music UI only; hidden until AUDIO.
        badgeView = new ImageView(appContext);
        badgeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badgeView.setImageBitmap(loadAirPlayBadgeBitmap());
        badgeView.setAlpha(0f);
        badgeView.setVisibility(View.GONE);
        frame.addView(badgeView);

        // In-overlay toast (above chrome; system Toast is hidden under TYPE_APPLICATION_OVERLAY)
        overlayToast = buildOverlayToast();
        frame.addView(overlayToast);

        root = frame;
        applyBadgeLayout();

        mirrorSurface = mirror;
        videoTexture = video;
    }

    private final TextureView.SurfaceTextureListener videoTextureListener =
            new TextureView.SurfaceTextureListener() {
            @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surfaceTexture,
                                                      int width, int height) {
                    Surface s = new Surface(surfaceTexture);
                    videoSurfaceRef = s;
                    callback.onSurfaceReady(s, true);
                    finishWindowSurfaceSwapIfNeeded();
            }

            @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surfaceTexture,
                                                        int width, int height) {
                    Surface s = videoSurfaceRef;
                    if (s != null && s.isValid()) {
                        callback.onSurfaceReady(s, true);
                    }
                }

            @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surfaceTexture) {
                    if (surfaceWindowSwapInProgress) {
                        videoSurfaceRef = null;
                        return true;
                    }
                    Surface s = videoSurfaceRef;
                    videoSurfaceRef = null;
                    if (s != null) callback.onSurfaceDestroyed(s, true);
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {
                }
            };

    private View buildOverlayToast() {
        LinearLayout card = new LinearLayout(appContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = dp(20);
        int padV = dp(14);
        card.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OVERLAY_TOAST_BG);
        bg.setCornerRadius(dp(OVERLAY_TOAST_CORNER_DP));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            card.setBackground(bg);
        } else {
            card.setBackgroundDrawable(bg);
        }
        card.setElevation(dp(8));
        card.setVisibility(View.GONE);
        card.setAlpha(0f);

        overlayToastTitle = new TextView(appContext);
        overlayToastTitle.setTextColor(Color.WHITE);
        overlayToastTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        overlayToastTitle.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        overlayToastTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayToastTitle.setLetterSpacing(0.06f);
        overlayToastTitle.setAllCaps(true);
        card.addView(overlayToastTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayToastHost = new TextView(appContext);
        overlayToastHost.setTextColor(0xE6FFFFFF);
        overlayToastHost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        overlayToastHost.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        overlayToastHost.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayToastHost.setMaxLines(1);
        overlayToastHost.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overlayToastHost.setPadding(0, dp(8), 0, 0);
        overlayToastHost.setVisibility(View.GONE);
        card.addView(overlayToastHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayToastDetail = new TextView(appContext);
        overlayToastDetail.setTextColor(0x99FFFFFF);
        overlayToastDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        overlayToastDetail.setTypeface(Typeface.MONOSPACE);
        overlayToastDetail.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayToastDetail.setMaxLines(2);
        overlayToastDetail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        overlayToastDetail.setPadding(0, dp(4), 0, 0);
        overlayToastDetail.setVisibility(View.GONE);
        card.addView(overlayToastDetail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        int maxW = dp(OVERLAY_TOAST_MAX_WIDTH_DP);
        card.setMinimumWidth(dp(160));
        card.setLayoutParams(lp);
        // Cap line width (View.setMaximumWidth is not on all stubs).
        int textMax = Math.max(dp(120), maxW - dp(40));
        if (overlayToastHost != null) overlayToastHost.setMaxWidth(textMax);
        if (overlayToastDetail != null) overlayToastDetail.setMaxWidth(textMax);
        return card;
    }

    /** Centered fake toast inside the overlay window (visible above AirPlay chrome). */
    public void showOverlayToast(final String message) {
        showOverlayToast(message, null);
    }

    /** Copy confirmation with host + path composition. */
    public void showCopyToast(final String url) {
        showOverlayToast("Copied", url);
    }

    public void showOverlayToast(final String title, final String detail) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                View t = overlayToast;
                if (t == null || title == null || title.isEmpty()) return;
                mainHandler.removeCallbacks(hideOverlayToast);
                t.animate().cancel();
                if (overlayToastTitle != null) overlayToastTitle.setText(title);
                if (detail != null && !detail.isEmpty()) {
                    applyCopiedUrlLines(detail);
                } else {
                    if (overlayToastHost != null) {
                        overlayToastHost.setText("");
                        overlayToastHost.setVisibility(View.GONE);
                    }
                    if (overlayToastDetail != null) {
                        overlayToastDetail.setText("");
                        overlayToastDetail.setVisibility(View.GONE);
                    }
                }
                t.setVisibility(View.VISIBLE);
                t.setAlpha(0f);
                t.animate().alpha(1f).setDuration(160L).start();
                mainHandler.postDelayed(hideOverlayToast, OVERLAY_TOAST_MS);
            }
        });
    }

    /** Split URL into host (primary) + path/query (secondary mono). */
    private void applyCopiedUrlLines(String url) {
        String raw = url == null ? "" : url.trim();
        String host = null;
        String rest = raw;
        try {
            android.net.Uri uri = android.net.Uri.parse(raw);
            host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                String path = uri.getEncodedPath();
                String query = uri.getEncodedQuery();
                StringBuilder sb = new StringBuilder();
                if (path != null && !path.isEmpty() && !"/".equals(path)) {
                    sb.append(path);
                } else if (path != null) {
                    sb.append(path);
                }
                if (query != null && !query.isEmpty()) {
                    sb.append('?').append(query);
                }
                rest = sb.length() > 0 ? sb.toString() : raw;
            }
        } catch (Throwable ignored) {}

        if (overlayToastHost != null) {
            if (host != null && !host.isEmpty()) {
                overlayToastHost.setText(host);
                overlayToastHost.setVisibility(View.VISIBLE);
            } else {
                overlayToastHost.setText("");
                overlayToastHost.setVisibility(View.GONE);
            }
        }
        if (overlayToastDetail != null) {
            String shown = formatCopiedUrl(host != null && !host.isEmpty() ? rest : raw);
            if (shown.isEmpty()) {
                overlayToastDetail.setVisibility(View.GONE);
            } else {
                overlayToastDetail.setText(shown);
                overlayToastDetail.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Soft middle ellipsis for long path/query lines. */
    private static String formatCopiedUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.length() <= 64) return s;
        return s.substring(0, 36) + "…" + s.substring(s.length() - 20);
    }

    /** Upstream VideoControlsTop + center PlayPause + VideoControlsBottom + speed sheet — 1:1 layout. */
    private FrameLayout buildVideoChrome(int safe) {
        FrameLayout host = new FrameLayout(appContext);

        // Scrim stack — same language as music cinema: soft full dim + top/bottom gradients
        // so chrome stays readable over bright video (flat 30% black was washing out).
        FrameLayout dimHost = new FrameLayout(appContext);
        dimHost.setAlpha(0f);
        dimHost.setVisibility(View.GONE);

        View fullDim = new View(appContext);
        fullDim.setBackgroundColor(0x66000000);
        dimHost.addView(fullDim, matchParent());

        View vignette = new View(appContext);
        GradientDrawable vignetteGd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x22000000, 0x88000000, 0xE0000000});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            vignette.setBackground(vignetteGd);
        }
        dimHost.addView(vignette, matchParent());

        dimHost.addView(makeVideoEdgeGradient(true));
        dimHost.addView(makeVideoEdgeGradient(false));

        videoDim = dimHost;
        host.addView(dimHost, matchParent());

        // --- VideoControlsTop ---
        // padding: systemInsets + horizontal 8dp + bottom 16dp + extraTop 16 if no inset
        int topPad = safe > 0 ? safe : dp(16);
        LinearLayout top = new LinearLayout(appContext);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(safe + dp(8), topPad, safe + dp(8), dp(16));
        top.setAlpha(0f);
        top.setVisibility(View.GONE);
        videoTopBar = top;

        // spacedBy(16.dp): IconButton(48) ArrowBack
        View backBtn = playerIconBtn("arrow_back", new Runnable() {
            @Override public void run() { callback.onStop(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        backLp.rightMargin = dp(16);
        top.addView(backBtn, backLp);

        // titleMedium, weight 1, maxLines 2
        videoTitleView = new TextView(appContext);
        videoTitleView.setTextColor(Color.WHITE);
        videoTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        videoTitleView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        videoTitleView.setMaxLines(2);
        videoTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.rightMargin = dp(16);
        top.addView(videoTitleView, titleLp);

        // Action row spacedBy(8.dp)
        LinearLayout topActions = new LinearLayout(appContext);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.CENTER_VERTICAL);

        View speedBtn = playerIconBtn("speed", new Runnable() {
            @Override public void run() { setVideoSpeedSheetVisible(true); }
        });
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        actLp.rightMargin = dp(8);
        topActions.addView(speedBtn, actLp);

        videoCopyBtn = playerIconBtn("content_copy", new Runnable() {
            @Override public void run() { callback.onCopyUrl(); }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        copyLp.rightMargin = dp(8);
        topActions.addView(videoCopyBtn, copyLp);

        FrameLayout downloadWrap = new FrameLayout(appContext);
        downloadWrap.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        View downloadIcon = playerIconBtn("download", new Runnable() {
            @Override public void run() { callback.onDownloadClick(); }
        });
        downloadWrap.addView(downloadIcon, matchParent());
        videoDownloadSpinner = new ProgressBar(appContext);
        tintAccentProgress(videoDownloadSpinner);
        videoDownloadSpinner.setVisibility(View.GONE);
        downloadWrap.addView(videoDownloadSpinner,
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        videoDownloadBtn = downloadWrap;
        topActions.addView(downloadWrap);
        top.addView(topActions);

        host.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // --- PlayPauseButton: 64dp touch, 48dp icon ---
        FrameLayout playWrap = new FrameLayout(appContext);
        videoPlayPauseIcon = VideoIcons.glyphView(appContext, "pause", 48);
        playWrap.addView(videoPlayPauseIcon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        playWrap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (videoControlsLocked) return;
                callback.onPlayPause();
                if (boundEngine == null) return;
                updateVideoPlayPauseIcon(boundEngine.getVideoPlaybackInfo());
                syncVideoCenterPlayAndBuffering(false);
                if (boundEngine.getVideoPlaybackInfo().playing) {
                    scheduleVideoChromeHide(boundEngine);
                } else {
                    mainHandler.removeCallbacks(hideVideoChrome);
                }
            }
        });
        videoPlayPauseBtn = playWrap;
        videoCenterPlay = playWrap;
        // Hidden until chrome show + not buffering (fades in via syncVideoCenterPlayAndBuffering).
        playWrap.setAlpha(0f);
        playWrap.setVisibility(View.GONE);
        host.addView(playWrap, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        videoBuffering = new ProgressBar(appContext);
        tintAccentProgress(videoBuffering);
        videoBuffering.setVisibility(View.GONE);
        // Added after play so the ring paints above the center button when both briefly overlap.
        host.addView(videoBuffering, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));

        // UnlockButton: deeper black@~0.75 pill; fades with idle hide while locked.
        videoUnlockBtn = playerIconBtn("lock", new Runnable() {
            @Override public void run() {
                mainHandler.removeCallbacks(hideUnlockAffordance);
                setVideoControlsLocked(false);
                setVideoChromeVisible(true);
                scheduleVideoChromeHide(boundEngine);
            }
        });
        GradientDrawable unlockBg = new GradientDrawable();
        unlockBg.setColor(0xBF000000);
        unlockBg.setCornerRadius(dp(24));
        videoUnlockBtn.setBackground(unlockBg);
        videoUnlockBtn.setAlpha(0f);
        videoUnlockBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams unlockLp = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        unlockLp.bottomMargin = dp(48);
        host.addView(videoUnlockBtn, unlockLp);

        // --- VideoControlsBottom ---
        // padding: insets + horizontal 8 + top 16 + bottom 16 if no inset
        int bottomPad = safe > 0 ? safe : dp(16);
        LinearLayout bottom = new LinearLayout(appContext);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(safe + dp(8), dp(16), safe + dp(8), bottomPad);
        bottom.setAlpha(0f);
        bottom.setVisibility(View.GONE);
        videoBottomBar = bottom;

        // Scrub row — same L/R layout as music: pos | seek | dur (+ rotate).
        LinearLayout scrubRow = new LinearLayout(appContext);
        scrubRow.setOrientation(LinearLayout.HORIZONTAL);
        scrubRow.setGravity(Gravity.CENTER_VERTICAL);
        scrubRow.setPadding(dp(8), 0, dp(8), 0);

        videoTimeView = monoText("0:00");
        videoDurationView = monoText("0:00");
        View.OnClickListener toggleRemaining = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRemaining = !showRemaining;
                refreshProgress();
            }
        };
        videoTimeView.setOnClickListener(toggleRemaining);
        videoDurationView.setOnClickListener(toggleRemaining);
        scrubRow.addView(videoTimeView);

        videoSeekBar = new SeekBar(appContext);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        seekLp.leftMargin = dp(10);
        seekLp.rightMargin = dp(10);
        videoSeekBar.setLayoutParams(seekLp);
        videoSeekBar.setMax(1000);
        styleThinSeekBar(videoSeekBar);
        videoSeekBar.setOnSeekBarChangeListener(seekListener(true));
        scrubRow.addView(videoSeekBar);

        scrubRow.addView(videoDurationView);
        bottom.addView(scrubRow);

        // Bottom actions: lock/scale/pip on the left; rotate alone on the right.
        LinearLayout actions = new LinearLayout(appContext);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(4), 0, 0);

        View lockBtn = playerIconBtn("lock_open", new Runnable() {
            @Override public void run() {
                setVideoControlsLocked(true);
                callback.onLockClick();
            }
        });
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lockLp.rightMargin = dp(8);
        actions.addView(lockBtn, lockLp);

        FrameLayout scaleBtn = playerIconBtn(videoContentScale.iconAsset(), new Runnable() {
            @Override public void run() {
                videoContentScale = videoContentScale.next();
                saveVideoContentScale(videoContentScale);
                if (videoScaleIcon != null) {
                    Drawable d = VideoIcons.load(appContext, videoContentScale.iconAsset());
                    if (d != null) videoScaleIcon.setImageDrawable(d);
                }
                lastTextureLayoutW = -1;
                lastTextureLayoutH = -1;
                applyVideoContentScale();
                callback.onContentScaleClick();
                scheduleVideoChromeHide(boundEngine);
            }
        });
        videoScaleIcon = (scaleBtn.getTag() instanceof ImageView)
                ? (ImageView) scaleBtn.getTag() : null;
        if (videoScaleIcon == null && scaleBtn.getChildCount() > 0
                && scaleBtn.getChildAt(0) instanceof ImageView) {
            videoScaleIcon = (ImageView) scaleBtn.getChildAt(0);
        }
        LinearLayout.LayoutParams scaleLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        scaleLp.rightMargin = dp(8);
        actions.addView(scaleBtn, scaleLp);

        videoPipBtn = playerIconBtn("picture_in_picture_alt", new Runnable() {
            @Override public void run() { callback.onPipClick(); }
        });
        if (!supportsPip()) videoPipBtn.setVisibility(View.GONE);
        actions.addView(videoPipBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));

        // Push rotate to the trailing end (bottom-right of the bar).
        View spacer = new View(appContext);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        actions.addView(playerIconBtn("screen_rotation", new Runnable() {
            @Override public void run() { callback.onRotateClick(); }
        }), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bottom.addView(actions);

        host.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        videoSpeedSheet = buildVideoSpeedSheet(safe);
        videoSpeedSheet.setVisibility(View.GONE);
        host.addView(videoSpeedSheet, matchParent());

        top.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scheduleVideoChromeHide(boundEngine); }
        });
        bottom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scheduleVideoChromeHide(boundEngine); }
        });
        return host;
    }


    private FrameLayout buildVideoSpeedSheet(int safe) {
        final FrameLayout sheetHost = new FrameLayout(appContext);
        View scrim = new View(appContext);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setVideoSpeedSheetVisible(false); }
        });
        sheetHost.addView(scrim, matchParent());

        final int accent = resolveAccentColor();

        LinearLayout panel = new LinearLayout(appContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(24), dp(24), dp(24) + safe);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadii(new float[]{
                dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0
        });
        panel.setBackground(bg);

        TextView title = new TextView(appContext);
        title.setText("Playback speed");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        LinearLayout row = new LinearLayout(appContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(playerIconBtn("remove", new Runnable() {
            @Override public void run() {
                float s = boundEngine != null ? boundEngine.getVideoPlaybackInfo().speed : 1f;
                callback.onSetVideoSpeed(Math.max(0.2f, s - 0.1f));
                refreshSpeedSheet();
            }
        }));
        videoSpeedValue = new TextView(appContext);
        videoSpeedValue.setTextColor(Color.WHITE);
        videoSpeedValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        videoSpeedValue.setGravity(Gravity.CENTER);
        row.addView(videoSpeedValue, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(playerIconBtn("add", new Runnable() {
            @Override public void run() {
                float s = boundEngine != null ? boundEngine.getVideoPlaybackInfo().speed : 1f;
                callback.onSetVideoSpeed(Math.min(4f, s + 0.1f));
                refreshSpeedSheet();
            }
        }));
        panel.addView(row);

        LinearLayout sliderRow = new LinearLayout(appContext);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        videoSpeedSeek = new SeekBar(appContext);
        videoSpeedSeek.setMax(38); // 0.2 .. 4.0 step 0.1
        stylePlayerSeekBar(videoSpeedSeek);
        videoSpeedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float speed = 0.2f + progress * 0.1f;
                callback.onSetVideoSpeed(speed);
                if (videoSpeedValue != null) {
                    videoSpeedValue.setText(String.format(Locale.US, "%.1f", speed));
                }
                updateSpeedPresetChips(speed);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { refreshSpeedSheet(); }
        });
        sliderRow.addView(videoSpeedSeek, new LinearLayout.LayoutParams(0, dp(32), 1f));
        sliderRow.addView(playerIconBtn("refresh", new Runnable() {
            @Override public void run() {
                callback.onSetVideoSpeed(1f);
                refreshSpeedSheet();
            }
        }));
        panel.addView(sliderRow);

        videoSpeedPresets = new LinearLayout(appContext);
        videoSpeedPresets.setOrientation(LinearLayout.HORIZONTAL);
        videoSpeedPresets.setPadding(0, dp(8), 0, 0);
        float[] presetVals = {0.5f, 0.75f, 1f, 1.5f, 2f};
        for (final float preset : presetVals) {
            TextView chip = new TextView(appContext);
            chip.setTag(preset);
            chip.setText(formatSpeedChip(preset));
            chip.setTextColor(accent);
            chip.setPadding(dp(10), dp(8), dp(10), dp(8));
            chip.setGravity(Gravity.CENTER);
            applySpeedChipStyle(chip, false, accent);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    callback.onSetVideoSpeed(preset);
                    refreshSpeedSheet();
                }
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp(8);
            videoSpeedPresets.addView(chip, chipLp);
        }
        panel.addView(videoSpeedPresets);

        LinearLayout skipRow = new LinearLayout(appContext);
        skipRow.setOrientation(LinearLayout.HORIZONTAL);
        skipRow.setGravity(Gravity.CENTER_VERTICAL);
        skipRow.setPadding(0, dp(12), 0, 0);
        TextView skipLabel = new TextView(appContext);
        skipLabel.setText("Skip silence");
        skipLabel.setTextColor(Color.WHITE);
        skipLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        skipRow.addView(skipLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        videoSkipSilence = new Switch(appContext);
        tintAccentSwitch(videoSkipSilence, accent);
        videoSkipSilence.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (buttonView.isPressed()) callback.onSetSkipSilence(isChecked);
            }
        });
        skipRow.addView(videoSkipSilence);
        panel.addView(skipRow);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetHost.addView(panel, panelLp);
        return sheetHost;
    }

    private static String formatSpeedChip(float preset) {
        if (Math.abs(preset - Math.round(preset)) < 0.01f) {
            return String.format(Locale.US, "%.0fx", preset);
        }
        return String.format(Locale.US, "%sx", Float.toString(preset));
    }

    private void applySpeedChipStyle(TextView chip, boolean selected, int accent) {
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(999));
        if (selected) {
            chipBg.setColor((accent & 0x00FFFFFF) | 0x33000000);
            chipBg.setStroke(dp(1), accent);
            chip.setTextColor(accent);
        } else {
            chipBg.setColor(Color.TRANSPARENT);
            chipBg.setStroke(dp(1), 0x66FFFFFF);
            chip.setTextColor(accent);
        }
        chip.setBackground(chipBg);
    }

    private void updateSpeedPresetChips(float speed) {
        if (videoSpeedPresets == null) return;
        int accent = resolveAccentColor();
        for (int i = 0; i < videoSpeedPresets.getChildCount(); i++) {
            View child = videoSpeedPresets.getChildAt(i);
            if (!(child instanceof TextView) || !(child.getTag() instanceof Float)) continue;
            float preset = (Float) child.getTag();
            boolean selected = Math.abs(preset - speed) < 0.05f;
            applySpeedChipStyle((TextView) child, selected, accent);
        }
    }

    private void tintAccentSwitch(Switch sw, int accent) {
        if (sw == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ColorStateList thumb = new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{accent, 0xFFBDBDBD});
            ColorStateList track = new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{(accent & 0x00FFFFFF) | 0x66000000, 0x44FFFFFF});
            sw.setThumbTintList(thumb);
            sw.setTrackTintList(track);
        }
    }

    private void refreshSpeedSheet() {
        if (boundEngine == null) return;
        AirPlayEngine.VideoPlaybackInfo info = boundEngine.getVideoPlaybackInfo();
        if (videoSpeedValue != null) {
            videoSpeedValue.setText(String.format(Locale.US, "%.1f", info.speed));
        }
        if (videoSpeedSeek != null) {
            int idx = Math.round((info.speed - 0.2f) / 0.1f);
            videoSpeedSeek.setProgress(Math.max(0, Math.min(videoSpeedSeek.getMax(), idx)));
        }
        updateSpeedPresetChips(info.speed);
        if (videoSkipSilence != null) {
            videoSkipSilence.setChecked(info.skipSilence);
        }
    }

    private void setVideoSpeedSheetVisible(boolean show) {
        videoSpeedSheetVisible = show;
        if (videoSpeedSheet != null) {
            videoSpeedSheet.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (show) {
            refreshSpeedSheet();
            mainHandler.removeCallbacks(hideVideoChrome);
            setVideoChromeVisible(true);
        } else {
            scheduleVideoChromeHide(boundEngine);
        }
        callback.onSpeedClick();
    }

    private void setVideoControlsLocked(boolean locked) {
        videoControlsLocked = locked;
        saveVideoControlsLocked(locked);
        if (locked) {
            mainHandler.removeCallbacks(hideVideoChrome);
            videoChromeVisible = false;
        } else {
            unlockAffordanceVisible = false;
        }
        applyVideoLockState();
    }

    /** Hide chrome layers while locked without disturbing unlock affordance. */
    private void ensureVideoChromeHiddenWhileLocked(boolean animated) {
        videoChromeVisible = false;
        fadeOverlayView(videoDim, false, animated, 1f, null);
        fadeOverlayView(videoTopBar, false, animated, 1f, null);
        fadeOverlayView(videoBottomBar, false, animated, 1f, null);
        setCenterPlayVisible(false, animated);
    }

    private boolean loadVideoControlsLocked() {
        try {
            return appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.VIDEO_CONTROLS_LOCKED, Prefs.DEF_VIDEO_CONTROLS_LOCKED);
        } catch (Throwable ignored) {
            return Prefs.DEF_VIDEO_CONTROLS_LOCKED;
        }
    }

    private void saveVideoControlsLocked(boolean locked) {
        try {
            appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Prefs.VIDEO_CONTROLS_LOCKED, locked)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save video lock failed", t);
        }
    }

    private void applyVideoLockState() {
        mainHandler.removeCallbacks(hideUnlockAffordance);
        mainHandler.removeCallbacks(hideVideoChrome);
        if (videoControlsLocked) {
            unlockAffordanceVisible = false;
            ensureVideoChromeHiddenWhileLocked(true);
            if (videoUnlockBtn != null) {
                fadeOverlayView(videoUnlockBtn, false, false, 1f, null);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoUnlockBtn.getLayoutParams();
                if (lp != null) {
                    lp.bottomMargin = dp(48);
                    videoUnlockBtn.setLayoutParams(lp);
                }
            }
            setVideoSpeedSheetVisible(false);
        } else {
            unlockAffordanceVisible = false;
            if (videoUnlockBtn != null) {
                fadeOverlayView(videoUnlockBtn, false, true, 1f, null);
            }
            syncVideoCenterPlayAndBuffering(true);
        }
    }

    /** Fade in unlock pill while locked; auto-hide if untouched. */
    private void showUnlockAffordance() {
        if (!videoControlsLocked || videoUnlockBtn == null || videoFloating) return;
        mainHandler.removeCallbacks(hideUnlockAffordance);
        if (videoUnlockBtn.getVisibility() == View.VISIBLE
                && videoUnlockBtn.getAlpha() >= 0.99f) {
            scheduleUnlockAffordanceHide();
            return;
        }
        unlockAffordanceVisible = true;
        fadeOverlayView(videoUnlockBtn, true, true, 1f, new WantVisible() {
            @Override
            public boolean get() {
                return unlockAffordanceVisible && videoControlsLocked && !videoFloating;
            }
        });
        scheduleUnlockAffordanceHide();
    }

    private void scheduleUnlockAffordanceHide() {
        mainHandler.removeCallbacks(hideUnlockAffordance);
        mainHandler.postDelayed(hideUnlockAffordance, VIDEO_OVERLAY_HIDE_MS);
    }

    private void applyVideoContentScale() {
        if (videoTexture == null || root == null) return;
        final float aspect = boundEngine != null
                ? Math.max(0.1f, boundEngine.getVideoPlaybackAspect())
                : (16f / 9f);
        root.post(new Runnable() {
            @Override
            public void run() {
                if (videoTexture == null || root == null) return;
                int rw = root.getWidth();
                int rh = root.getHeight();
                if (rw <= 0 || rh <= 0) return;
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER);
                switch (videoContentScale) {
                    case STRETCH:
                        lp.width = rw;
                        lp.height = rh;
                        break;
                    case CROP: {
                        if ((float) rw / rh > aspect) {
                            lp.width = rw;
                            lp.height = Math.round(rw / aspect);
                        } else {
                            lp.height = rh;
                            lp.width = Math.round(rh * aspect);
                        }
                        break;
                    }
                    case HUNDRED_PERCENT: {
                        AirPlayEngine.VideoSize sz = boundEngine != null
                                ? boundEngine.getVideoPlaybackSize() : null;
                        if (sz != null && sz.width > 0 && sz.height > 0) {
                            lp.width = sz.width;
                            lp.height = sz.height;
                        } else {
                            // fall through to best fit
                            if ((float) rw / rh > aspect) {
                                lp.height = rh;
                                lp.width = Math.round(rh * aspect);
                            } else {
                                lp.width = rw;
                                lp.height = Math.round(rw / aspect);
                            }
                        }
                        break;
                    }
                    case BEST_FIT:
                    default: {
                        if ((float) rw / rh > aspect) {
                            lp.height = rh;
                            lp.width = Math.round(rh * aspect);
                        } else {
                            lp.width = rw;
                            lp.height = Math.round(rw / aspect);
                        }
                        break;
                    }
                }
                if (lp.width == lastTextureLayoutW && lp.height == lastTextureLayoutH) return;
                videoTexture.setLayoutParams(lp);
                lastTextureLayoutW = lp.width;
                lastTextureLayoutH = lp.height;
            }
        });
    }

    private boolean supportsPip() {
        // Overlay window cannot use Activity Picture-in-Picture; we implement a
        // custom floating shell (upstream chrome still exposes the PiP button).
        return canDrawOverlays();
    }

    /** Toggle custom floating mini-window (upstream PiP behavior for overlay). */
    public void toggleVideoFloating() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mode != Mode.VIDEO || !isVisible) return;
                setVideoFloating(!videoFloating);
            }
        });
    }

    public boolean isVideoFloating() {
        return videoFloating;
    }

    private void setVideoFloating(boolean floating) {
        if (videoFloating == floating) return;
        if (floating && mode != Mode.VIDEO) return;
        if (floating) {
            videoFloating = true;
            enterDedicatedFloatWindow();
        } else {
            videoFloating = false;
            exitDedicatedFloatWindow();
        }
    }

    private void ensureFloatRoot() {
        if (floatRoot != null) return;
        floatRoot = new FrameLayout(appContext);
        floatRoot.setBackgroundColor(Color.BLACK);
        floatRoot.setKeepScreenOn(true);
        floatLayoutParams = createFloatLayoutParams();
    }

    private WindowManager.LayoutParams createFloatLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(200),
                dp(112),
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Ava AirPlay PiP");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private void reparentView(View child, FrameLayout newParent) {
        if (child == null || newParent == null) return;
        android.view.ViewGroup old = (android.view.ViewGroup) child.getParent();
        if (old != null) old.removeView(child);
        newParent.addView(child, matchParent());
    }

    private void reparentFloatViewsTo(FrameLayout parent) {
        reparentView(videoTexture, parent);
        reparentView(floatCornerMask, parent);
        reparentView(floatDragOverlay, parent);
    }

    /** Detach fullscreen root; attach a dedicated PiP WM window (newest addView = top). */
    private void enterDedicatedFloatWindow() {
        FrameLayout r = root;
        if (r == null || videoTexture == null || layoutParams == null) return;
        ensureFloatRoot();

        mainHandler.removeCallbacks(hideVideoChrome);
        setVideoChromeVisible(false);
        if (videoChrome != null) videoChrome.setVisibility(View.GONE);
        if (videoUnlockBtn != null) videoUnlockBtn.setVisibility(View.GONE);
        updateBadgeVisibility(false, false);

        beginWindowSurfaceSwap();
        reparentFloatViewsTo(floatRoot);
        applyFloatingWindowLayout(floatLayoutParams, floatRoot);
        showFloatingHelpers(true);

        try {
            if (r.isAttachedToWindow()) windowManager.removeView(r);
            floatRoot.setAlpha(1f);
            floatRoot.setVisibility(View.VISIBLE);
            windowManager.addView(floatRoot, floatLayoutParams);
            floatWindowAttached = true;
        } catch (Throwable t) {
            Log.w(TAG, "enter dedicated float window failed", t);
            reparentFloatViewsTo(r);
            videoFloating = false;
            floatWindowAttached = false;
            if (!r.isAttachedToWindow()) {
                try {
                    windowManager.addView(r, layoutParams);
                } catch (Throwable ignored) {}
            }
            return;
        }

        floatRoot.post(new Runnable() {
            @Override
            public void run() {
                applyFloatingChrome(floatRoot, true);
                applySurfaceCornerRadius(true);
                if (floatCornerMask != null) floatCornerMask.invalidate();
                rebindVideoSurfaceIfNeeded();
            }
        });
        lastTextureLayoutW = -1;
        lastTextureLayoutH = -1;
        applyVideoContentScale();
        if (boundEngine != null) bindVideoChrome(boundEngine);
        // Fresh addView already tops the stack; let surface rebind before Ava reassert.
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (videoFloating && floatWindowAttached) requestAvaOverlayReassert();
            }
        }, 500L);
        startFloatZOrderKeeper();
    }

    private void exitDedicatedFloatWindow() {
        stopFloatZOrderKeeper();
        FrameLayout r = root;
        if (r == null || layoutParams == null) return;

        beginWindowSurfaceSwap();
        try {
            if (floatRoot != null && floatWindowAttached) {
                windowManager.removeView(floatRoot);
            }
        } catch (Throwable ignored) {}
        floatWindowAttached = false;

        reparentFloatViewsTo(r);
        showFloatingHelpers(false);
        applyFullscreenWindowLayout(layoutParams);
        applyFloatingChrome(r, false);
        applySurfaceCornerRadius(false);
        if (videoChrome != null && mode == Mode.VIDEO) {
            videoChrome.setVisibility(View.VISIBLE);
        }
        setVideoChromeVisible(true);
        scheduleVideoChromeHide(boundEngine);

        try {
            if (!r.isAttachedToWindow()) {
                r.setAlpha(1f);
                windowManager.addView(r, layoutParams);
            } else {
                windowManager.updateViewLayout(r, layoutParams);
            }
            applyImmersive(r);
            clearFloatingTouch(r);
        } catch (Throwable t) {
            Log.w(TAG, "restore fullscreen window failed", t);
        }
        r.post(new Runnable() {
            @Override
            public void run() {
                rebindVideoSurfaceIfNeeded();
            }
        });
        lastTextureLayoutW = -1;
        lastTextureLayoutH = -1;
        applyVideoContentScale();
        if (boundEngine != null) bindVideoChrome(boundEngine);
    }

    private void detachFloatWindowInternal() {
        stopFloatZOrderKeeper();
        if (!floatWindowAttached || floatRoot == null) return;
        beginWindowSurfaceSwap();
        try {
            windowManager.removeView(floatRoot);
        } catch (Throwable ignored) {}
        floatWindowAttached = false;
        surfaceWindowSwapInProgress = false;
        resumePlaybackAfterSurfaceSwap = false;
        FrameLayout r = root;
        if (r != null && videoTexture != null && videoTexture.getParent() == floatRoot) {
            reparentFloatViewsTo(r);
        }
    }

    /** @param forceSurfaceDetach true only for one-shot enter/exit window swaps. */
    private void bringFloatWindowToFront(boolean forceSurfaceDetach) {
        FrameLayout f = floatRoot;
        WindowManager.LayoutParams lp = floatLayoutParams;
        if (f == null || lp == null || !floatWindowAttached) return;
        if (!forceSurfaceDetach && hasLiveDecodeSurface()) {
            try {
                windowManager.updateViewLayout(f, lp);
            } catch (Throwable t) {
                Log.w(TAG, "bring float window layout failed", t);
            }
            return;
        }
        detachLiveSurfacesBeforeWindowReattach();
        int vis = f.getVisibility();
        float alpha = f.getAlpha();
        try {
            windowManager.removeView(f);
            windowManager.addView(f, lp);
            f.setVisibility(vis);
            f.setAlpha(alpha);
        } catch (Throwable t) {
            Log.w(TAG, "bring float window to front failed", t);
        }
    }

    private void requestAvaOverlayReassert() {
        try {
            Intent intent = new Intent(ACTION_REASSERT_FOREGROUND_OVERLAYS);
            intent.setPackage(AVA_PACKAGE);
            appContext.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "overlay reassert broadcast failed", t);
        }
    }

    private void startFloatZOrderKeeper() {
        mainHandler.removeCallbacks(floatZOrderKeeper);
        mainHandler.postDelayed(floatZOrderKeeper, FLOAT_Z_ORDER_KEEPALIVE_MS);
    }

    private void stopFloatZOrderKeeper() {
        mainHandler.removeCallbacks(floatZOrderKeeper);
    }

    private void showFloatingHelpers(boolean show) {
        if (floatCornerMask != null) {
            floatCornerMask.setRadiusPx(dp(FLOAT_CORNER_DP));
            floatCornerMask.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (floatDragOverlay != null) {
            floatDragOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        applySurfaceCornerRadius(show);
        FrameLayout r = floatWindowAttached && floatRoot != null ? floatRoot : root;
        if (r == null) return;
        if (show) {
            r.setOnClickListener(null);
            r.setOnTouchListener(null);
        } else {
            clearFloatingTouch(r);
        }
    }

    private void applySurfaceCornerRadius(boolean floating) {
        if (videoTexture == null) return;
        final float radius = floating ? (float) dp(FLOAT_CORNER_DP) : 0f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (radius > 0f) {
                videoTexture.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
                videoTexture.setClipToOutline(true);
        } else {
                videoTexture.setClipToOutline(false);
                videoTexture.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            }
        }
    }

    private void applyFloatingWindowLayout(WindowManager.LayoutParams lp) {
        applyFloatingWindowLayout(lp, root);
    }

    private void applyFloatingWindowLayout(WindowManager.LayoutParams lp, FrameLayout chromeTarget) {
        DisplayMetrics dm = realDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        float aspect = 16f / 9f;
        if (boundEngine != null) {
            float a = boundEngine.getVideoPlaybackAspect();
            if (a > 0.05f && a < 40f) aspect = a;
            else {
                a = boundEngine.getVideoAspect();
                if (a > 0.05f && a < 40f) aspect = a;
            }
        }
        int targetW = Math.round(Math.min(screenW, screenH) * FLOAT_WIDTH_FRACTION);
        targetW = Math.max(dp(FLOAT_MIN_WIDTH_DP), Math.min(dp(FLOAT_MAX_WIDTH_DP), targetW));
        int w = targetW;
        int h = Math.round(w / aspect);
        int maxH = Math.round(Math.max(screenW, screenH) * 0.42f);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        if (w < dp(FLOAT_MIN_WIDTH_DP)) {
            w = dp(FLOAT_MIN_WIDTH_DP);
            h = Math.round(w / aspect);
        }
        // Keep within screen; allow every corner / edge (portrait & landscape).
        w = Math.min(w, screenW);
        h = Math.min(h, screenH);
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.TOP | Gravity.START;
        // LAYOUT_NO_LIMITS keeps free drag across system bars in both orientations.
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        placeFloatingWindow(lp, screenW, screenH);
        applyFloatingChrome(chromeTarget != null ? chromeTarget : root, true);
    }

    private void placeFloatingWindow(WindowManager.LayoutParams lp, int screenW, int screenH) {
        int maxX = Math.max(0, screenW - lp.width);
        int maxY = Math.max(0, screenH - lp.height);
        float nx = Prefs.DEF_VIDEO_FLOAT_NORM;
        float ny = Prefs.DEF_VIDEO_FLOAT_NORM;
        try {
            android.content.SharedPreferences p = appContext.getSharedPreferences(
                    Prefs.NAME, Context.MODE_PRIVATE);
            nx = p.getFloat(Prefs.VIDEO_FLOAT_NORM_X, Prefs.DEF_VIDEO_FLOAT_NORM);
            ny = p.getFloat(Prefs.VIDEO_FLOAT_NORM_Y, Prefs.DEF_VIDEO_FLOAT_NORM);
        } catch (Throwable ignored) {}
        int margin = dp(FLOAT_MARGIN_DP);
        if (nx < 0f || ny < 0f || Float.isNaN(nx) || Float.isNaN(ny)) {
            // Default: bottom-right
            lp.x = Math.max(0, maxX - margin);
            lp.y = Math.max(0, maxY - margin);
        } else {
            lp.x = clamp(Math.round(nx * maxX), 0, maxX);
            lp.y = clamp(Math.round(ny * maxY), 0, maxY);
        }
    }

    private void saveFloatingPosition() {
        WindowManager.LayoutParams lp = floatWindowAttached ? floatLayoutParams : layoutParams;
        if (lp == null) return;
        DisplayMetrics dm = realDisplayMetrics();
        int maxX = Math.max(1, dm.widthPixels - lp.width);
        int maxY = Math.max(1, dm.heightPixels - lp.height);
        float nx = clamp01(lp.x / (float) maxX);
        float ny = clamp01(lp.y / (float) maxY);
        try {
            appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(Prefs.VIDEO_FLOAT_NORM_X, nx)
                    .putFloat(Prefs.VIDEO_FLOAT_NORM_Y, ny)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save float pos failed", t);
        }
    }

    private void applyFullscreenWindowLayout(WindowManager.LayoutParams lp) {
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        lp.x = 0;
        lp.y = 0;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        applyFloatingChrome(root, false);
        applySurfaceCornerRadius(false);
        if (root != null) applyImmersive(root);
    }

    private void applyFloatingChrome(View view, boolean floating) {
        if (view == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (floating) {
                final int radius = dp(FLOAT_CORNER_DP);
                view.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline outline) {
                        int w = v.getWidth();
                        int h = v.getHeight();
                        if (w <= 0 || h <= 0) {
                            outline.setEmpty();
                            return;
                        }
                        outline.setRoundRect(0, 0, w, h, radius);
                    }
                });
                view.setClipToOutline(true);
                view.setElevation(dp(10));
            } else {
                view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                view.setClipToOutline(false);
                view.setElevation(0f);
            }
        }
        if (floating) {
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.BLACK);
            border.setCornerRadius(dp(FLOAT_CORNER_DP));
            border.setStroke(dp(1), 0x33FFFFFF);
            view.setBackground(border);
        } else {
            view.setBackgroundColor(Color.BLACK);
        }
    }

    private final View.OnTouchListener floatingTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (!videoFloating || floatLayoutParams == null || floatRoot == null || !floatWindowAttached) {
                return false;
            }
            int slop = ViewConfiguration.get(appContext).getScaledTouchSlop();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    floatDownRawX = event.getRawX();
                    floatDownRawY = event.getRawY();
                    floatDownParamX = floatLayoutParams.x;
                    floatDownParamY = floatLayoutParams.y;
                    floatMoved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - floatDownRawX;
                    float dy = event.getRawY() - floatDownRawY;
                    if (!floatMoved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        floatMoved = true;
                    }
                    if (floatMoved) {
                        DisplayMetrics dm = realDisplayMetrics();
                        int maxX = Math.max(0, dm.widthPixels - floatLayoutParams.width);
                        int maxY = Math.max(0, dm.heightPixels - floatLayoutParams.height);
                        floatLayoutParams.x = clamp((int) (floatDownParamX + dx), 0, maxX);
                        floatLayoutParams.y = clamp((int) (floatDownParamY + dy), 0, maxY);
                        try {
                            windowManager.updateViewLayout(floatRoot, floatLayoutParams);
                        } catch (Throwable ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (floatMoved) {
                        saveFloatingPosition();
                    } else {
                        setVideoFloating(false);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (floatMoved) saveFloatingPosition();
                    return true;
                default:
                    return false;
            }
        }
    };

    private void clearFloatingTouch(FrameLayout r) {
        r.setOnTouchListener(null);
        if (videoTexture != null) videoTexture.setOnTouchListener(null);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRootTap();
            }
        });
    }

    private DisplayMetrics realDisplayMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.getDefaultDisplay().getRealMetrics(dm);
            } else {
                windowManager.getDefaultDisplay().getMetrics(dm);
            }
        } catch (Throwable t) {
            dm = appContext.getResources().getDisplayMetrics();
        }
        return dm;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** Masks SurfaceView's square corners with an even-odd round-rect hole. */
    private static final class RoundCornerMaskView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float radiusPx;

        RoundCornerMaskView(Context context) {
            super(context);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            setWillNotDraw(false);
        }

        void setRadiusPx(float radiusPx) {
            this.radiusPx = radiusPx;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || radiusPx <= 0f) return;
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(0, 0, w, h, radiusPx, radiusPx, Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
    }

    /** Theme-colored indeterminate/determined spinner (Ava AccentBlue / AccentBrown). */
    private void tintAccentProgress(ProgressBar bar) {
        if (bar == null) return;
        int accent = resolveAccentColor();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ColorStateList csl = ColorStateList.valueOf(accent);
            bar.setIndeterminateTintList(csl);
            bar.setProgressTintList(csl);
            bar.setSecondaryProgressTintList(csl);
        } else {
            Drawable ind = bar.getIndeterminateDrawable();
            if (ind != null) ind.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            Drawable prog = bar.getProgressDrawable();
            if (prog != null) prog.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        }
    }

    /** Upstream Material IconButton (48×48 / 24dp glyph). */
    private FrameLayout playerIconBtn(String asset, final Runnable action) {
        return playerIconBtn(asset, 48, 24, action);
    }

    private FrameLayout playerIconBtn(String asset, int touchDp, int iconDp, final Runnable action) {
        final FrameLayout btn = VideoIcons.iconButton(appContext, asset, touchDp, iconDp, null);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) action.run();
                scheduleVideoChromeHide(boundEngine);
            }
        });
        return btn;
    }

    /** Upstream PlayerSeekbar: white thumb, white@0.5 track, primary fill. */
    private void stylePlayerSeekBar(SeekBar bar) {
        // Upstream PlayerSeekbar: 4dp track white@0.5 + primary fill, 16dp white thumb.
        int trackH = dp(4);
        int thumb = dp(16);
        int accent = resolveAccentColor();

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(trackH);
        background.setColor(0x80FFFFFF);

        GradientDrawable progress = new GradientDrawable();
        progress.setShape(GradientDrawable.RECTANGLE);
        progress.setCornerRadius(trackH);
        progress.setColor(accent);

        ClipDrawable clip = new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{background, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        // Intrinsic height so the 24dp SeekBar row matches Compose height(24.dp).
        layers.setLayerHeight(0, trackH);
        layers.setLayerHeight(1, trackH);
        layers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        layers.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        bar.setProgressDrawable(layers);

        GradientDrawable thumbGd = new GradientDrawable();
        thumbGd.setShape(GradientDrawable.OVAL);
        thumbGd.setColor(Color.WHITE);
        thumbGd.setSize(thumb, thumb);
        bar.setThumb(thumbGd);
        bar.setSplitTrack(false);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackgroundColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bar.setProgressTintList(null);
            bar.setProgressBackgroundTintList(null);
            bar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        }
    }

    /** Upstream FullscreenVideo — DLNA back pill top-start; badge is root top-end. */
    private FrameLayout buildMirrorChrome(int safe) {
        FrameLayout host = new FrameLayout(appContext);

        LinearLayout topStart = new LinearLayout(appContext);
        topStart.setOrientation(LinearLayout.HORIZONTAL);
        topStart.setPadding(0, 0, 0, 0);
        topStart.setAlpha(0f);
        topStart.setVisibility(View.GONE);
        mirrorTopRow = topStart;

        View back = makeBackPill();
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.setMargins(safe, safe, safe, dp(16));
        topStart.addView(back, backLp);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        host.addView(topStart, topLp);

        mirrorResLabel = new TextView(appContext);
        mirrorResLabel.setTextColor(0x99FFFFFF);
        mirrorResLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        mirrorResLabel.setPadding(dp(8), dp(8), dp(8), dp(8));
        mirrorResLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams resLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        host.addView(mirrorResLabel, resLp);

        return host;
    }

    /**
     * 1:1 with DLNA {@code CinemaOverlay} AUDIO hierarchy:
     * full-bleed cover (CENTER_CROP ×1.15) + vignette + bottom gradient +
     * top {@code ‹ 返回} strip + bottom bar (title / subtitle / seek / prev-play-next).
     */
    private FrameLayout buildCinemaAudio(int safe) {
        FrameLayout host = new FrameLayout(appContext);
        host.setBackgroundColor(Color.BLACK);
        host.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRootTap();
            }
        });

        FrameLayout coverHost = new FrameLayout(appContext);
        coverView = new ImageView(appContext);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setBackgroundColor(0xFF1A1A1A);
        coverView.setScaleX(1.15f);
        coverView.setScaleY(1.15f);
        coverHost.addView(coverView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        host.addView(coverHost, matchParent());

        coverVignette = new View(appContext);
        // Soft full-bleed gradient so centered title/controls stay readable.
        GradientDrawable vignette = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x80000000, 0xA6000000, 0xD9000000});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            coverVignette.setBackground(vignette);
        }
        host.addView(coverVignette, matchParent());

        // Unused flat dim (unified UI uses gradient vignette instead).
        audioClassicDim = new View(appContext);
        audioClassicDim.setBackgroundColor(0xB3000000);
        audioClassicDim.setVisibility(View.GONE);
        host.addView(audioClassicDim, matchParent());

        audioWaveShadow = makeAudioWaveShadow();
        host.addView(audioWaveShadow);

        audioChromeStrip = buildAudioChromeStrip(safe);
        host.addView(audioChromeStrip);

        audioBottomBar = buildAudioBottomBar(safe);
        host.addView(audioBottomBar);
        audioCenteredLayoutApplied = false;
        applyCenteredAudioLayout();

        return host;
    }

    /** Same top strip as DLNA {@code CinemaOverlay.buildChromeStrip}. */
    private FrameLayout buildAudioChromeStrip(int safe) {
        FrameLayout strip = new FrameLayout(appContext);
        strip.setVisibility(View.GONE);

        GradientDrawable shadow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        0x38400000, 0x30000000, 0x24000000, 0x18000000,
                        0x0C000000, 0x04000000, 0x00000000
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            strip.setBackground(shadow);
        }

        FrameLayout.LayoutParams stripLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        strip.setLayoutParams(stripLp);

        LinearLayout row = new LinearLayout(appContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP);
        rowLp.setMargins(safe, safe, safe, dp(16));
        row.setLayoutParams(rowLp);
        row.addView(makeBackPill());
        strip.addView(row);
        return strip;
    }

    /** Same bottom bar as DLNA {@code CinemaOverlay.buildBottomBar} (prev / play / next). */
    private LinearLayout buildAudioBottomBar(int safe) {
        audioBarSafeInsetPx = safe;
        LinearLayout bar = new LinearLayout(appContext);
        bar.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int sideMargin = dp(BOTTOM_BAR_SIDE_MARGIN_DP);
        bottomLp.setMargins(sideMargin, 0, sideMargin, safe);
        bar.setLayoutParams(bottomLp);

        npTitle = new CautiousMarqueeText(appContext);
        npTitle.setTextColor(Color.WHITE);
        npTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        npTitle.setTypeface(Typeface.DEFAULT_BOLD);
        npTitle.setRestGravity(Gravity.START);
        npTitle.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(npTitle);

        npSubtitle = new CautiousMarqueeText(appContext);
        npSubtitle.setTextColor(0x99FFFFFF);
        npSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        npSubtitle.setContentPadding(0, dp(2), 0, dp(8));
        npSubtitle.setRestGravity(Gravity.START);
        npSubtitle.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(npSubtitle);

        LinearLayout scrubRow = new LinearLayout(appContext);
        scrubRow.setOrientation(LinearLayout.HORIZONTAL);
        scrubRow.setGravity(Gravity.CENTER_VERTICAL);
        audioScrubRow = scrubRow;

        npPos = monoText("0:00");
        scrubRow.addView(npPos);

        npSeek = new SeekBar(appContext);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        seekLp.leftMargin = dp(10);
        seekLp.rightMargin = dp(10);
        npSeek.setLayoutParams(seekLp);
        npSeek.setMax(1000);
        styleThinSeekBar(npSeek);
        npSeek.setOnSeekBarChangeListener(seekListener(false));
        scrubRow.addView(npSeek);

        npDur = monoText("0:00");
        scrubRow.addView(npDur);
        bar.addView(scrubRow);

        // Clean transport cluster (sizes applied via vmin metrics).
        LinearLayout controls = new LinearLayout(appContext);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        audioControlsRow = controls;

        npPrev = makeCinemaSkipButton("skip_previous", new Runnable() {
            @Override
            public void run() {
                callback.onPrevious();
            }
        });
        controls.addView(npPrev);

        npPlayPause = makeCinemaPlayButton(new Runnable() {
            @Override
            public void run() {
                callback.onPlayPause();
                if (boundEngine == null) return;
                boolean playing = boundEngine.isPlaying();
                lastAudioPlayingIcon = playing;
                setAudioPlayPauseIcon(playing);
                scheduleAudioIdleHide(boundEngine);
            }
        });
        controls.addView(npPlayPause);

        npNext = makeCinemaSkipButton("skip_next", new Runnable() {
            @Override
            public void run() {
                callback.onNext();
            }
        });
        controls.addView(npNext);

        audioControlSpacerStart = audioControlSpacer();
        audioControlSpacerEnd = audioControlSpacer();
        audioControlSpacerStart.setVisibility(View.GONE);
        audioControlSpacerEnd.setVisibility(View.GONE);

        LinearLayout.LayoutParams transportLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        transportLp.gravity = Gravity.CENTER_HORIZONTAL;
        bar.addView(controls, transportLp);
        return bar;
    }

    /** Background wave shadow — replaces flat bottom gradient; reacts to PCM. */
    private AudioWaveShadowView makeAudioWaveShadow() {
        AudioWaveShadowView v = new AudioWaveShadowView(appContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(280),
                Gravity.BOTTOM);
        v.setLayoutParams(lp);
        return v;
    }

    /** Top/bottom edge scrims for video chrome — mirror music cinema gradients. */
    private View makeVideoEdgeGradient(boolean top) {
        View v = new View(appContext);
        GradientDrawable g = new GradientDrawable(
                top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                top
                        ? new int[]{0xC0000000, 0x60000000, 0x00000000}
                        : new int[]{0xE6000000, 0x99000000, 0x00000000});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            v.setBackground(g);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                top ? dp(120) : dp(168),
                top ? Gravity.TOP : Gravity.BOTTOM);
        v.setLayoutParams(lp);
        v.setClickable(false);
        v.setFocusable(false);
        return v;
    }

    private View audioControlSpacer() {
        View v = new View(appContext);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return v;
    }

    private TextView monoText(String text) {
        TextView tv = whiteBody(text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(0xCCFFFFFF);
        return tv;
    }

    private int resolveAccentColor() {
        try {
            boolean darkMode = appContext
                    .getSharedPreferences(HOME_PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_DARK_MODE, false);
            return darkMode ? ACCENT_BROWN : ACCENT_BLUE;
        } catch (Exception e) {
            return ACCENT_BLUE;
        }
    }

    private VideoContentScale loadVideoContentScale() {
        try {
            String name = appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getString(Prefs.VIDEO_CONTENT_SCALE, Prefs.DEF_VIDEO_CONTENT_SCALE);
            if (name != null) {
                return VideoContentScale.valueOf(name);
            }
        } catch (Throwable ignored) {}
        return VideoContentScale.BEST_FIT;
    }

    private void saveVideoContentScale(VideoContentScale scale) {
        if (scale == null) return;
        try {
            appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Prefs.VIDEO_CONTENT_SCALE, scale.name())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save content scale failed", t);
        }
    }

    /** Same thin accent seek track as DLNA CinemaOverlay. */
    private void styleThinSeekBar(SeekBar bar) {
        int trackH = dp(2);
        int accent = resolveAccentColor();

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(trackH);
        background.setColor(0x33FFFFFF);

        GradientDrawable progress = new GradientDrawable();
        progress.setShape(GradientDrawable.RECTANGLE);
        progress.setCornerRadius(trackH);
        progress.setColor(accent);

        ClipDrawable clip = new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{background, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        bar.setProgressDrawable(layers);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.TRANSPARENT);
        thumb.setSize(dp(28), dp(28));
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        bar.setPadding(0, dp(10), 0, dp(10));
        bar.setBackgroundColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bar.setProgressTintList(ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
            bar.setProgressTintMode(PorterDuff.Mode.SRC_IN);
            bar.setProgressBackgroundTintMode(PorterDuff.Mode.SRC_IN);
        }
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    private TextView holdScanBtn(String label, final boolean forward) {
        TextView t = transportBtn(label, null);
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        callback.onAudioScanBegin(forward);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        callback.onAudioScanEnd();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return t;
    }

    private TextView transportBtn(String label, final Runnable action) {
        TextView t = new TextView(appContext);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.leftMargin = dp(5);
        lp.rightMargin = dp(5);
        t.setLayoutParams(lp);
        if (action != null) {
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        }
        return t;
    }

    private TextView iconBtn(String label, final Runnable action) {
        TextView t = new TextView(appContext);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return t;
    }

    /**
     * Exact same back pill as DLNA {@code CinemaOverlay.buildChromeStrip}:
     * {@code "‹ 返回"} rounded pill → {@code callback.onStop()}.
     */
    private TextView makeBackPill() {
        TextView backPill = new TextView(appContext);
        backPill.setText("‹ 返回");
        backPill.setTextColor(0xCCFFFFFF);
        backPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        backPill.setPadding(dp(14), dp(10), dp(18), dp(10));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(999));
        pillBg.setColor(0x66121824);
        pillBg.setStroke(dp(1), 0x14FFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            backPill.setBackground(pillBg);
        }
        backPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Mirror DLNA: back always goes through onStop.
                callback.onStop();
            }
        });
        return backPill;
    }

    private void applyBadgeLayout() {
        if (badgeView == null) return;
        android.util.DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        float vmin = Math.min(dm.widthPixels, dm.heightPixels);
        float density = dm.density;
        int sizePx = Math.round(Math.max(BADGE_SIZE_MIN_DP * density,
                Math.min(BADGE_SIZE_MAX_DP * density, vmin * BADGE_SIZE_VMIN)));
        int edgePx = Math.round(Math.max(BADGE_EDGE_MIN_DP * density, vmin * BADGE_EDGE_VMIN));
        int cutoutTop = 0;
        int cutoutEnd = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && root != null
                && root.getRootWindowInsets() != null
                && root.getRootWindowInsets().getDisplayCutout() != null) {
            cutoutTop = root.getRootWindowInsets().getDisplayCutout().getSafeInsetTop();
            cutoutEnd = root.getRootWindowInsets().getDisplayCutout().getSafeInsetRight();
        }
        int marginTop = Math.max(edgePx, cutoutTop);
        int marginEnd = Math.max(edgePx, cutoutEnd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP | Gravity.END);
        lp.setMargins(0, marginTop, marginEnd, 0);
        badgeView.setLayoutParams(lp);
    }

    private void updateBadgeVisibility(boolean show, boolean animated) {
        if (badgeView == null) return;
        // Brand logo only on the music (AUDIO) UI — never video / mirror.
        if (mode != Mode.AUDIO) {
            show = false;
        } else {
            show = true;
        }
        badgeView.animate().cancel();
        if (show) {
            badgeView.setVisibility(View.VISIBLE);
            if (animated) {
                if (badgeView.getAlpha() <= 0.01f) badgeView.setAlpha(0f);
                badgeView.animate()
                        .alpha(BADGE_ALPHA / 255f)
                        .setDuration(CHROME_FADE_MS)
                        .start();
            } else {
                badgeView.setAlpha(BADGE_ALPHA / 255f);
            }
        } else {
            if (animated && badgeView.getVisibility() == View.VISIBLE) {
                badgeView.animate()
                        .alpha(0f)
                        .setDuration(CHROME_FADE_MS)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (badgeView != null && mode != Mode.AUDIO) {
                                    badgeView.setVisibility(View.GONE);
                                }
                            }
                        })
                        .start();
            } else {
                badgeView.setAlpha(0f);
                badgeView.setVisibility(View.GONE);
            }
        }
    }

    private Bitmap loadAirPlayBadgeBitmap() {
        if (airplayBadgeBitmap != null && !airplayBadgeBitmap.isRecycled()) {
            return airplayBadgeBitmap;
        }
        InputStream in = null;
        try {
            ClassLoader loader = AirPlayOverlay.class.getClassLoader();
            if (loader != null) {
                in = loader.getResourceAsStream(BADGE_ASSET);
            }
            if (in == null) {
                in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BADGE_ASSET);
            }
            if (in != null) {
                airplayBadgeBitmap = BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load AirPlay badge asset", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return airplayBadgeBitmap;
    }

    private TextView whiteBody(String text) {
        TextView tv = new TextView(appContext);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        return tv;
    }

    private View spacer(int h) {
        View v = new View(appContext);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private void styleSeekBar(SeekBar bar) {
        // Approximate upstream 4dp white@0.5 track + white thumb
        try {
            bar.getProgressDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            bar.getThumb().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) {}
    }

    /**
     * Clean cinema music chrome (MA + iOS): Ava-style vmin scaling, solid play,
     * no glass clutter. Portrait centered; landscape right column.
     */
    private void applyCenteredAudioLayout() {
        if (audioCenteredLayoutApplied) return;
        audioCenteredLayoutApplied = true;

        if (coverVignette != null) coverVignette.setVisibility(View.VISIBLE);
        if (audioClassicDim != null) audioClassicDim.setVisibility(View.GONE);
        if (audioWaveShadow != null) audioWaveShadow.setVisibility(View.VISIBLE);
        if (audioScrubRow != null) audioScrubRow.setVisibility(View.GONE);
        if (audioControlSpacerStart != null) audioControlSpacerStart.setVisibility(View.GONE);
        if (audioControlSpacerEnd != null) audioControlSpacerEnd.setVisibility(View.GONE);

        // Drop any leftover capsule chrome.
        if (audioControlsRow != null) {
            audioControlsRow.setBackground(null);
            audioControlsRow.setPadding(0, 0, 0, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioControlsRow.setElevation(0f);
            }
        }

        applyMusicChromeLayout(true);

        if (audioBottomBar != null) {
            audioBottomBar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                          int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if ((right - left) != (oldRight - oldLeft)) {
                        applyMusicChromeLayout(false);
                    }
                }
            });
        }
    }

    /**
     * Ava {@code DetailOverlayMetrics}: scale = clamp(vmin/360, 0.9, 1.28).
     */
    private static final class MusicMetrics {
        float titleSp;
        float artistSp;
        int padHPx;
        int titleArtistGapPx;
        int transportTopPx;
        int playPx;
        int skipPx;
        int skipIconPx;
        int playIconPx;
        int clusterGapPx;
        int sidePadPx;
    }

    private MusicMetrics computeMusicMetrics(boolean landscape) {
        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density;
        float hDp = dm.heightPixels / dm.density;
        float vmin = Math.min(wDp, hDp);
        float vmax = Math.max(wDp, hDp);
        float aspect = vmax / Math.max(1f, vmin);
        float scale = Math.max(0.9f, Math.min(1.28f, vmin / 360f));
        float textBoost = 1f;
        if (landscape) {
            textBoost = aspect >= 1.55f ? 1.08f : 1.04f;
        }
        float textScale = scale * textBoost;

        MusicMetrics m = new MusicMetrics();
        m.titleSp = clamp(29f * textScale, 26f, 36f);
        m.artistSp = clamp(19.5f * textScale, 16f, 24f);
        m.padHPx = dp(Math.round(Math.max(AUDIO_META_INSET_MIN_DP, 36f * scale)));
        m.titleArtistGapPx = dp(Math.round(clamp(14f * scale, 12f, 20f)));
        m.transportTopPx = dp(Math.round(clamp(34f * scale, 28f, 48f)));
        m.playPx = dp(Math.round(clamp((landscape ? 72f : 78f) * scale, 66f, 90f)));
        m.skipPx = dp(Math.round(clamp(50f * scale, 44f, 58f)));
        m.skipIconPx = dp(Math.round(clamp(30f * scale, 26f, 36f)));
        m.playIconPx = Math.max(dp(28), Math.round(m.playPx * 0.46f));
        m.clusterGapPx = dp(Math.round(clamp(20f * scale, 16f, 28f)));
        m.sidePadPx = dp(Math.round(clamp((landscape ? 36f : 28f) * scale, 24f, 44f)));
        return m;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void applyMusicChromeLayout(boolean force) {
        if (audioBottomBar == null) return;
        boolean landscape = isLandscapeUi();
        if (!force && landscape == audioChromeLandscape) return;
        audioChromeLandscape = landscape;

        MusicMetrics m = computeMusicMetrics(landscape);
        styleMusicMeta(m, landscape);
        layoutMusicBar(m, landscape);
        layoutMusicTransport(m, landscape);
    }

    private void styleMusicMeta(MusicMetrics m, boolean landscape) {
        Typeface titleTf = Typeface.create("sans-serif", Typeface.BOLD);
        Typeface artistTf = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        // Always centered — portrait and landscape (no left/right skew).
        int restG = Gravity.CENTER_HORIZONTAL;

        if (npTitle != null) {
            npTitle.setTextColor(Color.WHITE);
            npTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.titleSp);
            npTitle.setTypeface(titleTf);
            npTitle.setRestGravity(restG);
            npTitle.setContentPadding(m.padHPx, 0, m.padHPx, 0);
            npTitle.setShadowLayer(12f, 0f, 3f, 0xB3000000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                npTitle.setLetterSpacing(-0.02f);
            }
        }
        if (npSubtitle != null) {
            npSubtitle.setTextColor(0x9EFFFFFF); // ~62% like Ava
            npSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.artistSp);
            npSubtitle.setTypeface(artistTf != null ? artistTf : Typeface.SANS_SERIF);
            npSubtitle.setAllCaps(false);
            npSubtitle.setRestGravity(restG);
            npSubtitle.setContentPadding(m.padHPx, m.titleArtistGapPx, m.padHPx, 0);
            npSubtitle.setShadowLayer(8f, 0f, 2f, 0x80000000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                npSubtitle.setLetterSpacing(0.01f);
            }
        }
    }

    private void layoutMusicBar(MusicMetrics m, boolean landscape) {
        ViewGroup.LayoutParams raw = audioBottomBar.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        // Full-bleed centered column in both orientations.
        lp.gravity = Gravity.CENTER;
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
        lp.leftMargin = 0;
        lp.rightMargin = 0;
        lp.topMargin = 0;
        lp.bottomMargin = landscape ? Math.max(audioBarSafeInsetPx, dp(16)) : 0;
        audioBottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        audioBottomBar.setLayoutParams(lp);
    }

    private void layoutMusicTransport(MusicMetrics m, boolean landscape) {
        if (audioControlsRow == null) return;
        LinearLayout.LayoutParams clp =
                (audioControlsRow.getLayoutParams() instanceof LinearLayout.LayoutParams)
                        ? (LinearLayout.LayoutParams) audioControlsRow.getLayoutParams()
                        : new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        clp.topMargin = m.transportTopPx;
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        audioControlsRow.setLayoutParams(clp);
        audioControlsRow.setGravity(Gravity.CENTER);

        int half = Math.max(1, m.clusterGapPx / 2);
        sizeCinemaSkip(npPrev, m.skipPx, m.skipIconPx, 0, half);
        sizeCinemaPlay(npPlayPause, m.playPx, m.playIconPx, half, half);
        sizeCinemaSkip(npNext, m.skipPx, m.skipIconPx, half, 0);

        if (audioWaveShadow != null) {
            ViewGroup.LayoutParams raw = audioWaveShadow.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
                // Keep height modest — avoids giant bitmaps if a layer type regresses.
                int h = landscape
                        ? Math.round(dm.heightPixels * 0.55f)
                        : Math.round(dm.heightPixels * 0.38f);
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
                lp.height = Math.max(dp(220), Math.min(h, dp(420)));
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                audioWaveShadow.setLayoutParams(lp);
            }
        }
    }

    private boolean isLandscapeUi() {
        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    private View makeCinemaSkipButton(String glyph, final Runnable action) {
        FrameLayout btn = VideoIcons.iconButton(appContext, glyph, 50, 30, action);
        tintGlyphButton(btn, 0xE6FFFFFF);
        bindPressScale(btn);
        btn.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        return btn;
    }

    private View makeCinemaPlayButton(final Runnable action) {
        FrameLayout wrap = new FrameLayout(appContext);
        int size = dp(78);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(size, size));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            wrap.setBackground(bg);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wrap.setElevation(dp(8));
        }

        ImageView iv = VideoIcons.glyphView(appContext, "pause", 34);
        iv.setColorFilter(0xFF111111);
        wrap.addView(iv, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        wrap.setTag(iv);
        wrap.setClickable(true);
        wrap.setFocusable(true);
        wrap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) action.run();
            }
        });
        bindPressScale(wrap);
        return wrap;
    }

    private void sizeCinemaSkip(View btn, int hit, int icon, int left, int right) {
        if (btn == null) return;
        LinearLayout.LayoutParams lp = (btn.getLayoutParams() instanceof LinearLayout.LayoutParams)
                ? (LinearLayout.LayoutParams) btn.getLayoutParams()
                : new LinearLayout.LayoutParams(hit, hit);
        lp.width = hit;
        lp.height = hit;
        lp.leftMargin = left;
        lp.rightMargin = right;
        btn.setLayoutParams(lp);
        if (btn instanceof FrameLayout && ((FrameLayout) btn).getChildCount() > 0) {
            View child = ((FrameLayout) btn).getChildAt(0);
            child.setLayoutParams(new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        }
        tintGlyphButton(btn, 0xE6FFFFFF);
    }

    private void sizeCinemaPlay(View btn, int hit, int icon, int left, int right) {
        if (btn == null) return;
        LinearLayout.LayoutParams lp = (btn.getLayoutParams() instanceof LinearLayout.LayoutParams)
                ? (LinearLayout.LayoutParams) btn.getLayoutParams()
                : new LinearLayout.LayoutParams(hit, hit);
        lp.width = hit;
        lp.height = hit;
        lp.leftMargin = left;
        lp.rightMargin = right;
        btn.setLayoutParams(lp);
        Object tag = btn.getTag();
        if (tag instanceof ImageView) {
            ((ImageView) tag).setLayoutParams(
                    new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        }
    }

    private void setAudioPlayPauseIcon(boolean playing) {
        if (npPlayPause == null) return;
        ImageView iv = null;
        Object tag = npPlayPause.getTag();
        if (tag instanceof ImageView) {
            iv = (ImageView) tag;
        } else if (npPlayPause instanceof FrameLayout) {
            View child = ((FrameLayout) npPlayPause).getChildAt(0);
            if (child instanceof ImageView) iv = (ImageView) child;
        }
        if (iv == null) return;
        Drawable d = VideoIcons.load(appContext, playing ? "pause" : "play_arrow");
        if (d != null) {
            d = d.mutate();
            d.setColorFilter(0xFF111111, PorterDuff.Mode.SRC_IN);
            iv.setImageDrawable(d);
        }
        iv.setTranslationX(playing ? 0f : dp(2));
    }

    private void tintGlyphButton(View btn, int color) {
        if (!(btn instanceof FrameLayout)) return;
        View child = ((FrameLayout) btn).getChildAt(0);
        if (!(child instanceof ImageView)) return;
        Drawable d = ((ImageView) child).getDrawable();
        if (d != null) {
            d = d.mutate();
            d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            ((ImageView) child).setImageDrawable(d);
        } else {
            ((ImageView) child).setColorFilter(color);
        }
    }

    private void bindPressScale(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private SeekBar.OnSeekBarChangeListener seekListener(final boolean video) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (video && videoTimeView != null) {
                    long dur = videoDurationMs();
                    long pos = dur > 0 ? progress * dur / Math.max(1, seekBar.getMax()) : 0L;
                    videoTimeView.setText(formatVideoTime(pos));
                } else if (!video && npPos != null) {
                    // Cinema: progress is 0..1000 normalized.
                    long dur = audioDurationMs();
                    long pos = dur > 0 ? progress * dur / Math.max(1, seekBar.getMax()) : 0L;
                    npPos.setText(formatVideoTime(pos));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                callback.onSeekStart();
                if (video) {
                    mainHandler.removeCallbacks(hideVideoChrome);
                    setVideoChromeVisible(true);
                } else {
                    mainHandler.removeCallbacks(hideAudioIdle);
                    audioIdleHideArmed = false;
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long positionMs;
                if (video) {
                    long dur = videoDurationMs();
                    positionMs = dur > 0
                            ? (long) seekBar.getProgress() * dur / Math.max(1, seekBar.getMax())
                            : 0L;
                } else {
                    long dur = audioDurationMs();
                    positionMs = dur > 0
                            ? (long) seekBar.getProgress() * dur / Math.max(1, seekBar.getMax())
                            : 0L;
                }
                callback.onSeek(positionMs);
                callback.onSeekEnd();
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        userSeeking = false;
                    }
                }, 400);
                if (video) scheduleVideoChromeHide(boundEngine);
            }
        };
    }

    private long audioDurationMs() {
        if (boundEngine == null) return 0L;
        long dur = boundEngine.getDurationMs();
        if (dur <= 0) dur = boundEngine.getTrackInfo().durationMs;
        return dur;
    }

    private long videoDurationMs() {
        if (boundEngine == null) return 0L;
        return Math.max(0L, boundEngine.getVideoPlaybackInfo().durationMs);
    }

    private SurfaceHolder.Callback surfaceCb(final boolean forVideo) {
        // Only used for mirror SurfaceView (forVideo=false). Video uses TextureView listener.
        return new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (forVideo) return;
                mirrorSurfaceRef = holder.getSurface();
                callback.onSurfaceReady(holder.getSurface(), false);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (forVideo) return;
                if (holder.getSurface() != null && holder.getSurface().isValid()) {
                    callback.onSurfaceReady(holder.getSurface(), false);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (forVideo) return;
                Surface s = mirrorSurfaceRef;
                mirrorSurfaceRef = null;
                if (s != null) callback.onSurfaceDestroyed(s, false);
            }
        };
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Ava AirPlay");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive(View view) {
        view.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    /** Upstream {@code formatVideoTime}. */
    private static String formatVideoTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private int safeInsetPx() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (windowManager.getDefaultDisplay() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.getDefaultDisplay().getRealMetrics(dm);
            } else {
                windowManager.getDefaultDisplay().getMetrics(dm);
            }
        }
        return Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.04f);
    }

    private int dp(int v) {
        return Math.round(v * appContext.getResources().getDisplayMetrics().density);
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(appContext);
        }
        return true;
    }
}
