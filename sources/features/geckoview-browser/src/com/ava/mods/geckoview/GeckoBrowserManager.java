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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GeckoView Browser Mod Manager.
 *
 * On first use, automatically downloads GeckoView AAR from Mozilla Maven,
 * extracts classes.jar and native .so files, then loads them via reflection.
 *
 * Provides an independent Firefox-engine browser overlay controlled via
 * Home Assistant entities (switch for visibility, text for URL).
 */
public class GeckoBrowserManager {

    private static final String TAG = "GeckoBrowserManager";

    private static final String GECKO_RUNTIME_CLASS   = "org.mozilla.geckoview.GeckoRuntime";
    private static final String GECKO_SESSION_CLASS    = "org.mozilla.geckoview.GeckoSession";
    private static final String GECKO_VIEW_CLASS       = "org.mozilla.geckoview.GeckoView";
    private static final String GECKO_SETTINGS_CLASS   = "org.mozilla.geckoview.GeckoRuntimeSettings";
    private static final String GECKO_SETTINGS_BUILDER = "org.mozilla.geckoview.GeckoRuntimeSettings$Builder";

    // Mozilla Maven URL for arm64-v8a stable channel
    private static final String GECKO_VERSION = "149.0.20260403140140";
    private static final String GECKO_AAR_URL =
        "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/"
        + GECKO_VERSION + "/geckoview-arm64-v8a-" + GECKO_VERSION + ".aar";

    private static volatile GeckoBrowserManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // GeckoView instances (held as Object — loaded via reflection)
    private Object geckoRuntime;
    private Object geckoSession;
    private Object geckoView;
    private ClassLoader geckoClassLoader;

    // Overlay state
    private WindowManager windowManager;
    private FrameLayout containerView;
    private boolean isVisible = false;
    private volatile boolean isDownloading = false;

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
    // Config
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

