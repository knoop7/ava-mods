package com.ava.mods.mimiclaw.context;

import com.ava.mods.mimiclaw.memory.MemoryStore;
import com.ava.mods.mimiclaw.skills.SkillLoader;
import java.util.Date;
import java.util.function.Function;

public class ContextBuilder {
    public static final String HEARTBEAT_CHAT_ID = "__heartbeat__";
    
    public static final String CHANNEL_ANDROID = "android";
    public static final String CHANNEL_QQBOT = "qqbot";
    public static final String CHANNEL_TELEGRAM = "telegram";
    public static final String CHANNEL_WEBCONSOLE = "webconsole";
    
    private final MemoryStore memoryStore;
    private final SkillLoader skillLoader;
    private Function<String, Boolean> skillEnabledChecker;
    private int maxToolIterations = 50;

    public ContextBuilder(MemoryStore memoryStore, SkillLoader skillLoader) {
        this.memoryStore = memoryStore;
        this.skillLoader = skillLoader;
        this.skillEnabledChecker = skillId -> true; // Default: all enabled
    }
    
    public void setMaxToolIterations(int max) {
        this.maxToolIterations = max;
    }
    
    public void setSkillEnabledChecker(Function<String, Boolean> checker) {
        this.skillEnabledChecker = checker;
    }
    
    private boolean isSkillEnabled(String skillId) {
        return skillEnabledChecker != null ? skillEnabledChecker.apply(skillId) : true;
    }

    public String buildSystemPrompt(String channel, String chatId) {
        return buildSystemPrompt(channel, chatId, true);
    }
    
    public String buildSystemPrompt(String channel, String chatId, boolean isFirstMessage) {
        StringBuilder sb = new StringBuilder();

        // Heartbeat mode: lightweight prompt with essential context
        if (HEARTBEAT_CHAT_ID.equals(chatId)) {
            sb.append("# OpenClaw Heartbeat\n\n");
            sb.append("You are in heartbeat mode. Read HEARTBEAT.md and check if any items need action.\n");
            sb.append("If nothing actionable, return exactly: HEARTBEAT_OK\n");
            sb.append("Only return a notification if there's something worth pushing to the user.\n\n");
            
            // Include essential user context (truncated)
            String userInfo = memoryStore.readUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                String truncated = userInfo.length() > 500 ? userInfo.substring(0, 500) + "..." : userInfo;
                sb.append("## USER.md (summary)\n").append(truncated).append("\n\n");
            }
            
            appendSkill(sb, "heartbeat_cron.md");
            sb.append("\n## Time: ").append(new java.util.Date()).append("\n");
            return sb.toString();
        }

        appendBasePrompt(sb);
        appendChannelPrompt(sb, channel);
        
        // Only include memory files on first message of session to save tokens
        if (isFirstMessage) {
            appendMemorySection(sb);
        }
        
        appendSkillsSection(sb, channel, chatId);
        appendContextSection(sb, channel, chatId);

