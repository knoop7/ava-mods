package com.ava.mods.phicomm;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phicomm R1 device mod — stock 小讯 LED timing via Ava voice pipeline + msgcenter IPC.
 */
public class PhicommR1SupportManager implements PhicommKeyEventListener.Handler {
    private static final String TAG = "PhicommR1Support";
    private static final String ENTITY_VOICE_LED = "voice_led";
    private static final String ENTITY_TOP_KEY = "top_key";

    private static volatile PhicommR1SupportManager instance;

    private final Context appContext;
    private final PhicommVoiceLightListener voiceLightListener;
    private final PhicommLightController lightController;
    private final PhicommStatusBridge statusBridge;
    private final PhicommKeyEventListener keyListener;
    private final PhicommNetworkLightWatcher networkLightWatcher;
    private final PhicommVolumeLightWatcher volumeLightWatcher;
    private final PhicommMusicLightController musicLightController;
    private final PhicommPrivilegedShell privilegedShell;
    private final PhicommLightsEffectsCatalog lightsCatalog;
    private final PhicommVolumeHelper volumeHelper;
    private final Map<String, CopyOnWriteArrayList<Object>> stateListeners =
        new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();

    private volatile Boolean cachedSupported;
    private boolean enableFourMic = false;
    private boolean enableVoiceLed = true;
    private boolean enableDormantLight = true;
    private boolean enableNetLight = true;
    private boolean enableVolumeLed = false;
    private boolean enableTopKeySensor = true;
    private boolean enableMusicRgbLight = true;
    private boolean voiceSessionActive;
    private boolean dormantActive;
    private volatile boolean servicesStarted;

    private PhicommR1SupportManager(Context context) {
        appContext = context.getApplicationContext();
        lightController = new PhicommLightController(appContext);
        voiceLightListener = new PhicommVoiceLightListener(appContext);
        statusBridge = new PhicommStatusBridge(appContext);
        keyListener = new PhicommKeyEventListener(appContext);
        networkLightWatcher = new PhicommNetworkLightWatcher(appContext, lightController);
        volumeLightWatcher = new PhicommVolumeLightWatcher(appContext, lightController);
        musicLightController = new PhicommMusicLightController(appContext);
        privilegedShell = new PhicommPrivilegedShell(appContext);
        lightsCatalog = new PhicommLightsEffectsCatalog();
        volumeHelper = new PhicommVolumeHelper(appContext, privilegedShell);
        keyListener.setHandler(this);
        bootstrapFromAvaConfig();
    }

