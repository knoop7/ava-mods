package com.ava.mods.camerastream;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** multipart/x-mixed-replace MJPEG HTTP server for browsers / go2rtc / ffmpeg. */
public final class MjpegHttpServer {

    private static final String TAG = "MjpegHttpServer";
    private static final String BOUNDARY = "avaframe";

    private final StreamConfig config;
    private final CameraJpegSource camera;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;

    public MjpegHttpServer(StreamConfig config, CameraJpegSource camera) {
        this.config = config;
        this.camera = camera;
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
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", config.port));
            Log.i(TAG, "MJPEG listening on :" + config.port + "/" + config.streamPath());
            while (running.get()) {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handleClient(socket));
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "MJPEG accept failed: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    private void handleClient(Socket socket) {
        Client client = null;
        try {
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }
            String line;
            String auth = null;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("authorization:")) {
                    auth = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            String path = parsePath(requestLine);
            String queryToken = parseQueryToken(requestLine);
            if (!authorize(auth, queryToken)) {
                writePlain(socket, 401, "Unauthorized\r\nWWW-Authenticate: Bearer realm=\"ava\"\r\n");
                socket.close();
                return;
            }

            String expected = "/" + config.streamPath();
            if (!path.equals(expected) && !path.equals(expected + ".mjpg")
                    && !path.equals("/") && !path.equals("/stream")) {
                writePlain(socket, 404, "Not Found\r\n");
                socket.close();
                return;
            }

            OutputStream raw = new BufferedOutputStream(socket.getOutputStream());
            String headers =
                    "HTTP/1.0 200 OK\r\n"
                            + "Connection: close\r\n"
                            + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                            + "Pragma: no-cache\r\n"
                            + "Content-Type: multipart/x-mixed-replace; boundary="
                            + BOUNDARY
                            + "\r\n\r\n";
            raw.write(headers.getBytes(StandardCharsets.UTF_8));
            raw.flush();

            client = new Client(socket, raw);
            clients.add(client);
            Log.i(TAG, "MJPEG client connected, count=" + clients.size());

            while (running.get() && !socket.isClosed()) {
                byte[] jpeg = camera.getLatestJpeg();
                if (jpeg != null) {
                    client.writeFrame(jpeg);
                }
                Thread.sleep(config.frameIntervalMs());
            }
        } catch (Exception e) {
            Log.d(TAG, "MJPEG client ended: " + e.getMessage());
        } finally {
            if (client != null) {
                clients.remove(client);
                client.close();
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean authorize(String authHeader, String queryToken) {
        String token = config.token == null ? "" : config.token.trim();
        if (token.isEmpty()) return true;
        if (queryToken != null && queryToken.equals(token)) return true;
        if (authHeader == null) return false;
        return authHeader.equals("Bearer " + token) || authHeader.contains(token);
    }

    private static String parsePath(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return "/";
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return path;
    }

    private static String parseQueryToken(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        String path = parts[1];
        int q = path.indexOf('?');
        if (q < 0) return null;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void writePlain(Socket socket, int code, String extraHeaders) throws IOException {
        String body = code == 401 ? "unauthorized" : "not found";
        String resp =
                "HTTP/1.0 " + code + " \r\n"
                        + extraHeaders
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + body.length() + "\r\n\r\n"
                        + body;
        socket.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Client {
        final Socket socket;
        final OutputStream out;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        synchronized void writeFrame(byte[] jpeg) throws IOException {
            String part =
                    "--" + BOUNDARY + "\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + jpeg.length + "\r\n\r\n";
            out.write(part.getBytes(StandardCharsets.UTF_8));
            out.write(jpeg);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