        return sb.toString();
    }

    private void appendBasePrompt(StringBuilder sb) {
        String basePrompt = skillLoader.readSkill("prompts/base.md");
        if (basePrompt != null && !basePrompt.trim().isEmpty()) {
            sb.append(basePrompt.trim()).append("\n\n");
        } else {
            sb.append("# OpenClaw(Mini)\n\n");
            sb.append("You are OpenClaw(Mini), a personal assistant running on an Android device.\n");
            sb.append("Be helpful, accurate, and concise.\n");
            sb.append("Reply exactly once per user message unless a tool call loop is required.\n");
            sb.append("Keep answers short unless the user asks for depth.\n\n");
        }
        // Always append tool iteration limit
        sb.append("## Tool Usage (IMPORTANT)\n");
        sb.append("You have **").append(maxToolIterations).append(" tool calls** available. ");
        sb.append("**DO NOT STOP EARLY!** Keep calling tools until the task is FULLY complete. ");
        sb.append("If a tool fails, try alternatives. If stuck, try different approaches. ");
        sb.append("Never give up before exhausting all options. Be persistent!\n\n");
    }

    private void appendChannelPrompt(StringBuilder sb, String channel) {
        String channelFile = "prompts/channel_" + channel + ".md";
        String channelPrompt = skillLoader.readSkill(channelFile);
        
        if (channelPrompt != null && !channelPrompt.trim().isEmpty()) {
            sb.append("## Channel: ").append(channel.toUpperCase()).append("\n\n");
            sb.append(channelPrompt.trim()).append("\n\n");
        }
    }

    private void appendMemorySection(StringBuilder sb) {
        appendSection(sb, "AGENTS.md", memoryStore.readAgents());
        appendSection(sb, "SOUL.md", memoryStore.readSoul());
        appendSection(sb, "USER.md", memoryStore.readUserInfo());
        appendSection(sb, "MEMORY.md", memoryStore.readLongTermMemory());
        appendSection(sb, "Recent Notes", memoryStore.readRecent(3));
    }

    private void appendSkillsSection(StringBuilder sb, String channel, String chatId) {
        // Heartbeat mode: minimal skills to save tokens
        if (HEARTBEAT_CHAT_ID.equals(chatId)) {
            appendSkill(sb, "heartbeat_cron.md");
            return;
        }
        
        sb.append("## Skill Management\n\n");
        sb.append("**IMPORTANT**: You MUST call `read_skill <name>` BEFORE using ANY skill-specific tool.\n");
        sb.append("Skill tools will NOT work until you load the skill instructions first.\n\n");
        sb.append("Management tools:\n");
        sb.append("- `list_skills`: List installed skill files\n");
        sb.append("- `read_skill <name>`: **REQUIRED** - Load skill before using its tools\n");
        sb.append("- `install_skill_from_text`: Create/overwrite skill\n");
        sb.append("- `install_skill_from_url`: Install skill from URL\n");
        sb.append("- `delete_skill`: Remove skill\n\n");
        
        sb.append("## Available Skills\n\n");
        
        sb.append("### Android Core\n");
        if (isSkillEnabled("android_system_bridge")) {
            sb.append("- **android_system_bridge**: Device info, shell commands, system inspection\n");
        }
        if (isSkillEnabled("android_accessibility_bridge")) {
            sb.append("- **android_accessibility_bridge**: UI inspection and control without root\n");
        }
        if (isSkillEnabled("android_browser_bridge")) {
            sb.append("- **android_browser_bridge**: Floating browser control\n");
        }
        
        if (isSkillEnabled("homeassistant")) {
            sb.append("\n### Smart Home\n");
            sb.append("- **homeassistant**: Home Assistant control (26 tools: states, control, TODO, conversation)\n");
        }
        
        if (isSkillEnabled("multi_search_engine")) {
            sb.append("\n### Web & Search\n");
            sb.append("- **multi_search_engine**: Web search with Tavily + 17 fallback engines\n");
        }
        
        sb.append("\n### Utilities\n");
        sb.append("- **heartbeat_cron**: Scheduled background tasks\n");
        if (CHANNEL_QQBOT.equals(channel)) {
            sb.append("- **qqbot_media**: QQ media output (images, voice, files)\n");
        }
    }

    private void appendContextSection(StringBuilder sb, String channel, String chatId) {
        sb.append("## Current Context\n");
        sb.append("- Channel: ").append(channel).append("\n");
        sb.append("- Chat ID: ").append(chatId).append("\n");
        sb.append("- Time: ").append(new Date()).append("\n");
    }

    private void appendHeartbeatMode(StringBuilder sb) {
        sb.append("\n## Heartbeat Mode\n\n");
        sb.append("This is an isolated scheduled heartbeat run, not a normal conversation.\n");
        sb.append("Read `HEARTBEAT.md` using the read_file tool.\n");
        sb.append("If `HEARTBEAT.md` is missing or effectively empty, return exactly `HEARTBEAT_OK`.\n");
        sb.append("If the checklist has nothing actionable right now, return exactly `HEARTBEAT_OK`.\n");
        sb.append("Only return a user-facing notification when there is a real item worth proactively pushing.\n");
        sb.append("Keep heartbeat notifications concise and actionable.\n");
        sb.append("Do not chat, explain your process, or repeat status lines in heartbeat mode.\n");
    }

    private void appendSkill(StringBuilder sb, String name) {
        String content = skillLoader.readSkill(name);
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        sb.append("\n## Loaded Skill: ").append(name).append("\n\n");
        sb.append(content.trim()).append("\n");
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        sb.append("\n## ").append(title).append("\n\n");
        sb.append(content.trim()).append("\n");
    }
}
