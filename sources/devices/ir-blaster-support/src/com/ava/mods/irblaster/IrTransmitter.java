package com.ava.mods.irblaster;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.ConsumerIrManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends raw IR timings (as delivered by Home Assistant's infrared platform) through
 * the device's Consumer IR emitter.
 *
 * ESPHome timings: microseconds, alternating mark(+)/space(-), starting with a mark.
 * Android {@link ConsumerIrManager#transmit(int, int[])}: microseconds, alternating
 * on/off starting with on. So the mapping is simply the absolute value of each timing.
 *
 * Transmission runs on a dedicated single thread so the API socket thread stays free
 * to answer Home Assistant keep-alive pings while an IR burst is in flight.
 */
final class IrTransmitter {

    private static final String TAG = "IrBlaster";
    private static final int DEFAULT_CARRIER_HZ = 38000;
    private static final int MAX_TIMINGS = 1024;
    private static final String PERM_TRANSMIT_IR = "android.permission.TRANSMIT_IR";
    /** IConsumerIrService.transmit(String packageName, int carrierFrequency, int[] pattern). */
    private static final int TXN_TRANSMIT = 2;

    private final Context context;
    private final PrivilegedShell shell;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * When the host app does not hold TRANSMIT_IR, the in-process
     * {@link ConsumerIrManager#transmit} throws SecurityException. Instead of grabbing root,
     * we route the transmit through the host's Shizuku shell ("service call consumer_ir"): the
     * shell uid already holds TRANSMIT_IR, so no extra privilege is requested. Cached so we
     * don't retry the failing fast path on every burst.
     */
    private volatile boolean preferPrivileged;
    private volatile String lastPath = "unknown";

    private volatile String lastError;
    private volatile long lastTransmitAt;
    private volatile long transmitCount;

    IrTransmitter(Context context) {
        this.context = context.getApplicationContext();
        this.shell = new PrivilegedShell(this.context);
        this.preferPrivileged = !hostHasTransmitPermission();
    }

    void enqueue(final int carrierFrequency, final int[] timings, final int repeatCount) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                transmit(carrierFrequency, timings, repeatCount);
            }
        });
    }

    private void transmit(int carrierFrequency, int[] timings, int repeatCount) {
        if (timings == null || timings.length == 0) {
            lastError = "empty timings";
            return;
        }
        if (timings.length > MAX_TIMINGS) {
            lastError = "timings too long (" + timings.length + ")";
            return;
        }

        int freq = carrierFrequency > 0 ? carrierFrequency : DEFAULT_CARRIER_HZ;
        int[] pattern = toPattern(timings);
        int repeats = repeatCount <= 0 ? 1 : repeatCount;

        // Fast path: in-process ConsumerIrManager. Only usable when the host app declares
        // TRANSMIT_IR, so we skip it once we know it will fail.
        if (!preferPrivileged) {
            ConsumerIrManager ir = null;
            try {
                ir = (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
            } catch (Exception ignored) {
            }
            if (ir != null && safeHasEmitter(ir)) {
                try {
                    for (int i = 0; i < repeats; i++) {
                        ir.transmit(freq, pattern);
                    }
                    markSuccess(freq, pattern.length, repeats, "app");
                    return;
                } catch (SecurityException se) {
                    preferPrivileged = true;
                    Log.w(TAG, "host app lacks TRANSMIT_IR; switching to Shizuku service call");
                } catch (Exception e) {
                    lastError = "transmit failed: " + e.getMessage();
                    Log.e(TAG, lastError, e);
                    return;
                }
            } else {
                // No in-process emitter handle; the Shizuku shell can still reach the HAL service.
                preferPrivileged = true;
            }
        }

        // Privileged path (no root): the host's Shizuku shell uid already holds TRANSMIT_IR,
        // so it can drive the consumer_ir binder even though the host app cannot.
        if (transmitViaShizuku(freq, pattern, repeats)) {
            markSuccess(freq, pattern.length, repeats, "shizuku");
            return;
        }
        if (lastError == null) {
            lastError = "no usable IR path (grant Shizuku, or rebuild Ava with TRANSMIT_IR)";
        }
        Log.e(TAG, lastError);
    }

    /**
     * Transmits via {@code service call consumer_ir} run through the host's Shizuku shell. The
     * binder method is {@code transmit(String packageName, int carrierFrequency, int[] pattern)};
     * an int[] is marshalled as its length followed by each element (Parcel.writeIntArray).
     */
    private boolean transmitViaShizuku(int freq, int[] pattern, int repeats) {
        if (!shell.isShizukuGranted()) {
            lastError = "TRANSMIT_IR missing in host app; grant Shizuku "
                    + "or rebuild Ava declaring android.permission.TRANSMIT_IR";
            return false;
        }
        StringBuilder cmd = new StringBuilder(64 + pattern.length * 12);
        cmd.append("service call consumer_ir ").append(TXN_TRANSMIT)
                .append(" s16 ").append(context.getPackageName())
                .append(" i32 ").append(freq)
                .append(" i32 ").append(pattern.length);
        for (int v : pattern) {
            cmd.append(" i32 ").append(v);
        }
        String command = cmd.toString();
        for (int i = 0; i < repeats; i++) {
            int code = shell.shizukuExec(command);
            if (code != 0) {
                lastError = "Shizuku transmit failed (exit " + code + ")";
                return false;
            }
        }
        return true;
    }

    private void markSuccess(int freq, int slots, int repeats, String path) {
        lastError = null;
        lastPath = path;
        lastTransmitAt = System.currentTimeMillis();
        transmitCount++;
        Log.i(TAG, "transmitted IR via " + path + ": freq=" + freq + "Hz slots=" + slots
                + " repeats=" + repeats);
    }

    private boolean hostHasTransmitPermission() {
        try {
            return context.getPackageManager()
                    .checkPermission(PERM_TRANSMIT_IR, context.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Absolute value of each timing; a leading space (negative first) is dropped. */
    private static int[] toPattern(int[] timings) {
        int start = timings[0] < 0 ? 1 : 0;
        int[] pattern = new int[timings.length - start];
        for (int i = start; i < timings.length; i++) {
            int v = timings[i];
            pattern[i - start] = v < 0 ? -v : v;
        }
        return pattern;
    }

    private static boolean safeHasEmitter(ConsumerIrManager ir) {
        try {
            return ir.hasIrEmitter();
        } catch (Exception e) {
            return false;
        }
    }

    String getLastError() {
        return lastError;
    }

    String getLastPath() {
        return lastPath;
    }

    long getLastTransmitAt() {
        return lastTransmitAt;
    }

    long getTransmitCount() {
        return transmitCount;
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
