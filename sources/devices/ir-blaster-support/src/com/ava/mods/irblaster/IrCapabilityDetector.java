package com.ava.mods.irblaster;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.ConsumerIrManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects available infrared emitters:
 *   1. Built-in Consumer IR blaster (ConsumerIrManager).
 *   2. USB IR dongles (known VID/PID) and kernel lirc / rc nodes.
 *
 * Detection is side-effect free and safe to run repeatedly.
 */
final class IrCapabilityDetector {

    private static final String TAG = "IrBlaster";

    /** Well-known USB infrared transmitter/transceiver vendor:product pairs. */
    private static final int[][] KNOWN_USB_IR = {
            {0x20A0, 0x0006}, // Flirc USB
            {0x1781, 0x0938}, // IguanaWorks USB IR Transceiver
            {0x04D8, 0xFD08}, // Microchip / DIY IR blasters
            {0x0BDA, 0x5850}, // Realtek IR
            {0x147A, 0xE03E}, // Formosa eHome IR
            {0x0471, 0x0815}, // Philips eHome IR
            {0x112A, 0x0001}, // RedRat3
            {0x0FE9, 0x9010}, // DVICO IR
    };

    static final class Result {
        boolean consumerIrPresent;
        int[] carrierFrequencies;   // flattened [min0,max0,min1,max1,...]
        final List<String> usbDevices = new ArrayList<>();
        final List<String> lircNodes = new ArrayList<>();

        boolean anyEmitter() {
            return consumerIrPresent || !usbDevices.isEmpty() || !lircNodes.isEmpty();
        }

        String primarySource() {
            if (consumerIrPresent) {
                return "consumerir";
            }
            if (!usbDevices.isEmpty() || !lircNodes.isEmpty()) {
                return "usb";
            }
            return "none";
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(consumerIrPresent ? "ConsumerIR: yes" : "ConsumerIR: no");
            if (carrierFrequencies != null && carrierFrequencies.length >= 2) {
                sb.append(" (").append(carrierFrequencies[0]).append('-')
                        .append(carrierFrequencies[1]).append("Hz)");
            }
            if (!usbDevices.isEmpty()) {
                sb.append("; USB: ").append(usbDevices.size());
            }
            if (!lircNodes.isEmpty()) {
                sb.append("; lirc: ").append(lircNodes.size());
            }
            return sb.toString();
        }
    }

    private final Context context;
    private final PrivilegedShell shell;

    IrCapabilityDetector(Context context, PrivilegedShell shell) {
        this.context = context.getApplicationContext();
        this.shell = shell;
    }

    Result detect() {
        Result result = new Result();
        detectConsumerIr(result);
        detectUsb(result);
        detectLircNodes(result);
        Log.i(TAG, "IR capability: " + result.summary());
        return result;
    }

    private void detectConsumerIr(Result result) {
        boolean feature = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR);
        try {
            ConsumerIrManager ir =
                    (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
            if (ir != null && ir.hasIrEmitter()) {
                result.consumerIrPresent = true;
                result.carrierFrequencies = readCarrierFrequencies(ir);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "ConsumerIR probe failed: " + e.getMessage());
        }
        result.consumerIrPresent = feature;
    }

    private static int[] readCarrierFrequencies(ConsumerIrManager ir) {
        try {
            ConsumerIrManager.CarrierFrequencyRange[] ranges = ir.getCarrierFrequencies();
            if (ranges == null || ranges.length == 0) {
                return null;
            }
            int[] flat = new int[ranges.length * 2];
            for (int i = 0; i < ranges.length; i++) {
                flat[i * 2] = ranges[i].getMinFrequency();
                flat[i * 2 + 1] = ranges[i].getMaxFrequency();
            }
            return flat;
        } catch (Exception e) {
            return null;
        }
    }

    private void detectUsb(Result result) {
        try {
            UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (usb == null) {
                return;
            }
            Map<String, UsbDevice> devices = usb.getDeviceList();
            if (devices == null) {
                return;
            }
            for (UsbDevice device : devices.values()) {
                if (isKnownIrDevice(device)) {
                    result.usbDevices.add(describeUsb(device));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "USB probe failed: " + e.getMessage());
        }
    }

    private static boolean isKnownIrDevice(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();
        for (int[] pair : KNOWN_USB_IR) {
            if (pair[0] == vid && pair[1] == pid) {
                return true;
            }
        }
        String name = device.getProductName();
        if (name != null) {
            String lower = name.toLowerCase();
            return lower.contains("infrared") || lower.contains(" ir ")
                    || lower.contains("flirc") || lower.contains("remote");
        }
        return false;
    }

    private static String describeUsb(UsbDevice device) {
        String name = device.getProductName();
        String hex = String.format("%04x:%04x", device.getVendorId(), device.getProductId());
        return name != null ? name + " (" + hex + ")" : hex;
    }

    private void detectLircNodes(Result result) {
        for (int i = 0; i < 4; i++) {
            File node = new File("/dev/lirc" + i);
            if (node.exists()) {
                result.lircNodes.add(node.getPath());
            }
        }
        String listing = shell.captureOutput("ls -d /dev/lirc* /sys/class/rc/rc* 2>/dev/null");
        if (listing == null) {
            return;
        }
        for (String line : listing.split("\\n")) {
            String path = line.trim();
            if (path.isEmpty() || result.lircNodes.contains(path)) {
                continue;
            }
            if (path.startsWith("/dev/lirc") || path.startsWith("/sys/class/rc/")) {
                result.lircNodes.add(path);
            }
        }
    }
}
