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
import com.ava.mods.mimiclaw.task.TaskTree;
import org.json.JSONArray;
import org.json.JSONObject;

public class MimiClawManager {
    private static final String TAG = "MimiClawManager";
    private static final String MAIN_CONFIG_FILE = "mimiclaw-ai-assistant.json";
    private static final String PROFILES_CONFIG_FILE = "mimiclaw-ai-assistant-profiles.json";
    private static final String LIVE_CONFIG_FILE = "mimiclaw-ai-assistant-live-config.json";
    private static final String ACTIVE_PROFILE_ID_KEY = "active_profile_id";
    private static final String PROVIDER_PROFILES_KEY = "provider_profiles";
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
    private ContextBuilder contextBuilder;
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
        this.toolRegistry.cleanupCameraSnapshots();
        
        this.contextBuilder = new ContextBuilder(memoryStore, new com.ava.mods.mimiclaw.skills.SkillLoader(context));
        this.contextBuilder.setSkillEnabledChecker(skillId -> isSkillEnabled(skillId));
        
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
        
        // Only enable heartbeat if config allows (default: true)
        boolean heartbeatEnabled = !"false".equals(getConfigValue("heartbeat_enabled", "true"));
        if (heartbeatEnabled) {
            cronService.ensureBuiltinHeartbeatJob(
                AndroidChannel.NAME,
                ContextBuilder.HEARTBEAT_CHAT_ID,
                "HEARTBEAT_TICK: Read HEARTBEAT.md and return HEARTBEAT_OK if nothing needs proactive notification.",
                BUILTIN_HEARTBEAT_INTERVAL_S
            );
        }
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

