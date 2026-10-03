package com.ava.mods.respeaker;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The control channel to the reSpeaker XVF3800.
 *
 * Every parameter travels as a control transfer on endpoint 0, addressed to
 * the DEVICE rather than an interface. So no interface needs to be claimed:
 * the vendor-specific interface 3 ("reSpeaker Control") stays untouched and
 * Ava keeps the three audio interfaces undisturbed. This coexistence was
 * verified on a phone with Ava recording at the same time.
 *
 * Protocol (from python_control/xvf_host.py):
 *   Read:   bmRequestType 0xC0, bRequest 0, wValue 0x80|cmdid, wIndex resid
 *   Write:  bmRequestType 0x40, bRequest 0, wValue cmdid,      wIndex resid
 *   Response byte 0 is the status: 0 = ok, 64 = try again.
 *   Payload from byte 1, little-endian.
 */
final class XvfLink {

    private static final String TAG = "ReSpeaker";

    /** Seeed reSpeaker XVF3800 4-Mic Array. */
    private static final int VENDOR_ID = 0x2886;
    private static final int PRODUCT_ID = 0x001A;

    private static final String ACTION_PERMISSION = "com.ava.mods.respeaker.USB_PERMISSION";

    private static final int STATUS_OK = 0;
    private static final int STATUS_RETRY = 64;
    private static final int MAX_RETRIES = 100;
    private static final int TIMEOUT_MS = 2000;

    static final int STATE_NO_DEVICE = 0;
    static final int STATE_AWAITING_PERMISSION = 1;
    static final int STATE_CONNECTED = 2;
    static final int STATE_ERROR = 3;

    private final Context appContext;
    private final UsbManager usbManager;

