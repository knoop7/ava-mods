package com.ava.mods.camerastream;

import android.app.ActivityManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ava mod manager: LAN camera forwarder with user-selectable MJPEG or RTSP,
 * plus FPS / resolution / quality controls.
 *
 * <p>Lifecycle follows the voice satellite master service only — no auto-start.
 * Starting the stream requires the service to be running; when the service
 * (or this mod manager) is destroyed, the pipeline always stops.
 */
public final class CameraStreamManager {

    private static final String TAG = "CameraStreamManager";
    private static final String VOICE_SERVICE = "com.example.ava.services.VoiceSatelliteService";

    private static volatile CameraStreamManager instance;

    private final Context context;
    private final StreamConfig config = new StreamConfig();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean desiredOn = new AtomicBoolean(false);
    private final AtomicBoolean pipelineOn = new AtomicBoolean(false);
    private final Map<String, Object> stateListeners = new ConcurrentHashMap<>();

    private CameraJpegSource camera;
    private MjpegHttpServer mjpegServer;
    private H264Encoder encoder;
    private RtspServer rtspServer;
    private String lastError = "";

    private CameraStreamManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static CameraStreamManager getInstance(Context context) {
        if (instance == null) {
            synchronized (CameraStreamManager.class) {
                if (instance == null) {
                    instance = new CameraStreamManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) return;
        // Ignore legacy auto_start — never start just because the mod is enabled.
        if ("auto_start".equals(key)) {
            Log.i(TAG, "Ignoring auto_start — use stream_enabled or HA Camera Stream switch");
            return;
        }
        if ("stream_enabled".equals(key)) {
            setStreamEnabled("true".equalsIgnoreCase(value));
            return;
        }
        boolean needsRestart = pipelineOn.get();
        switch (key) {
            case "stream_format":
                config.format = value.trim().toLowerCase();
                break;
            case "h264_encoder":
                config.encoder = value.trim().toLowerCase();
                break;
            case "port":
                config.port = clamp(parseInt(value, 8554), 1024, 65535);
                break;
            case "path":
                config.path = value.trim();
                break;
            case "token":
                config.token = value;
                break;
            case "use_front_camera":
                config.useFrontCamera = "true".equalsIgnoreCase(value);
                break;
            case "fps":
                config.fps = clamp(parseInt(value, 5), 1, 15);
                break;
            case "resolution":
                config.resolution = clamp(parseInt(value, 480), 240, 720);
                break;
            case "jpeg_quality":
                config.jpegQuality = clamp(parseInt(value, 75), 40, 95);
                break;
            case "bitrate_kbps":
                config.bitrateKbps = clamp(parseInt(value, 800), 200, 4000);
                break;
            default:
                return;
        }
        notifyUrlChanged();
        if (needsRestart && desiredOn.get() && isMasterServiceRunning()) {
            restartStream();
        }
    }

    public void setStreamEnabled(boolean enabled) {
        if (enabled) {
            desiredOn.set(true);
            notifyState("stream", true);
            if (!isMasterServiceRunning()) {
                // Config/HA may apply before VoiceSatelliteService.instance is visible to
                // the mod ClassLoader — keep desiredOn and retry briefly (not auto-start).
                setLastError("Waiting for voice satellite service…");
                Log.w(TAG, "startStream deferred — master service not visible yet");
                scheduleStartRetry(0);
                return;
            }
            startPipeline();
        } else {
            desiredOn.set(false);
            notifyState("stream", false);
            stopPipeline();
        }
    }

    private void scheduleStartRetry(final int attempt) {
        if (attempt >= 10) {
            desiredOn.set(false);
            notifyState("stream", false);
            setLastError("Voice satellite service is not running");
            Log.w(TAG, "startStream gave up waiting for master service");
            return;
        }
        executor.execute(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!desiredOn.get()) return;
            if (pipelineOn.get()) return;
            if (isMasterServiceRunning()) {
                Log.i(TAG, "Master service ready — starting deferred stream");
                startPipelineSync();
            } else {
                scheduleStartRetry(attempt + 1);
            }
        });
    }

    /** HA switch on — only while master service is up. */
    public void startStream() {
        setStreamEnabled(true);
    }

    /** HA switch off */
    public void stopStream() {
        setStreamEnabled(false);
    }

    public boolean isStreamEnabled() {
        return desiredOn.get() && isMasterServiceRunning();
    }

    public boolean isServerRunning() {
        return pipelineOn.get() && isMasterServiceRunning();
    }

    public void restartStream() {
        executor.execute(() -> {
            stopPipelineSync();
            if (desiredOn.get() && isMasterServiceRunning()) {
                startPipelineSync();
            } else if (desiredOn.get() && !isMasterServiceRunning()) {
                desiredOn.set(false);
                notifyState("stream", false);
                setLastError("Voice satellite service is not running");
            }
        });
    }

    public String getStreamUrl() {
        String ip = localIp();
        String path = config.streamPath();
        if (config.isRtsp()) {
            return "rtsp://" + ip + ":" + config.port + "/" + path;
        }
        String url = "http://" + ip + ":" + config.port + "/" + path;
        if (config.token != null && !config.token.trim().isEmpty()) {
            url = url + "?token=" + config.token.trim();
        }
        return url;
    }

