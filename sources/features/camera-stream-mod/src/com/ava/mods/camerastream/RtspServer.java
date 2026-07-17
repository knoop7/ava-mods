package com.ava.mods.camerastream;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal RTSP server (TCP interleaved RTP) for H.264 — suitable for go2rtc / ffmpeg / Frigate.
 */
public final class RtspServer implements H264Encoder.NalListener {

    private static final String TAG = "RtspServer";

    private final StreamConfig config;
    private final H264Encoder encoder;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger rtpSeq = new AtomicInteger(0);
    private final int rtpSsrc = (int) (System.nanoTime() & 0x7fffffff);

    private ServerSocket serverSocket;
    private volatile byte[] sps;
    private volatile byte[] pps;

    public RtspServer(StreamConfig config, H264Encoder encoder) {
        this.config = config;
        this.encoder = encoder;
        encoder.setListener(this);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        executor.execute(this::acceptLoop);
    }

    public void stop() {
        running.set(false);
        for (Session s : sessions) {
            s.close();
        }
        sessions.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
    }

    @Override
    public void onSpsPps(byte[] spsNal, byte[] ppsNal) {
        this.sps = spsNal;
        this.pps = ppsNal;
    }

    @Override
    public void onNal(byte[] annexB, boolean isKeyFrame, long ptsUs) {
        if (sessions.isEmpty()) return;
        byte[][] nals = splitNals(annexB);
        for (Session s : sessions) {
            if (!s.playing) continue;
            for (byte[] nal : nals) {
                s.sendNal(nal, isKeyFrame, ptsUs);
            }
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", config.port));
            Log.i(TAG, "RTSP listening on :" + config.port + "/" + config.streamPath());
            while (running.get()) {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handleClient(socket));
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "RTSP accept failed: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    private void handleClient(Socket socket) {
        Session session = new Session(socket);
        try {
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (running.get() && !socket.isClosed()) {
                String requestLine = reader.readLine();
                if (requestLine == null) break;
                if (requestLine.isEmpty()) continue;

                String[] parts = requestLine.split(" ", 3);
                if (parts.length < 2) continue;
                String method = parts[0].toUpperCase(Locale.US);
                String url = parts[1];

                int cseq = 1;
                String auth = null;
                String line;
                int contentLength = 0;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    String lower = line.toLowerCase(Locale.US);
                    if (lower.startsWith("cseq:")) {
                        cseq = parseIntSafe(line.substring(line.indexOf(':') + 1).trim(), 1);
                    } else if (lower.startsWith("authorization:")) {
                        auth = line.substring(line.indexOf(':') + 1).trim();
                    } else if (lower.startsWith("content-length:")) {
                        contentLength = parseIntSafe(line.substring(line.indexOf(':') + 1).trim(), 0);
                    }
                }
                if (contentLength > 0) {
                    char[] body = new char[contentLength];
                    //noinspection ResultOfMethodCallIgnored
                    reader.read(body);
                }

                if (!authorize(auth) && !"OPTIONS".equals(method)) {
                    session.reply(401, cseq, "WWW-Authenticate: Bearer realm=\"ava\"\r\n", null);
                    break;
                }

                switch (method) {
                    case "OPTIONS":
                        session.reply(200, cseq,
                                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n", null);
                        break;
                    case "DESCRIBE":
                        if (!pathMatches(url)) {
                            session.reply(404, cseq, null, null);
                            break;
                        }
                        String sdp = buildSdp(socket.getLocalAddress().getHostAddress());
                        session.reply(200, cseq,
                                "Content-Type: application/sdp\r\nContent-Base: "
                                        + "rtsp://127.0.0.1:" + config.port + "/"
                                        + config.streamPath() + "/\r\n",
                                sdp);
                        break;
                    case "SETUP":
                        sessions.addIfAbsent(session);
                        session.reply(200, cseq,
                                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
                                        + "Session: " + session.id + ";timeout=60\r\n",
                                null);
                        break;
                    case "PLAY":
                        session.playing = true;
                        encoder.requestKeyFrame();
                        session.reply(200, cseq,
                                "Session: " + session.id + "\r\n"
                                        + "RTP-Info: url=track1;seq=" + rtpSeq.get() + "\r\n",
                                null);
                        Log.i(TAG, "RTSP PLAY client=" + session.id);
                        break;
                    case "TEARDOWN":
                        session.playing = false;
                        session.reply(200, cseq, "Session: " + session.id + "\r\n", null);
                        sessions.remove(session);
                        return;
                    default:
                        session.reply(405, cseq, null, null);
                        break;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "RTSP client ended: " + e.getMessage());
        } finally {
            sessions.remove(session);
            session.close();
        }
    }

    private boolean pathMatches(String url) {
        String needle = "/" + config.streamPath();
        return url.contains(needle) || url.endsWith("/") || url.contains("track1");
    }

    private boolean authorize(String authHeader) {
        String token = config.token == null ? "" : config.token.trim();
        if (token.isEmpty()) return true;
        if (authHeader == null) return false;
        return authHeader.equals("Bearer " + token) || authHeader.contains(token);
    }

    private String buildSdp(String host) {
        byte[] localSps = sps != null ? sps : encoder.getSps();
        byte[] localPps = pps != null ? pps : encoder.getPps();
        String profile = "42C01E";
        String spsB64 = "";
        String ppsB64 = "";
        if (localSps != null && localSps.length > 0) {
            byte[] raw = stripStart(localSps);
            if (raw.length >= 4) {
                profile = String.format(Locale.US, "%02X%02X%02X",
                        raw[1] & 0xff, raw[2] & 0xff, raw[3] & 0xff);
            }
            spsB64 = Base64.encodeToString(raw, Base64.NO_WRAP);
        }
        if (localPps != null && localPps.length > 0) {
            ppsB64 = Base64.encodeToString(stripStart(localPps), Base64.NO_WRAP);
        }
        int w = encoder.getWidth();
        int h = encoder.getHeight();
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 " + host + "\r\n"
                + "s=Ava Camera Stream\r\n"
                + "t=0 0\r\n"
                + "m=video 0 RTP/AVP 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=control:track1\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 packetization-mode=1;profile-level-id=" + profile
                + ";sprop-parameter-sets=" + spsB64 + "," + ppsB64 + "\r\n"
                + "a=framesize:96 " + w + "-" + h + "\r\n"
                + "a=framerate:" + Math.max(1, config.fps) + "\r\n";
    }

    private static byte[] stripStart(byte[] data) {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            byte[] out = new byte[data.length - 4];
            System.arraycopy(data, 4, out, 0, out.length);
            return out;
        }
        if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            byte[] out = new byte[data.length - 3];
            System.arraycopy(data, 3, out, 0, out.length);
            return out;
        }
        return data;
    }

