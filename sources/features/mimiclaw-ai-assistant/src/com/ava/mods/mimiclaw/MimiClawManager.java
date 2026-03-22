package com.ava.mods.mimiclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import com.ava.mods.mimiclaw.agent.AgentLoop;
import com.ava.mods.mimiclaw.bus.MessageBus;
import com.ava.mods.mimiclaw.context.ContextBuilder;
import com.ava.mods.mimiclaw.llm.LlmProxy;
import com.ava.mods.mimiclaw.memory.SessionManager;
import com.ava.mods.mimiclaw.memory.MemoryStore;
import com.ava.mods.mimiclaw.cron.CronService;
import com.ava.mods.mimiclaw.tools.ToolRegistry;
import com.ava.mods.mimiclaw.channel.ChannelManager;
import com.ava.mods.mimiclaw.channel.AndroidChannel;
import com.ava.mods.mimiclaw.channel.QQChannel;
import com.ava.mods.mimiclaw.channel.TelegramChannel;
import com.ava.mods.mimiclaw.channel.WebConsoleServer;
import org.json.JSONArray;
import org.json.JSONObject;

public class MimiClawManager {
    private static final String TAG = "MimiClawManager";
    private static final String AI_BROWSER_STATE_PATH = "browser/ai_browser_state.json";
    private static final String AI_BROWSER_EVENT_PREFIX = "[BROWSER_UI_EVENT]";
    private static final String ACTION_AI_BROWSER_UI_EVENT = "com.example.ava.AI_BROWSER_UI_EVENT";
    private static final long BUILTIN_HEARTBEAT_INTERVAL_S = 30 * 60L;
    private static volatile MimiClawManager instance;
    
    private Context context;
    private LlmProxy llmProxy;
    private SessionManager sessionManager;
    private MemoryStore memoryStore;
    private CronService cronService;
    private ToolRegistry toolRegistry;
    private ChannelManager channelManager;
    private AndroidChannel androidChannel;
    private QQChannel qqChannel;
    private TelegramChannel telegramChannel;
    private WebConsoleServer webConsoleServer;
    private AgentLoop agentLoop;
    private Thread agentThread;
    private MessageBus messageBus;
    private BroadcastReceiver aiBrowserUiReceiver;
    
    private String lastResponse = "";
    private String agentStatus = "idle";
    private String lastError = "";
    private boolean webConsoleEnabled = false;
    private String webConsolePassword = "openclaw";
    
    private MimiClawManager(Context context) {
        this.context = context;
        this.messageBus = MessageBus.getInstance();
        this.llmProxy = new LlmProxy();
        this.sessionManager = new SessionManager(context);
        this.memoryStore = new MemoryStore(context);
        this.cronService = new CronService(context);
        
        this.toolRegistry = ToolRegistry.getInstance();
        this.toolRegistry.init(context, memoryStore, cronService);
        this.toolRegistry.setSkillEnabledChecker(skillId -> isSkillEnabled(skillId));
        
        this.channelManager = ChannelManager.getInstance();
        this.androidChannel = new AndroidChannel();
        this.qqChannel = new QQChannel(context);
        this.telegramChannel = new TelegramChannel(context);
        this.webConsoleServer = new WebConsoleServer(this);
        this.channelManager.registerChannel(androidChannel);
        this.channelManager.registerChannel(qqChannel);
        this.channelManager.registerChannel(telegramChannel);
        
        this.agentLoop = new AgentLoop(llmProxy, sessionManager, memoryStore, (status, detail) -> {
            agentStatus = status != null ? status : "idle";
            if ("error".equals(agentStatus)) {
                lastError = detail != null ? detail : "";
                if (!lastError.isEmpty()) {
                    lastResponse = "Error: " + lastError;
                }
            } else if ("idle".equals(agentStatus)) {
                lastError = "";
            }
            Log.d(TAG, "Status -> " + agentStatus + (detail != null && !detail.isEmpty() ? " (" + detail + ")" : ""));
        });
        
        // Connect skill config to context builder (hot reload)
        this.agentLoop.setSkillEnabledChecker(skillId -> isSkillEnabled(skillId));
        
        startAgentLoop();
        channelManager.startOutboundDispatcher();
        cronService.ensureBuiltinHeartbeatJob(
            AndroidChannel.NAME,
            ContextBuilder.HEARTBEAT_CHAT_ID,
            "HEARTBEAT_TICK: Read HEARTBEAT.md and return HEARTBEAT_OK if nothing needs proactive notification.",
            BUILTIN_HEARTBEAT_INTERVAL_S
        );
        cronService.start();
        loadWebConsolePrefs();
        refreshWebConsoleServer();
        registerAiBrowserUiReceiver();
        
        Log.d(TAG, "OpenClaw(Mini) initialized");
    }

