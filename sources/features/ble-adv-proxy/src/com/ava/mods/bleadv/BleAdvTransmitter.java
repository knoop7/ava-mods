package com.ava.mods.bleadv;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends raw ADV bursts inside an exclusive BLE window provided by Ava core.
 *
 * <p>Maps to esphome-ble_adv_proxy's {@code esp_ble_gap_config_adv_data_raw} +
 * {@code esp_ble_gap_start_advertising} cycle. Android {@code ADVERTISE_MODE_LOW_LATENCY}
 * emits roughly every 100&nbsp;ms, so per-burst windows below that are clamped.
 */
final class BleAdvTransmitter {
    private static final String TAG = "BleAdvTransmitter";
    private static final long START_TIMEOUT_MS = 1500L;
    /** ESP32 MIN_ADV = 0x20 (20&nbsp;ms); Android needs a full LOW_LATENCY period. */
    static final int ANDROID_MIN_BURST_MS = 100;

    private final Context context;
    private final boolean useMaxTxPower;

    BleAdvTransmitter(Context context, boolean useMaxTxPower) {
        this.context = context.getApplicationContext();
        this.useMaxTxPower = useMaxTxPower;
    }

    private String lastError = "";

    String getLastError() {
        return lastError != null ? lastError : "";
    }

    /** Serialized AD length once the Flags structure (3 bytes) is stripped. */
    private static int rawLenWithoutFlags(byte[] raw) {
        int len = 0;
        int i = 0;
        int n = Math.min(raw.length, 31);
        while (i < n) {
            int fieldLen = raw[i] & 0xFF;
            if (fieldLen == 0 || i + 1 >= n || i + 1 + fieldLen > n) {
                break;
            }
            int type = raw[i + 1] & 0xFF;
            if (type != 0x01) {
                len += 1 + fieldLen;
            }
            i += 1 + fieldLen;
        }
        return len;
    }

    @SuppressLint("MissingPermission")
    String transmitBlocking(byte[] raw, int durationMs) {
        lastError = "";
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            lastError = "bluetooth_unavailable";
            Log.w(TAG, "Bluetooth adapter unavailable");
            return lastError;
        }
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            lastError = "advertiser_unavailable";
            Log.w(TAG, "BluetoothLeAdvertiser unavailable");
            return lastError;
        }

        final AdvertiseData data;
        final boolean restoreFlags;
        try {
            RawAdvParser.MappedAdv mapped = RawAdvParser.toMappedAdv(raw);
            data = mapped.data;
            // The public API cannot emit a custom Flags AD, but the stack prepends its own
            // Flags AD (02 01 1A) to connectable advertisements. Many ble_adv receivers parse
            // the payload at a fixed offset after Flags, so restoring the 3-byte prefix (even
            // with a different flags value) re-aligns the PDU. Only possible when the payload
            // plus the 3-byte Flags still fits in the 31-byte legacy limit.
            restoreFlags = mapped.flagsDropped && rawLenWithoutFlags(raw) + 3 <= 31;
            if (!mapped.fullyMapped) {
                Log.w(TAG, "Raw adv not byte-exact (needs raw HCI for 1:1): " + mapped.note);
            } else if (mapped.flagsDropped) {
                Log.d(TAG, "Raw adv mapped byte-exact; Flags AD "
                        + (restoreFlags ? "restored via connectable adv" : "dropped (no room)"));
            } else {
                Log.d(TAG, "Raw adv mapped byte-exact");
            }
        } catch (Exception e) {
            lastError = "invalid_raw_adv";
            Log.w(TAG, "Failed to parse raw adv", e);
            return lastError;
        }

        int txPower = useMaxTxPower
                ? AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                : AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;

        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(txPower)
                .setConnectable(restoreFlags)
                .setTimeout(0);
        if (restoreFlags && android.os.Build.VERSION.SDK_INT >= 34) {
            settingsBuilder.setDiscoverable(true);
        }
        AdvertiseSettings settings = settingsBuilder.build();

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                success.set(true);
                started.countDown();
            }

            @Override
            public void onStartFailure(int errorCode) {
                lastError = "advertise_start_failed:" + errorCode;
                Log.w(TAG, "Advertise start failed: " + errorCode);
                started.countDown();
            }
        };

        try {
            advertiser.startAdvertising(settings, data, callback);
            if (!started.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                lastError = "advertise_start_timeout";
                Log.w(TAG, "Advertise start timed out");
                advertiser.stopAdvertising(callback);
                return lastError;
            }
            if (!success.get()) {
                if (lastError == null || lastError.isEmpty()) {
                    lastError = "advertise_start_failed";
                }
                return lastError;
            }
            int sleepMs = Math.max(ANDROID_MIN_BURST_MS, durationMs);
            Thread.sleep(sleepMs);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "interrupted";
            return lastError;
        } finally {
            try {
                advertiser.stopAdvertising(callback);
            } catch (Exception e) {
                Log.w(TAG, "stopAdvertising failed", e);
            }
        }
    }
}
