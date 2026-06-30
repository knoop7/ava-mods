package com.ava.mods.hasttengine;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class WyomingWire {
    private static final String VERSION = "1.0.0";

    private WyomingWire() {
    }

    static WyomingEvent readEvent(BufferedInputStream input) throws Exception {
        String line = readLine(input);
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        JSONObject header = new JSONObject(line);
        String type = header.optString("type", "");
        JSONObject data = header.optJSONObject("data");
        if (data == null) {
            data = new JSONObject();
        }

        int dataLength = header.optInt("data_length", 0);
        if (dataLength > 0) {
            byte[] extra = readExact(input, dataLength);
            JSONObject extraJson = new JSONObject(new String(extra, StandardCharsets.UTF_8));
            for (java.util.Iterator<String> keys = extraJson.keys(); keys.hasNext(); ) {
                String key = keys.next();
                data.put(key, extraJson.get(key));
            }
        }

        byte[] payload = null;
        int payloadLength = header.optInt("payload_length", 0);
        if (payloadLength > 0) {
            payload = readExact(input, payloadLength);
        }

        return new WyomingEvent(type, data, payload);
    }

    static void writeEvent(BufferedOutputStream output, WyomingEvent event) throws Exception {
        JSONObject header = new JSONObject();
        header.put("type", event.type);
        header.put("version", VERSION);

        byte[] dataBytes = new byte[0];
        if (event.data.length() > 0) {
            dataBytes = event.data.toString().getBytes(StandardCharsets.UTF_8);
            header.put("data_length", dataBytes.length);
        }
        if (event.payload != null && event.payload.length > 0) {
            header.put("payload_length", event.payload.length);
        }

        output.write(header.toString().getBytes(StandardCharsets.UTF_8));
        output.write('\n');
        if (dataBytes.length > 0) {
            output.write(dataBytes);
        }
        if (event.payload != null && event.payload.length > 0) {
            output.write(event.payload);
        }
        output.flush();
    }

    static WyomingEvent infoEvent() {
        try {
            return new WyomingEvent("info", WyomingInfoBuilder.build(), null);
        } catch (Exception e) {
            return new WyomingEvent("info", new JSONObject(), null);
        }
    }

    static WyomingEvent transcriptEvent(String text, String emotion, String audioEvent, String language) {
        try {
            JSONObject data = new JSONObject();
            data.put("text", text == null ? "" : text);
            if (language != null && !language.isEmpty()) {
                data.put("language", language);
            }
            JSONObject context = new JSONObject();
            if (emotion != null && !emotion.isEmpty()) {
                context.put("emotion", emotion);
            }
            if (audioEvent != null && !audioEvent.isEmpty()) {
                context.put("audio_event", audioEvent);
            }
            if (context.length() > 0) {
                data.put("context", context);
            }
            return new WyomingEvent("transcript", data, null);
        } catch (Exception e) {
            return new WyomingEvent("transcript", new JSONObject(), null);
        }
    }

    private static String readLine(BufferedInputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8.name());
            }
            if (value == '\n') {
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
            buffer.write(value);
        }
    }

    private static byte[] readExact(InputStream input, int length) throws Exception {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IllegalStateException("Unexpected end of stream");
            }
            offset += read;
        }
        return buffer;
    }
}
