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
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
    private final Object runtimeInitLock = new Object();

    private Object geckoRuntime;
    private Object geckoSession;
    private Object geckoView;
    private Object geckoDisplay;  // GeckoDisplay from acquireDisplay()
    private SurfaceView surfaceView;

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
                Log.d(TAG, "Runtime ready, creating overlay...");
                createOverlay();
                Log.d(TAG, "Overlay created");
                isVisible = true;
                Log.d(TAG, "Browser shown");
                // Delay URL loading to let Gecko engine finish initializing
                String url = currentUrl.isEmpty() ? defaultUrl : currentUrl;
                if (!url.isEmpty()) {
                    mainHandler.postDelayed(() -> doLoadUrl(url), 2000);
                }
            } catch (Throwable e) {
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
        if (geckoRuntime != null) {
            return;
        }
        synchronized (runtimeInitLock) {
            if (geckoRuntime != null) {
                return;
            }
            initGeckoRuntime();
        }
    }

    private void initGeckoRuntime() throws Exception {
        final File geckoDir = getGeckoDir();
        final File nativeDir = getNativeLibDir();
        final File assetsDir = new File(geckoDir, "assets");
        final File omniJa = new File(assetsDir, "omni.ja");
        final String nativePath = nativeDir.getAbsolutePath();

        requirePath(nativeDir, "native library directory");
        requirePath(new File(nativeDir, "libmozglue.so"), "libmozglue.so");
        requirePath(new File(nativeDir, "libxul.so"), "libxul.so");
        requirePath(assetsDir, "assets directory");
        requirePath(omniJa, "omni.ja");

        final ClassLoader cl = GeckoBrowserManager.class.getClassLoader();
        injectNativeLibPathStrict(cl, nativePath);

        final ClassLoader contextCl = context.getClassLoader();
        if (contextCl != cl) {
            injectNativeLibPathStrict(contextCl, nativePath);
        }

        final ClassLoader threadCl = Thread.currentThread().getContextClassLoader();
        if (threadCl != null && threadCl != cl && threadCl != contextCl) {
            injectNativeLibPathStrict(threadCl, nativePath);
        }

        final String origNativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        try {
            context.getApplicationInfo().nativeLibraryDir = nativePath;
            Log.d(TAG, "Patched applicationInfo.nativeLibraryDir: " + origNativeLibDir + " -> " + nativePath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to patch nativeLibraryDir", e);
        }

        Log.d(TAG, "Initializing GeckoRuntime with external assets: " + omniJa.getAbsolutePath());

        final Class<?> runtimeClass = cl.loadClass(GECKO_RUNTIME_CLASS);

        try {
            final java.lang.reflect.Field sDefaultField = runtimeClass.getDeclaredField("sDefaultRuntime");
            sDefaultField.setAccessible(true);
            final Object existing = sDefaultField.get(null);
            if (existing != null) {
                geckoRuntime = existing;
                Log.d(TAG, "Reusing existing GeckoRuntime (sDefaultRuntime)");
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not inspect sDefaultRuntime: " + e.getMessage());
        }

        try {
            final Class<?> loaderClass = cl.loadClass("org.mozilla.gecko.mozglue.GeckoLoader");
            final java.lang.reflect.Field greDirField = loaderClass.getDeclaredField("sGREDir");
            greDirField.setAccessible(true);
            greDirField.set(null, assetsDir);
            Log.d(TAG, "Pinned GeckoLoader.sGREDir to " + assetsDir.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Failed to pin GeckoLoader.sGREDir", e);
        }

        final Class<?> builderClass = cl.loadClass(GECKO_SETTINGS_BUILDER);
        final Object builder = builderClass.getConstructor().newInstance();

        try {
            builderClass.getMethod("useContentProcessHint", boolean.class).invoke(builder, false);
            Log.d(TAG, "Set useContentProcessHint(false)");
        } catch (Exception e) {
            Log.w(TAG, "useContentProcessHint not available", e);
        }

        try {
            builderClass.getMethod("javaScriptEnabled", boolean.class).invoke(builder, javascriptEnabled);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            builderClass.getMethod("arguments", String[].class)
                    .invoke(builder, (Object) new String[]{"-greomni", omniJa.getAbsolutePath()});
            Log.d(TAG, "Pinned runtime args to external omni.ja");
        } catch (Exception e) {
            Log.w(TAG, "arguments() failed", e);
        }

        try {
            builderClass.getMethod("crashHandler", Class.class).invoke(builder, (Object) null);
        } catch (Exception ignored) {
        }

        final Method buildMethod = builderClass.getMethod("build");
        final Object settings = buildMethod.invoke(builder);
        final Class<?> settingsClass = cl.loadClass(GECKO_SETTINGS_CLASS);
        final Method createMethod = runtimeClass.getMethod("create", Context.class, settingsClass);
        geckoRuntime = createMethod.invoke(null, context, settings);

        if (geckoRuntime == null) {
            throw new IllegalStateException("GeckoRuntime.create returned null");
        }

        Log.d(TAG, "GeckoRuntime created with strict bootstrap path");

        // Restore nativeLibraryDir after GeckoThread finishes loading native libs.
        // GeckoThread loads libs asynchronously (loadSQLiteLibs, loadNSSLibs, loadGeckoLibs)
        // and reads applicationInfo.nativeLibraryDir during that process.
        // Delay restore by 5 seconds to ensure all native loading is complete.
        final String restorePath = origNativeLibDir;
        mainHandler.postDelayed(() -> {
            context.getApplicationInfo().nativeLibraryDir = restorePath;
            Log.d(TAG, "Restored applicationInfo.nativeLibraryDir: " + restorePath);
        }, 5000);
    }

    private void injectNativeLibPathStrict(ClassLoader classLoader, String nativePath) throws Exception {
        final java.lang.reflect.Field pathListField = findField(classLoader.getClass(), "pathList");
        pathListField.setAccessible(true);
        final Object pathList = pathListField.get(classLoader);

        final java.lang.reflect.Field nativeLibField =
                pathList.getClass().getDeclaredField("nativeLibraryDirectories");
        nativeLibField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final java.util.List<File> nativeLibDirs =
                (java.util.List<File>) nativeLibField.get(pathList);

        final File dir = new File(nativePath);
        if (!nativeLibDirs.contains(dir)) {
            nativeLibDirs.add(0, dir);
        }

        try {
            final java.lang.reflect.Field elementsField =
                    pathList.getClass().getDeclaredField("nativeLibraryPathElements");
            elementsField.setAccessible(true);
            final Object[] oldElements = (Object[]) elementsField.get(pathList);
            final Object[] newElements = makeNativePathElements(pathList, nativeLibDirs);

            if (oldElements == null || oldElements.length == 0) {
                elementsField.set(pathList, newElements);
            } else {
                final Object[] merged =
                        java.util.Arrays.copyOf(newElements, newElements.length + oldElements.length);
                System.arraycopy(oldElements, 0, merged, newElements.length, oldElements.length);
                elementsField.set(pathList, merged);
            }
        } catch (NoSuchFieldException ignored) {
            Log.d(TAG, "nativeLibraryPathElements not present on this runtime");
        }

        Log.d(TAG, "Injected native lib path: " + nativePath);
    }

    private Object[] makeNativePathElements(Object pathList, java.util.List<File> nativeLibDirs)
            throws Exception {
        try {
            final Method makeMethod =
                    pathList.getClass().getDeclaredMethod("makePathElements", java.util.List.class);
            makeMethod.setAccessible(true);
            return (Object[]) makeMethod.invoke(null, nativeLibDirs);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            final Method makeMethod = pathList.getClass().getDeclaredMethod(
                    "makePathElements", java.util.List.class, File.class, java.util.List.class);
            makeMethod.setAccessible(true);
            final java.util.ArrayList<java.io.IOException> suppressed = new java.util.ArrayList<>();
            final Object[] elements =
                    (Object[]) makeMethod.invoke(null, nativeLibDirs, null, suppressed);
            if (!suppressed.isEmpty()) {
                throw suppressed.get(0);
            }
            return elements;
        } catch (NoSuchMethodException ignored) {
        }

        throw new NoSuchMethodException("Unsupported DexPathList.makePathElements signature");
    }

    private void requirePath(File file, String label) {
        if (!file.exists()) {
            throw new IllegalStateException("Missing Gecko runtime " + label + ": " + file.getAbsolutePath());
        }
    }

    private void ensureGeckoRuntimeLegacy() throws Exception {
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

        // CRITICAL FIX: GeckoLoader.loadLibsSetupLocked() internally does:
        //   putenv("MOZ_ANDROID_LIBDIR=" + context.getApplicationInfo().nativeLibraryDir)
        // This overwrites any putenv we set beforehand with the APK's lib dir.
        // Solution: Temporarily patch applicationInfo.nativeLibraryDir during init,
        // then restore it so other app components (WebView, etc.) still work.
        String origNativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        try {
            context.getApplicationInfo().nativeLibraryDir = nativePath;
            Log.d(TAG, "Patched applicationInfo.nativeLibraryDir: " + origNativeLibDir + " -> " + nativePath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to patch nativeLibraryDir", e);
        }

        // Step 1: Load libmozglue.so — registers native methods including putenv()
        try {
            System.load(nativePath + "/libmozglue.so");
            Log.d(TAG, "Pre-loaded libmozglue.so");
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "libmozglue.so: " + e.getMessage());
        }

        try {
            Class<?> loaderClass = cl.loadClass("org.mozilla.gecko.mozglue.GeckoLoader");

            // Step 2: Mark mozglue as loaded so GeckoLoader won't re-load it
            try {
                java.lang.reflect.Field f = loaderClass.getDeclaredField("sMozGlueLoaded");
                f.setAccessible(true);
                f.set(null, true);
                Log.d(TAG, "Set sMozGlueLoaded = true");
            } catch (Exception e) {
                Log.w(TAG, "Could not set sMozGlueLoaded", e);
            }

            // Step 3: Set sGREDir to our assets dir
            try {
                java.lang.reflect.Field greField = loaderClass.getDeclaredField("sGREDir");
                greField.setAccessible(true);
                greField.set(null, new File(geckoDir, "assets"));
                Log.d(TAG, "Set GeckoLoader.sGREDir = " + new File(geckoDir, "assets").getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Could not set sGREDir", e);
            }

            // Step 4: putenv for both MOZ_ANDROID_LIBDIR and GRE_HOME
            // Even though we patched applicationInfo, also set env directly as belt-and-suspenders
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

        // Check if a runtime already exists via sDefaultRuntime field (NOT getDefault()
        // which triggers init() with empty settings and prematurely launches GeckoThread)
        try {
            java.lang.reflect.Field sDefaultField = runtimeClass.getDeclaredField("sDefaultRuntime");
            sDefaultField.setAccessible(true);
            Object existing = sDefaultField.get(null);
            if (existing != null) {
                geckoRuntime = existing;
                Log.d(TAG, "Reusing existing GeckoRuntime (sDefaultRuntime)");
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not check sDefaultRuntime: " + e.getMessage());
        }

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

        // Restore nativeLibraryDir after GeckoThread finishes loading native libs.
        // GeckoThread loads libs asynchronously (loadSQLiteLibs, loadNSSLibs, loadGeckoLibs)
        // and reads applicationInfo.nativeLibraryDir during that process.
        // Delay restore by 5 seconds to ensure all native loading is complete.
        final String restorePath = origNativeLibDir;
        mainHandler.postDelayed(() -> {
            context.getApplicationInfo().nativeLibraryDir = restorePath;
            Log.d(TAG, "Restored applicationInfo.nativeLibraryDir: " + restorePath);
        }, 5000);
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
        } catch (Throwable e) {
            Log.w(TAG, "GeckoView creation failed, fallback to WebView", e);
            createFallbackWebView();
        }

        // Close button (top-right corner)
        TextView closeBtn = new TextView(context);
        closeBtn.setText("  X  ");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(18f);
        closeBtn.setBackgroundColor(Color.parseColor("#88FF0000"));
        closeBtn.setPadding(24, 12, 24, 12);
        closeBtn.setOnClickListener(v -> hideBrowser());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = 48;
        closeLp.rightMargin = 16;
        containerView.addView(closeBtn, closeLp);

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

        // Skip the GeckoView class (crashes with native SIGSEGV in overlay context).
        // Instead: plain SurfaceView + GeckoSession + GeckoDisplay API.
        Log.d(TAG, "Creating SurfaceView...");
        surfaceView = new SurfaceView(context);
        surfaceView.setZOrderOnTop(false);
        Log.d(TAG, "SurfaceView created");

        Log.d(TAG, "Creating GeckoSession...");
        Class<?> sessionClass = cl.loadClass(GECKO_SESSION_CLASS);
        geckoSession = sessionClass.getConstructor().newInstance();
        Log.d(TAG, "GeckoSession created");

        Log.d(TAG, "Opening session...");
        Class<?> runtimeClass = cl.loadClass(GECKO_RUNTIME_CLASS);
        Method openMethod = sessionClass.getMethod("open", runtimeClass);
        openMethod.invoke(geckoSession, geckoRuntime);
        Log.d(TAG, "Session opened");

        // Acquire GeckoDisplay from session
        Log.d(TAG, "Acquiring GeckoDisplay...");
        Method acquireDisplay = sessionClass.getMethod("acquireDisplay");
        geckoDisplay = acquireDisplay.invoke(geckoSession);
        Log.d(TAG, "GeckoDisplay acquired");

        // Connect SurfaceView's Surface to GeckoDisplay via SurfaceHolder callback
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "Surface created");
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "Surface changed: " + width + "x" + height);
                if (geckoDisplay != null) {
                    try {
                        Method surfaceChangedMethod = geckoDisplay.getClass()
                            .getMethod("surfaceChanged", Surface.class, int.class, int.class);
                        surfaceChangedMethod.invoke(geckoDisplay, holder.getSurface(), width, height);
                        Log.d(TAG, "GeckoDisplay.surfaceChanged called");
                    } catch (Exception e) {
                        Log.e(TAG, "surfaceChanged failed", e);
                    }
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed");
                if (geckoDisplay != null) {
                    try {
                        geckoDisplay.getClass().getMethod("surfaceDestroyed").invoke(geckoDisplay);
                    } catch (Exception e) {
                        Log.w(TAG, "surfaceDestroyed failed", e);
                    }
                }
            }
        });

        geckoView = surfaceView;
        containerView.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Log.d(TAG, "SurfaceView added to container");
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
                containerView.removeAllViews();
                windowManager.removeView(containerView);
            } catch (Exception e) { Log.w(TAG, "Error removing overlay", e); }
            containerView = null;
            downloadStatusView = null;
        }
        closeSession();
    }

    private void closeSession() {
        if (geckoDisplay != null && geckoSession != null) {
            try {
                geckoSession.getClass().getMethod("releaseDisplay",
                    geckoDisplay.getClass()).invoke(geckoSession, geckoDisplay);
            } catch (Exception ignored) {}
            geckoDisplay = null;
        }
        if (geckoSession != null) {
            try { geckoSession.getClass().getMethod("close").invoke(geckoSession); }
            catch (Exception ignored) {}
            geckoSession = null;
        }
        geckoView = null;
        surfaceView = null;
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
