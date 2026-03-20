package com.ava.mods.mimiclaw.channel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

public class TelegramChannel implements Channel {
    private static final String TAG = "TelegramChannel";
    public static final String NAME = "telegram";

    private static final String TG_API_BASE = "https://api.telegram.org/bot";
    private static final int POLL_TIMEOUT_S = 30;
    private static final int MAX_MSG_LEN = 4096;
    private static final String PREFS_NAME = "mimiclaw_telegram";
    private static final String KEY_UPDATE_OFFSET = "update_offset";
    private static final int MAX_SEEN_MSG_KEYS = 256;

    private final SharedPreferences prefs;
    private final Set<String> seenMessageKeys = new LinkedHashSet<>();

    private String botToken = "";
    private long updateOffset = 0;
    private boolean enabled = false;

    private MessageListener listener;
    private ScheduledExecutorService pollExecutor;
    private volatile boolean running = false;

    public TelegramChannel(Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.updateOffset = Math.max(0L, prefs.getLong(KEY_UPDATE_OFFSET, 0L));
        if (this.updateOffset > 0) {
            Log.d(TAG, "Loaded Telegram update offset: " + this.updateOffset);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    public void configure(String botToken) {
        String normalizedToken = botToken != null ? botToken.trim() : "";
        boolean changed = !normalizedToken.equals(this.botToken);
        this.botToken = normalizedToken;
        this.enabled = !normalizedToken.isEmpty();
        Log.d(TAG, "Telegram Channel configured: enabled=" + enabled);
        if (!enabled) {
            stop();
            return;
        }
        if (changed && running) {
            restart();
        }
    }
    
    public void start() {
        if (!enabled || running) {
            return;
        }
        
        running = true;
        pollExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "TelegramPoll");
                thread.setDaemon(true);
                return thread;
            }
        });
        pollExecutor.execute(this::pollLoop);
        
        Log.d(TAG, "Telegram Channel started");
    }
    
    public void stop() {
        running = false;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
        Log.d(TAG, "Telegram Channel stopped");
    }

    public void restart() {
        Log.d(TAG, "Telegram Channel restarting");
        stop();
        start();
    }
    
    private void pollLoop() {
        while (running) {
            try {
                String url = TG_API_BASE + botToken + "/getUpdates?offset=" + updateOffset + "&timeout=" + POLL_TIMEOUT_S;
                String response = httpGet(url);
                
                if (response != null) {
                    processUpdates(response);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Poll error", e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }
    
    private void processUpdates(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            if (!root.optBoolean("ok", false)) {
                Log.w(TAG, "Telegram getUpdates returned non-ok: " + jsonStr);
                return;
            }

            JSONArray result = root.optJSONArray("result");
            if (result == null) {
                return;
            }

            for (int i = 0; i < result.length(); i++) {
                JSONObject update = result.getJSONObject(i);
                long updateId = update.optLong("update_id", -1);

                if (updateId >= 0) {
                    if (updateId < updateOffset) {
                        continue;
                    }
                    updateOffset = updateId + 1;
                    saveUpdateOffset();
                }

                JSONObject message = update.optJSONObject("message");
                if (message == null) {
                    message = update.optJSONObject("edited_message");
                }
                if (message == null) {
                    message = update.optJSONObject("channel_post");
                }
                if (message == null) {
                    message = update.optJSONObject("edited_channel_post");
                }
                if (message == null) {
                    continue;
                }
                
                String text = message.optString("text", "");
                if (text.isEmpty()) {
                    continue;
                }
                
                JSONObject chat = message.optJSONObject("chat");
                if (chat == null) {
                    continue;
                }

                String chatId = String.valueOf(chat.optLong("id", 0));
                if ("0".equals(chatId)) {
                    continue;
                }

                long messageId = message.optLong("message_id", -1);
                if (messageId >= 0) {
                    String msgKey = chatId + ":" + messageId;
                    if (seenMessageKeys.contains(msgKey)) {
                        Log.w(TAG, "Drop duplicate Telegram message update_id=" + updateId + " key=" + msgKey);
                        continue;
                    }
                    rememberMessageKey(msgKey);
                }

                Log.d(TAG, "Received message from " + chatId + ": " + text.substring(0, Math.min(40, text.length())));

                if (listener != null) {
                    listener.onMessage(chatId, text);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process updates", e);
        }
    }
    
    @Override
    public void sendMessage(String chatId, String content) {
        if (!enabled) {
            throw new IllegalStateException("Telegram channel not enabled");
        }
        
        try {
            int offset = 0;
            while (offset < content.length()) {
                int chunkLen = Math.min(MAX_MSG_LEN, content.length() - offset);
                String chunk = content.substring(offset, offset + chunkLen);
                
                JSONObject body = new JSONObject();
                body.put("chat_id", chatId);
                body.put("text", chunk);
                body.put("parse_mode", "Markdown");
                
                String url = TG_API_BASE + botToken + "/sendMessage";
                HttpResult response = httpPost(url, body.toString());
                boolean sentOk = response.isOkJson();

                if (!sentOk) {
                    Log.i(TAG, "Markdown send rejected for " + chatId + ", retrying without parse_mode: " + response.body);
                    body.remove("parse_mode");
                    response = httpPost(url, body.toString());
                    sentOk = response.isOkJson();
                }

                if (!sentOk) {
                    throw new IOException("Telegram send failed: HTTP " + response.code + " " + response.body);
                }
                
                offset += chunkLen;
                Log.d(TAG, "Sent chunk to " + chatId + " (" + chunkLen + " bytes)");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message to " + chatId, e);
            throw new RuntimeException("Telegram send failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void setMessageListener(MessageListener listener) {
        this.listener = listener;
    }
    
    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((POLL_TIMEOUT_S + 5) * 1000);
            conn.setReadTimeout((POLL_TIMEOUT_S + 5) * 1000);
            
            int code = conn.getResponseCode();
            
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            if (code >= 200 && code < 300) {
                return sb.toString();
            }
            throw new IOException("HTTP " + code + ": " + sb);
            
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET failed", e);
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    private HttpResult httpPost(String urlStr, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            
            int code = conn.getResponseCode();
            
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            return new HttpResult(code, sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "HTTP POST failed", e);
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }

    private void saveUpdateOffset() {
        prefs.edit().putLong(KEY_UPDATE_OFFSET, updateOffset).apply();
    }

    private void rememberMessageKey(String msgKey) {
        seenMessageKeys.add(msgKey);
        while (seenMessageKeys.size() > MAX_SEEN_MSG_KEYS) {
            String oldest = seenMessageKeys.iterator().next();
            seenMessageKeys.remove(oldest);
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body != null ? body : "";
        }

        boolean isOkJson() {
            if (code < 200 || code >= 300) {
                return false;
            }
            try {
                return new JSONObject(body).optBoolean("ok", false);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
