package com.ava.mods.bleadv;

import android.bluetooth.le.AdvertiseData;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.UUID;

/**
 * Maps a raw BLE advertising PDU (as delivered by ha-ble-adv / esphome-ble_adv_proxy)
 * into an Android {@link AdvertiseData}.
 *
 * <p>ha-ble-adv sends the FULL advertising payload, e.g.
 * <pre>02 01 1A | 1B 03 &lt;26 bytes&gt;</pre>
 * where the first structure is a codec specific Flags AD and the second is the real
 * data carried inside an AD structure whose type may be 0x03 / 0x05 / 0x07 (UUID lists
 * used as data containers), 0x16 (service data) or 0xFF (manufacturer data).
 *
 * <p>Android's public advertising API cannot emit an arbitrary raw PDU:
 * <ul>
 *   <li>The Flags AD (and its non standard value) is controlled by the framework and is
 *       dropped here — most ble_adv devices only match on the data structure, not Flags.</li>
 *   <li>Type 0x03 / 0x05 / 0x07 are reproduced byte for byte by splitting the payload back
 *       into 16 / 32 / 128 bit service UUIDs and re-adding them in order, which makes the
 *       framework serialise the identical AD structure.</li>
 * </ul>
 * Byte level fidelity therefore holds for every data structure; only the Flags prefix is lost.
 * True 1:1 (including Flags) requires raw HCI / MGMT injection (root only).
 */
final class RawAdvParser {
    private static final int MAX_PACKET_LEN = 31;

    private static final int AD_FLAGS = 0x01;
    private static final int AD_INCOMPLETE_16 = 0x02;
    private static final int AD_COMPLETE_16 = 0x03;
    private static final int AD_INCOMPLETE_32 = 0x04;
    private static final int AD_COMPLETE_32 = 0x05;
    private static final int AD_INCOMPLETE_128 = 0x06;
    private static final int AD_COMPLETE_128 = 0x07;
    private static final int AD_SHORT_NAME = 0x08;
    private static final int AD_COMPLETE_NAME = 0x09;
    private static final int AD_SERVICE_DATA_16 = 0x16;
    private static final int AD_SERVICE_DATA_32 = 0x20;
    private static final int AD_SERVICE_DATA_128 = 0x21;
    private static final int AD_MANUFACTURER = 0xFF;

    private RawAdvParser() {}

    /** Result of mapping a raw PDU, carrying fidelity metadata for diagnostics. */
    static final class MappedAdv {
        final AdvertiseData data;
        /** True when every non-Flags AD structure was reproduced byte for byte. */
        final boolean fullyMapped;
        /** True when a Flags AD was present in the raw but could not be reproduced. */
        final boolean flagsDropped;
        final String note;

        MappedAdv(AdvertiseData data, boolean fullyMapped, boolean flagsDropped, String note) {
            this.data = data;
            this.fullyMapped = fullyMapped;
            this.flagsDropped = flagsDropped;
            this.note = note;
        }
    }

    static AdvertiseData toAdvertiseData(byte[] raw) {
        return toMappedAdv(raw).data;
    }

    static MappedAdv toMappedAdv(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("empty raw adv");
        }

        byte[] packet = raw.length > MAX_PACKET_LEN ? Arrays.copyOf(raw, MAX_PACKET_LEN) : raw;

