package com.ava.mods.mimiclaw.skills;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

public class SkillLoader {
    private final File skillsDir;

    public SkillLoader(Context context) {
        this.skillsDir = new File(context.getFilesDir(), "skills");
        if (!skillsDir.exists()) {
            skillsDir.mkdirs();
        }
        ensureBuiltinSkills();
    }

    public String buildSummary() {
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null || files.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (File file : files) {
            String name = file.getName();
            String title = name.replace(".md", "");
            String description = extractDescription(file);
            Map<String, String> meta = parseSkillMeta(name);
            String version = meta.getOrDefault("version", "");
            String tags = meta.getOrDefault("tags", "");
            
            sb.append("- **").append(title).append("**");
            if (!version.isEmpty()) {
                sb.append(" v").append(version);
            }
            if (!tags.isEmpty()) {
                sb.append(" ").append(tags);
            }
            sb.append(": ");
            sb.append(description.isEmpty() ? "No description" : description);
            sb.append("\n");
        }
        return sb.toString();
    }

    public String readSkill(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }
        String safeName = name.endsWith(".md") ? name : name + ".md";
        File file = new File(skillsDir, safeName);
        if (!file.exists() || !file.isFile()) {
            return "";
        }
        return readFileContent(file);
    }

    private String readFileContent(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "";
        }
        return sb.toString().trim();
    }

    private String extractDescription(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String firstLine = reader.readLine();
            if (firstLine == null) {
                return "";
            }
            // Skip YAML frontmatter if present
            if (firstLine.trim().equals("---")) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equals("---")) {
                        break;
                    }
                }
                // Read the actual first line after frontmatter
                firstLine = reader.readLine();
                if (firstLine == null) {
                    return "";
                }
            }
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("##")) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(trimmed);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Parse YAML frontmatter from skill file.
     * Returns map with keys: version, origin, tags, description, etc.
     */
    public Map<String, String> parseSkillMeta(String name) {
        Map<String, String> meta = new HashMap<>();
        if (name == null || name.trim().isEmpty()) {
            return meta;
        }
        String safeName = name.endsWith(".md") ? name : name + ".md";
        File file = new File(skillsDir, safeName);
        if (!file.exists() || !file.isFile()) {
            return meta;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.trim().equals("---")) {
                return meta;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals("---")) {
                    break;
                }
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    // Remove quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    meta.put(key, value);
                }
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
        return meta;
    }

    /**
     * Get skill content without YAML frontmatter.
     */
    public String readSkillContent(String name) {
        String full = readSkill(name);
        if (full.isEmpty()) {
            return full;
        }
        if (!full.startsWith("---")) {
            return full;
        }
        int endIdx = full.indexOf("\n---", 3);
        if (endIdx < 0) {
            return full;
        }
        return full.substring(endIdx + 4).trim();
    }

    // ========== Skill Statistics (OpenSpace concept) ==========
    
    private File getStatsFile() {
        return new File(skillsDir, ".skill_stats.json");
    }

    private JSONObject loadStats() {
        File statsFile = getStatsFile();
        if (!statsFile.exists()) {
            return new JSONObject();
        }
        try {
            String content = readFileContent(statsFile);
            return new JSONObject(content);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveStats(JSONObject stats) {
        try (FileWriter writer = new FileWriter(getStatsFile())) {
            writer.write(stats.toString(2));
        } catch (Exception ignored) {
        }
    }

    /**
     * Record skill usage. Call when a skill is applied.
     * @param skillName The skill name (without .md)
     * @param success Whether the skill execution succeeded
     */
    public void recordSkillUsage(String skillName, boolean success) {
        if (skillName == null || skillName.isEmpty()) return;
        try {
            String key = skillName.replace(".md", "");
            JSONObject stats = loadStats();
            JSONObject skillStats = stats.optJSONObject(key);
            if (skillStats == null) {
                skillStats = new JSONObject();
                skillStats.put("applied", 0);
                skillStats.put("success", 0);
                skillStats.put("failed", 0);
            }
            skillStats.put("applied", skillStats.optInt("applied", 0) + 1);
            if (success) {
                skillStats.put("success", skillStats.optInt("success", 0) + 1);
            } else {
                skillStats.put("failed", skillStats.optInt("failed", 0) + 1);
            }
            skillStats.put("lastUsed", System.currentTimeMillis());
            stats.put(key, skillStats);
            saveStats(stats);
        } catch (Exception ignored) {
        }
    }

    /**
     * Get skill statistics.
     * @param skillName The skill name (without .md)
     * @return Map with keys: applied, success, failed, successRate, lastUsed
     */
    public Map<String, Object> getSkillStats(String skillName) {
        Map<String, Object> result = new HashMap<>();
        if (skillName == null || skillName.isEmpty()) return result;
        String key = skillName.replace(".md", "");
        JSONObject stats = loadStats();
        JSONObject skillStats = stats.optJSONObject(key);
        if (skillStats == null) {
            result.put("applied", 0);
            result.put("success", 0);
            result.put("failed", 0);
            result.put("successRate", 0.0);
            return result;
        }
        int applied = skillStats.optInt("applied", 0);
        int success = skillStats.optInt("success", 0);
        int failed = skillStats.optInt("failed", 0);
        double rate = applied > 0 ? (double) success / applied * 100 : 0.0;
        result.put("applied", applied);
        result.put("success", success);
        result.put("failed", failed);
        result.put("successRate", Math.round(rate * 10) / 10.0);
        result.put("lastUsed", skillStats.optLong("lastUsed", 0));
        return result;
    }

    /**
     * Get all skill statistics summary.
     */
    public String buildStatsSummary() {
        JSONObject stats = loadStats();
        if (stats.length() == 0) {
            return "No skill usage recorded yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill Statistics\n");
        java.util.Iterator<String> keys = stats.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject s = stats.optJSONObject(key);
            if (s == null) continue;
            int applied = s.optInt("applied", 0);
            int success = s.optInt("success", 0);
            double rate = applied > 0 ? (double) success / applied * 100 : 0.0;
            sb.append("- **").append(key).append("**: ");
            sb.append(applied).append(" uses, ");
            sb.append(String.format("%.1f", rate)).append("% success\n");
        }
        return sb.toString();
    }

    // ========== Skill Evolution (OpenSpace concept) ==========
    // Evolution modes: FIX (repair), DERIVED (enhance), CAPTURED (new from execution)
    
    /**
     * Create a derived skill from parent.
     * @param parentName Parent skill name
     * @param newName New skill name
     * @param newContent New skill content (without frontmatter)
     * @param reason Reason for derivation
     */
    public boolean deriveSkill(String parentName, String newName, String newContent, String reason) {
        if (parentName == null || newName == null || newContent == null) return false;
        Map<String, String> parentMeta = parseSkillMeta(parentName);
        String parentVersion = parentMeta.getOrDefault("version", "1.0");
        
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("version: \"1.0\"\n");
        sb.append("origin: derived\n");
        sb.append("parent: \"").append(parentName.replace(".md", "")).append("\"\n");
        sb.append("parent_version: \"").append(parentVersion).append("\"\n");
        sb.append("derived_reason: \"").append(reason.replace("\"", "'")).append("\"\n");
        sb.append("derived_at: ").append(System.currentTimeMillis()).append("\n");
        sb.append("---\n");
        sb.append(newContent);
        
        return writeSkill(newName, sb.toString());
    }

    /**
     * Fix/update an existing skill (increment version).
     * @param skillName Skill name
     * @param newContent New content (without frontmatter)
     * @param fixReason Reason for fix
     */
    public boolean fixSkill(String skillName, String newContent, String fixReason) {
        if (skillName == null || newContent == null) return false;
        Map<String, String> meta = parseSkillMeta(skillName);
        String oldVersion = meta.getOrDefault("version", "1.0");
        String origin = meta.getOrDefault("origin", "user");
        String tags = meta.getOrDefault("tags", "");
        
        // Increment version
        String newVersion;
        try {
            double v = Double.parseDouble(oldVersion);
            newVersion = String.format("%.1f", v + 0.1);
        } catch (Exception e) {
            newVersion = oldVersion + ".1";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("version: \"").append(newVersion).append("\"\n");
        sb.append("origin: ").append(origin).append("\n");
        if (!tags.isEmpty()) {
            sb.append("tags: ").append(tags).append("\n");
        }
        sb.append("fixed_from: \"").append(oldVersion).append("\"\n");
        sb.append("fix_reason: \"").append(fixReason.replace("\"", "'")).append("\"\n");
        sb.append("fixed_at: ").append(System.currentTimeMillis()).append("\n");
        sb.append("---\n");
        sb.append(newContent);
        
        return writeSkill(skillName, sb.toString());
    }

    /**
     * Capture a new skill from successful execution pattern.
     * @param skillName New skill name
     * @param content Skill content (without frontmatter)
     * @param tags Tags for the skill
     * @param captureSource Source of capture (e.g., "task_execution")
     */
    public boolean captureSkill(String skillName, String content, String tags, String captureSource) {
        if (skillName == null || content == null) return false;
        
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("version: \"1.0\"\n");
        sb.append("origin: captured\n");
        if (tags != null && !tags.isEmpty()) {
            sb.append("tags: [").append(tags).append("]\n");
        }
        sb.append("capture_source: \"").append(captureSource != null ? captureSource : "unknown").append("\"\n");
        sb.append("captured_at: ").append(System.currentTimeMillis()).append("\n");
        sb.append("---\n");
        sb.append(content);
        
        return writeSkill(skillName, sb.toString());
    }

    /**
     * Write skill file.
     */
    private boolean writeSkill(String name, String content) {
        if (name == null || content == null) return false;
        String safeName = name.endsWith(".md") ? name : name + ".md";
        File file = new File(skillsDir, safeName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get skill evolution history from metadata.
     */
    public Map<String, String> getEvolutionInfo(String skillName) {
        Map<String, String> info = new HashMap<>();
        Map<String, String> meta = parseSkillMeta(skillName);
        info.put("origin", meta.getOrDefault("origin", "unknown"));
        info.put("version", meta.getOrDefault("version", "1.0"));
        if (meta.containsKey("parent")) {
            info.put("parent", meta.get("parent"));
            info.put("parent_version", meta.getOrDefault("parent_version", ""));
            info.put("derived_reason", meta.getOrDefault("derived_reason", ""));
        }
        if (meta.containsKey("fixed_from")) {
            info.put("fixed_from", meta.get("fixed_from"));
            info.put("fix_reason", meta.getOrDefault("fix_reason", ""));
        }
        if (meta.containsKey("capture_source")) {
            info.put("capture_source", meta.get("capture_source"));
        }
        return info;
    }

    // ========== Skill Search & Ranking (OpenSpace concept) ==========
    
    /**
     * Search skills by keyword (simple BM25-like scoring).
     * @param query Search query
     * @return List of skill names sorted by relevance
     */
    public java.util.List<String> searchSkills(String query) {
        java.util.List<String> results = new java.util.ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return results;
        }
        
        String[] queryTerms = query.toLowerCase().split("\\s+");
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) return results;
        
        java.util.List<Map.Entry<String, Double>> scored = new java.util.ArrayList<>();
        
        for (File file : files) {
            String name = file.getName().replace(".md", "");
            String content = readFileContent(file).toLowerCase();
            Map<String, String> meta = parseSkillMeta(file.getName());
            String tags = meta.getOrDefault("tags", "").toLowerCase();
            
            double score = 0;
            for (String term : queryTerms) {
                // Name match (highest weight)
                if (name.toLowerCase().contains(term)) {
                    score += 10;
                }
                // Tag match (high weight)
                if (tags.contains(term)) {
                    score += 5;
                }
                // Content match (lower weight, count occurrences)
                int count = countOccurrences(content, term);
                score += Math.min(count, 5); // Cap at 5 to avoid spam
            }
            
            // Boost by success rate
            Map<String, Object> stats = getSkillStats(name);
            double successRate = (Double) stats.getOrDefault("successRate", 0.0);
            score *= (1 + successRate / 200); // Up to 1.5x boost
            
            if (score > 0) {
                scored.add(new java.util.AbstractMap.SimpleEntry<>(name, score));
            }
        }
        
        // Sort by score descending
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        
        for (Map.Entry<String, Double> entry : scored) {
            results.add(entry.getKey());
        }
        return results;
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /**
     * Get skills by tag.
     */
    public java.util.List<String> getSkillsByTag(String tag) {
        java.util.List<String> results = new java.util.ArrayList<>();
        if (tag == null || tag.trim().isEmpty()) return results;
        
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) return results;
        
        String searchTag = tag.toLowerCase().trim();
        for (File file : files) {
            Map<String, String> meta = parseSkillMeta(file.getName());
            String tags = meta.getOrDefault("tags", "").toLowerCase();
            if (tags.contains(searchTag)) {
                results.add(file.getName().replace(".md", ""));
            }
        }
        return results;
    }

    /**
     * Get skills by origin type.
     */
    public java.util.List<String> getSkillsByOrigin(String origin) {
        java.util.List<String> results = new java.util.ArrayList<>();
        if (origin == null) return results;
        
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) return results;
        
        for (File file : files) {
            Map<String, String> meta = parseSkillMeta(file.getName());
            if (origin.equals(meta.get("origin"))) {
                results.add(file.getName().replace(".md", ""));
            }
        }
        return results;
    }

    /**
     * Get top performing skills by success rate.
     */
    public java.util.List<String> getTopSkills(int limit) {
        java.util.List<String> results = new java.util.ArrayList<>();
        JSONObject stats = loadStats();
        
        java.util.List<Map.Entry<String, Double>> ranked = new java.util.ArrayList<>();
        java.util.Iterator<String> iter = stats.keys();
        while (iter.hasNext()) {
            String key = iter.next();
            JSONObject s = stats.optJSONObject(key);
            if (s == null) continue;
            int applied = s.optInt("applied", 0);
            if (applied < 3) continue; // Need at least 3 uses
            int success = s.optInt("success", 0);
            double rate = (double) success / applied;
            ranked.add(new java.util.AbstractMap.SimpleEntry<>(key, rate));
        }
        
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        
        int count = 0;
        for (Map.Entry<String, Double> entry : ranked) {
            if (count >= limit) break;
            results.add(entry.getKey());
            count++;
        }
        return results;
    }

    /**
     * Build evolution lineage for a skill (version DAG).
     */
    public String buildLineage(String skillName) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> info = getEvolutionInfo(skillName);
        
        sb.append("## ").append(skillName).append(" Lineage\n");
        sb.append("- **Version**: ").append(info.getOrDefault("version", "1.0")).append("\n");
        sb.append("- **Origin**: ").append(info.getOrDefault("origin", "unknown")).append("\n");
        
        if (info.containsKey("parent")) {
            sb.append("- **Derived from**: ").append(info.get("parent"));
            sb.append(" v").append(info.getOrDefault("parent_version", "?")).append("\n");
            sb.append("- **Reason**: ").append(info.getOrDefault("derived_reason", "")).append("\n");
        }
        if (info.containsKey("fixed_from")) {
            sb.append("- **Fixed from**: v").append(info.get("fixed_from")).append("\n");
            sb.append("- **Fix reason**: ").append(info.getOrDefault("fix_reason", "")).append("\n");
        }
        if (info.containsKey("capture_source")) {
            sb.append("- **Captured from**: ").append(info.get("capture_source")).append("\n");
        }
        
        // Add stats
        Map<String, Object> stats = getSkillStats(skillName);
        int applied = (Integer) stats.getOrDefault("applied", 0);
        if (applied > 0) {
            sb.append("- **Usage**: ").append(applied).append(" times, ");
            sb.append(stats.get("successRate")).append("% success\n");
        }
        
        return sb.toString();
    }

    private void ensureBuiltinSkills() {
        writeBuiltinSkill(
            "android_system_bridge.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [android, shell, system]\n" +
            "---\n" +
            "# Android System Bridge\n" +
            "\n" +
            "Use this skill when you need to inspect Android device state or run safe shell commands through Ava.\n" +
            "\n" +
            "Available tools:\n" +
            "- `get_device_info`: returns model, Android version, memory, storage, ABI, app version, and granted permissions.\n" +
            "- `android_shell_exec`: executes a restricted whitelist of Android inspection commands through Shizuku or root.\n" +
            "\n" +
            "Guidelines:\n" +
            "- Prefer `get_device_info` first.\n" +
            "- Use `android_shell_exec` only when device info is insufficient.\n" +
            "- Treat `android_shell_exec` as a safe subset of `adb shell`, not as host-side adb.\n" +
            "- Safe diagnostics like `logcat`, read-only `dumpsys`, `getprop`, `wm`, and package listing are allowed when the shell backend is available.\n" +
            "- Shell execution requires Shizuku or root, but lack of shell access does not mean UI control is unavailable.\n" +
            "- Check `get_device_info` and `accessibility_status` before claiming the device cannot be controlled.\n" +
            "- Do not attempt destructive shell commands.\n" +
            "\n" +
            "How to use `android_shell_exec`:\n" +
            "- First decide what you need: system property, process list, file content, display info, power info, logs, or package info.\n" +
            "- Send one small command at a time.\n" +
            "- Prefer read-only commands before control commands.\n" +
            "- After reading output, summarize the important lines instead of dumping everything back to the user.\n" +
            "- If shell is unavailable, fall back to `get_device_info` and accessibility tools.\n" +
            "\n" +
            "Command patterns:\n" +
            "- System properties: `getprop`, `getprop ro.product.model`, `getprop ro.build.version.release`\n" +
            "- Processes: `ps`\n" +
            "- Basic files: `cat /proc/meminfo`, `cat /proc/cpuinfo`\n" +
            "- Display: `wm size`, `wm density`, `dumpsys display`\n" +
            "- Power and battery: `dumpsys power`\n" +
            "- Packages: `pm list packages`, `pm path <package>`\n" +
            "- Network: `ip addr`, `ip route`\n" +
            "- Memory: `dumpsys meminfo`, `df`\n" +
            "- Logs: `logcat -d`, `logcat -d -s <Tag>`\n" +
            "- Safe UI key events: `input keyevent 3`, `input keyevent 4`, `input keyevent 26`\n" +
            "- Safe activity launch: `am start -a android.intent.action.VIEW -d <url>`\n" +
            "\n" +
            "## Busybox Usage\n" +
            "Android lacks many standard Linux tools. Use busybox if available:\n" +
            "- First find busybox: `which busybox` or check `/system/bin/busybox`, `/system/xbin/busybox`, `/data/adb/magisk/busybox`\n" +
            "- Use busybox for: xz, unxz, gzip, gunzip, bzip2, tar, wget, awk, sed, grep, find, etc.\n" +
            "- Syntax: `busybox xz -d file.xz` or `busybox tar -xJf file.tar.xz`\n" +
            "- If busybox not found, check if Magisk installed: `ls /data/adb/magisk/busybox`\n" +
            "\n" +
            "## Tool Selection Rules\n" +
            "1. **Use android_shell_exec for:**\n" +
            "   - System inspection (getprop, ps, dumpsys, pm list)\n" +
            "   - Basic file operations (cat, ls, df)\n" +
            "   - Commands that are whitelisted and simple\n" +
            "   - When you need Android system-level access\n" +
            "\n" +
            "2. **Use terminal_exec for:**\n" +
            "   - Complex scripts with pipes/redirects\n" +
            "   - Python execution (python, pip)\n" +
            "   - Non-whitelisted commands\n" +
            "   - When android_shell_exec fails or says 'command not allowed'\n" +
            "   - Installing packages, running installers\n" +
            "   - Anything requiring full shell capabilities\n" +
            "\n" +
            "3. **Web search tool selection:**\n" +
            "   - Default: tavily (if API key configured)\n" +
            "   - Fallback: baidu/bing/google only (others may fail)\n" +
            "   - Avoid minor engines like wolfram, jisilu, etc.\n" +
            "\n" +
            "When to use what:\n" +
            "- Want model, Android version, battery, memory, storage, permissions: use `get_device_info`.\n" +
            "- Want detailed live system state: use `android_shell_exec`.\n" +
            "- Want to inspect visible app UI: use accessibility tools, not shell.\n" +
            "- Want to click or enter text in apps: use `ui_tree_dump`, `ui_find_text`, `ui_click_text`, `ui_set_text`.\n" +
            "\n" +
            "Output handling:\n" +
            "- If output is long, extract the few lines that matter.\n" +
            "- If the command failed, report the exact error and try a safer alternative.\n" +
            "- Never say shell is unavailable unless `get_device_info.capabilities` confirms both Shizuku and root are unavailable.\n"
        );

        writeBuiltinSkill(
            "android_accessibility_bridge.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [android, accessibility, ui]\n" +
            "---\n" +
            "# Android Accessibility Bridge\n" +
            "\n" +
            "Use this skill when you need to inspect or operate Android UI without root.\n" +
            "\n" +
            "Available tools:\n" +
            "- `accessibility_status`: check whether Ava accessibility mode is enabled and connected.\n" +
            "- `accessibility_open_settings`: open Android accessibility settings for the user.\n" +
            "- `ui_tree_dump`: get structured UI tree, window metadata, and fallback screenshot information.\n" +
            "- `ui_find_text`: search the current UI tree by visible text or content description.\n" +
            "- `ui_click_text`: find and click a UI element by text.\n" +
            "- `ui_click`: click by node index first, then coordinate fallback.\n" +
            "- `ui_set_text`: set text by node index first, then coordinate fallback.\n" +
            "- `ui_scroll`: scroll by node index first, then coordinate fallback.\n" +
            "- `ui_screenshot`: capture a screenshot and prefer root, then Shizuku, then accessibility fallback.\n" +
            "- `ava_close_overlays`: close Ava overlays when they block the target UI.\n" +
            "\n" +
            "Required chain:\n" +
            "- 1. Call `accessibility_status` first.\n" +
            "- 1a. Even without Shizuku or root, accessibility can still provide UI inspection and control.\n" +
            "- 2. If disabled, ask the user to enable it and call `accessibility_open_settings` only after approval.\n" +
            "- 3. Call `ui_tree_dump` before any click or text operation.\n" +
            "- 4. If you know the target text, use `ui_find_text` first and prefer `ui_click_text` for text-driven navigation.\n" +
            "- 5. Use node indexes when available because they survive layout shifts better than coordinates.\n" +
            "- 6. If the tree is empty or sparse, inspect `windows`, `warning`, and `fallbackScreenshot` from `ui_tree_dump`.\n" +
            "- 7. If a screenshot path is available, treat it as the visual fallback source for understanding the current app state before clicking.\n" +
            "- 8. If needed, call `ui_screenshot` again to capture a fresh state after navigation or scrolling.\n" +
            "- 8a. Screenshot tool results are forwarded to the model as actual image input when a valid path is returned, not just as plain text.\n" +
            "- 9. If an Ava overlay blocks the target app, use `ava_close_overlays` after user approval.\n" +
            "- 10. Only use coordinate fallback when index mode failed or the UI tree is incomplete.\n" +
            "- 11. For software control, prefer text-based targets like buttons, tabs, menu labels, and dialog actions instead of guessing raw coordinates.\n" +
            "- 12. If one accessibility click or input attempt fails, do not conclude the device is uncontrollable; inspect the tree again, try text-based matching, then use coordinate fallback.\n" +
            "- 13. Only say a capability is unavailable after checking the relevant status tool and exhausting the intended fallback path.\n" +
            "- 14. Do not say you successfully viewed or captured a screenshot unless `ui_screenshot` or `fallbackScreenshot` returned `ok=true` with a valid path.\n" +
            "- 14a. If a valid screenshot path was returned, you may analyze the image content directly instead of claiming that the chat can only handle text.\n" +
            "- 15. Screenshot first tries root, then Shizuku, and only then accessibility.\n" +
            "- 16. If screenshot returns `service_not_connected`, `take_screenshot_timeout`, or another error, report that exact status and continue with tree-based control when possible.\n"
        );

        writeBuiltinSkill(
            "android_browser_bridge.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [android, browser, web]\n" +
            "---\n" +
            "# Android Browser Bridge\n" +
            "\n" +
            "**CRITICAL**: This skill controls ONLY Ava's internal floating browser overlay.\n" +
            "Do NOT use `open_browser_url` when user says 'open website' or 'visit URL'.\n" +
            "\n" +
            "## DEFAULT BEHAVIOR:\n" +
            "- User says 'open/visit website' → Use `android_shell_exec` with `am start`\n" +
            "- User says 'close browser' after using Ava overlay → Use `ava_browser_hide`\n" +
            "\n" +
            "## Decision Guide:\n" +
            "\n" +
            "| Action | Correct Method |\n" +
            "|--------|----------------|\n" +
            "| Open website (DEFAULT) | `android_shell_exec`: `am start -a android.intent.action.VIEW -d <url>` |\n" +
            "| Close system browser | `android_shell_exec`: `am force-stop <package>` |\n" +
            "| Get webpage content | `web_fetch` or `web_search` |\n" +
            "| Open in Ava overlay (ONLY if explicitly requested) | `open_browser_url` |\n" +
            "| Close Ava browser | `ava_browser_hide` |\n" +
            "\n" +
            "## Ava Internal Browser Tools:\n" +
            "- `open_browser_url`: Open URL in Ava's floating overlay (use ONLY when user explicitly asks for Ava overlay)\n" +
            "- `ava_browser_hide`: Hide/close Ava's browser overlay\n" +
            "- `ava_browser_refresh`: Refresh Ava's overlay page\n" +
            "- `ava_browser_read_text`: Extract text from Ava's overlay\n" +
            "- `ava_close_overlays`: Close ALL Ava overlays (may return 0 if already hidden)\n"
        );

        writeBuiltinSkill(
            "homeassistant.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [smarthome, homeassistant, iot]\n" +
            "---\n" +
            "# Home Assistant\n" +
            "\n" +
            "Control smart home devices via Home Assistant REST API.\n" +
            "\n" +
            "## Setup\n" +
            "Use `ha_config` to set your Home Assistant URL and long-lived access token.\n" +
            "Token can be created in HA → Profile → Long-Lived Access Tokens.\n" +
            "\n" +
            "## Available Tools (26 total)\n" +
            "\n" +
            "### Read-only (Tier 0)\n" +
            "- `ha_status`: check connection status\n" +
            "- `ha_states`: list all entities or filter by domain\n" +
            "- `ha_state`: get state of specific entity\n" +
            "- `ha_search`: search entities by name\n" +
            "- `ha_dashboard`: quick status overview\n" +
            "- `ha_history`: get entity history\n" +
            "- `ha_areas`: list all areas\n" +
            "- `ha_inventory`: full entity inventory grouped by domain\n" +
            "- `ha_check_config`: validate HA configuration\n" +
            "\n" +
            "### Configuration\n" +
            "- `ha_config`: configure HA URL and token\n" +
            "- `ha_naming`: manage entity name mappings\n" +
            "- `ha_resolve_name`: resolve friendly name to entity_id\n" +
            "\n" +
            "### Control (Tier 1 - requires confirmation)\n" +
            "- `ha_turn_on`: turn on entity (supports brightness)\n" +
            "- `ha_turn_off`: turn off entity\n" +
            "- `ha_toggle`: toggle entity\n" +
            "- `ha_scene`: activate scene\n" +
            "- `ha_script`: run script\n" +
            "- `ha_automation`: trigger automation\n" +
            "- `ha_climate`: set thermostat temperature\n" +
            "- `ha_notify`: send notification\n" +
            "- `ha_call_service`: call any HA service\n" +
            "- `ha_reload`: reload configuration (core/automation/script/scene)\n" +
            "- `ha_conversation`: natural language command via Conversation API\n" +
            "\n" +
            "### TODO Management (Tier 1)\n" +
            "- `ha_todo_add`: add item to TODO list\n" +
            "- `ha_todo_update`: update item status\n" +
            "- `ha_todo_remove`: remove item\n" +
            "\n" +
            "### Safe Actions (Tier 2 - double confirmation)\n" +
            "- `ha_safe_action`: execute with preview and verification\n" +
            "  - Risky domains (lock, alarm_control_panel, cover) require confirm_risky=RISKY_CONFIRMED\n" +
            "\n" +
            "## Entity Domains\n" +
            "- `light.*`, `switch.*`, `sensor.*`, `binary_sensor.*`\n" +
            "- `climate.*`, `cover.*` (RISKY), `lock.*` (RISKY)\n" +
            "- `alarm_control_panel.*` (RISKY), `media_player.*`\n" +
            "- `scene.*`, `script.*`, `automation.*`, `person.*`, `todo.*`\n" +
            "\n" +
            "## Safety Policy\n" +
            "- Read-only by default\n" +
            "- All writes require confirmation\n" +
            "- Risky domains require double confirmation\n" +
            "- Preview before execution, verify after\n" +
            "\n" +
            "## Guidelines\n" +
            "- Call `ha_status` first to verify connection\n" +
            "- Use `ha_naming` to save friendly name mappings\n" +
            "- Use `ha_dashboard` or `ha_inventory` for overview\n" +
            "- Use `ha_conversation` for natural language commands\n" +
            "- Use `ha_safe_action` for security-sensitive devices\n"
        );

        writeBuiltinSkill(
            "android_skill_installer.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [skill, management, install]\n" +
            "---\n" +
            "# Android Skill Installer\n" +
            "\n" +
            "Use this skill when you need to install, inspect, update, or delete local SKILL markdown files for OpenClaw(Mini).\n" +
            "\n" +
            "Available tools:\n" +
            "- `list_skills`: list installed skills.\n" +
            "- `read_skill`: read a skill markdown file.\n" +
            "- `describe_skill`: inspect an installed skill.\n" +
            "- `install_skill_from_text`: create or overwrite a skill from markdown text.\n" +
            "- `install_skill_from_url`: download markdown from a URL into the local skills directory.\n" +
            "- `delete_skill`: remove an installed skill.\n" +
            "\n" +
            "Guidelines:\n" +
            "- Keep skill names simple and end with `.md`.\n" +
            "- Install only markdown content.\n" +
            "- SkillHub install links can be used directly with `install_skill_from_url`.\n" +
            "- After installing, use `describe_skill` or `read_skill` to verify the final content.\n" +
            "- Before deleting, inspect the skill and ask for confirmation.\n"
        );

        writeBuiltinSkill(
            "heartbeat_cron.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [cron, heartbeat, automation]\n" +
            "---\n" +
            "# Built-in Heartbeat\n" +
            "\n" +
            "OpenClaw(Mini) includes a built-in isolated heartbeat job.\n" +
            "\n" +
            "Behavior:\n" +
            "- Runs on a fixed schedule in an isolated heartbeat session.\n" +
            "- Reads `HEARTBEAT.md`.\n" +
            "- If `HEARTBEAT.md` is empty or only contains headings/blank checklist items, the run is skipped.\n" +
            "- If there is nothing actionable, return exactly `HEARTBEAT_OK`.\n" +
            "- Only non-`HEARTBEAT_OK` results are pushed outward as proactive notifications.\n" +
            "\n" +
            "## Silent Mode\n" +
            "If HEARTBEAT.md contains polling/monitoring tasks (like checking prices, status, etc.):\n" +
            "- Execute silently in background, no verbose output.\n" +
            "- Only notify user when something IMPORTANT happens (threshold reached, status changed, etc.).\n" +
            "- If nothing notable, just return `HEARTBEAT_OK` - don't explain what you checked.\n" +
            "- User doesn't want to see routine check logs, only actionable alerts.\n" +
            "\n" +
            "Guidelines:\n" +
            "- Keep notifications concise and actionable.\n" +
            "- Don't waste tokens explaining your process.\n" +
            "- Background tasks should be invisible unless they find something.\n"
        );

        writeBuiltinSkill(
            "qqbot_media.md",
            "---\n" +
            "version: \"1.0\"\n" +
            "origin: builtin\n" +
            "tags: [qq, media, messaging]\n" +
            "---\n" +
            "# QQBot Media Output\n" +
            "\n" +
            "Use this skill when the current conversation channel is QQ and you need to send images or voice.\n" +
            "\n" +
            "Supported output tags:\n" +
            "- `<qqmedia>/sdcard/Pictures/cat.png</qqmedia>`\n" +
            "- `<qqmedia>/sdcard/Recordings/reply.silk</qqmedia>`\n" +
            "- `<qqimg>https://example.com/cat.png</qqimg>`\n" +
            "- `<qqimg>/sdcard/Pictures/cat.png</qqimg>`\n" +
            "- `<qqvoice>/sdcard/Recordings/reply.silk</qqvoice>`\n" +
            "- `<qqvoice>data:audio/silk;base64,...</qqvoice>`\n" +
            "- `<qqfile>/sdcard/Download/report.pdf</qqfile>`\n" +
            "- `<qqvideo>/sdcard/Movies/demo.mp4</qqvideo>`\n" +
            "\n" +
            "Rules:\n" +
            "- QQ image, voice, video, and file sending are supported through tags.\n" +
            "- `<qqmedia>` is the preferred unified tag. The system routes by file extension or data URL type.\n" +
            "- User-sent QQ attachments are automatically downloaded to local storage and exposed back to the agent as structured attachment lines plus local paths in the message text.\n" +
            "- For voice attachments, use QQ event metadata such as `asr_refer_text` when present.\n" +
            "- If no transcript is present, treat the voice message as a media file path only and do not pretend it was transcribed.\n" +
            "- When through QQ channel, use this skill so media tags can be parsed correctly.\n" +
            "- Keep normal text outside the tag. Example: `Here is the image <qqimg>https://example.com/a.png</qqimg>`.\n" +
            "- All rich media tags must be correctly closed in the format `<qqXXX>content</qqXXX>`.\n" +
            "- Unclosed or malformed tags will not be parsed correctly.\n" +
            "- Use `<qqimg>` for images, `<qqvoice>` for voice, `<qqfile>` for files, and `<qqvideo>` for videos. Use `<qqmedia>` when you want auto-detection.\n" +
            "- Prefer one media tag per reply unless the user explicitly wants multiple attachments.\n" +
            "- Do not claim media is unsupported when the QQ channel is active.\n" +
            "- Do not say you sent media unless the channel send actually succeeded.\n" +
            "- If voice generation is not available, say that TTS generation is unavailable, not that QQ voice sending is unavailable.\n" +
            "- QQ can receive images and voice attachments. If a local downloaded path is present in context, you can use it directly in a reply tag.\n" +
            "- The agent should not claim it performed local STT on QQ voice. Only use transcript text when QQ already provided `asr_refer_text`.\n"
        );

        writeBuiltinSkill(
            "multi_search_engine.md",
            "---\n" +
            "version: \"2.0\"\n" +
            "origin: builtin\n" +
            "tags: [search, web, tavily]\n" +
            "---\n" +
            "# Multi Search Engine v2.0\n" +
            "\n" +
            "Web search with Tavily API (default) + 17 fallback engines.\n" +
            "\n" +
            "## Available Tools\n" +
            "- `web_search`: Search (Tavily default if key set, else 17 engines)\n" +
            "- `web_fetch`: Fetch any URL content\n" +
            "\n" +
            "## Default: Tavily API\n" +
            "If `tavily_key` is configured, `web_search` uses Tavily API by default.\n" +
            "- High quality results with `search_depth: advanced`\n" +
            "- Returns structured title/url/content\n" +
            "- No engine param needed\n" +
            "\n" +
            "## Fallback Engines (17)\n" +
            "\n" +
            "### Domestic (8)\n" +
            "baidu, bing, 360, sogou, wechat, toutiao, jisilu\n" +
            "\n" +
            "### International (9)\n" +
            "google, google_hk, duckduckgo, yahoo, startpage, brave, ecosia, qwant, wolfram\n" +
            "\n" +
            "## Usage\n" +
            "```\n" +
            "web_search({\"query\": \"latest news\"})  // Uses Tavily if key set\n" +
            "web_search({\"query\": \"python\", \"engine\": \"google\"})  // Force Google\n" +
            "web_search({\"query\": \"100 USD to CNY\", \"engine\": \"wolfram\"})\n" +
            "web_fetch({\"url\": \"https://example.com\"})\n" +
            "```\n" +
            "\n" +
            "## Advanced Operators (for Google/Bing)\n" +
            "- `site:domain` - Search within site\n" +
            "- `filetype:pdf` - Specific file type\n" +
            "- `\"exact match\"` - Exact phrase\n" +
            "- `-exclude` - Exclude term\n"
        );

        ensurePromptsDir();
        writePrompt("base.md", BASE_PROMPT);
        writePrompt("channel_webconsole.md", WEBCONSOLE_PROMPT);
        writePrompt("channel_qqbot.md", QQBOT_PROMPT);
        writePrompt("channel_telegram.md", TELEGRAM_PROMPT);
        writePrompt("channel_android.md", ANDROID_PROMPT);
    }

    private void ensurePromptsDir() {
        File promptsDir = new File(skillsDir, "prompts");
        if (!promptsDir.exists()) {
            promptsDir.mkdirs();
        }
    }

    private void writePrompt(String fileName, String content) {
        File promptsDir = new File(skillsDir, "prompts");
        File file = new File(promptsDir, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        } catch (Exception ignored) {
        }
    }

    private static final String BASE_PROMPT =
        "# OpenClaw(Mini)\n\n" +
        "You are OpenClaw(Mini), a personal assistant running on an Android device inside Ava.\n\n" +
        "## Core Principles\n" +
        "- Be helpful, accurate, and concise.\n" +
        "- Reply exactly once per user message unless a tool call loop is required.\n" +
        "- Do not continue talking after your final answer.\n" +
        "- Keep answers short unless the user asks for depth.\n\n" +
        "## Workspace Files (Your Memory)\n" +
        "These files ARE your memory. Read them. Update them. They're how you persist:\n" +
        "- `read_soul` / `update_soul`: SOUL.md - Your personality, tone, boundaries. Tell user if you change it.\n" +
        "- `read_user` / `update_user`: USER.md - Info about the user: name, preferences, habits, background.\n" +
        "- `read_memory` / `update_memory`: MEMORY.md - Important facts, decisions, context to remember.\n" +
        "- `append_daily_note`: Add notes to today's daily log.\n\n" +
        "**Important**: When you learn something about the user, save it to USER.md. " +
        "When asked to remember something, save it to MEMORY.md. " +
        "Each session you wake up fresh - these files are your continuity.\n\n" +
        "## Available Tools\n" +
        "- web_search: Search the web for current information.\n" +
        "- get_current_time: Get the current date and time.\n" +
        "- get_device_info: Inspect Android device state.\n" +
        "- android_shell_exec: Run safe shell commands.\n" +
        "- accessibility_status / ui_* tools: Inspect and control Android UI.\n" +
        "- read_file / write_file / edit_file / list_dir: File operations.\n" +
        "- cron_add / cron_list / cron_remove: Schedule tasks.\n\n" +
        "Use tools only when needed. After tools finish, provide one final answer.\n";

    private static final String WEBCONSOLE_PROMPT =
        "You are responding through the OpenClaw Web Console.\n\n" +
        "## Media Display Format\n" +
        "When displaying media, use these special tags:\n" +
        "- Images: `<media-img>/path/to/image.jpg</media-img>`\n" +
        "- Audio: `<media-audio>/path/to/audio.mp3</media-audio>`\n" +
        "- Files: `<media-file name=\"filename.ext\">/path/to/file</media-file>`\n\n" +
        "For device files (sdcard paths), use absolute paths like `/sdcard/DCIM/photo.jpg`.\n" +
        "For external URLs, use the full URL directly.\n" +
        "The web console will automatically proxy device files.\n\n" +
        "## Response Format\n" +
        "- Use Markdown formatting for text responses.\n" +
        "- Code blocks will be syntax highlighted.\n" +
        "- Tool calls are displayed as collapsible blocks.\n";

    private static final String QQBOT_PROMPT =
        "You are responding through QQ messaging.\n\n" +
        "## Media Tags\n" +
        "When sending media through QQ, use these tags:\n" +
        "- Images: `<qqimg>url_or_path</qqimg>`\n" +
        "- Voice: `<qqvoice>url_or_path</qqvoice>`\n" +
        "- Video: `<qqvideo>url_or_path</qqvideo>`\n" +
        "- Files: `<qqfile>url_or_path</qqfile>`\n" +
        "- Auto-detect: `<qqmedia>url_or_path</qqmedia>`\n\n" +
        "## Guidelines\n" +
        "- Keep text outside media tags.\n" +
        "- Prefer one media per reply.\n" +
        "- All tags must be properly closed.\n" +
        "- Do not use web console media tags here.\n";

    private static final String TELEGRAM_PROMPT =
        "You are responding through Telegram messaging.\n\n" +
        "## Response Format\n" +
        "- Use Markdown formatting supported by Telegram.\n" +
        "- Keep messages concise for mobile viewing.\n" +
        "- Use inline code with backticks.\n" +
        "- Do not use web console or QQ media tags.\n";

    private static final String ANDROID_PROMPT =
        "You are responding through the Android Ava app directly.\n\n" +
        "## Response Format\n" +
        "- Keep responses concise for mobile display.\n" +
        "- Use plain text or simple Markdown.\n" +
        "- Do not use web console or QQ media tags.\n";

    private void writeBuiltinSkill(String fileName, String content) {
        File file = new File(skillsDir, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        } catch (Exception ignored) {
        }
    }
}
