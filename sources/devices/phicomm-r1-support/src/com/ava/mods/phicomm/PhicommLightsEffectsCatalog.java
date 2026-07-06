package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses stock {@code /system/etc/lights_effects.conf} (same format as {@code LightsEffectService}).
 *
 * Load order: root {@code cat} → direct system read → mod cache → built-in table.
 * No Shizuku; no mod manifest permissions.
 */
final class PhicommLightsEffectsCatalog {
    private static final String TAG = "PhicommLightsCatalog";
    static final String SYSTEM_PATH = "/system/etc/lights_effects.conf";

    /**
     * Minimal mirror of the real R1 conf (extracted from 官改 v1.2 {@code system.img}):
     * channel_num=3; wakeup ids 1–24 are white-LED sweeps on channel 1 (auto-close 200 s);
     * 100 = dormant GPIO LEDs on channel 2; 203 = blue breathing loading; 254 = red
     * net-disconnect (auto-close 60 s); 300–315 / 400–415 = ring brightness steps
     * (auto-close 3 s); 519 = blank/claim ring for the music visualizer.
     */
    private static final String BUILTIN_CONF =
        "channel_num=3\n"
        + "1,1,1,200,wakeup_dir_1\n"
        + "24,1,1,200,wakeup_dir_24\n"
        + "100,2,1,0,dormant_gpio\n"
        + "203,0,1,0,loading_blue_breathe\n"
        + "254,0,1,60,net_disconnect_red\n"
        + "300,0,1,3,vol_up_0\n"
        + "315,0,1,3,vol_up_15\n"
        + "400,0,1,3,vol_down_0\n"
        + "415,0,1,3,vol_down_15\n"
        + "519,0,1,0,playing_music_blank\n";

    static final class Entry {
        final int id;
        final int channel;
        final int priority;
        final int durationSeconds;
        final String params;

        Entry(int id, int channel, int priority, int durationSeconds, String params) {
            this.id = id;
            this.channel = channel;
            this.priority = priority;
            this.durationSeconds = durationSeconds;
            this.params = params;
        }
    }

    private final Map<Integer, Entry> byId = new HashMap<Integer, Entry>();
    private volatile String source = "unloaded";
    private volatile int channelCount;

    Map<Integer, Entry> entries() {
        return Collections.unmodifiableMap(byId);
    }

    String getSource() {
        return source;
    }

    int getChannelCount() {
        return channelCount;
    }

    Entry get(int lightId) {
        return byId.get(lightId);
    }

    boolean hasId(int lightId) {
        return byId.containsKey(lightId);
    }

    void load(Context context, PhicommPrivilegedShell shell) {
        byId.clear();
        channelCount = 0;
        String text = loadViaRoot(shell);
        if (text != null) {
            source = "root";
        } else {
            text = loadViaDirectRead();
            if (text != null) {
                source = "system";
            }
        }
        if (text == null) {
            text = loadFromCache(context);
            if (text != null) {
                source = "cache";
            }
        }
        if (text == null) {
            text = BUILTIN_CONF;
            source = "builtin";
        }
        parse(text);
        if ("root".equals(source) || "system".equals(source)) {
            saveCache(context, text);
        }
        Log.i(TAG, "loaded " + byId.size() + " light ids source=" + source
            + " channels=" + channelCount);
    }

    private static String loadViaRoot(PhicommPrivilegedShell shell) {
        if (shell == null || !shell.isRootAvailable()) {
            return null;
        }
        return shell.captureOutput("cat " + SYSTEM_PATH);
    }

    private static String loadViaDirectRead() {
        File file = new File(SYSTEM_PATH);
        if (!file.canRead()) {
            return null;
        }
        try {
            return readUtf8(file);
        } catch (Throwable t) {
            Log.d(TAG, "direct read failed: " + t.getMessage());
            return null;
        }
    }

    private static String loadFromCache(Context context) {
        File cache = cacheFile(context);
        if (!cache.exists()) {
            return null;
        }
        try {
            return readUtf8(cache);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveCache(Context context, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        File cache = cacheFile(context);
        File parent = cache.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(cache);
            out.write(text.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.d(TAG, "cache write failed: " + t.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "phicomm/lights_effects.conf");
    }

    private void parse(String text) {
        BufferedReader reader = new BufferedReader(new StringReader(text));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length == 5) {
                    Entry entry = new Entry(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()),
                        Integer.parseInt(parts[3].trim()),
                        parts[4].trim()
                    );
                    byId.put(entry.id, entry);
                } else if (parts.length == 1 && parts[0].contains("=")) {
                    String[] kv = parts[0].split("=");
                    if (kv.length == 2 && "channel_num".equals(kv[0].trim())) {
                        channelCount = Integer.parseInt(kv[1].trim());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse error source=" + source, t);
        }
    }

    private static String readUtf8(File file) throws java.io.IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) >= 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }
}