    private UsbDeviceConnection connection;
    private UsbDevice device;
    private volatile int state = STATE_NO_DEVICE;
    private volatile String detail = "not connected yet";
    private volatile int connectionCount;
    private boolean receiverRegistered;
    private boolean permissionRequested;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_PERMISSION.equals(intent.getAction())) {
                return;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, "USB permission " + (granted ? "granted" : "denied"));
            permissionRequested = false;
            if (!granted) {
                state = STATE_AWAITING_PERMISSION;
                detail = "permission denied";
            }
        }
    };

    XvfLink(Context context) {
        appContext = context.getApplicationContext();
        usbManager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
    }

    int getState() {
        return state;
    }

    String getDetail() {
        return detail;
    }

    /** Counts every successfully opened connection; a new one needs checking. */
    int getConnectionCount() {
        return connectionCount;
    }

    /**
     * Opens the connection if possible.
     *
     * Without permission it asks once and returns false. The dialog is a
     * single tap, and the permission lasts as long as the device stays
     * plugged in. There is no way around the tap: Ava's manifest has no USB
     * device filter, and a mod cannot add one.
     */
    synchronized boolean ensureOpen() {
        if (connection != null) {
            return true;
        }
        if (usbManager == null) {
            state = STATE_ERROR;
            detail = "no USB service";
            return false;
        }

        device = findDevice();
        if (device == null) {
            state = STATE_NO_DEVICE;
            detail = "reSpeaker not found";
            return false;
        }

        if (!usbManager.hasPermission(device)) {
            state = STATE_AWAITING_PERMISSION;
            detail = "waiting for USB permission";
            requestPermission();
            return false;
        }

        UsbDeviceConnection opened = usbManager.openDevice(device);
        if (opened == null) {
            state = STATE_ERROR;
            detail = "openDevice failed";
            return false;
        }
        connection = opened;

        try {
            int[] version = readInts(XvfParam.VERSION);
            state = STATE_CONNECTED;
            connectionCount++;
            detail = "connected, firmware " + version[0] + "." + version[1] + "." + version[2];
            Log.i(TAG, detail);
            return true;
        } catch (Exception e) {
            // An open device that does not answer is worse than none at all:
            // it would block every following request.
            Log.w(TAG, "Control channel does not answer", e);
            close();
            state = STATE_ERROR;
            detail = "no answer to VERSION";
            return false;
        }
    }

    synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        device = null;
        if (state == STATE_CONNECTED) {
            state = STATE_NO_DEVICE;
            detail = "disconnected";
        }
    }

    /** Unregisters the receiver. Without this the mod leaks when disabled. */
    synchronized void release() {
        close();
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(permissionReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
    }

    private UsbDevice findDevice() {
        for (UsbDevice candidate : usbManager.getDeviceList().values()) {
            if (candidate.getVendorId() == VENDOR_ID && candidate.getProductId() == PRODUCT_ID) {
                return candidate;
            }
        }
        return null;
    }

    private void requestPermission() {
        if (permissionRequested) {
            return;
        }
        registerReceiverOnce();

        Intent intent = new Intent(ACTION_PERMISSION);
        // Android 14 rejects implicit intents in a PendingIntent; the own
        // package makes it explicit.
        intent.setPackage(appContext.getPackageName());

        // The system adds EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED, so the
        // intent must be mutable. With FLAG_IMMUTABLE the grant never arrives.
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? PendingIntent.FLAG_MUTABLE
            : 0;
        PendingIntent pending = PendingIntent.getBroadcast(appContext, 0, intent, flags);

        permissionRequested = true;
        usbManager.requestPermission(device, pending);
    }

    private void registerReceiverOnce() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(permissionReceiver, filter);
        }
        receiverRegistered = true;
    }

    // ---- Reading and writing ------------------------------------------------

    private synchronized byte[] read(XvfParam param) throws XvfException {
        if (connection == null) {
            throw new XvfException(param.name + ": not connected");
        }
        byte[] buffer = new byte[param.readLength()];
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            int n = connection.controlTransfer(
                0xC0, 0, 0x80 | param.cmdid, param.resid, buffer, buffer.length, TIMEOUT_MS);
            if (n < 0) {
                throw new XvfException(param.name + ": control transfer failed");
            }
            int status = buffer[0] & 0xFF;
            if (status == STATUS_OK) {
                byte[] payload = new byte[buffer.length - 1];
                System.arraycopy(buffer, 1, payload, 0, payload.length);
                return payload;
            }
            if (status != STATUS_RETRY) {
                throw new XvfException(param.name + ": status " + status);
            }
            // Without a pause the loop hammers the servicer up to a hundred
            // times back to back, several times per second from the poll.
            try {
                Thread.sleep(0, 500000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new XvfException(param.name + ": interrupted");
            }
        }
        throw new XvfException(param.name + ": " + MAX_RETRIES + " attempts failed");
    }

    private synchronized void write(XvfParam param, byte[] payload) throws XvfException {
        if (connection == null) {
            throw new XvfException(param.name + ": not connected");
        }
        int n = connection.controlTransfer(
            0x40, 0, param.cmdid, param.resid, payload, payload.length, TIMEOUT_MS);
        if (n < 0) {
            throw new XvfException(param.name + ": write failed");
        }
    }

    float[] readFloats(XvfParam param) throws XvfException {
        ByteBuffer buffer = ByteBuffer.wrap(read(param)).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[param.count];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    int[] readInts(XvfParam param) throws XvfException {
        byte[] payload = read(param);
        int[] values = new int[param.count];
        if (param.type == XvfParam.T_UINT8) {
            for (int i = 0; i < values.length; i++) {
                values[i] = payload[i] & 0xFF;
            }
            return values;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getInt();
        }
        return values;
    }

    void writeFloats(XvfParam param, float[] values) throws XvfException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        write(param, buffer.array());
    }

    /** Writes several uint8 values, such as category and source of a channel. */
    void writeBytes(XvfParam param, int[] values) throws XvfException {
        byte[] payload = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            payload[i] = (byte) (values[i] & 0xFF);
        }
        write(param, payload);
    }

    /** Writes several int32 values, such as the twelve colours of the LED ring. */
    void writeInts(XvfParam param, int[] values) throws XvfException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putInt(value);
        }
        write(param, buffer.array());
    }

    void writeInt(XvfParam param, int value) throws XvfException {
        if (param.type == XvfParam.T_UINT8) {
            write(param, new byte[] { (byte) (value & 0xFF) });
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value);
        write(param, buffer.array());
    }

    /** Control channel error. Checked on purpose so no caller can overlook it. */
    static final class XvfException extends Exception {
        XvfException(String message) {
            super(message);
        }
    }
}
