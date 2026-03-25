package com.ava.mods.mimiclaw.channel;

import android.util.Log;
import com.ava.mods.mimiclaw.BuildInfo;
import com.ava.mods.mimiclaw.MimiClawManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebConsoleServer {
    private static final String TAG = "WebConsoleServer";
    private static final int PORT = 18789;
    private static final String COOKIE_NAME = "openclaw_session";
    private final MimiClawManager manager;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final java.util.List<BufferedWriter> sseClients = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile String lastError = "";

    public WebConsoleServer(MimiClawManager manager) {
        this.manager = manager;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", PORT));
            serverSocket = server;
            running = true;
            lastError = "";
            serverThread = new Thread(this::acceptLoop, "OpenClawWebConsole");
            serverThread.start();
            Log.i(TAG, "Web console listening on 0.0.0.0:" + PORT);
        } catch (Exception e) {
            running = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Web console failed to start", e);
        }
    }

    public synchronized void stop() {
        running = false;
        sessions.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        lastError = "";
    }

    private void acceptLoop() {
        try {
            ServerSocket server = serverSocket;
            if (server == null) {
                throw new IllegalStateException("server_not_bound");
            }
            while (running) {
                try {
                    Socket socket = server.accept();
                    clientPool.execute(() -> handleClient(socket));
                } catch (SocketException e) {
                    if (!running) {
                        break;
                    }
                    Log.w(TAG, "Socket accept failed", e);
                }
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Web console failed", e);
        } finally {
            running = false;
            serverSocket = null;
        }
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public int getPort() {
        return PORT;
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(30000);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            ParsedRequest request = readRequest(input);
            if (request == null || request.requestLine == null || request.requestLine.trim().isEmpty()) {
                return;
            }

            String[] requestParts = request.requestLine.split(" ");
            if (requestParts.length < 2) {
                writePlain(output, 400, "Bad Request");
                return;
            }

            String method = requestParts[0];
            String rawPath = requestParts[1];
            String path = normalizePath(rawPath);
            Map<String, String> headers = request.headers;

            if ("OPTIONS".equals(method)) {
                writeCorsPreflightResponse(output, headers.get("origin"));
                return;
            }

            String body = request.body;

            String sessionId = resolveSessionId(rawPath, headers);
            boolean authed = isSessionValid(sessionId);

            if ("GET".equals(method) && "/".equals(path)) {
                writeHtml(output, 200, buildPage(authed, sessionId), authed ? null : expiredCookieHeader());
                return;
            }

            if ("GET".equals(method) && path.startsWith("/api/file")) {
                String filePath = "";
                int qIdx = rawPath.indexOf("?path=");
                if (qIdx >= 0) {
                    String pathPart = rawPath.substring(qIdx + 6);
                    int ampIdx = pathPart.indexOf('&');
                    if (ampIdx >= 0) {
                        pathPart = pathPart.substring(0, ampIdx);
                    }
                    filePath = decode(pathPart);
                }
                if (filePath.isEmpty()) {
                    writeJson(output, 400, errorJson("path_required"), null);
                    return;
                }
                java.io.File file = new java.io.File(filePath);
                if (!file.exists() || !file.canRead()) {
                    writeJson(output, 404, errorJson("file_not_found"), null);
                    return;
                }
                String mimeType = guessMimeType(filePath);
                byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                String fileName = file.getName();
                String disposition = "attachment; filename=\"" + fileName.replace("\"", "\\\"") + "\"";
                writeResponse(output, 200, mimeType, data, "Content-Disposition: " + disposition, null);
                return;
            }

            if ("POST".equals(method) && "/login".equals(path)) {
                Map<String, String> form = parseForm(body);
                String password = form.get("password");
                if (manager.isWebConsolePasswordValid(password)) {
                    String newSessionId = UUID.randomUUID().toString();
                    sessions.put(newSessionId, System.currentTimeMillis());
                    writeJson(output, 200, okJson()
                        .put("message", "Login success")
                        .put("sessionId", newSessionId),
                        "Set-Cookie: " + COOKIE_NAME + "=" + newSessionId + "; Path=/; HttpOnly; SameSite=Lax");
                } else {
                    writeJson(output, 401, errorJson("invalid_password"), null);
                }
                return;
            }

            if (!authed) {
                writeJson(output, 401, errorJson("auth_required"), expiredCookieHeader());
                return;
            }

            if ("POST".equals(method) && "/api/upload".equals(path)) {
                try {
                    String contentType = headers.get("content-type");
                    if (contentType == null || !contentType.contains("multipart/form-data")) {
                        writeJson(output, 400, errorJson("multipart_required"), null);
                        return;
                    }
                    String boundary = extractBoundary(contentType);
                    if (boundary == null) {
                        writeJson(output, 400, errorJson("boundary_missing"), null);
                        return;
                    }
                    Map<String, Object> parts = parseMultipart(body, boundary);
                    byte[] fileData = (byte[]) parts.get("file_data");
                    String fileName = (String) parts.get("file_name");
                    String fileType = (String) parts.get("file_type");
                    if (fileData == null || fileName == null) {
                        writeJson(output, 400, errorJson("file_missing"), null);
                        return;
                    }
                    String content = null;
                    String base64Data = null;
                    if (fileType != null && fileType.startsWith("image/")) {
                        // Compress image before encoding
                        byte[] compressedData = compressImage(fileData);
                        base64Data = android.util.Base64.encodeToString(compressedData, android.util.Base64.NO_WRAP);
                        fileType = "image/jpeg"; // Always output as JPEG after compression
                    } else if (fileType != null && (fileType.startsWith("text/") || fileType.contains("json") || fileType.contains("xml") || fileName.endsWith(".md") || fileName.endsWith(".txt") || fileName.endsWith(".csv"))) {
                        content = new String(fileData, StandardCharsets.UTF_8);
                        if (content.length() > 50000) {
                            content = content.substring(0, 50000) + "\n... (truncated)";
                        }
                    } else {
                        content = "[Binary file: " + fileName + ", " + fileData.length + " bytes]";
                    }
                    JSONObject result = okJson()
                        .put("name", fileName)
                        .put("type", fileType)
                        .put("size", fileData.length)
                        .put("content", content)
                        .put("base64", base64Data);
                    writeJson(output, 200, result, null);
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Upload error", e);
                    writeJson(output, 500, errorJson("upload_failed: " + e.getMessage()), null);
                    return;
                }
            }

            if ("POST".equals(method) && "/api/chat".equals(path)) {
                JSONObject json = parseJson(body);
                String message = json.optString("message", "").trim();
                JSONArray attachments = json.optJSONArray("attachments");
                StringBuilder fullMessage = new StringBuilder();
                if (attachments != null && attachments.length() > 0) {
                    for (int i = 0; i < attachments.length(); i++) {
                        JSONObject att = attachments.getJSONObject(i);
                        String attType = att.optString("type", "");
                        String attName = att.optString("name", "file");
                        String attContent = att.optString("content", null);
                        String attBase64 = att.optString("base64", null);
                        if (attBase64 != null && !attBase64.isEmpty() && attType.startsWith("image/")) {
                            fullMessage.append("[IMAGE: ").append(attName).append("]\n");
                            fullMessage.append("<image_data>").append(attType).append(";base64,").append(attBase64).append("</image_data>\n\n");
                        } else if (attContent != null && !attContent.isEmpty()) {
                            fullMessage.append("[FILE: ").append(attName).append("]\n");
                            fullMessage.append("```\n").append(attContent).append("\n```\n\n");
                        }
                    }
                }
                if (!message.isEmpty()) {
                    fullMessage.append(message);
                }
                String finalMessage = fullMessage.toString().trim();
                if (finalMessage.isEmpty()) {
                    writeJson(output, 400, errorJson("message_required"), null);
                    return;
                }
                Log.d(TAG, "Chat request: " + finalMessage.substring(0, Math.min(50, finalMessage.length())));
                final String sid = sessionId;
                final String msg = finalMessage;
                clientPool.execute(() -> {
                    try {
                        manager.handleWebConsoleMessage(sid, msg);
                    } catch (Exception e) {
                        Log.e(TAG, "Async chat error", e);
                    }
                });
                JSONObject result = okJson()
                    .put("message", message)
                    .put("chatId", sessionId)
                    .put("status", "processing");
                writeJson(output, 200, result, null);
                return;
            }

            if ("POST".equals(method) && "/api/stop".equals(path)) {
                boolean stopped = manager.stopWebConsoleProcessing(sessionId);
                writeJson(output, 200, okJson().put("stopped", stopped).put("status", manager.getAgentStatus()), null);
                return;
            }

            if ("POST".equals(method) && "/api/password".equals(path)) {
                JSONObject json = parseJson(body);
                String currentPassword = json.optString("currentPassword", "");
                String newPassword = json.optString("newPassword", "").trim();
                if (newPassword.length() < 4) {
                    writeJson(output, 400, errorJson("password_too_short"), null);
                    return;
                }
                if (!manager.updateWebConsolePassword(currentPassword, newPassword)) {
                    writeJson(output, 400, errorJson("current_password_invalid"), null);
                    return;
                }
                writeJson(output, 200, okJson().put("message", "Password updated"), null);
                return;
            }

            if ("GET".equals(method) && "/api/status".equals(path)) {
                JSONObject detail = manager.getWebConsoleStatus(sessionId);
                JSONObject result = okJson()
                    .put("chatId", sessionId)
                    .put("status", manager.getAgentStatus())
                    .put("heartbeat", manager.getLastResponse())
                    .put("detail", detail);
                writeJson(output, 200, result, null);
                return;
            }

            if ("GET".equals(method) && "/api/config".equals(path)) {
                JSONObject profiles = manager.getProviderProfilesPayload();
                JSONObject result = okJson()
                    .put("provider", manager.getConfigValue("provider", "openai"))
                    .put("model", manager.getConfigValue("model", ""))
                    .put("custom_api_url", manager.getConfigValue("custom_api_url", "https://openrouter.ai/api/v1"))
                    .put("api_key", manager.getConfigValue("api_key", ""))
                    .put("max_tokens", manager.getConfigValue("max_tokens", "4096"))
                    .put("max_tool_iterations", manager.getConfigValue("max_tool_iterations", "30"))
                    .put("active_profile_id", profiles.optString("active_profile_id", "default"))
                    .put("profiles", profiles.optJSONArray("profiles"));
                writeJson(output, 200, result, null);
                return;
            }

            if ("POST".equals(method) && "/api/config".equals(path)) {
                JSONObject json = parseJson(body);
                boolean changed = false;
                if (json.has("provider")) {
                    manager.setConfigValue("provider", json.getString("provider"));
                    changed = true;
                }
                if (json.has("model")) {
                    manager.setConfigValue("model", json.getString("model"));
                    changed = true;
                }
                if (json.has("custom_api_url")) {
                    manager.setConfigValue("custom_api_url", json.getString("custom_api_url"));
                    changed = true;
                }
                if (json.has("api_key")) {
                    manager.setConfigValue("api_key", json.getString("api_key"));
                    changed = true;
                }
                if (json.has("max_tokens")) {
                    manager.setConfigValue("max_tokens", json.get("max_tokens").toString());
                    changed = true;
                }
                if (json.has("max_tool_iterations")) {
                    manager.setConfigValue("max_tool_iterations", json.get("max_tool_iterations").toString());
                    changed = true;
                }
                writeJson(output, 200, okJson().put("updated", changed), null);
                return;
            }

            if ("POST".equals(method) && "/api/config/profile".equals(path)) {
                JSONObject json = parseJson(body);
                String action = json.optString("action", "");
                if ("set_active".equals(action)) {
                    manager.setActiveProviderProfile(json.optString("profile_id", ""));
                    writeJson(output, 200, okJson().put("updated", true), null);
                    return;
                }
                if ("rename".equals(action)) {
                    JSONObject result = manager.renameProviderProfile(
                        json.optString("profile_id", ""),
                        json.optString("profile_name", "")
                    );
                    writeJson(output, 200, okJson().put("updated", true).put("result", result), null);
                    return;
                }
                if ("create".equals(action)) {
                    JSONObject result = manager.createEmptyProviderProfile(
                        json.optString("profile_id", ""),
                        json.optString("profile_name", ""),
                        json.optBoolean("make_active", true)
                    );
                    writeJson(output, 200, okJson().put("updated", true).put("result", result), null);
                    return;
                }
                if ("delete".equals(action)) {
                    JSONObject result = manager.deleteProviderProfile(json.optString("profile_id", ""));
                    writeJson(output, 200, okJson().put("updated", result.optBoolean("deleted", false)).put("result", result), null);
                    return;
                }
                writeJson(output, 400, errorJson("unsupported_profile_action"), null);
                return;
            }

            if ("GET".equals(method) && "/api/history".equals(path)) {
                JSONObject result = okJson()
                    .put("chatId", sessionId)
                    .put("messages", manager.getWebConsoleHistory(sessionId, 200));
                writeJson(output, 200, result, null);
                return;
            }

            if ("GET".equals(method) && "/api/skill_config".equals(path)) {
                JSONObject enabled = manager.getSkillConfig();
                JSONArray userSkills = manager.getUserInstalledSkills();
                JSONArray cronJobs = manager.getCronJobsForUi();
                writeJson(output, 200, okJson().put("enabled", enabled).put("user_skills", userSkills).put("cron_jobs", cronJobs), null);
                return;
            }

            if ("POST".equals(method) && "/api/skill_config".equals(path)) {
                JSONObject json = parseJson(body);
                String skillId = json.optString("skill_id", "");
                if (!skillId.isEmpty()) {
                    boolean enabled = json.optBoolean("enabled", true);
                    manager.setSkillEnabled(skillId, enabled);
                }
                String cronJobId = json.optString("cron_job_id", "");
                if (!cronJobId.isEmpty()) {
                    boolean enabled = json.optBoolean("enabled", true);
                    manager.setCronJobEnabled(cronJobId, enabled);
                }
                writeJson(output, 200, okJson(), null);
                return;
            }

            if ("GET".equals(method) && "/api/events".equals(path)) {
                handleSSE(client, output, sessionId);
                return;
            }

            writePlain(output, 404, "Not Found");
        } catch (SocketTimeoutException e) {
            Log.d(TAG, "Client read timeout");
        } catch (SocketException e) {
            Log.d(TAG, "Client socket closed: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Client handling failed", e);
        }
    }

    private ParsedRequest readRequest(InputStream input) throws Exception {
        ByteArrayOutputStream headBuffer = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int b = input.read();
            if (b < 0) {
                break;
            }
            headBuffer.write(b);
            if ((matched == 0 || matched == 2) && b == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = 0;
            }
        }

        byte[] headBytes = headBuffer.toByteArray();
        if (headBytes.length == 0) {
            return null;
        }

        String headText = new String(headBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headText.split("\r\n");
        if (lines.length == 0) {
            return null;
        }

        ParsedRequest request = new ParsedRequest();
        request.requestLine = lines[0];
        int contentLength = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim().toLowerCase();
            String value = line.substring(idx + 1).trim();
            request.headers.put(key, value);
            if ("content-length".equals(key)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (Exception ignored) {
                }
            }
        }

        if (contentLength > 0) {
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = input.read(bodyBytes, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            request.bodyBytes = new byte[read];
            System.arraycopy(bodyBytes, 0, request.bodyBytes, 0, read);
            String contentType = request.headers.get("content-type");
            if (contentType != null && contentType.contains("multipart/form-data")) {
                request.body = new String(request.bodyBytes, StandardCharsets.ISO_8859_1);
            } else {
                request.body = new String(request.bodyBytes, StandardCharsets.UTF_8);
            }
        } else {
            request.body = "";
            request.bodyBytes = new byte[0];
        }
        return request;
    }

    private static final class ParsedRequest {
        String requestLine;
        final Map<String, String> headers = new HashMap<>();
        byte[] bodyBytes = new byte[0];
        String body = "";
    }

    private void handleSSE(Socket client, OutputStream output, String sessionId) throws Exception {
        client.setSoTimeout(0);
        client.setKeepAlive(true);
        client.setTcpNoDelay(true);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 200 OK\r\n");
        writer.write("Content-Type: text/event-stream\r\n");
        writer.write("Cache-Control: no-cache, no-store\r\n");
        writer.write("Connection: keep-alive\r\n");
        writer.write("X-Accel-Buffering: no\r\n");
        writer.write("Access-Control-Allow-Origin: *\r\n");
        writer.write("Access-Control-Allow-Credentials: true\r\n");
        writer.write("\r\n");
        writer.flush();
        
        // Register this SSE client for broadcasts
        sseClients.add(writer);

        String lastStatus = "";
        int lastMsgCount = 0;
        long lastPing = System.currentTimeMillis();

        JSONArray initHistory = manager.getWebConsoleHistory(sessionId, 200);
        for (int i = 0; i < initHistory.length(); i++) {
            JSONObject msg = initHistory.getJSONObject(i);
            writer.write("event: message\ndata: " + msg.toString() + "\n\n");
        }
        writer.flush();
        lastMsgCount = initHistory.length();
        lastPing = System.currentTimeMillis();

        try {
            while (running && !client.isClosed()) {
                try {
                    String status = manager.getAgentStatus();
                    if (!status.equals(lastStatus)) {
                        writer.write("event: status\ndata: " + status + "\n\n");
                        writer.flush();
                        lastStatus = status;
                        lastPing = System.currentTimeMillis();
                    }

                    JSONArray history = manager.getWebConsoleHistory(sessionId, 200);
                    int currentCount = history.length();
                    if (currentCount > lastMsgCount) {
                        for (int i = lastMsgCount; i < currentCount; i++) {
                            JSONObject msg = history.getJSONObject(i);
                            writer.write("event: message\ndata: " + msg.toString() + "\n\n");
                            writer.flush();
                        }
                        lastMsgCount = currentCount;
                        lastPing = System.currentTimeMillis();
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastPing > 10000) {
                        writer.write(": ping\n\n");
                        writer.flush();
                        lastPing = now;
                    }

                    Thread.sleep(150);
                } catch (Exception e) {
                    Log.d(TAG, "SSE connection closed: " + e.getMessage());
                    break;
                }
            }
        } finally {
            sseClients.remove(writer);
        }
    }
    
    public void broadcastSkillChange(String skillId, boolean enabled, String message) {
        String eventData = "{\"type\":\"skill_change\",\"skill_id\":\"" + skillId + "\",\"enabled\":" + enabled + ",\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
        synchronized (sseClients) {
            java.util.Iterator<BufferedWriter> it = sseClients.iterator();
            while (it.hasNext()) {
                BufferedWriter writer = it.next();
                try {
                    writer.write("event: skill_change\ndata: " + eventData + "\n\n");
                    writer.flush();
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
        Log.i(TAG, "Broadcasted skill change: " + skillId + " -> " + enabled);
    }

    private boolean isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return sessions.containsKey(sessionId);
    }

    private String resolveSessionId(String rawPath, Map<String, String> headers) {
        String sessionId = parseSessionId(headers.get("cookie"));
        if (isSessionValid(sessionId)) {
            return sessionId;
        }
        sessionId = parseSessionIdFromQuery(rawPath);
        if (isSessionValid(sessionId)) {
            return sessionId;
        }
        sessionId = headers.get("x-openclaw-session");
        if (isSessionValid(sessionId)) {
            return sessionId;
        }
        return sessionId;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        int idx = rawPath.indexOf('?');
        return idx >= 0 ? rawPath.substring(0, idx) : rawPath;
    }

    private String parseSessionIdFromQuery(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        int idx = rawPath.indexOf('?');
        if (idx < 0 || idx + 1 >= rawPath.length()) {
            return null;
        }
        String query = rawPath.substring(idx + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "sid".equals(kv[0])) {
                return decode(kv[1]);
            }
        }
        return null;
    }

    private String parseSessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && COOKIE_NAME.equals(kv[0].trim())) {
                return kv[1].trim();
            }
        }
        return null;
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return map;
        }
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String key = decode(kv[0]);
            String value = kv.length > 1 ? decode(kv[1]) : "";
            map.put(key, value);
        }
        return map;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private String guessMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private JSONObject parseJson(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject okJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", true);
        } catch (Exception ignored) {
        }
        return json;
    }

    private JSONObject errorJson(String error) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", error);
        } catch (Exception ignored) {
        }
        return json;
    }
    
    private static final int MAX_IMAGE_SIZE = 800 * 1024; // 800KB max
    
    private byte[] compressImage(byte[] imageData) {
        try {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            if (bitmap == null) {
                return imageData;
            }
            
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int quality = 75;
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out);
            
            while (out.size() > MAX_IMAGE_SIZE && quality > 10) {
                out.reset();
                quality -= 10;
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out);
            }
            
            bitmap.recycle();
            Log.d(TAG, "Image compressed: " + imageData.length + " -> " + out.size() + " bytes, quality=" + quality);
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Image compression failed", e);
            return imageData;
        }
    }

    private String extractBoundary(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String boundary = trimmed.substring(9);
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    private Map<String, Object> parseMultipart(String body, String boundary) {
        Map<String, Object> result = new HashMap<>();
        try {
            byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);
            String delimiter = "--" + boundary;
            String endDelimiter = "--" + boundary + "--";
            int pos = 0;
            while (pos < bodyBytes.length) {
                int delimStart = indexOf(bodyBytes, delimiter.getBytes(StandardCharsets.ISO_8859_1), pos);
                if (delimStart < 0) break;
                int headerStart = delimStart + delimiter.length();
                if (headerStart + 2 <= bodyBytes.length && bodyBytes[headerStart] == '\r' && bodyBytes[headerStart + 1] == '\n') {
                    headerStart += 2;
                }
                int headerEnd = indexOf(bodyBytes, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headerStart);
                if (headerEnd < 0) break;
                String headers = new String(bodyBytes, headerStart, headerEnd - headerStart, StandardCharsets.UTF_8);
                int dataStart = headerEnd + 4;
                int nextDelim = indexOf(bodyBytes, ("\r\n" + delimiter).getBytes(StandardCharsets.ISO_8859_1), dataStart);
                if (nextDelim < 0) {
                    nextDelim = indexOf(bodyBytes, delimiter.getBytes(StandardCharsets.ISO_8859_1), dataStart);
                    if (nextDelim < 0) break;
                }
                byte[] data = new byte[nextDelim - dataStart];
                System.arraycopy(bodyBytes, dataStart, data, 0, data.length);
                String disposition = null;
                String contentTypeHeader = null;
                for (String line : headers.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-disposition:")) {
                        disposition = line.substring(20).trim();
                    } else if (line.toLowerCase().startsWith("content-type:")) {
                        contentTypeHeader = line.substring(13).trim();
                    }
                }
                if (disposition != null && disposition.contains("name=\"file\"")) {
                    result.put("file_data", data);
                    result.put("file_type", contentTypeHeader);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename=\"([^\"]+)\"").matcher(disposition);
                    if (m.find()) {
                        result.put("file_name", m.group(1));
                    }
                }
                pos = nextDelim;
                if (new String(bodyBytes, nextDelim, Math.min(endDelimiter.length(), bodyBytes.length - nextDelim), StandardCharsets.ISO_8859_1).startsWith(endDelimiter)) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseMultipart error", e);
        }
        return result;
    }

    private int indexOf(byte[] data, byte[] pattern, int start) {
        outer:
        for (int i = start; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private String expiredCookieHeader() {
        return "Set-Cookie: " + COOKIE_NAME + "=; Path=/; Max-Age=0; SameSite=Lax";
    }

    private void writePlain(OutputStream output, int status, String text) throws Exception {
        writeResponse(output, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), null, null);
    }

    private void writeHtml(OutputStream output, int status, String html, String extraHeader) throws Exception {
        writeResponse(output, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), extraHeader, null);
    }

    private void writeJson(OutputStream output, int status, JSONObject json, String extraHeader) throws Exception {
        writeResponse(output, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), extraHeader, null);
    }

    private void writeCorsPreflightResponse(OutputStream output, String origin) throws Exception {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 204 No Content\r\n");
        if (origin != null && !origin.isEmpty()) {
            writer.write("Access-Control-Allow-Origin: " + origin + "\r\n");
        } else {
            writer.write("Access-Control-Allow-Origin: *\r\n");
        }
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        writer.write("Access-Control-Allow-Headers: Content-Type, Cookie\r\n");
        writer.write("Access-Control-Allow-Credentials: true\r\n");
        writer.write("Access-Control-Max-Age: 86400\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
    }

    private void writeResponse(OutputStream output, int status, String contentType, byte[] body, String extraHeader, String origin) throws Exception {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 " + status + " " + statusText(status) + "\r\n");
        writer.write("Content-Type: " + contentType + "\r\n");
        writer.write("Content-Length: " + body.length + "\r\n");
        writer.write("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
        writer.write("Pragma: no-cache\r\n");
        writer.write("Expires: 0\r\n");
        if (origin != null && !origin.isEmpty()) {
            writer.write("Access-Control-Allow-Origin: " + origin + "\r\n");
        } else {
            writer.write("Access-Control-Allow-Origin: *\r\n");
        }
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        writer.write("Access-Control-Allow-Headers: Content-Type\r\n");
        writer.write("Access-Control-Allow-Credentials: true\r\n");
        writer.write("Connection: close\r\n");
        if (extraHeader != null && !extraHeader.isEmpty()) {
            writer.write(extraHeader + "\r\n");
        }
        writer.write("\r\n");
        writer.flush();
        output.write(body);
        output.flush();
    }

    private String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            default: return "OK";
        }
    }

    private String buildPage(boolean authed, String sessionId) {
        String svgSun = "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><circle cx='12' cy='12' r='4'/><path d='M12 2v2'/><path d='M12 20v2'/><path d='m4.93 4.93 1.41 1.41'/><path d='m17.66 17.66 1.41 1.41'/><path d='M2 12h2'/><path d='M20 12h2'/><path d='m6.34 17.66-1.41 1.41'/><path d='m19.07 4.93-1.41 1.41'/></svg>";
        String svgMoon = "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z'/></svg>";
        String svgMenu = "<svg xmlns='http://www.w3.org/2000/svg' width='22' height='22' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><line x1='4' x2='20' y1='12' y2='12'/><line x1='4' x2='20' y1='6' y2='6'/><line x1='4' x2='20' y1='18' y2='18'/></svg>";
        String svgClose = "<svg xmlns='http://www.w3.org/2000/svg' width='22' height='22' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M18 6 6 18'/><path d='m6 6 12 12'/></svg>";
        String svgSend = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'><line x1='12' y1='19' x2='12' y2='5'/><polyline points='5 12 12 5 19 12'/></svg>";
        String svgRefresh = "<svg xmlns='http://www.w3.org/2000/svg' width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8'/><path d='M21 3v5h-5'/><path d='M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16'/><path d='M8 16H3v5'/></svg>";
        String svgSettings = "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z'/><circle cx='12' cy='12' r='3'/></svg>";
        String svgPlus = "<svg xmlns='http://www.w3.org/2000/svg' width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'><path d='M12 5v14'/><path d='M5 12h14'/></svg>";
        String svgBolt = "<svg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M13 2 3 14h9l-1 8 10-12h-9l1-8z'/></svg>";
        String svgHeart = "<svg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M12 21s-6.716-4.38-9.192-8.192C1.465 10.749 2 7.5 4.5 6A4.748 4.748 0 0 1 12 8.25 4.748 4.748 0 0 1 19.5 6c2.5 1.5 3.035 4.749 1.692 6.808C18.716 16.62 12 21 12 21Z'/></svg>";
        String svgStop = "<svg xmlns='http://www.w3.org/2000/svg' width='18' height='18' viewBox='0 0 24 24' fill='currentColor'><rect x='6' y='6' width='12' height='12' rx='3'/></svg>";
        String svgMic = "<svg xmlns='http://www.w3.org/2000/svg' width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'><path d='M12 3a3 3 0 0 1 3 3v6a3 3 0 1 1-6 0V6a3 3 0 0 1 3-3Z'/><path d='M19 10v2a7 7 0 0 1-14 0v-2'/><path d='M12 19v3'/><path d='M8 22h8'/></svg>";
        String svgEye = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z'/><circle cx='12' cy='12' r='3'/></svg>";
        String svgRobot = "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20' viewBox='0 0 24 24' fill='currentColor'><path d='M6,2V8H6V8L10,12L6,16V16H6V22H18V16H18V16L14,12L18,8V8H18V2H6M16,16.5V20H8V16.5L12,12.5L16,16.5M12,11.5L8,7.5V4H16V7.5L12,11.5Z'/></svg>";
        String svgPaperclip = "<svg xmlns='http://www.w3.org/2000/svg' width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48'/></svg>";
        String svgDownload = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4'/><polyline points='7 10 12 15 17 10'/><line x1='12' x2='12' y1='15' y2='3'/></svg>";
        String svgFile = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z'/><polyline points='14 2 14 8 20 8'/></svg>";

        if (!authed) {
            return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover'>"
                + "<meta name='apple-mobile-web-app-capable' content='yes'>"
                + "<meta name='apple-mobile-web-app-status-bar-style' content='black-translucent'>"
                + "<meta name='mobile-web-app-capable' content='yes'>"
                + "<meta name='theme-color' content='#faf8ff' media='(prefers-color-scheme: light)'>"
                + "<meta name='theme-color' content='#111319' media='(prefers-color-scheme: dark)'>"
                + "<title>OpenClaw Mini</title><style>"
                + baseCss()
                + "html,body{margin:0;padding:0;height:100%;height:100dvh;overflow:hidden;overscroll-behavior:none;touch-action:manipulation;position:fixed;width:100%;background:var(--bg);}body{background:var(--bg);color:var(--text);font-family:var(--font);display:flex;align-items:center;justify-content:center;}"
                + ".auth{width:min(94vw,360px);background:var(--panel);padding:28px;border-radius:24px;border:1px solid var(--line);box-shadow:var(--shadow);}"
                + "input{width:100%;box-sizing:border-box;border:1px solid var(--line);background:var(--field);color:var(--text);padding:15px 14px;border-radius:16px;outline:none;font-size:15px;}"
                + "input:focus{border-color:var(--accent);}"
                + ".actions{margin-top:14px;}"
                + "button{width:100%;background:var(--accent);color:var(--accentText);border:0;padding:15px;border-radius:16px;font-weight:700;font-size:15px;cursor:pointer;}"
                + "button:hover{opacity:0.92;}"
                + ".msg{margin-top:12px;color:#ef4444;font-size:13px;min-height:18px;text-align:center;}"
                + "</style></head><body>"
                + "<div class='auth'>"
                + "<input id='password' type='password' placeholder='Password' onkeydown='if(event.key===\"Enter\"){event.preventDefault();login();}'>"
                + "<div class='actions'><button onclick='login()'>Enter</button></div><div class='msg' id='msg'></div></div>"
                + "<script>async function login(){const fd=new URLSearchParams();fd.set('password',document.getElementById('password').value);const r=await fetch('/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:fd});const j=await r.json().catch(()=>({ok:false,error:'login_failed'}));if(j.ok){localStorage.setItem('openclaw_sid',j.sessionId||'');location.href='/?sid='+encodeURIComponent(j.sessionId||'');}else{document.getElementById('msg').textContent=j.error||'Login failed';}}</script>"
                + themeScript()
                + "</body></html>";
        }

        String safeSessionId = sessionId == null ? "" : sessionId.replace("\\", "\\\\").replace("'", "\\'");
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover'>"
            + "<meta name='apple-mobile-web-app-capable' content='yes'>"
            + "<meta name='apple-mobile-web-app-status-bar-style' content='black-translucent'>"
            + "<meta name='mobile-web-app-capable' content='yes'>"
            + "<meta name='theme-color' content='#faf8ff' media='(prefers-color-scheme: light)'>"
            + "<meta name='theme-color' content='#111319' media='(prefers-color-scheme: dark)'>"
            + "<title>OpenClaw Mini</title><style>"
            + baseCss()
            + "html{height:100%;height:-webkit-fill-available;background:var(--bg);}body{margin:0;padding:0;height:100%;height:-webkit-fill-available;overflow:hidden;overscroll-behavior:none;-webkit-overflow-scrolling:touch;touch-action:pan-y;background:var(--bg);color:var(--text);font-family:var(--font);}"
            + ".app{display:flex;height:100%;height:100dvh;overflow:hidden;}"
            + ".sidebar{width:350px;flex-shrink:0;background:var(--sidebar);border-right:1px solid var(--line);padding:calc(24px + env(safe-area-inset-top,0px)) 18px calc(24px + env(safe-area-inset-bottom,0px));display:flex;flex-direction:column;gap:18px;overflow-y:auto;-ms-overflow-style:none;scrollbar-width:none;}"
            + ".sidebar::-webkit-scrollbar{display:none;width:0;height:0;}"
            + ".main{flex:1;min-width:0;padding:calc(20px + env(safe-area-inset-top,0px)) 120px calc(50px + env(safe-area-inset-bottom,0px)) 120px;display:flex;flex-direction:column;gap:14px;overflow:hidden;}"
            + ".logo{display:flex;align-items:flex-start;justify-content:space-between;}.logo>div:first-child{padding-left:10px;}.logo h1{margin:0;font-size:24px;line-height:1.15;}.logo p{margin:8px 0 0;color:var(--muted);line-height:1.5;font-size:13px;}"
            + ".iconBtn{width:40px;height:40px;padding:0;display:flex;align-items:center;justify-content:center;background:var(--panel);border:1px solid var(--line);border-radius:12px;color:var(--text);cursor:pointer;flex-shrink:0;}"
            + ".iconBtn:hover{background:var(--panelAlt);}"
            + ".sun{display:block;}.moon{display:none;}html[data-theme='dark'] .sun{display:none;}html[data-theme='dark'] .moon{display:block;}"
            + ".navCard,.settingsCard{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:26px;box-shadow:var(--shadow);}"
            + ".navTitle{font-size:10px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);margin-bottom:8px;font-weight:600;}"
            + ".navItem{padding:12px 14px;border-radius:14px;background:var(--field);border:1px solid var(--line);}"
            + ".navItem strong{display:block;font-size:14px;margin-bottom:4px;}.navItem span{font-size:12px;color:var(--muted);line-height:1.5;}"
            + ".jsonBox{margin-top:12px;background:var(--field);border:1px solid var(--line);border-radius:14px;padding:12px;font:11px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;word-break:break-word;color:var(--muted);max-height:200px;overflow:auto;}"
            + ".settingsCard details{margin-top:8px;} .settingsCard summary{list-style:none;cursor:pointer;font-weight:500;font-size:13px;color:var(--muted);display:flex;align-items:center;gap:6px;} .settingsCard summary::-webkit-details-marker{display:none;} .settingsCard summary svg{width:16px;height:16px;opacity:0.6;}"
            + ".cardStatus{margin-left:auto;font-size:10px;line-height:1;color:var(--accent);font-weight:700;letter-spacing:.02em;}"
            + ".cardHead{display:flex;align-items:center;gap:8px;min-width:0;}"
            + ".cardHead .navTitle{margin-bottom:0;}"
            + ".configCard details+details,.settingsGroupCard details+details{margin-top:12px;padding-top:12px;border-top:1px solid var(--line);}"
            + ".settingsRow{display:flex;flex-direction:column;gap:8px;margin-top:10px;} .settingsRow input{border:1px solid var(--line);background:var(--field);color:var(--text);padding:10px;border-radius:10px;outline:none;font-size:13px;}"
            + ".settingsRow input:focus{border-color:var(--accent);}"
            + ".primaryBtn{background:var(--accent);color:var(--accentText);border:0;padding:10px;border-radius:10px;font-weight:600;font-size:13px;cursor:pointer;}"
            + ".primaryBtn:hover{opacity:0.92;}"
            + ".status{font-size:13px;color:var(--accent);margin-top:6px;}"
            + ".topbar{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap;}"
            + ".titleBlock{margin-left:10px;}.titleBlock h2{margin:0;font-size:26px;}.titleBlock p{margin:0;color:var(--muted);font-size:14px;}"
            + ".topActions{display:flex;gap:10px;align-items:center;}"
            + ".statusPill{padding:8px 14px;border-radius:999px;background:var(--accentSoft);font-size:12px;font-weight:700;display:flex;align-items:center;gap:6px;color:var(--accent);}.statusPill.st-ai{color:#a855f7;}.statusPill.st-ok{color:#22c55e;}.statusPill.st-warn{color:#eab308;}.statusPill.st-err{color:#ef4444;}.dot{width:8px;height:8px;border-radius:50%;background:currentColor;display:inline-block;}.dot.spin{animation:pulse .8s ease-in-out infinite;}@keyframes pulse{0%,100%{opacity:1;}50%{opacity:.3;}}"
            + ".ghostBtn{background:var(--panel);color:var(--text);border:1px solid var(--line);padding:10px 14px;border-radius:12px;font-weight:600;font-size:13px;cursor:pointer;display:flex;align-items:center;gap:6px;}"
            + ".ghostBtn:hover{background:var(--panelAlt);}"
            + ".chatShell{flex:1;background:var(--panel);border:1px solid var(--line);border-radius:24px;box-shadow:var(--shadow);display:flex;flex-direction:column;min-height:0;max-height:100%;overflow:hidden;}"
            + ".messages{flex:1;overflow-y:auto;padding:20px;display:flex;flex-direction:column;gap:12px;background:linear-gradient(180deg,var(--panelAlt),var(--panel));}"
            + ".bubble{padding:12px 16px;border-radius:18px;line-height:1.5;max-width:min(72ch,85%);word-break:break-word;font-size:15px;}"
            + ".bubble h1{font-size:1.4em;margin:16px 0 8px;font-weight:700;border-bottom:1px solid var(--line);padding-bottom:6px;}"
            + ".bubble h2{font-size:1.2em;margin:14px 0 6px;font-weight:600;}"
            + ".bubble h3{font-size:1.05em;margin:12px 0 4px;font-weight:600;}"
            + ".bubble h4,.bubble h5,.bubble h6{font-size:1em;margin:10px 0 4px;font-weight:600;color:var(--muted);}"
            + ".bubble ul,.bubble ol{margin:8px 0;padding-left:20px;}"
            + ".bubble li{margin:4px 0;}"
            + ".bubble strong{font-weight:600;}"
            + ".bubble em{font-style:italic;}"
            + ".bubble blockquote{margin:8px 0;padding:8px 12px;border-left:3px solid var(--accent);background:var(--panelAlt);border-radius:0 8px 8px 0;}"
            + ".bubble hr{border:none;border-top:1px solid var(--line);margin:12px 0;}"
            + ".bubble.user{white-space:pre-wrap;}"
            + ".bubble.bot{white-space:normal;}"
            + ".bubble p{margin:0 0 8px;}.bubble p:last-child{margin-bottom:0;}"
            + ".bubble :not(pre)>code{font-family:'Monaco','Menlo','Consolas',monospace;font-size:.84em;padding:2px 6px;border-radius:6px;background:var(--codeTop);color:var(--codeText);}"
            + ".bubble .imgWrap{max-height:450px;overflow-y:auto;border-radius:8px;}"
            + ".bubble img{max-width:100%;display:block;cursor:pointer;}"
            + ".codeBlock{margin:10px 0;border:1px solid var(--codeLine);border-radius:12px;overflow:hidden;background:var(--codeBg);box-shadow:inset 0 1px 0 rgba(255,255,255,.04);}"
            + ".codeBlockHead{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:7px 10px;background:var(--codeTop);border-bottom:1px solid var(--codeLine);}"
            + ".codeLang{font-size:10px;line-height:1;letter-spacing:.08em;text-transform:uppercase;color:var(--codeMuted);font-weight:700;}"
            + ".codeCopyBtn{width:26px;height:26px;padding:0;border:1px solid var(--codeLine);border-radius:8px;background:rgba(255,255,255,.35);color:var(--codeMuted);display:flex;align-items:center;justify-content:center;cursor:pointer;transition:background .15s,color .15s,border-color .15s;}"
            + "html[data-theme='dark'] .codeCopyBtn{background:rgba(255,255,255,.04);}"
            + ".codeCopyBtn:hover{background:var(--panel);color:var(--codeText);border-color:var(--muted);}"
            + ".codeCopyBtn.copied{color:#22c55e;border-color:rgba(34,197,94,.4);}"
            + ".codeCopyBtn svg{width:14px;height:14px;display:block;flex-shrink:0;stroke:currentColor;fill:none;}"
            + ".codeBlock pre{margin:0;padding:12px 14px;background:var(--codeBg);color:var(--codeText);font-family:'Monaco','Menlo','Consolas',monospace;font-size:11px;line-height:1.6;overflow:auto;white-space:pre;word-break:normal;tab-size:2;}"
            + ".codeBlock pre code{display:block;font-family:inherit;color:inherit;background:none;padding:0;font-size:inherit;}"
            + ".hljs{background:transparent!important;color:inherit!important;}"
            + ".user{align-self:flex-end;background:var(--userBubble);color:var(--userText);box-shadow:var(--userShadow);}"
            + ".bot{align-self:flex-start;background:var(--botBubble);color:var(--text);}"
            + ".toolCallGroup{display:flex;flex-direction:column;gap:4px;max-width:min(72ch,85%);width:100%;align-self:flex-start;align-items:flex-start;}"
            + ".toolCall{margin:4px 0;border:1px solid var(--line);border-radius:12px;overflow:hidden;background:var(--field);max-width:100%;}"
            + ".bubble.toolCallBubble{background:transparent;padding:0;box-shadow:none;}"
            + ".bubble.toolCallBubble .toolCallGroup{max-width:100%;}"
            + ".toolCall summary{display:flex;align-items:center;gap:8px;padding:10px 14px;cursor:pointer;font-size:13px;font-weight:500;color:var(--muted);list-style:none;}"
            + ".toolCall summary::-webkit-details-marker{display:none;}"
            + ".toolCall summary svg{color:var(--accent);flex-shrink:0;}"
            + ".toolCall summary::after{content:'';margin-left:auto;border:solid var(--muted);border-width:0 2px 2px 0;padding:3px;transform:rotate(-45deg);transition:transform .2s;}"
            + ".toolCall[open] summary::after{transform:rotate(45deg);}"
            + ".toolCall pre{margin:0;padding:12px 14px;font-size:12px;overflow:auto;max-height:300px;background:var(--panelAlt);border-top:1px solid var(--line);font-family:'Monaco','Menlo','Consolas',monospace;color:var(--text);white-space:pre-wrap;word-break:break-word;}"
            + ".toolCall code{font-family:inherit;}"
            + ".skillItem{display:flex;align-items:center;gap:8px;padding:4px 0;cursor:pointer;font-size:13px;}"
            + ".skillItem input{width:14px;height:14px;cursor:pointer;accent-color:var(--accent);}"
            + ".skillItem span{color:var(--text);}"
            + ".skillDivider{font-size:10px;color:var(--muted);margin:8px 0 4px;padding-top:8px;border-top:1px solid var(--line);text-transform:uppercase;letter-spacing:0.5px;}"
            + ".settingsRow.compact{gap:6px;}"
            + ".settingsRow.compact select{padding:6px 8px;font-size:12px;border-radius:6px;border:1px solid var(--line);background:var(--field);color:var(--text);cursor:pointer;appearance:none;background-image:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E\");background-repeat:no-repeat;background-position:right 6px center;padding-right:24px;}"
            + ".settingsRow.compact input{padding:6px 8px;font-size:12px;border-radius:6px;border:1px solid var(--line);background:var(--field);color:var(--text);}"
            + ".fieldGroup{display:flex;flex-direction:column;gap:4px;}"
            + ".fieldLabel{font-size:11px;font-weight:600;color:var(--muted);letter-spacing:.02em;}"
            + ".fieldHint{font-size:10px;line-height:1.45;color:var(--muted);opacity:.9;}"
            + ".inputWrap{position:relative;width:100%;}"
            + ".inputWrap::after{content:'';position:absolute;top:1px;right:24px;bottom:1px;width:24px;border-radius:0 6px 6px 0;background:linear-gradient(90deg,rgba(0,0,0,0),var(--field) 70%);pointer-events:none;z-index:2;}"
            + ".inputWrap input{width:100%;padding-right:42px;position:relative;z-index:1;}"
            + ".eyeBtn{position:absolute;right:5px;top:50%;transform:translateY(-50%);width:22px;height:22px;padding:0;border:1px solid rgba(100,116,139,.18);border-radius:999px;background:var(--panel);color:var(--muted);display:flex;align-items:center;justify-content:center;cursor:pointer;z-index:3;opacity:.92;box-shadow:0 1px 2px rgba(15,23,42,.08);}"
            + ".eyeBtn:hover{color:var(--text);opacity:1;}"
            + ".eyeBtn svg{width:16px;height:16px;}"
            + ".settingsRow.compact input:focus,.settingsRow.compact select:focus{outline:none;border-color:var(--accent);}"
            + ".compactBtn{padding:6px 12px;font-size:12px;border-radius:6px;border:none;background:var(--accent);color:var(--accentText);cursor:pointer;transition:opacity .15s;}"
            + ".compactBtn:hover{opacity:0.85;}"
            + ".inlineActions{display:flex;gap:6px;align-items:center;justify-content:flex-end;flex-wrap:wrap;}"
            + ".inlineMiniBtn{padding:5px 10px;font-size:11px;line-height:1;border-radius:999px;border:1px solid var(--accent);background:var(--accent);color:var(--accentText);cursor:pointer;transition:opacity .15s,transform .15s;}"
            + ".inlineMiniBtn:hover{opacity:.9;transform:translateY(-1px);}"
            + ".inlineMiniBtn.primary{background:var(--accent);border-color:var(--accent);color:var(--accentText);}"
            + ".sectionDivider{margin:4px 0 2px;padding-top:8px;border-top:1px solid var(--line);display:flex;flex-direction:column;gap:2px;}"
            + ".sectionDivider strong{font-size:11px;line-height:1.2;color:var(--text);}"
            + ".sectionDivider span{font-size:10px;line-height:1.4;color:var(--muted);}"
            + ".skillTabs{display:flex;gap:4px;margin-bottom:8px;}"
            + ".skillTab{padding:4px 10px;font-size:11px;color:var(--muted);cursor:pointer;border-radius:6px;transition:all .15s;}"
            + ".skillTab:hover{background:var(--field);}"
            + ".skillTab.active{background:var(--accent);color:var(--accentText);}"
            + ".skillPanel{}"
            + ".cronList{display:flex;flex-direction:column;gap:6px;margin-top:8px;}"
            + ".cronItem{display:flex;align-items:flex-start;gap:10px;padding:8px 10px;font-size:12px;color:var(--text);background:var(--field);border:1px solid var(--line);border-radius:12px;}"
            + ".cronBody{display:flex;flex-direction:column;gap:3px;min-width:0;flex:1;}"
            + ".cronNameRow{display:flex;align-items:center;justify-content:space-between;gap:8px;}"
            + ".cronName{font-size:12px;font-weight:600;color:var(--text);}"
            + ".cronBadge{padding:2px 8px;border-radius:999px;background:var(--accentSoft);color:var(--accent);font-size:10px;font-weight:700;line-height:1.2;flex-shrink:0;}"
            + ".cronMeta{font-size:11px;line-height:1.45;color:var(--muted);word-break:break-word;}"
            + ".workspaceDebug{display:flex;flex-direction:column;gap:8px;margin-top:10px;}"
            + ".workspaceGrid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;}"
            + ".workspaceStat{padding:8px 10px;background:var(--field);border:1px solid var(--line);border-radius:12px;min-width:0;}"
            + ".workspaceStatLabel{font-size:10px;line-height:1.2;color:var(--muted);text-transform:uppercase;letter-spacing:.04em;margin-bottom:4px;}"
            + ".workspaceStatValue{font-size:12px;line-height:1.35;color:var(--text);font-weight:600;word-break:break-word;max-height:48px;overflow-y:auto;}"
            + ".workspaceJson{margin-top:0;max-height:140px;font-size:10px;line-height:1.5;padding:10px 11px;border-radius:12px;}"
            + ".composerWrap{padding:12px 16px;border-top:1px solid var(--line);background:var(--panel);}"
            + ".composer{position:relative;height:120px;padding:16px;background:var(--field);border-radius:24px;transition:border-color 0.15s;border:2px solid transparent;}"
            + ".composer.dragover{border-color:var(--accent);background:var(--accentSoft);}"
            + ".composer textarea{display:block;width:100%;height:100%;border:0;background:transparent;color:var(--text);padding:0 96px 0 8px;outline:none;font-size:16px;line-height:1.4;resize:none;overflow:auto;}"
            + ".composer textarea::placeholder{color:var(--muted);}"
            + ".composerBtns{position:absolute;right:14px;bottom:14px;display:flex;align-items:center;gap:8px;}"
            + ".composer button.sendBtn{width:34px;height:34px;padding:0;display:flex;align-items:center;justify-content:center;background:var(--accent);color:var(--accentText);border:0;border-radius:50%;cursor:pointer;transition:opacity 0.15s,border-radius .15s,transform .15s,background .15s,box-shadow .15s;}"
            + ".composer button.attachBtn{width:34px;height:34px;padding:0;display:flex;align-items:center;justify-content:center;background:transparent;color:var(--muted);border:0;border-radius:50%;cursor:pointer;transition:color 0.15s,background 0.15s,box-shadow 0.15s;}"
            + ".composer button.attachBtn:hover{color:var(--accent);}"
            + ".composer button.attachBtn.recording{color:#d92d20;}"
            + ".composer button.sendBtn.busy{border-radius:50%;transform:none;}"
            + ".composer button.sendBtn.recording{background:#d92d20;box-shadow:0 0 0 4px rgba(217,45,32,.16);}"
            + ".composer button.sendBtn:hover{opacity:0.85;}"
            + ".composer button.sendBtn svg{width:18px;height:18px;}"
            + ".composer button.attachBtn svg{width:18px;height:18px;}"
            + ".attachPreview{position:absolute;left:14px;bottom:14px;display:flex;align-items:center;gap:6px;max-width:calc(100% - 120px);}"
            + ".attachItem{display:flex;align-items:center;gap:4px;padding:4px 8px;background:var(--attachBg);border:1px solid var(--line);border-radius:8px;font-size:11px;color:var(--text);max-width:120px;}"
            + ".attachItem span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}"
            + ".attachItem button{width:16px;height:16px;padding:0;background:transparent;border:0;color:var(--muted);cursor:pointer;display:flex;align-items:center;justify-content:center;}"
            + ".attachItem button:hover{color:var(--text);}"
            + ".attachItem img{width:24px;height:24px;object-fit:cover;border-radius:4px;}"
            + ".userAttachments{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px;}"
            + ".userAttachItem{border-radius:8px;overflow:hidden;}"
            + ".userAttachItem img{max-width:200px;max-height:150px;object-fit:cover;border-radius:8px;cursor:pointer;}"
            + ".userAttachItem.file{padding:6px 10px;background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.2);font-size:12px;}"
            + ".bubble.user p{margin:0;}"
            + ".meta{padding:5px 25px 0;color:var(--muted);font-size:12px;}"
            + ".footer{display:none;text-align:center;color:var(--muted);font-size:11px;padding:6px 0;flex-shrink:0;}"
            + ".menuBtn{display:none;}"
            + ".overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.5);z-index:90;}"
            + ".installHint{display:none;position:fixed;left:12px;right:12px;top:calc(12px + env(safe-area-inset-top,0px));z-index:120;align-items:flex-start;gap:10px;padding:12px 14px;border-radius:16px;background:var(--panel);border:1px solid var(--line);box-shadow:var(--shadow);}"
            + ".installHint.show{display:flex;}"
            + ".installHintIcon{width:34px;height:34px;flex-shrink:0;border-radius:12px;background:var(--accentSoft);color:var(--accent);display:flex;align-items:center;justify-content:center;}"
            + ".installHintBody{min-width:0;flex:1;}"
            + ".installHintTitle{font-size:13px;font-weight:700;color:var(--text);line-height:1.3;}"
            + ".installHintText{margin-top:4px;font-size:12px;line-height:1.5;color:var(--muted);}"
            + ".installHintActions{display:flex;align-items:center;gap:8px;margin-top:10px;}"
            + ".installHintBtn{padding:6px 10px;border-radius:999px;border:1px solid var(--line);background:var(--field);color:var(--text);font-size:12px;font-weight:600;cursor:pointer;}"
            + ".installHintBtn.primary{background:var(--accent);border-color:var(--accent);color:var(--accentText);}"
            + ".installHintBtn:hover{opacity:.88;}"
            + "@media (max-width:768px){"
            + "html,body{height:100%;height:100dvh;height:-webkit-fill-available;overflow:hidden;}"
            + ".app{height:100vh;height:100dvh;}"
            + ".sidebar{position:fixed;left:0;top:0;bottom:0;z-index:100;transform:translateX(-100%);transition:transform 0.25s ease;}"
            + ".sidebar.open{transform:translateX(0);}"
            + ".overlay.open{display:block;}"
            + ".menuBtn{display:flex;}"
            + ".main{padding:calc(12px + env(safe-area-inset-top,0px)) 12px env(safe-area-inset-bottom,0px);height:100%;min-height:100%;overflow:hidden;}"
            + ".topbar{flex-wrap:nowrap;padding-bottom:8px;}"
            + ".titleBlock{flex:1;min-width:0;margin-left:8px;}"
            + ".titleBlock h2{font-size:18px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}"
            + ".titleBlock p{display:none;}"
            + ".topActions{flex-shrink:0;}"
            + ".statusPill{padding:6px 10px;font-size:11px;}"
            + ".ghostBtn{padding:8px 10px;font-size:12px;}"
            + ".ghostBtn svg{width:16px;height:16px;}"
            + ".chatShell{flex:1;min-height:0;border-radius:18px;}"
            + ".composerWrap{padding:10px 12px;}"
            + ".meta{padding:0 12px 4px;}"
            + ".footer{display:none;}"
            + ".installHint{left:12px;right:12px;top:calc(8px + env(safe-area-inset-top,0px));}"
            + "}"
            + "</style></head><body>"
            + "<div class='overlay' id='overlay' onclick='closeSidebar()'></div>"
            + "<div class='app'>"
            + "<aside class='sidebar' id='sidebar'>"
            + "<div class='logo'><div><h1>OpenClaw(Mini)</h1><p>Your own personal AI assistant. Any OS. Any Platform. The lobster way.</p></div>"
            + "<button class='iconBtn' onclick='toggleTheme()' title='Toggle theme'><span class='sun'>" + svgSun + "</span><span class='moon'>" + svgMoon + "</span></button>"
            + "</div>"
            + "<div class='settingsCard configCard'><div class='navTitle'>Configuration</div>"
            + "<details><summary>" + svgRobot + " API<span class='cardStatus' id='apiStatus'></span></summary><div class='settingsRow compact'>"
            + "<div class='sectionDivider'><strong>Provider Profile</strong><span>Switch between saved provider setups without retyping URL, key, and limits.</span></div>"
            + "<div class='fieldGroup'><div class='fieldLabel'>Current Profile</div><select id='cfgActiveProfile' onchange='switchActiveProfile()'></select></div>"
            + "<div class='fieldGroup'><div class='fieldLabel'>Profile Name</div><input id='cfgProfileName' placeholder='Give this setup a clear name' oninput='renameCurrentProfile()'><div class='fieldHint'>Edit saves automatically. Add creates a new blank profile.</div></div>"
            + "<div class='inlineActions'><button class='inlineMiniBtn' type='button' onclick='createProfileDraft()'>Add</button><button class='inlineMiniBtn' type='button' onclick='deleteCurrentProfile()'>Delete</button></div>"
            + "<div class='sectionDivider'><strong>Connection</strong><span>These fields belong to the currently selected profile.</span></div>"
            + "<input id='cfgModel' placeholder='Model' oninput='saveConfig()'>"
            + "<input id='cfgApiUrl' placeholder='API URL' oninput='saveConfig()'>"
            + "<div class='inputWrap'><input id='cfgApiKey' type='password' placeholder='API Key' oninput='saveConfig()'><button class='eyeBtn' type='button' onclick='toggleApiKey()' aria-label='Toggle API key visibility'>" + svgEye + "</button></div>"
            + "<div class='fieldGroup'><div class='fieldLabel'>Protocol</div><select id='cfgProvider' onchange='saveConfig()'><option value='openai'>OpenAI</option><option value='anthropic'>Anthropic</option></select></div>"
            + "<div class='fieldGroup'><div class='fieldLabel'>Max Tokens</div><input id='cfgMaxTokens' type='number' min='256' max='32768' step='256' placeholder='4096' oninput='saveConfig()'></div>"
            + "<div class='fieldGroup'><div class='fieldLabel'>Tool Call Limit</div><input id='cfgMaxToolIterations' type='number' min='1' max='100' step='1' placeholder='30' oninput='saveConfig()'></div>"
            + "</div></details>"
            + "<details><summary>" + svgBolt + " Skills<span class='cardStatus' id='skillStatus'></span></summary><div class='settingsRow compact' id='skillControlList'></div></details>"
            + "<details><summary>" + svgHeart + " Timers<span class='cardStatus' id='cronStatus'></span></summary><div class='settingsRow compact' id='cronList'></div></details></div>"
            + "<div class='settingsCard settingsGroupCard'><div class='navTitle'>Settings</div>"
            + "<details><summary>" + svgSettings + " Display</summary><div class='settingsRow compact'><label class='skillItem'><input type='checkbox' id='showToolCalls' onchange='toggleToolCalls()'><span>Show tool calls</span></label></div></details>"
            + "<details><summary>" + svgSettings + " Password<span class='cardStatus' id='passwordStatus'></span></summary><div class='settingsRow compact'><input id='currentPassword' type='password' placeholder='Current'><input id='newPassword' type='password' placeholder='New'><button class='compactBtn' onclick='updatePassword()'>Update</button></div></details></div>"
            + "<div class='settingsCard'><div class='cardHead'><div class='navTitle'>Workspace</div><span class='cardStatus' id='workspaceStatus'></span></div><div class='workspaceDebug'><div class='workspaceGrid'>"
            + "<div class='workspaceStat'><div class='workspaceStatLabel'>Tokens</div><div class='workspaceStatValue' id='wsTokens'>0</div></div>"
            + "<div class='workspaceStat'><div class='workspaceStatLabel'>Model</div><div class='workspaceStatValue' id='wsModel'>-</div></div>"
            + "<div class='workspaceStat'><div class='workspaceStatLabel'>History</div><div class='workspaceStatValue' id='wsHistory'>0</div></div>"
            + "<div class='workspaceStat'><div class='workspaceStatLabel'>Error</div><div class='workspaceStatValue' id='wsError'>None</div></div>"
            + "</div><div class='jsonBox workspaceJson' id='statusJson'>Loading...</div></div></div>"
            + "</aside>"
            + "<main class='main'>"
            + "<div class='installHint' id='installHint'><div class='installHintIcon'>" + svgPlus + "</div><div class='installHintBody'><div class='installHintTitle'>Add OpenClaw to Home Screen</div><div class='installHintText' id='installHintText'>Open Share, then tap Add to Home Screen.</div><div class='installHintActions'><button class='installHintBtn primary' type='button' onclick='dismissInstallHint()'>OK</button><button class='installHintBtn' type='button' onclick='neverShowInstallHint()'>Hide</button></div></div></div>"
            + "<div class='topbar'>"
            + "<div style='display:flex;align-items:center;gap:12px;'>"
            + "<button class='iconBtn menuBtn' onclick='openSidebar()' title='Menu'>" + svgMenu + "</button>"
            + "<div class='titleBlock'><h2>Chat Window</h2><p>AI assistant " + BuildInfo.VERSION + "</p></div>"
            + "</div>"
            + "<div class='topActions'><span class='statusPill' id='chatStatus'>Connecting</span><button class='ghostBtn' onclick='reloadHistory()'>" + svgRefresh + " Refresh</button></div>"
            + "</div>"
            + "<section class='chatShell'><div class='messages' id='messages'></div><div class='composerWrap'><div class='composer'><textarea id='message' placeholder='Type a message...' onkeydown='if(event.key===\"Enter\"&&!event.shiftKey){event.preventDefault();sendChat();}'></textarea><div class='attachPreview' id='attachPreview'></div><input type='file' id='fileInput' style='display:none' accept='image/*,text/*,.pdf,.json,.xml,.csv,.md,.zip,.tar,.gz,.7z,.rar' multiple onchange='handleFiles(this.files)'><div class='composerBtns'><button class='attachBtn' onclick='document.getElementById(\"fileInput\").click()' title='Attach file'>" + svgPaperclip + "</button><button id='micBtn' class='attachBtn' title='Voice input'>" + svgMic + "</button><button id='sendBtn' class='sendBtn' onclick='sendOrStop(event)' title='Send'>" + svgSend + "</button></div></div><div class='meta' id='meta'></div></div></section>"
            + "</main></div>"
            + "<script src='https://cdn.jsdelivr.net/npm/json5@2/dist/index.min.js'></script>"
            + "<script src='https://cdn.jsdelivr.net/npm/marked/marked.min.js'></script>"
            + "<script>marked.setOptions({breaks:true,gfm:true});</script>"
            + "<link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/highlight.js@11.10.0/styles/github.min.css'>"
            + "<script src='https://cdn.jsdelivr.net/npm/highlight.js@11.10.0/highlight.min.js'></script>"
            + "<link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/viewerjs/dist/viewer.min.css'>"
            + "<script src='https://cdn.jsdelivr.net/npm/viewerjs/dist/viewer.min.js'></script>"
            + themeScript()
            + "<script>"
            + "const SESSION_ID=(function(){const fromServer='" + safeSessionId + "';const saved=localStorage.getItem('openclaw_sid')||'';const sid=fromServer||saved;if(sid){localStorage.setItem('openclaw_sid',sid);}return sid;})();"
            + "function apiUrl(path){return SESSION_ID?path+(path.indexOf('?')>=0?'&':'?')+'sid='+encodeURIComponent(SESSION_ID):path;}"
            + "function apiHeaders(base){const h=base||{};if(SESSION_ID){h['X-OpenClaw-Session']=SESSION_ID;}return h;}"
            + "let loadingHistory=false,shownMsgs=new Set();"
            + "let showToolCalls=(function(){const v=localStorage.getItem('openclaw_showToolCalls');return v!=='false';})();"
            + "function toggleToolCalls(){showToolCalls=document.getElementById('showToolCalls').checked;localStorage.setItem('openclaw_showToolCalls',showToolCalls);applyToolCallVisibility();}"
            + "function applyToolCallVisibility(){const show=showToolCalls;document.querySelectorAll('.toolCall').forEach(el=>{el.style.display=show?'':'none';});document.querySelectorAll('.toolCallGroup').forEach(g=>{g.style.display=show?'':'none';});document.querySelectorAll('.toolCallBubble').forEach(b=>{b.style.display=show?'':'none';});}"
            + "(function(){const cb=document.getElementById('showToolCalls');if(cb)cb.checked=showToolCalls;})();"
            + "function openSidebar(){document.getElementById('sidebar').classList.add('open');document.getElementById('overlay').classList.add('open');}"
            + "function closeSidebar(){document.getElementById('sidebar').classList.remove('open');document.getElementById('overlay').classList.remove('open');}"
            + "function isStandalone(){return window.matchMedia('(display-mode: standalone)').matches||window.navigator.standalone===true;}"
            + "function isIos(){return /iphone|ipad|ipod/i.test(navigator.userAgent||'')||((/macintosh/i.test(navigator.userAgent||''))&&('ontouchend' in document));}"
            + "function isMobileViewport(){return window.matchMedia('(max-width: 768px)').matches;}"
            + "function dismissInstallHint(){const el=document.getElementById('installHint');if(el)el.classList.remove('show');sessionStorage.setItem('openclaw_install_hint_dismissed','1');}"
            + "function neverShowInstallHint(){const el=document.getElementById('installHint');if(el)el.classList.remove('show');localStorage.setItem('openclaw_install_hint_hidden','1');}"
            + "function maybeShowInstallHint(){const el=document.getElementById('installHint');const text=document.getElementById('installHintText');if(!el||!text)return;const hidden=localStorage.getItem('openclaw_install_hint_hidden')==='1';const dismissed=sessionStorage.getItem('openclaw_install_hint_dismissed')==='1';if(hidden||dismissed||isStandalone()||!isMobileViewport()){el.classList.remove('show');return;}if(isIos()){text.textContent='Tap Share in Safari, then choose Add to Home Screen.';el.classList.add('show');return;}text.textContent='Use your browser menu, then choose Add to Home Screen.';el.classList.add('show');}"
            + "function bubbleClass(role){return role==='user'?'user':'bot';}"
            + "function msgKey(role,text,msgId){if(msgId!==undefined&&msgId!==null&&msgId!==''){return role+':'+msgId+':'+(text||'');}return role+':'+(text||'');}"
            + "let viewer=null;function initViewer(){if(viewer)viewer.destroy();const box=document.getElementById('messages');if(typeof Viewer!=='undefined')viewer=new Viewer(box,{navbar:false,toolbar:{zoomIn:1,zoomOut:1,oneToOne:1,reset:1,rotateLeft:1,rotateRight:1}});}"
            + "function highlightCodeBlocks(root){if(typeof hljs==='undefined')return;(root||document).querySelectorAll('.codeBlock pre code').forEach(code=>{try{hljs.highlightElement(code);}catch(e){}});}"
            + "function parseStructuredContent(text){try{if(text==null)return null;if(Array.isArray(text))return text;if(typeof text==='object'){if(Array.isArray(text.content))return text.content;return null;}const raw=String(text);const trimmed=raw.trim();if(!trimmed||trimmed.length>500000)return null;if(!trimmed.startsWith('['))return null;if(!trimmed.includes('type'))return null;try{const parsed=(typeof JSON5!=='undefined')?JSON5.parse(trimmed):JSON.parse(trimmed);if(Array.isArray(parsed)&&parsed.length>0&&parsed[0]&&parsed[0].type)return parsed;}catch(e){}return null;}catch(e){return null;}}"
            + "function isToolOnlyContent(text){const j=parseStructuredContent(text);return Array.isArray(j)&&j.length>0&&j.every(x=>x.type==='tool_use'||x.type==='tool_result')&&!j.some(x=>x.type==='text');}"
            + "function isToolResultOnly(text){const j=parseStructuredContent(text);return Array.isArray(j)&&j.length>0&&j.every(t=>t.type==='tool_result');}"
            + "function looksLikeToolResult(text){if(!text)return false;const t=text.trim();return t.includes('tool_result')||t.includes('tool_use_id')||/^\\[\\{.*\"type\"/.test(t);}"
            + "function renderUserContent(text){if(!text)return'';const imgRe=/<image_data>([^<]+)<\\/image_data>/g;const images=[];let m;while((m=imgRe.exec(text))!==null){let url=m[1].trim();if(!url.startsWith('data:'))url='data:'+url;images.push(url);}let cleaned=text.replace(/<image_data>[^<]+<\\/image_data>/g,'').replace(/\\[IMAGE:[^\\]]*\\]\\n?/g,'').replace(/\\[FILE:[^\\]]*\\]\\n?/g,'').trim();let result='';if(images.length>0){result+='<div class=\"userAttachments\">';images.forEach(u=>{result+='<div class=\"userAttachItem\"><img src=\"'+u+'\"/></div>';});result+='</div>';}if(cleaned){result+=escapeHtml(cleaned).replace(/\\n/g,'<br>');}return result;}"
            + "function appendBubbleNode(cls,html){if(!html)return;const el=document.createElement('div');el.className='bubble '+cls;el.innerHTML=(cls==='user')?renderUserContent(html):html;document.getElementById('messages').appendChild(el);}"
            + "function appendToolGroupNode(cls,html){if(!html)return;const bubble=document.createElement('div');bubble.className='bubble '+cls+' toolCallBubble';const container=document.createElement('div');container.className='toolCallGroup';container.innerHTML=html;bubble.appendChild(container);document.getElementById('messages').appendChild(bubble);}"
            + "function addStructuredBubble(blocks,cls){if(!Array.isArray(blocks)||blocks.length===0)return false;let textParts=[];let toolParts=[];const flushText=()=>{if(textParts.length===0)return;appendBubbleNode(cls,renderPlainContent(textParts.join('\\n\\n')));textParts=[];};const flushTools=()=>{if(toolParts.length===0)return;appendToolGroupNode(cls,renderToolBlocks(toolParts));toolParts=[];};blocks.forEach(t=>{if(t.type==='text'){const value=(t.text||'').trim();if(value&&value!=='null'){flushTools();textParts.push(value);}}else if(t.type==='tool_use'||t.type==='tool_result'){flushText();toolParts.push(t);}});flushText();flushTools();return true;}"
            + "function addBubble(text,cls,skipDup,msgId){const rawText=typeof text==='string'?text:(text==null?'':JSON.stringify(text));const key=msgKey(cls,rawText||'',msgId);if(!skipDup&&shownMsgs.has(key))return;shownMsgs.add(key);if(cls!=='user'){const structured=parseStructuredContent(rawText);if(structured&&structured.length>0){if(addStructuredBubble(structured,cls)){document.getElementById('messages').scrollTop=1e9;highlightCodeBlocks(document.getElementById('messages'));initViewer();applyToolCallVisibility();return;}}}appendBubbleNode(cls,cls==='user'?(rawText||''):renderContent(rawText||''));document.getElementById('messages').scrollTop=1e9;highlightCodeBlocks(document.getElementById('messages'));initViewer();applyToolCallVisibility();}"
            + "function addBubbleHtml(html,cls){if(!html)return;const el=document.createElement('div');el.className='bubble '+cls;el.innerHTML=html;document.getElementById('messages').appendChild(el);document.getElementById('messages').scrollTop=1e9;highlightCodeBlocks(el);initViewer();}"
            + "function fileUrl(p){return p.startsWith('/')?apiUrl('/api/file?path='+encodeURIComponent(p)):p;}"
            + "const svgBolt='<svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M13 2 3 14h9l-1 8 10-12h-9l1-8z\"/></svg>';"
            + "const svgHeart='<svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M12 21s-6.716-4.38-9.192-8.192C1.465 10.749 2 7.5 4.5 6A4.748 4.748 0 0 1 12 8.25 4.748 4.748 0 0 1 19.5 6c2.5 1.5 3.035 4.749 1.692 6.808C18.716 16.62 12 21 12 21Z\"/></svg>';"
            + "const svgDownload='" + svgDownload.replace("\\", "\\\\").replace("'", "\\'") + "';"
            + "const svgFileIcon='" + svgFile.replace("\\", "\\\\").replace("'", "\\'") + "';"
            + "const svgCopy='<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"9\" y=\"9\" width=\"11\" height=\"11\" rx=\"2\" ry=\"2\"/><path d=\"M5 15V6a2 2 0 0 1 2-2h9\"/></svg>';"
            + "const svgSendIcon='" + svgSend.replace("\\", "\\\\").replace("'", "\\'") + "';"
            + "const svgStopIcon='" + svgStop.replace("\\", "\\\\").replace("'", "\\'") + "';"
            + "const svgMicIcon='" + svgMic.replace("\\", "\\\\").replace("'", "\\'") + "';"
            + "let toolCallId=0;"
            + "function renderToolCall(arr){let h='';arr.forEach(t=>{if(t.type==='tool_use'){const id='tc_'+(++toolCallId);h+='<details class=\"toolCall\" id=\"'+id+'\"><summary onclick=\"event.stopPropagation()\">'+svgBolt+' '+escapeHtml(t.name||'tool')+'</summary><pre>'+escapeHtml(JSON.stringify(t.input||{},null,2))+'</pre></details>';}});return h;}"
            + "function truncate(s,n){return s.length>n?s.substring(0,n)+'... (truncated)':s;}"
            + "function renderToolBlocks(blocks){let h='';(blocks||[]).forEach(t=>{try{if(t.type==='tool_use'){const id='tc_'+(++toolCallId);const name=escapeHtml(String(t.name||'tool'));let inputStr='';try{inputStr=JSON.stringify(t.input||{},null,2);}catch(e){inputStr=String(t.input||'{}');}h+='<details class=\"toolCall\" id=\"'+id+'\"><summary onclick=\"event.stopPropagation()\">'+svgBolt+' '+name+'</summary><pre><code>'+escapeHtml(truncate(inputStr,4000))+'</code></pre></details>';}if(t.type==='tool_result'){const id='tc_'+(++toolCallId);let c='';try{c=typeof t.content==='string'?t.content:JSON.stringify(t.content,null,2);}catch(e){c=String(t.content||'');}h+='<details class=\"toolCall\" id=\"'+id+'\"><summary onclick=\"event.stopPropagation()\">'+svgBolt+' Result</summary><pre><code>'+escapeHtml(truncate(c,4000))+'</code></pre></details>';}}catch(e){console.error('renderToolBlocks error',e);}});return h;}"
            + "function decorateCodeBlocks(html){if(!html)return'';return html.replace(/<pre><code(?: class=\"([^\"]*)\")?>([\\s\\S]*?)<\\/code><\\/pre>/g,(m,cls,code)=>{let lang='Code';if(cls){const match=cls.match(/language-([^\\s\"]+)/);if(match&&match[1])lang=match[1];}const safeLang=escapeHtml(lang);const safeCls=cls?(' class=\"'+cls+'\"'):'';return '<div class=\"codeBlock\"><div class=\"codeBlockHead\"><span class=\"codeLang\">'+safeLang+'</span><button class=\"codeCopyBtn\" type=\"button\" onclick=\"copyCode(this)\" aria-label=\"Copy code\">'+svgCopy+'</button></div><pre><code'+safeCls+'>'+code+'</code></pre></div>';});}"
            + "async function copyCode(btn){const block=btn&&btn.closest('.codeBlock');const code=block?block.querySelector('code'):null;const text=code?code.textContent:'';if(!text)return;try{if(navigator.clipboard&&navigator.clipboard.writeText){await navigator.clipboard.writeText(text);}else{const area=document.createElement('textarea');area.value=text;area.setAttribute('readonly','');area.style.position='fixed';area.style.opacity='0';document.body.appendChild(area);area.select();document.execCommand('copy');document.body.removeChild(area);}btn.classList.add('copied');setTimeout(()=>btn.classList.remove('copied'),1200);}catch(e){console.error('copyCode error',e);}}"
            + "function renderPlainContent(text){if(!text)return'';if(looksLikeToolJson(text))return'';let html=text;html=html.replace(/<image_data>([^<]+)<\\/image_data>/g,(m,d)=>{let url=d.trim();if(!url.startsWith('data:'))url='data:'+url;return '<div class=\"imgWrap\"><img src=\"'+url+'\"/></div>';});html=html.replace(/\\[IMAGE:[^\\]]*\\]\\n?/g,'');html=html.replace(/<media-img>([^<]+)<\\/media-img>/g,(m,p)=>'<div class=\"imgWrap\"><img src=\"'+fileUrl(p.trim())+'\"/></div>');html=html.replace(/<media-audio>([^<]+)<\\/media-audio>/g,(m,p)=>'<audio controls src=\"'+fileUrl(p.trim())+'\" style=\"width:100%;margin:8px 0;\"></audio>');html=html.replace(/<media-file name=\"([^\"]+)\">([^<]+)<\\/media-file>/g,(m,n,p)=>'<a href=\"'+fileUrl(p.trim())+'\" target=\"_blank\" style=\"display:inline-flex;align-items:center;gap:6px;padding:8px 12px;background:var(--field);border:1px solid var(--line);border-radius:8px;text-decoration:none;color:var(--text);margin:4px 0;\">'+n+'</a>');html=html.replace(/\\[image:([^\\]]+)\\]/g,(m,p)=>'<div class=\"imgWrap\"><img src=\"'+fileUrl(p)+'\"/></div>');html=html.replace(/\\[audio:([^\\]]+)\\]/g,(m,p)=>'<audio controls src=\"'+fileUrl(p)+'\" style=\"width:100%;margin:8px 0;\"></audio>');html=html.replace(/\\[file:([^|]+)\\|([^\\]]+)\\]/g,(m,p,n)=>'<a href=\"'+fileUrl(p)+'\" target=\"_blank\" style=\"display:inline-flex;align-items:center;gap:6px;padding:8px 12px;background:var(--field);border:1px solid var(--line);border-radius:8px;text-decoration:none;color:var(--text);margin:4px 0;\">'+n+'</a>');if(typeof marked!=='undefined'){try{html=marked.parse(html).trim();}catch(e){html=escapeHtml(text).replace(/\\n/g,'<br>');}}else{html=escapeHtml(text).replace(/\\n/g,'<br>');}return decorateCodeBlocks(html.trim());}"
            + "function looksLikeToolJson(text){if(!text)return false;const t=text.trim();return t.startsWith('[')&&t.includes('type')&&(t.includes('tool_use')||t.includes('tool_result'));}"
            + "function renderContent(text){if(looksLikeToolJson(text))return'';const structured=parseStructuredContent(text);if(structured){let h='';let textParts=[];const flushText=()=>{if(textParts.length===0)return;h+=renderPlainContent(textParts.join('\\n\\n'));textParts=[];};structured.forEach(t=>{if(t.type==='text'&&t.text&&t.text!=='null'){textParts.push(t.text);}else if(t.type==='tool_use'||t.type==='tool_result'){flushText();h+=renderToolBlocks([t]);}});flushText();return h;}return renderPlainContent((text||'').trim());}"
            + "function escapeHtml(t){return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}"
            + "function renderHistory(items){const box=document.getElementById('messages');box.innerHTML='';shownMsgs.clear();(items||[]).forEach(item=>addBubble(item.content||'',bubbleClass(item.role||'assistant'),false,item.timestamp||''));box.scrollTop=1e9;highlightCodeBlocks(box);initViewer();applyToolCallVisibility();}"
            + "function humanStatus(v){const s=String(v||'');if(!s)return 'idle';if(s==='llm_request')return 'thinking';if(s.startsWith('llm_request:'))return s.replace('llm_request','thinking');return s;}"
            + "function workspaceValue(v,fallback){return v===undefined||v===null||v===''?(fallback||'-'):String(v);}"
            + "function renderStatusJson(detail){const el=document.getElementById('statusJson');if(el)el.textContent=JSON.stringify(detail||{},null,2);}"
            + "function renderWorkspace(status,detail){detail=detail||{};const tokens=document.getElementById('wsTokens');const model=document.getElementById('wsModel');const history=document.getElementById('wsHistory');const err=document.getElementById('wsError');const statusEl=document.getElementById('workspaceStatus');const normalizedStatus=humanStatus(detail.agentStatus||status);if(tokens)tokens.textContent=workspaceValue(detail.totalTokens,'0');if(model)model.textContent=workspaceValue(detail.model,'-');if(history)history.textContent=workspaceValue(detail.historyCount,'0');if(err)err.textContent=workspaceValue(detail.webConsoleError||'',normalizedStatus&&String(normalizedStatus).indexOf('error')>=0?workspaceValue(normalizedStatus,'Error'):'None');if(statusEl)statusEl.textContent=normalizedStatus==='error'?'Error':workspaceValue(normalizedStatus,'idle');renderStatusJson(detail);}"
            + "function isBusyStatus(status){const s=String(status||'').split(': ')[0];return s==='processing'||s==='thinking'||s==='llm_request'||s==='tool_use'||s==='responding';}"
            + "function isTerminalStatus(status){const s=String(status||'').split(': ')[0];return !s||s==='idle'||s==='error';}"
            + "function isSendButtonBusy(){return !!(window.__openclawBusy||window.__sendingRequest||window.__stoppingRequest);}"
            + "function setChatStatus(status,opts){const el=document.getElementById('chatStatus');if(!el)return;const options=opts||{};const raw=String(status||'');if(options.plain){el.className='statusPill'+(options.className?' '+options.className:'');el.innerHTML='<i class=\"dot\"></i>'+(raw||'Ready');return;}el.innerHTML=statusHtml(raw);}"
            + "function renderSendButton(){const btn=document.getElementById('sendBtn');const input=document.getElementById('message');if(!btn)return;const busy=isSendButtonBusy();btn.innerHTML=busy?svgStopIcon:svgSendIcon;btn.title=busy?'Stop':'Send';btn.classList.toggle('busy',busy);if(input&&!window.__voiceRecording)input.placeholder=busy?'AI is processing...':'Type a message...';}"
            + "function renderMicButton(){const btn=document.getElementById('micBtn');const input=document.getElementById('message');if(!btn)return;btn.classList.toggle('recording',!!window.__voiceRecording);btn.title=window.__voiceRecording?'Stop recording':'Voice input';if(input){if(window.__voiceRecording){input.placeholder='Listening...';}else if(!isSendButtonBusy()){input.placeholder='Type a message...';}}}"
            + "function setComposerBusy(busy){window.__openclawBusy=!!busy;renderSendButton();renderMicButton();}"
            + "function syncBusyFromStatus(status){if(window.__stoppingRequest&&!isTerminalStatus(status))return;setComposerBusy(isBusyStatus(status));}"
            + "function sendOrStop(event){if(isSendButtonBusy()){stopChat();}else{sendChat();}}"
            + "let voiceRecognition=null;let voicePermissionReady=false;let voiceFinalTranscript='';window.__voiceRecording=false;window.__sendingRequest=false;window.__stoppingRequest=false;let micLongPress=false;let micPressTimer=null;window.__voiceSendOnEnd=false;let voiceToastShown=false;"
            + "function getSpeechLang(){return 'zh-CN';}"
            + "async function ensureMicPermission(){if(voicePermissionReady)return true;try{if(navigator.permissions&&navigator.permissions.query){const result=await navigator.permissions.query({name:'microphone'});if(result&&result.state==='granted'){voicePermissionReady=true;return true;}}}catch(e){}try{if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia)return false;const stream=await navigator.mediaDevices.getUserMedia({audio:true});(stream.getTracks?stream.getTracks():[]).forEach(track=>{try{track.stop();}catch(e){}});voicePermissionReady=true;return true;}catch(e){console.error('mic permission error',e);return false;}}"
            + "function ensureVoiceRecognition(){if(voiceRecognition)return voiceRecognition;const SpeechRecognition=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SpeechRecognition)return null;const recognition=new SpeechRecognition();recognition.lang=getSpeechLang();recognition.continuous=true;recognition.interimResults=true;recognition.maxAlternatives=1;recognition.onresult=function(event){const input=document.getElementById('message');let live='';for(let i=0;i<event.results.length;i++){const result=event.results[i];const text=((result&&result[0]&&result[0].transcript)||'').trim();if(text){live+=(live?' ':'')+text;}}if(input&&live){input.value=live;input.dispatchEvent(new Event('input',{bubbles:true}));}};recognition.onerror=function(e){if(e.error==='aborted')return;console.error('speech error',e.error);window.__voiceSendOnEnd=false;window.__voiceRecording=false;renderMicButton();loadStatus();};recognition.onend=function(){if(!window.__voiceRecording)return;window.__voiceRecording=false;renderMicButton();const shouldSend=window.__voiceSendOnEnd;window.__voiceSendOnEnd=false;if(shouldSend){const input=document.getElementById('message');if(input&&input.value.trim()){setTimeout(()=>sendChat(),50);}else{loadStatus();}}else{loadStatus();}};voiceRecognition=recognition;return recognition;}"
            + "window.toggleVoiceRecording=async function(){if(window.__voiceRecording){stopVoiceRecording();return;}if(isSendButtonBusy()){setChatStatus('Wait for AI',{plain:true,className:'st-warn'});return;}setChatStatus('Preparing mic',{plain:true});const granted=await ensureMicPermission();if(!granted){setChatStatus('Mic denied',{plain:true,className:'st-err'});return;}const recognition=ensureVoiceRecognition();if(!recognition){setChatStatus('Voice unsupported',{plain:true,className:'st-err'});return;}voiceFinalTranscript='';window.__voiceRecording=true;renderMicButton();setChatStatus('Listening...',{plain:true});try{recognition.start();}catch(e){window.__voiceRecording=false;renderMicButton();setChatStatus('Voice unavailable',{plain:true,className:'st-err'});}};"
            + "function stopVoiceRecording(sendAfter){if(voiceRecognition&&window.__voiceRecording){window.__voiceSendOnEnd=!!sendAfter;try{voiceRecognition.stop();}catch(e){window.__voiceRecording=false;renderMicButton();if(sendAfter){const input=document.getElementById('message');if(input&&input.value.trim())sendChat();}}}else{window.__voiceRecording=false;renderMicButton();loadStatus();}}"
            + "function showToast(msg){let t=document.getElementById('voiceToast');if(!t){t=document.createElement('div');t.id='voiceToast';t.style.cssText='position:fixed;bottom:180px;left:50%;transform:translateX(-50%);background:var(--text);color:var(--bg);padding:8px 16px;border-radius:20px;font-size:13px;opacity:0;transition:opacity .2s;z-index:9999;pointer-events:none;';document.body.appendChild(t);}t.textContent=msg;t.style.opacity='1';clearTimeout(t._timer);t._timer=setTimeout(()=>{t.style.opacity='0';},1500);}"
            + "let micPressActive=false;"
            + "function setupMicLongPress(){const btn=document.getElementById('micBtn');if(!btn)return;btn.addEventListener('mousedown',micPressStart);btn.addEventListener('touchstart',e=>{e.preventDefault();micPressStart(e);},{passive:false});document.addEventListener('mouseup',globalMicRelease);document.addEventListener('touchend',globalMicRelease,{passive:true});}"
            + "function micPressStart(e){if(isSendButtonBusy()||window.__voiceRecording)return;micPressActive=true;micLongPress=false;if(micPressTimer)clearTimeout(micPressTimer);micPressTimer=setTimeout(()=>{micPressTimer=null;if(!micPressActive)return;micLongPress=true;if(!voiceToastShown){showToast('Release to send');voiceToastShown=true;}startVoiceForLongPress();},400);}"
            + "function globalMicRelease(e){if(!micPressActive&&!micLongPress&&!micPressTimer)return;if(micPressTimer){clearTimeout(micPressTimer);micPressTimer=null;micPressActive=false;micLongPress=false;return;}if(micLongPress&&window.__voiceRecording){stopVoiceRecording(true);}micPressActive=false;micLongPress=false;}"
            + "async function startVoiceForLongPress(){if(window.__voiceRecording||isSendButtonBusy())return;const granted=await ensureMicPermission();if(!granted){showToast('Mic denied');return;}const recognition=ensureVoiceRecognition();if(!recognition){showToast('Voice unsupported');return;}window.__voiceRecording=true;renderMicButton();setChatStatus('Listening...',{plain:true});try{recognition.start();}catch(e){window.__voiceRecording=false;renderMicButton();}}"
            + "let pendingFiles=[];const svgX='<svg width=\"10\" height=\"10\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><path d=\"M18 6 6 18\"/><path d=\"m6 6 12 12\"/></svg>';"
            + "function handleFiles(files){for(let i=0;i<files.length;i++){const f=files[i];if(f.size>10*1024*1024){alert('File too large (max 10MB)');continue;}pendingFiles.push(f);}renderAttachPreview();document.getElementById('fileInput').value='';}"
            + "function setupDragDrop(){const c=document.querySelector('.composer');if(!c)return;c.addEventListener('dragover',e=>{e.preventDefault();e.stopPropagation();c.classList.add('dragover');});c.addEventListener('dragleave',e=>{e.preventDefault();e.stopPropagation();c.classList.remove('dragover');});c.addEventListener('drop',e=>{e.preventDefault();e.stopPropagation();c.classList.remove('dragover');if(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files.length>0){handleFiles(e.dataTransfer.files);}});}"
            + "function removeAttach(idx){pendingFiles.splice(idx,1);renderAttachPreview();}"
            + "function renderAttachPreview(){const box=document.getElementById('attachPreview');if(!box)return;if(pendingFiles.length===0){box.innerHTML='';return;}let h='';pendingFiles.forEach((f,i)=>{const isImg=f.type.startsWith('image/');if(isImg){const url=URL.createObjectURL(f);h+='<div class=\"attachItem\"><img src=\"'+url+'\"><span>'+escapeHtml(f.name)+'</span><button onclick=\"removeAttach('+i+')\">'+svgX+'</button></div>';}else{h+='<div class=\"attachItem\"><span>'+escapeHtml(f.name)+'</span><button onclick=\"removeAttach('+i+')\">'+svgX+'</button></div>';}});box.innerHTML=h;}"
            + "async function uploadFiles(){if(pendingFiles.length===0)return[];const results=[];for(const f of pendingFiles){const fd=new FormData();fd.append('file',f);try{const r=await fetch(apiUrl('/api/upload'),{method:'POST',credentials:'include',headers:apiHeaders(),body:fd});const j=await r.json();if(j.ok){results.push({name:j.name||f.name,type:j.type||f.type,content:j.content||null,base64:j.base64||null});}}catch(e){console.error('upload error',e);}}pendingFiles=[];renderAttachPreview();return results;}"
            + "let statusRefreshTimer=null;"
            + "function queueStatusRefresh(delay){if(statusRefreshTimer)clearTimeout(statusRefreshTimer);statusRefreshTimer=setTimeout(()=>{statusRefreshTimer=null;loadStatus();},delay||120);}"
            + "async function loadStatus(){try{const r=await fetch(apiUrl('/api/status'),{credentials:'include',headers:apiHeaders()});const j=await r.json();if(j.ok){setChatStatus(j.status||'Ready');syncBusyFromStatus(j.status||'');document.getElementById('meta').textContent=(j.chatId?('Session: '+j.chatId.substring(0,8)+'...'):'');renderWorkspace(j.status||'',j.detail||{});}else{renderWorkspace('error',{error:'not ok',data:j});}}catch(e){console.error('loadStatus error',e);renderWorkspace('error',{error:e.message});}}"
            + "async function reloadHistory(){if(loadingHistory)return;loadingHistory=true;try{const r=await fetch(apiUrl('/api/history'),{credentials:'include',headers:apiHeaders()});const j=await r.json();if(j.ok){renderHistory(j.messages||[]);}}catch(e){}finally{loadingHistory=false;loadStatus();}}"
            + "function renderUserAttachments(files){if(!files||files.length===0)return'';let h='<div class=\"userAttachments\">';files.forEach(f=>{const isImg=f.type&&f.type.startsWith('image/');if(isImg){const url=URL.createObjectURL(f);h+='<div class=\"userAttachItem\"><img src=\"'+url+'\"/></div>';}else{h+='<div class=\"userAttachItem file\"><span>'+escapeHtml(f.name)+'</span></div>';}});h+='</div>';return h;}"
            + "let sending=false,lastSend=0;async function sendChat(){if(sending||Date.now()-lastSend<1000)return;const input=document.getElementById('message');const value=input.value.trim();const hasFiles=pendingFiles.length>0;if(!value&&!hasFiles)return;sending=true;window.__sendingRequest=true;lastSend=Date.now();setComposerBusy(true);input.value='';const filesToShow=[...pendingFiles];if(value==='/new'){document.getElementById('messages').innerHTML='';shownMsgs.clear();pendingFiles=[];renderAttachPreview();}else{let displayHtml=renderUserAttachments(filesToShow);if(value)displayHtml+='<p>'+escapeHtml(value)+'</p>';if(!displayHtml)displayHtml=hasFiles?'[Attached '+filesToShow.length+' file(s)]':'';addBubbleHtml(displayHtml,'user');}setChatStatus(hasFiles?'Uploading...':'Thinking...',hasFiles?{plain:true}:{});queueStatusRefresh(60);"
            + "try{const attachments=await uploadFiles();const r=await fetch(apiUrl('/api/chat'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({message:value,attachments:attachments})});if(!r.ok){addBubble('HTTP '+r.status+': '+r.statusText,'bot');setChatStatus('HTTP Error',{plain:true,className:'st-err'});setComposerBusy(false);return;}const j=await r.json();"
            + "if(j.ok){if(j.status){setChatStatus(j.status);syncBusyFromStatus(j.status||'');}if(j.response){if(j.response==='Conversation cleared.'){document.getElementById('messages').innerHTML='';shownMsgs.clear();}else{addBubble(j.response,'bot');}}}else{setChatStatus('Error',{plain:true,className:'st-err'});setComposerBusy(false);addBubble('Error: '+(j.error||'request_failed'),'bot');}}catch(e){setChatStatus('Error',{plain:true,className:'st-err'});setComposerBusy(false);addBubble('Error: '+e.message,'bot');console.error('sendChat error',e);}finally{sending=false;window.__sendingRequest=false;renderSendButton();queueStatusRefresh(80);}}"
            + "async function stopChat(){if(!isSendButtonBusy())return;try{window.__stoppingRequest=true;renderSendButton();setChatStatus('Stopping...',{plain:true});queueStatusRefresh(60);const r=await fetch(apiUrl('/api/stop'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'})});const j=await r.json();if(j&&j.ok){setComposerBusy(false);setChatStatus(j.status||'idle');addBubble('Generation stopped.','bot');queueStatusRefresh(120);}else{setChatStatus('Stop failed',{plain:true,className:'st-err'});queueStatusRefresh(120);}}catch(e){setChatStatus('Stop failed',{plain:true,className:'st-err'});queueStatusRefresh(120);console.error('stopChat error',e);}finally{window.__stoppingRequest=false;window.__sendingRequest=false;renderSendButton();}}"
            + "function flashCardStatus(id,text){const el=document.getElementById(id);if(!el)return;el.textContent=text||'';if(id==='apiStatus'||id==='skillStatus'||id==='cronStatus'||id==='passwordStatus'){setTimeout(()=>{if(el.textContent===text)el.textContent='';},1500);}}"
            + "async function updatePassword(){try{const r=await fetch(apiUrl('/api/password'),{method:'POST',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({currentPassword:document.getElementById('currentPassword').value,newPassword:document.getElementById('newPassword').value})});const j=await r.json();flashCardStatus('passwordStatus',j.ok?'Updated':(j.error||'Failed'));if(j.ok){document.getElementById('currentPassword').value='';document.getElementById('newPassword').value='';}}catch(e){flashCardStatus('passwordStatus','Error');}}"
            + "function statusHtml(s){if(!s){document.getElementById('chatStatus').className='statusPill';return'<i class=\"dot\"></i>Ready';}const p=String(s).split(': ');const c=p[0];const d=p.slice(1).join(': ');const m={idle:'Ready',processing:'Work',thinking:'Thinking',llm_request:'Thinking',tool_use:'Tools',responding:'Done',error:'Err'};let label=m[c]||humanStatus(c);if(c==='error'&&d){const h=d.match(/API error (\\d+)/);if(h){const e={'400':'Bad','401':'Auth','403':'Deny','404':'404','413':'Big','429':'Limit','500':'Srv','502':'Gate','503':'Down','504':'Time'};label=e[h[1]]||'E'+h[1];}else if(d.includes('timeout')){label='Time';}else if(d.includes('connect')){label='Net';}}const cls={idle:'',processing:'',thinking:'st-ai',llm_request:'st-ai',tool_use:'st-warn',responding:'st-ok',error:'st-err'};const stCls=cls[c]||(c.includes('error')?'st-err':'');document.getElementById('chatStatus').className='statusPill'+(stCls?' '+stCls:'');const spin=(c==='processing'||c==='thinking'||c==='llm_request'||c==='tool_use')?' spin':'';return'<i class=\"dot'+spin+'\"></i>'+label;}"
            + "let es=null,esRetry=0,lastPong=0;function connectSSE(){if(es){try{es.close();}catch(x){}}es=new EventSource(apiUrl('/api/events'),{withCredentials:true});lastPong=Date.now();es.addEventListener('status',e=>{setChatStatus(e.data||'');syncBusyFromStatus(e.data||'');esRetry=0;lastPong=Date.now();queueStatusRefresh(80);});es.addEventListener('message',e=>{lastPong=Date.now();try{const d=JSON.parse(e.data);if(d.content&&d.role!=='user'){addBubble(d.content,'bot',false,d.timestamp||'');}queueStatusRefresh(120);}catch(x){}});es.addEventListener('skill_change',e=>{lastPong=Date.now();try{JSON.parse(e.data);loadSkillControl();queueStatusRefresh(120);}catch(x){}});es.onerror=e=>{if(es){es.close();es=null;}queueStatusRefresh(200);esRetry++;setTimeout(connectSSE,Math.min(esRetry*500,5000));};es.onopen=()=>{esRetry=0;lastPong=Date.now();queueStatusRefresh(50);}}setInterval(()=>{if(es&&Date.now()-lastPong>15000){es.close();es=null;connectSSE();}},5000);"
            + "let providerProfiles=[];let activeProfileId='default';let profileRenameTimer=null;"
            + "function renderProfileOptions(){const select=document.getElementById('cfgActiveProfile');if(!select)return;let h='';(providerProfiles||[]).forEach(p=>{const id=(p&&p.id)||'';const name=(p&&p.name)||id||'Profile';h+='<option value=\"'+escapeHtml(id)+'\" '+(id===activeProfileId?'selected':'')+'>'+escapeHtml(name)+'</option>';});select.innerHTML=h;const active=(providerProfiles||[]).find(p=>p&&p.id===activeProfileId)||providerProfiles[0]||null;const nameInput=document.getElementById('cfgProfileName');if(nameInput){nameInput.value=active&&active.name?active.name:'';}}"
            + "async function switchActiveProfile(){const select=document.getElementById('cfgActiveProfile');if(!select)return;const nextId=select.value||'';if(!nextId||nextId===activeProfileId)return;try{const r=await fetch(apiUrl('/api/config/profile'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({action:'set_active',profile_id:nextId})});const j=await r.json();if(j.ok){activeProfileId=nextId;await loadConfig();flashCardStatus('apiStatus','Switched');}else{flashCardStatus('apiStatus','Failed');}}catch(e){flashCardStatus('apiStatus','Error');}}"
            + "async function renameCurrentProfile(){if(profileRenameTimer)clearTimeout(profileRenameTimer);profileRenameTimer=setTimeout(async()=>{const nameInput=document.getElementById('cfgProfileName');const profileName=nameInput&&nameInput.value?nameInput.value.trim():'';if(!activeProfileId||!profileName)return;try{const r=await fetch(apiUrl('/api/config/profile'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({action:'rename',profile_id:activeProfileId,profile_name:profileName})});const j=await r.json();if(j.ok){await loadConfig();flashCardStatus('apiStatus','Saved');}else{flashCardStatus('apiStatus','Failed');}}catch(e){flashCardStatus('apiStatus','Error');}},350);}"
            + "async function createProfileDraft(){try{const r=await fetch(apiUrl('/api/config/profile'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({action:'create',make_active:true})});const j=await r.json();if(j.ok){await loadConfig();flashCardStatus('apiStatus','Created');}else{flashCardStatus('apiStatus','Failed');}}catch(e){flashCardStatus('apiStatus','Error');}}"
            + "async function deleteCurrentProfile(){if(!activeProfileId)return;const total=(providerProfiles||[]).length;if(total<=1){flashCardStatus('apiStatus','Keep one');return;}const active=(providerProfiles||[]).find(p=>p&&p.id===activeProfileId);const name=active&&active.name?active.name:activeProfileId;if(!confirm('Delete profile \"'+name+'\"?'))return;try{const r=await fetch(apiUrl('/api/config/profile'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({action:'delete',profile_id:activeProfileId})});const j=await r.json();if(j.ok){await loadConfig();flashCardStatus('apiStatus','Deleted');}else{const reason=j&&j.result&&j.result.reason?j.result.reason:'';flashCardStatus('apiStatus',reason==='last_profile'?'Keep one':'Failed');}}catch(e){flashCardStatus('apiStatus','Error');}}"
            + "async function loadConfig(){try{const r=await fetch(apiUrl('/api/config'),{credentials:'include',headers:apiHeaders()});const j=await r.json();if(j.ok){providerProfiles=Array.isArray(j.profiles)?j.profiles:[];activeProfileId=j.active_profile_id||'default';renderProfileOptions();document.getElementById('cfgProvider').value=j.provider||'openai';document.getElementById('cfgModel').value=j.model||'';document.getElementById('cfgApiUrl').value=j.custom_api_url||'';document.getElementById('cfgApiKey').value=j.api_key||'';document.getElementById('cfgMaxTokens').value=j.max_tokens||'4096';document.getElementById('cfgMaxToolIterations').value=j.max_tool_iterations||'30';}}catch(e){console.error('loadConfig error',e);}}"
            + "let saveTimer=null;async function saveConfig(){if(saveTimer)clearTimeout(saveTimer);saveTimer=setTimeout(async()=>{try{const maxTokens=document.getElementById('cfgMaxTokens');let maxTokensValue=maxTokens&&maxTokens.value?parseInt(maxTokens.value,10):4096;if(!Number.isFinite(maxTokensValue))maxTokensValue=4096;maxTokensValue=Math.max(256,Math.min(32768,maxTokensValue));if(maxTokens)maxTokens.value=String(maxTokensValue);const maxToolIterations=document.getElementById('cfgMaxToolIterations');let maxToolIterationsValue=maxToolIterations&&maxToolIterations.value?parseInt(maxToolIterations.value,10):30;if(!Number.isFinite(maxToolIterationsValue))maxToolIterationsValue=30;maxToolIterationsValue=Math.max(1,Math.min(100,maxToolIterationsValue));if(maxToolIterations)maxToolIterations.value=String(maxToolIterationsValue);const r=await fetch(apiUrl('/api/config'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({provider:document.getElementById('cfgProvider').value,model:document.getElementById('cfgModel').value,custom_api_url:document.getElementById('cfgApiUrl').value,api_key:document.getElementById('cfgApiKey').value,max_tokens:maxTokensValue,max_tool_iterations:maxToolIterationsValue})});const j=await r.json();flashCardStatus('apiStatus',j.ok?'Saved':'Failed');}catch(e){flashCardStatus('apiStatus','Error');}},500);}function toggleApiKey(){const input=document.getElementById('cfgApiKey');if(!input)return;input.type=input.type==='password'?'text':'password';}"
            + "const builtinSkills=[{id:'homeassistant',name:'Home Assistant',desc:'Smart home control (26 tools)',default:true},{id:'android_system_bridge',name:'Android System',desc:'Device info, shell commands',default:true},{id:'android_accessibility_bridge',name:'Accessibility',desc:'UI inspection and control',default:true},{id:'android_browser_bridge',name:'Browser',desc:'Floating browser control',default:true},{id:'multi_search_engine',name:'Web Search',desc:'Tavily + 17 engines',default:true}];"
            + "function toggleSkillTab(tab){document.querySelectorAll('.skillTab').forEach(t=>t.classList.remove('active'));document.querySelectorAll('.skillPanel').forEach(p=>p.style.display='none');document.querySelector('.skillTab[data-tab=\"'+tab+'\"]').classList.add('active');document.getElementById('skillPanel_'+tab).style.display='block';}"
            + "function displayCronName(job){if(!job)return'Task';const raw=job.name||'Task';return raw==='__builtin_heartbeat__'?'Background heartbeat':raw;}"
            + "function formatCronMeta(job){if(!job)return'';const isHeartbeat=job&&job.name==='__builtin_heartbeat__';if(isHeartbeat&&job.kind==='every'){const seconds=Number(job.interval_s||0);const interval=seconds%60===0&&seconds>=60?Math.round(seconds/60)+' min':seconds+' sec';return 'Starts after you chat, then runs in background every '+interval;}if(job.kind==='every'){const seconds=Number(job.interval_s||0);const interval=seconds%60===0&&seconds>=60?Math.round(seconds/60)+' min':seconds+' sec';return 'Runs every '+interval;}if(job.kind==='at'){const at=Number(job.at_epoch||0);if(at>0){try{return 'Runs once at '+new Date(at*1000).toLocaleString();}catch(e){}}return 'Runs once';}return '';}"
            + "async function toggleCron(cb){const jobId=cb.dataset.jobId||'';if(!jobId)return;try{const r=await fetch(apiUrl('/api/skill_config'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({cron_job_id:jobId,enabled:!!cb.checked})});const j=await r.json();flashCardStatus('cronStatus',j.ok?'Saved':'Failed');if(j.ok){await loadSkillControl();}}catch(e){flashCardStatus('cronStatus','Error');}}"
            + "function renderCronList(cronJobs){const list=document.getElementById('cronList');if(!list)return;let h='';if(Array.isArray(cronJobs)&&cronJobs.length>0){h+='<div class=\"cronList\">';cronJobs.forEach(job=>{const name=displayCronName(job);const meta=formatCronMeta(job);const checked=job&&job.enabled!==false;const id=job&&job.id?job.id:'';const badge=checked?'On':'Off';h+='<label class=\"skillItem cronItem\"><input type=\"checkbox\" data-job-id=\"'+escapeHtml(id)+'\" '+(checked?'checked':'')+' onchange=\"toggleCron(this)\"><div class=\"cronBody\"><div class=\"cronNameRow\"><div class=\"cronName\">'+escapeHtml(name)+'</div><div class=\"cronBadge\">'+badge+'</div></div><div class=\"cronMeta\">'+escapeHtml(meta)+'</div></div></label>';});h+='</div>';}else{h='<div class=\"cronList\"><div class=\"cronItem\"><div class=\"cronBody\"><div class=\"cronNameRow\"><div class=\"cronName\">No scheduled tasks</div></div><div class=\"cronMeta\">Timers created by AI will appear here.</div></div></div></div>';}list.innerHTML=h;}"
            + "async function loadSkillControl(){const list=document.getElementById('skillControlList');if(!list)return;try{const r=await fetch(apiUrl('/api/skill_config'),{credentials:'include',headers:apiHeaders()});const j=await r.json();const enabled=j.ok?j.enabled||{}:{};const userSkills=j.user_skills||[];const cronJobs=Array.isArray(j.cron_jobs)?j.cron_jobs:[];let h='<div class=\"skillTabs\"><span class=\"skillTab active\" data-tab=\"builtin\" onclick=\"toggleSkillTab(\\'builtin\\')\">Built-in</span>';if(userSkills.length>0){h+='<span class=\"skillTab\" data-tab=\"user\" onclick=\"toggleSkillTab(\\'user\\')\">User ('+userSkills.length+')</span>';}h+='</div><div id=\"skillPanel_builtin\" class=\"skillPanel\">';builtinSkills.forEach(s=>{const checked=(enabled[s.id]!==undefined)?enabled[s.id]:s.default;h+='<label class=\"skillItem\"><input type=\"checkbox\" data-skill=\"'+s.id+'\" '+(checked?'checked':'')+' onchange=\"toggleSkill(this)\"><span>'+s.name+'</span></label>'});h+='</div>';if(userSkills.length>0){h+='<div id=\"skillPanel_user\" class=\"skillPanel\" style=\"display:none\">';userSkills.forEach(s=>{const checked=(enabled[s.id]!==undefined)?enabled[s.id]:true;h+='<label class=\"skillItem\"><input type=\"checkbox\" data-skill=\"'+s.id+'\" '+(checked?'checked':'')+' onchange=\"toggleSkill(this)\"><span>'+s.name+'</span></label>'});h+='</div>';}list.innerHTML=h;renderCronList(cronJobs);}catch(e){console.error('loadSkillControl error',e);}}"
            + "async function toggleSkill(cb){const skillId=cb.dataset.skill;const enabled=cb.checked;try{const r=await fetch(apiUrl('/api/skill_config'),{method:'POST',credentials:'include',headers:apiHeaders({'Content-Type':'application/json'}),body:JSON.stringify({skill_id:skillId,enabled:enabled})});const j=await r.json();flashCardStatus('skillStatus',j.ok?'Saved':'Failed');}catch(e){flashCardStatus('skillStatus','Error');}}"
            + "window.addEventListener('resize',maybeShowInstallHint);window.addEventListener('orientationchange',maybeShowInstallHint);"
            + "(async function init(){window.__openclawBusy=false;window.__sendingRequest=false;window.__stoppingRequest=false;window.__voiceRecording=false;window.__voiceSendOnEnd=false;setComposerBusy(false);renderMicButton();setupMicLongPress();const cb=document.getElementById('showToolCalls');if(cb)cb.checked=showToolCalls;maybeShowInstallHint();setupDragDrop();await loadConfig();await loadSkillControl();await reloadHistory();applyToolCallVisibility();setTimeout(connectSSE,100);})();"
            + "</script></body></html>";
    }

    private String baseCss() {
        return ":root{color-scheme:light;--font:'SF Pro Display','Segoe UI',system-ui,sans-serif;--bg:#faf8ff;--sidebar:#f3f3fc;--panel:#ffffff;--panelAlt:#ededf6;--field:#f3f3fc;--line:#e2e8f0;--text:#191b22;--muted:#64748b;--accent:#0417e0;--accentSoft:#eef2ff;--accentText:#ffffff;--userBubble:#0417e0;--userText:#ffffff;--botBubble:#e2e8f0;--attachBg:#ffffff;--codeBg:#f6f8fc;--codeTop:#edf2f8;--codeLine:#d8e0ec;--codeText:#1f2937;--codeMuted:#7c8798;--shadow:0 22px 60px rgba(24,39,75,.10);--userShadow:0 8px 24px rgba(37,99,235,.18);--scrollTrack:#f3f3fc;--scrollThumb:#c4c9d4;}html[data-theme='dark']{color-scheme:dark;--bg:#111319;--sidebar:#191b22;--panel:#1e1f26;--panelAlt:#282a30;--field:#2d2d2d;--line:#3d3d3d;--text:#f1f5f9;--muted:#94a3b8;--accent:#a78b73;--accentSoft:#3a322c;--accentText:#fffaf6;--userBubble:#a78b73;--userText:#ffffff;--botBubble:#2d2d2d;--attachBg:#1e1b1b;--codeBg:#161b22;--codeTop:#1f2630;--codeLine:#2d3641;--codeText:#e5edf6;--codeMuted:#8ea0b5;--shadow:0 22px 60px rgba(0,0,0,.34);--userShadow:0 8px 24px rgba(167,139,115,.25);--scrollTrack:#1e1f26;--scrollThumb:#4a4d55;}*{box-sizing:border-box;}button,input{font:inherit;}::-webkit-scrollbar{width:8px;height:8px;}::-webkit-scrollbar-track{background:var(--scrollTrack);border-radius:4px;}::-webkit-scrollbar-thumb{background:var(--scrollThumb);border-radius:4px;}::-webkit-scrollbar-thumb:hover{background:var(--muted);}";
    }

    private String themeScript() {
        return "<script>(function(){const key='openclaw_theme';const root=document.documentElement;const media=window.matchMedia?window.matchMedia('(prefers-color-scheme: dark)'):null;function applyThemeColor(theme){let meta=document.querySelector('meta[name=\"theme-color\"][data-dynamic=\"1\"]');if(!meta){meta=document.createElement('meta');meta.name='theme-color';meta.setAttribute('data-dynamic','1');document.head.appendChild(meta);}meta.content=theme==='dark'?'#111319':'#faf8ff';}function applyTheme(theme,persist){if(theme!=='dark'&&theme!=='light'){theme=media&&media.matches?'dark':'light';}root.setAttribute('data-theme',theme);applyThemeColor(theme);if(persist){localStorage.setItem(key,theme);}else{localStorage.removeItem(key);}}const saved=localStorage.getItem(key);if(saved==='dark'||saved==='light'){applyTheme(saved,false);localStorage.setItem(key,saved);}else{applyTheme(null,false);}if(media){const onChange=()=>{if(!localStorage.getItem(key)){applyTheme(null,false);}};if(media.addEventListener){media.addEventListener('change',onChange);}else if(media.addListener){media.addListener(onChange);}}window.toggleTheme=function(){const current=root.getAttribute('data-theme')==='dark'?'dark':'light';applyTheme(current==='dark'?'light':'dark',true);};window.clearThemePreference=function(){applyTheme(null,false);};})();</script>";
    }
}
