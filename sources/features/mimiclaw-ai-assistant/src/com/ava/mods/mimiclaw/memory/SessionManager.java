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
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sessionFile), "UTF-8"));
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
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(sessionFile, true), "UTF-8");
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

    /**
     * Compress history by truncating long tool results and old messages.
     * Keeps recent messages intact, compresses older ones.
     * @param history Full history
     * @param maxTokensEstimate Approximate token limit (chars / 4)
     * @param recentKeepCount Number of recent messages to keep uncompressed
     */
    public JSONArray compressHistory(JSONArray history, int maxTokensEstimate, int recentKeepCount) {
        if (history == null || history.length() == 0) {
            return history;
        }
        
        try {
            int totalChars = 0;
            int maxChars = maxTokensEstimate * 4; // Rough token estimate
            
            JSONArray compressed = new JSONArray();
            int len = history.length();
            int compressThreshold = Math.max(0, len - recentKeepCount);
            
            for (int i = 0; i < len; i++) {
                JSONObject msg = history.getJSONObject(i);
                JSONObject newMsg = new JSONObject(msg.toString());
                
                // Smart compress based on message age
                String content = newMsg.optString("content", "");
                boolean isRecentMsg = i >= compressThreshold;
                
                // Recent messages: keep intact for proper UI rendering
                if (isRecentMsg) {
                    // No compression for recent messages
                }
                // Old tool results: smart compress
                else if (content.startsWith("[{") || content.startsWith("[")) {
                    content = smartCompressToolResults(content, false);
                    newMsg.put("content", content);
                }
                // Old non-tool content: truncate if very long
                else if (content.length() > 4000) {
                    content = content.substring(0, 3000) + "\n...[truncated " + (content.length() - 3000) + " chars]";
                    newMsg.put("content", content);
                }
                
                String finalContent = newMsg.optString("content", "");
                totalChars += finalContent.length();
                
                // If we're over budget, start dropping oldest messages
                if (totalChars > maxChars && compressed.length() > recentKeepCount) {
                    // Remove oldest message
                    JSONArray newCompressed = new JSONArray();
                    for (int j = 1; j < compressed.length(); j++) {
                        newCompressed.put(compressed.get(j));
                    }
                    compressed = newCompressed;
                    totalChars -= 500; // Rough estimate of removed content
                }
                
                compressed.put(newMsg);
            }
            
            return compressed;
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress history", e);
            return history;
        }
    }

    /**
     * Smart compress tool results: extract key info, preserve structure.
     * High-value tools (read_file, web_search) get more space.
     * Low-value tools (get_time, status) get minimal space.
     */
    private String smartCompressToolResults(String jsonContent, boolean isRecent) {
        try {
            JSONArray arr = new JSONArray(jsonContent);
            JSONArray compressed = new JSONArray();
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String type = item.optString("type", "");
                
                if ("tool_use".equals(type)) {
                    // Keep tool_use intact (small)
                    compressed.put(item);
                } else if ("tool_result".equals(type)) {
                    JSONObject newItem = new JSONObject();
                    newItem.put("type", "tool_result");
                    newItem.put("tool_use_id", item.optString("tool_use_id", ""));
                    
                    String content = item.optString("content", "");
                    String toolName = extractToolName(content);
                    
                    // Recent results: keep more
                    // Old results: keep less but preserve key info
                    int maxLen = isRecent ? 2000 : getMaxLenForTool(toolName);
                    
                    if (content.length() > maxLen) {
                        // Try to extract key info first
                        String extracted = extractKeyInfo(content, toolName);
                        if (extracted.length() < content.length() / 2) {
                            content = extracted;
                        } else {
                            content = content.substring(0, maxLen - 50) + "\n...[" + (content.length() - maxLen + 50) + " chars omitted]";
                        }
                    }
                    newItem.put("content", content);
                    compressed.put(newItem);
                } else {
                    compressed.put(item);
                }
            }
            return compressed.toString();
        } catch (Exception e) {
            return jsonContent;
        }
    }

    /**
     * Get max length based on tool importance.
     */
    private int getMaxLenForTool(String toolName) {
        if (toolName == null) return 500;
        // High-value tools: more space
        if (toolName.contains("read_file") || toolName.contains("web_search") || 
            toolName.contains("web_fetch") || toolName.contains("ui_tree") ||
            toolName.contains("peer_chat") || toolName.contains("peer_scan") ||
            toolName.contains("peer_connect") || toolName.contains("peer_status")) {
            return 1500;
        }
        // Medium-value tools
        if (toolName.contains("list_dir") || toolName.contains("shell") || 
            toolName.contains("terminal")) {
            return 800;
        }
        // Low-value tools: minimal
        return 300;
    }

    /**
     * Extract tool name from result content (heuristic).
     */
    private String extractToolName(String content) {
        if (content == null) return "";
        // Look for common patterns
        if (content.contains("\"ok\":")) return "api_result";
        if (content.contains("file_content")) return "read_file";
        if (content.contains("search_results")) return "web_search";
        if (content.contains("ui_tree")) return "ui_tree";
        if (content.contains("Peer response:")) return "peer_chat";
        if (content.contains("OpenClaw family")) return "peer_scan";
        if (content.contains("connected to peer")) return "peer_connect";
        return "";
    }

    /**
     * Extract key info from tool result.
     */
    private String extractKeyInfo(String content, String toolName) {
        try {
            // Try to parse as JSON and extract key fields
            if (content.trim().startsWith("{")) {
                JSONObject obj = new JSONObject(content);
                JSONObject extracted = new JSONObject();
                
                // Always keep these fields
                if (obj.has("ok")) extracted.put("ok", obj.get("ok"));
                if (obj.has("error")) extracted.put("error", obj.get("error"));
                if (obj.has("status")) extracted.put("status", obj.get("status"));
                if (obj.has("message")) extracted.put("message", obj.get("message"));
                
                // Tool-specific extractions
                if (obj.has("file_content")) {
                    String fc = obj.optString("file_content", "");
                    if (fc.length() > 1000) {
                        extracted.put("file_content", fc.substring(0, 800) + "...[truncated]");
                    } else {
                        extracted.put("file_content", fc);
                    }
                }
                if (obj.has("results") && obj.get("results") instanceof JSONArray) {
                    JSONArray results = obj.getJSONArray("results");
                    if (results.length() > 5) {
                        JSONArray trimmed = new JSONArray();
                        for (int i = 0; i < 5; i++) {
                            trimmed.put(results.get(i));
                        }
                        extracted.put("results", trimmed);
                        extracted.put("_note", "showing 5 of " + results.length());
                    } else {
                        extracted.put("results", results);
                    }
                }
                
                return extracted.toString();
            }
        } catch (Exception e) {
            // Fall through to simple truncation
        }
        return content;
    }

    /**
     * Estimate token count (rough: chars / 4 for English, / 2 for CJK).
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkCount = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B) {
                cjkCount++;
            }
        }
        int nonCjk = text.length() - cjkCount;
        return (nonCjk / 4) + (cjkCount / 2);
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
