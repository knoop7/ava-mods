package com.ava.mods.bleadv;

import android.bluetooth.le.AdvertiseData;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.UUID;

/**
 * Parses raw BLE advertisement payloads (max 31 bytes) for Android transmission.
 * Receive path forwards the same bytes HA expects from esphome-ble_adv_proxy.
 */
final class RawAdvParser {
    private static final int MAX_PACKET_LEN = 31;

    private RawAdvParser() {}

    static AdvertiseData toAdvertiseData(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("empty raw adv");
        }

        byte[] packet = raw.length > MAX_PACKET_LEN
                ? Arrays.copyOf(raw, MAX_PACKET_LEN)
                : raw;

        AdvertiseData.Builder builder = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false);

        int offset = 0;
        boolean added = false;
        Integer fallbackManufacturerId = null;
        byte[] fallbackManufacturerPayload = null;

        while (offset < packet.length) {
            int fieldLen = packet[offset] & 0xFF;
            if (fieldLen == 0) {
                break;
            }
            if (offset + fieldLen >= packet.length) {
                break;
            }
            int type = packet[offset + 1] & 0xFF;
            int dataLen = fieldLen - 1;
            int dataStart = offset + 2;
            if (dataStart + dataLen > packet.length) {
                break;
            }

            switch (type) {
                case 0xFF:
                    if (dataLen >= 2) {
                        int manufacturerId = ((packet[dataStart + 1] & 0xFF) << 8) | (packet[dataStart] & 0xFF);
                        byte[] payload = new byte[dataLen - 2];
                        System.arraycopy(packet, dataStart + 2, payload, 0, payload.length);
                        builder.addManufacturerData(manufacturerId, payload);
                        added = true;
                        fallbackManufacturerId = manufacturerId;
                        fallbackManufacturerPayload = payload;
                    }
                    break;
                case 0x16:
                    if (dataLen >= 2) {
                        int uuid16 = ((packet[dataStart + 1] & 0xFF) << 8) | (packet[dataStart] & 0xFF);
                        byte[] servicePayload = new byte[dataLen - 2];
                        System.arraycopy(packet, dataStart + 2, servicePayload, 0, servicePayload.length);
                        builder.addServiceData(new ParcelUuid(uuidFrom16(uuid16)), servicePayload);
                        added = true;
                    }
                    break;
                case 0x09:
                case 0x08:
                    if (dataLen > 0) {
                        builder.setIncludeDeviceName(true);
                        added = true;
                    }
                    break;
                default:
                    break;
            }
            offset += fieldLen + 1;
        }

        if (!added && packet.length >= 3) {
            Integer cid = BleAdvDedupCache.extractCompanyId(packet);
            if (cid != null) {
                byte[] payload = new byte[Math.max(0, packet.length - 4)];
                if (payload.length > 0) {
                    System.arraycopy(packet, 4, payload, 0, payload.length);
                }
                builder.addManufacturerData(cid, payload);
                added = true;
            }
        }

        if (!added && fallbackManufacturerId != null && fallbackManufacturerPayload != null) {
            builder.addManufacturerData(fallbackManufacturerId, fallbackManufacturerPayload);
            added = true;
        }

        if (!added) {
            throw new IllegalArgumentException("unable to map raw adv to AdvertiseData");
        }
        return builder.build();
    }

    static String toHex(byte[] raw) {
        if (raw == null) {
            return "";
        }
        int len = Math.min(raw.length, MAX_PACKET_LEN);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", raw[i] & 0xFF));
        }
        return sb.toString();
    }

    static byte[] fromHex(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String cleaned = hex.replace(":", "").replace(" ", "").trim();
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        if ((cleaned.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        int maxBytes = Math.min(cleaned.length() / 2, MAX_PACKET_LEN);
        byte[] out = new byte[maxBytes];
        for (int i = 0; i < maxBytes; i++) {
            int idx = i * 2;
            out[i] = (byte) Integer.parseInt(cleaned.substring(idx, idx + 2), 16);
        }
        return out;
    }

    private static UUID uuidFrom16(int uuid16) {
        return UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", uuid16 & 0xFFFF));
    }
}
