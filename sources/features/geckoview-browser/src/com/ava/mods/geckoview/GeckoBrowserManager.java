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
 * GeckoView v68 Browser Mod Manager.
 *
 * Uses GeckoView 68 which supports single-process mode via
 * useContentProcessHint(false), requiring only 4 manifest services
 * instead of 120+. Classes (DEX) bundled in mod libs, native .so
 * and omni.ja downloaded on first use (~44MB).
 */
public class GeckoBrowserManager {

    private static final String TAG = "GeckoBrowserManager";

    private static final String GECKO_RUNTIME_CLASS   = "org.mozilla.geckoview.GeckoRuntime";
    private static final String GECKO_SESSION_CLASS    = "org.mozilla.geckoview.GeckoSession";
    private static final String GECKO_VIEW_CLASS       = "org.mozilla.geckoview.GeckoView";
    private static final String GECKO_SETTINGS_CLASS   = "org.mozilla.geckoview.GeckoRuntimeSettings";
    private static final String GECKO_SETTINGS_BUILDER = "org.mozilla.geckoview.GeckoRuntimeSettings$Builder";

    // GeckoView v68 — single-process capable, small manifest footprint
    private static final String GECKO_VERSION = "68.0.20190711090008";
    private static final String GECKO_AAR_URL =
        "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/"
        + GECKO_VERSION + "/geckoview-arm64-v8a-" + GECKO_VERSION + ".aar";

    private static volatile GeckoBrowserManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Object geckoRuntime;
    private Object geckoSession;
    private Object geckoView;