    private static byte[][] splitNals(byte[] annexB) {
        java.util.ArrayList<byte[]> list = new java.util.ArrayList<>();
        int i = 0;
        while (i + 3 < annexB.length) {
            int sc = 0;
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1) {
                sc = 4;
            } else if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                sc = 3;
            }
            if (sc == 0) {
                i++;
                continue;
            }
            int start = i + sc;
            int next = -1;
            for (int j = start; j + 3 < annexB.length; j++) {
                if ((annexB[j] == 0 && annexB[j + 1] == 0 && annexB[j + 2] == 0 && annexB[j + 3] == 1)
                        || (annexB[j] == 0 && annexB[j + 1] == 0 && annexB[j + 2] == 1)) {
                    next = j;
                    break;
                }
            }
            if (next < 0) next = annexB.length;
            byte[] nal = new byte[next - start];
            System.arraycopy(annexB, start, nal, 0, nal.length);
            if (nal.length > 0) list.add(nal);
            i = next;
        }
        if (list.isEmpty() && annexB.length > 0) {
            list.add(annexB);
        }
        return list.toArray(new byte[0][]);
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private final class Session {
        final Socket socket;
        final OutputStream out;
        final String id = Integer.toHexString(System.identityHashCode(this));
        volatile boolean playing;

        Session(Socket socket) {
            this.socket = socket;
            OutputStream o;
            try {
                o = socket.getOutputStream();
            } catch (IOException e) {
                o = null;
            }
            this.out = o;
        }

        synchronized void reply(int code, int cseq, String extraHeaders, String body)
                throws IOException {
            if (out == null) return;
            String status = code == 200 ? "OK" : code == 401 ? "Unauthorized"
                    : code == 404 ? "Not Found" : "Error";
            StringBuilder sb = new StringBuilder();
            sb.append("RTSP/1.0 ").append(code).append(' ').append(status).append("\r\n");
            sb.append("CSeq: ").append(cseq).append("\r\n");
            if (extraHeaders != null) sb.append(extraHeaders);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                sb.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
            } else {
                sb.append("\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }

        synchronized void sendNal(byte[] nal, boolean keyFrame, long ptsUs) {
            if (out == null || !playing || nal == null || nal.length == 0) return;
            try {
                // FU-A fragment if needed (max ~1400)
                final int maxPayload = 1200;
                long ts = ptsUs * 9 / 100; // 90kHz
                if (nal.length <= maxPayload) {
                    sendRtp(nal, true, ts);
                } else {
                    int nalHeader = nal[0] & 0xff;
                    int nalType = nalHeader & 0x1f;
                    int nri = nalHeader & 0x60;
                    int offset = 1;
                    boolean first = true;
                    while (offset < nal.length) {
                        int len = Math.min(maxPayload - 2, nal.length - offset);
                        boolean last = offset + len >= nal.length;
                        byte[] fu = new byte[len + 2];
                        fu[0] = (byte) (nri | 28); // FU-A
                        fu[1] = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | nalType);
                        System.arraycopy(nal, offset, fu, 2, len);
                        sendRtp(fu, last, ts);
                        offset += len;
                        first = false;
                    }
                }
            } catch (IOException e) {
                playing = false;
                close();
            }
        }

        private void sendRtp(byte[] payload, boolean marker, long ts) throws IOException {
            int seq = rtpSeq.getAndIncrement() & 0xffff;
            ByteBuffer header = ByteBuffer.allocate(4 + 12 + payload.length);
            // interleaved: $, channel, length
            header.put((byte) '$');
            header.put((byte) 0);
            header.putShort((short) (12 + payload.length));
            // RTP header
            header.put((byte) 0x80);
            header.put((byte) ((marker ? 0x80 : 0) | 96));
            header.putShort((short) seq);
            header.putInt((int) ts);
            header.putInt(rtpSsrc);
            header.put(payload);
            out.write(header.array(), 0, header.position());
            out.flush();
        }

        void close() {
            playing = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
