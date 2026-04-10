package com.ava.mods.geckoview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * GeckoView Browser Mod Manager.
 *
 * Provides an independent Firefox-engine browser overlay controlled via
 * Home Assistant entities (switch for visibility, text for URL).
 *
 * GeckoView classes are loaded entirely via reflection so the mod JAR
 * itself has zero compile-time dependency on the GeckoView AAR.  The AAR
 * (or its extracted classes.jar + native .so) must be placed in the mod's
 * libs/ directory so that Ava's DexClassLoader picks them up at runtime.
 */
public class GeckoBrowserManager {

    private static final String TAG = "GeckoBrowserManager";

    private static final String GECKO_RUNTIME_CLASS  = "org.mozilla.geckoview.GeckoRuntime";
    private static final String GECKO_SESSION_CLASS   = "org.mozilla.geckoview.GeckoSession";
    private static final String GECKO_VIEW_CLASS      = "org.mozilla.geckoview.GeckoView";
    private static final String GECKO_SETTINGS_CLASS  = "org.mozilla.geckoview.GeckoRuntimeSettings";
    private static final String GECKO_SETTINGS_BUILDER = "org.mozilla.geckoview.GeckoRuntimeSettings$Builder";

    private static volatile GeckoBrowserManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // GeckoView instances (held as Object — loaded via reflection)
    private Object geckoRuntime;   // GeckoRuntime
    private Object geckoSession;   // GeckoSession
    private Object geckoView;      // GeckoView (android.view.View subclass)
    private ClassLoader geckoClassLoader;

    // Overlay state
    private WindowManager windowManager;
    private FrameLayout containerView;
    private boolean isVisible = false;

    // Current state
    private volatile String currentUrl = "";
    private volatile String defaultUrl = "";
    private volatile boolean javascriptEnabled = true;
    private volatile boolean touchEnabled = true;
    private volatile String userAgentMode = "default";
    private volatile String lastError = "";

    // ---------------------------------------------------------------
    // Singleton
    // ---------------------------------------------------------------

    private GeckoBrowserManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static GeckoBrowserManager getInstance(Context context) {
        if (instance == null) {
            synchronized (GeckoBrowserManager.class) {
                if (instance == null) {
                    instance = new GeckoBrowserManager(context);
                }
            }
        }
        return instance;
    }

    // ---------------------------------------------------------------
    // Config (called by ModEntityFactory via reflection)
    // ---------------------------------------------------------------

    public void applyConfig(String key, String value) {
        if (key == null || value == null) return;
        switch (key) {
            case "default_url":
                defaultUrl = value.trim();
                break;
            case "javascript_enabled":
                javascriptEnabled = "true".equalsIgnoreCase(value.trim());
                break;
            case "touch_enabled":
                touchEnabled = "true".equalsIgnoreCase(value.trim());
                break;
            case "user_agent":
                userAgentMode = value.trim();
                break;
        }
    }

    public void setLastError(String error) {
        this.lastError = error;
    }

    // ---------------------------------------------------------------
    // Entity actions
    // ---------------------------------------------------------------

    /** Switch ON — show GeckoView overlay */
    public void showBrowser() {
        mainHandler.post(() -> {
            if (isVisible) return;
            try {
                ensureGeckoRuntime();
                createOverlay();
                String url = currentUrl.isEmpty() ? defaultUrl : currentUrl;
                if (!url.isEmpty()) {
                    doLoadUrl(url);
                }
                isVisible = true;
                Log.d(TAG, "Browser shown");
            } catch (Exception e) {
                Log.e(TAG, "Failed to show browser", e);
                showToast("GeckoView failed: " + e.getMessage());
            }
        });
    }

    /** Switch OFF — hide GeckoView overlay */
    public void hideBrowser() {
        mainHandler.post(() -> {
            if (!isVisible) return;
            removeOverlay();
            isVisible = false;
            Log.d(TAG, "Browser hidden");
        });
    }

