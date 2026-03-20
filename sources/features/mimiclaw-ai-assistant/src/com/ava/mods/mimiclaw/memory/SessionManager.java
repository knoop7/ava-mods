package com.ava.mods.mimiclaw.memory;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final String SESSION_DIR = "sessions";
    
    private final Context context;
    private final Map<String, JSONArray> sessionCache = new HashMap<>();
    
    public SessionManager(Context context) {
        this.context = context;
        ensureSessionDir();
    }
    
    private void ensureSessionDir() {
        File dir = new File(context.getFilesDir(), SESSION_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    public JSONArray getHistory(String chatId, int maxMessages) {
        if (sessionCache.containsKey(chatId)) {
            return cloneHistory(trimHistory(sessionCache.get(chatId), maxMessages));
        }
        
        File sessionFile = getSessionFile(chatId);
        if (!sessionFile.exists()) {
            JSONArray empty = new JSONArray();
            sessionCache.put(chatId, empty);
            return empty;
        }
        
        try {
            BufferedReader reader = new BufferedReader(new FileReader(sessionFile));
            JSONArray history = new JSONArray();
            String line;
            
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject msg = new JSONObject(line);
                history.put(msg);
            }
            reader.close();
            
            sessionCache.put(chatId, history);
            return cloneHistory(trimHistory(history, maxMessages));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load session: " + chatId, e);
            JSONArray empty = new JSONArray();
            sessionCache.put(chatId, empty);
            return empty;
        }
    }
    
    public void appendMessage(String chatId, String role, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            msg.put("timestamp", System.currentTimeMillis());
            
            JSONArray history = sessionCache.get(chatId);
            if (history == null) {
                history = new JSONArray();
                sessionCache.put(chatId, history);
            }
            history.put(msg);
            
            File sessionFile = getSessionFile(chatId);
            FileWriter writer = new FileWriter(sessionFile, true);
            writer.write(msg.toString() + "\n");
            writer.close();
            
            Log.d(TAG, "Appended message to session: " + chatId);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to append message to session: " + chatId, e);
        }
    }
    
    public void clearSession(String chatId) {
        sessionCache.remove(chatId);
        File sessionFile = getSessionFile(chatId);
        if (sessionFile.exists()) {
            sessionFile.delete();
        }
        Log.d(TAG, "Cleared session: " + chatId);
    }
    
    private File getSessionFile(String chatId) {
        String safeId = chatId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(context.getFilesDir(), SESSION_DIR + "/" + safeId + ".jsonl");
    }
    
    private JSONArray trimHistory(JSONArray history, int maxMessages) {
        if (history.length() <= maxMessages) {
            return history;
        }
        
        try {
            JSONArray trimmed = new JSONArray();
            int start = history.length() - maxMessages;
            for (int i = start; i < history.length(); i++) {
                trimmed.put(history.get(i));
            }
            return trimmed;
        } catch (Exception e) {
            Log.e(TAG, "Failed to trim history", e);
            return history;
        }
    }

    private JSONArray cloneHistory(JSONArray history) {
        JSONArray copy = new JSONArray();
        if (history == null) {
            return copy;
        }
        try {
            for (int i = 0; i < history.length(); i++) {
                Object item = history.get(i);
                if (item instanceof JSONObject) {
                    copy.put(new JSONObject(item.toString()));
                } else if (item instanceof JSONArray) {
                    copy.put(new JSONArray(item.toString()));
                } else {
                    copy.put(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clone history", e);
            return history;
        }
        return copy;
    }
}
