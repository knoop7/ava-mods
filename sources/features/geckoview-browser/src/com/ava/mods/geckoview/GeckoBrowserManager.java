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
 * GeckoView Browser Mod Manager.
 *
 * GeckoView Java classes (geckoview-classes.jar, pre-converted to DEX) are
 * bundled in the mod's libs/ and loaded by Ava's DexClassLoader automatically.
 *
 * Native .so libraries (~150MB) are downloaded from Mozilla Maven on first use
 * and cached locally.
 */
public class GeckoBrowserManager {

    private static final String TAG = "GeckoBrowserManager";

    private static final String GECKO_RUNTIME_CLASS   = "org.mozilla.geckoview.GeckoRuntime";
    private static final String GECKO_SESSION_CLASS    = "org.mozilla.geckoview.GeckoSession";
    private static final String GECKO_VIEW_CLASS       = "org.mozilla.geckoview.GeckoView";
    private static final String GECKO_SETTINGS_CLASS   = "org.mozilla.geckoview.GeckoRuntimeSettings";
    private static final String GECKO_SETTINGS_BUILDER = "org.mozilla.geckoview.GeckoRuntimeSettings$Builder";

    private static final String GECKO_VERSION = "149.0.20260403140140";
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

        // Check if native libs are downloaded
        File nativeDir = getNativeLibDir();
        if (!nativeDir.exists() || !hasNativeLibs(nativeDir)) {
            // Need to download native libs first
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
    // Native library download (only .so + assets, ~80MB compressed)
    // ---------------------------------------------------------------

    private File getGeckoDir() {
        // Store inside the mod's own directory: files/mods/geckoview-browser/native/
        return new File(context.getFilesDir(), "mods/geckoview-browser/native");
    }

    private File getNativeLibDir() {
        return new File(getGeckoDir(), "jni/arm64-v8a");
    }

    private boolean hasNativeLibs(File dir) {
        File libxul = new File(dir, "libxul.so");
        return libxul.exists();
    }

    private void downloadNativeLibs() throws Exception {
        File geckoDir = getGeckoDir();
        File tmpDir = new File(geckoDir.getParent(), "geckoview_tmp");
        if (tmpDir.exists()) deleteRecursive(tmpDir);
        tmpDir.mkdirs();

        File aarFile = new File(tmpDir, "geckoview.aar");

        // Download
        mainHandler.post(() -> updateDownloadStatus("Downloading GeckoView engine (~84MB)..."));
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

        // Extract only native libs and assets from AAR
        mainHandler.post(() -> updateDownloadStatus("Extracting native libraries..."));
        extractAar(aarFile, tmpDir);
        aarFile.delete();

        // Move to final location
        if (geckoDir.exists()) deleteRecursive(geckoDir);
        tmpDir.renameTo(geckoDir);
        Log.d(TAG, "Native libs ready at " + geckoDir.getAbsolutePath());
    }

    private void extractAar(File aarFile, File outDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(aarFile)));
        ZipEntry entry;
        byte[] buffer = new byte[65536];
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            // Only extract native libs and assets (classes already bundled in mod)
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
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER;
        containerView.addView(downloadStatusView, lp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(containerView, params);
    }

    private void updateDownloadStatus(String text) {
        if (downloadStatusView != null) downloadStatusView.setText(text);
    }

    // ---------------------------------------------------------------
    // GeckoView runtime (classes loaded by mod's ClassLoader, native from disk)
    // ---------------------------------------------------------------

    private void ensureGeckoRuntime() throws Exception {
        if (geckoRuntime != null) return;

        // GeckoView classes are already on this class's ClassLoader
        // (geckoview-classes.jar is in mod libs/, loaded by Ava's DexClassLoader)
        ClassLoader cl = GeckoBrowserManager.class.getClassLoader();

        // But GeckoView needs to find its native .so files.
        // We need to add the native lib path to the system.
        File nativeDir = getNativeLibDir();
        if (!nativeDir.exists()) {
            throw new RuntimeException("Native libs not downloaded. Toggle the switch to trigger download.");
        }

        // Set system property so GeckoView can find its libs
        String nativePath = nativeDir.getAbsolutePath();
        try {
            System.setProperty("gecko.libdir", nativePath);
        } catch (Exception ignored) {}

        // Also try to inject native path into the existing ClassLoader
        addNativeLibPath(cl, nativePath);

        // Set assets path for omni.ja
        File assetsDir = new File(getGeckoDir(), "assets");
        if (assetsDir.exists()) {
            try {
                System.setProperty("gecko.assetsdir", assetsDir.getAbsolutePath());
            } catch (Exception ignored) {}
        }

        Log.d(TAG, "Initializing GeckoRuntime, nativeDir=" + nativePath);

        Class<?> builderClass = cl.loadClass(GECKO_SETTINGS_BUILDER);
        Object builder = builderClass.getConstructor().newInstance();

        try {
            Method jsMethod = builderClass.getMethod("javaScriptEnabled", boolean.class);
            jsMethod.invoke(builder, javascriptEnabled);
        } catch (NoSuchMethodException ignored) {}

        Method buildMethod = builderClass.getMethod("build");
        Object settings = buildMethod.invoke(builder);

        Class<?> runtimeClass = cl.loadClass(GECKO_RUNTIME_CLASS);
        Class<?> settingsClass = cl.loadClass(GECKO_SETTINGS_CLASS);
        Method createMethod = runtimeClass.getMethod("create", Context.class, settingsClass);
        geckoRuntime = createMethod.invoke(null, context, settings);

        Log.d(TAG, "GeckoRuntime initialized");
    }

    /**
     * Inject a native library path into an existing ClassLoader via reflection.
     * This allows System.loadLibrary() to find .so files in our custom directory.
     */
    private void addNativeLibPath(ClassLoader classLoader, String nativePath) {
        try {
            // BaseDexClassLoader.pathList (DexPathList)
            java.lang.reflect.Field pathListField = findField(classLoader.getClass(), "pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);

            // DexPathList.nativeLibraryDirectories (List<File>)
            java.lang.reflect.Field nativeLibField = pathList.getClass().getDeclaredField("nativeLibraryDirectories");
            nativeLibField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<File> nativeLibDirs = (java.util.List<File>) nativeLibField.get(pathList);

            File dir = new File(nativePath);
            if (!nativeLibDirs.contains(dir)) {
                nativeLibDirs.add(0, dir);
                Log.d(TAG, "Injected native lib path: " + nativePath);
            }

            // Also update nativeLibraryPathElements if it exists
            try {
                java.lang.reflect.Field elementsField = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
                elementsField.setAccessible(true);
                Object[] oldElements = (Object[]) elementsField.get(pathList);

                // Use makePathElements via reflection
                Method makeMethod = pathList.getClass().getDeclaredMethod(
                    "makePathElements", java.util.List.class);
                makeMethod.setAccessible(true);
                Object[] newElements = (Object[]) makeMethod.invoke(null, nativeLibDirs);

                // Merge: new elements first, then old
                Object[] merged = java.util.Arrays.copyOf(newElements, newElements.length + oldElements.length);
                System.arraycopy(oldElements, 0, merged, newElements.length, oldElements.length);
                elementsField.set(pathList, merged);
            } catch (Exception e) {
                Log.w(TAG, "Could not update nativeLibraryPathElements, may still work", e);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject native path, trying System.load directly", e);
            // Fallback: pre-load critical libs manually
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
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
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
            PixelFormat.TRANSLUCENT
        );
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
            try {
                geckoSession.getClass().getMethod("close").invoke(geckoSession);
            } catch (Exception ignored) {}
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
