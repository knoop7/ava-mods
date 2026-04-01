package com.ava.mods.mimiclaw.memory;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final String SESSION_DIR = "sessions";
    private static final Set<String> HIGH_RETENTION_TOOLS = new HashSet<>();
    private static final Set<String> MEDIUM_RETENTION_TOOLS = new HashSet<>();
    private static final Set<String> COMPACTABLE_TOOLS = new HashSet<>();

    static {
        HIGH_RETENTION_TOOLS.add("read_file");
        HIGH_RETENTION_TOOLS.add("web_search");
        HIGH_RETENTION_TOOLS.add("web_fetch");
        HIGH_RETENTION_TOOLS.add("ui_tree_dump");
        HIGH_RETENTION_TOOLS.add("peer_chat");
        HIGH_RETENTION_TOOLS.add("peer_scan");
        HIGH_RETENTION_TOOLS.add("peer_connect");
        HIGH_RETENTION_TOOLS.add("peer_status");

        MEDIUM_RETENTION_TOOLS.add("list_dir");
        MEDIUM_RETENTION_TOOLS.add("android_shell_exec");
        MEDIUM_RETENTION_TOOLS.add("terminal_exec");
        MEDIUM_RETENTION_TOOLS.add("ui_screenshot");

        COMPACTABLE_TOOLS.addAll(HIGH_RETENTION_TOOLS);
        COMPACTABLE_TOOLS.addAll(MEDIUM_RETENTION_TOOLS);
        COMPACTABLE_TOOLS.add("get_current_time");
        COMPACTABLE_TOOLS.add("get_device_info");
        COMPACTABLE_TOOLS.add("read_memory");
        COMPACTABLE_TOOLS.add("read_user");
        COMPACTABLE_TOOLS.add("read_agents");
        COMPACTABLE_TOOLS.add("read_soul");
    }
    
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
        return compressHistory(history, maxTokensEstimate, recentKeepCount, true);
    }

    public JSONArray compressHistory(JSONArray history, int maxTokensEstimate, int recentKeepCount, boolean allowHeavyCompression) {
        if (history == null || history.length() == 0) {
            return history;
        }
        
        try {
            int maxChars = maxTokensEstimate * 4; // Rough token estimate
            JSONArray compressed = applyLightweightCompression(history, recentKeepCount);
            int totalChars = estimateHistoryChars(compressed);

            if (totalChars <= maxChars || !allowHeavyCompression) {
                return compressed;
            }

            JSONArray dropped = new JSONArray();
            int len = history.length();
            int compressThreshold = Math.max(0, len - recentKeepCount);

            while (totalChars > maxChars && compressed.length() > recentKeepCount) {
                Object oldest = compressed.get(0);
                if (isRecentProtectedMessage(oldest, compressed.length(), recentKeepCount, compressThreshold, len)) {
                    break;
                }
                dropped.put(oldest);
                totalChars -= estimateHistoryItemChars(oldest);
                compressed = sliceArray(compressed, 1);
            }

            String droppedSummary = buildDroppedHistorySummary(dropped);
            if (!droppedSummary.isEmpty()) {
                String mergedSummary = droppedSummary;
                if (compressed.length() > 0) {
                    JSONObject first = compressed.optJSONObject(0);
                    if (isCompressedHistoryMessage(first)) {
                        mergedSummary = mergeCompressedHistorySummaries(
                            extractCompressedHistorySummary(first),
                            droppedSummary
                        );
                        compressed = sliceArray(compressed, 1);
                    }
                }

                JSONObject summaryMsg = createCompressedHistoryMessage(mergedSummary);
                JSONArray withSummary = new JSONArray();
                if (summaryMsg != null) {
                    withSummary.put(summaryMsg);
                }
                for (int i = 0; i < compressed.length(); i++) {
                    withSummary.put(compressed.get(i));
                }
                compressed = withSummary;
            }
            
            return compressed;
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress history", e);
            return history;
        }
    }

    private JSONArray applyLightweightCompression(JSONArray history, int recentKeepCount) throws Exception {
        JSONArray compressed = new JSONArray();
        Set<String> seenToolResults = new HashSet<>();
        int len = history.length();
        int compressThreshold = Math.max(0, len - recentKeepCount);

        for (int i = 0; i < len; i++) {
            JSONObject msg = history.getJSONObject(i);
            JSONObject newMsg = new JSONObject(msg.toString());
            boolean isRecentMsg = i >= compressThreshold;

            if (isRecentMsg) {
                compressed.put(newMsg);
                continue;
            }

            Object contentObj = newMsg.opt("content");
            if (contentObj instanceof JSONArray) {
                JSONArray contentArr = (JSONArray) contentObj;
                newMsg.put("content", compressContentArray(contentArr, seenToolResults));
            } else if (contentObj instanceof String) {
                String content = (String) contentObj;
                if (content.startsWith("[{") || content.startsWith("[")) {
                    newMsg.put("content", smartCompressToolResults(content, false, seenToolResults));
                } else if (content.length() > 2000) {
                    newMsg.put("content", content.substring(0, 1500) + "\n...[truncated]");
                }
            }

            compressed.put(newMsg);
        }

        return compressed;
    }

    public boolean shouldCompressHistory(JSONArray history, int maxTokensEstimate, int bufferTokens) {
        if (history == null || history.length() == 0) {
            return false;
        }
        int threshold = Math.max(256, maxTokensEstimate - Math.max(0, bufferTokens));
        return estimateHistoryTokens(history) > threshold;
    }

    public int estimateHistoryTokens(JSONArray history) {
        if (history == null || history.length() == 0) {
            return 0;
        }
        return estimateTokens(history.toString());
    }

    /**
     * Compress content array (tool_use or tool_result blocks).
     * Preserves tool_use structure completely, compresses tool_result content.
     */
    private JSONArray compressContentArray(JSONArray contentArr, Set<String> seenToolResults) {
        try {
            JSONArray compressed = new JSONArray();
            for (int i = 0; i < contentArr.length(); i++) {
                JSONObject block = contentArr.getJSONObject(i);
                String type = block.optString("type", "");
                
                if ("tool_use".equals(type)) {
                    // Keep tool_use intact - critical for AI to understand tool calls
                    compressed.put(block);
                } else if ("tool_result".equals(type)) {
                    // Compress tool_result content
                    JSONObject newBlock = new JSONObject();
                    newBlock.put("type", "tool_result");
                    newBlock.put("tool_use_id", block.optString("tool_use_id", ""));
                    copyToolResultMetadata(block, newBlock);
                    
                    String content = block.optString("content", "");
                    String toolName = resolveToolName(block, content);
                    int maxLen = getMaxLenForTool(toolName);
                    String summary = block.optString("summary", "");
                    boolean compressible = isToolCompressible(toolName, block.optBoolean("compressible", true));

                    if (isDuplicateToolResult(toolName, summary, content, seenToolResults)) {
                        newBlock.put("content", "[same tool result omitted]");
                        compressed.put(newBlock);
                        continue;
                    }
                    
                    if (compressible && content.length() > maxLen) {
                        content = compressToolResultContent(content, toolName, maxLen, summary);
                    }
                    newBlock.put("content", content);
                    compressed.put(newBlock);
                } else if ("text".equals(type)) {
                    // Compress text blocks if too long
                    String text = block.optString("text", "");
                    if (text.length() > 1500) {
                        JSONObject newBlock = new JSONObject();
                        newBlock.put("type", "text");
                        newBlock.put("text", text.substring(0, 1200) + "\n...[truncated]");
                        compressed.put(newBlock);
                    } else {
                        compressed.put(block);
                    }
                } else {
                    // Keep other types intact
                    compressed.put(block);
                }
            }
            return compressed;
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress content array", e);
            return contentArr;
        }
    }

    /**
     * Smart compress tool results: extract key info, preserve structure.
     * High-value tools (read_file, web_search) get more space.
     * Low-value tools (get_time, status) get minimal space.
     */
    private String smartCompressToolResults(String jsonContent, boolean isRecent, Set<String> seenToolResults) {
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
                    copyToolResultMetadata(item, newItem);
                    
                    String content = item.optString("content", "");
                    String toolName = resolveToolName(item, content);
                    String summary = item.optString("summary", "");
                    boolean compressible = isToolCompressible(toolName, item.optBoolean("compressible", true));

                    if (isDuplicateToolResult(toolName, summary, content, seenToolResults)) {
                        newItem.put("content", "[same tool result omitted]");
                        compressed.put(newItem);
                        continue;
                    }
                    
                    // Recent results: keep more
                    // Old results: keep less but preserve key info
                    int maxLen = isRecent ? 2000 : getMaxLenForTool(toolName);
                    
                    if (compressible && content.length() > maxLen) {
                        content = compressToolResultContent(content, toolName, maxLen, summary);
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
        String normalized = normalizeToolName(toolName);
        if (normalized.isEmpty()) return 500;
        if (HIGH_RETENTION_TOOLS.contains(normalized)) {
            return 1500;
        }
        if (MEDIUM_RETENTION_TOOLS.contains(normalized)) {
            return 800;
        }
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

    private void copyToolResultMetadata(JSONObject source, JSONObject target) {
        String toolName = source.optString("tool_name", "");
        if (!toolName.isEmpty()) {
            try {
                target.put("tool_name", toolName);
            } catch (Exception ignored) {
            }
        }

        String summary = source.optString("summary", "");
        if (!summary.isEmpty()) {
            try {
                target.put("summary", summary);
            } catch (Exception ignored) {
            }
        }

        try {
            target.put("compressible", source.optBoolean("compressible", true));
        } catch (Exception ignored) {
        }
    }

    private String resolveToolName(JSONObject block, String content) {
        if (block != null) {
            String toolName = block.optString("tool_name", "");
            if (!toolName.isEmpty()) {
                return normalizeToolName(toolName);
            }
        }
        return normalizeToolName(extractToolName(content));
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    private boolean isToolCompressible(String toolName, boolean defaultValue) {
        String normalized = normalizeToolName(toolName);
        if (normalized.isEmpty()) {
            return defaultValue;
        }
        if (COMPACTABLE_TOOLS.contains(normalized)) {
            return true;
        }
        return defaultValue;
    }

    private boolean isDuplicateToolResult(String toolName, String summary, String content, Set<String> seenToolResults) {
        if (seenToolResults == null) {
            return false;
        }
        String signature = buildToolResultSignature(toolName, summary, content);
        if (signature.isEmpty()) {
            return false;
        }
        if (seenToolResults.contains(signature)) {
            return true;
        }
        seenToolResults.add(signature);
        return false;
    }

    private String buildToolResultSignature(String toolName, String summary, String content) {
        String normalizedTool = toolName != null ? toolName.trim() : "";
        String normalizedSummary = summary != null ? summary.trim() : "";
        String normalizedContent = content != null ? content.trim() : "";
        if (normalizedTool.isEmpty() && normalizedSummary.isEmpty() && normalizedContent.isEmpty()) {
            return "";
        }
        int contentHash = normalizedContent.hashCode();
        return normalizedTool + "|" + normalizedSummary + "|" + contentHash;
    }

    private String compressToolResultContent(String content, String toolName, int maxLen, String summary) {
        if (content == null || content.length() <= maxLen) {
            return content;
        }

        if (summary != null && !summary.isEmpty()) {
            String summarized = summary;
            if (summarized.length() > maxLen) {
                summarized = summarized.substring(0, Math.max(0, maxLen - 16)) + "...[truncated]";
            }
            return summarized + "\n...[full result omitted]";
        }

        String extracted = extractKeyInfo(content, toolName);
        if (extracted.length() < content.length() / 2) {
            return extracted;
        }

        return content.substring(0, maxLen - 50) + "\n...[" + (content.length() - maxLen + 50) + " chars omitted]";
    }

    public String buildContextSummary(JSONArray history, int maxMessages) {
        if (history == null || history.length() == 0) {
            return "";
        }

        try {
            int start = Math.max(0, history.length() - Math.max(1, maxMessages));
            String lastUser = "";
            String lastAssistant = "";
            java.util.List<String> toolLines = new java.util.ArrayList<>();

            for (int i = start; i < history.length(); i++) {
                JSONObject msg = history.optJSONObject(i);
                if (msg == null) {
                    continue;
                }

                String role = msg.optString("role", "");
                Object contentObj = msg.opt("content");

                if (isCompressedHistoryMessage(msg)) {
                    collectCompressedHistoryLines(contentObj, toolLines);
                    continue;
                }

                if ("user".equals(role)) {
                    String extracted = extractMessageSummary(contentObj);
                    if (!extracted.isEmpty()) {
                        lastUser = extracted;
                    }
                } else if ("assistant".equals(role)) {
                    String extracted = extractMessageSummary(contentObj);
                    if (!extracted.isEmpty()) {
                        lastAssistant = extracted;
                    }
                    collectToolSummaryLines(contentObj, toolLines);
                } else if ("tool".equals(role)) {
                    collectToolSummaryLines(contentObj, toolLines);
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!lastUser.isEmpty()) {
                sb.append("- Latest user intent: ").append(lastUser).append("\n");
            }
            if (!lastAssistant.isEmpty()) {
                sb.append("- Latest assistant state: ").append(lastAssistant).append("\n");
            }
            if (!toolLines.isEmpty()) {
                sb.append("- Recent tool observations:\n");
                int keep = Math.min(4, toolLines.size());
                for (int i = Math.max(0, toolLines.size() - keep); i < toolLines.size(); i++) {
                    sb.append("  - ").append(toolLines.get(i)).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build context summary", e);
            return "";
        }
    }

    private String buildDroppedHistorySummary(JSONArray dropped) {
        String summary = buildContextSummary(dropped, Math.max(1, dropped != null ? dropped.length() : 0));
        if (summary.isEmpty()) {
            return "";
        }
        return "Earlier conversation was compacted to save tokens.\n" + summary;
    }

    private JSONObject createCompressedHistoryMessage(String summary) {
        if (summary == null || summary.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject summaryMsg = new JSONObject();
            summaryMsg.put("role", "assistant");
            summaryMsg.put("content", "[COMPRESSED_HISTORY]\n" + summary.trim());
            return summaryMsg;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCompressedHistoryMessage(JSONObject msg) {
        if (msg == null) {
            return false;
        }
        String role = msg.optString("role", "");
        if (!"assistant".equals(role)) {
            return false;
        }
        String content = msg.optString("content", "");
        return content.startsWith("[COMPRESSED_HISTORY]");
    }

    public boolean hasCompressedHistoryBoundary(JSONArray history) {
        if (history == null || history.length() == 0) {
            return false;
        }
        for (int i = 0; i < history.length(); i++) {
            if (isCompressedHistoryMessage(history.optJSONObject(i))) {
                return true;
            }
        }
        return false;
    }

    private String extractCompressedHistorySummary(JSONObject msg) {
        if (!isCompressedHistoryMessage(msg)) {
            return "";
        }
        String content = msg.optString("content", "");
        int newline = content.indexOf('\n');
        if (newline < 0) {
            return "";
        }
        return content.substring(newline + 1).trim();
    }

    private String mergeCompressedHistorySummaries(String olderSummary, String newerSummary) {
        String older = olderSummary != null ? olderSummary.trim() : "";
        String newer = newerSummary != null ? newerSummary.trim() : "";
        if (older.isEmpty()) {
            return newer;
        }
        if (newer.isEmpty()) {
            return older;
        }
        if (older.equals(newer)) {
            return older;
        }
        return older + "\n" + newer;
    }

    private String extractMessageSummary(Object contentObj) {
        if (contentObj == null) {
            return "";
        }

        try {
            if (contentObj instanceof String) {
                String value = ((String) contentObj).trim();
                if (value.startsWith("[") && value.contains("\"type\"")) {
                    JSONArray blocks = new JSONArray(value);
                    return extractTextFromBlocks(blocks);
                }
                return abbreviateLine(value, 220);
            }

            if (contentObj instanceof JSONArray) {
                return extractTextFromBlocks((JSONArray) contentObj);
            }
        } catch (Exception ignored) {
        }

        return abbreviateLine(String.valueOf(contentObj), 220);
    }

    private String extractTextFromBlocks(JSONArray blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) {
                continue;
            }
            if ("text".equals(block.optString("type", ""))) {
                String text = block.optString("text", "").trim();
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(" ");
                    }
                    sb.append(text);
                }
            }
        }
        return abbreviateLine(sb.toString(), 220);
    }

    private void collectToolSummaryLines(Object contentObj, java.util.List<String> toolLines) {
        if (contentObj == null) {
            return;
        }

        try {
            JSONArray blocks;
            if (contentObj instanceof JSONArray) {
                blocks = (JSONArray) contentObj;
            } else if (contentObj instanceof String) {
                String text = ((String) contentObj).trim();
                if (!text.startsWith("[")) {
                    return;
                }
                blocks = new JSONArray(text);
            } else {
                return;
            }

            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block == null || !"tool_result".equals(block.optString("type", ""))) {
                    continue;
                }
                String toolName = resolveToolName(block, block.optString("content", ""));
                String summary = block.optString("summary", "");
                if (summary.isEmpty()) {
                    summary = abbreviateLine(block.optString("content", ""), 160);
                }
                if (!summary.isEmpty()) {
                    toolLines.add(toolName + ": " + summary);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void collectCompressedHistoryLines(Object contentObj, java.util.List<String> toolLines) {
        if (!(contentObj instanceof String)) {
            return;
        }
        String text = ((String) contentObj).trim();
        if (!text.startsWith("[COMPRESSED_HISTORY]")) {
            return;
        }
        int newline = text.indexOf('\n');
        if (newline < 0 || newline + 1 >= text.length()) {
            return;
        }
        String body = text.substring(newline + 1).trim();
        if (body.isEmpty()) {
            return;
        }
        toolLines.add("compacted_history: " + abbreviateLine(body, 180));
    }

    private String abbreviateLine(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 16)) + "...[truncated]";
    }

    private int estimateHistoryItemChars(Object item) {
        if (item == null) {
            return 0;
        }
        if (item instanceof JSONObject) {
            JSONObject msg = (JSONObject) item;
            Object content = msg.opt("content");
            return content != null ? content.toString().length() : msg.toString().length();
        }
        return item.toString().length();
    }

    private int estimateHistoryChars(JSONArray history) {
        if (history == null || history.length() == 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < history.length(); i++) {
            total += estimateHistoryItemChars(history.opt(i));
        }
        return total;
    }

    private boolean isRecentProtectedMessage(Object item, int currentLength, int recentKeepCount, int compressThreshold, int originalLength) {
        if (!(item instanceof JSONObject)) {
            return false;
        }
        if (currentLength <= recentKeepCount) {
            return true;
        }
        JSONObject msg = (JSONObject) item;
        return isCompressedHistoryMessage(msg) && currentLength <= recentKeepCount + 1;
    }

    private JSONArray sliceArray(JSONArray array, int start) {
        JSONArray sliced = new JSONArray();
        if (array == null) {
            return sliced;
        }
        for (int i = Math.max(0, start); i < array.length(); i++) {
            try {
                sliced.put(array.get(i));
            } catch (Exception ignored) {
            }
        }
        return sliced;
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