    public String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public void setLastError(String error) {
        lastError = error == null ? "" : error;
        notifyState("last_error", lastError);
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || callback == null) return false;
        stateListeners.put(entityId, callback);
        if ("stream".equals(entityId)) {
            invokeState(callback, isStreamEnabled());
        } else if ("server_running".equals(entityId)) {
            invokeState(callback, isServerRunning());
        } else if ("stream_url".equals(entityId)) {
            invokeState(callback, getStreamUrl());
        } else if ("last_error".equals(entityId)) {
            invokeState(callback, getLastError());
        }
        return true;
    }

    /**
     * Called when the mod / voice satellite tears down — always stop, never leave
     * a background camera server running without the master service.
     */
    public void onDestroy() {
        desiredOn.set(false);
        stopPipelineSync();
        pipelineOn.set(false);
        notifyState("stream", false);
        notifyState("server_running", false);
        stateListeners.clear();
        Log.i(TAG, "onDestroy — pipeline stopped with master service");
    }

    private void startPipeline() {
        executor.execute(this::startPipelineSync);
    }

    private void stopPipeline() {
        executor.execute(this::stopPipelineSync);
    }

    private void startPipelineSync() {
        synchronized (this) {
            if (pipelineOn.get()) return;
            if (!isMasterServiceRunning()) {
                desiredOn.set(false);
                notifyState("stream", false);
                setLastError("Voice satellite service is not running");
                return;
            }
            try {
                disableCoreCameraBestEffort();
                camera = new CameraJpegSource(context, config);
                if (config.isRtsp()) {
                    encoder = new H264Encoder(config);
                    if (!encoder.start()) {
                        setLastError("H.264 encoder failed ("
                                + config.normalizedEncoder()
                                + "). Try auto or the other encoder mode.");
                        releaseAllLocked();
                        return;
                    }
                    camera.setEncoderSurface(encoder.getInputSurface());
                    rtspServer = new RtspServer(config, encoder);
                    camera.start();
                    rtspServer.start();
                } else {
                    mjpegServer = new MjpegHttpServer(config, camera);
                    camera.start();
                    mjpegServer.start();
                }
                // Service may have died while we were opening the camera.
                if (!isMasterServiceRunning()) {
                    setLastError("Voice satellite service stopped during start");
                    releaseAllLocked();
                    desiredOn.set(false);
                    notifyState("stream", false);
                    return;
                }
                pipelineOn.set(true);
                setLastError("");
                notifyState("server_running", true);
                notifyUrlChanged();
                String encInfo = config.isRtsp()
                        ? " encoder=" + config.normalizedEncoder()
                        + (encoder != null ? "/" + encoder.getActiveCodecName() : "")
                        : "";
                Log.i(TAG, "Pipeline started format=" + config.normalizedFormat()
                        + encInfo + " url=" + getStreamUrl());
            } catch (Exception e) {
                Log.e(TAG, "startPipeline failed", e);
                setLastError(String.valueOf(e.getMessage()));
                releaseAllLocked();
            }
        }
    }

    private void stopPipelineSync() {
        synchronized (this) {
            releaseAllLocked();
            pipelineOn.set(false);
            notifyState("server_running", false);
            Log.i(TAG, "Pipeline stopped");
        }
    }

    private void releaseAllLocked() {
        try {
            if (mjpegServer != null) mjpegServer.stop();
        } catch (Exception ignored) {
        }
        mjpegServer = null;
        try {
            if (rtspServer != null) rtspServer.stop();
        } catch (Exception ignored) {
        }
        rtspServer = null;
        try {
            if (camera != null) camera.stop();
        } catch (Exception ignored) {
        }
        camera = null;
        try {
            if (encoder != null) encoder.stop();
        } catch (Exception ignored) {
        }
        encoder = null;
    }

    private void notifyUrlChanged() {
        notifyState("stream_url", getStreamUrl());
    }

    private void notifyState(String entityId, Object value) {
        Object cb = stateListeners.get(entityId);
        if (cb != null) invokeState(cb, value);
    }

    private static void invokeState(Object callback, Object value) {
        try {
            Method m = callback.getClass().getMethod("onStateChanged", Object.class);
            m.invoke(callback, value);
        } catch (Exception ignored) {
        }
    }

    /** True only while Ava's voice satellite foreground service is alive. */
    private boolean isMasterServiceRunning() {
        // 1) Kotlin companion: VoiceSatelliteService.Companion.getInstance()
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> cls = Class.forName(VOICE_SERVICE, false, loader);
            Field companionField = cls.getField("Companion");
            Object companion = companionField.get(null);
            Method getInstance = companion.getClass().getMethod("getInstance");
            if (getInstance.invoke(companion) != null) {
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "companion getInstance: " + t.getMessage());
        }
        // 2) Fallback: own-process running service list
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningServiceInfo> list = am.getRunningServices(64);
                if (list != null) {
                    for (ActivityManager.RunningServiceInfo info : list) {
                        if (info.service != null
                                && VOICE_SERVICE.equals(info.service.getClassName())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "RunningServices check: " + t.getMessage());
        }
        return false;
    }

    private void disableCoreCameraBestEffort() {
        try {
            Class<?> storeClass = Class.forName("com.example.ava.settings.ExperimentalSettingsStore");
            Object store = storeClass.getConstructor(Context.class).newInstance(context);
            // Prefer blocking update helpers if present; suspend setCameraEnabled is not callable here.
            for (String name : new String[]{"setCameraEnabledBlocking", "setCameraEnabledSync"}) {
                try {
                    Method m = storeClass.getMethod(name, boolean.class);
                    m.invoke(store, false);
                    Log.i(TAG, "Disabled Ava core camera via " + name);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "Core camera disable skipped: " + t.getMessage());
        }
        try {
            Class<?> mgr = Class.forName(
                    "com.example.ava.esphome.voicesatellite.VoiceSatelliteCamera");
            Method clear = mgr.getMethod("clearSavedRecordingState", Context.class);
            clear.invoke(null, context);
        } catch (Throwable ignored) {
        }
    }

    private String localIp() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return Formatter.formatIpAddress(ip);
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
