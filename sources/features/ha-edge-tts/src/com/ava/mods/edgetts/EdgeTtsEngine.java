package com.ava.mods.edgetts;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

final class EdgeTtsEngine {
    private static final String TAG = "EdgeTtsEngine";
    private static final String WS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR_VERSION = "143";
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_FULL_VERSION;
    private static final long WIN_EPOCH = 11644473600L;

    interface SynthesisListener {
        void onAudio(byte[] mp3Data);
        void onError(String message);
    }

    private EdgeTtsEngine() {
    }

    static byte[] synthesize(String text, String voice, String rate, String volume, String pitch)
            throws Exception {
        String ssml = buildSsml(text, voice, rate, volume, pitch);
        String url = buildConnectUrl();

        URI uri = new URI(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;

        Socket rawSocket = null;
        javax.net.ssl.SSLSocket sslSocket = null;
        BufferedInputStream input = null;
        BufferedOutputStream output = null;

        try {
            javax.net.ssl.SSLSocketFactory factory =
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            sslSocket = (javax.net.ssl.SSLSocket) factory.createSocket(host, port);
            sslSocket.setTcpNoDelay(true);
            sslSocket.setSoTimeout(30000);

            input = new BufferedInputStream(sslSocket.getInputStream());
            output = new BufferedOutputStream(sslSocket.getOutputStream());

            performHandshake(output, input, uri);

            String requestId = UUID.randomUUID().toString().replace("-", "");
            sendTtsRequest(output, requestId, ssml);

            ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
            boolean gotAudio = false;

            while (true) {
                int[] opAndPayload = readWsFrame(input);
                if (opAndPayload == null) {
                    break;
                }
                int opcode = opAndPayload[0];
                int payloadLen = opAndPayload[1];
                byte[] payload = new byte[payloadLen];
                for (int i = 0; i < payloadLen; i++) {
                    payload[i] = (byte) opAndPayload[2 + i];
                }

                if (opcode == 0x1) {
                    String textPayload = new String(payload, "UTF-8");
                    if (textPayload.contains("Path:turn.end")) {
                        break;
                    }
                } else if (opcode == 0x2) {
                    String header = parseBinaryHeader(payload);
                    if (header.contains("Path:audio")) {
                        byte[] audioData = extractAudioPayload(payload);
                        if (audioData != null && audioData.length > 0) {
                            audioBuffer.write(audioData);
                            gotAudio = true;
                        }
                    }
                }
            }

            if (!gotAudio) {
                throw new IllegalStateException("No audio received from Edge TTS");
            }

            return audioBuffer.toByteArray();
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            closeQuietly(sslSocket);
            closeQuietly(rawSocket);
        }
    }

    private static String buildSsml(String text, String voice, String rate, String volume, String pitch) {
        StringBuilder sb = new StringBuilder();
        sb.append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>");
        sb.append("<voice name='").append(voice).append("'>");
        sb.append("<prosody pitch='").append(pitch).append("' rate='").append(rate)
                .append("' volume='").append(volume).append("'>");
        sb.append(escapeXml(text));
        sb.append("</prosody>");
        sb.append("</voice>");
        sb.append("</speak>");
        return sb.toString();
    }

    private static String buildConnectUrl() {
        return WS_URL
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + generateSecMsGec()
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION
                + "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateSecMsGec() {
        long unixTime = System.currentTimeMillis() / 1000L;
        long ticks = unixTime + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 10000000L;
        String strToHash = ticks + TRUSTED_CLIENT_TOKEN;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(strToHash.getBytes("ASCII"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateMuid() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    private static void performHandshake(BufferedOutputStream output, BufferedInputStream input,
                                         URI uri) throws Exception {
        String key = generateWsKey();
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(uri.getRawPath())
                .append(uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "")
                .append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n");
        req.append("Pragma: no-cache\r\n");
        req.append("Cache-Control: no-cache\r\n");
        req.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR_VERSION + ".0.0.0 Safari/537.36"
                + " Edg/" + CHROMIUM_MAJOR_VERSION + ".0.0.0\r\n");
        req.append("Accept-Encoding: gzip, deflate, br, zstd\r\n");
        req.append("Accept-Language: en-US,en;q=0.9\r\n");
        req.append("Cookie: muid=").append(generateMuid()).append(";\r\n");
        req.append("\r\n");

        output.write(req.toString().getBytes("UTF-8"));
        output.flush();

        String statusLine = readHttpLine(input);
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("WebSocket handshake failed: " + statusLine);
        }

        String line;
        while ((line = readHttpLine(input)) != null) {
            if (line.isEmpty()) {
                break;
            }
        }
    }

    private static void sendTtsRequest(BufferedOutputStream output, String requestId, String ssml)
            throws Exception {
        StringBuilder msg = new StringBuilder();
        msg.append("X-Timestamp:").append(iso8601Now()).append("\r\n");
        msg.append("Content-Type:application/json; charset=utf-8\r\n");
        msg.append("Path:speech.config\r\n");
        msg.append("\r\n");
        msg.append("{\"context\":{\"synthesis\":{\"audio\":{\"metadataOptions\":"
                + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}");
        sendWsTextFrame(output, msg.toString());

        StringBuilder msg2 = new StringBuilder();
        msg2.append("X-Timestamp:").append(iso8601Now()).append("\r\n");
        msg2.append("X-RequestId:").append(requestId).append("\r\n");
        msg2.append("Content-Type:application/ssml+xml\r\n");
        msg2.append("Path:ssml\r\n");
        msg2.append("\r\n");
        msg2.append(ssml);
        sendWsTextFrame(output, msg2.toString());
    }

    private static int[] readWsFrame(BufferedInputStream input) throws Exception {
        int b0 = input.read();
        if (b0 < 0) return null;
        int b1 = input.read();
        if (b1 < 0) return null;

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        int payloadLen = b1 & 0x7F;

        if (payloadLen == 126) {
            int high = input.read();
            int low = input.read();
            payloadLen = ((high & 0xFF) << 8) | (low & 0xFF);
        } else if (payloadLen == 127) {
            long extended = 0;
            for (int i = 0; i < 8; i++) {
                int b = input.read();
                extended = (extended << 8) | (b & 0xFF);
            }
            payloadLen = (int) extended;
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            for (int i = 0; i < 4; i++) {
                maskKey[i] = (byte) input.read();
            }
        }

        byte[] payloadData = new byte[payloadLen];
        int offset = 0;
        while (offset < payloadLen) {
            int read = input.read(payloadData, offset, payloadLen - offset);
            if (read < 0) throw new IOException("Unexpected end of WebSocket frame");
            offset += read;
        }

        if (masked) {
            for (int i = 0; i < payloadLen; i++) {
                payloadData[i] ^= maskKey[i % 4];
            }
        }

        int[] result = new int[2 + payloadLen];
        result[0] = opcode;
        result[1] = payloadLen;
        for (int i = 0; i < payloadLen; i++) {
            result[2 + i] = payloadData[i] & 0xFF;
        }
        return result;
    }

    private static String parseBinaryHeader(byte[] payload) {
        int headerEnd = -1;
        for (int i = 0; i < payload.length - 1; i++) {
            if (payload[i] == '\r' && payload[i + 1] == '\n' && i + 3 < payload.length
                    && payload[i + 2] == '\r' && payload[i + 3] == '\n') {
                headerEnd = i;
                break;
            }
        }
        if (headerEnd < 0) {
            return "";
        }
        return new String(payload, 0, headerEnd, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] extractAudioPayload(byte[] payload) {
        int headerEnd = -1;
        for (int i = 0; i < payload.length - 3; i++) {
            if (payload[i] == '\r' && payload[i + 1] == '\n'
                    && payload[i + 2] == '\r' && payload[i + 3] == '\n') {
                headerEnd = i + 4;
                break;
            }
        }
        if (headerEnd < 0 || headerEnd >= payload.length) {
            return null;
        }
        byte[] audio = new byte[payload.length - headerEnd];
        System.arraycopy(payload, headerEnd, audio, 0, audio.length);
        return audio;
    }

    private static void sendWsTextFrame(BufferedOutputStream output, String message) throws Exception {
        byte[] data = message.getBytes("UTF-8");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        frame.write(0x81);

        int len = data.length;
        if (len <= 125) {
            frame.write(0x80 | len);
        } else if (len <= 65535) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((len >> (i * 8)) & 0xFF);
            }
        }

        byte[] mask = generateMask();
        for (int i = 0; i < 4; i++) {
            frame.write(mask[i]);
        }

        for (int i = 0; i < data.length; i++) {
            frame.write(data[i] ^ mask[i % 4]);
        }

        output.write(frame.toByteArray());
        output.flush();
    }

    private static byte[] generateMask() {
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        return mask;
    }

    private static String generateWsKey() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private static String readHttpLine(BufferedInputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b < 0) {
                return buffer.size() == 0 ? null : buffer.toString("UTF-8");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        return buffer.toString("UTF-8");
    }

    private static String iso8601Now() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date());
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