    /** Text entity setter — load URL */
    public void loadUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String normalized = normalizeUrl(url.trim());
        currentUrl = normalized;
        if (isVisible) {
            mainHandler.post(() -> doLoadUrl(normalized));
        }
    }

    /** Text entity reader — return current URL */
    public String getCurrentUrl() {
        return currentUrl;
    }

    /** Called when mod is disabled */
    public void onDestroy() {
        mainHandler.post(() -> {
            removeOverlay();
            closeSession();
            isVisible = false;
            currentUrl = "";
            Log.d(TAG, "Destroyed");
        });
    }

    // ---------------------------------------------------------------
    // GeckoView runtime initialization (reflection)
    // ---------------------------------------------------------------

    private void ensureGeckoRuntime() throws Exception {
        if (geckoRuntime != null) return;

        // Discover the ClassLoader that has GeckoView classes.
        // Ava's DexClassLoader for this mod should already include the
        // GeckoView JAR if placed in libs/.  We use the current thread's
        // context class loader first, then fall back to this class's loader.
        geckoClassLoader = findGeckoClassLoader();
        if (geckoClassLoader == null) {
            throw new RuntimeException(
                "GeckoView classes not found. Place the GeckoView AAR " +
                "(or classes.jar + native .so) in the mod's libs/ directory.");
        }

        Log.d(TAG, "Initializing GeckoRuntime...");

        // GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder();
        Class<?> builderClass = geckoClassLoader.loadClass(GECKO_SETTINGS_BUILDER);
        Object builder = builderClass.getConstructor().newInstance();

        // builder.javaScriptEnabled(true)
        try {
            Method jsMethod = builderClass.getMethod("javaScriptEnabled", boolean.class);
            jsMethod.invoke(builder, javascriptEnabled);
        } catch (NoSuchMethodException ignored) { }

        // GeckoRuntimeSettings settings = builder.build()
        Method buildMethod = builderClass.getMethod("build");
        Object settings = buildMethod.invoke(builder);

        // GeckoRuntime.create(context, settings)
        Class<?> runtimeClass = geckoClassLoader.loadClass(GECKO_RUNTIME_CLASS);
        Class<?> settingsClass = geckoClassLoader.loadClass(GECKO_SETTINGS_CLASS);
        Method createMethod = runtimeClass.getMethod("create", Context.class, settingsClass);
        geckoRuntime = createMethod.invoke(null, context, settings);

        Log.d(TAG, "GeckoRuntime initialized successfully");
    }

    private ClassLoader findGeckoClassLoader() {
        // Try thread context loader (Ava sets this for mod loading)
        ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
        if (threadLoader != null && canLoadGecko(threadLoader)) {
            return threadLoader;
        }
        // Try this class's loader (DexClassLoader from ModManager)
        ClassLoader ownLoader = GeckoBrowserManager.class.getClassLoader();
        if (ownLoader != null && canLoadGecko(ownLoader)) {
            return ownLoader;
        }
        return null;
    }

    private boolean canLoadGecko(ClassLoader loader) {
        try {
            loader.loadClass(GECKO_RUNTIME_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Overlay window management
    // ---------------------------------------------------------------

    private void createOverlay() throws Exception {
        if (containerView != null) return;

        containerView = new FrameLayout(context);
        containerView.setBackgroundColor(Color.BLACK);

        // Try to create a real GeckoView; fall back to placeholder on failure
        try {
            createGeckoView();
        } catch (Exception e) {
            Log.w(TAG, "Cannot create GeckoView, using fallback WebView", e);
            createFallbackWebView();
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // Touch → make focusable
        containerView.setOnTouchListener((v, event) -> {
            if (!touchEnabled) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                makeFocusable(params);
            }
            return false;
        });

        windowManager.addView(containerView, params);
        Log.d(TAG, "Overlay added");
    }

    private void createGeckoView() throws Exception {
        // GeckoView view = new GeckoView(context)
        Class<?> viewClass = geckoClassLoader.loadClass(GECKO_VIEW_CLASS);
        Constructor<?> ctor = viewClass.getConstructor(Context.class);
        geckoView = ctor.newInstance(context);

        // GeckoSession session = new GeckoSession()
        Class<?> sessionClass = geckoClassLoader.loadClass(GECKO_SESSION_CLASS);
        geckoSession = sessionClass.getConstructor().newInstance();

        // session.open(runtime)
        Class<?> runtimeClass = geckoClassLoader.loadClass(GECKO_RUNTIME_CLASS);
        Method openMethod = sessionClass.getMethod("open", runtimeClass);
        openMethod.invoke(geckoSession, geckoRuntime);

        // view.setSession(session)
        Method setSessionMethod = viewClass.getMethod("setSession", sessionClass);
        setSessionMethod.invoke(geckoView, geckoSession);

        View view = (View) geckoView;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        containerView.addView(view, lp);
        Log.d(TAG, "GeckoView created and attached");
    }

    /**
     * If GeckoView AAR is not present, create a standard WebView as fallback
     * so the mod still functions (just without the Firefox engine).
     */
    private void createFallbackWebView() {
        try {
            android.webkit.WebView webView = new android.webkit.WebView(context);
            webView.getSettings().setJavaScriptEnabled(javascriptEnabled);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webView.setWebViewClient(new android.webkit.WebViewClient());

            geckoView = webView;
            geckoSession = null; // no session for WebView fallback

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            );
            containerView.addView(webView, lp);
            Log.d(TAG, "Fallback WebView created");
        } catch (Exception e) {
            Log.e(TAG, "Fallback WebView also failed", e);
            // Add a text placeholder
            TextView tv = new TextView(context);
            tv.setText("GeckoView not available.\nPlace GeckoView AAR in mod libs/ directory.");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(48, 48, 48, 48);
            containerView.addView(tv);
        }
    }

    private void removeOverlay() {
        if (containerView != null) {
            try {
                // Remove GeckoView from container before removing from WM
                if (geckoView instanceof View) {
                    containerView.removeView((View) geckoView);
                }
                windowManager.removeView(containerView);
            } catch (Exception e) {
                Log.w(TAG, "Error removing overlay", e);
            }
            containerView = null;
        }
        closeSession();
    }

    private void closeSession() {
        if (geckoSession != null) {
            try {
                Method closeMethod = geckoSession.getClass().getMethod("close");
                closeMethod.invoke(geckoSession);
            } catch (Exception e) {
                Log.w(TAG, "Error closing GeckoSession", e);
            }
            geckoSession = null;
        }
        geckoView = null;
    }

    private void makeFocusable(WindowManager.LayoutParams params) {
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            windowManager.updateViewLayout(containerView, params);
        } catch (Exception e) {
            Log.w(TAG, "Failed to make focusable", e);
        }
    }

    // ---------------------------------------------------------------
    // URL loading
    // ---------------------------------------------------------------

    private void doLoadUrl(String url) {
        if (geckoSession != null) {
            // GeckoSession: session.loadUri(url)
            try {
                Method loadUri = geckoSession.getClass().getMethod("loadUri", String.class);
                loadUri.invoke(geckoSession, url);
                currentUrl = url;
                Log.d(TAG, "GeckoSession loading: " + url);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load URL in GeckoSession", e);
            }
        } else if (geckoView instanceof android.webkit.WebView) {
            // Fallback WebView
            ((android.webkit.WebView) geckoView).loadUrl(url);
            currentUrl = url;
            Log.d(TAG, "Fallback WebView loading: " + url);
        }
    }

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    private String normalizeUrl(String url) {
        if (url.isEmpty()) return url;
        if (!url.contains("://")) {
            return "https://" + url;
        }
        return url;
    }

    private void showToast(String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) { }
    }
}
