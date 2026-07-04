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

/**
 * 1:1 port of esphome-ble_adv_proxy for Ava (Android transmit via AdvertiseData).
 *
 * <p>Services: setup_svc_v0, adv_svc (legacy), adv_svc_v1
 * <p>Event: esphome.ble_adv.raw_adv with keys raw + orig
 */
public class BleAdvProxyManager {
    private static final String TAG = "BleAdvProxyManager";
    private static volatile BleAdvProxyManager instance;

    private static final String EVENT_RAW_ADV = "esphome.ble_adv.raw_adv";
    private static final String KEY_RAW = "raw";
    private static final String KEY_ORIGIN = "orig";

    private static final int REPEAT_NB = 3;
    private static final int MIN_VIABLE_PACKET_LEN = 5;

    // Android's LOW_LATENCY advertise interval is ~100ms, so a single short per-burst window
    // (ha-ble-adv sends e.g. 20-30ms x repeat) may emit zero ADV events. Collapse the whole
    // repeat x duration budget into one continuous window, clamped to keep the exclusive BLE
    // window (which pauses proxy scan / presence) bounded.
    private static final int MIN_ADV_WINDOW_MS = 120;
    private static final int MAX_ADV_WINDOW_MS = 1800;

    private final Context context;
    private final BleAdvDedupCache dedupCache = new BleAdvDedupCache();
    private final ConcurrentLinkedQueue<TransmitJob> transmitQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
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
    private final RawHciAdvertiser rawHciAdvertiser;
    private volatile String adapterNameOverride = "";
    private volatile String deviceName = "";
    private volatile Object hostApi;
    private volatile boolean haServicesReady = false;
    private volatile boolean setupDone = false;
    private volatile String lastAdvertiseError = "";

    private BleAdvProxyManager(Context context) {
        this.context = context.getApplicationContext();
        this.rawHciAdvertiser = new RawHciAdvertiser(this.context);
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
                useMaxTxPower = parseBoolean(value, false);
                break;
            case "enable_raw_hci":
                rawHciEnabled = parseBoolean(value, false);
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
        Log.d(TAG, "HA homeassistant services subscribed");
    }

    public void onScanResult(Context ctx, String mac, int rssi, byte[] raw) {
        if (!featureEnabled || !haServicesReady || !setupDone) {
            return;
        }
        if (raw == null || raw.length < MIN_VIABLE_PACKET_LEN) {
            return;
        }
        // Android's ScanRecord.getBytes() hands back a fixed-size legacy buffer (advertising data,
        // plus scan-response for active scans) zero-padded to its maximum length — typically 62
        // bytes. The ESP32 ble_adv_proxy operates on the real PDU, so trim the trailing AD padding
        // to the true payload length first. Without this every packet is 62 bytes and the >31 guard
        // silently drops the entire scan stream (raw_adv never fires, ha-ble-adv listen stays None).
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
        Map<String, String> payload = new HashMap<>();
        payload.put(KEY_RAW, RawAdvParser.toHex(adv));
        payload.put(KEY_ORIGIN, mac != null ? mac.toUpperCase() : "");
        fireHomeassistantEvent(EVENT_RAW_ADV, payload);
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

    private void handleAdvertiseV1Internal(
            String raw,
            float durationMs,
            int repeat,
            List<String> ignoredAdvs,
            float ignDurationMs
    ) {
        setupDone = true;
        notifyProxyReadyChanged();
        int perBurst = Math.max(1, (int) durationMs);
        long intIgnDuration = (long) ignDurationMs;

        // Same packet is repeated by ha-ble-adv to fight radio collisions; on Android we advertise
        // continuously for the aggregate budget instead, which is equivalent and guarantees output.
        long budget = (long) perBurst * Math.max(1, repeat);
        int window = (int) Math.max(MIN_ADV_WINDOW_MS, Math.min(MAX_ADV_WINDOW_MS, budget));
        Log.d(TAG, "send adv - " + raw + ", perBurst " + perBurst + "ms x repeat " + repeat
                + " -> window " + window + "ms");

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

        transmitQueue.offer(new TransmitJob(rawBytes, window));
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
                        runExclusive(new Runnable() {
                            @Override
                            public void run() {
                                // B-layer: true 1:1 raw injection (Flags included) via root HCI/MGMT.
                                if (rawHciEnabled && rawHciAdvertiser.isAvailable()) {
                                    String rawErr = rawHciAdvertiser.transmit(0, "auto", job.durationMs, job.raw);
                                    if (rawErr == null) {
                                        clearLastAdvertiseError();
                                        return;
                                    }
                                    Log.w(TAG, "raw HCI unavailable, falling back to AdvertiseData: " + rawErr);
                                }
                                // A-layer: best-effort AdvertiseData (byte-exact except Flags).
                                String error = transmitter.transmitBlocking(job.raw, job.durationMs);
                                if (error != null && !error.isEmpty()) {
                                    setLastAdvertiseError(error);
                                } else {
                                    clearLastAdvertiseError();
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
        final int durationMs;

        TransmitJob(byte[] raw, int durationMs) {
            this.raw = raw;
            this.durationMs = durationMs;
        }
    }
}
