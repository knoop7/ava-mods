package com.ava.mods.portal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PortalSupportManager implements PortalSensorBridge.Listener, PortalPresenceMonitor.Listener,
        PortalSoundMonitor.Listener, PortalScreenTimeoutController.PresenceState {

    private static final String TAG = "PortalSupport";
    private static final String PREFS = "portal_support";
    private static final String KEY_PRESENCE_DETECTION = "presence_detection_enabled";
    private static final String KEY_SCREEN_TIMEOUT = "screen_timeout_enabled";
    private static final long SOUND_PRESENCE_HOLD_MS = 60_000L;
    private static volatile PortalSupportManager instance;

    private final Context context;
    private final Map<String, CopyOnWriteArrayList<Object>> stateListeners = new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();

    private volatile boolean enablePresence;
    private volatile boolean enableAmbientLight;
    private volatile boolean enableLightRgb;
    private volatile boolean enableTemperature;
    private volatile boolean enableTapTilt;
    private volatile boolean enableAccelerometer;
    private volatile boolean enableSoundLevel;
    private volatile boolean enableDoorbellAlert;
    private volatile boolean enableScreenTimeout;
    private volatile boolean presenceDetectionEnabled;
    private volatile boolean screenTimeoutEnabled;
    private volatile boolean enhancedPresenceEnabled;
    private volatile int presenceSoundThreshold = 8;
    private volatile float tapThreshold = 4.0f;
    private volatile float temperatureOffset = 0.0f;
    private volatile int screenTimeoutMinutes = 5;

    private PortalPresenceMonitor presenceMonitor;
    private PortalSensorBridge sensorBridge;
    private PortalSoundMonitor soundMonitor;
    private PortalScreenTimeoutController screenTimeoutController;
    private PortalPermissionHelper permissionHelper;
    private volatile boolean portalPresent;
    private volatile boolean facePresent;
    private volatile long lastSoundActivityMs;

    private PortalSupportManager(Context context) {
        this.context = context.getApplicationContext();
        this.permissionHelper = new PortalPermissionHelper(this.context);
        android.content.SharedPreferences prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        presenceDetectionEnabled = prefs.getBoolean(KEY_PRESENCE_DETECTION, false);
        screenTimeoutEnabled = prefs.getBoolean(KEY_SCREEN_TIMEOUT, false);
    }

    public static PortalSupportManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PortalSupportManager.class) {
                if (instance == null) {
                    instance = new PortalSupportManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isSupported() {
        return isPortalDevice();
    }

    public boolean isSupported(Context context) {
        return isSupported();
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        switch (key) {
            case "enable_presence":
                enablePresence = parseBoolean(value);
                reconcilePresence();
                updateSoundSubsystem();
                break;
            case "enable_ambient_light":
                enableAmbientLight = parseBoolean(value);
                updateSensorSubsystem();
                break;
            case "enable_light_rgb":
                enableLightRgb = parseBoolean(value);
                updateSensorSubsystem();
                break;
            case "enable_temperature":
                enableTemperature = parseBoolean(value);
                updateSensorSubsystem();
                break;
            case "enable_tap_tilt":
                enableTapTilt = parseBoolean(value);
                updateSensorSubsystem();
                break;
            case "enable_accelerometer":
                enableAccelerometer = parseBoolean(value);
                updateSensorSubsystem();
                break;
            case "enable_sound_level":
                enableSoundLevel = parseBoolean(value);
                updateSoundSubsystem();
                break;
            case "enhanced_presence":
                enhancedPresenceEnabled = parseBoolean(value);
                updateSoundSubsystem();
                reconcilePresence();
                break;
            case "presence_sound_threshold":
                presenceSoundThreshold = clampSoundThreshold(parseInt(value, presenceSoundThreshold));
                break;
            case "enable_doorbell_alert":
                enableDoorbellAlert = parseBoolean(value);
                break;
            case "enable_screen_timeout":
                enableScreenTimeout = parseBoolean(value);
                updateScreenTimeoutSubsystem();
                break;
            case "tap_tilt_sensitivity":
                tapThreshold = parseFloat(value, 4.0f);
                if (sensorBridge != null) {
                    sensorBridge.setTapThreshold(tapThreshold);
                }
                break;
            case "temperature_offset":
                temperatureOffset = parseFloat(value, 0.0f);
                if (sensorBridge != null) {
                    sensorBridge.setTempOffset(temperatureOffset);
                }
                break;
            case "screen_timeout_minutes":
                screenTimeoutMinutes = clampMinutes(parseInt(value, 5));
                if (screenTimeoutController != null) {
                    screenTimeoutController.setTimeoutMinutes(screenTimeoutMinutes);
                }
                break;
            default:
                break;
        }
    }

    public void setPresenceDetection(String enabled) {
        presenceDetectionEnabled = parseBoolean(enabled);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PRESENCE_DETECTION, presenceDetectionEnabled)
                .apply();
        reconcilePresence();
        notifyStateListeners("presence_detection", Boolean.valueOf(presenceDetectionEnabled));
    }

    public boolean isPresenceDetectionEnabled() {
        return presenceDetectionEnabled;
    }

    public boolean isPortalPresent() {
        return presenceDetectionEnabled && portalPresent;
    }

    @Override
    public boolean isPresent() {
        return isPortalPresent();
    }

    public void setScreenTimeout(String enabled) {
        screenTimeoutEnabled = parseBoolean(enabled);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SCREEN_TIMEOUT, screenTimeoutEnabled)
                .apply();
        updateScreenTimeoutSubsystem();
        notifyStateListeners("screen_timeout", Boolean.valueOf(screenTimeoutEnabled));
    }

    public boolean isScreenTimeoutEnabled() {
        return screenTimeoutEnabled;
    }

    public int getScreenTimeoutMinutes() {
        return screenTimeoutMinutes;
    }

    public void setScreenTimeoutMinutes(String value) {
        screenTimeoutMinutes = clampMinutes(parseInt(value, screenTimeoutMinutes));
        if (screenTimeoutController != null) {
            screenTimeoutController.setTimeoutMinutes(screenTimeoutMinutes);
        }
    }

    public float getAmbientLight() {
        return sensorBridge == null ? 0f : sensorBridge.getLastLux();
    }

    public float getLightRed() {
        return sensorBridge == null ? 0f : sensorBridge.getLastR();
    }

    public float getLightGreen() {
        return sensorBridge == null ? 0f : sensorBridge.getLastG();
    }

    public float getLightBlue() {
        return sensorBridge == null ? 0f : sensorBridge.getLastB();
    }

    public float getTemperature() {
        return sensorBridge == null ? 0f : sensorBridge.getLastTemperature();
    }

    public float getTemperatureOffset() {
        return temperatureOffset;
    }

    public void setTemperatureOffset(String value) {
        temperatureOffset = parseFloat(value, 0.0f);
        if (sensorBridge != null) {
            sensorBridge.setTempOffset(temperatureOffset);
        }
    }

    public float getTapTiltSensitivity() {
        return tapThreshold;
    }

    public void setTapTiltSensitivity(String value) {
        tapThreshold = parseFloat(value, 4.0f);
        if (sensorBridge != null) {
            sensorBridge.setTapThreshold(tapThreshold);
        }
    }

    public String getTapTiltDirection() {
        return sensorBridge == null ? "none" : sensorBridge.getLastTapDirection();
    }

    public float getAccelX() {
        return sensorBridge == null ? 0f : sensorBridge.getLastAccelX();
    }

    public float getAccelY() {
        return sensorBridge == null ? 0f : sensorBridge.getLastAccelY();
    }

    public float getAccelZ() {
        return sensorBridge == null ? 0f : sensorBridge.getLastAccelZ();
    }

    public int getSoundLevel() {
        return soundMonitor == null ? 0 : soundMonitor.getLastLevel();
    }

    public void playDoorbell() {
        PortalTonePlayer.play("doorbell");
    }

    public void playAlert() {
        PortalTonePlayer.play("alert");
    }

    public boolean grantPortalPermissionsIfNeeded() {
        if (!isSupported()) {
            return false;
        }
        return grantPortalPermissionsIfNeeded(context);
    }

    public boolean grantPortalPermissionsIfNeeded(Context context) {
        boolean granted = permissionHelper.grantAll();
        permissionHelper.ensureAppOps();
        return granted;
    }

    public boolean grantOverlayPermissionIfNeeded() {
        return grantPortalPermissionsIfNeeded();
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        return grantPortalPermissionsIfNeeded(context);
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
        pushCurrentState(entityId, callback);
        return true;
    }

    @Override
    public void onPresenceChanged(boolean present) {
        facePresent = present;
        recomputePresence();
    }

    @Override
    public void onPresenceTick() {
        recomputePresence();
    }

    @Override
    public void onAmbientLight(float lux) {
        notifyStateListeners("ambient_light", Float.valueOf(lux));
    }

    @Override
    public void onRgb(float r, float g, float b) {
        notifyStateListeners("light_red", Float.valueOf(r));
        notifyStateListeners("light_green", Float.valueOf(g));
        notifyStateListeners("light_blue", Float.valueOf(b));
    }

    @Override
    public void onTemperature(float celsius) {
        notifyStateListeners("temperature", Float.valueOf(celsius));
    }

    @Override
    public void onAccelerometer(float x, float y, float z) {
        notifyStateListeners("accel_x", Float.valueOf(x));
        notifyStateListeners("accel_y", Float.valueOf(y));
        notifyStateListeners("accel_z", Float.valueOf(z));
    }

    @Override
    public void onTapTilt(String direction) {
        notifyStateListeners("tap_tilt", direction);
    }

    @Override
    public void onSoundLevel(int level) {
        if (enableSoundLevel) {
            notifyStateListeners("sound_level", Integer.valueOf(level));
        }
        if (enhancedPresenceEnabled && presenceDetectionEnabled && enablePresence
                && level >= presenceSoundThreshold) {
            lastSoundActivityMs = System.currentTimeMillis();
            recomputePresence();
        }
    }

    /** Match portal-ha-bridge reconcilePresence: persist switch, start monitor when logcat is available. */
    private void reconcilePresence() {
        if (!enablePresence || !presenceDetectionEnabled) {
            if (presenceMonitor != null) {
                presenceMonitor.release();
                presenceMonitor = null;
            }
            portalPresent = false;
            facePresent = false;
            lastSoundActivityMs = 0L;
            notifyStateListeners("portal_presence", Boolean.FALSE);
            return;
        }
        permissionHelper.ensureReadLogsForPresence();
        boolean canRead = permissionHelper.hasReadLogs() || permissionHelper.canUsePrivilegedLogcat();
        if (!canRead) {
            if (presenceMonitor != null) {
                presenceMonitor.release();
                presenceMonitor = null;
            }
            facePresent = false;
            if (!enhancedPresenceEnabled) {
                portalPresent = false;
                lastSoundActivityMs = 0L;
                notifyStateListeners("portal_presence", Boolean.FALSE);
                Log.w(TAG, "presence enabled but READ_LOGS missing — run provision.sh or authorize Shizuku, then restart Ava");
                return;
            }
            Log.w(TAG, "READ_LOGS missing — face presence off; enhanced sound-only active");
            updateSoundSubsystem();
            recomputePresence();
            return;
        }
        if (presenceMonitor == null) {
            presenceMonitor = new PortalPresenceMonitor(context, this);
        }
        presenceMonitor.start();
        if (!permissionHelper.hasReadLogs()) {
            Log.w(TAG, "READ_LOGS not in app process yet — using privileged shell; restart Ava after grant for in-app logcat");
        }
        updateSoundSubsystem();
        recomputePresence();
    }

    private void updatePresenceSubsystem() {
        reconcilePresence();
    }

    private void recomputePresence() {
        if (!enablePresence || !presenceDetectionEnabled) {
            portalPresent = false;
            notifyStateListeners("portal_presence", Boolean.FALSE);
            return;
        }
        boolean soundActive = enhancedPresenceEnabled && lastSoundActivityMs > 0L
                && System.currentTimeMillis() - lastSoundActivityMs < SOUND_PRESENCE_HOLD_MS;
        boolean combined = facePresent || soundActive;
        if (combined == portalPresent) {
            return;
        }
        portalPresent = combined;
        if (combined && screenTimeoutController != null) {
            screenTimeoutController.onPresenceActivity();
        }
        Log.i(TAG, "presence -> " + (combined ? "DETECTED" : "CLEAR")
                + " (face=" + facePresent + " sound=" + soundActive + ")");
        notifyStateListeners("portal_presence", Boolean.valueOf(isPortalPresent()));
    }

    private boolean needsSoundMonitor() {
        return enableSoundLevel
                || (enhancedPresenceEnabled && enablePresence && presenceDetectionEnabled);
    }

    private void updateSoundSubsystem() {
        if (needsSoundMonitor()) {
            if (!hasRecordAudio()) {
                Log.w(TAG, "sound level enabled but RECORD_AUDIO missing — requesting via Shizuku/root");
                permissionHelper.ensurePermission(Manifest.permission.RECORD_AUDIO);
            }
            if (soundMonitor == null) {
                soundMonitor = new PortalSoundMonitor(context, this);
            }
            soundMonitor.start();
            return;
        }
        if (soundMonitor != null) {
            soundMonitor.stop();
            soundMonitor = null;
        }
    }

    private void updateScreenTimeoutSubsystem() {
        if (enableScreenTimeout && screenTimeoutEnabled) {
            PortalScreenControl.enableAccessibility(context);
            if (screenTimeoutController == null) {
                screenTimeoutController = new PortalScreenTimeoutController(context, this);
            }
            screenTimeoutController.start(true, screenTimeoutMinutes);
            return;
        }
        if (screenTimeoutController != null) {
            screenTimeoutController.release();
            screenTimeoutController = null;
        }
    }

    private void updateSensorSubsystem() {
        boolean needsSensors = enableAmbientLight || enableLightRgb || enableTemperature
                || enableTapTilt || enableAccelerometer;
        if (!needsSensors) {
            if (sensorBridge != null) {
                sensorBridge.stop();
                sensorBridge = null;
            }
            return;
        }
        if (sensorBridge == null) {
            sensorBridge = new PortalSensorBridge(context, this);
            sensorBridge.setTapThreshold(tapThreshold);
            sensorBridge.setTempOffset(temperatureOffset);
            sensorBridge.start(enableAmbientLight, enableLightRgb, enableTemperature, enableTapTilt, enableAccelerometer);
            return;
        }
        sensorBridge.setTapThreshold(tapThreshold);
        sensorBridge.setTempOffset(temperatureOffset);
        sensorBridge.updateFlags(enableAmbientLight, enableLightRgb, enableTemperature, enableTapTilt, enableAccelerometer);
    }

    private void pushCurrentState(String entityId, Object callback) {
        if ("portal_presence".equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(isPortalPresent()));
        } else if ("presence_detection".equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(presenceDetectionEnabled));
        } else if ("screen_timeout".equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(screenTimeoutEnabled));
        } else if ("ambient_light".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getAmbientLight()));
        } else if ("light_red".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getLightRed()));
        } else if ("light_green".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getLightGreen()));
        } else if ("light_blue".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getLightBlue()));
        } else if ("temperature".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getTemperature()));
        } else if ("tap_tilt".equals(entityId)) {
            notifySingleListener(callback, getTapTiltDirection());
        } else if ("accel_x".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getAccelX()));
        } else if ("accel_y".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getAccelY()));
        } else if ("accel_z".equals(entityId)) {
            notifySingleListener(callback, Float.valueOf(getAccelZ()));
        } else if ("sound_level".equals(entityId)) {
            notifySingleListener(callback, Integer.valueOf(getSoundLevel()));
        }
    }

    private void notifyStateListeners(String entityId, Object value) {
        List<Object> listeners = stateListeners.get(entityId);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Object listener : listeners) {
            notifySingleListener(listener, value);
        }
    }

    private void notifySingleListener(Object listener, Object value) {
        try {
            Method method = listener.getClass().getMethod("onStateChanged", Object.class);
            method.invoke(listener, value);
        } catch (Exception e) {
            Log.w(TAG, "State listener callback failed for " + listener, e);
        }
    }

    private boolean hasRecordAudio() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isPortalDevice() {
        String model = safeLower(Build.MODEL);
        String manufacturer = safeLower(Build.MANUFACTURER);
        String device = safeLower(Build.DEVICE);
        String brand = safeLower(Build.BRAND);
        if (model.contains("portal")) {
            return true;
        }
        if (manufacturer.contains("facebook") || brand.contains("facebook") || brand.contains("meta")) {
            return model.contains("portal") || device.contains("portal") || "cipher".equals(device);
        }
        return "cipher".equals(device) || device.contains("portal");
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value.trim());
    }

    private float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int clampMinutes(int minutes) {
        if (minutes < 1) {
            return 1;
        }
        if (minutes > 240) {
            return 240;
        }
        return minutes;
    }

    private int clampSoundThreshold(int threshold) {
        if (threshold < 0) {
            return 0;
        }
        if (threshold > 100) {
            return 100;
        }
        return threshold;
    }
}
