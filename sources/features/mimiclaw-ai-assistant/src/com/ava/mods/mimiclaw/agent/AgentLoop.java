package com.ava.mods.mimiclaw.agent;

import android.util.Log;
import com.ava.mods.mimiclaw.bus.MessageBus;
import com.ava.mods.mimiclaw.context.ContextBuilder;
import com.ava.mods.mimiclaw.llm.LlmProxy;
import com.ava.mods.mimiclaw.memory.SessionManager;
import com.ava.mods.mimiclaw.memory.MemoryStore;
import com.ava.mods.mimiclaw.skills.SkillLoader;
import com.ava.mods.mimiclaw.tools.ToolRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AgentLoop implements Runnable {
    private static final String TAG = "AgentLoop";
    private static final int MAX_TOOL_ITERATIONS = 50;

    public interface StatusListener {
        void onStatusChanged(String status, String detail);
    }
    
    private final MessageBus messageBus;
    private final LlmProxy llmProxy;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final ContextBuilder contextBuilder;
    private final StatusListener statusListener;
    private volatile String activeChannel = null;
    private volatile String activeChatId = null;
    private volatile boolean cancelRequested = false;
    private volatile String cancelChannel = null;
    private volatile String cancelChatId = null;
    private volatile boolean running = true;
    private int maxToolIterations = MAX_TOOL_ITERATIONS;
    
    public AgentLoop(
        LlmProxy llmProxy,
        SessionManager sessionManager,
        MemoryStore memoryStore,
        StatusListener statusListener
    ) {
        this.messageBus = MessageBus.getInstance();
        this.llmProxy = llmProxy;
        this.toolRegistry = ToolRegistry.getInstance();
        this.sessionManager = sessionManager;
        this.memoryStore = memoryStore;
        this.contextBuilder = new ContextBuilder(memoryStore, new SkillLoader(memoryStore.getContext()));
        this.statusListener = statusListener;
    }
    
    public void setSkillEnabledChecker(java.util.function.Function<String, Boolean> checker) {
        this.contextBuilder.setSkillEnabledChecker(checker);
    }
    
    public void setMaxToolIterations(int max) {
        this.maxToolIterations = max;
    }
    
    public void stop() {
        running = false;
    }

    public void requestCancel(String channel, String chatId) {
        cancelRequested = true;
        cancelChannel = channel;
        cancelChatId = chatId;
    }
    
    @Override
    public void run() {
        Log.d(TAG, "Agent loop started");
        
        while (running) {
            try {
                MessageBus.Message msg = messageBus.popInbound(1000);
                if (msg == null) {
                    continue;
                }
                
                Log.d(TAG, "Processing message from " + msg.channel + ":" + msg.chatId);
                notifyStatus("processing", "Received message from " + msg.channel);
                processMessage(msg);
                
            } catch (InterruptedException e) {
                Log.d(TAG, "Agent loop interrupted");
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error in agent loop", e);
            }
        }
        
        Log.d(TAG, "Agent loop stopped");
    }
    
    private void processMessage(MessageBus.Message msg) {
        try {
            activeChannel = msg.channel;
            activeChatId = msg.chatId;
            if (isCancelRequested(msg)) {
                notifyStatus("idle", "Cancelled");
                return;
            }
            if (isHeartbeatMessage(msg) && isHeartbeatChecklistEmpty()) {
                Log.d(TAG, "Heartbeat skipped: HEARTBEAT.md is empty");
                notifyStatus("idle", "Heartbeat skipped");
                return;
            }

            String sessionKey = buildSessionKey(msg);
            toolRegistry.setCurrentContext(msg.channel, msg.chatId);
            JSONArray messages = sessionManager.getHistory(sessionKey, 50);
            boolean isFirstMessage = messages.length() == 0;
            String systemPrompt = buildSystemPrompt(msg, isFirstMessage);
            
            // Save user message first (before any tool calls)
            if ("webconsole".equals(msg.channel)) {
                sessionManager.appendMessage(sessionKey, "user", msg.content);
            }
            
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", msg.content);
            messages.put(userMsg);
            
            JSONArray tools = toolRegistry.getToolsJson();
            
            String finalText = null;
            int iteration = 0;
            
            while (iteration < maxToolIterations) {
                if (isCancelRequested(msg)) {
                    notifyStatus("idle", "Cancelled");
                    return;
                }
                notifyStatus("llm_request", "Calling " + llmProxy.getProvider() + " / " + llmProxy.getModel());
                LlmProxy.Response resp = llmProxy.chatWithTools(systemPrompt, messages, tools);
                if (isCancelRequested(msg)) {
                    notifyStatus("idle", "Cancelled");
                    return;
                }
                
                if (!resp.toolUse) {
                    finalText = resp.text;
                    notifyStatus("responding", "Model returned final response");
                    break;
                }
                
                Log.d(TAG, "Tool use iteration " + (iteration + 1) + ": " + resp.calls.size() + " calls");
                notifyStatus("tool_use", "Executing " + resp.calls.size() + " tool call(s)");
                
                JSONArray assistantContent = buildAssistantContent(resp);
                JSONObject asstMsg = new JSONObject();
                asstMsg.put("role", "assistant");
                asstMsg.put("content", assistantContent);
                messages.put(asstMsg);
                
                // Save tool_use to history for webconsole
                if ("webconsole".equals(msg.channel)) {
                    sessionManager.appendMessage(sessionKey, "assistant", assistantContent.toString());
                }
                
                JSONArray toolResults = executeTools(resp, msg);
                if (isCancelRequested(msg)) {
                    notifyStatus("idle", "Cancelled");
                    return;
                }
                JSONObject resultMsg = new JSONObject();
                resultMsg.put("role", "user");
                resultMsg.put("content", toolResults);
                messages.put(resultMsg);
                
                // Save tool_result to history for webconsole
                if ("webconsole".equals(msg.channel)) {
                    sessionManager.appendMessage(sessionKey, "assistant", toolResults.toString());
                }
                
                iteration++;
            }
            
            if (finalText != null && !finalText.isEmpty()) {
                finalText = normalizeFinalText(finalText);
                // User message already saved at the beginning
                sessionManager.appendMessage(sessionKey, "assistant", finalText);

                if (isHeartbeatMessage(msg) && "HEARTBEAT_OK".equals(finalText.trim())) {
                    Log.d(TAG, "Heartbeat completed with HEARTBEAT_OK");
                } else {
                    MessageBus.Message outMsg = new MessageBus.Message(msg.channel, msg.chatId, finalText);
                    messageBus.pushOutbound(outMsg);
                    Log.d(TAG, "Response sent: " + finalText.length() + " bytes");
                    
                    // Also push heartbeat notifications to webconsole
                    if (isHeartbeatMessage(msg)) {
                        MessageBus.Message webMsg = new MessageBus.Message("webconsole", "default", "[Heartbeat] " + finalText);
                        messageBus.pushOutbound(webMsg);
                    }
                }
                notifyStatus("idle", "");
            } else {
                String detail = iteration >= maxToolIterations
                    ? "Max tool iterations reached"
                    : "Model returned empty response";
                notifyStatus("error", detail);
                MessageBus.Message errorMsg = new MessageBus.Message(
                    msg.channel, msg.chatId, "Error: " + detail
                );
                messageBus.pushOutbound(errorMsg);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to process message", e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            notifyStatus("error", detail);
            String errorText = "Error: " + detail;
            // Save error to history for webconsole
            if ("webconsole".equals(msg.channel)) {
                sessionManager.appendMessage(buildSessionKey(msg), "assistant", errorText);
            }
            MessageBus.Message errorMsg = new MessageBus.Message(
                msg.channel, msg.chatId, errorText
            );
            messageBus.pushOutbound(errorMsg);
        } finally {
            clearActiveIfMatch(msg);
        }
    }
    
    private String buildSystemPrompt(MessageBus.Message msg, boolean isFirstMessage) {
        return contextBuilder.buildSystemPrompt(msg.channel, msg.chatId, isFirstMessage);
    }

    private String buildSessionKey(MessageBus.Message msg) {
        return msg.channel + ":" + msg.chatId;
    }

    private boolean isHeartbeatMessage(MessageBus.Message msg) {
        return msg != null
            && ContextBuilder.HEARTBEAT_CHAT_ID.equals(msg.chatId)
            && msg.content != null
            && msg.content.startsWith("HEARTBEAT_TICK");
    }

    private boolean isHeartbeatChecklistEmpty() {
        String content = memoryStore.readFileByPath("HEARTBEAT.md");
        if (content == null) {
            return true;
        }
        String normalized = content
            .replaceAll("(?m)^\\s*#.*$", "")
            .replaceAll("(?m)^\\s*[-*+]\\s*$", "")
            .replaceAll("\\s+", "")
            .trim();
        return normalized.isEmpty();
    }

    private String normalizeFinalText(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return "";
        }

        String[] blocks = normalized.split("\\n+");
        List<String> kept = new ArrayList<>();
        String previousKey = null;

        for (String block : blocks) {
            String trimmed = block != null ? block.trim() : "";
            if (trimmed.isEmpty()) {
                continue;
            }

            String key = canonicalizeText(trimmed);
            if (key.isEmpty()) {
                continue;
            }

            if (key.equals(previousKey)) {
                continue;
            }

            kept.add(trimmed);
            previousKey = key;
        }

        String merged = String.join("\n", kept).trim();
        return removeRepeatedTrailingSentence(merged);
    }

    private String removeRepeatedTrailingSentence(String text) {
        String[] paragraphs = text.split("\\n");
        List<String> kept = new ArrayList<>();
        String previousKey = null;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph != null ? paragraph.trim() : "";

            String key = canonicalizeText(trimmed);
            if (key.isEmpty()) {
                continue;
            }

            if (key.equals(previousKey)) {
                continue;
            }

            kept.add(trimmed);
            previousKey = key;
        }

        return String.join("\n", kept).trim();
    }

    private String canonicalizeText(String value) {
        String lowered = value.toLowerCase(Locale.ROOT).trim();
        lowered = lowered.replaceAll("[`*_>#\\-]+", " ");
        lowered = lowered.replaceAll("[^\\p{L}\\p{N}\\s]+", " ");
        lowered = lowered.replaceAll("\\s+", " ").trim();
        return lowered;
    }
    
    private JSONArray buildAssistantContent(LlmProxy.Response resp) throws Exception {
        JSONArray content = new JSONArray();
        
        if (resp.text != null && !resp.text.isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", resp.text);
            content.put(textBlock);
        }
        
        for (LlmProxy.ToolCall call : resp.calls) {
            JSONObject toolBlock = new JSONObject();
            toolBlock.put("type", "tool_use");
            toolBlock.put("id", call.id);
            toolBlock.put("name", call.name);
            toolBlock.put("input", new JSONObject(call.input));
            content.put(toolBlock);
        }
        
        return content;
    }
    
    private JSONArray executeTools(LlmProxy.Response resp, MessageBus.Message msg) throws Exception {
        JSONArray results = new JSONArray();
        
        for (LlmProxy.ToolCall call : resp.calls) {
            if (isCancelRequested(msg)) {
                break;
            }
            String output = toolRegistry.executeTool(call.name, call.input);
            
            JSONObject resultBlock = new JSONObject();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", call.id);
            resultBlock.put("content", output);
            enrichToolResultBlock(call.name, output, resultBlock);
            results.put(resultBlock);
            
            Log.d(TAG, "Tool " + call.name + " result: " + output.length() + " bytes");
        }
        
        return results;
    }

    private void enrichToolResultBlock(String toolName, String output, JSONObject resultBlock) {
        if (output == null || output.isEmpty()) {
            return;
        }

        try {
            JSONObject json = new JSONObject(output);
            if ("ui_screenshot".equals(toolName)) {
                if (json.optBoolean("ok", false)) {
                    String path = json.optString("path", "");
                    if (!path.isEmpty()) {
                        resultBlock.put("image_path", path);
                    }
                }
                return;
            }

            if ("ui_tree_dump".equals(toolName)) {
                JSONObject fallback = json.optJSONObject("fallbackScreenshot");
                if (fallback != null && fallback.optBoolean("ok", false)) {
                    String path = fallback.optString("path", "");
                    if (!path.isEmpty()) {
                        resultBlock.put("image_path", path);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyStatus(String status, String detail) {
        if (statusListener != null) {
            try {
                statusListener.onStatusChanged(status, detail != null ? detail : "");
            } catch (Exception e) {
                Log.w(TAG, "Status listener failed", e);
            }
        }
    }

    private boolean isCancelRequested(MessageBus.Message msg) {
        if (!cancelRequested || msg == null) {
            return false;
        }
        boolean channelMatch = cancelChannel == null || cancelChannel.equals(msg.channel);
        boolean chatMatch = cancelChatId == null || cancelChatId.equals(msg.chatId);
        return channelMatch && chatMatch;
    }

    private void clearActiveIfMatch(MessageBus.Message msg) {
        if (msg == null) {
            return;
        }
        if (msg.channel != null && msg.channel.equals(activeChannel)
            && msg.chatId != null && msg.chatId.equals(activeChatId)) {
            activeChannel = null;
            activeChatId = null;
            if (isCancelRequested(msg)) {
                cancelRequested = false;
                cancelChannel = null;
                cancelChatId = null;
            }
        }
    }
}