    public void showBrowser() {
        if (isVisible || isDownloading) return;

        File geckoDir = getGeckoDir();
        File classesJar = new File(geckoDir, "classes.jar");

        if (!classesJar.exists()) {
            // First time — download GeckoView AAR
            isDownloading = true;
            showDownloadOverlay();
            executor.execute(() -> {
                try {
                    downloadAndExtractGecko(geckoDir);
                    mainHandler.post(() -> {
                        isDownloading = false;
                        removeOverlay();
                        doShowBrowser();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Download failed", e);
                    mainHandler.post(() -> {
                        isDownloading = false;
                        updateDownloadStatus("Download failed: " + e.getMessage());
                        // Auto dismiss after 5s
                        mainHandler.postDelayed(() -> removeOverlay(), 5000);
                    });
                }
            });
        } else {
            doShowBrowser();
        }
    }

    private void doShowBrowser() {
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

    public void hideBrowser() {
        mainHandler.post(() -> {
            if (!isVisible) return;
            removeOverlay();
            isVisible = false;
            Log.d(TAG, "Browser hidden");
        });
    }

    public void loadUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String normalized = normalizeUrl(url.trim());
        currentUrl = normalized;
        if (isVisible) {
            mainHandler.post(() -> doLoadUrl(normalized));
        }
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

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
    // GeckoView AAR download and extraction
    // ---------------------------------------------------------------

    private File getGeckoDir() {
        return new File(context.getFilesDir(), "geckoview");
    }

    private void downloadAndExtractGecko(File geckoDir) throws Exception {
        // Clean up any previous partial download
        if (geckoDir.exists()) {
            deleteRecursive(geckoDir);
        }

        File tmpDir = new File(geckoDir.getParent(), "geckoview_tmp");
        if (tmpDir.exists()) deleteRecursive(tmpDir);
        tmpDir.mkdirs();

        File aarFile = new File(tmpDir, "geckoview.aar");

        // Download AAR
        mainHandler.post(() -> updateDownloadStatus("Downloading GeckoView (~84MB)..."));
        Log.d(TAG, "Downloading: " + GECKO_AAR_URL);

        HttpURLConnection conn = (HttpURLConnection) new URL(GECKO_AAR_URL).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Ava-Mod/1.0");

        int totalBytes = conn.getContentLength();
        int downloaded = 0;

        InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
        FileOutputStream fos = new FileOutputStream(aarFile);
        byte[] buffer = new byte[65536];
        int count;
        long lastProgress = 0;
        while ((count = in.read(buffer)) != -1) {
            fos.write(buffer, 0, count);
            downloaded += count;
            long now = System.currentTimeMillis();
            if (now - lastProgress > 500) {
                lastProgress = now;
                int pct = totalBytes > 0 ? (downloaded * 100 / totalBytes) : -1;
                final String msg = pct >= 0
                    ? "Downloading... " + pct + "% (" + (downloaded / 1048576) + "MB)"
                    : "Downloading... " + (downloaded / 1048576) + "MB";
                mainHandler.post(() -> updateDownloadStatus(msg));
            }
        }
        fos.close();
        in.close();
        conn.disconnect();
        Log.d(TAG, "Download complete: " + aarFile.length() + " bytes");

        // Extract needed files from AAR (which is a ZIP)
        mainHandler.post(() -> updateDownloadStatus("Extracting..."));
        extractAar(aarFile, tmpDir);

        // Rename to final directory (atomic)
        aarFile.delete();
        tmpDir.renameTo(geckoDir);
        Log.d(TAG, "GeckoView ready at " + geckoDir.getAbsolutePath());
    }

    private void extractAar(File aarFile, File outDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(aarFile)));
        ZipEntry entry;
        byte[] buffer = new byte[65536];
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();

            // We need: classes.jar, jni/**/*.so, assets/**
            boolean needed = name.equals("classes.jar")
                || (name.startsWith("jni/") && name.endsWith(".so"))
                || name.startsWith("assets/");

            if (!needed || entry.isDirectory()) {
                continue;
            }

            File outFile = new File(outDir, name);
            outFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(outFile);
            int count;
            while ((count = zis.read(buffer)) != -1) {
                fos.write(buffer, 0, count);
            }
            fos.close();
            Log.d(TAG, "Extracted: " + name + " (" + outFile.length() + " bytes)");
        }
        zis.close();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    // ---------------------------------------------------------------
    // Download progress overlay
    // ---------------------------------------------------------------

    private TextView downloadStatusView;

    private void showDownloadOverlay() {
        if (containerView != null) return;

        containerView = new FrameLayout(context);
        containerView.setBackgroundColor(Color.parseColor("#E6000000"));

        FrameLayout center = new FrameLayout(context);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        centerLp.gravity = Gravity.CENTER;
        center.setLayoutParams(centerLp);

        downloadStatusView = new TextView(context);
        downloadStatusView.setText("Preparing GeckoView...");
        downloadStatusView.setTextColor(Color.WHITE);
        downloadStatusView.setTextSize(16f);
        downloadStatusView.setGravity(Gravity.CENTER);
        downloadStatusView.setPadding(64, 64, 64, 64);
        center.addView(downloadStatusView);

        containerView.addView(center);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(containerView, params);
    }

    private void updateDownloadStatus(String text) {
        if (downloadStatusView != null) {
            downloadStatusView.setText(text);
        }
    }

    // ---------------------------------------------------------------
    // GeckoView runtime initialization (reflection)
    // ---------------------------------------------------------------

    private void ensureGeckoRuntime() throws Exception {
        if (geckoRuntime != null) return;

        File geckoDir = getGeckoDir();
        File classesJar = new File(geckoDir, "classes.jar");
        if (!classesJar.exists()) {
            throw new RuntimeException("GeckoView not downloaded yet");
        }

        // Build native lib path: geckoDir/jni/arm64-v8a/
        String nativeLibPath = new File(geckoDir, "jni/arm64-v8a").getAbsolutePath();
        // Also check armeabi-v7a for 32-bit devices
        File armv7 = new File(geckoDir, "jni/armeabi-v7a");
        if (armv7.exists() && !new File(nativeLibPath).exists()) {
            nativeLibPath = armv7.getAbsolutePath();
        }

        // Create DexClassLoader to load GeckoView classes
        File dexDir = context.getDir("gecko_dex", Context.MODE_PRIVATE);
        geckoClassLoader = new dalvik.system.DexClassLoader(
            classesJar.getAbsolutePath(),
            dexDir.getAbsolutePath(),
            nativeLibPath,
            context.getClassLoader()
        );

        Log.d(TAG, "Initializing GeckoRuntime with ClassLoader...");

        // GeckoRuntimeSettings.Builder
        Class<?> builderClass = geckoClassLoader.loadClass(GECKO_SETTINGS_BUILDER);
        Object builder = builderClass.getConstructor().newInstance();

        try {
            Method jsMethod = builderClass.getMethod("javaScriptEnabled", boolean.class);
            jsMethod.invoke(builder, javascriptEnabled);
        } catch (NoSuchMethodException ignored) { }

        Method buildMethod = builderClass.getMethod("build");
        Object settings = buildMethod.invoke(builder);

        // GeckoRuntime.create(context, settings)
        Class<?> runtimeClass = geckoClassLoader.loadClass(GECKO_RUNTIME_CLASS);
        Class<?> settingsClass = geckoClassLoader.loadClass(GECKO_SETTINGS_CLASS);
        Method createMethod = runtimeClass.getMethod("create", Context.class, settingsClass);
        geckoRuntime = createMethod.invoke(null, context, settings);

        Log.d(TAG, "GeckoRuntime initialized successfully");
    }

    // ---------------------------------------------------------------
    // Overlay window management
    // ---------------------------------------------------------------

    private void createOverlay() throws Exception {
        if (containerView != null) return;

        containerView = new FrameLayout(context);
        containerView.setBackgroundColor(Color.BLACK);

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
        Class<?> viewClass = geckoClassLoader.loadClass(GECKO_VIEW_CLASS);
        Constructor<?> ctor = viewClass.getConstructor(Context.class);
        geckoView = ctor.newInstance(context);

        Class<?> sessionClass = geckoClassLoader.loadClass(GECKO_SESSION_CLASS);
        geckoSession = sessionClass.getConstructor().newInstance();

        Class<?> runtimeClass = geckoClassLoader.loadClass(GECKO_RUNTIME_CLASS);
        Method openMethod = sessionClass.getMethod("open", runtimeClass);
        openMethod.invoke(geckoSession, geckoRuntime);

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

    private void createFallbackWebView() {
        try {
            android.webkit.WebView webView = new android.webkit.WebView(context);
            webView.getSettings().setJavaScriptEnabled(javascriptEnabled);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webView.setWebViewClient(new android.webkit.WebViewClient());
            geckoView = webView;
            geckoSession = null;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            );
            containerView.addView(webView, lp);
            Log.d(TAG, "Fallback WebView created");
        } catch (Exception e) {
            Log.e(TAG, "Fallback WebView also failed", e);
            TextView tv = new TextView(context);
            tv.setText("GeckoView initialization failed.\n" + e.getMessage());
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
                if (geckoView instanceof View) {
                    containerView.removeView((View) geckoView);
                }
                windowManager.removeView(containerView);
            } catch (Exception e) {
                Log.w(TAG, "Error removing overlay", e);
            }
            containerView = null;
            downloadStatusView = null;
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
            try {
                Method loadUri = geckoSession.getClass().getMethod("loadUri", String.class);
                loadUri.invoke(geckoSession, url);
                currentUrl = url;
                Log.d(TAG, "GeckoSession loading: " + url);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load URL in GeckoSession", e);
            }
        } else if (geckoView instanceof android.webkit.WebView) {
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