    private WindowManager windowManager;
    private FrameLayout containerView;
    private boolean isVisible = false;
    private volatile boolean isDownloading = false;

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
            case "default_url": defaultUrl = value.trim(); break;
            case "javascript_enabled": javascriptEnabled = "true".equalsIgnoreCase(value.trim()); break;
            case "touch_enabled": touchEnabled = "true".equalsIgnoreCase(value.trim()); break;
            case "user_agent": userAgentMode = value.trim(); break;
        }
    }

    public void setLastError(String error) { this.lastError = error; }

    // ---------------------------------------------------------------
    // Entity actions
    // ---------------------------------------------------------------

    public void showBrowser() {
        if (isVisible || isDownloading) return;

        File nativeDir = getNativeLibDir();
        if (!nativeDir.exists() || !hasNativeLibs(nativeDir)) {
            isDownloading = true;
            showDownloadOverlay();
            executor.execute(() -> {
                try {
                    downloadNativeLibs();
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
                showToast("GeckoView: " + e.getMessage());
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

    public String getCurrentUrl() { return currentUrl; }

    public void onDestroy() {
        mainHandler.post(() -> {
            removeOverlay();
            closeSession();
            isVisible = false;
            currentUrl = "";
        });
    }

    // ---------------------------------------------------------------
    // Native library download (~44MB compressed AAR)
    // ---------------------------------------------------------------

    private File getGeckoDir() {
        return new File(context.getFilesDir(), "mods/geckoview-browser/native");
    }

    private File getNativeLibDir() {
        return new File(getGeckoDir(), "jni/arm64-v8a");
    }

    private boolean hasNativeLibs(File dir) {
        return new File(dir, "libxul.so").exists();
    }

    private void downloadNativeLibs() throws Exception {
        File geckoDir = getGeckoDir();
        File tmpDir = new File(geckoDir.getParent(), "native_tmp");
        if (tmpDir.exists()) deleteRecursive(tmpDir);
        tmpDir.mkdirs();

        File aarFile = new File(tmpDir, "geckoview.aar");

        mainHandler.post(() -> updateDownloadStatus("Downloading GeckoView v68 (~44MB)..."));
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

        mainHandler.post(() -> updateDownloadStatus("Extracting..."));
        extractAar(aarFile, tmpDir);
        aarFile.delete();

        if (geckoDir.exists()) deleteRecursive(geckoDir);
        tmpDir.renameTo(geckoDir);
        Log.d(TAG, "GeckoView v68 ready at " + geckoDir.getAbsolutePath());
    }

    private void extractAar(File aarFile, File outDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(aarFile)));
        ZipEntry entry;
        byte[] buffer = new byte[65536];
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            boolean needed = (name.startsWith("jni/") && name.endsWith(".so"))
                || name.startsWith("assets/");
            if (!needed || entry.isDirectory()) continue;

            File outFile = new File(outDir, name);
            outFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(outFile);
            int count;
            while ((count = zis.read(buffer)) != -1) {
                fos.write(buffer, 0, count);
            }
            fos.close();
            Log.d(TAG, "Extracted: " + name);
        }
        zis.close();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
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

        downloadStatusView = new TextView(context);
        downloadStatusView.setText("Preparing GeckoView...");
        downloadStatusView.setTextColor(Color.WHITE);
        downloadStatusView.setTextSize(16f);
        downloadStatusView.setGravity(Gravity.CENTER);
        downloadStatusView.setPadding(64, 64, 64, 64);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        containerView.addView(downloadStatusView, lp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(containerView, params);
    }

    private void updateDownloadStatus(String text) {
        if (downloadStatusView != null) downloadStatusView.setText(text);
    }

    // ---------------------------------------------------------------
    // GeckoView v68 runtime — single process mode
    // ---------------------------------------------------------------

    private void ensureGeckoRuntime() throws Exception {
        if (geckoRuntime != null) return;

        File geckoDir = getGeckoDir();
        File nativeDir = getNativeLibDir();
        if (!nativeDir.exists()) {
            throw new RuntimeException("Native libs not downloaded yet");
        }

        // Inject native lib path into all relevant ClassLoaders
        String nativePath = nativeDir.getAbsolutePath();
        ClassLoader cl = GeckoBrowserManager.class.getClassLoader();
        addNativeLibPath(cl, nativePath);
        // Also inject into context classloader (used by GeckoThread)
        ClassLoader contextCl = context.getClassLoader();
        if (contextCl != cl) {
            addNativeLibPath(contextCl, nativePath);
        }
        ClassLoader threadCl = Thread.currentThread().getContextClassLoader();
        if (threadCl != null && threadCl != cl && threadCl != contextCl) {
            addNativeLibPath(threadCl, nativePath);
        }

        File omniJa = new File(geckoDir, "assets/omni.ja");

        // Pre-configure GeckoLoader before GeckoThread can start.
        // Key insight from bytecode analysis:
        //   loadLibsSetupLocked() calls putenv("MOZ_ANDROID_LIBDIR=" + nativeLibraryDir)
        //   where nativeLibraryDir comes from context.getApplicationInfo().nativeLibraryDir
        //   (= APK's lib dir, NOT our mod dir). The native dlopen uses this env var.
        //   We must: load libmozglue first, then putenv our path BEFORE GeckoThread runs.
        try {
            Class<?> loaderClass = cl.loadClass("org.mozilla.gecko.mozglue.GeckoLoader");

            // 1. Set sLibDir field
            java.lang.reflect.Field libDirField = loaderClass.getDeclaredField("sLibDir");
            libDirField.setAccessible(true);
            libDirField.set(null, nativePath);
            Log.d(TAG, "Set GeckoLoader.sLibDir = " + nativePath);

            // 2. Load libmozglue.so first — this registers native methods including putenv()
            try {
                System.load(nativePath + "/libmozglue.so");
                Log.d(TAG, "Pre-loaded libmozglue.so");
            } catch (UnsatisfiedLinkError e) {
                Log.d(TAG, "libmozglue.so: " + e.getMessage());
            }

            // 3. Mark mozglue as loaded to prevent GeckoLoader from trying to reload it
            try {
                java.lang.reflect.Field f = loaderClass.getDeclaredField("sMozGlueLoaded");
                f.setAccessible(true);
                f.set(null, true);
            } catch (Exception ignored) {}

            // 4. Call putenv() to set MOZ_ANDROID_LIBDIR to our native dir
            //    This is what loadSQLiteLibsNative() uses to dlopen libnss3.so etc
            try {
                Method putenvMethod = loaderClass.getDeclaredMethod("putenv", String.class);
                putenvMethod.setAccessible(true);
                putenvMethod.invoke(null, "MOZ_ANDROID_LIBDIR=" + nativePath);
                Log.d(TAG, "putenv MOZ_ANDROID_LIBDIR=" + nativePath);
                putenvMethod.invoke(null, "GRE_HOME=" + new File(geckoDir, "assets").getAbsolutePath());
                Log.d(TAG, "putenv GRE_HOME=" + new File(geckoDir, "assets").getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "putenv failed", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure GeckoLoader", e);
        }

        Log.d(TAG, "Initializing GeckoRuntime v68, native=" + nativePath
            + ", omni=" + omniJa.getAbsolutePath());

        Class<?> runtimeClass = cl.loadClass(GECKO_RUNTIME_CLASS);

        // Try to reuse existing runtime (only one per process)
        try {
            Method getDefault = runtimeClass.getMethod("getDefault", Context.class);
            geckoRuntime = getDefault.invoke(null, context);
            if (geckoRuntime != null) {
                Log.d(TAG, "Reusing existing GeckoRuntime");
                return;
            }
        } catch (Exception ignored) {}

        // Build settings with single-process mode
        Class<?> builderClass = cl.loadClass(GECKO_SETTINGS_BUILDER);
        Object builder = builderClass.getConstructor().newInstance();

        // Disable multi-process — this is the key v68 feature
        try {
            Method useContent = builderClass.getMethod("useContentProcessHint", boolean.class);
            useContent.invoke(builder, false);
            Log.d(TAG, "Set useContentProcessHint(false) — single process mode");
        } catch (Exception e) {
            Log.w(TAG, "useContentProcessHint not available", e);
        }

        // JavaScript
        try {
            builderClass.getMethod("javaScriptEnabled", boolean.class).invoke(builder, javascriptEnabled);
        } catch (NoSuchMethodException ignored) {}

        // Pass -greomni via arguments() — do NOT use configFilePath (requires SnakeYAML)
        try {
            builderClass.getMethod("arguments", String[].class)
                .invoke(builder, (Object) new String[]{"-greomni", omniJa.getAbsolutePath()});
            Log.d(TAG, "Set arguments: -greomni " + omniJa.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "arguments() failed", e);
        }

        // Disable crash handler (not registered in manifest)
        try {
            builderClass.getMethod("crashHandler", Class.class).invoke(builder, (Object) null);
            Log.d(TAG, "Disabled crash handler");
        } catch (Exception ignored) {}

        // Build and create
        Method buildMethod = builderClass.getMethod("build");
        Object settings = buildMethod.invoke(builder);

        Class<?> settingsClass = cl.loadClass(GECKO_SETTINGS_CLASS);
        Method createMethod = runtimeClass.getMethod("create", Context.class, settingsClass);
        geckoRuntime = createMethod.invoke(null, context, settings);

        Log.d(TAG, "GeckoRuntime v68 created (single-process)");
    }

    private void addNativeLibPath(ClassLoader classLoader, String nativePath) {
        try {
            java.lang.reflect.Field pathListField = findField(classLoader.getClass(), "pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);

            java.lang.reflect.Field nativeLibField = pathList.getClass().getDeclaredField("nativeLibraryDirectories");
            nativeLibField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<File> nativeLibDirs = (java.util.List<File>) nativeLibField.get(pathList);

            File dir = new File(nativePath);
            if (!nativeLibDirs.contains(dir)) {
                nativeLibDirs.add(0, dir);
                Log.d(TAG, "Injected native lib path: " + nativePath);
            }

            // Update nativeLibraryPathElements
            try {
                java.lang.reflect.Field elementsField = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
                elementsField.setAccessible(true);
                Object[] oldElements = (Object[]) elementsField.get(pathList);

                Method makeMethod = pathList.getClass().getDeclaredMethod("makePathElements", java.util.List.class);
                makeMethod.setAccessible(true);
                Object[] newElements = (Object[]) makeMethod.invoke(null, nativeLibDirs);

                Object[] merged = java.util.Arrays.copyOf(newElements, newElements.length + oldElements.length);
                System.arraycopy(oldElements, 0, merged, newElements.length, oldElements.length);
                elementsField.set(pathList, merged);
            } catch (Exception e) {
                Log.w(TAG, "Could not update nativeLibraryPathElements", e);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject native path, trying System.load", e);
            try {
                System.load(nativePath + "/libmozglue.so");
                System.load(nativePath + "/libxul.so");
            } catch (Exception e2) {
                Log.e(TAG, "Manual lib loading also failed", e2);
            }
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException e) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    // ---------------------------------------------------------------
    // Overlay
    // ---------------------------------------------------------------

    private void createOverlay() throws Exception {
        if (containerView != null) return;
        containerView = new FrameLayout(context);
        containerView.setBackgroundColor(Color.BLACK);

        try {
            createGeckoView();
        } catch (Exception e) {
            Log.w(TAG, "GeckoView creation failed, fallback to WebView", e);
            createFallbackWebView();
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        containerView.setOnTouchListener((v, event) -> {
            if (!touchEnabled) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) makeFocusable(params);
            return false;
        });

        windowManager.addView(containerView, params);
    }

    private void createGeckoView() throws Exception {
        ClassLoader cl = GeckoBrowserManager.class.getClassLoader();

        Class<?> viewClass = cl.loadClass(GECKO_VIEW_CLASS);
        Constructor<?> ctor = viewClass.getConstructor(Context.class);
        geckoView = ctor.newInstance(context);

        Class<?> sessionClass = cl.loadClass(GECKO_SESSION_CLASS);
        geckoSession = sessionClass.getConstructor().newInstance();

        Class<?> runtimeClass = cl.loadClass(GECKO_RUNTIME_CLASS);
        Method openMethod = sessionClass.getMethod("open", runtimeClass);
        openMethod.invoke(geckoSession, geckoRuntime);

        Method setSessionMethod = viewClass.getMethod("setSession", sessionClass);
        setSessionMethod.invoke(geckoView, geckoSession);

        containerView.addView((View) geckoView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Log.d(TAG, "GeckoView created");
    }

    private void createFallbackWebView() {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(context);
            wv.getSettings().setJavaScriptEnabled(javascriptEnabled);
            wv.getSettings().setDomStorageEnabled(true);
            wv.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            wv.setWebViewClient(new android.webkit.WebViewClient());
            geckoView = wv;
            geckoSession = null;
            containerView.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } catch (Exception e) {
            TextView tv = new TextView(context);
            tv.setText("Browser init failed:\n" + e.getMessage());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(48, 48, 48, 48);
            containerView.addView(tv);
        }
    }

    private void removeOverlay() {
        if (containerView != null) {
            try {
                if (geckoView instanceof View) containerView.removeView((View) geckoView);
                windowManager.removeView(containerView);
            } catch (Exception e) { Log.w(TAG, "Error removing overlay", e); }
            containerView = null;
            downloadStatusView = null;
        }
        closeSession();
    }

    private void closeSession() {
        if (geckoSession != null) {
            try { geckoSession.getClass().getMethod("close").invoke(geckoSession); }
            catch (Exception ignored) {}
            geckoSession = null;
        }
        geckoView = null;
    }

    private void makeFocusable(WindowManager.LayoutParams params) {
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { windowManager.updateViewLayout(containerView, params); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------
    // URL
    // ---------------------------------------------------------------

    private void doLoadUrl(String url) {
        if (geckoSession != null) {
            try {
                geckoSession.getClass().getMethod("loadUri", String.class).invoke(geckoSession, url);
                currentUrl = url;
                Log.d(TAG, "Loading: " + url);
            } catch (Exception e) { Log.e(TAG, "loadUri failed", e); }
        } else if (geckoView instanceof android.webkit.WebView) {
            ((android.webkit.WebView) geckoView).loadUrl(url);
            currentUrl = url;
        }
    }

    private String normalizeUrl(String url) {
        if (url.isEmpty()) return url;
        return url.contains("://") ? url : "https://" + url;
    }

    private void showToast(String msg) {
        try { Toast.makeText(context, msg, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
    }
}