    public static PhicommR1SupportManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PhicommR1SupportManager.class) {
                if (instance == null) {
                    instance = new PhicommR1SupportManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isSupported() {
        if (cachedSupported != null) {
            return cachedSupported.booleanValue();
        }
        boolean supported = matchesBuildFingerprint() && lightController.isAvailable();
        if (supported) {
            startServices();
        }
        cachedSupported = supported;
        Log.i(TAG, "device supported=" + supported
            + " manufacturer=" + Build.MANUFACTURER
            + " model=" + Build.MODEL
            + " device=" + Build.DEVICE);
        return supported;
    }

    public boolean isSupported(Context context) {
        return isSupported();
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        bootstrapFromAvaConfig();
        return false;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        switch (key) {
            case "enable_four_mic":
                setFourMicEnabled(parseBoolean(value, false));
                break;
            case "enable_voice_led":
                setVoiceLedEnabled(parseBoolean(value, true));
                break;
            case "enable_dormant_light":
                setDormantLightEnabled(parseBoolean(value, true));
                break;
            case "enable_net_light":
                setNetLightEnabled(parseBoolean(value, true));
                break;
            case "enable_volume_led":
                setVolumeLedEnabled(parseBoolean(value, false));
                break;
            case "enable_top_key_sensor":
                setTopKeySensorEnabled(parseBoolean(value, true));
                break;
            case "enable_music_rgb_light":
                setMusicRgbLightEnabled(parseBoolean(value, true));
                break;
            default:
                break;
        }
    }

    public void setFourMicEnabled(boolean enabled) {
        enableFourMic = enabled;
        if (!isSupported()) {
            return;
        }
        if (enabled) {
            PhicommDoaResolver.start(appContext, privilegedShell);
        } else {
            PhicommDoaResolver.stop();
        }
        Log.i(TAG, "four mic enabled=" + enabled);
    }

    public void setVoiceLedEnabled(boolean enabled) {
        if (!isSupported()) {
            return;
        }
        enableVoiceLed = enabled;
        if (!enabled) {
            voiceLightListener.onInterrupt();
            voiceSessionActive = false;
        }
        notifyEntityListeners(ENTITY_VOICE_LED, Boolean.valueOf(enabled));
        Log.i(TAG, "voice LED enabled=" + enabled);
    }

    public boolean isVoiceLedEnabled() {
        return isSupported() && enableVoiceLed;
    }

    public void setDormantLightEnabled(boolean enabled) {
        enableDormantLight = enabled;
        if (!enabled && dormantActive) {
            exitDormant(false);
        }
        Log.i(TAG, "dormant light enabled=" + enabled);
    }

    public void setNetLightEnabled(boolean enabled) {
        enableNetLight = enabled;
        networkLightWatcher.setEnabled(enabled);
        Log.i(TAG, "net light enabled=" + enabled);
    }

    public void setVolumeLedEnabled(boolean enabled) {
        enableVolumeLed = enabled;
        volumeLightWatcher.setEnabled(enabled);
        Log.i(TAG, "volume light enabled=" + enabled);
    }

    public void setTopKeySensorEnabled(boolean enabled) {
        enableTopKeySensor = enabled;
        if (enabled) {
            ensureServicesStarted();
            keyListener.start();
        } else {
            keyListener.stop();
            notifyEntityListeners(ENTITY_TOP_KEY, "idle");
        }
        Log.i(TAG, "top key sensor enabled=" + enabled);
    }

    public void setMusicRgbLightEnabled(boolean enabled) {
        enableMusicRgbLight = enabled;
        musicLightController.setEnabled(enabled);
        Log.i(TAG, "music RGB light enabled=" + enabled);
    }

    public boolean isMusicRgbLightEnabled() {
        return isSupported() && enableMusicRgbLight;
    }

    /** Diagnostic: {@code jni} or {@code msgcenter} fallback backend. */
    public String getMusicLightBackend() {
        if (!isSupported()) {
            return "unsupported";
        }
        return musicLightController.getBackendLabel();
    }

    /** Diagnostic: {@code root} / {@code system} / {@code cache} / {@code builtin}. */
    public String getLightsEffectsSource() {
        if (!isSupported()) {
            return "unsupported";
        }
        return lightsCatalog.getSource();
    }

    public boolean isRootAvailable() {
        return isSupported() && privilegedShell.isRootAvailable();
    }

    public String getVolumeControlMethod() {
        if (!isSupported()) {
            return "unsupported";
        }
        return volumeHelper.getLastMethod();
    }

    /** HA fallback when touch ring is broken — root {@code input keyevent} first, else AudioManager. */
    public boolean adjustVolumeUp() {
        if (!isSupported()) {
            return false;
        }
        return volumeHelper.adjustVolumeUp();
    }

    public boolean adjustVolumeDown() {
        if (!isSupported()) {
            return false;
        }
        return volumeHelper.adjustVolumeDown();
    }

    /** Home Assistant text_sensor — last top-cover gesture label. */
    public String getTopKeyEvent() {
        if (!isSupported() || !enableTopKeySensor) {
            return "idle";
        }
        return keyListener.getLastKeyLabel();
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<Object>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        if (ENTITY_VOICE_LED.equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(isVoiceLedEnabled()));
        } else if (ENTITY_TOP_KEY.equals(entityId)) {
            notifySingleListener(callback, getTopKeyEvent());
        }
        return true;
    }

    public void onVoicePipelineEvent(Context context, String event, Bundle extras) {
        if (!isSupported() || event == null) {
            return;
        }
        if (enableMusicRgbLight) {
            musicLightController.onVoicePipelineEvent(event);
        }

        if (!enableVoiceLed || !voiceLightListener.isAvailable()) {
            return;
        }
        if (dormantActive) {
            return;
        }

        switch (event) {
            case "wake_detected":
                if (voiceSessionActive) {
                    voiceLightListener.onInterrupt();
                }
                voiceSessionActive = true;
                voiceLightListener.onWakeupSuccess(PhicommDoaResolver.resolve(extras));
                break;

            case "stt_vad_end":
                voiceLightListener.onRecognizeStart();
                break;

            case "tts_finished":
                voiceLightListener.onTTSEnd();
                break;

            case "session_ended":
            case "pipeline_error":
                voiceLightListener.onInterrupt();
                voiceSessionActive = false;
                break;

            default:
                break;
        }
    }

    @Override
    public void onTopKeyGesture(int type, String label) {
        notifyEntityListeners(ENTITY_TOP_KEY, label);
    }

    @Override
    public void onSingleClick() {
        if (dormantActive) {
            exitDormant(true);
        }
    }

    @Override
    public void onDoubleClick() {
        if (!enableDormantLight || dormantActive) {
            return;
        }
        enterDormant();
    }

    @Override
    public void onTripleClick() {
        // Sensor only — stock TripleClickProcessor opens BT mode; not replicated here.
    }

    @Override
    public void onLongClick() {
        // Sensor only — stock LongClickProcessor opens connect-net UI; not replicated here.
    }

    private void enterDormant() {
        voiceLightListener.onInterrupt();
        voiceSessionActive = false;
        lightController.turnOnDormantLight();
        statusBridge.syncDeviceStatus(PhicommStatusBridge.STATUS_DORMANT);
        dormantActive = true;
        musicLightController.setDormantActive(true);
        Log.i(TAG, "entered dormant mode");
    }

    private void exitDormant(boolean fromSingleClick) {
        lightController.turnOffDormantLight();
        statusBridge.syncDeviceStatus(PhicommStatusBridge.STATUS_SPEECH);
        dormantActive = false;
        musicLightController.setDormantActive(false);
        Log.i(TAG, "exited dormant mode singleClick=" + fromSingleClick);
    }

    private void startServices() {
        ensureServicesStarted();
        privilegedShell.isRootAvailable();
        lightsCatalog.load(appContext, privilegedShell);
        if (enableFourMic) {
            PhicommDoaResolver.start(appContext, privilegedShell);
        }
        networkLightWatcher.setEnabled(enableNetLight);
        networkLightWatcher.start();
        volumeLightWatcher.setEnabled(enableVolumeLed);
        volumeLightWatcher.start();
        musicLightController.setEnabled(enableMusicRgbLight);
        musicLightController.start();
        if (enableTopKeySensor) {
            keyListener.start();
        }
    }

    private void ensureServicesStarted() {
        if (servicesStarted) {
            return;
        }
        servicesStarted = true;
    }

    private void bootstrapFromAvaConfig() {
        try {
            java.io.File config = new java.io.File(
                appContext.getFilesDir(),
                "mod_configs/phicomm-r1-support.json"
            );
            if (!config.exists()) {
                return;
            }
            String json = readUtf8(config);
            enableFourMic = readConfigBoolean(json, "enable_four_mic", false);
            enableVoiceLed = readConfigBoolean(json, "enable_voice_led", true);
            enableDormantLight = readConfigBoolean(json, "enable_dormant_light", true);
            enableNetLight = readConfigBoolean(json, "enable_net_light", true);
            enableVolumeLed = readConfigBoolean(json, "enable_volume_led", false);
            enableTopKeySensor = readConfigBoolean(json, "enable_top_key_sensor", true);
            enableMusicRgbLight = readConfigBoolean(json, "enable_music_rgb_light", true);
        } catch (Throwable t) {
            Log.w(TAG, "bootstrapFromAvaConfig failed", t);
        }
    }

    private static boolean readConfigBoolean(String json, String key, boolean defaultValue) {
        if (!json.contains("\"" + key + "\"")) {
            return defaultValue;
        }
        return !json.contains("\"" + key + "\":false")
            && !json.contains("\"" + key + "\": false");
    }

    private boolean matchesBuildFingerprint() {
        String manufacturer = safeLower(Build.MANUFACTURER);
        String model = safeLower(Build.MODEL);
        String device = safeLower(Build.DEVICE);
        String product = safeLower(Build.PRODUCT);
        String brand = safeLower(Build.BRAND);
        return manufacturer.contains("phicomm")
            || brand.contains("phicomm")
            || device.contains("rk322x")
            || model.contains("rk322x")
            || product.contains("rk322x")
            || model.contains("r1");
    }

    private void notifyEntityListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            return;
        }
        for (Object listener : listeners) {
            notifySingleListener(listener, value);
        }
    }

    private static void notifySingleListener(Object listener, Object value) {
        try {
            Method method = listener.getClass().getMethod("onStateChanged", Object.class);
            method.invoke(listener, value);
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify state listener", e);
        }
    }

    private static String readUtf8(java.io.File file) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim().toLowerCase();
        if ("true".equals(trimmed) || "1".equals(trimmed)) {
            return true;
        }
        if ("false".equals(trimmed) || "0".equals(trimmed)) {
            return false;
        }
        return defaultValue;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