    public Context getContext() {
        return context;
    }
    
    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }

        value = resolveManagedConfigValue(key, value);
        persistManagedConfigMirror(key, value);
        
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
    
    private String toPublicAgentStatus(String rawStatus) {
        String status = rawStatus == null ? "idle" : rawStatus;
        if ("llm_request".equals(status)) {
            return "thinking";
        }
        if (status.startsWith("llm_request:")) {
            return "thinking" + status.substring("llm_request".length());
        }
        return status;
    }

    public String getAgentStatus() {
        String status = toPublicAgentStatus(agentStatus);
        if (lastError != null && !lastError.isEmpty()) {
            return status + ": " + lastError;
        }
        return status;
    }
    
    public String getConfigValue(String key, String defaultValue) {
        try {
            if (isMirroredConfigKey(key)) {
                JSONObject liveConfig = readLiveConfig();
                if (liveConfig.has(key)) {
                    return liveConfig.optString(key, defaultValue);
                }
            }
            JSONObject config = sanitizeLegacyMainConfig(readMainConfig());
            String value = config.optString(key, "");
            if (!value.isEmpty()) return value;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read config file: " + e.getMessage());
        }
        return defaultValue;
    }
    
    public void setConfigValue(String key, String value) {
        try {
            JSONObject config = sanitizeLegacyMainConfig(readMainConfig());
            config.put(key, value);
            syncActiveProfileFields(config, key, value);
            writeMainConfig(config);
            persistManagedConfigMirror(key, value);
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

    public String getModVersion() {
        try {
            java.io.File manifestFile = new java.io.File(context.getFilesDir(), "mods/mimiclaw-ai-assistant/manifest.json");
            if (manifestFile.exists()) {
                java.io.FileInputStream fis = new java.io.FileInputStream(manifestFile);
                byte[] data = new byte[(int) manifestFile.length()];
                fis.read(data);
                fis.close();
                JSONObject manifest = new JSONObject(new String(data, "UTF-8"));
                return manifest.optString("version", "unknown");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read mod version: " + e.getMessage());
        }
        return "unknown";
    }

    public boolean hasRootAccess() {
        return toolRegistry != null && toolRegistry.hasRootAccess();
    }

    public String processPeerMessage(String message) {
        // Process message from peer device - use full system prompt and tools
        try {
            // Build full system prompt with context
            String systemPrompt = contextBuilder.buildSystemPrompt("peer", "peer_chat");
            
            // Create message array
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", "[From peer device] " + message);
            messages.put(userMsg);
            
            // Get tools
            JSONArray tools = toolRegistry.getToolsJson();
            
            // Call LLM with tools
            LlmProxy.Response resp = llmProxy.chatWithTools(systemPrompt, messages, tools);
            
            // If tool use, execute tools and get final response
            int iteration = 0;
            int maxIterations = 5; // Limit iterations for peer messages
            while (resp.toolUse && iteration < maxIterations) {
                Log.d(TAG, "Peer message tool iteration " + (iteration + 1));
                
                // Execute tools
                StringBuilder toolResults = new StringBuilder();
                for (LlmProxy.ToolCall call : resp.calls) {
                    String output = toolRegistry.executeTool(call.name, call.input);
                    toolResults.append("[").append(call.name).append("] ").append(output).append("\n");
                    
                    // Add tool result to messages
                    JSONObject asstMsg = new JSONObject();
                    asstMsg.put("role", "assistant");
                    JSONArray content = new JSONArray();
                    JSONObject toolUseBlock = new JSONObject();
                    toolUseBlock.put("type", "tool_use");
                    toolUseBlock.put("id", call.id);
                    toolUseBlock.put("name", call.name);
                    toolUseBlock.put("input", new JSONObject(call.input));
                    content.put(toolUseBlock);
                    asstMsg.put("content", content);
                    messages.put(asstMsg);
                    
                    JSONObject resultMsg = new JSONObject();
                    resultMsg.put("role", "user");
                    JSONArray resultContent = new JSONArray();
                    JSONObject resultBlock = new JSONObject();
                    resultBlock.put("type", "tool_result");
                    resultBlock.put("tool_use_id", call.id);
                    resultBlock.put("content", output);
                    resultContent.put(resultBlock);
                    resultMsg.put("content", resultContent);
                    messages.put(resultMsg);
                }
                
                // Call LLM again
                resp = llmProxy.chatWithTools(systemPrompt, messages, tools);
                iteration++;
            }
            
            return resp.text != null && !resp.text.isEmpty() ? resp.text : "(no response)";
        } catch (Exception e) {
            Log.e(TAG, "Error processing peer message", e);
            return "Error: " + e.getMessage();
        }
    }

    public JSONObject getProviderProfilesPayload() {
        try {
            JSONObject config = ensureProviderProfiles(readProfilesConfig(), readMainConfig());
            JSONObject result = new JSONObject();
            result.put("active_profile_id", config.optString(ACTIVE_PROFILE_ID_KEY, "default"));
            result.put("profiles", config.optJSONArray(PROVIDER_PROFILES_KEY));
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load provider profiles: " + e.getMessage());
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("active_profile_id", "default");
                fallback.put("profiles", new JSONArray());
            } catch (Exception ignored) {
            }
            return fallback;
        }
    }

    public void setActiveProviderProfile(String profileId) {
        if (profileId == null || profileId.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject config = ensureProviderProfiles(readProfilesConfig(), readMainConfig());
            JSONArray profiles = config.optJSONArray(PROVIDER_PROFILES_KEY);
            if (profiles == null) {
                return;
            }
            String normalizedId = profileId.trim();
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile == null) {
                    continue;
                }
                if (!normalizedId.equals(profile.optString("id"))) {
                    continue;
                }
                config.put(ACTIVE_PROFILE_ID_KEY, normalizedId);
                writeProfilesConfig(config);
                JSONObject mainConfig = readMainConfig();
                applyProfileToTopLevelConfig(mainConfig, profile);
                writeMainConfig(mainConfig);
                applyConfig("provider", profile.optString("provider", "openai"));
                applyConfig("model", profile.optString("model", ""));
                applyConfig("custom_api_url", profile.optString("custom_api_url", ""));
                applyConfig("api_key", profile.optString("api_key", ""));
                applyConfig("max_tokens", profile.optString("max_tokens", "4096"));
                applyConfig("max_tool_iterations", profile.optString("max_tool_iterations", "30"));
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set active provider profile: " + e.getMessage());
        }
    }

    public JSONObject createEmptyProviderProfile(String profileId, String profileName, boolean makeActive) {
        try {
            JSONObject config = ensureProviderProfiles(readProfilesConfig(), readMainConfig());
            JSONArray profiles = config.optJSONArray(PROVIDER_PROFILES_KEY);
            if (profiles == null) {
                profiles = new JSONArray();
                config.put(PROVIDER_PROFILES_KEY, profiles);
            }
            String normalizedName = nextProfileName(profiles);
            String normalizedId = nextProfileId(profiles, normalizedName);
            JSONObject profile = buildEmptyProfile(normalizedId, normalizedName);
            upsertProfile(profiles, profile);
            if (makeActive) {
                config.put(ACTIVE_PROFILE_ID_KEY, normalizedId);
                JSONObject mainConfig = readMainConfig();
                applyProfileToTopLevelConfig(mainConfig, profile);
                writeMainConfig(mainConfig);
            }
            writeProfilesConfig(config);
            if (makeActive) {
                applyConfig("provider", profile.optString("provider", "openai"));
                applyConfig("model", profile.optString("model", ""));
                applyConfig("custom_api_url", profile.optString("custom_api_url", ""));
                applyConfig("api_key", profile.optString("api_key", ""));
                applyConfig("max_tokens", profile.optString("max_tokens", "4096"));
                applyConfig("max_tool_iterations", profile.optString("max_tool_iterations", "30"));
            }
            JSONObject result = new JSONObject();
            result.put("active_profile_id", config.optString(ACTIVE_PROFILE_ID_KEY, normalizedId));
            result.put("profile", profile);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create empty provider profile: " + e.getMessage());
            return new JSONObject();
        }
    }

    public JSONObject renameProviderProfile(String profileId, String profileName) {
        try {
            String normalizedId = profileId == null ? "" : profileId.trim();
            String normalizedName = profileName == null ? "" : profileName.trim();
            if (normalizedId.isEmpty() || normalizedName.isEmpty()) {
                JSONObject result = new JSONObject();
                result.put("updated", false);
                return result;
            }
            JSONObject config = ensureProviderProfiles(readProfilesConfig(), readMainConfig());
            JSONArray profiles = config.optJSONArray(PROVIDER_PROFILES_KEY);
            if (profiles == null) {
                JSONObject result = new JSONObject();
                result.put("updated", false);
                return result;
            }
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile == null || !normalizedId.equals(profile.optString("id"))) {
                    continue;
                }
                profile.put("name", normalizedName);
                writeProfilesConfig(config);
                JSONObject result = new JSONObject();
                result.put("updated", true);
                result.put("profile", profile);
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to rename provider profile: " + e.getMessage());
        }
        return new JSONObject();
    }

    public JSONObject deleteProviderProfile(String profileId) {
        try {
            JSONObject config = ensureProviderProfiles(readProfilesConfig(), readMainConfig());
            JSONArray profiles = config.optJSONArray(PROVIDER_PROFILES_KEY);
            if (profiles == null || profiles.length() <= 1) {
                JSONObject result = new JSONObject();
                result.put("deleted", false);
                result.put("reason", "last_profile");
                result.put("active_profile_id", config.optString(ACTIVE_PROFILE_ID_KEY, "default"));
                return result;
            }
            String normalizedId = profileId == null ? "" : profileId.trim();
            JSONArray nextProfiles = new JSONArray();
            JSONObject nextActiveProfile = null;
            boolean deleted = false;
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile == null) {
                    continue;
                }
                if (normalizedId.equals(profile.optString("id"))) {
                    deleted = true;
                    continue;
                }
                if (nextActiveProfile == null) {
                    nextActiveProfile = profile;
                }
                nextProfiles.put(profile);
            }
            if (!deleted) {
                JSONObject result = new JSONObject();
                result.put("deleted", false);
                result.put("reason", "not_found");
                result.put("active_profile_id", config.optString(ACTIVE_PROFILE_ID_KEY, "default"));
                return result;
            }
            config.put(PROVIDER_PROFILES_KEY, nextProfiles);
            String activeId = config.optString(ACTIVE_PROFILE_ID_KEY, "default");
            if (normalizedId.equals(activeId)) {
                if (nextActiveProfile == null) {
                    nextActiveProfile = nextProfiles.optJSONObject(0);
                }
                if (nextActiveProfile != null) {
                    String nextId = nextActiveProfile.optString("id", "default");
                    config.put(ACTIVE_PROFILE_ID_KEY, nextId);
                    JSONObject mainConfig = readMainConfig();
                    applyProfileToTopLevelConfig(mainConfig, nextActiveProfile);
                    writeMainConfig(mainConfig);
                    applyConfig("provider", nextActiveProfile.optString("provider", "openai"));
                    applyConfig("model", nextActiveProfile.optString("model", ""));
                    applyConfig("custom_api_url", nextActiveProfile.optString("custom_api_url", ""));
                    applyConfig("api_key", nextActiveProfile.optString("api_key", ""));
                    applyConfig("max_tokens", nextActiveProfile.optString("max_tokens", "4096"));
                    applyConfig("max_tool_iterations", nextActiveProfile.optString("max_tool_iterations", "30"));
                }
            }
            writeProfilesConfig(config);
            JSONObject result = new JSONObject();
            result.put("deleted", true);
            result.put("active_profile_id", config.optString(ACTIVE_PROFILE_ID_KEY, "default"));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete provider profile: " + e.getMessage());
            return new JSONObject();
        }
    }

    private JSONObject readMainConfig() throws Exception {
        return readJsonConfigFile(MAIN_CONFIG_FILE);
    }

    private JSONObject readProfilesConfig() throws Exception {
        return readJsonConfigFile(PROFILES_CONFIG_FILE);
    }

    private JSONObject readLiveConfig() throws Exception {
        return readJsonConfigFile(LIVE_CONFIG_FILE);
    }

    private JSONObject readJsonConfigFile(String fileName) throws Exception {
        java.io.File configDir = new java.io.File(context.getFilesDir(), "mod_configs");
        configDir.mkdirs();
        java.io.File configFile = new java.io.File(configDir, fileName);
        if (!configFile.exists()) {
            return new JSONObject();
        }
        String content = readFileAsString(configFile);
        return content.trim().isEmpty() ? new JSONObject() : new JSONObject(content);
    }

    private String readFileAsString(java.io.File file) throws Exception {
        // Android 8.0+ (API 26) 支持 java.nio.file.Files
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
        }
        // Android 7.x 及以下使用 BufferedReader
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            if (reader != null) reader.close();
        }
        return sb.toString();
    }

    private void writeMainConfig(JSONObject config) throws Exception {
        writeJsonConfigFile(MAIN_CONFIG_FILE, sanitizeLegacyMainConfig(config));
    }

    private void writeProfilesConfig(JSONObject config) throws Exception {
        writeJsonConfigFile(PROFILES_CONFIG_FILE, config);
    }

    private void writeLiveConfig(JSONObject config) throws Exception {
        writeJsonConfigFile(LIVE_CONFIG_FILE, config);
    }

    private void writeJsonConfigFile(String fileName, JSONObject config) throws Exception {
        java.io.File configDir = new java.io.File(context.getFilesDir(), "mod_configs");
        configDir.mkdirs();
        java.io.File configFile = new java.io.File(configDir, fileName);
        java.io.FileWriter writer = new java.io.FileWriter(configFile);
        writer.write(config.toString());
        writer.close();
    }

    private JSONObject sanitizeLegacyMainConfig(JSONObject config) throws Exception {
        if (config == null) {
            return new JSONObject();
        }
        boolean changed = false;
        if (config.has(PROVIDER_PROFILES_KEY)) {
            config.remove(PROVIDER_PROFILES_KEY);
            changed = true;
        }
        if (config.has(ACTIVE_PROFILE_ID_KEY)) {
            config.remove(ACTIVE_PROFILE_ID_KEY);
            changed = true;
        }
        if (changed) {
            writeJsonConfigFile(MAIN_CONFIG_FILE, config);
        }
        return config;
    }

    private JSONObject ensureProviderProfiles(JSONObject profilesConfig, JSONObject mainConfig) throws Exception {
        JSONObject config = profilesConfig != null ? profilesConfig : new JSONObject();
        JSONArray profiles = config.optJSONArray(PROVIDER_PROFILES_KEY);
        if (profiles == null || profiles.length() == 0) {
            profiles = new JSONArray();
            JSONObject sourceConfig = sanitizeLegacyMainConfig(mainConfig != null ? mainConfig : readMainConfig());
            profiles.put(buildProfileFromCurrentConfig(sourceConfig, "default", "Default"));
            config.put(PROVIDER_PROFILES_KEY, profiles);
        }
        String activeId = config.optString(ACTIVE_PROFILE_ID_KEY, "");
        if (activeId.isEmpty()) {
            JSONObject first = profiles.optJSONObject(0);
            config.put(ACTIVE_PROFILE_ID_KEY, first != null ? first.optString("id", "default") : "default");
        }
        writeProfilesConfig(config);
        return config;
    }

    private JSONObject buildProfileFromCurrentConfig(JSONObject config, String id, String name) throws Exception {
        JSONObject profile = new JSONObject();
        profile.put("id", id);
        profile.put("name", name);
        profile.put("provider", config.optString("provider", getPrefs().getString("cfg_provider", "openai")));
        profile.put("model", config.optString("model", getPrefs().getString("cfg_model", "")));
        profile.put("custom_api_url", config.optString("custom_api_url", getPrefs().getString("cfg_custom_api_url", "")));
        profile.put("api_key", config.optString("api_key", getPrefs().getString("cfg_api_key", "")));
        profile.put("max_tokens", config.optString("max_tokens", "4096"));
        profile.put("max_tool_iterations", config.optString("max_tool_iterations", "30"));
        return profile;
    }

    private JSONObject buildEmptyProfile(String id, String name) throws Exception {
        JSONObject profile = new JSONObject();
        profile.put("id", id);
        profile.put("name", name);
        profile.put("provider", "openai");
        profile.put("model", "");
        profile.put("custom_api_url", "");
        profile.put("api_key", "");
        profile.put("max_tokens", "4096");
        profile.put("max_tool_iterations", "30");
        return profile;
    }

    private void syncActiveProfileFields(JSONObject config, String key, String value) throws Exception {
        if (!isProfileField(key)) {
            return;
        }
        JSONObject profilesConfig = ensureProviderProfiles(readProfilesConfig(), config);
        JSONArray profiles = profilesConfig.optJSONArray(PROVIDER_PROFILES_KEY);
        if (profiles == null) {
            return;
        }
        String activeId = profilesConfig.optString(ACTIVE_PROFILE_ID_KEY, "default");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile == null || !activeId.equals(profile.optString("id"))) {
                continue;
            }
            profile.put(key, value);
            if (!profile.has("name") || profile.optString("name", "").trim().isEmpty()) {
                profile.put("name", activeId);
            }
            writeProfilesConfig(profilesConfig);
            return;
        }
    }

    private boolean isProfileField(String key) {
        return "provider".equals(key)
            || "model".equals(key)
            || "custom_api_url".equals(key)
            || "api_key".equals(key)
            || "max_tokens".equals(key)
            || "max_tool_iterations".equals(key);
    }

    private boolean isMirroredConfigKey(String key) {
        return isProfileField(key)
            || "web_console_password".equals(key);
    }

    private String manifestDefaultForKey(String key) {
        if ("provider".equals(key)) return "openai";
        if ("model".equals(key)) return "stepfun/step-3.5-flash";
        if ("custom_api_url".equals(key)) return "https://openrouter.ai/api/v1";
        if ("api_key".equals(key)) return "";
        if ("max_tokens".equals(key)) return "4096";
        if ("max_tool_iterations".equals(key)) return "30";
        if ("web_console_password".equals(key)) return "openclaw";
        return null;
    }

    private String resolveManagedConfigValue(String key, String incomingValue) {
        if (!isMirroredConfigKey(key)) {
            return incomingValue;
        }
        try {
            JSONObject mainConfig = sanitizeLegacyMainConfig(readMainConfig());
            if (mainConfig.has(key)) {
                return incomingValue;
            }
            JSONObject liveConfig = readLiveConfig();
            if (!liveConfig.has(key)) {
                return incomingValue;
            }
            String liveValue = liveConfig.optString(key, incomingValue);
            String manifestDefault = manifestDefaultForKey(key);
            if (manifestDefault == null) {
                return incomingValue;
            }
            if (manifestDefault.equals(incomingValue) && !liveValue.equals(incomingValue)) {
                Log.d(TAG, "Preserving mirrored config for " + key + " instead of manifest default");
                return liveValue;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve managed config for " + key + ": " + e.getMessage());
        }
        return incomingValue;
    }

    private void persistManagedConfigMirror(String key, String value) {
        if (!isMirroredConfigKey(key)) {
            return;
        }
        try {
            JSONObject liveConfig = readLiveConfig();
            liveConfig.put(key, value);
            writeLiveConfig(liveConfig);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist managed config mirror for " + key + ": " + e.getMessage());
        }
    }

    private void applyProfileToTopLevelConfig(JSONObject config, JSONObject profile) throws Exception {
        config.put("provider", profile.optString("provider", "openai"));
        config.put("model", profile.optString("model", ""));
        config.put("custom_api_url", profile.optString("custom_api_url", ""));
        config.put("api_key", profile.optString("api_key", ""));
        config.put("max_tokens", profile.optString("max_tokens", "4096"));
        config.put("max_tool_iterations", profile.optString("max_tool_iterations", "30"));
    }

    private void upsertProfile(JSONArray profiles, JSONObject profile) throws Exception {
        String id = profile.optString("id");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject existing = profiles.optJSONObject(i);
            if (existing != null && id.equals(existing.optString("id"))) {
                profiles.put(i, profile);
                return;
            }
        }
        profiles.put(profile);
    }

    private String sanitizeProfileId(String profileId, String profileName) {
        String base = profileId != null && !profileId.trim().isEmpty() ? profileId : profileName;
        if (base == null) {
            base = "profile";
        }
        String normalized = base.trim().toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
        return normalized.isEmpty() ? "profile" : normalized;
    }

    private String nextProfileName(JSONArray profiles) {
        int index = profiles != null ? profiles.length() + 1 : 1;
        while (true) {
            String candidate = "Profile " + index;
            if (!hasProfileName(profiles, candidate)) {
                return candidate;
            }
            index++;
        }
    }

    private String nextProfileId(JSONArray profiles, String profileName) {
        String baseId = sanitizeProfileId("", profileName);
        String candidate = baseId;
        int suffix = 2;
        while (hasProfileId(profiles, candidate)) {
            candidate = baseId + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean hasProfileName(JSONArray profiles, String name) {
        if (profiles == null || name == null) {
            return false;
        }
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile != null && name.equalsIgnoreCase(profile.optString("name", ""))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasProfileId(JSONArray profiles, String id) {
        if (profiles == null || id == null) {
            return false;
        }
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile != null && id.equals(profile.optString("id", ""))) {
                return true;
            }
        }
        return false;
    }

    public JSONObject getSkillConfig() {
        try {
            java.io.File configFile = new java.io.File(context.getFilesDir(), "mod_configs/skill_config.json");
            if (configFile.exists()) {
                String content = readFileAsString(configFile);
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
                String content = readFileAsString(configFile);
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
        return lastResponse;
    }

    public String getAiResponseForHass() {
        // This method is called by HASS entity, controlled by expose_ai_response config
        return lastResponse;
    }
    
    public String getTotalTokens() {
        return String.valueOf(llmProxy.getTotalTokens());
    }

    public String getHeartbeatStatus() {
        // Check heartbeat enabled config
        boolean heartbeatEnabled = !"false".equals(getConfigValue("heartbeat_enabled", "true"));
        if (!heartbeatEnabled) {
            return "Heartbeat disabled";
        }
        
        // Check cron service status
        if (cronService == null) {
            return "Cron not initialized";
        }
        
        // Get builtin heartbeat job status
        java.util.List<com.ava.mods.mimiclaw.cron.CronService.CronJob> jobs = cronService.listJobs();
        for (com.ava.mods.mimiclaw.cron.CronService.CronJob job : jobs) {
            if (com.ava.mods.mimiclaw.cron.CronService.BUILTIN_HEARTBEAT_NAME.equals(job.name)) {
                long intervalS = job.intervalS;
                long nextRun = job.nextRun;
                boolean enabled = job.enabled;
                
                if (!enabled) {
                    return "Paused";
                }
                
                long nowS = System.currentTimeMillis() / 1000;
                long inS = nextRun - nowS;
                if (inS <= 0) {
                    return "Running...";
                }
                String inStr = inS >= 60 ? (inS / 60) + "m" : inS + "s";
                return "Next: " + inStr;
            }
        }
        
        return "Heartbeat not scheduled";
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
            json.put("historyCount", getWebConsoleHistory(chatId, 50).length());
            json.put("taskTree", getTaskTreeStatus());
        } catch (Exception e) {
            Log.w(TAG, "Failed to build web console status", e);
        }
        return json;
    }
    
    public JSONObject getTaskTreeStatus() {
        JSONObject json = new JSONObject();
        try {
            TaskTree taskTree = toolRegistry.getTaskTree();
            if (taskTree == null) {
                json.put("error", "not_initialized");
                return json;
            }
            JSONArray todos = taskTree.getTasksJson();
            int pending = 0, inProgress = 0, completed = 0, failed = 0;
            if (todos != null) {
                for (int i = 0; i < todos.length(); i++) {
                    JSONObject task = todos.getJSONObject(i);
                    String status = task.optString("status", "pending");
                    switch (status) {
                        case "pending": pending++; break;
                        case "in_progress": inProgress++; break;
                        case "completed": completed++; break;
                        case "failed": failed++; break;
                    }
                }
            }
            json.put("total", todos != null ? todos.length() : 0);
            json.put("pending", pending);
            json.put("in_progress", inProgress);
            json.put("completed", completed);
            json.put("failed", failed);
            json.put("todos", todos != null ? todos : new JSONArray());
        } catch (Exception e) {
            Log.w(TAG, "Failed to get task tree status", e);
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

    public JSONArray getCronJobsForUi() {
        JSONArray result = new JSONArray();
        if (cronService == null) {
            return result;
        }
        try {
            java.util.List<com.ava.mods.mimiclaw.cron.CronService.CronJob> jobs = cronService.listJobs();
            for (com.ava.mods.mimiclaw.cron.CronService.CronJob job : jobs) {
                JSONObject item = new JSONObject();
                item.put("id", job.id != null ? job.id : "");
                item.put("name", job.name != null ? job.name : "");
                item.put("title", job.title != null ? job.title : "");
                item.put("description", job.description != null ? job.description : "");
                item.put("enabled", job.enabled);
                item.put("kind", job.kind == com.ava.mods.mimiclaw.cron.CronService.KIND_EVERY ? "every" : "at");
                item.put("interval_s", job.intervalS);
                item.put("at_epoch", job.atEpoch);
                item.put("next_run", job.nextRun);
                result.put(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to build cron jobs for UI", e);
        }
        return result;
    }

    public boolean setCronJobEnabled(String jobId, boolean enabled) {
        if (cronService == null || jobId == null || jobId.trim().isEmpty()) {
            return false;
        }
        return cronService.setJobEnabled(jobId.trim(), enabled);
    }

    private boolean isInternalWebConsoleMessage(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("[SYSTEM] Skill '")
            || trimmed.startsWith(AI_BROWSER_EVENT_PREFIX)
            || trimmed.contains("[timer]")
            || trimmed.startsWith("HEARTBEAT_TICK:")
            || trimmed.startsWith("CRON_TICK:");
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
            for (int i = 0; i < arr.length(); i++) {
                JSONObject block = arr.getJSONObject(i);
                String type = block.optString("type", "");
                if ("text".equals(type)) {
                    String text = block.optString("text", "").trim();
                    if (!text.isEmpty() && !"null".equals(text)) {
                        result.put(block);
                    }
                } else if ("tool_use".equals(type) || "tool_result".equals(type)) {
                    // Keep tool blocks intact for UI rendering
                    result.put(block);
                }
            }
            if (result.length() == 0) return null;
            return result.toString();
        } catch (Exception e) {
            // Not a JSON array, return as-is (plain text)
            return content;
        }
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
        Log.d(TAG, "OpenClaw(Mini) onDestroy called");
        unregisterAiBrowserUiReceiver();
        
        // Cancel any pending LLM requests first - this will unblock HTTP connections
        if (llmProxy != null) {
            llmProxy.cancel();
        }
        
        // Stop agent loop first to prevent new work
        if (agentLoop != null) {
            agentLoop.stop();
        }
        
        // Interrupt agent thread
        if (agentThread != null) {
            agentThread.interrupt();
        }
        
        // Stop all channels and services
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
        
        // Clear message bus to unblock any waiting threads
        MessageBus bus = MessageBus.getInstance();
        bus.clearInbound(null, null);
        bus.clearOutbound(null, null);
        
        instance = null;
        Log.d(TAG, "OpenClaw(Mini) destroyed");
    }
}
