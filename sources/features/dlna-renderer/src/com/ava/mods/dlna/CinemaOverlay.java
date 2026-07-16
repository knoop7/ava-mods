package com.ava.mods.dlna;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Style B · Cinema — full-screen DLNA player overlay.
 *
 * Audio uses an edge-to-edge blurred-style cover (Glass player pattern). Chrome
 * (top gradient + back pill, bottom controls, video-only DLNA badge) is hidden
 * until touch and auto-hides after idle.
 */
public final class CinemaOverlay {
    private static final String TAG = "DlnaCinemaOverlay";
    private static final String BADGE_ASSET = "assets/dlna-icon.png";

    private static final long CHROME_AUTO_HIDE_MS = 10_000L;
    private static final long CHROME_SLIDE_IN_MS = 380L;
    private static final long CHROME_SLIDE_OUT_MS = 350L;
    private static final int COVER_CROSSFADE_MS = 420;
    /** Matches PlaybackEngine's progress polling interval so the glide lands exactly on the next tick. */
    private static final long PROGRESS_TICK_ANIM_MS = 480L;
    /** Grace period after playback fully ends before the overlay window disappears. */
    static final long OVERLAY_END_OF_QUEUE_HIDE_MS = 15_000L;
    /** Fixed horizontal margin for the bottom playback bar, per design spec. */
    private static final int BOTTOM_BAR_SIDE_MARGIN_DP = 20;

    private static final float BADGE_SIZE_VMIN = 0.11f;
    private static final float BADGE_EDGE_VMIN = 0.05f;
    private static final float BADGE_SIZE_MIN_DP = 32f;
    private static final float BADGE_SIZE_MAX_DP = 48f;
    private static final float BADGE_EDGE_MIN_DP = 32f;
    private static final int BADGE_ALPHA = 64;

    private static final int ICON_ACTIVE = Color.WHITE;
    /** Matches GlassMusicPlayerView inactive control tint (alpha 0.3). */
    private static final int ICON_INACTIVE = 0x4DFFFFFF;
    /** Matches GlassMusicPlayerView skip-prev/next tint (alpha 0.5). */
    private static final int ICON_SKIP_TINT = 0x80FFFFFF;

    /** Mirrors Ava's AppColors.kt (AccentBlue / AccentBrown), switched by the same dark-mode flag. */
    private static final String HOME_PREFS_NAME = "ava_home_prefs";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final int ACCENT_BLUE = 0xFF0417E0;
    private static final int ACCENT_BROWN = 0xFFA78B73;

    private static final float SHUFFLE_REPEAT_ICON_DP = 22f;
    private static final float SKIP_ICON_DP = 26f;
    private static final float PLAY_ICON_DP = 26f;

    /**
     * Path data below is copied verbatim from Google's Material Icons (the same
     * "filled" glyph set backing GlassMusicPlayerView's Icons.Filled.Shuffle /
     * Repeat / RepeatOne / SkipPrevious / SkipNext / PlayArrow / Pause), so the
     * Cinema controls render pixel-identical shapes instead of hand-approximated
     * canvas primitives. All paths use a 24x24 viewport.
     */
    private static final String PATH_SHUFFLE =
            "M10.59 9.17 5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z";
    private static final String PATH_REPEAT =
            "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z";
    private static final String PATH_REPEAT_ONE =
            "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z";
    private static final String PATH_SKIP_PREVIOUS = "M6 6h2v12H6zm3.5 6 8.5 6V6z";
    private static final String PATH_SKIP_NEXT = "m6 18 8.5-6L6 6v12zM16 6v12h2V6h-2z";
    private static final String PATH_PLAY_ARROW = "M8 5v14l11-7z";
    private static final String PATH_PAUSE = "M6 19h4V5H6v14zm8-14v14h4V5h-4z";

    /**
     * Must be declared before the GLYPH_* fields below: static field
     * initializers run in textual order, and parsePathData() (called from
     * those initializers) depends on these two patterns being non-null.
     */
    private static final Pattern PATH_COMMAND_SPLIT = Pattern.compile("(?=[MmLlHhVvZz])");
    private static final Pattern PATH_NUMBER = Pattern.compile("-?(?:\\d+\\.?\\d*|\\.\\d+)");

    private static final Path GLYPH_SHUFFLE = parsePathData(PATH_SHUFFLE);
    private static final Path GLYPH_REPEAT = parsePathData(PATH_REPEAT);
    private static final Path GLYPH_REPEAT_ONE = parsePathData(PATH_REPEAT_ONE);
    private static final Path GLYPH_SKIP_PREVIOUS = parsePathData(PATH_SKIP_PREVIOUS);
    private static final Path GLYPH_SKIP_NEXT = parsePathData(PATH_SKIP_NEXT);
    private static final Path GLYPH_PLAY_ARROW = parsePathData(PATH_PLAY_ARROW);
    private static final Path GLYPH_PAUSE = parsePathData(PATH_PAUSE);

