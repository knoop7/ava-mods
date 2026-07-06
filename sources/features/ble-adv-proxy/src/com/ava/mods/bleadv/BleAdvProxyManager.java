package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int MAX_TRANSMIT_QUEUE = 6;

    private final Context context;
    private final BleAdvDedupCache dedupCache = new BleAdvDedupCache();
    private final ConcurrentLinkedQueue<TransmitJob> transmitQueue = new ConcurrentLinkedQueue<>();
    private final Object transmitQueueLock = new Object();
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
    private volatile boolean rawHciConfigInitialized = false;
    private final BleAdvPermissionHelper permissionHelper;
    private final RawHciAdvertiser rawHciAdvertiser;
    private final BleAdvCapabilityProbe capabilityProbe;
    private final BleAdvLeScanner leScanner;
    private final BleAdvExclusiveSession exclusiveSession;
    private volatile BleAdvCapabilityProbe.Report lastCapabilityReport;
    private final AtomicBoolean probeRunning = new AtomicBoolean(false);
    private volatile String adapterNameOverride = "";
    private volatile String deviceName = "";
    private volatile Object hostApi;
    private volatile boolean haServicesReady = false;
    private volatile boolean setupDone = false;

    private BleAdvProxyManager(Context context) {
        this.context = context.getApplicationContext();
        this.permissionHelper = new BleAdvPermissionHelper(this.context);
        this.rawHciAdvertiser = new RawHciAdvertiser(this.context, permissionHelper);
        this.capabilityProbe = new BleAdvCapabilityProbe(this.context, permissionHelper, rawHciAdvertiser);
        this.leScanner = new BleAdvLeScanner(this.context, permissionHelper);
        this.exclusiveSession = new BleAdvExclusiveSession(leScanner, new Runnable() {
            @Override
            public void run() {
                if (rawHciEnabled) {
                    rawHciAdvertiser.prepControllerForAdv();
                }
            }
        });
        this.leScanner.setResultHandler(new BleAdvLeScanner.ResultHandler() {
            @Override
            public void onScanResult(String mac, int rssi, byte[] raw) {
                deliverScanResult(mac, rssi, raw);
            }
        });
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
                if (!featureEnabled) {
                    stopStandaloneScan();
                } else if (isStandalone() && haServicesReady) {
                    startStandaloneScan();
                }
                break;
            case "use_max_tx_power":
                useMaxTxPower = parseBoolean(value, true);
                break;
            case "force_broadcast":
                // Inverted switch: ON (default) forces standard BLE broadcast and disables the
                // advanced raw HCI/MGMT path. OFF re-enables the root-only 1:1 raw path.
                boolean nextRawHci = !parseBoolean(value, true);
                boolean userToggled = rawHciConfigInitialized && nextRawHci != rawHciEnabled;
                rawHciEnabled = nextRawHci;
                rawHciConfigInitialized = true;
                if (userToggled) {
                    scheduleCapabilityProbe();
                }
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
        BleAdvManifestReader.invalidateCache();
        if (isStandalone()) {
            if (rawHciEnabled) {
                permissionHelper.ensurePrivilegedAccess();
            }
            if (!permissionHelper.hasRequiredBlePermissions()) {
                Log.w(TAG, "standalone: missing BLE runtime permissions — enable mod in store to grant");
            }
        } else {
            setHostPresenceAdvertisingSuppressed(true);
        }
        notifyStateListeners("ble_adv_proxy_name", getAdapterName());
        Log.i(TAG, "ESPHome connected (adapter=" + getAdapterName(ctx)
                + " standalone=" + isStandalone() + ")");
    }

    public void onEspHomeDisconnected(Context ctx) {
        haServicesReady = false;
        setupDone = false;
        stopStandaloneScan();
        hostApi = null;
        transmitQueue.clear();
    }

    public void onHomeassistantServicesSubscribed(Context ctx) {
        haServicesReady = true;
        notifyStateListeners("ble_adv_proxy_name", getAdapterName());
        if (isStandalone() && featureEnabled) {
            startStandaloneScan();
        }
        Log.d(TAG, "HA homeassistant services subscribed (standalone=" + isStandalone() + ")");
    }

    /**
     * Legacy integrated path — host forwards proxy scan results here.
     * Standalone mod uses {@link BleAdvLeScanner} instead.
     */
    public void onScanResult(Context ctx, String mac, int rssi, byte[] raw) {
        if (isStandalone()) {
            return;
        }
        deliverScanResult(mac, rssi, raw);
    }

    private void deliverScanResult(String mac, int rssi, byte[] raw) {
        if (!featureEnabled || !haServicesReady || !setupDone) {
            return;
        }
        if (exclusiveSession.isActive()) {
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
        if (!isStandalone()) {
            setHostPresenceAdvertisingSuppressed(false);
        }
        stopStandaloneScan();
        transmitQueue.clear();
        hostApi = null;
        haServicesReady = false;
        setupDone = false;
    }

    /** setup_svc_v0(ignored_duration, ignored_cids, ignored_macs) */
    private void handleSetup(Map<String, Object> args) {
        float ignoredDuration = readFloat(args, "ignored_duration", 20_000f);
        List<?> ignoredCids = readList(args, "ignored_cids");
        List<?> ignoredMacs = readList(args, "ignored_macs");
        dedupCache.clearDupes();
        dedupCache.configureSetup(ignoredDuration, ignoredCids, ignoredMacs);
        setupDone = true;
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

        offerTransmitJob(new TransmitJob(rawBytes, perBurstMs, repeat));
        drainTransmitQueue();
    }

    private void offerTransmitJob(TransmitJob job) {
        synchronized (transmitQueueLock) {
            for (TransmitJob existing : transmitQueue) {
                if (Arrays.equals(existing.raw, job.raw) && existing.perBurstMs == job.perBurstMs) {
                    Log.d(TAG, "coalesce: skip duplicate queued adv len=" + job.raw.length);
                    return;
                }
            }
            while (transmitQueue.size() >= MAX_TRANSMIT_QUEUE) {
                transmitQueue.poll();
                Log.w(TAG, "transmit queue full, dropped oldest job");
            }
            transmitQueue.offer(job);
        }
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
                        final TransmitJob job;
                        synchronized (transmitQueueLock) {
                            job = transmitQueue.poll();
                        }
                        if (job == null) {
                            break;
                        }
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
                                            return;
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
     * Sends one advertising burst.
     *
     * <p>Primary path is the standard {@link BluetoothLeAdvertiser} broadcast, which works on
     * every phone with no root/Shizuku and no delay. It reproduces every AD data structure
     * byte-for-byte; only the 3-byte codec Flags prefix is controlled by Android, and ble_adv
     * fan/lamp devices match on the self-delimiting data structure, not Flags.
     *
     * <p>Raw HCI/MGMT (true 1:1 including Flags) is an opt-in advanced path for rooted devices
     * with a real kernel HCI transport. It is off by default and only attempted when explicitly
     * enabled and actually available; otherwise we broadcast directly.
     */
    private String transmitOneBurst(BleAdvTransmitter transmitter, byte[] raw, int burstMs) {
        if (rawHciEnabled && rawHciAdvertiser.isAvailable()) {
            pauseForRawAdvertise();
            String rawErr = rawHciAdvertiser.transmit(-1, "auto", burstMs, raw);
            if (rawErr == null) {
                Log.i(TAG, "TX ok raw HCI/MGMT (1:1) len=" + raw.length + " burst=" + burstMs + "ms");
                return "";
            }
            Log.w(TAG, "raw HCI unavailable (" + rawErr + "), broadcasting via AdvertiseData");
        }
        String err = transmitter.transmitBlocking(raw, burstMs);
        if ((err == null || err.isEmpty())) {
            Log.i(TAG, "TX ok BLE broadcast burst=" + burstMs + "ms");
            return "";
        }
        Log.w(TAG, "BLE broadcast failed (" + err + "), retrying once");
        try {
            Thread.sleep(120L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err;
        }
        String retryErr = transmitter.transmitBlocking(raw, burstMs);
        if (retryErr == null || retryErr.isEmpty()) {
            Log.i(TAG, "TX ok BLE broadcast (retry) burst=" + burstMs + "ms");
            return "";
        }
        Log.w(TAG, "BLE broadcast failed after retry: " + retryErr);
        return retryErr;
    }

    private void scheduleCapabilityProbe() {
        if (!featureEnabled || haServicesReady || drainRunning.get() || !transmitQueue.isEmpty()) {
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
                            pauseForRawAdvertise();
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
                            Log.d(TAG, "capability probe: " + report.summary);
                        }
                    });
                } finally {
                    probeRunning.set(false);
                }
            }
        });
    }

    private void runExclusive(Runnable task) {
        if (isStandalone()) {
            exclusiveSession.runExclusive(task, !rawHciEnabled);
            return;
        }
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

    /** Pause competing BLE activity before raw MGMT inject. */
    private void pauseForRawAdvertise() {
        if (!isStandalone()) {
            setHostPresenceAdvertisingSuppressed(true);
            Object api = hostApi;
            if (api == null) {
                return;
            }
            try {
                api.getClass().getMethod("awaitRawAdvertiseSettle").invoke(api);
            } catch (NoSuchMethodException ignored) {
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                Log.w(TAG, "awaitRawAdvertiseSettle failed", e);
            }
        }
    }

    private boolean isStandalone() {
        return BleAdvManifestReader.isStandalone(context);
    }

    private void startStandaloneScan() {
        if (!isStandalone() || !featureEnabled || !haServicesReady) {
            return;
        }
        if (rawHciEnabled) {
            permissionHelper.ensurePrivilegedAccess();
            rawHciAdvertiser.warmup();
        }
        leScanner.start();
    }

    private void stopStandaloneScan() {
        leScanner.stop();
    }

    private void setHostPresenceAdvertisingSuppressed(boolean suppressed) {
        Object api = hostApi;
        if (api == null) {
            return;
        }
        try {
            api.getClass()
                    .getMethod("setPresenceAdvertisingSuppressed", boolean.class)
                    .invoke(api, suppressed);
            Log.d(TAG, "host presence suppress=" + suppressed);
        } catch (Exception e) {
            Log.w(TAG, "setPresenceAdvertisingSuppressed(" + suppressed + ") failed", e);
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
        if (error != null && !error.isEmpty()) {
            Log.w(TAG, "advertise error: " + error);
        }
    }

    private void clearLastAdvertiseError() {
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
        if ("ble_adv_proxy_name".equals(entityId)) {
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
        final int perBurstMs;
        final int repeat;

        TransmitJob(byte[] raw, int perBurstMs, int repeat) {
            this.raw = raw;
            this.perBurstMs = perBurstMs;
            this.repeat = Math.max(1, repeat);
        }
    }
}
