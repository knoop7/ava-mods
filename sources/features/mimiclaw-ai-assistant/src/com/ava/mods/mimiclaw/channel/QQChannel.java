package com.ava.mods.mimiclaw.channel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class QQChannel implements Channel {
    private static final String TAG = "QQChannel";
    public static final String NAME = "qqbot";
    private static final String PREFS_NAME = "mimiclaw_channel_runtime";
    private static final String KEY_ACTIVE_INSTANCE = "qq_active_instance";

    private static final String QQ_API_BASE = "https://api.sgroup.qq.com";
    private static final String QQ_SANDBOX_API_BASE = "https://sandbox.api.sgroup.qq.com";
    private static final String QQ_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final long TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1000L;
    private static final long QUICK_DISCONNECT_THRESHOLD_MS = 5_000L;
    private static final long[] RECONNECT_DELAYS_MS = new long[]{1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L};

    private static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;
    private static final int INTENT_DIRECT_MESSAGE = 1 << 12;
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;
    private static final int DEFAULT_INTENTS = INTENT_PUBLIC_GUILD_MESSAGES | INTENT_DIRECT_MESSAGE | INTENT_GROUP_AND_C2C;
    private static final int MSG_TYPE_TEXT = 0;
    private static final int MSG_TYPE_MEDIA = 7;
    private static final int MEDIA_TYPE_IMAGE = 1;
    private static final int MEDIA_TYPE_VIDEO = 2;
    private static final int MEDIA_TYPE_VOICE = 3;
    private static final int MEDIA_TYPE_FILE = 4;
    private static final Pattern MEDIA_TAG_PATTERN = Pattern.compile(
        "<(qqimg|qqvoice|qqvideo|qqfile|qqmedia)>(.*?)</(?:qqimg|qqvoice|qqvideo|qqfile|qqmedia|img)>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final Object stateLock = new Object();
    private final SharedPreferences prefs;
    private final String instanceId;

    private String appId = "";
    private String clientSecret = "";
    private String accessToken = "";
    private long tokenExpireTime = 0L;
    private boolean useSandbox = false;
    private boolean enabled = false;
    private boolean configured = false;

    private MessageListener listener;
    private ScheduledExecutorService executor;
    private OkHttpClient webSocketClient;
    private WebSocket webSocket;

    private volatile boolean running = false;
    private volatile boolean connecting = false;
    private volatile boolean helloReceived = false;

    private long connectStartedAt = 0L;
    private int reconnectAttempts = 0;
    private int quickDisconnectCount = 0;
    private long heartbeatIntervalMs = 0L;
    private ScheduledFuture<?> heartbeatFuture = null;
    private ScheduledFuture<?> reconnectFuture = null;
    private Integer lastSeq = null;
    private String sessionId = null;
    private int lastCloseCode = 0;
    private String lastCloseReason = "";
    private long lastHeartbeatSentAtMs = 0L;
    private long lastHeartbeatAckAtMs = 0L;
    private long heartbeatAckCount = 0L;
    private final Map<String, Long> recentInboundMessageIds = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };

    public QQChannel(Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.instanceId = "qq-" + System.currentTimeMillis() + "-" + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public String getName() {
        return NAME;
    }

    public void configure(String appId, String clientSecret, boolean sandbox) {
        String normalizedAppId = appId != null ? appId.trim() : "";
        String normalizedSecret = clientSecret != null ? clientSecret.trim() : "";
        boolean changed;

        synchronized (stateLock) {
            changed = !normalizedAppId.equals(this.appId)
                || !normalizedSecret.equals(this.clientSecret)
                || sandbox != this.useSandbox;

            this.appId = normalizedAppId;
            this.clientSecret = normalizedSecret;
            this.useSandbox = sandbox;
            this.enabled = !this.appId.isEmpty() && !this.clientSecret.isEmpty();
            this.configured = true;

            if (!changed) {
                Log.d(TAG, "QQ Channel configure skipped, no changes");
                return;
            }
        }

        Log.d(TAG, "QQ Channel configured: appId=" + normalizedAppId + ", sandbox=" + sandbox + ", enabled=" + enabled);

        if (!enabled) {
            stop();
            return;
        }

        if (running) {
            restart();
        }
    }

    public void start() {
        synchronized (stateLock) {
            if (!enabled || running) {
                return;
            }
            acquireActiveLease();
            ensureExecutorLocked();
            ensureWebSocketClientLocked();
            running = true;
            reconnectAttempts = 0;
            quickDisconnectCount = 0;
            sessionId = null;
            lastSeq = null;
        }

        Log.d(TAG, "QQ Channel starting gateway session");
        connectAsync(0L);
    }

    public void stop() {
        WebSocket socketToClose;
        ScheduledExecutorService executorToShutdown;
        OkHttpClient clientToShutdown;
        ScheduledFuture<?> heartbeatToCancel;
        ScheduledFuture<?> reconnectToCancel;
        synchronized (stateLock) {
            running = false;
            connecting = false;
            helloReceived = false;
            socketToClose = webSocket;
            webSocket = null;
            executorToShutdown = executor;
            executor = null;
            clientToShutdown = webSocketClient;
            webSocketClient = null;
            heartbeatToCancel = heartbeatFuture;
            heartbeatFuture = null;
            reconnectToCancel = reconnectFuture;
            reconnectFuture = null;
            sessionId = null;
            lastSeq = null;
            heartbeatIntervalMs = 0L;
            lastCloseCode = 0;
            lastCloseReason = "";
        }
        releaseActiveLeaseIfOwned();

        if (socketToClose != null) {
            try {
                socketToClose.close(1000, "stopped");
            } catch (Exception ignored) {
            }
            try {
                socketToClose.cancel();
            } catch (Exception ignored) {
            }
        }
        if (heartbeatToCancel != null) {
            heartbeatToCancel.cancel(true);
        }
        if (reconnectToCancel != null) {
            reconnectToCancel.cancel(true);
        }
        if (executorToShutdown != null) {
            executorToShutdown.shutdownNow();
        }
        if (clientToShutdown != null) {
            clientToShutdown.dispatcher().executorService().shutdown();
            clientToShutdown.connectionPool().evictAll();
        }
        Log.d(TAG, "QQ Channel stopped");
    }

    public void restart() {
        Log.d(TAG, "QQ Channel restarting");
        stop();
        start();
    }

    @Override
    public void sendMessage(String chatId, String content) {
        if (!enabled) {
            throw new IllegalStateException("QQ channel not enabled");
        }

        ensureValidToken();

        try {
            OutboundTarget target = parseTarget(chatId);
            String normalizedContent = content != null ? content.trim() : "";
            List<MediaTag> mediaTags = extractMediaTags(normalizedContent);

            if (mediaTags.isEmpty()) {
                JSONObject resp = sendTextMessage(target, normalizedContent);
                Log.d(TAG, "QQ outbound sent to " + chatId + ", msgId=" + resp.optString("id", ""));
                return;
            }

            String plainText = stripMediaTags(normalizedContent);
            boolean sentAnyMedia = false;
            for (int i = 0; i < mediaTags.size(); i++) {
                MediaTag mediaTag = mediaTags.get(i);
                String caption = (!plainText.isEmpty() && i == 0 && ("qqimg".equals(mediaTag.type) || "qqmedia".equals(mediaTag.type)))
                    ? plainText
                    : null;
                JSONObject resp = sendMediaTag(target, mediaTag, caption);
                sentAnyMedia = true;
                Log.d(TAG, "QQ outbound media sent to " + chatId + ", type=" + mediaTag.type + ", msgId=" + resp.optString("id", ""));
            }

            if (!sentAnyMedia && !plainText.isEmpty()) {
                JSONObject resp = sendTextMessage(target, plainText);
                Log.d(TAG, "QQ fallback text sent to " + chatId + ", msgId=" + resp.optString("id", ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message to " + chatId, e);
            throw new RuntimeException("QQ send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void setMessageListener(MessageListener listener) {
        this.listener = listener;
    }

    public void injectInboundMessage(String chatId, String content) {
        if (listener != null) {
            listener.onMessage(chatId, content);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isOnline() {
        return running && webSocket != null && (helloReceived || (sessionId != null && !sessionId.isEmpty()));
    }

    public String getHeartbeatStatus() {
        long now = System.currentTimeMillis();
        boolean socketOpen = running && webSocket != null;
        boolean sessionReady = socketOpen && (helloReceived || (sessionId != null && !sessionId.isEmpty()));
        boolean heartbeatActive = lastHeartbeatSentAtMs > 0L || lastHeartbeatAckAtMs > 0L || heartbeatAckCount > 0L;
        String state = sessionReady ? (heartbeatActive ? "online" : "ready") : (running ? "connecting" : "offline");
        long ackAgeMs = lastHeartbeatAckAtMs > 0L ? Math.max(0L, now - lastHeartbeatAckAtMs) : -1L;
        long sendAgeMs = lastHeartbeatSentAtMs > 0L ? Math.max(0L, now - lastHeartbeatSentAtMs) : -1L;
        StringBuilder sb = new StringBuilder();
        sb.append("QQ ").append(state);
        sb.append(" | ack=").append(heartbeatAckCount);
        sb.append(" | last_ack=").append(ackAgeMs >= 0L ? (ackAgeMs / 1000L) + "s" : (sessionReady ? "waiting" : "never"));
        sb.append(" | last_send=").append(sendAgeMs >= 0L ? (sendAgeMs / 1000L) + "s" : (sessionReady ? "waiting" : "never"));
        if (lastCloseCode != 0) {
            sb.append(" | close=").append(lastCloseCode);
            if (lastCloseReason != null && !lastCloseReason.isEmpty()) {
                sb.append("/").append(lastCloseReason);
            }
        }
        return sb.toString();
    }

    private void ensureExecutorLocked() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "QQGateway");
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }
    }

    private void ensureWebSocketClientLocked() {
        if (webSocketClient == null) {
            webSocketClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
        }
    }

    private void connectAsync(long delayMs) {
        ScheduledExecutorService currentExecutor;
        synchronized (stateLock) {
            if (!ownsActiveLease()) {
                return;
            }
            if (!running) {
                return;
            }
            ensureExecutorLocked();
            if (reconnectFuture != null) {
                reconnectFuture.cancel(true);
                reconnectFuture = null;
            }
            currentExecutor = executor;
        }

        Runnable connectTask = new Runnable() {
            @Override
            public void run() {
                connectInternal();
            }
        };

        if (delayMs > 0L) {
            currentExecutor.schedule(connectTask, delayMs, TimeUnit.MILLISECONDS);
        } else {
            currentExecutor.execute(connectTask);
        }
    }

    private void connectInternal() {
        synchronized (stateLock) {
            if (!ownsActiveLease()) {
                Log.w(TAG, "QQ connect aborted because this instance lost the active lease");
                return;
            }
            if (!running || connecting) {
                return;
            }
            connecting = true;
            helloReceived = false;
            connectStartedAt = System.currentTimeMillis();
        }

        try {
            ensureValidToken();
            String gatewayUrl = getGatewayUrl();
            Log.d(TAG, "Connecting to QQ gateway: " + gatewayUrl);

            Request request = new Request.Builder().url(gatewayUrl).build();
            WebSocket newSocket = webSocketClient.newWebSocket(request, new GatewayListener());
            synchronized (stateLock) {
                webSocket = newSocket;
            }
        } catch (Exception e) {
            synchronized (stateLock) {
                connecting = false;
            }
            Log.e(TAG, "QQ gateway connect failed", e);
            scheduleReconnect("connect failed: " + e.getMessage(), false);
        }
    }

    private void handleWebSocketOpen(WebSocket socket) {
        synchronized (stateLock) {
            if (!ownsActiveLease()) {
                Log.w(TAG, "Closing QQ socket from stale instance after lease loss");
                try {
                    socket.close(1000, "stale_instance");
                } catch (Exception ignored) {
                }
                return;
            }
            if (socket != webSocket) {
                Log.d(TAG, "Ignoring open from stale QQ socket");
                return;
            }
            connecting = false;
            connectStartedAt = System.currentTimeMillis();
        }
        Log.d(TAG, "QQ gateway socket opened");
    }

    private void handleGatewayText(WebSocket socket, String text) {
        try {
            synchronized (stateLock) {
                if (!ownsActiveLease()) {
                    Log.w(TAG, "Ignoring QQ message after lease loss");
                    try {
                        socket.close(1000, "stale_instance");
                    } catch (Exception ignored) {
                    }
                    return;
                }
                if (socket != webSocket) {
                    Log.d(TAG, "Ignoring message from stale QQ socket");
                    return;
                }
            }
            JSONObject payload = new JSONObject(text);
            if (!payload.isNull("s")) {
                lastSeq = payload.optInt("s");
            }

            int op = payload.optInt("op", -1);
            String eventType = payload.optString("t", "");
            JSONObject data = payload.optJSONObject("d");

            switch (op) {
                case 10:
                    handleHello(data);
                    break;
                case 11:
                    lastHeartbeatAckAtMs = System.currentTimeMillis();
                    heartbeatAckCount++;
                    break;
                case 0:
                    handleDispatch(eventType, data);
                    break;
                case 7:
                    Log.w(TAG, "QQ gateway requested reconnect");
                    scheduleReconnect("server requested reconnect", true);
                    break;
                case 9:
                    Log.w(TAG, "QQ invalid session, dropping saved session");
                    sessionId = null;
                    lastSeq = null;
                    scheduleReconnect("invalid session", true);
                    break;
                default:
                    Log.d(TAG, "QQ gateway op=" + op + ", t=" + eventType);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse QQ gateway payload: " + text, e);
        }
    }

    private void handleHello(JSONObject data) throws IOException {
        if (data == null) {
            throw new IOException("QQ gateway hello missing data");
        }

        heartbeatIntervalMs = data.optLong("heartbeat_interval", 0L);
        helloReceived = true;
        reconnectAttempts = 0;
        quickDisconnectCount = 0;

        if (heartbeatIntervalMs > 0L) {
            scheduleHeartbeat();
        }

        if (sessionId != null && lastSeq != null) {
            Log.d(TAG, "Resuming QQ session: " + sessionId + ", seq=" + lastSeq);
            sendGatewayJson(buildResumePayload());
        } else {
            Log.d(TAG, "Identifying QQ gateway session");
            sendGatewayJson(buildIdentifyPayload());
        }
    }

    private void handleDispatch(String eventType, JSONObject data) {
        if ("READY".equals(eventType)) {
            sessionId = data != null ? data.optString("session_id", null) : null;
            cancelReconnect("READY");
            Log.d(TAG, "QQ gateway READY, sessionId=" + sessionId);
            return;
        }
        if ("RESUMED".equals(eventType)) {
            cancelReconnect("RESUMED");
            Log.d(TAG, "QQ gateway RESUMED");
            return;
        }
        if (data == null) {
            return;
        }

        if ("C2C_MESSAGE_CREATE".equals(eventType)) {
            String openId = data.optJSONObject("author") != null
                ? data.optJSONObject("author").optString("user_openid", "")
                : "";
            String content = buildInboundContent(data.optString("content", ""), data.optJSONArray("attachments"));
            String messageId = data.optString("id", "");
            if (shouldDropInboundDuplicate("c2c:" + openId, messageId)) {
                Log.d(TAG, "Drop duplicate QQ inbound C2C: " + openId + ", msgId=" + messageId);
                return;
            }
            Log.d(TAG, "QQ inbound C2C: " + openId + ", msgId=" + messageId + ", content=" + content);
            dispatchInbound("c2c:" + openId, content);
            return;
        }

        if ("GROUP_AT_MESSAGE_CREATE".equals(eventType)) {
            String groupOpenId = data.optString("group_openid", "");
            String content = buildInboundContent(data.optString("content", ""), data.optJSONArray("attachments"));
            String messageId = data.optString("id", "");
            if (shouldDropInboundDuplicate("group:" + groupOpenId, messageId)) {
                Log.d(TAG, "Drop duplicate QQ inbound GROUP: " + groupOpenId + ", msgId=" + messageId);
                return;
            }
            Log.d(TAG, "QQ inbound GROUP: " + groupOpenId + ", msgId=" + messageId + ", content=" + content);
            dispatchInbound("group:" + groupOpenId, content);
            return;
        }

        if ("AT_MESSAGE_CREATE".equals(eventType) || "DIRECT_MESSAGE_CREATE".equals(eventType)) {
            String authorId = data.optJSONObject("author") != null
                ? data.optJSONObject("author").optString("id", "")
                : "";
            String content = buildInboundContent(data.optString("content", ""), data.optJSONArray("attachments"));
            String messageId = data.optString("id", "");
            if (shouldDropInboundDuplicate("c2c:" + authorId, messageId)) {
                Log.d(TAG, "Drop duplicate QQ inbound " + eventType + ": " + authorId + ", msgId=" + messageId);
                return;
            }
            Log.d(TAG, "QQ inbound " + eventType + ": " + authorId + ", msgId=" + messageId + ", content=" + content);
            dispatchInbound("c2c:" + authorId, content);
        }
    }

    private boolean shouldDropInboundDuplicate(String chatId, String messageId) {
        if (chatId == null || chatId.isEmpty() || messageId == null || messageId.isEmpty()) {
            return false;
        }
        String key = chatId + "|" + messageId;
        synchronized (recentInboundMessageIds) {
            if (recentInboundMessageIds.containsKey(key)) {
                return true;
            }
            recentInboundMessageIds.put(key, System.currentTimeMillis());
            return false;
        }
    }

    private void dispatchInbound(String chatId, String content) {
        if (!ownsActiveLease()) {
            Log.w(TAG, "QQ inbound ignored because this instance lost the active lease");
            stop();
            return;
        }
        if (listener == null) {
            Log.w(TAG, "QQ inbound dropped, listener is null");
            return;
        }
        if (chatId == null || chatId.isEmpty()) {
            Log.w(TAG, "QQ inbound dropped, chatId empty");
            return;
        }
        String normalizedContent = content != null ? content.trim() : "";
        listener.onMessage(chatId, normalizedContent);
        Log.d(TAG, "QQ inbound dispatched to manager: " + chatId);
    }

    private void scheduleHeartbeat() {
        ScheduledExecutorService currentExecutor = executor;
        if (currentExecutor == null || heartbeatIntervalMs <= 0L) {
            return;
        }

        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }

        heartbeatFuture = currentExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!running || !ownsActiveLease()) {
                    return;
                }
                WebSocket currentSocket = webSocket;
                if (currentSocket == null) {
                    return;
                }
                try {
                    JSONObject heartbeat = new JSONObject();
                    heartbeat.put("op", 1);
                    if (lastSeq == null) {
                        heartbeat.put("d", JSONObject.NULL);
                    } else {
                        heartbeat.put("d", lastSeq);
                    }
                    boolean sent = currentSocket.send(heartbeat.toString());
                    lastHeartbeatSentAtMs = System.currentTimeMillis();
                    if (!sent) {
                        Log.w(TAG, "QQ heartbeat send returned false");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "QQ heartbeat failed", e);
                }
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private String buildIdentifyPayload() {
        try {
            JSONObject payload = new JSONObject();
            JSONObject data = new JSONObject();
            data.put("token", "QQBot " + accessToken);
            data.put("intents", DEFAULT_INTENTS);
            data.put("shard", new org.json.JSONArray().put(0).put(1));
            payload.put("op", 2);
            payload.put("d", data);
            return payload.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build QQ identify payload", e);
        }
    }

    private String buildResumePayload() {
        try {
            JSONObject payload = new JSONObject();
            JSONObject data = new JSONObject();
            data.put("token", "QQBot " + accessToken);
            data.put("session_id", sessionId);
            data.put("seq", lastSeq != null ? lastSeq : JSONObject.NULL);
            payload.put("op", 6);
            payload.put("d", data);
            return payload.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build QQ resume payload", e);
        }
    }

    private void sendGatewayJson(String payload) throws IOException {
        WebSocket currentSocket = webSocket;
        if (currentSocket == null || !currentSocket.send(payload)) {
            throw new IOException("QQ gateway send failed");
        }
    }

    private void handleWebSocketClosed(WebSocket socket, int code, String reason, boolean fromFailure) {
        boolean shouldReconnect;
        long lifetimeMs = System.currentTimeMillis() - connectStartedAt;

        synchronized (stateLock) {
            if (socket != webSocket) {
                Log.d(TAG, "Ignoring close from stale QQ socket, code=" + code + ", reason=" + reason);
                return;
            }
            connecting = false;
            helloReceived = false;
            lastCloseCode = code;
            lastCloseReason = reason != null ? reason : "";
            if (lifetimeMs > 0 && lifetimeMs < QUICK_DISCONNECT_THRESHOLD_MS) {
                quickDisconnectCount++;
            } else {
                quickDisconnectCount = 0;
            }
            shouldReconnect = running;
            webSocket = null;
        }

        Log.w(TAG, "QQ gateway closed, code=" + code + ", reason=" + reason + ", failure=" + fromFailure);

        if (!shouldReconnect) {
            return;
        }

        boolean refreshToken = fromFailure || code == 4004 || code == 4009 || code == 4010 || code == 4011;
        scheduleReconnect("socket closed: " + code + "/" + reason, refreshToken);
    }

    private void cancelReconnect(String source) {
        synchronized (stateLock) {
            reconnectAttempts = 0;
            if (reconnectFuture != null) {
                reconnectFuture.cancel(true);
                reconnectFuture = null;
                Log.d(TAG, "Cancelled pending QQ reconnect after " + source);
            }
        }
    }

    private void scheduleReconnect(String reason, boolean refreshTokenFirst) {
        ScheduledExecutorService currentExecutor;
        synchronized (stateLock) {
            if (!ownsActiveLease()) {
                return;
            }
            if (!running) {
                return;
            }
            ensureExecutorLocked();
            currentExecutor = executor;
            if (reconnectFuture != null && !reconnectFuture.isDone()) {
                Log.d(TAG, "QQ reconnect already pending, skip duplicate. reason=" + reason);
                return;
            }
            if (refreshTokenFirst) {
                accessToken = "";
                tokenExpireTime = 0L;
            }
        }

        int index = Math.min(reconnectAttempts, RECONNECT_DELAYS_MS.length - 1);
        long delayMs = RECONNECT_DELAYS_MS[index];
        reconnectAttempts++;

        if (quickDisconnectCount >= 3) {
            delayMs = Math.max(delayMs, 30_000L);
        }

        Log.w(TAG, "QQ reconnect scheduled in " + delayMs + "ms, reason=" + reason + ", attempt=" + reconnectAttempts);

        reconnectFuture = currentExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (stateLock) {
                    reconnectFuture = null;
                }
                if (!ownsActiveLease()) {
                    return;
                }
                connectInternal();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void acquireActiveLease() {
        prefs.edit().putString(KEY_ACTIVE_INSTANCE, instanceId).apply();
    }

    private boolean ownsActiveLease() {
        return instanceId.equals(prefs.getString(KEY_ACTIVE_INSTANCE, ""));
    }

    private void releaseActiveLeaseIfOwned() {
        if (ownsActiveLease()) {
            prefs.edit().remove(KEY_ACTIVE_INSTANCE).apply();
        }
    }

    private void refreshAccessToken() {
        try {
            JSONObject body = new JSONObject();
            body.put("appId", appId);
            body.put("clientSecret", clientSecret);

            String response = httpRequest("POST", QQ_TOKEN_URL, body.toString(), false);
            JSONObject resp = new JSONObject(response);
            accessToken = resp.optString("access_token", "");
            int expiresIn = resp.optInt("expires_in", 7200);
            tokenExpireTime = System.currentTimeMillis() + (expiresIn * 1000L) - TOKEN_REFRESH_BUFFER_MS;

            if (accessToken.isEmpty()) {
                throw new IOException("QQ access_token empty");
            }

            Log.d(TAG, "Access token refreshed, expires in " + expiresIn + "s");
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh access token", e);
            throw new RuntimeException("QQ token refresh failed: " + e.getMessage(), e);
        }
    }

    private void ensureValidToken() {
        if (System.currentTimeMillis() >= tokenExpireTime || accessToken.isEmpty()) {
            refreshAccessToken();
        }
    }

    private String getGatewayUrl() throws IOException {
        try {
            String response = httpRequest("GET", getApiBase() + "/gateway", null, true);
            JSONObject resp = new JSONObject(response);
            String url = resp.optString("url", "");
            if (url == null || url.isEmpty()) {
                throw new IOException("QQ gateway url missing");
            }
            return url;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse QQ gateway url", e);
        }
    }

    private String getApiBase() {
        return useSandbox ? QQ_SANDBOX_API_BASE : QQ_API_BASE;
    }

    private OutboundTarget parseTarget(String chatId) {
        String targetType = "c2c";
        String targetId = chatId;
        if (chatId.startsWith("c2c:")) {
            targetId = chatId.substring(4);
        } else if (chatId.startsWith("group:")) {
            targetType = "group";
            targetId = chatId.substring(6);
        }
        return new OutboundTarget(targetType, targetId);
    }

    private JSONObject sendTextMessage(OutboundTarget target, String content) throws Exception {
        String apiUrl = "group".equals(target.type)
            ? getApiBase() + "/v2/groups/" + target.id + "/messages"
            : getApiBase() + "/v2/users/" + target.id + "/messages";

        JSONObject body = new JSONObject();
        body.put("content", content);
        body.put("msg_type", MSG_TYPE_TEXT);
        body.put("msg_seq", 1);
        return new JSONObject(httpRequest("POST", apiUrl, body.toString(), true));
    }

    private JSONObject sendMediaTag(OutboundTarget target, MediaTag mediaTag, String content) throws Exception {
        String trimmedValue = mediaTag.value != null ? mediaTag.value.trim() : "";
        if (trimmedValue.isEmpty()) {
            throw new IOException("QQ media tag is empty: " + mediaTag.type);
        }

        int fileType;
        String uploadUrl = null;
        String fileData = null;

        if ("qqimg".equals(mediaTag.type)) {
            fileType = MEDIA_TYPE_IMAGE;
            MediaSource source = resolveImageSource(trimmedValue);
            uploadUrl = source.url;
            fileData = source.fileData;
        } else if ("qqvoice".equals(mediaTag.type)) {
            fileType = MEDIA_TYPE_VOICE;
            MediaSource source = resolveVoiceSource(trimmedValue);
            uploadUrl = source.url;
            fileData = source.fileData;
        } else if ("qqvideo".equals(mediaTag.type)) {
            fileType = MEDIA_TYPE_VIDEO;
            MediaSource source = resolveBinarySource(trimmedValue, "QQ video");
            uploadUrl = source.url;
            fileData = source.fileData;
        } else if ("qqfile".equals(mediaTag.type)) {
            fileType = MEDIA_TYPE_FILE;
            MediaSource source = resolveBinarySource(trimmedValue, "QQ file");
            uploadUrl = source.url;
            fileData = source.fileData;
        } else if ("qqmedia".equals(mediaTag.type)) {
            String inferredType = inferMediaTagType(trimmedValue);
            return sendMediaTag(target, new MediaTag(inferredType, trimmedValue), content);
        } else {
            throw new IOException("Unsupported QQ media type: " + mediaTag.type);
        }

        JSONObject uploadResult = uploadMedia(target, fileType, uploadUrl, fileData);
        String fileInfo = uploadResult.optString("file_info", "");
        if (fileInfo.isEmpty()) {
            throw new IOException("QQ media upload missing file_info");
        }
        return sendMediaMessage(target, fileInfo, content);
    }

    private JSONObject uploadMedia(OutboundTarget target, int fileType, String url, String fileData) throws Exception {
        if ((url == null || url.isEmpty()) && (fileData == null || fileData.isEmpty())) {
            throw new IOException("QQ media upload requires url or fileData");
        }

        String apiUrl = "group".equals(target.type)
            ? getApiBase() + "/v2/groups/" + target.id + "/files"
            : getApiBase() + "/v2/users/" + target.id + "/files";

        JSONObject body = new JSONObject();
        body.put("file_type", fileType);
        body.put("srv_send_msg", false);
        if (url != null && !url.isEmpty()) {
            body.put("url", url);
        } else {
            body.put("file_data", fileData);
        }
        return new JSONObject(httpRequest("POST", apiUrl, body.toString(), true));
    }

    private String inferMediaTagType(String value) {
        String lower = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (lower.startsWith("data:image/")) return "qqimg";
        if (lower.startsWith("data:audio/")) return "qqvoice";
        if (lower.startsWith("data:video/")) return "qqvideo";
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int query = lower.indexOf('?');
            if (query >= 0) {
                lower = lower.substring(0, query);
            }
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            return "qqimg";
        }
        if (lower.endsWith(".silk") || lower.endsWith(".slk") || lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".aac") || lower.endsWith(".flac") || lower.endsWith(".amr")) {
            return "qqvoice";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".mkv") || lower.endsWith(".webm")) {
            return "qqvideo";
        }
        return "qqfile";
    }

    private JSONObject sendMediaMessage(OutboundTarget target, String fileInfo, String content) throws Exception {
        String apiUrl = "group".equals(target.type)
            ? getApiBase() + "/v2/groups/" + target.id + "/messages"
            : getApiBase() + "/v2/users/" + target.id + "/messages";

        JSONObject body = new JSONObject();
        body.put("msg_type", MSG_TYPE_MEDIA);
        body.put("msg_seq", 1);
        body.put("media", new JSONObject().put("file_info", fileInfo));
        if (content != null && !content.trim().isEmpty()) {
            body.put("content", content.trim());
        }
        return new JSONObject(httpRequest("POST", apiUrl, body.toString(), true));
    }

    private List<MediaTag> extractMediaTags(String content) {
        List<MediaTag> tags = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return tags;
        }
        Matcher matcher = MEDIA_TAG_PATTERN.matcher(content);
        while (matcher.find()) {
            String type = matcher.group(1) != null ? matcher.group(1).trim().toLowerCase(Locale.ROOT) : "";
            String value = matcher.group(2) != null ? matcher.group(2).trim() : "";
            if (!type.isEmpty() && !value.isEmpty()) {
                tags.add(new MediaTag(type, normalizeMediaValue(value)));
            }
        }
        return tags;
    }

    private String stripMediaTags(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return MEDIA_TAG_PATTERN.matcher(content).replaceAll("").trim();
    }

    private String normalizeMediaValue(String value) {
        String normalized = value.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
            || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.startsWith("~" + File.separator) || normalized.equals("~")) {
            return System.getProperty("user.home", "") + normalized.substring(1);
        }
        return normalized;
    }

    private MediaSource resolveImageSource(String value) throws Exception {
        if (isHttpUrl(value)) {
            return new MediaSource(value, null);
        }
        if (value.startsWith("data:")) {
            return new MediaSource(null, extractBase64Payload(value));
        }
        File file = new File(value);
        if (!file.isFile()) {
            throw new IOException("QQ image file not found: " + value);
        }
        return new MediaSource(null, readFileAsBase64(file));
    }

    private MediaSource resolveVoiceSource(String value) throws Exception {
        if (value.startsWith("data:")) {
            return new MediaSource(null, extractBase64Payload(value));
        }
        if (isProbablyBase64(value)) {
            return new MediaSource(null, value.replaceAll("\\s+", ""));
        }
        if (isHttpUrl(value)) {
            throw new IOException("QQ voice upload requires local/base64 audio data");
        }
        File file = new File(value);
        if (!file.isFile()) {
            throw new IOException("QQ voice file not found: " + value);
        }
        return new MediaSource(null, readFileAsBase64(file));
    }

    private MediaSource resolveBinarySource(String value, String label) throws Exception {
        if (isHttpUrl(value)) {
            return new MediaSource(value, null);
        }
        if (value.startsWith("data:")) {
            return new MediaSource(null, extractBase64Payload(value));
        }
        if (isProbablyBase64(value)) {
            return new MediaSource(null, value.replaceAll("\\s+", ""));
        }
        File file = new File(value);
        if (!file.isFile()) {
            throw new IOException(label + " file not found: " + value);
        }
        return new MediaSource(null, readFileAsBase64(file));
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private boolean isProbablyBase64(String value) {
        String normalized = value.replaceAll("\\s+", "");
        return normalized.length() > 64 && normalized.matches("^[A-Za-z0-9+/=]+$");
    }

    private String extractBase64Payload(String dataUrl) throws IOException {
        int commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0 || commaIndex >= dataUrl.length() - 1) {
            throw new IOException("Invalid data URL for QQ media");
        }
        return dataUrl.substring(commaIndex + 1).trim();
    }

    private String readFileAsBase64(File file) throws IOException {
        FileInputStream inputStream = new FileInputStream(file);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
        } finally {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String buildInboundContent(String content, org.json.JSONArray attachments) {
        String normalizedContent = content != null ? content.trim() : "";
        String attachmentInfo = buildInboundAttachmentInfo(attachments);
        if (attachmentInfo.isEmpty()) {
            return normalizedContent;
        }
        if (normalizedContent.isEmpty()) {
            return attachmentInfo;
        }
        return normalizedContent + "\n" + attachmentInfo;
    }

    private String buildInboundAttachmentInfo(org.json.JSONArray attachments) {
        if (attachments == null || attachments.length() == 0) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        File downloadDir = new File(System.getProperty("java.io.tmpdir"), "ava_qqbot_downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        for (int i = 0; i < attachments.length(); i++) {
            org.json.JSONObject attachment = attachments.optJSONObject(i);
            if (attachment == null) {
                continue;
            }
            String rawUrl = attachment.optString("url", "");
            if (rawUrl.startsWith("//")) {
                rawUrl = "https:" + rawUrl;
            }
            String voiceWavUrl = attachment.optString("voice_wav_url", "");
            if (voiceWavUrl.startsWith("//")) {
                voiceWavUrl = "https:" + voiceWavUrl;
            }
            String asrReferText = attachment.optString("asr_refer_text", "").trim();
            String filename = attachment.optString("filename", "");
            String contentType = attachment.optString("content_type", "");
            if (filename.isEmpty()) {
                filename = inferAttachmentFileName(rawUrl, contentType, i);
            }
            String localPath = rawUrl.isEmpty() ? null : downloadAttachment(rawUrl, downloadDir, filename);
            String type = classifyAttachmentType(contentType, filename);
            lines.add("[QQ attachment] type=" + type + " | name=" + filename + " | content_type=" + (contentType == null || contentType.isEmpty() ? "unknown" : contentType));
            if (localPath != null) {
                lines.add("[QQ " + type + " attachment] " + localPath);
            } else if (!rawUrl.isEmpty()) {
                lines.add("[QQ " + type + " attachment] " + rawUrl);
            }
            if ("voice".equals(type)) {
                if (!voiceWavUrl.isEmpty()) {
                    lines.add("[QQ voice wav] " + voiceWavUrl);
                }
                if (!asrReferText.isEmpty()) {
                    lines.add("[QQ voice transcript] " + asrReferText);
                    lines.add("[QQ voice transcript source] asr_refer_text");
                } else {
                    lines.add("[QQ voice transcript] unavailable");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private String classifyAttachmentType(String contentType, String filename) {
        String lowerContentType = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        String lowerName = filename != null ? filename.toLowerCase(Locale.ROOT) : "";
        if (lowerContentType.startsWith("image/")) {
            return "image";
        }
        if ("voice".equals(lowerContentType) || lowerContentType.startsWith("audio/")) {
            return "voice";
        }
        if (lowerContentType.startsWith("video/")) {
            return "video";
        }
        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".gif") || lowerName.endsWith(".webp")) {
            return "image";
        }
        if (lowerName.endsWith(".amr") || lowerName.endsWith(".silk") || lowerName.endsWith(".slk") || lowerName.endsWith(".wav") || lowerName.endsWith(".mp3") || lowerName.endsWith(".ogg")) {
            return "voice";
        }
        if (lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".mov") || lowerName.endsWith(".webm")) {
            return "video";
        }
        return "file";
    }

    private String inferAttachmentFileName(String url, String contentType, int index) {
        String extension = "";
        if (url != null && !url.isEmpty()) {
            int slash = url.lastIndexOf('/');
            String tail = slash >= 0 ? url.substring(slash + 1) : url;
            int query = tail.indexOf('?');
            if (query >= 0) {
                tail = tail.substring(0, query);
            }
            if (!tail.isEmpty()) {
                return tail;
            }
        }
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.startsWith("image/")) extension = ".img";
            else if ("voice".equals(lower) || lower.startsWith("audio/")) extension = ".audio";
            else if (lower.startsWith("video/")) extension = ".video";
        }
        return "attachment_" + System.currentTimeMillis() + "_" + index + extension;
    }

    private String downloadAttachment(String url, File downloadDir, String fileName) {
        HttpURLConnection conn = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            File target = new File(downloadDir, sanitizeFileName(fileName));
            URL remote = new URL(url);
            conn = (HttpURLConnection) remote.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.connect();
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
                return null;
            }
            inputStream = conn.getInputStream();
            outputStream = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            return target.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Failed to download QQ attachment: " + url, e);
            return null;
        } finally {
            try {
                if (inputStream != null) inputStream.close();
            } catch (IOException ignored) {
            }
            try {
                if (outputStream != null) outputStream.close();
            } catch (IOException ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName != null ? fileName.trim() : "";
        if (sanitized.isEmpty()) {
            sanitized = "attachment";
        }
        return sanitized.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String httpRequest(String method, String urlStr, String body, boolean withAuth) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);

            if (withAuth) {
                if (accessToken == null || accessToken.isEmpty()) {
                    throw new IOException("QQ access token unavailable");
                }
                conn.setRequestProperty("Authorization", "QQBot " + accessToken);
            }

            if (body != null) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readAll(is);

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + urlStr + ": " + responseBody);
            }

            return responseBody;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("HTTP request failed for " + urlStr + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private final class GatewayListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            handleWebSocketOpen(webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleGatewayText(webSocket, text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleGatewayText(webSocket, bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "QQ gateway closing: " + code + ", " + reason);
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            handleWebSocketClosed(webSocket, code, reason, false);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            String reason = t != null ? t.getMessage() : "unknown";
            if (response != null) {
                try {
                    reason = reason + " | http=" + response.code();
                } catch (Exception ignored) {
                }
            }
            Log.e(TAG, "QQ gateway failure: " + reason, t);
            handleWebSocketClosed(webSocket, lastCloseCode != 0 ? lastCloseCode : 1006, reason, true);
        }
    }

    private static final class OutboundTarget {
        final String type;
        final String id;

        OutboundTarget(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    private static final class MediaTag {
        final String type;
        final String value;

        MediaTag(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    private static final class MediaSource {
        final String url;
        final String fileData;

        MediaSource(String url, String fileData) {
            this.url = url;
            this.fileData = fileData;
        }
    }
}
