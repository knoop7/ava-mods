package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private volatile boolean featureEnabled = true;
    private volatile boolean useMaxTxPower = false;
    private volatile String adapterNameOverride = "";
    private volatile String deviceName = "";
    private volatile Object hostApi;
    private volatile boolean haServicesReady = false;
    private volatile boolean setupDone = false;

    private BleAdvProxyManager(Context context) {
        this.context = context.getApplicationContext();
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

    public String getAdapterName(Context ctx) {
        if (adapterNameOverride != null && !adapterNameOverride.trim().isEmpty()) {
            return adapterNameOverride.trim();
        }
        return deviceName;
    }

    public void applyConfig(String key, String value) {
        if (key == null) {
            return;
        }
        switch (key) {
            case "enabled":
                featureEnabled = parseBoolean(value, true);
                break;
            case "use_max_tx_power":
                useMaxTxPower = parseBoolean(value, false);
                break;
            case "adapter_name":
                adapterNameOverride = value != null ? value : "";
                break;
            default:
                break;
        }
    }

    public void onEspHomeConnected(Context ctx, String deviceName, Object hostApi) {
        this.deviceName = deviceName != null ? deviceName : "";
        this.hostApi = hostApi;
        Log.i(TAG, "ESPHome connected (adapter=" + getAdapterName(ctx) + ")");
    }

    public void onEspHomeDisconnected(Context ctx) {
        haServicesReady = false;
        setupDone = false;
        hostApi = null;
        transmitQueue.clear();
    }

    public void onHomeassistantServicesSubscribed(Context ctx) {
        haServicesReady = true;
        Log.d(TAG, "HA homeassistant services subscribed");
    }

    public void onScanResult(Context ctx, String mac, int rssi, byte[] raw) {
        if (!featureEnabled || !haServicesReady || !setupDone) {
            return;
        }
        if (raw == null || raw.length < MIN_VIABLE_PACKET_LEN || raw.length > 31) {
            return;
        }
        if (dedupCache.isMacIgnored(mac) || dedupCache.isCompanyIdIgnored(raw)) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + dedupCache.getDupeIgnoreDurationMs();
        if (!dedupCache.checkAddDupePacket(raw, expiresAt)) {
            return;
        }
        Map<String, String> payload = new HashMap<>();
        payload.put(KEY_RAW, RawAdvParser.toHex(raw));
        payload.put(KEY_ORIGIN, mac != null ? mac.toUpperCase() : "");
        fireHomeassistantEvent(EVENT_RAW_ADV, payload);
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

    private void handleAdvertiseV1Internal(
            String raw,
            float durationMs,
            int repeat,
            List<String> ignoredAdvs,
            float ignDurationMs
    ) {
        setupDone = true;
        int intDuration = Math.max(32, (int) durationMs);
        long intIgnDuration = (long) ignDurationMs;
        Log.d(TAG, "send adv - " + raw + ", duration " + intDuration + "ms, repeat: " + repeat);

        byte[] rawBytes;
        try {
            rawBytes = RawAdvParser.fromHex(raw);
        } catch (Exception e) {
            Log.w(TAG, "Invalid raw hex: " + raw, e);
            return;
        }

        for (String ignoredAdv : ignoredAdvs) {
            dedupCache.ignoreHexEcho(ignoredAdv, intIgnDuration);
        }

        for (int i = 0; i < repeat; i++) {
            transmitQueue.offer(new TransmitJob(rawBytes, intDuration));
        }
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
                                transmitter.transmitBlocking(job.raw, job.durationMs);
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

    private static final class TransmitJob {
        final byte[] raw;
        final int durationMs;

        TransmitJob(byte[] raw, int durationMs) {
            this.raw = raw;
            this.durationMs = durationMs;
        }
    }
}
