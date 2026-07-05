package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1:1 port of esphome-ble_adv_proxy for Ava.
 *
 * <p>Services: setup_svc_v0, adv_svc (legacy), adv_svc_v1
 * <p>Event: esphome.ble_adv.raw_adv with keys raw + orig
 *
 * <p>Transmit semantics mirror ESP32: each {@code repeat} is a separate advertising burst
 * ({@code esp_ble_gap_config_adv_data_raw} + {@code esp_ble_gap_start_advertising} cycle),
 * not one merged window.
 */
public class BleAdvProxyManager {
    private static final String TAG = "BleAdvProxyManager";
    private static volatile BleAdvProxyManager instance;

    private static final String EVENT_RAW_ADV = "esphome.ble_adv.raw_adv";
    private static final String KEY_RAW = "raw";
    private static final String KEY_ORIGIN = "orig";

    private static final int REPEAT_NB = 3;
    private static final int MIN_VIABLE_PACKET_LEN = 5;

    private final Context context;
    private final BleAdvDedupCache dedupCache = new BleAdvDedupCache();
    private final ConcurrentLinkedQueue<TransmitJob> transmitQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
    private final AtomicLong recvDebugCounter = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "BleAdvProxy");
            t.setDaemon(true);
            return t;
        }
    });

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Object>> stateListeners =
            new ConcurrentHashMap<>();

    private volatile boolean featureEnabled = true;
    private volatile boolean useMaxTxPower = false;
    private volatile boolean rawHciEnabled = false;
    private final BleAdvPermissionHelper permissionHelper;
    private final RawHciAdvertiser rawHciAdvertiser;
    private final BleAdvCapabilityProbe capabilityProbe;
    private volatile BleAdvCapabilityProbe.Report lastCapabilityReport;
    private final AtomicBoolean probeRunning = new AtomicBoolean(false);
    private volatile String adapterNameOverride = "";
    private volatile String deviceName = "";
    private volatile Object hostApi;
    private volatile boolean haServicesReady = false;
    private volatile boolean setupDone = false;
    private volatile String lastAdvertiseError = "";

    private BleAdvProxyManager(Context context) {
        this.context = context.getApplicationContext();
        this.permissionHelper = new BleAdvPermissionHelper(this.context);
        this.rawHciAdvertiser = new RawHciAdvertiser(this.context, permissionHelper);
        this.capabilityProbe = new BleAdvCapabilityProbe(this.context, permissionHelper, rawHciAdvertiser);
    }

    public static BleAdvProxyManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BleAdvProxyManager.class) {
                if (instance == null) {
                    instance = new BleAdvProxyManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isBleAdvProxySupported(Context ctx) {
        return true;
    }

    public boolean isFeatureEnabled(Context ctx) {
        return featureEnabled;
    }

    public boolean isProxyReady() {
        return featureEnabled && haServicesReady && setupDone;
    }

    public String getLastAdvertiseError() {
        return lastAdvertiseError != null ? lastAdvertiseError : "";
    }

    /** Human-readable self-probe summary (ha-ble-adv transport + privileged shell). */
    public String getCapabilityReport() {
        BleAdvCapabilityProbe.Report report = lastCapabilityReport;
        return report != null && report.summary != null ? report.summary : "capability not probed";
    }

    public String getRawTransport() {
        BleAdvCapabilityProbe.Report report = lastCapabilityReport;
        if (report != null && report.rawTransport != null) {
            return report.rawTransport;
        }
        return rawHciAdvertiser.getLastTransport();
    }

    public String getPrivilegedShell() {
        BleAdvCapabilityProbe.Report report = lastCapabilityReport;
        if (report != null && report.privilegedShell != null) {
            return report.privilegedShell;
        }
        return permissionHelper.getPrivilegedShellLabel();
    }

    public String getFidelityMode() {
        BleAdvCapabilityProbe.Report report = lastCapabilityReport;
        return report != null && report.fidelityMode != null ? report.fidelityMode : "unknown";
    }

    public String getAdapterName() {
        String raw;
        if (adapterNameOverride != null && !adapterNameOverride.trim().isEmpty()) {
            raw = adapterNameOverride.trim();
        } else {
            raw = deviceName != null ? deviceName : "";
        }
        return normalizeNodeName(raw);
    }

    public String getAdapterName(Context ctx) {
        return getAdapterName();
    }

    /**
     * ESPHome node names must be a lowercase slug ([a-z0-9_]). Home Assistant lowercases every
     * registered service name, while ha-ble-adv builds its ESPHome service-lookup key from the
     * reported device name case-sensitively (esp_adapters.py:
     * {@code f"{device_name.replace('-', '_')}_{svc}"}). An uppercase name (e.g. "MI_9") therefore
     * makes ble_adv fail to match {@code <name>_adv_svc_v1}/{@code <name>_setup_svc_v0} and report
     * "Invalid adapter". Normalize thoroughly to a safe lowercase slug.
     */
    private static String normalizeNodeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public void applyConfig(String key, String value) {
        if (key == null) {
            return;
        }
        switch (key) {
            case "enabled":
                featureEnabled = parseBoolean(value, true);
                notifyProxyReadyChanged();
                break;
            case "use_max_tx_power":
                useMaxTxPower = parseBoolean(value, true);
                break;
            case "enable_raw_hci":
                rawHciEnabled = parseBoolean(value, true);
                scheduleCapabilityProbe();
                break;
            case "adapter_name":
                adapterNameOverride = value != null ? value : "";
                notifyStateListeners("ble_adv_proxy_name", getAdapterName());
                break;
            default:
                break;
        }
    }

    public void onEspHomeConnected(Context ctx, String deviceName, Object hostApi) {
        this.deviceName = deviceName != null ? deviceName : "";
        this.hostApi = hostApi;
        notifyStateListeners("ble_adv_proxy_name", getAdapterName());
        scheduleCapabilityProbe();
        Log.i(TAG, "ESPHome connected (adapter=" + getAdapterName(ctx) + ")");
    }

    public void onEspHomeDisconnected(Context ctx) {
        haServicesReady = false;
        setupDone = false;
        hostApi = null;
        transmitQueue.clear();
        notifyProxyReadyChanged();
    }

    public void onHomeassistantServicesSubscribed(Context ctx) {
        haServicesReady = true;
        notifyStateListeners("ble_adv_proxy_name", getAdapterName());
        notifyProxyReadyChanged();
        scheduleCapabilityProbe();
        Log.d(TAG, "HA homeassistant services subscribed");
    }

    public void onScanResult(Context ctx, String mac, int rssi, byte[] raw) {
        if (!featureEnabled || !haServicesReady || !setupDone) {
            return;
        }
        if (raw == null || raw.length < MIN_VIABLE_PACKET_LEN) {
            return;
        }
        byte[] adv = trimAdvPadding(raw);
        if (adv.length < MIN_VIABLE_PACKET_LEN || adv.length > 31) {
            return;
        }
        if (dedupCache.isMacIgnored(mac) || dedupCache.isCompanyIdIgnored(adv)) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + dedupCache.getDupeIgnoreDurationMs();
        if (!dedupCache.checkAddDupePacket(adv, expiresAt)) {
            return;
        }
        if (recvDebugCounter.incrementAndGet() % 50 == 1) {
            Log.d(TAG, "raw_adv sample orig=" + normalizeMac(mac) + " rssi=" + rssi
                    + " len=" + adv.length + " raw=" + RawAdvParser.toHex(adv));
        }
        Map<String, String> payload = new HashMap<>();
        payload.put(KEY_RAW, RawAdvParser.toHex(adv));
        payload.put(KEY_ORIGIN, normalizeMac(mac));
        fireHomeassistantEvent(EVENT_RAW_ADV, payload);
    }

    private static String normalizeMac(String mac) {
        if (mac == null) {
            return "";
        }
        return mac.replace('-', ':').trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Walk the AD structures ([len][type][len-1 payload]...) and return the real advertising
     * length, stopping at the first zero-length field (the padding Android appends) or a malformed
     * structure. Returns a trimmed copy, or the original array when there is no padding to remove.
     */
    static byte[] trimAdvPadding(byte[] raw) {
        int i = 0;
        int n = raw.length;
        while (i < n) {
            int len = raw[i] & 0xFF;
            if (len == 0) {
                break;
            }
            if (i + 1 + len > n) {
                break;
            }
            i += 1 + len;
        }
        if (i >= n) {
            return raw;
        }
        return java.util.Arrays.copyOf(raw, i);
    }

    public void onServiceCall(Context ctx, String serviceName, Map<String, Object> args) {
        if (!featureEnabled || serviceName == null) {
            return;
        }
        switch (serviceName) {
            case "setup_svc_v0":
                handleSetup(args);
                break;
            case "adv_svc":
                handleAdvertiseV0(args);
                break;
            case "adv_svc_v1":
                handleAdvertiseV1(args);
                break;
            default:
                Log.w(TAG, "Unknown service: " + serviceName);
                break;
        }
    }

    public void onDestroy() {
        transmitQueue.clear();
        hostApi = null;
        haServicesReady = false;
        setupDone = false;
        notifyProxyReadyChanged();
    }

    /** setup_svc_v0(ignored_duration, ignored_cids, ignored_macs) */
    private void handleSetup(Map<String, Object> args) {
        float ignoredDuration = readFloat(args, "ignored_duration", 20_000f);
        List<?> ignoredCids = readList(args, "ignored_cids");
        List<?> ignoredMacs = readList(args, "ignored_macs");
        dedupCache.clearDupes();
        dedupCache.configureSetup(ignoredDuration, ignoredCids, ignoredMacs);
        setupDone = true;
        notifyProxyReadyChanged();
        Log.i(TAG, "setup_svc_v0 durationMs=" + ignoredDuration
                + " ignoredCids=" + (ignoredCids != null ? ignoredCids.size() : 0)
                + " ignoredMacs=" + (ignoredMacs != null ? ignoredMacs.size() : 0));
    }

    /** adv_svc(raw, duration) -> adv_svc_v1 with repeat=3 */
    private void handleAdvertiseV0(Map<String, Object> args) {
        String raw = readString(args, "raw");
        float duration = readFloat(args, "duration", 100f);
        if (raw.isEmpty()) {
            return;
        }
        float perBurst = duration / (float) REPEAT_NB;
        List<String> ignoredAdvs = new ArrayList<>();
        ignoredAdvs.add(raw);
        handleAdvertiseV1Internal(raw, perBurst, REPEAT_NB, ignoredAdvs, dedupCache.getDupeIgnoreDurationMs());
    }

    /** adv_svc_v1(raw, duration, repeat, ignored_advs, ignored_duration) */
    private void handleAdvertiseV1(Map<String, Object> args) {
        String raw = readString(args, "raw");
        float duration = readFloat(args, "duration", 100f);
        float repeat = readFloat(args, "repeat", 1f);
        if (raw.isEmpty()) {
            return;
        }
        List<String> ignoredAdvs = readStringList(args, "ignored_advs");
        if (ignoredAdvs.isEmpty()) {
            ignoredAdvs.add(raw);
        }
        float ignDuration = readFloat(args, "ignored_duration", dedupCache.getDupeIgnoreDurationMs());
        int intRepeat = Math.max(1, (int) repeat);
        handleAdvertiseV1Internal(raw, duration, intRepeat, ignoredAdvs, ignDuration);
    }

    /**
     * ESP32: each repeat enqueues a separate send_packets_ entry with
     * {@code adv_time = max(MIN_ADV, 1.6 * duration)} (0.625&nbsp;ms units).
     */
    /**
     * ESP32: each repeat enqueues a separate send_packets_ entry with
     * {@code adv_time = max(MIN_ADV, 1.6 * duration)} (0.625&nbsp;ms units).
     */
    private static int computeBurstMs(float durationMs) {
        int scaled = Math.max(1, Math.round(durationMs * 1.6f));
        return Math.max(BleAdvTransmitter.ANDROID_MIN_BURST_MS, scaled);
    }

    private void handleAdvertiseV1Internal(
            String raw,
            float durationMs,
            int repeat,
            List<String> ignoredAdvs,
            float ignDurationMs
    ) {
        setupDone = true;
        notifyProxyReadyChanged();
        int perBurstMs = computeBurstMs(durationMs);
        long intIgnDuration = (long) ignDurationMs;

        Log.d(TAG, "send adv - " + raw + ", duration " + durationMs + "ms x repeat " + repeat
                + " -> perBurst " + perBurstMs + "ms (ESP32 sequential)");

        byte[] rawBytes;
        try {
            rawBytes = RawAdvParser.fromHex(raw);
        } catch (Exception e) {
            Log.w(TAG, "Invalid raw hex: " + raw, e);
            setLastAdvertiseError("invalid_raw_hex");
            return;
        }

        for (String ignoredAdv : ignoredAdvs) {
            dedupCache.ignoreHexEcho(ignoredAdv, intIgnDuration);
        }

        transmitQueue.offer(new TransmitJob(rawBytes, perBurstMs, repeat));
        drainTransmitQueue();
    }

    private void drainTransmitQueue() {
        if (!drainRunning.compareAndSet(false, true)) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    BleAdvTransmitter transmitter = new BleAdvTransmitter(context, useMaxTxPower);
                    while (true) {
                        final TransmitJob job = transmitQueue.poll();
                        if (job == null) {
                            break;
                        }
                        // One exclusive window for the whole repeat sequence (matches ESP32 gap
                        // between bursts while avoiding scan restart per packet).
                        runExclusive(new Runnable() {
                            @Override
                            public void run() {
                                for (int i = 0; i < job.repeat; i++) {
                                    String err = transmitOneBurst(transmitter, job.raw, job.perBurstMs);
                                    if (err != null && !err.isEmpty()) {
                                        setLastAdvertiseError(err);
                                    } else {
                                        clearLastAdvertiseError();
                                    }
                                    if (i + 1 < job.repeat) {
                                        try {
                                            Thread.sleep(2L);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    }
                                }
                            }
                        });
                    }
                } finally {
                    drainRunning.set(false);
                    if (!transmitQueue.isEmpty()) {
                        drainTransmitQueue();
                    }
                }
            }
        });
    }

    /**
     * Raw HCI/MGMT when enabled; AdvertiseData only if PDU has no custom Flags AD.
     */
    private String transmitOneBurst(BleAdvTransmitter transmitter, byte[] raw, int burstMs) {
        boolean needsExactPdu = RawAdvParser.hasFlagsAd(raw);
        boolean tryRaw = rawHciEnabled && rawHciAdvertiser.isAvailable();
        if (tryRaw) {
            String rawErr = rawHciAdvertiser.transmit(-1, "auto", burstMs, raw);
            if (rawErr == null) {
                Log.d(TAG, "TX ok raw HCI/MGMT len=" + raw.length + " burst=" + burstMs + "ms");
                return "";
            }
            Log.w(TAG, "raw HCI failed: " + rawErr + " hex=" + RawAdvParser.toHex(raw));
            if (needsExactPdu) {
                return "flags_need_raw_hci:" + rawErr;
            }
        } else if (needsExactPdu) {
            return "flags_need_raw_hci:" + privilegedShellLabel();
        }
        String err = transmitter.transmitBlocking(raw, burstMs);
        if (err == null || err.isEmpty()) {
            Log.d(TAG, "TX ok AdvertiseData burst=" + burstMs + "ms");
        }
        return err;
    }

    private String privilegedShellLabel() {
        return permissionHelper.getPrivilegedShellLabel();
    }

    private void scheduleCapabilityProbe() {
        if (!featureEnabled) {
            return;
        }
        if (!probeRunning.compareAndSet(false, true)) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runExclusive(new Runnable() {
                        @Override
                        public void run() {
                            BleAdvCapabilityProbe.Report report = capabilityProbe.probe(
                                    rawHciEnabled,
                                    useMaxTxPower,
                                    new BleAdvCapabilityProbe.ExclusiveRunner() {
                                        @Override
                                        public void runExclusive(Runnable task) {
                                            task.run();
                                        }
                                    });
                            lastCapabilityReport = report;
                            notifyStateListeners("capability_report", report.summary);
                            notifyStateListeners("raw_transport", report.rawTransport);
                            notifyStateListeners("privileged_shell", report.privilegedShell);
                            notifyStateListeners("fidelity_mode", report.fidelityMode);
                        }
                    });
                } finally {
                    probeRunning.set(false);
                }
            }
        });
    }

    private void runExclusive(Runnable task) {
        Object api = hostApi;
        if (api == null) {
            task.run();
            return;
        }
        try {
            Method method = api.getClass().getMethod("runExclusiveTransmit", Runnable.class);
            method.invoke(api, task);
        } catch (Exception e) {
            Log.w(TAG, "runExclusiveTransmit fallback", e);
            task.run();
        }
    }

    private void fireHomeassistantEvent(String service, Map<String, String> data) {
        Object api = hostApi;
        if (api == null) {
            return;
        }
        try {
            Method method = api.getClass().getMethod("fireHomeassistantEvent", String.class, Map.class);
            method.invoke(api, service, data);
        } catch (Exception e) {
            Log.w(TAG, "fireHomeassistantEvent failed", e);
        }
    }

    private static String readString(Map<String, Object> args, String key) {
        if (args == null) {
            return "";
        }
        Object value = args.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private static float readFloat(Map<String, Object> args, String key, float fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (value instanceof String) {
            try {
                return Float.parseFloat((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<?> readList(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof List) {
            return (List<?>) value;
        }
        return null;
    }

    private static List<String> readStringList(Map<String, Object> args, String key) {
        List<?> raw = readList(args, key);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object item : raw) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private void setLastAdvertiseError(String error) {
        lastAdvertiseError = error != null ? error : "";
        notifyStateListeners("last_advertise_error", lastAdvertiseError);
    }

    private void clearLastAdvertiseError() {
        if (lastAdvertiseError == null || lastAdvertiseError.isEmpty()) {
            return;
        }
        lastAdvertiseError = "";
        notifyStateListeners("last_advertise_error", lastAdvertiseError);
    }

    private void notifyProxyReadyChanged() {
        notifyStateListeners("proxy_ready", isProxyReady());
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        pushCurrentState(entityId, callback);
        return true;
    }

    private void pushCurrentState(String entityId, Object callback) {
        if ("proxy_ready".equals(entityId)) {
            notifySingleListener(callback, isProxyReady());
        } else if ("last_advertise_error".equals(entityId)) {
            notifySingleListener(callback, getLastAdvertiseError());
        } else if ("ble_adv_proxy_name".equals(entityId)) {
            notifySingleListener(callback, getAdapterName());
        } else if ("capability_report".equals(entityId)) {
            notifySingleListener(callback, getCapabilityReport());
        } else if ("raw_transport".equals(entityId)) {
            notifySingleListener(callback, getRawTransport());
        } else if ("privileged_shell".equals(entityId)) {
            notifySingleListener(callback, getPrivilegedShell());
        } else if ("fidelity_mode".equals(entityId)) {
            notifySingleListener(callback, getFidelityMode());
        }
    }

    private void notifyStateListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            return;
        }
        for (Object callback : listeners) {
            notifySingleListener(callback, value);
        }
    }

    private void notifySingleListener(Object callback, Object value) {
        try {
            Method method;
            try {
                method = callback.getClass().getMethod("onStateChanged", Object.class);
            } catch (NoSuchMethodException e) {
                method = callback.getClass().getMethod("onState", Object.class);
            }
            method.invoke(callback, value);
        } catch (Exception e) {
            Log.w(TAG, "State callback failed", e);
        }
    }

    private static final class TransmitJob {
        final byte[] raw;
        final int perBurstMs;
        final int repeat;

        TransmitJob(byte[] raw, int perBurstMs, int repeat) {
            this.raw = raw;
            this.perBurstMs = perBurstMs;
            this.repeat = Math.max(1, repeat);
        }
    }
}