    public static MimiClawManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MimiClawManager.class) {
                if (instance == null) {
                    instance = new MimiClawManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }
    
    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        
        Log.d(TAG, "Config: " + key + " = " + value);
        
        switch (key) {
            case "api_key":
                llmProxy.setApiKey(value);
                getPrefs().edit().putString("cfg_api_key", value).apply();
                break;
            case "model":
                llmProxy.setModel(value);
                getPrefs().edit().putString("cfg_model", value).apply();
                break;
            case "provider":
                llmProxy.setProvider(value);
                getPrefs().edit().putString("cfg_provider", value).apply();
                break;
            case "max_tokens":
                try {
                    int tokens = Integer.parseInt(value);
                    llmProxy.setMaxTokens(tokens);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid max_tokens: " + value);
                }
                break;
            case "max_tool_iterations":
                try {
                    int iterations = Integer.parseInt(value);
                    agentLoop.setMaxToolIterations(iterations);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid max_tool_iterations: " + value);
                }
                break;
            case "custom_api_url":
                llmProxy.setCustomApiUrl(value);
                getPrefs().edit().putString("cfg_custom_api_url", value).apply();
                break;
            case "tavily_key":
                toolRegistry.setTavilyKey(value);
                break;
            case "qq_app_id":
                qqAppId = value;
                updateQQChannel();
                break;
            case "qq_client_secret":
                qqClientSecret = value;
                updateQQChannel();
                break;
            case "qq_sandbox":
                qqSandbox = "true".equalsIgnoreCase(value);
                updateQQChannel();
                break;
            case "telegram_token":
                telegramToken = value;
                updateTelegramChannel();
                break;
            case "web_console_enabled":
                webConsoleEnabled = "true".equalsIgnoreCase(value);
                refreshWebConsoleServer();
                break;
            case "web_console_password":
                String normalizedPassword = value == null ? "" : value.trim();
                if (normalizedPassword.isEmpty()) {
                    break;
                }
                boolean keepSavedPassword = "openclaw".equals(normalizedPassword)
                    && webConsolePassword != null
                    && !webConsolePassword.trim().isEmpty()
                    && !"openclaw".equals(webConsolePassword.trim());
                if (!keepSavedPassword) {
                    webConsolePassword = normalizedPassword;
                    saveWebConsolePassword(webConsolePassword);
                }
                refreshWebConsoleServer();
                break;
        }
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences("openclaw_mini", Context.MODE_PRIVATE);
    }

    private void loadWebConsolePrefs() {
        String saved = getPrefs().getString("web_console_password", "");
        if (saved != null && !saved.trim().isEmpty()) {
            webConsolePassword = saved.trim();
        }
        
        String provider = getConfigValue("provider", getPrefs().getString("cfg_provider", ""));
        if (provider != null && !provider.isEmpty()) {
            llmProxy.setProvider(provider);
        }
        String model = getConfigValue("model", getPrefs().getString("cfg_model", ""));
        if (model != null && !model.isEmpty()) {
            llmProxy.setModel(model);
        }
        String apiKey = getConfigValue("api_key", getPrefs().getString("cfg_api_key", ""));
        if (apiKey != null && !apiKey.isEmpty()) {
            llmProxy.setApiKey(apiKey);
        }
        String customApiUrl = getConfigValue("custom_api_url", getPrefs().getString("cfg_custom_api_url", ""));
        if (customApiUrl != null && !customApiUrl.isEmpty()) {
            llmProxy.setCustomApiUrl(customApiUrl);
        }
        String maxToolIterations = getConfigValue("max_tool_iterations", "30");
        if (maxToolIterations != null && !maxToolIterations.isEmpty()) {
            try {
                agentLoop.setMaxToolIterations(Integer.parseInt(maxToolIterations));
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid persisted max_tool_iterations: " + maxToolIterations);
            }
        }
    }

    private void saveWebConsolePassword(String password) {
        getPrefs().edit().putString("web_console_password", password).apply();
    }

    private void refreshWebConsoleServer() {
        if (webConsoleEnabled) {
            webConsoleServer.start();
            Log.d(TAG, "Web console enabled=" + webConsoleServer.isRunning()
                + " port=" + webConsoleServer.getPort()
                + " error=" + webConsoleServer.getLastError());
        } else {
            webConsoleServer.stop();
            Log.d(TAG, "Web console disabled");
        }
    }
    
    private String qqAppId = "";
    private String qqClientSecret = "";
    private boolean qqSandbox = false;
    private String telegramToken = "";
    
    private void updateQQChannel() {
        if (qqChannel != null) {
            qqChannel.configure(qqAppId, qqClientSecret, qqSandbox);
            if (!qqAppId.isEmpty() && !qqClientSecret.isEmpty()) {
                qqChannel.start();
                Log.d(TAG, "QQ Channel configured and started");
            } else {
                Log.d(TAG, "QQ Channel config incomplete, waiting for remaining fields");
            }
        }
    }
    
    private void updateTelegramChannel() {
        if (telegramChannel != null) {
            telegramChannel.configure(telegramToken);
            if (!telegramToken.isEmpty()) {
                telegramChannel.start();
                Log.d(TAG, "Telegram Channel configured and started");
            } else {
                Log.d(TAG, "Telegram Channel disabled or waiting for token");
            }
        }
    }
    
    public String getAgentStatus() {
        if (lastError != null && !lastError.isEmpty()) {
            return agentStatus + ": " + lastError;
        }
        return agentStatus;
    }
    
    public String getConfigValue(String key, String defaultValue) {
        try {
            java.io.File configFile = new java.io.File(context.getFilesDir(), "mod_configs/mimiclaw-ai-assistant.json");
            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), "UTF-8");
                JSONObject config = new JSONObject(content);
                String value = config.optString(key, "");
                if (!value.isEmpty()) return value;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read config file: " + e.getMessage());
        }
        return defaultValue;
    }
    
    public void setConfigValue(String key, String value) {
        try {
            java.io.File configDir = new java.io.File(context.getFilesDir(), "mod_configs");
            configDir.mkdirs();
            java.io.File configFile = new java.io.File(configDir, "mimiclaw-ai-assistant.json");
            
            JSONObject config = new JSONObject();
            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), "UTF-8");
                config = new JSONObject(content);
            }
            config.put(key, value);
            
            java.io.FileWriter writer = new java.io.FileWriter(configFile);
            writer.write(config.toString());
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write config file: " + e.getMessage());
        }

        switch (key) {
            case "api_key":
                getPrefs().edit().putString("cfg_api_key", value).apply();
                llmProxy.setApiKey(value);
                break;
            case "provider":
                getPrefs().edit().putString("cfg_provider", value).apply();
                llmProxy.setProvider(value);
                break;
            case "model":
                getPrefs().edit().putString("cfg_model", value).apply();
                llmProxy.setModel(value);
                break;
            case "custom_api_url":
                getPrefs().edit().putString("cfg_custom_api_url", value).apply();
                llmProxy.setCustomApiUrl(value);
                break;
            case "max_tokens":
                try {
                    llmProxy.setMaxTokens(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid max_tokens: " + value);
                }
                break;
            case "max_tool_iterations":
                try {
                    agentLoop.setMaxToolIterations(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid max_tool_iterations: " + value);
                }
                break;
        }
    }

    public JSONObject getSkillConfig() {
        try {
            java.io.File configFile = new java.io.File(context.getFilesDir(), "mod_configs/skill_config.json");
            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), "UTF-8");
                return new JSONObject(content);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read skill config: " + e.getMessage());
        }
        return new JSONObject();
    }
    
    public void setSkillEnabled(String skillId, boolean enabled) {
        try {
            java.io.File configDir = new java.io.File(context.getFilesDir(), "mod_configs");
            configDir.mkdirs();
            java.io.File configFile = new java.io.File(configDir, "skill_config.json");
            
            JSONObject config = new JSONObject();
            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), "UTF-8");
                config = new JSONObject(content);
            }
            if (config.has(skillId) && config.optBoolean(skillId, enabled) == enabled) {
                return;
            }
            config.put(skillId, enabled);
            
            java.io.FileWriter writer = new java.io.FileWriter(configFile);
            writer.write(config.toString());
            writer.close();
            
            // Hot reload: notify AI about skill change via system message
            notifySkillConfigChanged(skillId, enabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write skill config: " + e.getMessage());
        }
    }
    
    public boolean isSkillEnabled(String skillId) {
        JSONObject config = getSkillConfig();
        // Default to true for all skills
        return config.optBoolean(skillId, true);
    }
    
    private void notifySkillConfigChanged(String skillId, boolean enabled) {
        Log.i(TAG, "Skill config changed: " + skillId + " -> " + (enabled ? "enabled" : "disabled"));
        // Send system notification to current chat
        String action = enabled ? "enabled" : "disabled";
        String message = "[SYSTEM] Skill '" + skillId + "' has been " + action + ". " +
            (enabled ? "Tools for this skill are now available." : "Tools for this skill are no longer available.");
        
        // Inject system message into webconsole session history so AI sees it
        String webConsoleSessionKey = "webconsole:" + resolveWebConsoleChatId(null);
        sessionManager.appendMessage(webConsoleSessionKey, "assistant", message);
        
        // Broadcast to webconsole via SSE
        if (webConsoleServer != null) {
            webConsoleServer.broadcastSkillChange(skillId, enabled, message);
        }
    }
    
    public JSONArray getUserInstalledSkills() {
        JSONArray skills = new JSONArray();
        try {
            java.io.File skillsDir = new java.io.File(context.getFilesDir(), "skills");
            if (skillsDir.exists() && skillsDir.isDirectory()) {
                java.io.File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".md"));
                if (files != null) {
                    // Builtin skills to exclude
                    java.util.Set<String> builtins = new java.util.HashSet<>(java.util.Arrays.asList(
                        "android_system_bridge.md", "android_accessibility_bridge.md", "android_browser_bridge.md",
                        "homeassistant.md", "multi_search_engine.md", "heartbeat_cron.md", 
                        "android_skill_installer.md", "qqbot_media.md"
                    ));
                    
                    for (java.io.File file : files) {
                        if (builtins.contains(file.getName())) continue;
                        
                        JSONObject skill = new JSONObject();
                        String id = file.getName().replace(".md", "");
                        skill.put("id", id);
                        skill.put("name", formatSkillName(id));
                        skill.put("desc", extractSkillDescription(file));
                        skill.put("user_installed", true);
                        skills.put(skill);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to list user skills: " + e.getMessage());
        }
        return skills;
    }
    
    private String formatSkillName(String id) {
        return id.replace("_", " ").replace("-", " ");
    }
    
    private String extractSkillDescription(java.io.File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            StringBuilder desc = new StringBuilder();
            boolean foundTitle = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# ")) {
                    foundTitle = true;
                    continue;
                }
                if (foundTitle && !line.trim().isEmpty() && !line.startsWith("#")) {
                    desc.append(line.trim());
                    if (desc.length() > 80) break;
                }
                if (line.startsWith("##")) break;
            }
            String result = desc.toString().trim();
            return result.length() > 80 ? result.substring(0, 77) + "..." : result;
        } catch (Exception e) {
            return "User installed skill";
        }
    }

    public void setLastError(String error) {
        this.lastError = error != null ? error : "";
        this.agentStatus = this.lastError.isEmpty() ? "idle" : "error";
        if (!this.lastError.isEmpty()) {
            this.lastResponse = "Error: " + this.lastError;
        }
        Log.w(TAG, "External error reported: " + this.lastError);
    }
    
    public String getLastResponse() {
        String heartbeatStatus = null;
        if (qqChannel != null && qqChannel.isEnabled()) {
            heartbeatStatus = qqChannel.getHeartbeatStatus();
        } else if (telegramChannel != null) {
            heartbeatStatus = telegramChannel.isEnabled() ? "Telegram enabled" : "Telegram offline";
        }
        if (heartbeatStatus != null && !heartbeatStatus.isEmpty()) {
            lastResponse = heartbeatStatus;
        }
        return lastResponse;
    }
    
    public String getTotalTokens() {
        return String.valueOf(llmProxy.getTotalTokens());
    }

    public boolean isWebConsolePasswordValid(String password) {
        return password != null && password.equals(webConsolePassword);
    }

    public boolean updateWebConsolePassword(String currentPassword, String newPassword) {
        if (!isWebConsolePasswordValid(currentPassword)) {
            return false;
        }
        String normalized = newPassword == null ? "" : newPassword.trim();
        if (normalized.length() < 4) {
            return false;
        }
        webConsolePassword = normalized;
        saveWebConsolePassword(normalized);
        return true;
    }

    private String resolveWebConsoleChatId(String chatId) {
        return (chatId == null || chatId.trim().isEmpty())
            ? "__web_console__"
            : "__web_console__:" + chatId.trim();
    }

    private String normalizeWebConsoleResolvedChatId(String chatId) {
        String trimmed = chatId == null ? "" : chatId.trim();
        if (trimmed.isEmpty()) {
            return "__web_console__";
        }
        if ("__web_console__".equals(trimmed) || trimmed.startsWith("__web_console__:")) {
            return trimmed;
        }
        return resolveWebConsoleChatId(trimmed);
    }

    public void reportAiBrowserUiAction(String chatId, String payloadJson) {
        try {
            String resolvedChatId = normalizeWebConsoleResolvedChatId(chatId);
            String sessionKey = "webconsole:" + resolvedChatId;
            JSONObject payload = new JSONObject(payloadJson == null ? "{}" : payloadJson);
            payload.put("resolved_chat_id", resolvedChatId);
            payload.put("channel", "webconsole");
            payload.put("source", "ai_browser_ui");
            payload.put("received_at", System.currentTimeMillis());
            memoryStore.writeFileByPath(AI_BROWSER_STATE_PATH, payload.toString(2));

            String action = payload.optString("action", "unknown");
            String url = payload.optString("url", "");
            memoryStore.appendToday("- [AI Browser UI] action=" + action
                + (url.isEmpty() ? "" : " url=" + url)
                + " chat=" + resolvedChatId);

            // Persist hidden event into the live session context so the next user turn
            // always carries the exact UI action in conversation history, even though
            // the frontend will filter it from visible chat bubbles.
            sessionManager.appendMessage(
                sessionKey,
                "user",
                AI_BROWSER_EVENT_PREFIX + " " + payload.toString()
            );

            MessageBus.Message eventMsg = new MessageBus.Message(
                "webconsole",
                resolvedChatId,
                AI_BROWSER_EVENT_PREFIX + " " + payload.toString()
            );
            messageBus.pushInbound(eventMsg);
            Log.d(TAG, "AI browser UI action queued: " + action + " -> " + resolvedChatId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to report AI browser UI action", e);
        }
    }

    private void registerAiBrowserUiReceiver() {
        if (aiBrowserUiReceiver != null) {
            return;
        }
        aiBrowserUiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION_AI_BROWSER_UI_EVENT.equals(intent.getAction())) {
                    return;
                }
                reportAiBrowserUiAction(
                    intent.getStringExtra("sid"),
                    intent.getStringExtra("payload")
                );
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_AI_BROWSER_UI_EVENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(aiBrowserUiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(aiBrowserUiReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register AI browser UI receiver", e);
        }
    }

    private void unregisterAiBrowserUiReceiver() {
        if (aiBrowserUiReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(aiBrowserUiReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister AI browser UI receiver", e);
        }
        aiBrowserUiReceiver = null;
    }

    public void clearWebConsoleSession(String chatId) {
        String resolvedChatId = resolveWebConsoleChatId(chatId);
        String sessionKey = "webconsole:" + resolvedChatId;
        sessionManager.clearSession(sessionKey);
        androidChannel.clearResponsesForChat(resolvedChatId);
    }

    public JSONObject getWebConsoleStatus(String chatId) {
        JSONObject json = new JSONObject();
        try {
            String resolvedChatId = resolveWebConsoleChatId(chatId);
            json.put("chatId", chatId == null ? "" : chatId);
            json.put("resolvedChatId", resolvedChatId);
            json.put("sessionKey", "webconsole:" + resolvedChatId);
            json.put("agentStatus", getAgentStatus());
            json.put("provider", llmProxy.getProvider());
            json.put("model", llmProxy.getModel());
            json.put("apiUrl", llmProxy.getApiUrlForDebug());
            json.put("customApi", llmProxy.isUsingCustomApi());
            json.put("totalTokens", llmProxy.getTotalTokens());
            json.put("heartbeat", getLastResponse());
            json.put("webConsoleEnabled", webConsoleEnabled);
            json.put("webConsoleRunning", webConsoleServer != null && webConsoleServer.isRunning());
            json.put("webConsolePort", webConsoleServer != null ? webConsoleServer.getPort() : 18789);
            json.put("webConsoleError", webConsoleServer != null ? webConsoleServer.getLastError() : "");
            json.put("qqEnabled", qqChannel != null && qqChannel.isEnabled());
            json.put("telegramEnabled", telegramChannel != null && telegramChannel.isEnabled());
            json.put("historyCount", getWebConsoleHistory(chatId, 200).length());
        } catch (Exception e) {
            Log.w(TAG, "Failed to build web console status", e);
        }
        return json;
    }

    private boolean handleWebConsoleCommand(String chatId, String content, StringBuilder out) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            if ("/new".equalsIgnoreCase(trimmed)) {
                clearWebConsoleSession(chatId);
                agentStatus = "idle";
                lastError = "";
                lastResponse = "";
                out.append("Conversation cleared.");
                return true;
            }
            if ("/status".equalsIgnoreCase(trimmed)
                || "openclaw status".equalsIgnoreCase(trimmed)
                || "openclaw status --all".equalsIgnoreCase(trimmed)
                || "openclaw status --deep".equalsIgnoreCase(trimmed)
                || "openclaw health".equalsIgnoreCase(trimmed)) {
                out.append(getWebConsoleStatus(chatId).toString(2));
                return true;
            }
            if ("/model".equalsIgnoreCase(trimmed) || "openclaw models status".equalsIgnoreCase(trimmed)) {
                JSONObject modelJson = new JSONObject();
                modelJson.put("provider", llmProxy.getProvider());
                modelJson.put("model", llmProxy.getModel());
                modelJson.put("apiUrl", llmProxy.getApiUrlForDebug());
                modelJson.put("customApi", llmProxy.isUsingCustomApi());
                out.append(modelJson.toString(2));
                return true;
            }
            if (trimmed.startsWith("/model ")) {
                String nextModel = trimmed.substring(7).trim();
                if (nextModel.isEmpty()) {
                    out.append("Model name required.");
                } else {
                    llmProxy.setModel(nextModel);
                    out.append("Model updated to ").append(nextModel);
                }
                return true;
            }
            if ("openclaw gateway status".equalsIgnoreCase(trimmed)) {
                JSONObject gatewayJson = new JSONObject();
                gatewayJson.put("running", webConsoleServer != null && webConsoleServer.isRunning());
                gatewayJson.put("port", webConsoleServer != null ? webConsoleServer.getPort() : 18789);
                gatewayJson.put("error", webConsoleServer != null ? webConsoleServer.getLastError() : "");
                gatewayJson.put("bind", "lan");
                out.append(gatewayJson.toString(2));
                return true;
            }
            if ("openclaw gateway restart".equalsIgnoreCase(trimmed)) {
                refreshWebConsoleServer();
                out.append("Gateway restart requested.");
                return true;
            }
            if ("openclaw gateway stop".equalsIgnoreCase(trimmed)) {
                if (webConsoleServer != null) {
                    webConsoleServer.stop();
                }
                out.append("Gateway stopped.");
                return true;
            }
            if ("openclaw gateway start".equalsIgnoreCase(trimmed)) {
                if (webConsoleServer != null) {
                    webConsoleServer.start();
                }
                out.append("Gateway start requested.");
                return true;
            }
        } catch (Exception e) {
            out.setLength(0);
            out.append("Command failed: ").append(e.getMessage());
            Log.w(TAG, "Web console command failed: " + trimmed, e);
            return true;
        }
        return false;
    }

    public String handleWebConsoleMessage(String chatId, String content) {
        final String resolvedChatId = resolveWebConsoleChatId(chatId);
        StringBuilder commandResponse = new StringBuilder();
        if (handleWebConsoleCommand(chatId, content, commandResponse)) {
            return commandResponse.toString();
        }
        lastResponse = "";
        lastError = "";
        androidChannel.clearResponsesForChat(resolvedChatId);
        androidChannel.injectMessage(resolvedChatId, content, "webconsole");
        agentStatus = "processing";
        try {
            String response = androidChannel.waitForResponse(resolvedChatId, 45000);
            if (response == null || response.trim().isEmpty()) {
                return "Timed out waiting for AI response.";
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for AI response.";
        }
    }

    public boolean stopWebConsoleProcessing(String chatId) {
        final String resolvedChatId = resolveWebConsoleChatId(chatId);
        agentLoop.requestCancel("webconsole", resolvedChatId);
        MessageBus bus = MessageBus.getInstance();
        bus.clearInbound("webconsole", resolvedChatId);
        bus.clearOutbound("webconsole", resolvedChatId);
        androidChannel.clearResponsesForChat(resolvedChatId);
        androidChannel.sendMessage(resolvedChatId, "Generation stopped.");
        lastResponse = "Generation stopped.";
        lastError = "";
        agentStatus = "idle";
        return true;
    }

    public JSONArray getWebConsoleHistory(String chatId, int maxMessages) {
        String resolvedChatId = resolveWebConsoleChatId(chatId);
        String sessionKey = "webconsole:" + resolvedChatId;
        JSONArray history = mergedWebConsoleHistory(resolvedChatId, Math.max(1, maxMessages));
        JSONArray result = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            try {
                JSONObject item = history.getJSONObject(i);
                if (isInternalWebConsoleMessage(item.optString("content", ""))) {
                    continue;
                }
                String visibleContent = normalizeWebConsoleContent(item.optString("content", ""));
                if (visibleContent == null || visibleContent.trim().isEmpty()) {
                    continue;
                }
                JSONObject normalized = new JSONObject();
                normalized.put("role", item.optString("role", "assistant"));
                normalized.put("content", visibleContent);
                normalized.put("timestamp", item.optLong("timestamp", 0L));
                result.put(normalized);
            } catch (Exception e) {
                Log.w(TAG, "Failed to normalize web console history item", e);
            }
        }
        return result;
    }

    private boolean isInternalWebConsoleMessage(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("[SYSTEM] Skill '")
            || trimmed.startsWith(AI_BROWSER_EVENT_PREFIX);
    }

    private JSONArray mergedWebConsoleHistory(String resolvedChatId, int maxMessages) {
        JSONArray merged = new JSONArray();
        try {
            String globalChatId = resolveWebConsoleChatId(null);
            String globalSessionKey = "webconsole:" + globalChatId;
            String currentSessionKey = "webconsole:" + resolvedChatId;
            boolean sameSession = globalSessionKey.equals(currentSessionKey);
            JSONArray globalHistory = sessionManager.getHistory(globalSessionKey, maxMessages);
            JSONArray currentHistory = sameSession
                ? new JSONArray()
                : sessionManager.getHistory(currentSessionKey, maxMessages);
            java.util.List<JSONObject> combined = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            collectHistoryItems(globalHistory, combined, seen);
            collectHistoryItems(currentHistory, combined, seen);
            combined.sort((a, b) -> Long.compare(a.optLong("timestamp", 0L), b.optLong("timestamp", 0L)));
            int start = Math.max(0, combined.size() - maxMessages);
            for (int i = start; i < combined.size(); i++) {
                merged.put(new JSONObject(combined.get(i).toString()));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to merge web console history", e);
            return sessionManager.getHistory("webconsole:" + resolvedChatId, maxMessages);
        }
        return merged;
    }

    private void collectHistoryItems(JSONArray source, java.util.List<JSONObject> out, java.util.Set<String> seen) {
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            try {
                JSONObject item = source.getJSONObject(i);
                String dedupeKey = item.optLong("timestamp", 0L) + "|" + item.optString("role", "") + "|" + item.optString("content", "");
                if (!seen.add(dedupeKey)) {
                    continue;
                }
                out.add(new JSONObject(item.toString()));
            } catch (Exception e) {
                Log.w(TAG, "Failed to collect web console history item", e);
            }
        }
    }
    
    private String normalizeWebConsoleContent(String content) {
        if (content == null || content.isEmpty()) return content;
        try {
            JSONArray arr = new JSONArray(content);
            JSONArray result = new JSONArray();
            boolean hasVisibleContent = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject block = arr.getJSONObject(i);
                String type = block.optString("type", "");
                if ("text".equals(type)) {
                    String text = block.optString("text", "").trim();
                    if (!text.isEmpty() && !"null".equals(text)) {
                        hasVisibleContent = true;
                        result.put(block);
                    }
                } else if ("tool_use".equals(type) || "tool_result".equals(type)) {
                    // Truncate large tool content (keep 1500-3000 chars)
                    if ("tool_result".equals(type)) {
                        String c = block.optString("content", "");
                        if (c.length() > 3000) {
                            block.put("content", c.substring(0, 3000) + "... (truncated)");
                        }
                    }
                    result.put(block);
                }
            }
            if (result.length() == 0) return null;
            return result.toString();
        } catch (Exception e) {
            return content;
        }
    }
    
    public void sendMessage() {
        sendMessage("Hello, how can I help you?");
    }
    
    public void sendMessage(String content) {
        lastResponse = "";
        lastError = "";
        androidChannel.injectMessage("default", content);
        agentStatus = "processing";
        Log.d(TAG, "Message queued: " + content);
    }
    
    public void clearMemory() {
        sessionManager.clearSession("default");
        lastResponse = "";
        lastError = "";
        agentStatus = "idle";
        Log.d(TAG, "Memory cleared");
    }
    
    private void startAgentLoop() {
        agentThread = new Thread(agentLoop, "AgentLoop");
        agentThread.start();
        Log.d(TAG, "Agent loop started");
    }
    
    public void onDestroy() {
        unregisterAiBrowserUiReceiver();
        if (telegramChannel != null) {
            telegramChannel.stop();
        }
        if (qqChannel != null) {
            qqChannel.stop();
        }
        if (webConsoleServer != null) {
            webConsoleServer.stop();
        }
        if (channelManager != null) {
            channelManager.stop();
        }
        if (cronService != null) {
            cronService.stop();
        }
        if (agentLoop != null) {
            agentLoop.stop();
        }
        if (agentThread != null) {
            agentThread.interrupt();
        }
        instance = null;
        Log.d(TAG, "OpenClaw(Mini) destroyed");
    }
}
