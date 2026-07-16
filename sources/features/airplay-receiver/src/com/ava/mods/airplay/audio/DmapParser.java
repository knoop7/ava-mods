package com.ava.mods.airplay.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DmapParser {

    private DmapParser() {}

    private static final Set<String> INT_TAGS;
    private static final Set<String> CONTAINER_TAGS;

    static {
        Set<String> ints = new HashSet<>();
        ints.add("astm");
        ints.add("astn");
        ints.add("asdk");
        ints.add("asts");
        ints.add("miid");
        ints.add("mcti");
        ints.add("mper");
        ints.add("asai");
        ints.add("asri");
        ints.add("asci");
        ints.add("asgi");
        INT_TAGS = Collections.unmodifiableSet(ints);

        Set<String> ctr = new HashSet<>();
        ctr.add("mlit");
        ctr.add("mcon");
        ctr.add("mlcl");
        CONTAINER_TAGS = Collections.unmodifiableSet(ctr);
    }

    public static Map<String, Object> parse(byte[] data) {
        Map<String, Object> result = new LinkedHashMap<>();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 8) {
            byte[] tagBytes = new byte[4];
            buf.get(tagBytes);
            String tag = new String(tagBytes, StandardCharsets.US_ASCII);
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) break;
            byte[] payload = new byte[len];
            buf.get(payload);
            Object value;
            if (CONTAINER_TAGS.contains(tag)) {
                value = parse(payload);
            } else if (INT_TAGS.contains(tag)) {
                value = readInt(payload);
            } else {
                value = new String(payload, StandardCharsets.UTF_8);
            }
            result.put(tag, value);
        }
        return result;
    }

    private static long readInt(byte[] b) {
        long v = 0L;
        for (byte by : b) {
            v = (v << 8) | ((long) by & 0xFF);
        }
        return v;
    }
}
