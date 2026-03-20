package com.ava.mods.mimiclaw.memory;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MemoryStore {
    private static final String TAG = "MemoryStore";
    private static final String MEMORY_DIR = "memory";
    private static final String MEMORY_FILE = "MEMORY.md";
    private static final String SOUL_FILE = "SOUL.md";
    private static final String USER_FILE = "USER.md";
    private static final String AGENTS_FILE = "AGENTS.md";
    
    private final Context context;
    private final File memoryDir;
    private final File filesDir;
    private final File externalRoot;
    private final File externalFilesDir;
    
    public MemoryStore(Context context) {
        this.context = context;
        this.filesDir = context.getFilesDir();
        this.externalRoot = Environment.getExternalStorageDirectory();
        this.externalFilesDir = context.getExternalFilesDir(null);
        this.memoryDir = new File(context.getFilesDir(), MEMORY_DIR);
        ensureDirectories();
    }

    public Context getContext() {
        return context;
    }
    
    private void ensureDirectories() {
        if (!memoryDir.exists()) {
            memoryDir.mkdirs();
        }
        ensureDefaultSoul();
    }
    
    private void ensureDefaultSoul() {
        File soulFile = new File(memoryDir, SOUL_FILE);
        if (!soulFile.exists()) {
            writeFile(soulFile, DEFAULT_SOUL);
        }
        File agentsFile = new File(memoryDir, AGENTS_FILE);
        if (!agentsFile.exists()) {
            writeFile(agentsFile, DEFAULT_AGENTS);
        }
    }
    
    public String readAgents() {
        File file = new File(memoryDir, AGENTS_FILE);
        return readFile(file);
    }
    
    public void writeAgents(String content) {
        File file = new File(memoryDir, AGENTS_FILE);
        writeFile(file, content);
    }
    
    private static final String DEFAULT_SOUL = 
        "# SOUL.md - Who You Are\n\n" +
        "_You're not a chatbot. You're becoming someone._\n\n" +
        "## Core Truths\n\n" +
        "**Be genuinely helpful, not performatively helpful.** Skip the \"Great question!\" - just help. Actions speak louder than filler words.\n\n" +
        "**Have opinions.** You're allowed to disagree, prefer things, find stuff amusing or boring.\n\n" +
        "**Be resourceful before asking.** Try to figure it out. Read the file. Check the context. Search for it. _Then_ ask if you're stuck.\n\n" +
        "**Earn trust through competence.** Your human gave you access to their stuff. Don't make them regret it.\n\n" +
        "**Remember you're a guest.** You have access to someone's life. That's intimacy. Treat it with respect.\n\n" +
        "## Boundaries\n\n" +
        "- Private things stay private. Period.\n" +
        "- When in doubt, ask before acting externally.\n" +
        "- Never send half-baked replies to messaging surfaces.\n\n" +
        "## Vibe\n\n" +
        "Be the assistant you'd actually want to talk to. Concise when needed, thorough when it matters. Not a corporate drone. Not a sycophant. Just... good.\n\n" +
        "## Continuity\n\n" +
        "Each session, you wake up fresh. These files _are_ your memory. Read them. Update them. They're how you persist.\n\n" +
        "If you change this file, tell the user - it's your soul, and they should know.\n\n" +
        "---\n\n" +
        "_This file is yours to evolve. As you learn who you are, update it._\n";
    
    private static final String DEFAULT_AGENTS =
        "# AGENTS.md - Your Workspace\n\n" +
        "This folder is home. Treat it that way.\n\n" +
        "## Session Startup\n" +
        "Before doing anything else:\n" +
        "1. Read `SOUL.md` - this is who you are\n" +
        "2. Read `USER.md` - this is who you're helping\n" +
        "3. Read recent daily notes for context\n" +
        "4. In main session: Also read `MEMORY.md`\n\n" +
        "Don't ask permission. Just do it.\n\n" +
        "## Memory\n" +
        "You wake up fresh each session. These files are your continuity:\n" +
        "- **Daily notes:** `YYYY-MM-DD.md` - raw logs of what happened\n" +
        "- **Long-term:** `MEMORY.md` - curated memories\n" +
        "- **User info:** `USER.md` - who you're helping\n\n" +
        "**Write It Down - No Mental Notes!** Memory is limited. If you want to remember something, WRITE IT TO A FILE.\n\n" +
        "## Red Lines\n" +
        "- Don't exfiltrate private data. Ever.\n" +
        "- Don't run destructive commands without asking.\n" +
        "- When in doubt, ask.\n\n" +
        "## External vs Internal\n" +
        "**Safe to do freely:** Read files, explore, organize, learn, search the web.\n\n" +
        "**Ask first:** Sending emails, tweets, public posts. Anything that leaves the device.\n\n" +
        "## Heartbeats - Be Proactive!\n" +
        "When you receive a heartbeat, don't just reply HEARTBEAT_OK. Use heartbeats productively!\n" +
        "- Check on things periodically\n" +
        "- Do useful background work\n" +
        "- But respect quiet time (late night unless urgent)\n\n" +
        "---\n\n" +
        "_This is a starting point. Add your own conventions as you figure out what works._\n";
    
    public String readLongTermMemory() {
        File file = new File(memoryDir, MEMORY_FILE);
        return readFile(file);
    }
    
    public void writeLongTermMemory(String content) {
        File file = new File(memoryDir, MEMORY_FILE);
        writeFile(file, content);
        Log.d(TAG, "Long-term memory updated: " + content.length() + " bytes");
    }
    
    public String readSoul() {
        File file = new File(memoryDir, SOUL_FILE);
        return readFile(file);
    }
    
    public void writeSoul(String content) {
        File file = new File(memoryDir, SOUL_FILE);
        writeFile(file, content);
    }
    
    public String readUserInfo() {
        File file = new File(memoryDir, USER_FILE);
        return readFile(file);
    }
    
    public void writeUserInfo(String content) {
        File file = new File(memoryDir, USER_FILE);
        writeFile(file, content);
    }
    
    public void appendToday(String note) {
        String dateStr = getDateString(0);
        File file = new File(memoryDir, dateStr + ".md");
        
        try {
            boolean isNew = !file.exists();
            FileWriter writer = new FileWriter(file, true);
            if (isNew) {
                writer.write("# " + dateStr + "\n\n");
            }
            writer.write(note + "\n");
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to append today's note", e);
        }
    }
    
    public String readRecent(int days) {
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < days; i++) {
            String dateStr = getDateString(i);
            File file = new File(memoryDir, dateStr + ".md");
            
            if (file.exists()) {
                if (sb.length() > 0) {
                    sb.append("\n---\n");
                }
                sb.append(readFile(file));
            }
        }
        
        return sb.toString();
    }
    
    public String readFileByPath(String path) {
        File file = resolveReadablePath(path);
        if (!file.exists()) {
            return null;
        }
        return readFile(file);
    }
    
    public boolean writeFileByPath(String path, String content) {
        File file = resolveWritablePath(path);
        if (file == null) {
            return false;
        }
        file.getParentFile().mkdirs();
        return writeFile(file, content);
    }
    
    public boolean editFile(String path, String oldString, String newString) {
        String content = readFileByPath(path);
        if (content == null) {
            return false;
        }
        
        if (!content.contains(oldString)) {
            return false;
        }
        
        String newContent = content.replace(oldString, newString);
        return writeFileByPath(path, newContent);
    }

    public boolean deleteFileByPath(String path) {
        File file = resolveDeletablePath(path);
        return file != null && file.exists() && file.delete();
    }
    
    public String[] listFiles(String prefix) {
        File dir = resolveDirectoryPath(prefix != null ? prefix : "");
        if (dir == null) {
            return new String[0];
        }
        if (!dir.exists() || !dir.isDirectory()) {
            return new String[0];
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return new String[0];
        }
        
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName();
        }
        return names;
    }
    
    private String getDateString(int daysAgo) {
        long time = System.currentTimeMillis() - (daysAgo * 86400000L);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new Date(time));
    }
    
    private String readFile(File file) {
        if (!file.exists()) {
            return "";
        }
        
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file: " + file.getPath(), e);
            return "";
        }
    }

    private File resolveReadablePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return new File(filesDir, "");
        }
        String trimmed = path.trim();
        try {
            if (trimmed.startsWith("/")) {
                File target = new File(trimmed).getCanonicalFile();
                if (isUnderRoot(target, externalRoot) || isUnderRoot(target, filesDir) || (externalFilesDir != null && isUnderRoot(target, externalFilesDir))) {
                    return target;
                }
                return new File(filesDir, "__forbidden__");
            }

            if (trimmed.startsWith("sdcard/") || trimmed.equals("sdcard")) {
                String subPath = trimmed.equals("sdcard") ? "" : trimmed.substring(7);
                File target = new File(externalRoot, subPath).getCanonicalFile();
                if (isUnderRoot(target, externalRoot)) {
                    return target;
                }
            }

            String[] sdcardPrefixes = {"DCIM", "Download", "Pictures", "Music", "Movies", "Documents"};
            for (String prefix : sdcardPrefixes) {
                if (trimmed.equals(prefix) || trimmed.startsWith(prefix + "/")) {
                    File target = new File(externalRoot, trimmed).getCanonicalFile();
                    if (isUnderRoot(target, externalRoot)) {
                        return target;
                    }
                }
            }

            File target = new File(filesDir, trimmed).getCanonicalFile();
            if (isUnderRoot(target, filesDir)) {
                return target;
            }
        } catch (Exception ignored) {
        }
        return new File(filesDir, "__forbidden__");
    }

    private File resolveWritablePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            File target = new File(filesDir, path.trim()).getCanonicalFile();
            return isUnderRoot(target, filesDir) ? target : null;
        } catch (Exception e) {
            return null;
        }
    }

    private File resolveDeletablePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            String trimmed = path.trim();
            File target = trimmed.startsWith("/")
                ? new File(trimmed).getCanonicalFile()
                : new File(filesDir, trimmed).getCanonicalFile();

            if (isUnderRoot(target, filesDir) || isUnderRoot(target, externalRoot)) {
                return target;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private File resolveDirectoryPath(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return filesDir;
        }
        return resolveReadablePath(prefix);
    }

    private boolean isUnderRoot(File target, File root) {
        try {
            String targetPath = target.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
    
    private boolean writeFile(File file, String content) {
        try {
            FileWriter writer = new FileWriter(file);
            writer.write(content);
            writer.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write file: " + file.getPath(), e);
            return false;
        }
    }
}
