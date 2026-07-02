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

/** Sends one raw ADV burst inside an exclusive BLE window provided by Ava core. */
final class BleAdvTransmitter {
    private static final String TAG = "BleAdvTransmitter";
    private static final long START_TIMEOUT_MS = 1500L;

    private final Context context;
    private final boolean useMaxTxPower;

    BleAdvTransmitter(Context context, boolean useMaxTxPower) {
        this.context = context.getApplicationContext();
        this.useMaxTxPower = useMaxTxPower;
    }

    @SuppressLint("MissingPermission")
    boolean transmitBlocking(byte[] raw, int durationMs) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter unavailable");
            return false;
        }
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser unavailable");
            return false;
        }

        final AdvertiseData data;
        try {
            data = RawAdvParser.toAdvertiseData(raw);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse raw adv", e);
            return false;
        }

        int txPower = useMaxTxPower
                ? AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                : AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(txPower)
                .setConnectable(false)
                .setTimeout(0)
                .build();

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
                Log.w(TAG, "Advertise start failed: " + errorCode);
                started.countDown();
            }
        };

        try {
            advertiser.startAdvertising(settings, data, callback);
            if (!started.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Advertise start timed out");
                advertiser.stopAdvertising(callback);
                return false;
            }
            if (!success.get()) {
                return false;
            }
            int sleepMs = Math.max(32, durationMs);
            Thread.sleep(sleepMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            try {
                advertiser.stopAdvertising(callback);
            } catch (Exception e) {
                Log.w(TAG, "stopAdvertising failed", e);
            }
        }
    }
}