    public interface Callback {
        void onPlayPause();

        void onStop();

        void onSeek(long positionMs);

        void onShuffleChanged(boolean enabled);

        /** @param mode off | all | one */
        void onRepeatModeChanged(String mode);

        /** Step back to the previous track in our locally-tracked play history. */
        void onSkipPrevious();

        /**
         * Advance to the next track: honors a controller-provided
         * SetNextAVTransportURI if present, otherwise replays forward through
         * local play history (or shuffle/repeat, depending on mode).
         */
        void onSkipNext();
    }

    /** Repeat modes aligned with GlassMusicPlayerView. */
    private enum RepeatMode {
        OFF("off"),
        ALL("all"),
        ONE("one");

        final String id;

        RepeatMode(String id) {
            this.id = id;
        }

        RepeatMode next() {
            switch (this) {
                case OFF:
                    return ALL;
                case ALL:
                    return ONE;
                default:
                    return OFF;
            }
        }

        static RepeatMode fromId(String id) {
            if (ALL.id.equals(id)) {
                return ALL;
            }
            if (ONE.id.equals(id)) {
                return ONE;
            }
            return OFF;
        }
    }

    private final Context context;
    private final Handler mainHandler;
    private final WindowManager windowManager;
    private final Callback callback;
    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DlnaCinemaArt");
        t.setDaemon(true);
        return t;
    });
    private final Runnable chromeHideRunnable = () -> hideChrome(true);

    private FrameLayout root;
    private TextureView textureView;
    private ImageView coverView;
    private View coverVignette;
    private FrameLayout chromeStrip;
    private ImageView badgeView;
    private LinearLayout bottomBar;
    private TextView titleView;
    private TextView subtitleView;
    private TextView timeCurrentView;
    private TextView timeTotalView;
    private SeekBar seekBar;
    private VectorIconView playIconView;
    private VectorIconView shuffleIconView;
    private VectorIconView repeatIconView;
    private WindowManager.LayoutParams layoutParams;
    /** Animates the seek bar smoothly between polling ticks instead of snapping. */
    private ValueAnimator progressAnimator;

    private volatile boolean visible = false;
    private volatile boolean userSeeking = false;
    /** Cached duration for scrub math; avoids fragile parse-back from formatted labels. */
    private volatile long lastKnownDurationMs = 0;
    private volatile boolean chromeShown = false;
    private volatile int chromeSlidePx = 0;
    private volatile int bottomSlidePx = 0;
    private volatile MediaKind currentKind = MediaKind.AUDIO;
    private volatile Surface videoSurface;
    private volatile boolean shuffleEnabled = false;
    private volatile RepeatMode repeatMode = RepeatMode.OFF;
    /** Default on: only the top header (back button) auto-hides; the bottom playback bar stays put. */
    private volatile boolean keepControlsVisible = true;
    private volatile long artRequestId = 0;
    private Bitmap dlnaBadgeBitmap;

    public CinemaOverlay(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.callback = callback;
    }

    public TextureView getTextureView() {
        return textureView;
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * Restores a persisted shuffle/repeat preference before the control bar is
     * first built. Must be called before the first {@link #show}; has no effect
     * once the buttons exist (they own their own toggle state after that).
     */
    public void setInitialShuffle(boolean enabled) {
        shuffleEnabled = enabled;
    }

    /** @param mode off | all | one */
    public void setInitialRepeatMode(String mode) {
        repeatMode = RepeatMode.fromId(mode);
    }

    /**
     * Default on: the bottom playback bar (scrubber + shuffle/prev/play/next/
     * repeat) stays permanently visible instead of participating in the
     * idle auto-hide; only the top header (back button) hides on idle. Set to
     * false to restore the old behavior where both strips auto-hide together.
     */
    public void setKeepControlsVisible(boolean enabled) {
        if (keepControlsVisible == enabled) {
            return;
        }
        keepControlsVisible = enabled;
        runOnMain(() -> {
            if (!visible || bottomBar == null) {
                return;
            }
            if (enabled) {
                showBottomBarPersistent();
            } else if (!chromeShown) {
                hideChrome(true);
            }
        });
    }

    public void show(DidlMetadata metadata, MediaKind kind) {
        runOnMain(() -> showInternal(metadata, kind));
    }

    public void hide() {
        runOnMain(this::hideInternal);
    }

    public void fadeOutAndHide() {
        runOnMain(() -> {
            if (!visible || root == null) {
                hideInternal();
                return;
            }
            root.animate().cancel();
            root.animate()
                    .alpha(0f)
                    .setDuration(380)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(this::hideInternal)
                    .start();
        });
    }

    public void updateMetadata(DidlMetadata metadata, MediaKind kind) {
        runOnMain(() -> {
            currentKind = kind;
            applyKindLayout(kind);
            bindMetadata(metadata);
            if (keepControlsVisible) {
                showBottomBarPersistent();
            }
            // New track: reset the scrubber instead of leaving the previous
            // track's position/total on screen. If the controller declared a
            // duration in DIDL, show it immediately rather than waiting for
            // MediaPlayer to (maybe) report one once playback actually starts.
            if (!userSeeking && seekBar != null) {
                setSeekBarProgress(0);
            }
            if (timeCurrentView != null) {
                timeCurrentView.setText(formatTime(0));
            }
            if (timeTotalView != null) {
                long knownDuration = metadata != null ? metadata.durationMs : -1;
                if (knownDuration > 0) {
                    lastKnownDurationMs = knownDuration;
                }
                timeTotalView.setText(formatTime(knownDuration > 0 ? knownDuration : 0));
            }
        });
    }

    public void updatePlayback(boolean playing, long positionMs, long durationMs) {
        runOnMain(() -> updatePlaybackInternal(playing, positionMs, durationMs));
    }

    /** Called after MediaPlayer confirms a seek (local scrub or DLNA Seek action). */
    public void notifySeekApplied(long positionMs, long durationMs, boolean playing) {
        runOnMain(() -> {
            userSeeking = false;
            updatePlaybackInternal(playing, positionMs, durationMs);
        });
    }

    private void updatePlaybackInternal(boolean playing, long positionMs, long durationMs) {
        if (!visible || playIconView == null) {
            return;
        }
        playIconView.setGlyphPath(playing ? GLYPH_PAUSE : GLYPH_PLAY_ARROW);
        if (durationMs > 0) {
            lastKnownDurationMs = durationMs;
        }
        if (!userSeeking && durationMs > 0 && seekBar != null) {
            int max = seekBar.getMax();
            int target = (int) (positionMs * max / durationMs);
            animateSeekBarTo(target, playing);
        }
        if (!userSeeking && timeCurrentView != null) {
            timeCurrentView.setText(formatTime(positionMs));
        }
        if (durationMs > 0 && timeTotalView != null) {
            timeTotalView.setText(formatTime(durationMs));
        }
    }

    /**
     * Ticks arrive every {@link PlaybackEngine}-side polling interval (currently
     * ~500ms), which looks jerky if we just snap the bar to each new value. We
     * instead glide from the current on-screen progress to the new one over the
     * same window, matching the smooth motion of GlassMusicPlayerView. A large
     * jump (track change, real seek confirmation) snaps instantly instead of
     * playing an unnatural slow-motion catch-up.
     */
    private void animateSeekBarTo(int target, boolean playing) {
        if (seekBar == null) {
            return;
        }
        int current = seekBar.getProgress();
        int max = Math.max(1, seekBar.getMax());
        int delta = Math.abs(target - current);
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        if (!playing || delta == 0 || delta > max / 10) {
            setSeekBarProgress(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(current, target);
        animator.setDuration(PROGRESS_TICK_ANIM_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            if (userSeeking) {
                a.cancel();
                return;
            }
            setSeekBarProgress((int) a.getAnimatedValue());
        });
        animator.start();
        progressAnimator = animator;
    }

    /** Avoids the implicit progress-catchup animation ProgressBar has run since API 26. */
    private void setSeekBarProgress(int progress) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            seekBar.setProgress(progress, false);
        } else {
            seekBar.setProgress(progress);
        }
    }

    public void destroy() {
        runOnMain(this::hideInternal);
        artExecutor.shutdownNow();
    }

    public void bringToFrontIfActive(Context ignored) {
        runOnMain(() -> {
            if (!visible || root == null || layoutParams == null) {
                return;
            }
            try {
                if (!root.isAttachedToWindow()) {
                    windowManager.addView(root, layoutParams);
                    return;
                }
                if (hasLiveDecodeSurface()) {
                    windowManager.updateViewLayout(root, layoutParams);
                } else {
                    int vis = root.getVisibility();
                    float alpha = root.getAlpha();
                    windowManager.removeView(root);
                    windowManager.addView(root, layoutParams);
                    root.setVisibility(vis);
                    root.setAlpha(alpha);
                }
            } catch (Exception e) {
                Log.w(TAG, "bringToFront failed", e);
            }
        });
    }

    /** True while video decode has an active Surface bound to MediaPlayer. */
    private boolean hasLiveDecodeSurface() {
        Surface s = videoSurface;
        return currentKind == MediaKind.VIDEO && s != null && s.isValid();
    }

    /** Drop overlay window state after a failed initial addView in showInternal. */
    private void resetOverlayWindowState() {
        visible = false;
        try {
            if (root != null && root.isAttachedToWindow()) {
                windowManager.removeView(root);
            }
        } catch (Exception ignored) {
        }
        root = null;
        layoutParams = null;
        videoSurface = null;
    }

    // ------------------------------------------------------------------
    // Show / hide
    // ------------------------------------------------------------------

    private void showInternal(DidlMetadata metadata, MediaKind kind) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission missing; cinema UI skipped");
            return;
        }
        currentKind = kind;
        if (root == null) {
            buildViewHierarchy();
            layoutParams = createLayoutParams();
        }
        applyKindLayout(kind);
        bindMetadata(metadata);
        if (!root.isAttachedToWindow()) {
            try {
                root.setAlpha(0f);
                windowManager.addView(root, layoutParams);
                root.animate().alpha(1f).setDuration(CHROME_SLIDE_IN_MS).start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to add cinema overlay", e);
                resetOverlayWindowState();
                return;
            }
        }
        applyImmersive(root);
        hideChrome(false);
        if (keepControlsVisible) {
            showBottomBarPersistent();
        }
        visible = true;
    }

    /** Shows the bottom bar outside of the chrome reveal/hide dance, per {@link #keepControlsVisible}. */
    private void showBottomBarPersistent() {
        if (bottomBar == null) {
            return;
        }
        bottomBar.animate().cancel();
        bottomBar.setTranslationY(0f);
        bottomBar.setAlpha(1f);
        bottomBar.setVisibility(View.VISIBLE);
    }

    private void hideInternal() {
        visible = false;
        videoSurface = null;
        mainHandler.removeCallbacks(chromeHideRunnable);
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        if (root == null) {
            return;
        }
        try {
            if (root.isAttachedToWindow()) {
                windowManager.removeView(root);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove cinema overlay", e);
        }
        root = null;
        layoutParams = null;
    }

    // ------------------------------------------------------------------
    // Chrome (top strip + bottom bar + video badge)
    // ------------------------------------------------------------------

    private void revealChrome() {
        mainHandler.removeCallbacks(chromeHideRunnable);
        if (chromeShown) {
            scheduleChromeAutoHide();
            return;
        }
        chromeShown = true;

        if (chromeStrip != null) {
            chromeStrip.setVisibility(View.VISIBLE);
            chromeStrip.animate().cancel();
            chromeStrip.animate()
                    .translationY(0f)
                    .setDuration(CHROME_SLIDE_IN_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        if (bottomBar != null && !keepControlsVisible) {
            bottomBar.setVisibility(View.VISIBLE);
            bottomBar.animate().cancel();
            bottomBar.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(CHROME_SLIDE_IN_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        updateVideoBadgeVisibility(true, true);
        if (keepControlsVisible) {
            showBottomBarPersistent();
        }
        scheduleChromeAutoHide();
    }

    private void hideChrome(boolean animated) {
        mainHandler.removeCallbacks(chromeHideRunnable);
        if (!chromeShown
                && (chromeStrip == null || chromeStrip.getTranslationY() <= -chromeSlidePx)
                && (keepControlsVisible || bottomBar == null || bottomBar.getTranslationY() >= bottomSlidePx)) {
            updateVideoBadgeVisibility(false, animated);
            return;
        }
        chromeShown = false;

        if (chromeStrip != null) {
            float target = -chromeSlidePx;
            chromeStrip.animate().cancel();
            if (!animated || chromeSlidePx <= 0) {
                chromeStrip.setTranslationY(target);
                chromeStrip.setVisibility(View.GONE);
            } else {
                chromeStrip.animate()
                        .translationY(target)
                        .setDuration(CHROME_SLIDE_OUT_MS)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            if (!chromeShown) {
                                chromeStrip.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }

        if (bottomBar != null && !keepControlsVisible) {
            float target = bottomSlidePx;
            bottomBar.animate().cancel();
            if (!animated || bottomSlidePx <= 0) {
                bottomBar.setTranslationY(target);
                bottomBar.setVisibility(View.GONE);
            } else {
                bottomBar.animate()
                        .translationY(target)
                        .alpha(0.85f)
                        .setDuration(CHROME_SLIDE_OUT_MS)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            if (!chromeShown) {
                                bottomBar.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }

        updateVideoBadgeVisibility(false, animated);
    }

    private void updateVideoBadgeVisibility(boolean show, boolean animated) {
        if (badgeView == null) {
            return;
        }
        if (currentKind == MediaKind.AUDIO) {
            badgeView.setVisibility(View.VISIBLE);
            badgeView.setAlpha(BADGE_ALPHA / 255f);
            return;
        }
        badgeView.animate().cancel();
        if (show) {
            badgeView.setVisibility(View.VISIBLE);
            if (animated) {
                badgeView.setAlpha(0f);
                badgeView.animate()
                        .alpha(BADGE_ALPHA / 255f)
                        .setDuration(CHROME_SLIDE_IN_MS)
                        .start();
            } else {
                badgeView.setAlpha(BADGE_ALPHA / 255f);
            }
        } else {
            if (animated) {
                badgeView.animate()
                        .alpha(0f)
                        .setDuration(CHROME_SLIDE_OUT_MS)
                        .withEndAction(() -> badgeView.setVisibility(View.GONE))
                        .start();
            } else {
                badgeView.setAlpha(0f);
                badgeView.setVisibility(View.GONE);
            }
        }
    }

    private void scheduleChromeAutoHide() {
        mainHandler.removeCallbacks(chromeHideRunnable);
        mainHandler.postDelayed(chromeHideRunnable, CHROME_AUTO_HIDE_MS);
    }

    private void onUserTouch() {
        revealChrome();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void buildViewHierarchy() {
        int safe = safeInsetPx();

        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(false);
        applyImmersive(root);
        root.setOnSystemUiVisibilityChangeListener(visibility -> applyImmersive(root));
        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                onUserTouch();
            }
            return false;
        });

        textureView = new TextureView(context);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surfaceTexture, int width, int height) {
                videoSurface = new Surface(surfaceTexture);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surfaceTexture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surfaceTexture) {
                videoSurface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {
            }
        });
        root.addView(textureView);

        // Immersive audio cover — full bleed CENTER_CROP (GlassMusicPlayerView pattern).
        FrameLayout coverHost = new FrameLayout(context);
        coverHost.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        coverView = new ImageView(context);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        coverView.setScaleX(1.15f);
        coverView.setScaleY(1.15f);
        coverHost.addView(coverView);
        root.addView(coverHost);

        coverVignette = new View(context);
        GradientDrawable vignette = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x33000000, 0x99000000, 0xE6000000});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            coverVignette.setBackground(vignette);
        }
        coverVignette.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(coverVignette);

        root.addView(makeGradient(false));

        badgeView = new ImageView(context);
        badgeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badgeView.setImageBitmap(loadDlnaBadgeBitmap());
        applyBadgeLayout();
        root.addView(badgeView);

        chromeStrip = buildChromeStrip(safe);
        root.addView(chromeStrip);

        bottomBar = buildBottomBar(safe);
        root.addView(bottomBar);

        root.post(this::measureChromeSlides);
    }

    private FrameLayout buildChromeStrip(int safe) {
        FrameLayout strip = new FrameLayout(context);
        strip.setVisibility(View.GONE);

        GradientDrawable shadow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        0x38400000,
                        0x30000000,
                        0x24000000,
                        0x18000000,
                        0x0C000000,
                        0x04000000,
                        0x00000000
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            strip.setBackground(shadow);
        }

        FrameLayout.LayoutParams stripLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        strip.setLayoutParams(stripLp);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP);
        rowLp.setMargins(safe, safe, safe, dp(16));
        row.setLayoutParams(rowLp);

        TextView backPill = new TextView(context);
        backPill.setText("‹ 返回");
        backPill.setTextColor(0xCCFFFFFF);
        backPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        backPill.setPadding(dp(14), dp(10), dp(18), dp(10));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(999));
        pillBg.setColor(0x66121824);
        pillBg.setStroke(dp(1), 0x14FFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            backPill.setBackground(pillBg);
        }
        backPill.setOnClickListener(v -> {
            if (callback != null) {
                callback.onStop();
            }
        });
        row.addView(backPill);
        strip.addView(row);
        return strip;
    }

    private LinearLayout buildBottomBar(int safe) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int sideMargin = dp(BOTTOM_BAR_SIDE_MARGIN_DP);
        bottomLp.setMargins(sideMargin, 0, sideMargin, safe);
        bar.setLayoutParams(bottomLp);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(2);
        bar.addView(titleView);

        subtitleView = new TextView(context);
        subtitleView.setTextColor(0x99FFFFFF);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setPadding(0, dp(2), 0, dp(8));
        bar.addView(subtitleView);

        LinearLayout scrubRow = new LinearLayout(context);
        scrubRow.setOrientation(LinearLayout.HORIZONTAL);
        scrubRow.setGravity(Gravity.CENTER_VERTICAL);

        timeCurrentView = monoText("0:00");
        scrubRow.addView(timeCurrentView);

        seekBar = new SeekBar(context);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        seekLp.leftMargin = dp(10);
        seekLp.rightMargin = dp(10);
        seekBar.setLayoutParams(seekLp);
        seekBar.setMax(1000);
        styleThinSeekBar(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                long dur = resolveSeekDurationMs();
                if (dur > 0) {
                    timeCurrentView.setText(formatTime((long) progress * dur / bar.getMax()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                if (progressAnimator != null) {
                    progressAnimator.cancel();
                    progressAnimator = null;
                }
                onUserTouch();
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                long dur = resolveSeekDurationMs();
                if (dur > 0 && callback != null) {
                    long positionMs = (long) bar.getProgress() * dur / bar.getMax();
                    callback.onSeek(positionMs);
                    timeCurrentView.setText(formatTime(positionMs));
                    // Fallback if MediaPlayer never fires onSeekComplete (non-seekable stream).
                    mainHandler.postDelayed(() -> userSeeking = false, 1500);
                } else {
                    userSeeking = false;
                }
            }
        });
        scrubRow.addView(seekBar);

        timeTotalView = monoText("0:00");
        scrubRow.addView(timeTotalView);
        bar.addView(scrubRow);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, 0);

        controls.addView(createShuffleButton());

        View prev = createSkipButton(false);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        prevLp.leftMargin = dp(8);
        controls.addView(prev, prevLp);

        View play = createPlayButton();
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        playLp.leftMargin = dp(32);
        playLp.rightMargin = dp(32);
        controls.addView(play, playLp);

        View next = createSkipButton(true);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        nextLp.rightMargin = dp(8);
        controls.addView(next, nextLp);

        controls.addView(createRepeatButton());

        bar.addView(controls);
        return bar;
    }

    private View createPlayButton() {
        playIconView = new VectorIconView(context, GLYPH_PLAY_ARROW, PLAY_ICON_DP, ICON_ACTIVE);
        View wrap = wrapControlGlyph(playIconView);
        wrap.setOnClickListener(v -> {
            onUserTouch();
            if (callback != null) {
                callback.onPlayPause();
            }
        });
        return wrap;
    }

    /** Resolves Ava's live theme accent (AppColors.kt: dark_mode ? AccentBrown : AccentBlue). */
    private int resolveAccentColor() {
        try {
            boolean darkMode = context
                    .getSharedPreferences(HOME_PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_DARK_MODE, false);
            return darkMode ? ACCENT_BROWN : ACCENT_BLUE;
        } catch (Exception e) {
            return ACCENT_BLUE;
        }
    }

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
        // No visible thumb — thin track only. Transparent touch target enables drag.
        bar.setThumb(createInvisibleSeekThumb());
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

    /** Invisible 28dp touch target; keeps the original thumbless thin-bar look. */
    private Drawable createInvisibleSeekThumb() {
        int touch = dp(28);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.TRANSPARENT);
        thumb.setSize(touch, touch);
        return thumb;
    }

    private View wrapControlGlyph(View glyph) {
        FrameLayout wrap = new FrameLayout(context);
        wrap.setBackgroundColor(Color.TRANSPARENT);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        wrap.addView(glyph, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private View createShuffleButton() {
        shuffleIconView = new VectorIconView(context, GLYPH_SHUFFLE, SHUFFLE_REPEAT_ICON_DP,
                shuffleEnabled ? ICON_ACTIVE : ICON_INACTIVE);
        View wrap = wrapControlGlyph(shuffleIconView);
        wrap.setOnClickListener(v -> {
            onUserTouch();
            shuffleEnabled = !shuffleEnabled;
            shuffleIconView.setTint(shuffleEnabled ? ICON_ACTIVE : ICON_INACTIVE);
            if (callback != null) {
                callback.onShuffleChanged(shuffleEnabled);
            }
        });
        return wrap;
    }

    private View createRepeatButton() {
        repeatIconView = new VectorIconView(context, glyphForRepeatMode(repeatMode),
                SHUFFLE_REPEAT_ICON_DP, tintForRepeatMode(repeatMode));
        View wrap = wrapControlGlyph(repeatIconView);
        wrap.setOnClickListener(v -> {
            onUserTouch();
            repeatMode = repeatMode.next();
            repeatIconView.setGlyphPath(glyphForRepeatMode(repeatMode));
            repeatIconView.setTint(tintForRepeatMode(repeatMode));
            if (callback != null) {
                callback.onRepeatModeChanged(repeatMode.id);
            }
        });
        return wrap;
    }

    private static Path glyphForRepeatMode(RepeatMode mode) {
        return mode == RepeatMode.ONE ? GLYPH_REPEAT_ONE : GLYPH_REPEAT;
    }

    private static int tintForRepeatMode(RepeatMode mode) {
        return mode == RepeatMode.OFF ? ICON_INACTIVE : ICON_ACTIVE;
    }

    private View createSkipButton(boolean forward) {
        Path glyph = forward ? GLYPH_SKIP_NEXT : GLYPH_SKIP_PREVIOUS;
        VectorIconView view = new VectorIconView(context, glyph, SKIP_ICON_DP, ICON_SKIP_TINT);
        View wrap = wrapControlGlyph(view);
        wrap.setOnClickListener(v -> {
            onUserTouch();
            if (callback != null) {
                if (forward) {
                    callback.onSkipNext();
                } else {
                    callback.onSkipPrevious();
                }
            }
        });
        return wrap;
    }

    private void measureChromeSlides() {
        if (chromeStrip != null) {
            chromeStrip.measure(
                    View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            chromeSlidePx = chromeStrip.getMeasuredHeight();
            if (chromeSlidePx <= 0) {
                chromeSlidePx = dp(120);
            }
            chromeStrip.setTranslationY(-chromeSlidePx);
            chromeStrip.setVisibility(View.GONE);
        }

        if (bottomBar != null) {
            bottomBar.measure(
                    View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            bottomSlidePx = bottomBar.getMeasuredHeight();
            if (bottomSlidePx <= 0) {
                bottomSlidePx = dp(160);
            }
            if (keepControlsVisible) {
                showBottomBarPersistent();
            } else {
                bottomBar.setTranslationY(bottomSlidePx);
                bottomBar.setVisibility(View.GONE);
            }
        }

        chromeShown = false;
        applyKindLayout(currentKind);
    }

    private void applyBadgeLayout() {
        if (badgeView == null) {
            return;
        }
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float vmin = Math.min(dm.widthPixels, dm.heightPixels);
        float density = dm.density;
        int sizePx = Math.round(Math.max(BADGE_SIZE_MIN_DP * density,
                Math.min(BADGE_SIZE_MAX_DP * density, vmin * BADGE_SIZE_VMIN)));
        int edgePx = Math.round(Math.max(BADGE_EDGE_MIN_DP * density, vmin * BADGE_EDGE_VMIN));
        int cutoutTop = 0;
        int cutoutEnd = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && root != null && root.getRootWindowInsets() != null
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

    private Bitmap loadDlnaBadgeBitmap() {
        if (dlnaBadgeBitmap != null && !dlnaBadgeBitmap.isRecycled()) {
            return dlnaBadgeBitmap;
        }
        InputStream in = null;
        try {
            ClassLoader loader = CinemaOverlay.class.getClassLoader();
            if (loader != null) {
                in = loader.getResourceAsStream(BADGE_ASSET);
            }
            if (in == null) {
                in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BADGE_ASSET);
            }
            if (in != null) {
                dlnaBadgeBitmap = BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load DLNA badge asset", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return dlnaBadgeBitmap;
    }

    private long resolveSeekDurationMs() {
        if (lastKnownDurationMs > 0) {
            return lastKnownDurationMs;
        }
        try {
            String total = timeTotalView.getText().toString();
            return parseTime(total);
        } catch (Exception e) {
            return 0;
        }
    }

    private TextView monoText(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xB3FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setMinWidth(dp(36));
        return tv;
    }

    private TextView iconButton(String label, int sp) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return tv;
    }

    private View makeGradient(boolean top) {
        View v = new View(context);
        GradientDrawable g = new GradientDrawable(
                top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                top ? new int[]{0xB3000000, 0x00000000} : new int[]{0xD9000000, 0x00000000});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            v.setBackground(g);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(120),
                top ? Gravity.TOP : Gravity.BOTTOM);
        v.setLayoutParams(lp);
        return v;
    }

    private void applyKindLayout(MediaKind kind) {
        if (textureView == null) {
            return;
        }
        boolean video = kind == MediaKind.VIDEO;
        textureView.setVisibility(video ? View.VISIBLE : View.GONE);
        coverView.setVisibility(video ? View.GONE : View.VISIBLE);
        if (coverVignette != null) {
            coverVignette.setVisibility(video ? View.GONE : View.VISIBLE);
        }
        updateVideoBadgeVisibility(chromeShown && video, false);
        if (currentKind == MediaKind.AUDIO && badgeView != null) {
            badgeView.setVisibility(View.VISIBLE);
            badgeView.setAlpha(BADGE_ALPHA / 255f);
        }
    }

    private void bindMetadata(DidlMetadata metadata) {
        if (metadata == null) {
            metadata = DidlMetadata.EMPTY;
        }
        String title = metadata.title.isEmpty() ? "DLNA 播放" : metadata.title;
        titleView.setText(title);
        subtitleView.setText(metadata.subtitleLine());
        if (metadata.durationMs > 0) {
            lastKnownDurationMs = metadata.durationMs;
            if (timeTotalView != null) {
                timeTotalView.setText(formatTime(metadata.durationMs));
            }
        }
        loadCoverArt(metadata.albumArtUri);
    }

    /**
     * Loads new cover art without ever clearing the ImageView first: the old
     * request could still be in flight when a track changes rapidly, and
     * blanking to null immediately exposes the black window background behind
     * it. We keep showing whatever art is already on screen and crossfade to
     * the new one only once it has actually loaded.
     */
    private void loadCoverArt(String url) {
        final long requestId = ++artRequestId;
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        final String artUrl = url.trim();
        artExecutor.execute(() -> {
            Bitmap bitmap = fetchBitmap(artUrl);
            if (bitmap == null) {
                return;
            }
            mainHandler.post(() -> {
                // A newer track/art request superseded this one, or the overlay
                // is gone by the time the network fetch finished.
                if (requestId != artRequestId || !visible || currentKind != MediaKind.AUDIO) {
                    return;
                }
                applyCoverArt(bitmap);
            });
        });
    }

    private void applyCoverArt(Bitmap bitmap) {
        Drawable previous = coverView.getDrawable();
        Drawable next = new BitmapDrawable(context.getResources(), bitmap);
        if (previous == null) {
            coverView.setImageDrawable(next);
            return;
        }
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{previous, next});
        transition.setCrossFadeEnabled(true);
        coverView.setImageDrawable(transition);
        transition.startTransition(COVER_CROSSFADE_MS);
    }

    private Bitmap fetchBitmap(String urlString) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.connect();
            in = conn.getInputStream();
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Log.d(TAG, "Cover art load failed: " + urlString);
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
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

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
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
        return Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.05f);
    }

    private int dp(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static long parseTime(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] parts = text.split(":");
        try {
            if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600
                        + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2])) * 1000;
            }
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    /**
     * Single reusable glyph renderer for all bottom-bar controls (shuffle, repeat,
     * skip prev/next, play/pause). Every instance just fills a Material Icons
     * {@link Path} (see PATH_* constants above) scaled into its own square — same
     * rendering path for every control keeps their weight/anti-aliasing identical
     * instead of five hand-tuned canvas drawings.
     */
    private static final class VectorIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float iconSizePx;
        private Path glyphPath;

        VectorIconView(Context context, Path initialPath, float iconSizeDp, int initialColor) {
            super(context);
            this.glyphPath = initialPath;
            this.iconSizePx = iconSizeDp * context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(initialColor);
            setBackgroundColor(Color.TRANSPARENT);
        }

        void setGlyphPath(Path path) {
            if (glyphPath == path) {
                return;
            }
            glyphPath = path;
            invalidate();
        }

        void setTint(int color) {
            if (paint.getColor() == color) {
                return;
            }
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (glyphPath == null) {
                return;
            }
            float scale = iconSizePx / 24f;
            int saved = canvas.save();
            canvas.translate((getWidth() - iconSizePx) / 2f, (getHeight() - iconSizePx) / 2f);
            canvas.scale(scale, scale);
            canvas.drawPath(glyphPath, paint);
            canvas.restoreToCount(saved);
        }
    }

    /**
     * Minimal parser for the subset of Android vector-drawable / SVG pathData
     * syntax used by Material Icons glyphs (move/line/horizontal/vertical/close,
     * absolute + relative, with implicit repeated commands). No curves are needed
     * for the icons above.
     */
    private static Path parsePathData(String data) {
        Path path = new Path();
        float curX = 0f;
        float curY = 0f;
        float startX = 0f;
        float startY = 0f;
        for (String token : PATH_COMMAND_SPLIT.split(data)) {
            if (token.isEmpty()) {
                continue;
            }
            char command = token.charAt(0);
            boolean relative = Character.isLowerCase(command);
            List<Float> nums = new ArrayList<>();
            Matcher m = PATH_NUMBER.matcher(token.substring(1));
            while (m.find()) {
                nums.add(Float.parseFloat(m.group()));
            }
            int i = 0;
            switch (Character.toUpperCase(command)) {
                case 'M': {
                    boolean first = true;
                    while (i + 1 < nums.size()) {
                        float x = nums.get(i++);
                        float y = nums.get(i++);
                        if (relative) {
                            x += curX;
                            y += curY;
                        }
                        if (first) {
                            path.moveTo(x, y);
                            startX = x;
                            startY = y;
                            first = false;
                        } else {
                            path.lineTo(x, y);
                        }
                        curX = x;
                        curY = y;
                    }
                    break;
                }
                case 'L': {
                    while (i + 1 < nums.size()) {
                        float x = nums.get(i++);
                        float y = nums.get(i++);
                        if (relative) {
                            x += curX;
                            y += curY;
                        }
                        path.lineTo(x, y);
                        curX = x;
                        curY = y;
                    }
                    break;
                }
                case 'H': {
                    while (i < nums.size()) {
                        float x = nums.get(i++);
                        if (relative) {
                            x += curX;
                        }
                        path.lineTo(x, curY);
                        curX = x;
                    }
                    break;
                }
                case 'V': {
                    while (i < nums.size()) {
                        float y = nums.get(i++);
                        if (relative) {
                            y += curY;
                        }
                        path.lineTo(curX, y);
                        curY = y;
                    }
                    break;
                }
                case 'Z': {
                    path.close();
                    curX = startX;
                    curY = startY;
                    break;
                }
                default:
                    break;
            }
        }
        return path;
    }
}
