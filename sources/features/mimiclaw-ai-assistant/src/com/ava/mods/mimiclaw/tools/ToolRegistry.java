package com.ava.mods.mimiclaw.tools;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Log;
import com.ava.mods.mimiclaw.memory.MemoryStore;
import com.ava.mods.mimiclaw.cron.CronService;
import com.ava.mods.mimiclaw.skills.SkillLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ToolRegistry {
    private static final String TAG = "ToolRegistry";
    private static volatile ToolRegistry instance;
    private static final Set<String> SAFE_SHELL_PREFIXES = new HashSet<String>() {{
        add("getprop");
        add("logcat");
        add("settings get");
        add("dumpsys power");
        add("dumpsys activity");
        add("dumpsys package");
        add("dumpsys window");
        add("dumpsys input");
        add("dumpsys display");
        add("dumpsys accessibility");
        add("dumpsys notification");
        add("dumpsys sensorservice");
        add("dumpsys meminfo");
        add("pm list packages");
        add("pm path");
        add("cmd statusbar");
        add("cmd notification");
        add("cmd activity get-uid-state");
        add("cmd overlay list");
        add("ip addr");
        add("ip route");
        add("wm size");
        add("wm density");
        add("df");
        add("cat /proc/meminfo");
        add("cat /proc/cpuinfo");
        add("uname -a");
        add("curl");
        add("ls");
        add("cd");
        add("bash");
        add("exit");
        add("pwd");
        add("cat");
        add("head");
        add("tail");
        add("grep");
        add("find");
        add("which");
        add("whoami");
        add("id");
        add("date");
        add("uptime");
        add("free");
        add("top -n 1");
        add("ps");
        add("netstat");
        add("ping");
        add("nslookup");
        add("ifconfig");
        add("echo");
        add("printf");
        add("wc");
        add("sort");
        add("uniq");
        add("cut");
        add("awk");
        add("sed");
        add("tr");
        add("md5sum");
        add("sha256sum");
        add("base64");
        add("stat");
        add("file");
        add("du");
        add("env");
        add("printenv");
        add("input keyevent");
        add("am start");
        add("tar");
        add("wget");
        add("unzip");
        add("zip");
        add("python");
        add("python3");
        add("pip");
        add("pip3");
        add("busybox");
        add("xz");
        add("unxz");
        add("gzip");
        add("gunzip");
        add("bzip2");
        add("bunzip2");
    }};
    private static final String CONFIRM_TOKEN = "USER_CONFIRMED";
    
    private final Map<String, Tool> tools = new HashMap<>();
    private final Map<String, ToolDef> toolDefs = new HashMap<>();
    private final Map<String, String> toolSkillMap = new HashMap<>(); // tool name -> skill id
    private JSONArray toolsJson;
    private Context context;
    private MemoryStore memoryStore;
    private CronService cronService;
    private SkillLoader skillLoader;
    private String tavilyKey = "";
    private String currentChannel = "android";
    private String currentChatId = "default";
    private java.util.function.Function<String, Boolean> skillEnabledChecker;
    
    public void setCurrentContext(String channel, String chatId) {
        this.currentChannel = channel != null ? channel : "android";
        this.currentChatId = chatId != null ? chatId : "default";
    }
    
    public void setSkillEnabledChecker(java.util.function.Function<String, Boolean> checker) {
        this.skillEnabledChecker = checker;
    }
    
    private boolean isSkillEnabled(String skillId) {
        return skillEnabledChecker == null || skillEnabledChecker.apply(skillId);
    }
    
    public interface Tool {
        String execute(String inputJson) throws Exception;
    }
    
    private static class ToolDef {
        String name;
        String description;
        String inputSchema;
    }
    
    private ToolRegistry() {}
    
    public static ToolRegistry getInstance() {
        if (instance == null) {
            synchronized (ToolRegistry.class) {
                if (instance == null) {
                    instance = new ToolRegistry();
                }
            }
        }
        return instance;
    }
    
    public void init(Context context, MemoryStore memoryStore, CronService cronService) {
        this.context = context;
        this.memoryStore = memoryStore;
        this.cronService = cronService;
        this.skillLoader = new SkillLoader(context);
        registerBuiltinTools();
        buildToolsJson();
    }
    
    public void setTavilyKey(String key) {
        this.tavilyKey = key;
    }
    
    private void addTool(String name, String description, String inputSchema, Tool tool) {
        addTool(name, description, inputSchema, tool, null);
    }
    
    private void addToolWithSkill(String name, String description, String inputSchema, Tool tool, String skillId) {
        addTool(name, description, inputSchema, tool, skillId);
    }
    
    private void addTool(String name, String description, String inputSchema, Tool tool, String skillId) {
        tools.put(name, tool);
        ToolDef def = new ToolDef();
        def.name = name;
        def.description = description;
        def.inputSchema = inputSchema;
        toolDefs.put(name, def);
        if (skillId != null) {
            toolSkillMap.put(name, skillId);
        }
        Log.d(TAG, "Registered tool: " + name + (skillId != null ? " (skill: " + skillId + ")" : ""));
    }
    
    public String executeTool(String name, String inputJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Error: unknown tool '" + name + "'";
        }
        
        try {
            Log.d(TAG, "Executing tool: " + name);
            return tool.execute(inputJson);
        } catch (Exception e) {
            Log.e(TAG, "Tool execution failed: " + name, e);
            return "Error: " + e.getMessage();
        }
    }
    
    public JSONArray getToolsJson() {
        // Dynamic filtering based on skill config
        JSONArray filtered = new JSONArray();
        try {
            for (ToolDef def : toolDefs.values()) {
                String skillId = toolSkillMap.get(def.name);
                if (skillId != null && !isSkillEnabled(skillId)) {
                    continue; // Skip disabled skill tools
                }
                JSONObject tool = new JSONObject();
                tool.put("name", def.name);
                tool.put("description", def.description);
                tool.put("input_schema", new JSONObject(def.inputSchema));
                filtered.put(tool);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build filtered tools JSON", e);
            return toolsJson; // Fallback to cached
        }
        return filtered;
    }
    
    private void registerBuiltinTools() {
        addTool("get_current_time", 
            "Get the current date and time. You do NOT have an internal clock - always use this tool when you need to know the time or date.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US);
                return sdf.format(new Date());
            }
        );
        
        addTool("get_device_info",
            "Get Android device model, version, memory, storage, app version, ABI, and currently granted app permissions.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> buildDeviceInfoJson()
        );

        addTool("list_skills",
            "List locally installed SKILL markdown files available to the agent.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String[] files = memoryStore.listFiles("skills");
                if (files == null || files.length == 0) {
                    return "No skills found";
                }
                StringBuilder sb = new StringBuilder();
                for (String file : files) {
                    if (file != null && file.endsWith(".md")) {
                        String skillId = file.replace(".md", "");
                        if (skillEnabledChecker == null || skillEnabledChecker.apply(skillId)) {
                            sb.append(file).append("\n");
                        }
                    }
                }
                return sb.length() > 0 ? sb.toString().trim() : "No skills found";
            }
        );

        addTool("read_skill",
            "Read a local SKILL markdown file from the app's skills directory.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Skill filename, with or without .md suffix\"}},\"required\":[\"name\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String name = input.optString("name", "").trim();
                if (name.isEmpty()) {
                    return "Error: Skill name is required";
                }
                String safeName = name.endsWith(".md") ? name : name + ".md";
                if (safeName.contains("/") || safeName.contains("\\") || safeName.contains("..")) {
                    return "Error: Invalid skill name";
                }
                String skillId = safeName.replace(".md", "");
                if (skillEnabledChecker != null && !skillEnabledChecker.apply(skillId)) {
                    return "Error: Skill '" + skillId + "' is disabled";
                }
                String content = memoryStore.readFileByPath("skills/" + safeName);
                return (content != null && !content.trim().isEmpty()) ? content : "Error: Skill not found";
            }
        );

        addTool("describe_skill",
            "Get metadata about an installed skill file, including its path, size, and title line.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Skill filename, with or without .md suffix\"}},\"required\":[\"name\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String safeName = normalizeSkillName(input.optString("name", ""));
                if (safeName.isEmpty()) {
                    return "Error: Invalid skill name";
                }
                String relativePath = "skills/" + safeName;
                String content = memoryStore.readFileByPath(relativePath);
                if (content == null || content.trim().isEmpty()) {
                    return "Error: Skill not found";
                }
                JSONObject result = new JSONObject();
                result.put("name", safeName);
                result.put("path", relativePath);
                result.put("bytes", content.getBytes("UTF-8").length);
                result.put("title", extractSkillTitle(content));
                result.put("lines", content.split("\\r?\\n").length);
                return result.toString(2);
            }
        );

        addTool("install_skill_from_text",
            "Install or overwrite a local skill markdown file in the app's skills directory.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Skill filename, with or without .md suffix\"},\"content\":{\"type\":\"string\",\"description\":\"Markdown content for the skill\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"name\",\"content\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "install skill file");
                if (confirmation != null) {
                    return confirmation;
                }
                String safeName = normalizeSkillName(input.optString("name", ""));
                String content = input.optString("content", "");
                if (safeName.isEmpty()) {
                    return "Error: Invalid skill name";
                }
                if (content.trim().isEmpty()) {
                    return "Error: Skill content is empty";
                }
                boolean ok = memoryStore.writeFileByPath("skills/" + safeName, content);
                return ok ? "Skill installed: " + safeName : "Error: Failed to install skill";
            }
        );

        addTool("install_skill_from_url",
            "Download a markdown skill file from a URL into the app's skills directory. This works for direct skill URLs, including SkillHub install links.",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"Direct URL to a markdown skill file\"},\"name\":{\"type\":\"string\",\"description\":\"Optional local filename override\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"url\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "download and install a skill from URL");
                if (confirmation != null) {
                    return confirmation;
                }
                String url = input.optString("url", "").trim();
                String requestedName = input.optString("name", "").trim();
                if (url.isEmpty()) {
                    return "Error: url is required";
                }
                String content = downloadText(url);
                if (content.trim().isEmpty()) {
                    return "Error: Downloaded skill is empty";
                }
                String fileName = normalizeSkillName(!requestedName.isEmpty() ? requestedName : inferSkillFileName(url));
                if (fileName.isEmpty()) {
                    return "Error: Could not determine skill filename";
                }
                boolean ok = memoryStore.writeFileByPath("skills/" + fileName, content);
                return ok ? "Skill installed from URL: " + fileName : "Error: Failed to install skill from URL";
            }
        );

        addTool("delete_skill",
            "Delete an installed skill markdown file from the app's skills directory.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Skill filename, with or without .md suffix\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"name\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "delete a skill file");
                if (confirmation != null) {
                    return confirmation;
                }
                String safeName = normalizeSkillName(input.optString("name", ""));
                if (safeName.isEmpty()) {
                    return "Error: Invalid skill name";
                }
                boolean ok = memoryStore.deleteFileByPath("skills/" + safeName);
                return ok ? "Skill deleted: " + safeName : "Error: Failed to delete skill";
            }
        );

        addTool("open_browser_url",
            "Open URL in Ava's internal overlay ONLY. For normal browsing, use android_shell_exec with 'am start -a android.intent.action.VIEW -d <url>' instead.",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"The URL to open\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"url\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "open Ava's internal browser");
                if (confirmation != null) {
                    return confirmation;
                }
                String url = input.optString("url", "").trim();
                if (url.isEmpty()) {
                    return "Error: url is required";
                }
                invokeStaticVoid("com.example.ava.services.AiBrowserService", "hide", new Class[]{Context.class}, new Object[]{context});
                android.content.Intent intent = new android.content.Intent();
                intent.setClassName(context, "com.example.ava.services.AiBrowserService");
                intent.setAction("com.example.ava.services.AiBrowserService.SHOW");
                intent.putExtra("url", url);
                if ("webconsole".equals(currentChannel) && currentChatId != null && !currentChatId.trim().isEmpty()) {
                    intent.putExtra("sid", currentChatId);
                }
                context.startService(intent);
                return "Opened AI browser overlay: " + url;
            }
        );

        addTool("ava_browser_hide",
            "Hide Ava's internal floating browser overlay (NOT system browsers).",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                android.content.Intent hideIntent = new android.content.Intent();
                hideIntent.setClassName(context, "com.example.ava.services.AiBrowserService");
                hideIntent.setAction("com.example.ava.services.AiBrowserService.HIDE");
                context.startService(hideIntent);
                return "AI browser hidden";
            }
        );

        addTool("ava_browser_refresh",
            "Refresh current page in Ava's internal browser overlay.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                android.content.Intent refreshIntent = new android.content.Intent();
                refreshIntent.setClassName(context, "com.example.ava.services.AiBrowserService");
                refreshIntent.setAction("com.example.ava.services.AiBrowserService.REFRESH");
                context.startService(refreshIntent);
                return "AI browser refreshed";
            }
        );

        addTool("ava_browser_read_text",
            "Read text from Ava's internal browser overlay (NOT system browsers).",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                try {
                    Class<?> serviceClass = loadHostClass("com.example.ava.services.AiBrowserService");
                    Object result = serviceClass.getMethod("getCurrentPageText", Context.class).invoke(null, context);
                    String text = result != null ? String.valueOf(result) : "";
                    if (text.isEmpty()) {
                        return "No text available from browser";
                    }
                    try {
                        JSONObject raw = new JSONObject(text);
                        if (raw.optBoolean("ok", false)) {
                            JSONObject slim = new JSONObject();
                            slim.put("ok", true);
                            slim.put("url", raw.optString("url", ""));
                            slim.put("title", raw.optString("title", ""));
                            slim.put("excerpt", raw.optString("excerpt", ""));
                            String body = raw.optString("text", "");
                            if (body.length() > 1200) {
                                body = body.substring(0, 1200) + "...";
                            }
                            slim.put("text", body);
                            slim.put("length", raw.optInt("length", body.length()));
                            slim.put("truncated", raw.optBoolean("truncated", false) || raw.optInt("length", body.length()) > body.length());
                            return slim.toString();
                        }
                    } catch (Exception ignored) {
                    }
                    if (text.length() > 1500) {
                        text = text.substring(0, 1500) + "...";
                    }
                    return text;
                } catch (Exception e) {
                    return "Error reading browser text: " + e.getMessage();
                }
            }
        );

        addTool("android_shell_exec",
            "Execute Android system shell command (Shizuku/root). Whitelisted only: getprop, cat, ls, ps, dumpsys, pm, logcat, df, ip, busybox, etc. For compression use 'busybox xz/gzip/bzip2'. For complex scripts or non-whitelisted commands, use terminal_exec instead.",
            "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"Whitelisted shell command\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"command\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "execute Android shell command");
                if (confirmation != null) {
                    return confirmation;
                }
                String command = input.optString("command", "").trim();
                if (command.isEmpty()) {
                    return "Error: command is required";
                }
                if (!isAllowedShellCommand(command)) {
                    return "Error: command not allowed";
                }
                return executeAndroidShell(command);
            }
        );

        addTool("terminal_exec",
            "Execute command in Termux PTY terminal. NO whitelist restrictions. Use this for: complex scripts, pipes, Python, non-system commands, anything android_shell_exec cannot do. Prefer this over android_shell_exec when you need full shell capabilities.",
            "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"Shell command to execute\"},\"cwd\":{\"type\":\"string\",\"description\":\"Working directory (optional)\"},\"timeout\":{\"type\":\"integer\",\"description\":\"Timeout in seconds (default 30)\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"command\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "execute terminal command");
                if (confirmation != null) {
                    return confirmation;
                }
                String command = input.optString("command", "").trim();
                if (command.isEmpty()) {
                    return "Error: command is required";
                }
                String cwd = input.optString("cwd", null);
                int timeout = input.optInt("timeout", 30);
                try {
                    com.ava.mods.mimiclaw.terminal.TerminalService termService = com.ava.mods.mimiclaw.terminal.TerminalService.getInstance(context);
                    return termService.executeCommand(command, cwd, timeout);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );
        
        addTool("accessibility_status",
            "Get Accessibility mixed-mode availability, including whether the Ava accessibility bridge is enabled and connected.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> invokeAccessibilityString("getStatus", new Class[]{Context.class}, new Object[]{context})
        );

        addTool("accessibility_open_settings",
            "Open Android accessibility settings so the user can enable Ava accessibility mode. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "open accessibility settings");
                if (confirmation != null) {
                    return confirmation;
                }
                Object result = invokeAccessibility("openSettings", new Class[]{Context.class}, new Object[]{context});
                return Boolean.TRUE.equals(result) ? "Opened accessibility settings" : "Error: Failed to open accessibility settings";
            }
        );

        addTool("ui_tree_dump",
            "Dump the current Android UI tree as structured JSON using the accessibility service.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> invokeAccessibilityString("dumpUiTreeJson", new Class[]{}, new Object[]{})
        );

        addTool("ui_find_text",
            "Find UI nodes by visible text or content description using the current accessibility tree.",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Text to find in the UI\"},\"contains\":{\"type\":\"boolean\",\"description\":\"Use partial matching, defaults to true\"}},\"required\":[\"query\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String query = input.optString("query", "").trim();
                boolean contains = input.optBoolean("contains", true);
                if (query.isEmpty()) {
                    return "Error: query is required";
                }
                return findUiText(query, contains);
            }
        );

        addTool("ui_click_text",
            "Find a UI node by visible text and click it using node index first, then coordinate fallback. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Text to find in the UI\"},\"contains\":{\"type\":\"boolean\",\"description\":\"Use partial matching, defaults to true\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"query\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "click a UI element by text");
                if (confirmation != null) {
                    return confirmation;
                }
                String query = input.optString("query", "").trim();
                boolean contains = input.optBoolean("contains", true);
                if (query.isEmpty()) {
                    return "Error: query is required";
                }
                JSONObject match = findBestUiNode(query, contains);
                if (match == null) {
                    return "Error: No UI text match found for: " + query;
                }
                int index = match.optInt("index", -1);
                JSONObject bounds = match.optJSONObject("bounds");
                Integer x = bounds != null && bounds.has("centerX") ? Integer.valueOf(bounds.optInt("centerX")) : null;
                Integer y = bounds != null && bounds.has("centerY") ? Integer.valueOf(bounds.optInt("centerY")) : null;
                return invokeAccessibilityString("clickByIndex", new Class[]{int.class, Integer.class, Integer.class}, new Object[]{index, x, y});
            }
        );

        addTool("ui_click",
            "Click a UI element by accessibility node index first, then fall back to coordinates if needed. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\",\"description\":\"Node index from ui_tree_dump\"},\"x\":{\"type\":\"integer\",\"description\":\"Optional fallback X\"},\"y\":{\"type\":\"integer\",\"description\":\"Optional fallback Y\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"index\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "click a UI element");
                if (confirmation != null) {
                    return confirmation;
                }
                int index = input.optInt("index", Integer.MIN_VALUE);
                if (index == Integer.MIN_VALUE) {
                    return "Error: index is required";
                }
                Integer x = input.has("x") ? Integer.valueOf(input.optInt("x")) : null;
                Integer y = input.has("y") ? Integer.valueOf(input.optInt("y")) : null;
                return invokeAccessibilityString("clickByIndex", new Class[]{int.class, Integer.class, Integer.class}, new Object[]{index, x, y});
            }
        );

        addTool("ui_set_text",
            "Set text in an editable UI element by accessibility node index, with optional coordinate fallback. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\",\"description\":\"Node index from ui_tree_dump\"},\"text\":{\"type\":\"string\",\"description\":\"Text to set\"},\"x\":{\"type\":\"integer\",\"description\":\"Optional fallback X\"},\"y\":{\"type\":\"integer\",\"description\":\"Optional fallback Y\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"index\",\"text\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "set text in a UI field");
                if (confirmation != null) {
                    return confirmation;
                }
                int index = input.optInt("index", Integer.MIN_VALUE);
                String textValue = input.optString("text", "");
                if (index == Integer.MIN_VALUE) {
                    return "Error: index is required";
                }
                Integer x = input.has("x") ? Integer.valueOf(input.optInt("x")) : null;
                Integer y = input.has("y") ? Integer.valueOf(input.optInt("y")) : null;
                return invokeAccessibilityString("setTextByIndex", new Class[]{int.class, String.class, Integer.class, Integer.class}, new Object[]{index, textValue, x, y});
            }
        );

        addTool("ui_scroll",
            "Scroll a UI element by accessibility node index first, then fall back to coordinates if needed. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\",\"description\":\"Node index from ui_tree_dump\"},\"forward\":{\"type\":\"boolean\",\"description\":\"True for forward/down, false for backward/up\"},\"x\":{\"type\":\"integer\",\"description\":\"Optional fallback X\"},\"y\":{\"type\":\"integer\",\"description\":\"Optional fallback Y\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"index\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "scroll the UI");
                if (confirmation != null) {
                    return confirmation;
                }
                int index = input.optInt("index", Integer.MIN_VALUE);
                boolean forward = input.optBoolean("forward", true);
                if (index == Integer.MIN_VALUE) {
                    return "Error: index is required";
                }
                Integer x = input.has("x") ? Integer.valueOf(input.optInt("x")) : null;
                Integer y = input.has("y") ? Integer.valueOf(input.optInt("y")) : null;
                return invokeAccessibilityString("scrollByIndex", new Class[]{int.class, boolean.class, Integer.class, Integer.class}, new Object[]{index, forward, x, y});
            }
        );

        addTool("ui_screenshot",
            "Capture a screenshot and return the saved file path. Prefer root, then Shizuku, then accessibility. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "capture a UI screenshot");
                if (confirmation != null) {
                    return confirmation;
                }
                return takeSmartScreenshot();
            }
        );

        addTool("read_agents",
            "Read AGENTS.md - operating instructions for how you should work and use memory. Load at session start.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String content = memoryStore.readAgents();
                return (content != null && !content.trim().isEmpty()) ? content : "AGENTS.md is empty.";
            }
        );
        
        addTool("update_agents",
            "Update AGENTS.md - your operating instructions. Add your own conventions as you learn what works.",
            "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"description\":\"Complete AGENTS.md content in markdown format\"}},\"required\":[\"content\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String content = input.optString("content", "");
                if (content.trim().isEmpty()) {
                    return "Error: content is required";
                }
                memoryStore.writeAgents(content);
                return "AGENTS.md updated successfully";
            }
        );
        
        addTool("read_soul",
            "Read SOUL.md - your personality, tone, and boundaries. This defines who you are.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String content = memoryStore.readSoul();
                return (content != null && !content.trim().isEmpty()) ? content : "SOUL.md is empty.";
            }
        );
        
        addTool("update_soul",
            "Update SOUL.md - your personality definition. IMPORTANT: Tell the user when you change this file.",
            "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"description\":\"Complete SOUL.md content in markdown format\"}},\"required\":[\"content\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String content = input.optString("content", "");
                if (content.trim().isEmpty()) {
                    return "Error: content is required";
                }
                memoryStore.writeSoul(content);
                return "SOUL.md updated. Remember to inform the user about this change.";
            }
        );
        
        addTool("read_user",
            "Read USER.md - information about the user: name, preferences, habits, background.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String content = memoryStore.readUserInfo();
                return (content != null && !content.trim().isEmpty()) ? content : "USER.md is empty. Use update_user to add information about the user.";
            }
        );
        
        addTool("update_user",
            "Update USER.md with information learned about the user. Persists across sessions.",
            "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"description\":\"Complete USER.md content in markdown format\"}},\"required\":[\"content\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String content = input.optString("content", "");
                if (content.trim().isEmpty()) {
                    return "Error: content is required";
                }
                memoryStore.writeUserInfo(content);
                return "USER.md updated successfully";
            }
        );
        
        addTool("read_memory",
            "Read the long-term memory notes. Contains important facts, decisions, and context to remember across sessions.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String content = memoryStore.readLongTermMemory();
                return (content != null && !content.trim().isEmpty()) ? content : "Long-term memory is empty. Use update_memory to add notes.";
            }
        );
        
        addTool("update_memory",
            "Update long-term memory with important information to remember. This persists across sessions. Use for: key decisions, project context, user requests to remember something.",
            "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"description\":\"Complete memory content in markdown format\"}},\"required\":[\"content\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String content = input.optString("content", "");
                if (content.trim().isEmpty()) {
                    return "Error: content is required";
                }
                memoryStore.writeLongTermMemory(content);
                return "Long-term memory updated successfully";
            }
        );
        
        addTool("append_daily_note",
            "Append a note to today's daily log. Good for tracking events, observations, or things to follow up on.",
            "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\",\"description\":\"Note content to append\"}},\"required\":[\"note\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String note = input.optString("note", "");
                if (note.trim().isEmpty()) {
                    return "Error: note is required";
                }
                memoryStore.appendToday(note);
                return "Note appended to today's log";
            }
        );

        addTool("read_file",
            "Read a file from app storage or the shared SD card directory. Other absolute paths are blocked.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path\"}},\"required\":[\"path\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String path = input.optString("path", "");
                String content = memoryStore.readFileByPath(path);
                return content != null ? content : "Error: File not found";
            }
        );

        addTool("delete_file",
            "Delete a file from app storage or the shared SD card directory. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path to delete\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"path\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "delete file");
                if (confirmation != null) {
                    return confirmation;
                }
                String path = input.optString("path", "").trim();
                if (path.isEmpty()) {
                    return "Error: path is required";
                }
                boolean ok = memoryStore.deleteFileByPath(path);
                return ok ? "File deleted: " + path : "Error: Failed to delete file";
            }
        );
        
        addTool("write_file",
            "Write or overwrite a file on storage.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path\"},\"content\":{\"type\":\"string\",\"description\":\"File content to write\"}},\"required\":[\"path\",\"content\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String path = input.optString("path", "");
                String content = input.optString("content", "");
                boolean ok = memoryStore.writeFileByPath(path, content);
                return ok ? "File written successfully" : "Error: Failed to write file";
            }
        );
        
        addTool("edit_file",
            "Find and replace text in a file. Replaces first occurrence of old_string with new_string.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path\"},\"old_string\":{\"type\":\"string\",\"description\":\"Text to find\"},\"new_string\":{\"type\":\"string\",\"description\":\"Replacement text\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String path = input.optString("path", "");
                String oldStr = input.optString("old_string", "");
                String newStr = input.optString("new_string", "");
                boolean ok = memoryStore.editFile(path, oldStr, newStr);
                return ok ? "File edited successfully" : "Error: Failed to edit file (string not found or file not exists)";
            }
        );
        
        addTool("list_dir",
            "List files in app storage or the shared SD card directory.",
            "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\",\"description\":\"Optional path prefix filter\"}},\"required\":[]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String prefix = input.optString("prefix", "");
                String[] files = memoryStore.listFiles(prefix.isEmpty() ? null : prefix);
                StringBuilder sb = new StringBuilder();
                for (String f : files) {
                    sb.append(f).append("\n");
                }
                return sb.length() > 0 ? sb.toString() : "No files found";
            }
        );
        
        addTool("cron_add",
            "Schedule a recurring or one-shot task. The message will trigger an agent turn when the job fires.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Short name for the job\"},\"schedule_type\":{\"type\":\"string\",\"description\":\"'every' for recurring interval or 'at' for one-shot at a unix timestamp\"},\"interval_s\":{\"type\":\"integer\",\"description\":\"Interval in seconds (required for 'every')\"},\"at_epoch\":{\"type\":\"integer\",\"description\":\"Unix timestamp to fire at (required for 'at')\"},\"message\":{\"type\":\"string\",\"description\":\"Message to inject when the job fires\"},\"channel\":{\"type\":\"string\",\"description\":\"Optional reply channel\"},\"chat_id\":{\"type\":\"string\",\"description\":\"Optional reply chat_id\"}},\"required\":[\"name\",\"schedule_type\",\"message\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                CronService.CronJob job = new CronService.CronJob();
                job.name = input.optString("name", "");
                job.message = input.optString("message", "");
                job.channel = input.optString("channel", currentChannel);
                job.chatId = input.optString("chat_id", currentChatId);
                
                String scheduleType = input.optString("schedule_type", "");
                if ("every".equals(scheduleType)) {
                    job.kind = CronService.KIND_EVERY;
                    job.intervalS = input.optLong("interval_s", 60);
                } else if ("at".equals(scheduleType)) {
                    job.kind = CronService.KIND_AT;
                    job.atEpoch = input.optLong("at_epoch", 0);
                } else {
                    return "Error: Invalid schedule_type, must be 'every' or 'at'";
                }
                
                String id = cronService.addJob(job);
                return id != null ? "Cron job added: " + id : "Error: Failed to add cron job (max jobs reached)";
            }
        );
        
        addTool("cron_list",
            "List all scheduled cron jobs with their status, schedule, and IDs.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                List<CronService.CronJob> jobs = cronService.listJobs();
                if (jobs.isEmpty()) {
                    return "No cron jobs scheduled";
                }
                StringBuilder sb = new StringBuilder();
                for (CronService.CronJob job : jobs) {
                    sb.append(job.id).append(" | ").append(job.name)
                      .append(" | ").append(job.enabled ? "enabled" : "disabled")
                      .append(" | ").append(job.kind == CronService.KIND_EVERY ? "every " + job.intervalS + "s" : "at " + job.atEpoch)
                      .append("\n");
                }
                return sb.toString();
            }
        );
        
        addTool("cron_remove",
            "Remove a scheduled cron job by its ID.",
            "{\"type\":\"object\",\"properties\":{\"job_id\":{\"type\":\"string\",\"description\":\"The job ID to remove\"}},\"required\":[\"job_id\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String jobId = input.optString("job_id", "");
                boolean ok = cronService.removeJob(jobId);
                return ok ? "Cron job removed: " + jobId : "Error: Job not found";
            }
        );

        addTool("web_search",
            "Search the web. Default: Tavily API (if key set). Fallback: 17 engines (baidu/bing/google/duckduckgo/360/sogou/wechat/toutiao/jisilu/yahoo/startpage/brave/ecosia/qwant/wolfram).",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"},\"engine\":{\"type\":\"string\",\"description\":\"Engine: tavily(default)/baidu/bing/google/duckduckgo/360/sogou/wechat/toutiao/jisilu/yahoo/startpage/brave/ecosia/qwant/wolfram\"}},\"required\":[\"query\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String query = input.optString("query", "").trim();
                if (query.isEmpty()) return "Error: query is required";
                
                String engine = input.optString("engine", "").toLowerCase();
                
                // Default to Tavily if key is set and no engine specified
                if (engine.isEmpty() || "tavily".equals(engine)) {
                    if (tavilyKey != null && !tavilyKey.isEmpty()) {
                        try {
                            JSONObject payload = new JSONObject();
                            payload.put("api_key", tavilyKey);
                            payload.put("query", query);
                            payload.put("max_results", 5);
                            payload.put("search_depth", "advanced");
                            
                            URL url = new URL("https://api.tavily.com/search");
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            conn.setDoOutput(true);
                            conn.setConnectTimeout(15000);
                            conn.setReadTimeout(15000);
                            
                            OutputStream os = conn.getOutputStream();
                            os.write(payload.toString().getBytes("UTF-8"));
                            os.close();
                            
                            int code = conn.getResponseCode();
                            if (code == 200) {
                                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) sb.append(line);
                                reader.close();
                                conn.disconnect();
                                
                                JSONObject resp = new JSONObject(sb.toString());
                                JSONArray results = resp.optJSONArray("results");
                                if (results != null && results.length() > 0) {
                                    StringBuilder output = new StringBuilder();
                                    for (int i = 0; i < Math.min(results.length(), 5); i++) {
                                        JSONObject item = results.getJSONObject(i);
                                        output.append(i + 1).append(". ").append(item.optString("title", "")).append("\n");
                                        output.append("   ").append(item.optString("url", "")).append("\n");
                                        output.append("   ").append(item.optString("content", "")).append("\n\n");
                                    }
                                    JSONObject result = new JSONObject();
                                    result.put("engine", "tavily");
                                    result.put("query", query);
                                    result.put("content", output.toString().trim());
                                    return result.toString(2);
                                }
                            }
                            conn.disconnect();
                        } catch (Exception e) {
                            // Fall through to other engines
                        }
                    }
                    // No Tavily key or failed, default to baidu
                    if (engine.isEmpty()) engine = "baidu";
                }
                
                String encodedQuery = URLEncoder.encode(query, "UTF-8");
                
                // Build search URL based on engine
                String searchUrl;
                switch (engine) {
                    case "baidu": searchUrl = "https://www.baidu.com/s?wd=" + encodedQuery; break;
                    case "bing": searchUrl = "https://cn.bing.com/search?q=" + encodedQuery; break;
                    case "bing_int": searchUrl = "https://cn.bing.com/search?q=" + encodedQuery + "&ensearch=1"; break;
                    case "google": searchUrl = "https://www.google.com/search?q=" + encodedQuery; break;
                    case "google_hk": searchUrl = "https://www.google.com.hk/search?q=" + encodedQuery; break;
                    case "duckduckgo": searchUrl = "https://duckduckgo.com/html/?q=" + encodedQuery; break;
                    case "360": searchUrl = "https://www.so.com/s?q=" + encodedQuery; break;
                    case "sogou": searchUrl = "https://sogou.com/web?query=" + encodedQuery; break;
                    case "wechat": searchUrl = "https://wx.sogou.com/weixin?type=2&query=" + encodedQuery; break;
                    case "toutiao": searchUrl = "https://so.toutiao.com/search?keyword=" + encodedQuery; break;
                    case "jisilu": searchUrl = "https://www.jisilu.cn/explore/?keyword=" + encodedQuery; break;
                    case "yahoo": searchUrl = "https://search.yahoo.com/search?p=" + encodedQuery; break;
                    case "startpage": searchUrl = "https://www.startpage.com/sp/search?query=" + encodedQuery; break;
                    case "brave": searchUrl = "https://search.brave.com/search?q=" + encodedQuery; break;
                    case "ecosia": searchUrl = "https://www.ecosia.org/search?q=" + encodedQuery; break;
                    case "qwant": searchUrl = "https://www.qwant.com/?q=" + encodedQuery; break;
                    case "wolfram": searchUrl = "https://www.wolframalpha.com/input?i=" + encodedQuery; break;
                    default: searchUrl = "https://www.baidu.com/s?wd=" + encodedQuery; engine = "baidu"; break;
                }
                
                // Try curl first (faster, no UI)
                try {
                    String[] cmd = {"curl", "-s", "-L", "-A", "Mozilla/5.0", "--connect-timeout", "10", searchUrl};
                    Process p = Runtime.getRuntime().exec(cmd);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    StringBuilder html = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        html.append(line).append("\n");
                        if (html.length() > 50000) break;
                    }
                    p.waitFor();
                    
                    if (html.length() > 100) {
                        String text = html.toString()
                            .replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                            .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                            .replaceAll("<[^>]+>", " ")
                            .replaceAll("&nbsp;", " ")
                            .replaceAll("&lt;", "<")
                            .replaceAll("&gt;", ">")
                            .replaceAll("&amp;", "&")
                            .replaceAll("\\s+", " ")
                            .trim();
                        
                        String[] lines = text.split("\\s{2,}");
                        StringBuilder sb = new StringBuilder();
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (String l : lines) {
                            String trimmed = l.trim();
                            if (trimmed.length() < 5) continue;
                            if (seen.contains(trimmed)) continue;
                            seen.add(trimmed);
                            sb.append(trimmed).append("\n");
                            if (sb.length() > 3000) break;
                        }
                        
                        JSONObject result = new JSONObject();
                        result.put("engine", engine);
                        result.put("query", query);
                        result.put("url", searchUrl);
                        result.put("content", sb.toString().trim());
                        return result.toString(2);
                    }
                } catch (Exception e) {
                    // Fall through to browser
                }
                
                // Fallback: AI browser (kept separate from HA/HASS WebViewService)
                if (!engine.equals("baidu") && !engine.equals("bing") && !engine.equals("google")) {
                    return "Error: curl failed and browser fallback disabled for " + engine;
                }
                
                invokeStaticVoid("com.example.ava.services.AiBrowserService", "hide", new Class[]{Context.class}, new Object[]{context});
                android.content.Intent intent = new android.content.Intent();
                intent.setClassName(context, "com.example.ava.services.AiBrowserService");
                intent.setAction("com.example.ava.services.AiBrowserService.SHOW");
                intent.putExtra("url", searchUrl);
                if ("webconsole".equals(currentChannel) && currentChatId != null && !currentChatId.trim().isEmpty()) {
                    intent.putExtra("sid", currentChatId);
                }
                context.startService(intent);
                
                Thread.sleep(5000);
                
                String pageText = "";
                try {
                    Class<?> serviceClass = loadHostClass("com.example.ava.services.AiBrowserService");
                    Object result = serviceClass.getMethod("getCurrentPageText", Context.class).invoke(null, context);
                    pageText = result != null ? String.valueOf(result) : "";
                } catch (Exception e) {
                    pageText = "";
                }
                
                android.content.Intent hideIntent = new android.content.Intent();
                hideIntent.setClassName(context, "com.example.ava.services.AiBrowserService");
                hideIntent.setAction("com.example.ava.services.AiBrowserService.HIDE");
                context.startService(hideIntent);
                
                JSONObject result = new JSONObject();
                result.put("engine", engine);
                result.put("query", query);
                result.put("url", searchUrl);
                result.put("content", pageText.length() > 3000 ? pageText.substring(0, 3000) + "..." : pageText);
                return result.toString(2);
            }
        );

        addTool("web_fetch",
            "Fetch webpage content (text extraction). Uses curl, falls back to Ava internal browser for JS pages. Does NOT open system browsers.",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"URL to fetch\"}},\"required\":[\"url\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String url = input.optString("url", "").trim();
                if (url.isEmpty()) return "Error: url is required";
                
                // Try curl first
                try {
                    String[] cmd = {"curl", "-s", "-L", "-A", "Mozilla/5.0", "--connect-timeout", "10", url};
                    Process p = Runtime.getRuntime().exec(cmd);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    StringBuilder html = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        html.append(line).append("\n");
                        if (html.length() > 50000) break;
                    }
                    p.waitFor();
                    
                    if (html.length() > 100) {
                        String text = html.toString()
                            .replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                            .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                            .replaceAll("<[^>]+>", " ")
                            .replaceAll("&nbsp;", " ")
                            .replaceAll("\\s+", " ")
                            .trim();
                        
                        if (text.length() > 3000) text = text.substring(0, 3000) + "...";
                        
                        JSONObject result = new JSONObject();
                        result.put("url", url);
                        result.put("method", "curl");
                        result.put("content", text);
                        return result.toString(2);
                    }
                } catch (Exception e) {
                    // Fall through
                }
                
                // Fallback: AI browser, isolated from HA/HASS browser state
                invokeStaticVoid("com.example.ava.services.AiBrowserService", "hide", new Class[]{Context.class}, new Object[]{context});
                android.content.Intent intent = new android.content.Intent();
                intent.setClassName(context, "com.example.ava.services.AiBrowserService");
                intent.setAction("com.example.ava.services.AiBrowserService.SHOW");
                intent.putExtra("url", url);
                if ("webconsole".equals(currentChannel) && currentChatId != null && !currentChatId.trim().isEmpty()) {
                    intent.putExtra("sid", currentChatId);
                }
                context.startService(intent);
                
                Thread.sleep(2500);
                
                String pageText = "";
                try {
                    Class<?> serviceClass = loadHostClass("com.example.ava.services.AiBrowserService");
                    Object result = serviceClass.getMethod("getCurrentPageText", Context.class).invoke(null, context);
                    pageText = result != null ? String.valueOf(result) : "";
                } catch (Exception e) {
                    pageText = "";
                }
                
                android.content.Intent hideIntent = new android.content.Intent();
                hideIntent.setClassName(context, "com.example.ava.services.AiBrowserService");
                hideIntent.setAction("com.example.ava.services.AiBrowserService.HIDE");
                context.startService(hideIntent);
                
                JSONObject result = new JSONObject();
                result.put("url", url);
                result.put("method", "browser");
                result.put("content", pageText.length() > 3000 ? pageText.substring(0, 3000) + "..." : pageText);
                return result.toString(2);
            }
        );

        addTool("ava_close_overlays",
            "Close Ava's internal overlays only (internal browser, assistant, quick entity). Does NOT close Chrome/Kiwi/Via or any system apps.",
            "{\"type\":\"object\",\"properties\":{\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "close Ava overlays");
                if (confirmation != null) {
                    return confirmation;
                }
                int closed = 0;
                if (invokeStaticVoid("com.example.ava.services.AiBrowserService", "hide", new Class[]{Context.class}, new Object[]{context})) {
                    closed++;
                }
                if (invokeStaticVoid("com.example.ava.services.WebViewService", "hide", new Class[]{Context.class}, new Object[]{context})) {
                    closed++;
                }
                if (invokeStaticVoid("com.example.ava.services.FloatingWindowService", "hide", new Class[]{Context.class}, new Object[]{context})) {
                    closed++;
                }
                if (invokeStaticVoid("com.example.ava.services.QuickEntityOverlayService", "hide", new Class[]{Context.class}, new Object[]{context})) {
                    closed++;
                }
                return "Closed Ava overlays: " + closed;
            }
        );

        addTool("list_installed_apps",
            "List installed applications with package name and label.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                PackageManager pm = context.getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                StringBuilder sb = new StringBuilder();
                for (android.content.pm.ApplicationInfo app : apps) {
                    String label = String.valueOf(pm.getApplicationLabel(app));
                    sb.append(label).append(" | ").append(app.packageName).append("\n");
                }
                return sb.length() > 0 ? sb.toString().trim() : "No installed apps found";
            }
        );

        addTool("launch_app",
            "Launch another installed app by package name. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"package_name\":{\"type\":\"string\",\"description\":\"Android package name\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"package_name\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "launch another app");
                if (confirmation != null) {
                    return confirmation;
                }
                String packageName = input.optString("package_name", "").trim();
                if (packageName.isEmpty()) {
                    return "Error: package_name is required";
                }
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent == null) {
                    return "Error: App not found or not launchable";
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                return "Launched app: " + packageName;
            }
        );

        addTool("open_app_settings",
            "Open Android settings for an app package. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"package_name\":{\"type\":\"string\",\"description\":\"Android package name\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"package_name\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "open app settings");
                if (confirmation != null) {
                    return confirmation;
                }
                String packageName = input.optString("package_name", "").trim();
                if (packageName.isEmpty()) {
                    return "Error: package_name is required";
                }
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return "Opened app settings: " + packageName;
            }
        );

        addTool("android_input_tap",
            "Perform a screen tap via Android shell. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\",\"description\":\"Screen X coordinate\"},\"y\":{\"type\":\"integer\",\"description\":\"Screen Y coordinate\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"x\",\"y\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "tap the screen");
                if (confirmation != null) {
                    return confirmation;
                }
                int x = input.optInt("x", Integer.MIN_VALUE);
                int y = input.optInt("y", Integer.MIN_VALUE);
                if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
                    return "Error: x and y are required";
                }
                return executeAndroidTap(x, y);
            }
        );

        addTool("android_input_swipe",
            "Perform a screen swipe via Android shell. Requires explicit user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"x1\":{\"type\":\"integer\",\"description\":\"Start X\"},\"y1\":{\"type\":\"integer\",\"description\":\"Start Y\"},\"x2\":{\"type\":\"integer\",\"description\":\"End X\"},\"y2\":{\"type\":\"integer\",\"description\":\"End Y\"},\"duration_ms\":{\"type\":\"integer\",\"description\":\"Optional duration in ms\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED after user approval\"}},\"required\":[\"x1\",\"y1\",\"x2\",\"y2\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "swipe on the screen");
                if (confirmation != null) {
                    return confirmation;
                }
                int x1 = input.optInt("x1", Integer.MIN_VALUE);
                int y1 = input.optInt("y1", Integer.MIN_VALUE);
                int x2 = input.optInt("x2", Integer.MIN_VALUE);
                int y2 = input.optInt("y2", Integer.MIN_VALUE);
                int duration = input.optInt("duration_ms", 300);
                if (x1 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || x2 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE) {
                    return "Error: x1, y1, x2, y2 are required";
                }
                return executeAndroidSwipe(x1, y1, x2, y2, duration);
            }
        );

        // ========== Home Assistant Tools ==========
        // All ha_* tools belong to "homeassistant" skill
        
        addTool("ha_status",
            "Check Home Assistant connection status and get basic info.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) {
                    return "{\"ok\":false,\"error\":\"ha_not_configured\",\"message\":\"Home Assistant not configured. Use ha_config to set HA_URL and HA_TOKEN.\"}";
                }
                try {
                    String result = haApiCall("GET", "/api/", null, config[0], config[1]);
                    JSONObject info = new JSONObject(result);
                    JSONObject resp = new JSONObject();
                    resp.put("ok", true);
                    resp.put("message", info.optString("message", ""));
                    resp.put("url", config[0]);
                    return resp.toString(2);
                } catch (Exception e) {
                    return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
                }
            }
        );

        addTool("ha_config",
            "Configure Home Assistant connection. Set URL and long-lived access token.",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"Home Assistant URL (e.g. http://192.168.1.100:8123)\"},\"token\":{\"type\":\"string\",\"description\":\"Long-lived access token\"}},\"required\":[\"url\",\"token\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String url = input.optString("url", "").trim();
                String token = input.optString("token", "").trim();
                if (url.isEmpty() || token.isEmpty()) {
                    return "Error: url and token are required";
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return "Error: URL must start with http:// or https://";
                }
                url = url.replaceAll("/+$", "");
                try {
                    String result = haApiCall("GET", "/api/", null, url, token);
                    JSONObject info = new JSONObject(result);
                    if (info.has("message")) {
                        saveHaConfig(url, token);
                        return "Home Assistant configured successfully. Connected to: " + url;
                    }
                    return "Error: Invalid response from Home Assistant";
                } catch (Exception e) {
                    return "Error: Failed to connect - " + e.getMessage();
                }
            }
        );

        addTool("ha_states",
            "Get all entity states or filter by domain.",
            "{\"type\":\"object\",\"properties\":{\"domain\":{\"type\":\"string\",\"description\":\"Optional domain filter (light, switch, sensor, etc.)\"}},\"required\":[]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                JSONObject input = new JSONObject(inputJson);
                String domain = input.optString("domain", "").trim();
                try {
                    String result = haApiCall("GET", "/api/states", null, config[0], config[1]);
                    JSONArray states = new JSONArray(result);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject entity = states.getJSONObject(i);
                        String entityId = entity.optString("entity_id", "");
                        if (domain.isEmpty() || entityId.startsWith(domain + ".")) {
                            String state = entity.optString("state", "");
                            String name = entity.optJSONObject("attributes") != null ? 
                                entity.getJSONObject("attributes").optString("friendly_name", entityId) : entityId;
                            sb.append(entityId).append(": ").append(state).append(" (").append(name).append(")\n");
                        }
                    }
                    return sb.length() > 0 ? sb.toString().trim() : "No entities found";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_state",
            "Get state of a specific entity.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID (e.g. light.living_room)\"}},\"required\":[\"entity_id\"]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                JSONObject input = new JSONObject(inputJson);
                String entityId = input.optString("entity_id", "").trim();
                if (entityId.isEmpty()) return "Error: entity_id is required";
                try {
                    String result = haApiCall("GET", "/api/states/" + entityId, null, config[0], config[1]);
                    JSONObject entity = new JSONObject(result);
                    JSONObject resp = new JSONObject();
                    resp.put("entity_id", entityId);
                    resp.put("state", entity.optString("state", ""));
                    resp.put("attributes", entity.optJSONObject("attributes"));
                    resp.put("last_changed", entity.optString("last_changed", ""));
                    return resp.toString(2);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_turn_on",
            "Turn on an entity (light, switch, etc.). Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID\"},\"brightness\":{\"type\":\"integer\",\"description\":\"Optional brightness 0-255 for lights\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "turn on device");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                if (entityId.isEmpty()) return "Error: entity_id is required";
                String domain = entityId.split("\\.")[0];
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    if (input.has("brightness")) {
                        data.put("brightness", input.getInt("brightness"));
                    }
                    haApiCall("POST", "/api/services/" + domain + "/turn_on", data.toString(), config[0], config[1]);
                    return "✓ " + entityId + " turned on";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_turn_off",
            "Turn off an entity (light, switch, etc.). Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "turn off device");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                if (entityId.isEmpty()) return "Error: entity_id is required";
                String domain = entityId.split("\\.")[0];
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    haApiCall("POST", "/api/services/" + domain + "/turn_off", data.toString(), config[0], config[1]);
                    return "✓ " + entityId + " turned off";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_toggle",
            "Toggle an entity on/off. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "toggle device");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                if (entityId.isEmpty()) return "Error: entity_id is required";
                String domain = entityId.split("\\.")[0];
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    haApiCall("POST", "/api/services/" + domain + "/toggle", data.toString(), config[0], config[1]);
                    return "✓ " + entityId + " toggled";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_scene",
            "Activate a scene. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"scene\":{\"type\":\"string\",\"description\":\"Scene name or entity_id\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"scene\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "activate scene");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String scene = input.optString("scene", "").trim();
                if (scene.isEmpty()) return "Error: scene is required";
                if (!scene.startsWith("scene.")) scene = "scene." + scene;
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", scene);
                    haApiCall("POST", "/api/services/scene/turn_on", data.toString(), config[0], config[1]);
                    return "✓ Scene " + scene + " activated";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_script",
            "Run a script. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"script\":{\"type\":\"string\",\"description\":\"Script name or entity_id\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"script\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "run script");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String script = input.optString("script", "").trim();
                if (script.isEmpty()) return "Error: script is required";
                if (!script.startsWith("script.")) script = "script." + script;
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", script);
                    haApiCall("POST", "/api/services/script/turn_on", data.toString(), config[0], config[1]);
                    return "✓ Script " + script + " executed";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_call_service",
            "Call any Home Assistant service. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"domain\":{\"type\":\"string\",\"description\":\"Service domain (light, switch, etc.)\"},\"service\":{\"type\":\"string\",\"description\":\"Service name (turn_on, turn_off, etc.)\"},\"data\":{\"type\":\"string\",\"description\":\"JSON data for the service call\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"domain\",\"service\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "call Home Assistant service");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String domain = input.optString("domain", "").trim();
                String service = input.optString("service", "").trim();
                String dataStr = input.optString("data", "{}").trim();
                if (domain.isEmpty() || service.isEmpty()) return "Error: domain and service are required";
                try {
                    String result = haApiCall("POST", "/api/services/" + domain + "/" + service, dataStr, config[0], config[1]);
                    return "✓ Service " + domain + "." + service + " called\n" + result;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_search",
            "Search entities by name pattern.",
            "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"Search pattern (case-insensitive)\"}},\"required\":[\"pattern\"]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                JSONObject input = new JSONObject(inputJson);
                String pattern = input.optString("pattern", "").trim().toLowerCase();
                if (pattern.isEmpty()) return "Error: pattern is required";
                try {
                    String result = haApiCall("GET", "/api/states", null, config[0], config[1]);
                    JSONArray states = new JSONArray(result);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject entity = states.getJSONObject(i);
                        String entityId = entity.optString("entity_id", "");
                        String name = entity.optJSONObject("attributes") != null ? 
                            entity.getJSONObject("attributes").optString("friendly_name", "") : "";
                        if (entityId.toLowerCase().contains(pattern) || name.toLowerCase().contains(pattern)) {
                            sb.append(entityId).append(": ").append(entity.optString("state", ""));
                            if (!name.isEmpty()) sb.append(" (").append(name).append(")");
                            sb.append("\n");
                        }
                    }
                    return sb.length() > 0 ? sb.toString().trim() : "No matching entities found";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_dashboard",
            "Get a quick status dashboard of Home Assistant.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                try {
                    String result = haApiCall("GET", "/api/states", null, config[0], config[1]);
                    JSONArray states = new JSONArray(result);
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== 🏠 Home Assistant Dashboard ===\n\n");
                    
                    sb.append("👥 Presence:\n");
                    boolean hasPresence = false;
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject e = states.getJSONObject(i);
                        if (e.optString("entity_id", "").startsWith("person.")) {
                            String name = e.optJSONObject("attributes") != null ? 
                                e.getJSONObject("attributes").optString("friendly_name", e.optString("entity_id")) : e.optString("entity_id");
                            sb.append("  ").append(name).append(": ").append(e.optString("state")).append("\n");
                            hasPresence = true;
                        }
                    }
                    if (!hasPresence) sb.append("  (none)\n");
                    
                    sb.append("\n💡 Lights ON:\n");
                    boolean hasLights = false;
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject e = states.getJSONObject(i);
                        if (e.optString("entity_id", "").startsWith("light.") && "on".equals(e.optString("state"))) {
                            String name = e.optJSONObject("attributes") != null ? 
                                e.getJSONObject("attributes").optString("friendly_name", e.optString("entity_id")) : e.optString("entity_id");
                            sb.append("  ").append(name).append("\n");
                            hasLights = true;
                        }
                    }
                    if (!hasLights) sb.append("  (none)\n");
                    
                    sb.append("\n🔌 Switches ON:\n");
                    boolean hasSwitches = false;
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject e = states.getJSONObject(i);
                        if (e.optString("entity_id", "").startsWith("switch.") && "on".equals(e.optString("state"))) {
                            String name = e.optJSONObject("attributes") != null ? 
                                e.getJSONObject("attributes").optString("friendly_name", e.optString("entity_id")) : e.optString("entity_id");
                            sb.append("  ").append(name).append("\n");
                            hasSwitches = true;
                        }
                    }
                    if (!hasSwitches) sb.append("  (none)\n");
                    
                    // Temperature sensors
                    sb.append("\n🌡️ Temperature:\n");
                    boolean hasTemp = false;
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject e = states.getJSONObject(i);
                        if (e.optString("entity_id", "").startsWith("sensor.")) {
                            JSONObject attrs = e.optJSONObject("attributes");
                            if (attrs != null && "temperature".equals(attrs.optString("device_class", ""))) {
                                String name = attrs.optString("friendly_name", e.optString("entity_id"));
                                String unit = attrs.optString("unit_of_measurement", "");
                                sb.append("  ").append(name).append(": ").append(e.optString("state")).append(unit).append("\n");
                                hasTemp = true;
                            }
                        }
                    }
                    if (!hasTemp) sb.append("  (none)\n");
                    
                    // Locks
                    sb.append("\n🔒 Locks:\n");
                    boolean hasLocks = false;
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject e = states.getJSONObject(i);
                        if (e.optString("entity_id", "").startsWith("lock.")) {
                            String name = e.optJSONObject("attributes") != null ? 
                                e.getJSONObject("attributes").optString("friendly_name", e.optString("entity_id")) : e.optString("entity_id");
                            sb.append("  ").append(name).append(": ").append(e.optString("state")).append("\n");
                            hasLocks = true;
                        }
                    }
                    if (!hasLocks) sb.append("  (none)\n");
                    
                    // Open doors/windows
                    sb.append("\n🚪 Open doors/windows:\n");
                    boolean hasOpen = false;
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject e = states.getJSONObject(i);
                        if (e.optString("entity_id", "").startsWith("binary_sensor.") && "on".equals(e.optString("state"))) {
                            JSONObject attrs = e.optJSONObject("attributes");
                            if (attrs != null) {
                                String dc = attrs.optString("device_class", "");
                                if ("door".equals(dc) || "window".equals(dc)) {
                                    String name = attrs.optString("friendly_name", e.optString("entity_id"));
                                    sb.append("  ").append(name).append("\n");
                                    hasOpen = true;
                                }
                            }
                        }
                    }
                    if (!hasOpen) sb.append("  (all closed)\n");
                    
                    return sb.toString();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_automation",
            "Trigger an automation. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"automation\":{\"type\":\"string\",\"description\":\"Automation name or entity_id\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"automation\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "trigger automation");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String auto = input.optString("automation", "").trim();
                if (auto.isEmpty()) return "Error: automation is required";
                if (!auto.startsWith("automation.")) auto = "automation." + auto;
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", auto);
                    haApiCall("POST", "/api/services/automation/trigger", data.toString(), config[0], config[1]);
                    return "✓ Automation " + auto + " triggered";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_climate",
            "Set climate/thermostat temperature. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"Climate entity ID\"},\"temperature\":{\"type\":\"number\",\"description\":\"Target temperature\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\",\"temperature\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "set temperature");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                double temp = input.optDouble("temperature", Double.NaN);
                if (entityId.isEmpty()) return "Error: entity_id is required";
                if (Double.isNaN(temp)) return "Error: temperature is required";
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    data.put("temperature", temp);
                    haApiCall("POST", "/api/services/climate/set_temperature", data.toString(), config[0], config[1]);
                    return "✓ " + entityId + " set to " + temp + "°";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_notify",
            "Send a notification via Home Assistant. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\",\"description\":\"Notification message\"},\"title\":{\"type\":\"string\",\"description\":\"Optional title\"},\"service\":{\"type\":\"string\",\"description\":\"Notification service name (default: notify)\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"message\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "send notification");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String message = input.optString("message", "").trim();
                String title = input.optString("title", "Home Assistant").trim();
                String service = input.optString("service", "notify").trim();
                if (message.isEmpty()) return "Error: message is required";
                try {
                    JSONObject data = new JSONObject();
                    data.put("message", message);
                    data.put("title", title);
                    haApiCall("POST", "/api/services/notify/" + service, data.toString(), config[0], config[1]);
                    return "✓ Notification sent: " + message;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_history",
            "Get entity history for the past 24 hours.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID to get history for\"}},\"required\":[\"entity_id\"]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                JSONObject input = new JSONObject(inputJson);
                String entityId = input.optString("entity_id", "").trim();
                if (entityId.isEmpty()) return "Error: entity_id is required";
                try {
                    String result = haApiCall("GET", "/api/history/period?filter_entity_id=" + entityId, null, config[0], config[1]);
                    JSONArray history = new JSONArray(result);
                    if (history.length() == 0) return "No history found for " + entityId;
                    JSONArray entityHistory = history.getJSONArray(0);
                    StringBuilder sb = new StringBuilder();
                    sb.append("History for ").append(entityId).append(":\n");
                    int count = Math.min(entityHistory.length(), 20);
                    for (int i = entityHistory.length() - count; i < entityHistory.length(); i++) {
                        JSONObject entry = entityHistory.getJSONObject(i);
                        String state = entry.optString("state", "");
                        String lastChanged = entry.optString("last_changed", "");
                        if (lastChanged.length() > 19) lastChanged = lastChanged.substring(11, 19);
                        sb.append("  ").append(lastChanged).append(": ").append(state).append("\n");
                    }
                    return sb.toString().trim();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_naming",
            "Manage entity name mappings. Map user-friendly names to Home Assistant entity IDs for natural language control.",
            "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"description\":\"Action: get, set, delete, list\"},\"name\":{\"type\":\"string\",\"description\":\"User-friendly name (e.g. 'living room light')\"},\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID (e.g. 'light.living_room') - required for set\"}},\"required\":[\"action\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String action = input.optString("action", "").trim().toLowerCase();
                String name = input.optString("name", "").trim().toLowerCase();
                String entityId = input.optString("entity_id", "").trim();
                
                JSONObject mappings = getHaNamingMappings();
                
                switch (action) {
                    case "list":
                        if (mappings.length() == 0) return "No name mappings configured. Use ha_naming with action=set to add mappings.";
                        StringBuilder sb = new StringBuilder("Entity Name Mappings:\n");
                        java.util.Iterator<String> keys = mappings.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            sb.append("  \"").append(key).append("\" → ").append(mappings.optString(key)).append("\n");
                        }
                        return sb.toString().trim();
                    case "get":
                        if (name.isEmpty()) return "Error: name is required for get action";
                        String mapped = mappings.optString(name, "");
                        return mapped.isEmpty() ? "No mapping found for: " + name : name + " → " + mapped;
                    case "set":
                        if (name.isEmpty() || entityId.isEmpty()) return "Error: name and entity_id are required for set action";
                        mappings.put(name, entityId);
                        saveHaNamingMappings(mappings);
                        return "Mapping saved: \"" + name + "\" → " + entityId;
                    case "delete":
                        if (name.isEmpty()) return "Error: name is required for delete action";
                        if (!mappings.has(name)) return "No mapping found for: " + name;
                        mappings.remove(name);
                        saveHaNamingMappings(mappings);
                        return "Mapping deleted: " + name;
                    default:
                        return "Error: action must be one of: list, get, set, delete";
                }
            }
        );

        addTool("ha_resolve_name",
            "Resolve a user-friendly name to entity ID using configured mappings.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"User-friendly name to resolve\"}},\"required\":[\"name\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String name = input.optString("name", "").trim().toLowerCase();
                if (name.isEmpty()) return "Error: name is required";
                
                JSONObject mappings = getHaNamingMappings();
                String entityId = mappings.optString(name, "");
                
                if (!entityId.isEmpty()) {
                    return entityId;
                }
                
                // Try fuzzy match
                java.util.Iterator<String> keys = mappings.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.contains(name) || name.contains(key)) {
                        return mappings.optString(key);
                    }
                }
                
                return "No mapping found. Use ha_search to find the entity, then ha_naming to save the mapping.";
            }
        );

        addTool("ha_reload",
            "Reload Home Assistant configuration. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\",\"description\":\"What to reload: core, automation, script, scene, all (default: all)\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "reload Home Assistant configuration");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String target = input.optString("target", "all").trim().toLowerCase();
                try {
                    StringBuilder sb = new StringBuilder();
                    if ("all".equals(target) || "core".equals(target)) {
                        haApiCall("POST", "/api/services/homeassistant/reload_core_config", "{}", config[0], config[1]);
                        sb.append("✓ Core config reloaded\n");
                    }
                    if ("all".equals(target) || "automation".equals(target)) {
                        haApiCall("POST", "/api/services/automation/reload", "{}", config[0], config[1]);
                        sb.append("✓ Automations reloaded\n");
                    }
                    if ("all".equals(target) || "script".equals(target)) {
                        haApiCall("POST", "/api/services/script/reload", "{}", config[0], config[1]);
                        sb.append("✓ Scripts reloaded\n");
                    }
                    if ("all".equals(target) || "scene".equals(target)) {
                        haApiCall("POST", "/api/services/scene/reload", "{}", config[0], config[1]);
                        sb.append("✓ Scenes reloaded\n");
                    }
                    return sb.toString().trim();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_check_config",
            "Validate Home Assistant configuration.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                try {
                    String result = haApiCall("POST", "/api/config/core/check_config", "{}", config[0], config[1]);
                    JSONObject resp = new JSONObject(result);
                    String checkResult = resp.optString("result", "unknown");
                    if ("valid".equals(checkResult)) {
                        return "✓ Configuration is valid";
                    } else {
                        String errors = resp.optString("errors", "");
                        return "❌ Configuration invalid: " + errors;
                    }
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_todo_add",
            "Add an item to a TODO list. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"TODO list entity ID (e.g. todo.shopping_list)\"},\"item\":{\"type\":\"string\",\"description\":\"Item text to add\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\",\"item\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "add TODO item");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                String item = input.optString("item", "").trim();
                if (entityId.isEmpty() || item.isEmpty()) return "Error: entity_id and item are required";
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    data.put("item", item);
                    haApiCall("POST", "/api/services/todo/add_item", data.toString(), config[0], config[1]);
                    return "✓ Added to " + entityId + ": " + item;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_todo_update",
            "Update a TODO item status. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"TODO list entity ID\"},\"item\":{\"type\":\"string\",\"description\":\"Item text to update\"},\"status\":{\"type\":\"string\",\"description\":\"New status: needs_action or completed\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\",\"item\",\"status\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "update TODO item");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                String item = input.optString("item", "").trim();
                String status = input.optString("status", "").trim();
                if (entityId.isEmpty() || item.isEmpty() || status.isEmpty()) return "Error: entity_id, item, and status are required";
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    data.put("item", item);
                    data.put("status", status);
                    haApiCall("POST", "/api/services/todo/update_item", data.toString(), config[0], config[1]);
                    return "✓ Updated " + item + " to " + status;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_todo_remove",
            "Remove an item from a TODO list. Requires user confirmation.",
            "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"string\",\"description\":\"TODO list entity ID\"},\"item\":{\"type\":\"string\",\"description\":\"Item text to remove\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"}},\"required\":[\"entity_id\",\"item\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String confirmation = requireUserConfirmation(input, "remove TODO item");
                if (confirmation != null) return confirmation;
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String entityId = input.optString("entity_id", "").trim();
                String item = input.optString("item", "").trim();
                if (entityId.isEmpty() || item.isEmpty()) return "Error: entity_id and item are required";
                try {
                    JSONObject data = new JSONObject();
                    data.put("entity_id", entityId);
                    data.put("item", item);
                    haApiCall("POST", "/api/services/todo/remove_item", data.toString(), config[0], config[1]);
                    return "✓ Removed from " + entityId + ": " + item;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_conversation",
            "Send natural language command to Home Assistant Conversation API.",
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"Natural language command\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED for action commands\"}},\"required\":[\"text\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                String text = input.optString("text", "").trim();
                if (text.isEmpty()) return "Error: text is required";
                
                // Check if it's an action command (needs confirmation)
                String textLower = text.toLowerCase();
                boolean isAction = textLower.contains("turn") || textLower.contains("open") || textLower.contains("close") || 
                                   textLower.contains("lock") || textLower.contains("unlock") || textLower.contains("set");
                if (isAction) {
                    String confirmation = requireUserConfirmation(input, "execute conversation command");
                    if (confirmation != null) return confirmation;
                }
                
                try {
                    JSONObject data = new JSONObject();
                    data.put("text", text);
                    String result = haApiCall("POST", "/api/conversation/process", data.toString(), config[0], config[1]);
                    JSONObject resp = new JSONObject(result);
                    JSONObject response = resp.optJSONObject("response");
                    if (response != null) {
                        String speech = response.optJSONObject("speech") != null ? 
                            response.getJSONObject("speech").optJSONObject("plain") != null ?
                            response.getJSONObject("speech").getJSONObject("plain").optString("speech", "") : "" : "";
                        return speech.isEmpty() ? "Command processed" : speech;
                    }
                    return "Command processed";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_areas",
            "List all areas configured in Home Assistant.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                try {
                    JSONObject template = new JSONObject();
                    template.put("template", "{% for area in areas() %}{{ area }}\n{% endfor %}");
                    String result = haApiCall("POST", "/api/template", template.toString(), config[0], config[1]);
                    return result.trim().isEmpty() ? "No areas configured" : "Areas:\n" + result.trim();
                } catch (Exception e) {
                    return "Error: " + e.getMessage() + " (Template API may not be available)";
                }
            }
        );

        addTool("ha_inventory",
            "Generate a full entity inventory grouped by domain.",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
            inputJson -> {
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                try {
                    String result = haApiCall("GET", "/api/states", null, config[0], config[1]);
                    JSONArray states = new JSONArray(result);
                    
                    // Group by domain
                    java.util.Map<String, java.util.List<String>> grouped = new java.util.TreeMap<>();
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject entity = states.getJSONObject(i);
                        String entityId = entity.optString("entity_id", "");
                        String state = entity.optString("state", "");
                        String[] parts = entityId.split("\\.", 2);
                        if (parts.length == 2) {
                            String domain = parts[0];
                            grouped.computeIfAbsent(domain, k -> new java.util.ArrayList<>())
                                   .add(entityId + ": " + state);
                        }
                    }
                    
                    StringBuilder sb = new StringBuilder();
                    sb.append("# Home Assistant Inventory\n\n");
                    for (java.util.Map.Entry<String, java.util.List<String>> entry : grouped.entrySet()) {
                        sb.append("## ").append(entry.getKey().toUpperCase()).append(" (").append(entry.getValue().size()).append(")\n");
                        for (String item : entry.getValue()) {
                            sb.append("  ").append(item).append("\n");
                        }
                        sb.append("\n");
                    }
                    return sb.toString().trim();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );

        addTool("ha_safe_action",
            "Execute a safe action with preview and verification. For risky domains (lock, alarm, cover) requires double confirmation.",
            "{\"type\":\"object\",\"properties\":{\"domain\":{\"type\":\"string\",\"description\":\"Service domain\"},\"service\":{\"type\":\"string\",\"description\":\"Service name\"},\"entity_id\":{\"type\":\"string\",\"description\":\"Entity ID\"},\"data\":{\"type\":\"string\",\"description\":\"Optional JSON data\"},\"confirm\":{\"type\":\"string\",\"description\":\"Must be USER_CONFIRMED\"},\"confirm_risky\":{\"type\":\"string\",\"description\":\"For risky domains, must also be RISKY_CONFIRMED\"}},\"required\":[\"domain\",\"service\",\"entity_id\"]}",
            inputJson -> {
                JSONObject input = new JSONObject(inputJson);
                String[] config = getHaConfig();
                if (config == null) return "{\"ok\":false,\"error\":\"ha_not_configured\"}";
                
                String domain = input.optString("domain", "").trim();
                String service = input.optString("service", "").trim();
                String entityId = input.optString("entity_id", "").trim();
                String dataStr = input.optString("data", "{}").trim();
                
                if (domain.isEmpty() || service.isEmpty() || entityId.isEmpty()) {
                    return "Error: domain, service, and entity_id are required";
                }
                
                // Check if risky domain
                boolean isRisky = "lock".equals(domain) || "alarm_control_panel".equals(domain) || "cover".equals(domain);
                
                // Get current state for preview
                String currentState = "unknown";
                String friendlyName = entityId;
                try {
                    String stateResult = haApiCall("GET", "/api/states/" + entityId, null, config[0], config[1]);
                    JSONObject stateJson = new JSONObject(stateResult);
                    currentState = stateJson.optString("state", "unknown");
                    JSONObject attrs = stateJson.optJSONObject("attributes");
                    if (attrs != null) {
                        friendlyName = attrs.optString("friendly_name", entityId);
                    }
                } catch (Exception e) {
                    // Continue with unknown state
                }
                
                // Build preview
                StringBuilder preview = new StringBuilder();
                preview.append("Action preview:\n");
                preview.append("- Entity: ").append(entityId).append(" (").append(friendlyName).append(")\n");
                preview.append("- Current state: ").append(currentState).append("\n");
                preview.append("- Service: ").append(domain).append("/").append(service).append("\n");
                preview.append("- Data: ").append(dataStr).append("\n");
                
                if (isRisky) {
                    preview.append("⚠️ This is a RISKY domain (").append(domain).append("). Requires double confirmation.\n");
                    String riskyConfirm = input.optString("confirm_risky", "").trim();
                    if (!"RISKY_CONFIRMED".equals(riskyConfirm)) {
                        return preview.toString() + "\nConfirmation required: This action affects a security-sensitive device. Call again with confirm=USER_CONFIRMED AND confirm_risky=RISKY_CONFIRMED after user explicitly approves.";
                    }
                }
                
                String confirmation = requireUserConfirmation(input, "execute " + domain + "/" + service);
                if (confirmation != null) {
                    return preview.toString() + "\n" + confirmation;
                }
                
                // Execute
                try {
                    JSONObject data = new JSONObject(dataStr);
                    data.put("entity_id", entityId);
                    haApiCall("POST", "/api/services/" + domain + "/" + service, data.toString(), config[0], config[1]);
                    
                    // Verify new state
                    String newState = "unknown";
                    try {
                        String stateResult = haApiCall("GET", "/api/states/" + entityId, null, config[0], config[1]);
                        JSONObject stateJson = new JSONObject(stateResult);
                        newState = stateJson.optString("state", "unknown");
                    } catch (Exception e) {
                        // Continue
                    }
                    
                    return "✓ Service " + domain + "/" + service + " executed\n- Previous state: " + currentState + "\n- New state: " + newState;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        );
        
        // Map all ha_* tools to homeassistant skill
        String[] haTools = {"ha_status", "ha_config", "ha_states", "ha_state", "ha_turn_on", "ha_turn_off", 
            "ha_toggle", "ha_scene", "ha_script", "ha_call_service", "ha_search", "ha_dashboard", 
            "ha_automation", "ha_climate", "ha_notify", "ha_history", "ha_naming", "ha_resolve_name",
            "ha_reload", "ha_check_config", "ha_todo_add", "ha_todo_update", "ha_todo_remove", 
            "ha_conversation", "ha_areas", "ha_inventory", "ha_safe_action"};
        for (String toolName : haTools) {
            toolSkillMap.put(toolName, "homeassistant");
        }
        
        // Map android_system_bridge tools
        String[] systemTools = {"get_device_info", "android_shell_exec"};
        for (String toolName : systemTools) {
            toolSkillMap.put(toolName, "android_system_bridge");
        }
        
        // Map android_accessibility_bridge tools
        String[] accessibilityTools = {"accessibility_status", "ui_tree_dump", "ui_find_text", 
            "ui_click_text", "ui_click", "ui_set_text", "ui_scroll", "ui_screenshot", 
            "ui_back", "ui_home", "ui_recents", "ui_swipe"};
        for (String toolName : accessibilityTools) {
            toolSkillMap.put(toolName, "android_accessibility_bridge");
        }
        
        // Map android_browser_bridge tools
        String[] browserTools = {"open_browser_url", "ava_browser_hide", "ava_browser_refresh", "ava_browser_read_text", "ava_close_overlays"};
        for (String toolName : browserTools) {
            toolSkillMap.put(toolName, "android_browser_bridge");
        }
        
        // Map multi_search_engine tools
        toolSkillMap.put("web_search", "multi_search_engine");
        toolSkillMap.put("web_fetch", "multi_search_engine");
    }
    
    // ========== Home Assistant Helpers ==========
    
    private String[] getHaConfig() {
        try {
            String content = memoryStore.readFileByPath("ha_config.json");
            if (content != null && !content.trim().isEmpty()) {
                JSONObject config = new JSONObject(content);
                String url = config.optString("url", "");
                String token = config.optString("token", "");
                if (!url.isEmpty() && !token.isEmpty()) {
                    return new String[]{url, token};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read HA config: " + e.getMessage());
        }
        return null;
    }
    
    private void saveHaConfig(String url, String token) {
        try {
            JSONObject config = new JSONObject();
            config.put("url", url);
            config.put("token", token);
            memoryStore.writeFileByPath("ha_config.json", config.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save HA config: " + e.getMessage());
        }
    }
    
    private JSONObject getHaNamingMappings() {
        try {
            String content = memoryStore.readFileByPath("ha_naming.json");
            if (content != null && !content.trim().isEmpty()) {
                return new JSONObject(content);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read HA naming mappings: " + e.getMessage());
        }
        return new JSONObject();
    }
    
    private void saveHaNamingMappings(JSONObject mappings) {
        try {
            memoryStore.writeFileByPath("ha_naming.json", mappings.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save HA naming mappings: " + e.getMessage());
        }
    }
    
    private String haApiCall(String method, String path, String data, String baseUrl, String token) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        
        if ("POST".equals(method) && data != null) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(data.getBytes("UTF-8"));
            os.close();
        }
        
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();
        
        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + sb.toString());
        }
        return sb.toString();
    }
    
    private String normalizeSkillName(String name) {
        String trimmed = name != null ? name.trim() : "";
        if (trimmed.isEmpty()) {
            return "";
        }
        String fileName = trimmed.endsWith(".md") ? trimmed : trimmed + ".md";
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return "";
        }
        return fileName;
    }

    private String requireUserConfirmation(JSONObject input, String actionLabel) {
        String confirm = input.optString("confirm", "").trim();
        if (CONFIRM_TOKEN.equals(confirm)) {
            return null;
        }
        return "Confirmation required: ask the user for approval, then call this tool again with confirm=" + CONFIRM_TOKEN + " to " + actionLabel + ".";
    }

    private String takeAccessibilityScreenshotWithRetry() throws Exception {
        String result = invokeAccessibilityString("takeScreenshot", new Class[]{Context.class}, new Object[]{context});
        try {
            JSONObject json = new JSONObject(result);
            if (json.optBoolean("ok", false)) {
                return result;
            }
            if ("take_screenshot_interval_time_short".equals(json.optString("error", ""))) {
                long retryAfterMs = json.optLong("retryAfterMs", 0L);
                if (retryAfterMs > 0L) {
                    Thread.sleep(Math.min(retryAfterMs + 120L, 2200L));
                    return invokeAccessibilityString("takeScreenshot", new Class[]{Context.class}, new Object[]{context});
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private String takeSmartScreenshot() throws Exception {
        String privilegedResult = takePrivilegedScreenshot();
        if (privilegedResult != null) {
            return privilegedResult;
        }
        return takeAccessibilityScreenshotWithRetry();
    }

    private String takePrivilegedScreenshot() {
        try {
            File outputDir = context.getExternalFilesDir("screenshots");
            if (outputDir == null) {
                return null;
            }
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                return null;
            }
            File outputFile = new File(outputDir, "screenshot_" + System.currentTimeMillis() + ".png");
            String escapedDir = outputDir.getAbsolutePath().replace("'", "'\\''");
            String escapedFile = outputFile.getAbsolutePath().replace("'", "'\\''");
            String command = "mkdir -p '" + escapedDir + "' && screencap -p '" + escapedFile + "'";

            String rootResult = tryRoot(command);
            if (isPrivilegedScreenshotSuccess(rootResult, outputFile)) {
                return buildScreenshotJson(outputFile, "root");
            }

            String shizukuResult = tryShizuku(command);
            if (isPrivilegedScreenshotSuccess(shizukuResult, outputFile)) {
                return buildScreenshotJson(outputFile, "shizuku");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isPrivilegedScreenshotSuccess(String result, File outputFile) {
        return result != null
            && !result.startsWith("Error:")
            && outputFile.exists()
            && outputFile.length() > 0L;
    }

    private String buildScreenshotJson(File outputFile, String source) throws Exception {
        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("path", outputFile.getAbsolutePath());
        json.put("source", source);
        json.put("bytes", outputFile.length());
        return json.toString();
    }

    private String inferSkillFileName(String url) {
        try {
            String path = new URL(url).getPath();
            String tail = path.substring(path.lastIndexOf('/') + 1).trim();
            if (tail.isEmpty()) {
                return "";
            }
            return tail.endsWith(".md") ? tail : tail + ".md";
        } catch (Exception e) {
            return "";
        }
    }

    private String extractSkillTitle(String content) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line != null ? line.trim() : "";
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
            }
        }
        return "";
    }

    private String downloadText(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "text/plain, text/markdown, text/x-markdown, */*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " when downloading skill");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean isAllowedShellCommand(String command) {
        String normalized = command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
        if (normalized.contains("pm uninstall")
            || normalized.contains("clear ")
            || normalized.contains(" rm ")
            || normalized.startsWith("rm ")
            || normalized.contains("cmd package")
            || normalized.contains("format")
            || normalized.contains("mkfs")
            || normalized.contains("wipe")
            || normalized.contains("delete")
            || normalized.contains("truncate")) {
            return false;
        }
        for (String prefix : SAFE_SHELL_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String executeAndroidShell(String command) throws Exception {
        String shizukuResult = tryShizuku(command);
        if (shizukuResult != null) {
            return shizukuResult;
        }
        String rootResult = tryRoot(command);
        if (rootResult != null) {
            return rootResult;
        }
        return "Error: Neither Shizuku nor root is available for shell execution. Accessibility-based UI control may still be available through accessibility_status, ui_tree_dump, ui_find_text, ui_click_text, ui_click, ui_set_text, and ui_scroll.";
    }

    private String executeAndroidTap(int x, int y) throws Exception {
        String shellResult = executeAndroidShell("input tap " + x + " " + y);
        if (!shellResult.startsWith("Error: Neither Shizuku nor root is available")) {
            return shellResult;
        }
        Object result = invokeAccessibility("tap", new Class[]{int.class, int.class}, new Object[]{x, y});
        return Boolean.TRUE.equals(result)
            ? "Accessibility tap completed at " + x + "," + y
            : shellResult;
    }

    private String executeAndroidSwipe(int x1, int y1, int x2, int y2, int duration) throws Exception {
        String shellResult = executeAndroidShell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration);
        if (!shellResult.startsWith("Error: Neither Shizuku nor root is available")) {
            return shellResult;
        }
        Object result = invokeAccessibility("swipe", new Class[]{int.class, int.class, int.class, int.class, int.class}, new Object[]{x1, y1, x2, y2, duration});
        return Boolean.TRUE.equals(result)
            ? "Accessibility swipe completed from " + x1 + "," + y1 + " to " + x2 + "," + y2
            : shellResult;
    }

    private Object invokeAccessibility(String methodName, Class<?>[] parameterTypes, Object[] args) throws Exception {
        Class<?> bridgeClass = loadHostClass("com.example.ava.services.AccessibilityBridge");
        Object bridgeInstance = getKotlinObjectInstance(bridgeClass);
        return bridgeClass.getMethod(methodName, parameterTypes).invoke(bridgeInstance, args);
    }

    private boolean invokeStaticVoid(String className, String methodName, Class<?>[] parameterTypes, Object[] args) {
        try {
            Class<?> target = loadHostClass(className);
            Object targetInstance = getKotlinObjectInstance(target);
            target.getMethod(methodName, parameterTypes).invoke(targetInstance, args);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to invoke " + className + "." + methodName, e);
            return false;
        }
    }

    private String invokeAccessibilityString(String methodName, Class<?>[] parameterTypes, Object[] args) throws Exception {
        Object value = invokeAccessibility(methodName, parameterTypes, args);
        return value != null ? String.valueOf(value) : "";
    }

    private String findUiText(String query, boolean contains) throws Exception {
        JSONObject dump = new JSONObject(invokeAccessibilityString("dumpUiTreeJson", new Class[]{}, new Object[]{}));
        JSONArray matches = new JSONArray();
        JSONArray nodes = dump.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) {
                    continue;
                }
                if (matchesText(node.optString("text", ""), query, contains)
                    || matchesText(node.optString("contentDescription", ""), query, contains)) {
                    matches.put(node);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("query", query);
        result.put("matchCount", matches.length());
        result.put("matches", matches);
        if (matches.length() == 0 && dump.has("warning")) {
            result.put("warning", dump.optString("warning"));
        }
        return result.toString();
    }

    private JSONObject findBestUiNode(String query, boolean contains) throws Exception {
        JSONObject dump = new JSONObject(invokeAccessibilityString("dumpUiTreeJson", new Class[]{}, new Object[]{}));
        JSONArray nodes = dump.optJSONArray("nodes");
        if (nodes == null) {
            return null;
        }
        JSONObject best = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String text = node.optString("text", "");
            String description = node.optString("contentDescription", "");
            if (matchesText(text, query, contains) || matchesText(description, query, contains)) {
                if (best == null) {
                    best = node;
                    continue;
                }
                boolean bestClickable = best.optBoolean("clickable", false);
                boolean currentClickable = node.optBoolean("clickable", false);
                if (currentClickable && !bestClickable) {
                    best = node;
                    continue;
                }
                int bestDepth = best.optInt("depth", Integer.MAX_VALUE);
                int currentDepth = node.optInt("depth", Integer.MAX_VALUE);
                if (currentClickable == bestClickable && currentDepth < bestDepth) {
                    best = node;
                }
            }
        }
        return best;
    }

    private boolean matchesText(String source, String query, boolean contains) {
        String normalizedSource = source != null ? source.trim().toLowerCase(Locale.US) : "";
        String normalizedQuery = query != null ? query.trim().toLowerCase(Locale.US) : "";
        if (normalizedSource.isEmpty() || normalizedQuery.isEmpty()) {
            return false;
        }
        return contains ? normalizedSource.contains(normalizedQuery) : normalizedSource.equals(normalizedQuery);
    }

    private String tryShizuku(String command) {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object shizukuUtilsInstance = getKotlinObjectInstance(shizukuUtils);
            Boolean granted = (Boolean) shizukuUtils.getMethod("isShizukuPermissionGranted").invoke(shizukuUtilsInstance);
            if (granted == null || !granted) {
                return null;
            }
            Object pair = shizukuUtils.getMethod("executeCommand", String.class).invoke(shizukuUtilsInstance, command);
            if (pair == null) {
                return "Error: Shizuku returned null";
            }
            Integer code = (Integer) pair.getClass().getMethod("getFirst").invoke(pair);
            String output = (String) pair.getClass().getMethod("getSecond").invoke(pair);
            return "Shizuku exit=" + code + (output != null && !output.isEmpty() ? "\n" + output : "");
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return "Error: Shizuku execution failed: " + e.getMessage();
        }
    }

    private String tryRoot(String command) {
        try {
            if (!isRootAvailable()) {
                return null;
            }
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            int exitCode = process.waitFor();
            StringBuilder sb = new StringBuilder();
            sb.append("root exit=").append(exitCode);
            if (!stdout.trim().isEmpty()) {
                sb.append("\n").append(stdout.trim());
            }
            if (!stderr.trim().isEmpty()) {
                sb.append("\n").append(stderr.trim());
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: root execution failed: " + e.getMessage();
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private Object getKotlinObjectInstance(Class<?> clazz) throws Exception {
        try {
            return clazz.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private Class<?> loadHostClass(String className) throws ClassNotFoundException {
        if (context != null) {
            ClassLoader hostLoader = context.getClassLoader();
            if (hostLoader != null) {
                try {
                    return Class.forName(className, false, hostLoader);
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        ClassLoader fallbackLoader = ToolRegistry.class.getClassLoader();
        if (fallbackLoader != null) {
            try {
                return Class.forName(className, false, fallbackLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(className);
    }

    private String buildDeviceInfoJson() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject device = new JSONObject();
        device.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
        device.put("brand", Build.BRAND != null ? Build.BRAND : "");
        device.put("model", Build.MODEL != null ? Build.MODEL : "");
        device.put("device", Build.DEVICE != null ? Build.DEVICE : "");
        device.put("product", Build.PRODUCT != null ? Build.PRODUCT : "");
        device.put("hardware", Build.HARDWARE != null ? Build.HARDWARE : "");
        device.put("board", Build.BOARD != null ? Build.BOARD : "");
        device.put("supported_abis", new JSONArray(Build.SUPPORTED_ABIS != null ? Build.SUPPORTED_ABIS : new String[0]));
        root.put("device", device);

        JSONObject android = new JSONObject();
        android.put("release", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
        android.put("sdk_int", Build.VERSION.SDK_INT);
        android.put("security_patch", Build.VERSION.SECURITY_PATCH != null ? Build.VERSION.SECURITY_PATCH : "");
        root.put("android", android);

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        JSONObject memory = new JSONObject();
        memory.put("total_bytes", memoryInfo.totalMem);
        memory.put("available_bytes", memoryInfo.availMem);
        memory.put("used_bytes", Math.max(0L, memoryInfo.totalMem - memoryInfo.availMem));
        root.put("memory", memory);

        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long totalStorage = statFs.getBlockCountLong() * statFs.getBlockSizeLong();
        long availableStorage = statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong();
        JSONObject storage = new JSONObject();
        storage.put("total_bytes", totalStorage);
        storage.put("available_bytes", availableStorage);
        storage.put("used_bytes", Math.max(0L, totalStorage - availableStorage));
        root.put("storage", storage);

        root.put("battery", buildBatteryInfoJson());
        root.put("power", buildPowerInfoJson());

        JSONObject capabilities = new JSONObject();
        capabilities.put("shizuku_running", isShizukuRunning());
        capabilities.put("shizuku_granted", isShizukuGranted());
        capabilities.put("root_available", isRootAvailable());
        capabilities.put("accessibility", getAccessibilityStatusJson());
        capabilities.put("shell_execution_available", capabilities.optBoolean("shizuku_granted", false) || capabilities.optBoolean("root_available", false));
        capabilities.put("ui_control_available", capabilities.optJSONObject("accessibility") != null
            && capabilities.optJSONObject("accessibility").optBoolean("enabled", false)
            && capabilities.optJSONObject("accessibility").optBoolean("connected", false));
        root.put("capabilities", capabilities);

        JSONObject app = new JSONObject();
        app.put("package_name", context.getPackageName());
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            app.put("version_name", packageInfo.versionName != null ? packageInfo.versionName : "");
            app.put("version_code", packageInfo.versionCode);

            JSONArray permissions = new JSONArray();
            if (packageInfo.requestedPermissions != null) {
                for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
                    String permission = packageInfo.requestedPermissions[i];
                    JSONObject permissionInfo = new JSONObject();
                    permissionInfo.put("name", permission != null ? permission : "");
                    boolean granted = packageInfo.requestedPermissionsFlags != null
                        && packageInfo.requestedPermissionsFlags.length > i
                        && (packageInfo.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    permissionInfo.put("granted", granted);
                    permissions.put(permissionInfo);
                }
            }
            app.put("permissions", permissions);
        } catch (Exception e) {
            app.put("version_name", "");
            app.put("version_code", 0);
            app.put("permissions_error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        root.put("app", app);

        if (skillLoader != null) {
            root.put("skills_summary", skillLoader.buildSummary());
        }

        return root.toString(2);
    }

    private JSONObject buildBatteryInfoJson() {
        JSONObject battery = new JSONObject();
        try {
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent == null) {
                battery.put("available", false);
                return battery;
            }
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            int temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

            battery.put("available", true);
            battery.put("level", level);
            battery.put("scale", scale);
            battery.put("percent", level >= 0 && scale > 0 ? (level * 100) / scale : -1);
            battery.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
            battery.put("status", batteryStatusToString(status));
            battery.put("plugged", batteryPluggedToString(plugged));
            battery.put("health", batteryHealthToString(health));
            battery.put("temperature_c", temperature >= 0 ? (temperature / 10.0) : JSONObject.NULL);
        } catch (Exception e) {
            try {
                battery.put("available", false);
                battery.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return battery;
    }

    private JSONObject buildPowerInfoJson() {
        JSONObject power = new JSONObject();
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            power.put("available", powerManager != null);
            if (powerManager != null) {
                power.put("interactive", powerManager.isInteractive());
                power.put("power_save_mode", powerManager.isPowerSaveMode());
                power.put("device_idle_mode", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager.isDeviceIdleMode());
                power.put("ignoring_battery_optimizations", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && powerManager.isIgnoringBatteryOptimizations(context.getPackageName()));
            }
        } catch (Exception e) {
            try {
                power.put("available", false);
                power.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return power;
    }

    private String batteryStatusToString(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "not_charging";
            case BatteryManager.BATTERY_STATUS_UNKNOWN:
            default:
                return "unknown";
        }
    }

    private String batteryPluggedToString(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "usb";
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "ac";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "wireless";
            default:
                return "none";
        }
    }

    private String batteryHealthToString(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "good";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "cold";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "dead";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "overheat";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "over_voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "failure";
            case BatteryManager.BATTERY_HEALTH_UNKNOWN:
            default:
                return "unknown";
        }
    }

    private boolean isShizukuRunning() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object shizukuUtilsInstance = getKotlinObjectInstance(shizukuUtils);
            Boolean running = (Boolean) shizukuUtils.getMethod("isShizukuRunning").invoke(shizukuUtilsInstance);
            return running != null && running;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isShizukuGranted() {
        try {
            Class<?> shizukuUtils = loadHostClass("com.example.ava.utils.ShizukuUtils");
            Object shizukuUtilsInstance = getKotlinObjectInstance(shizukuUtils);
            Boolean granted = (Boolean) shizukuUtils.getMethod("isShizukuPermissionGranted").invoke(shizukuUtilsInstance);
            return granted != null && granted;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRootAvailable() {
        try {
            Class<?> rootUtils = loadHostClass("com.example.ava.utils.RootUtils");
            Object rootUtilsInstance = getKotlinObjectInstance(rootUtils);
            Boolean available = (Boolean) rootUtils.getMethod("isRootAvailable").invoke(rootUtilsInstance);
            if (available != null && available) {
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Primary root availability check failed", e);
        }
        return probeRootDirectly();
    }

    private JSONObject getAccessibilityStatusJson() {
        try {
            return new JSONObject(invokeAccessibilityString("getStatus", new Class[]{Context.class}, new Object[]{context}));
        } catch (Exception e) {
            try {
                Class<?> bridgeClass = loadHostClass("com.example.ava.services.AccessibilityBridge");
                Object bridgeInstance = getKotlinObjectInstance(bridgeClass);
                Boolean enabled = (Boolean) bridgeClass.getMethod("isEnabled", Context.class).invoke(bridgeInstance, context);
                JSONObject fallback = new JSONObject();
                fallback.put("enabled", enabled != null && enabled);
                fallback.put("connected", false);
                fallback.put("mode", (enabled != null && enabled) ? "accessibility_unbound" : "disabled");
                fallback.put("supportsScreenshots", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                fallback.put("supportsGestures", Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
                fallback.put("fallback", true);
                if (e.getMessage() != null) {
                    fallback.put("bridge_error", e.getMessage());
                }
                return fallback;
            } catch (Exception ignored) {
                try {
                    JSONObject error = new JSONObject();
                    error.put("enabled", false);
                    error.put("connected", false);
                    error.put("mode", "error");
                    error.put("supportsScreenshots", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                    error.put("supportsGestures", Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
                    error.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    return error;
                } catch (Exception finalIgnored) {
                    return new JSONObject();
                }
            }
        }
    }

    private boolean probeRootDirectly() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String stdout = readStream(process.getInputStream()).trim();
            String stderr = readStream(process.getErrorStream()).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            if (!stdout.isEmpty()) {
                String lower = stdout.toLowerCase(Locale.US);
                if (lower.contains("uid=0") || lower.contains("gid=0")) {
                    return true;
                }
            }
            if (!stderr.isEmpty()) {
                Log.w(TAG, "Direct root probe stderr: " + stderr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Direct root probe failed", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }
    
    private void buildToolsJson() {
        toolsJson = new JSONArray();
        
        try {
            for (ToolDef def : toolDefs.values()) {
                JSONObject tool = new JSONObject();
                tool.put("name", def.name);
                tool.put("description", def.description);
                tool.put("input_schema", new JSONObject(def.inputSchema));
                toolsJson.put(tool);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build tools JSON", e);
        }
    }
}
