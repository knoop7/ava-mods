package com.ava.mods.bleadv;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Mirrors esphome-ble_adv_proxy dupe_packets_ / ign_macs_ / ign_cids_ behaviour.
 *
 * Packet matching uses prefix equality (existing.len &lt;= new.len) like the ESP32 code.
 * Entry expiry timestamps are absolute millis, not durations.
 */
final class BleAdvDedupCache {
    private static final int DEFAULT_IGNORE_MS = 20_000;
    private static final int MAX_PACKET_LEN = 31;
    private static final int MAX_ENTRIES = 512;

    private final LinkedList<Entry> entries = new LinkedList<>();

    private long dupeIgnoreDurationMs = DEFAULT_IGNORE_MS;
    private final List<String> ignoredMacs = new LinkedList<>();
    private final List<Integer> ignoredCompanyIds = new LinkedList<>();

    void clearDupes() {
        entries.clear();
    }

    /**
     * setup_svc_v0 — durations from HA are milliseconds (same as ESP32 millis() offset).
     */
    void configureSetup(float ignoredDurationMs, List<?> ignoredCids, List<?> ignoredMacs) {
        if (ignoredDurationMs > 0f) {
            dupeIgnoreDurationMs = (long) ignoredDurationMs;
        }
        ignoredCompanyIds.clear();
        if (ignoredCids != null) {
            for (Object value : ignoredCids) {
                if (value instanceof Number) {
                    ignoredCompanyIds.add(((Number) value).intValue() & 0xFFFF);
                }
            }
        }
        this.ignoredMacs.clear();
        if (ignoredMacs != null) {
            for (Object value : ignoredMacs) {
                if (value != null) {
                    this.ignoredMacs.add(normalizeMac(String.valueOf(value)));
                }
            }
        }
    }

    long getDupeIgnoreDurationMs() {
        return dupeIgnoreDurationMs;
    }

    boolean isMacIgnored(String mac) {
        if (mac == null || mac.isEmpty()) {
            return true;
        }
        String normalized = normalizeMac(mac);
        for (String ignored : ignoredMacs) {
            if (normalized.equals(ignored)) {
                return true;
            }
        }
        return false;
    }

    boolean isCompanyIdIgnored(byte[] raw) {
        Integer cid = extractCompanyId(raw);
        return cid != null && ignoredCompanyIds.contains(cid);
    }

    /**
     * @return true when packet is new and should be forwarded to HA.
     */
    boolean checkAddDupePacket(byte[] raw, long expiresAtMs) {
        if (raw == null || raw.length < 5) {
            return false;
        }
        long now = System.currentTimeMillis();
        purgeExpired(now);

        byte[] packet = raw.length > MAX_PACKET_LEN
                ? Arrays.copyOf(raw, MAX_PACKET_LEN)
                : raw;

        for (Entry entry : entries) {
            if (entry.raw.length <= packet.length && startsWith(packet, entry.raw)) {
                if (expiresAtMs > 0L) {
                    entry.expiresAtMs = expiresAtMs;
                }
                return false;
            }
        }

        entries.addLast(new Entry(Arrays.copyOf(packet, packet.length), expiresAtMs));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        return true;
    }

    void ignoreHexEcho(String hex, long ignoreDurationMs) {
        if (hex == null || hex.isEmpty()) {
            return;
        }
        try {
            byte[] raw = RawAdvParser.fromHex(hex);
            long expiresAt = System.currentTimeMillis() + Math.max(ignoreDurationMs, 500L);
            checkAddDupePacket(raw, expiresAt);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void purgeExpired(long now) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.expiresAtMs > 0L && entry.expiresAtMs < now) {
                it.remove();
            }
        }
    }

    private static boolean startsWith(byte[] haystack, byte[] prefix) {
        if (haystack.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (haystack[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** ESP32: cid = (ble_adv[3] << 8) + ble_adv[2] */
    static Integer extractCompanyId(byte[] raw) {
        if (raw == null || raw.length < 4) {
            return null;
        }
        return ((raw[3] & 0xFF) << 8) | (raw[2] & 0xFF);
    }

    private static String normalizeMac(String mac) {
        return mac.replace('-', ':').trim().toUpperCase();
    }

    private static final class Entry {
        final byte[] raw;
        long expiresAtMs;

        Entry(byte[] raw, long expiresAtMs) {
            this.raw = raw;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