        AdvertiseData.Builder builder = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false);

        boolean addedAny = false;
        boolean fullyMapped = true;
        boolean flagsDropped = false;
        StringBuilder note = new StringBuilder();

        int i = 0;
        while (i < packet.length) {
            int fieldLen = packet[i] & 0xFF;
            if (fieldLen == 0) {
                break;
            }
            if (i + 1 >= packet.length) {
                fullyMapped = false;
                note.append("truncated_header;");
                break;
            }
            int type = packet[i + 1] & 0xFF;
            int dataStart = i + 2;
            int dataLen = fieldLen - 1;
            int dataEnd = dataStart + dataLen;
            boolean overrun = dataEnd > packet.length;
            if (overrun) {
                dataEnd = packet.length;
            }
            byte[] data = dataStart <= dataEnd
                    ? Arrays.copyOfRange(packet, dataStart, dataEnd)
                    : new byte[0];

            switch (type) {
                case AD_FLAGS:
                    flagsDropped = true;
                    break;
                case AD_MANUFACTURER:
                    if (data.length >= 2) {
                        int manufacturerId = le16(data, 0);
                        builder.addManufacturerData(manufacturerId, Arrays.copyOfRange(data, 2, data.length));
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("bad_manufacturer;");
                    }
                    break;
                case AD_SERVICE_DATA_16:
                    if (data.length >= 2) {
                        builder.addServiceData(new ParcelUuid(uuidFrom16(le16(data, 0))),
                                Arrays.copyOfRange(data, 2, data.length));
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("bad_service_data16;");
                    }
                    break;
                case AD_SERVICE_DATA_32:
                    if (data.length >= 4) {
                        builder.addServiceData(new ParcelUuid(uuidFrom32(le32(data, 0))),
                                Arrays.copyOfRange(data, 4, data.length));
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("bad_service_data32;");
                    }
                    break;
                case AD_SERVICE_DATA_128:
                    if (data.length >= 16) {
                        builder.addServiceData(new ParcelUuid(uuidFrom128Le(data, 0)),
                                Arrays.copyOfRange(data, 16, data.length));
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("bad_service_data128;");
                    }
                    break;
                case AD_INCOMPLETE_16:
                case AD_COMPLETE_16:
                    if (data.length > 0 && (data.length % 2) == 0) {
                        for (int p = 0; p < data.length; p += 2) {
                            builder.addServiceUuid(new ParcelUuid(uuidFrom16(le16(data, p))));
                        }
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("odd_uuid16;");
                    }
                    break;
                case AD_INCOMPLETE_32:
                case AD_COMPLETE_32:
                    if (data.length > 0 && (data.length % 4) == 0) {
                        for (int p = 0; p < data.length; p += 4) {
                            builder.addServiceUuid(new ParcelUuid(uuidFrom32(le32(data, p))));
                        }
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("bad_uuid32;");
                    }
                    break;
                case AD_INCOMPLETE_128:
                case AD_COMPLETE_128:
                    if (data.length > 0 && (data.length % 16) == 0) {
                        for (int p = 0; p < data.length; p += 16) {
                            builder.addServiceUuid(new ParcelUuid(uuidFrom128Le(data, p)));
                        }
                        addedAny = true;
                    } else {
                        fullyMapped = false;
                        note.append("bad_uuid128;");
                    }
                    break;
                case AD_SHORT_NAME:
                case AD_COMPLETE_NAME:
                    // Android appends its own adapter name, never the codec name -> cannot reproduce.
                    fullyMapped = false;
                    note.append("name_unmappable;");
                    break;
                default:
                    fullyMapped = false;
                    note.append("unknown_type_0x").append(Integer.toHexString(type)).append(';');
                    break;
            }

            if (overrun) {
                fullyMapped = false;
                note.append("overrun;");
                break;
            }
            i += fieldLen + 1;
        }

        if (!addedAny) {
            throw new IllegalArgumentException("no_mappable_ad_structure");
        }

        return new MappedAdv(builder.build(), fullyMapped, flagsDropped, note.toString());
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

    private static int le16(byte[] data, int offset) {
        return ((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF);
    }

    private static long le32(byte[] data, int offset) {
        return ((long) (data[offset + 3] & 0xFF) << 24)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | (data[offset] & 0xFF);
    }

    private static UUID uuidFrom16(int uuid16) {
        return UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", uuid16 & 0xFFFF));
    }

    private static UUID uuidFrom32(long uuid32) {
        return UUID.fromString(String.format("%08X-0000-1000-8000-00805F9B34FB", uuid32 & 0xFFFFFFFFL));
    }

    /**
     * Builds a 128-bit UUID from 16 AD bytes stored little-endian (as on air).
     * Android re-emits {@code addServiceUuid(uuid128)} as the reversed (little-endian) bytes,
     * so reversing here yields the original on-air layout.
     */
    private static UUID uuidFrom128Le(byte[] data, int offset) {
        long msb = 0L;
        long lsb = 0L;
        for (int k = 0; k < 8; k++) {
            msb = (msb << 8) | (data[offset + 15 - k] & 0xFFL);
        }
        for (int k = 0; k < 8; k++) {
            lsb = (lsb << 8) | (data[offset + 7 - k] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}
