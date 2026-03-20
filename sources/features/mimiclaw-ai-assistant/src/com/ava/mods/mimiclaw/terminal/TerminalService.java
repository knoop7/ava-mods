package com.ava.mods.mimiclaw.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terminal service for AI to execute shell commands with full PTY support.
 * Uses Termux terminal-emulator library for proper terminal emulation.
 */
public class TerminalService {
    private static final String TAG = "TerminalService";
    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLS = 80;
    
    private static volatile TerminalService instance;
    private static final Object instanceLock = new Object();
    
    private final Context context;
    private TerminalSession currentSession;
    private final StringBuilder outputBuffer = new StringBuilder();
    private final Object outputLock = new Object();
    private HandlerThread handlerThread;
    private Handler handler;
    
    public static TerminalService getInstance(Context context) {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new TerminalService(context);
                }
            }
        }
        return instance;
    }
    
    private TerminalService(Context context) {
        this.context = context;
        // Create a HandlerThread with Looper for TerminalSession
        handlerThread = new HandlerThread("TerminalServiceThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }
    
    /**
     * Execute a command and wait for completion.
     * @param command The command to execute
     * @param cwd Working directory (null for default)
     * @param timeoutSeconds Maximum time to wait
     * @return Command output
     */
    public String executeCommand(String command, String cwd, int timeoutSeconds) {
        final String shellPath = "/system/bin/sh";
        final String appFilesDir = context.getFilesDir().getAbsolutePath();
        // Handle null or empty cwd
        final String workDir = (cwd != null && !cwd.isEmpty()) ? cwd : appFilesDir;
        // args[0] must be the shell name (argv[0]), then -c, then the command
        final String[] args = new String[]{"sh", "-c", command};
        final String[] env = buildEnvironment();
        
        synchronized (outputLock) {
            outputBuffer.setLength(0);
        }
        
        final CountDownLatch completionLatch = new CountDownLatch(1);
        final AtomicReference<Integer> exitCode = new AtomicReference<>(-1);
        final AtomicReference<Exception> error = new AtomicReference<>(null);
        
        // Run session creation on handler thread with Looper
        handler.post(() -> {
            try {
                TerminalSessionClient client = new TerminalSessionClient() {
                    @Override
                    public void onTextChanged(TerminalSession session) {
                        TerminalEmulator emulator = session.getEmulator();
                        if (emulator != null) {
                            synchronized (outputLock) {
                                outputBuffer.setLength(0);
                                int rows = emulator.getScreen().getActiveRows();
                                for (int i = 0; i < rows; i++) {
                                    String line = emulator.getScreen().getSelectedText(0, i, emulator.mColumns - 1, i);
                                    if (line != null) {
                                        outputBuffer.append(line.trim()).append("\n");
                                    }
                                }
                            }
                        }
                    }
                    
                    @Override public void onTitleChanged(TerminalSession session) {}
                    
                    @Override
                    public void onSessionFinished(TerminalSession session) {
                        exitCode.set(session.getExitStatus());
                        completionLatch.countDown();
                    }
                    
                    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
                    @Override public void onPasteTextFromClipboard(TerminalSession session) {}
                    @Override public void onBell(TerminalSession session) {}
                    @Override public void onColorsChanged(TerminalSession session) {}
                    @Override public void onTerminalCursorStateChange(boolean state) {}
                    @Override public Integer getTerminalCursorStyle() { return 0; }
                    @Override public void logError(String tag, String message) { Log.e(tag, message); }
                    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
                    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
                    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
                    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
                    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
                    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Exception", e); }
                };
                
                currentSession = new TerminalSession(shellPath, workDir, args, env, 2000, client);
                currentSession.updateSize(DEFAULT_COLS, DEFAULT_ROWS);
            } catch (Exception e) {
                error.set(e);
                completionLatch.countDown();
            }
        });
        
        try {
            boolean completed = completionLatch.await(timeoutSeconds, TimeUnit.SECONDS);
            
            if (error.get() != null) {
                return "Error: " + error.get().getMessage();
            }
            
            String output;
            synchronized (outputLock) {
                output = outputBuffer.toString().trim();
            }
            
            if (!completed) {
                if (currentSession != null) {
                    currentSession.finishIfRunning();
                }
                return "Error: Command timed out after " + timeoutSeconds + " seconds\n" + output;
            }
            
            int code = exitCode.get();
            if (code != 0) {
                return "Exit code: " + code + "\n" + output;
            }
            
            return output;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + command, e);
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * Start an interactive session.
     * @param cwd Working directory
     * @return Session ID
     */
    public String startSession(String cwd) {
        try {
            String shellPath = "/system/bin/sh";
            String appFilesDir = context.getFilesDir().getAbsolutePath();
            String workDir = cwd != null ? cwd : appFilesDir;
            String[] args = new String[]{};
            String[] env = buildEnvironment();
            
            TerminalSessionClient client = createMinimalClient();
            currentSession = new TerminalSession(shellPath, workDir, args, env, 2000, client);
            currentSession.updateSize(DEFAULT_COLS, DEFAULT_ROWS);
            
            return currentSession.mHandle;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start session", e);
            return null;
        }
    }
    
    /**
     * Write input to the current session.
     */
    public void writeToSession(String input) {
        if (currentSession != null && currentSession.isRunning()) {
            byte[] bytes = (input + "\n").getBytes();
            currentSession.write(bytes, 0, bytes.length);
        }
    }
    
    /**
     * Read current output from session.
     */
    public String readSessionOutput() {
        if (currentSession == null) return "";
        
        TerminalEmulator emulator = currentSession.getEmulator();
        if (emulator == null) return "";
        
        StringBuilder sb = new StringBuilder();
        int rows = emulator.getScreen().getActiveRows();
        for (int i = 0; i < rows; i++) {
            String line = emulator.getScreen().getSelectedText(0, i, emulator.mColumns - 1, i);
            if (line != null) {
                sb.append(line.trim()).append("\n");
            }
        }
        return sb.toString().trim();
    }
    
    /**
     * Close current session.
     */
    public void closeSession() {
        if (currentSession != null) {
            currentSession.finishIfRunning();
            currentSession = null;
        }
    }
    
    /**
     * Check if session is running.
     */
    public boolean isSessionRunning() {
        return currentSession != null && currentSession.isRunning();
    }
    
    /**
     * Get current working directory.
     */
    public String getCurrentDirectory() {
        if (currentSession != null) {
            return currentSession.getCwd();
        }
        return null;
    }
    
    private String[] buildEnvironment() {
        Map<String, String> envMap = new HashMap<>();
        envMap.put("TERM", "xterm-256color");
        envMap.put("HOME", context.getFilesDir().getAbsolutePath());
        envMap.put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin");
        envMap.put("LANG", "en_US.UTF-8");
        envMap.put("SHELL", "/system/bin/sh");
        
        String[] env = new String[envMap.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            env[i++] = entry.getKey() + "=" + entry.getValue();
        }
        return env;
    }
    
    private TerminalSessionClient createMinimalClient() {
        return new TerminalSessionClient() {
            @Override public void onTextChanged(TerminalSession session) {}
            @Override public void onTitleChanged(TerminalSession session) {}
            @Override public void onSessionFinished(TerminalSession session) {}
            @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
            @Override public void onPasteTextFromClipboard(TerminalSession session) {}
            @Override public void onBell(TerminalSession session) {}
            @Override public void onColorsChanged(TerminalSession session) {}
            @Override public void onTerminalCursorStateChange(boolean state) {}
            @Override public Integer getTerminalCursorStyle() { return 0; }
            @Override public void logError(String tag, String message) { Log.e(tag, message); }
            @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
            @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
            @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
            @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
            @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
            @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Exception", e); }
        };
    }
}
