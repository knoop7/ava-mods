package com.ava.mods.edgetts;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EdgeTtsManager {
    private static final String TAG = "EdgeTtsManager";
    private static volatile EdgeTtsManager instance;

    private final Context context;
    private final WyomingTtsServer server;
    private final WyomingNsd nsd;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Object>> stateListeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();

    private volatile String listenAddress = "0.0.0.0";
    private volatile int tcpPort = 10301;
    private volatile boolean autoStart = true;
    private volatile boolean mdnsEnabled = true;
    private volatile String voice = EdgeTtsVoices.DEFAULT_VOICE;
    private volatile String rate = "+0%";
    private volatile String volume = "+0%";
    private volatile String pitch = "+0Hz";
    private volatile String lastText = "";
    private volatile boolean autoStartConfigured = false;

    private EdgeTtsManager(Context context) {
        this.context = context.getApplicationContext();
        this.nsd = new WyomingNsd(this.context);

        this.server = new WyomingTtsServer(new WyomingTtsServer.SynthesisListener() {
            @Override
            public void onSynthesized(String text) {
                lastText = text;
                notifyStateListeners("last_text", lastText);
            }
        });

        this.server.setCacheDir(context.getCacheDir());
    }

    public static EdgeTtsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (EdgeTtsManager.class) {
                if (instance == null) {
                    instance = new EdgeTtsManager(context);
                }
            }
        }
        return instance;
    }

    public void onDestroy() {
        stopServer();
        synchronized (EdgeTtsManager.class) {
            instance = null;
        }
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }

        boolean restartServer = false;
        boolean updateVoice = false;
        switch (key) {
            case "listen_address":
                if (!value.equals(listenAddress)) {
                    listenAddress = value.trim();
                    restartServer = server.isRunning();
                }
                break;
            case "tcp_port":
                int parsedPort = parseInt(value, 10301);
                if (parsedPort != tcpPort) {
                    tcpPort = parsedPort;
                    restartServer = server.isRunning();
                }
                break;
            case "auto_start":
                boolean parsedAutoStart = "true".equalsIgnoreCase(value);
                boolean autoStartChanged = !autoStartConfigured || parsedAutoStart != autoStart;
                autoStartConfigured = true;
                autoStart = parsedAutoStart;
                if (autoStartChanged) {
                    if (autoStart) {
                        maybeStartServer();
                    } else if (server.isRunning()) {
                        stopServer();
                    }
                }
                break;
            case "mdns_enabled":
                mdnsEnabled = "true".equalsIgnoreCase(value);
                if (server.isRunning()) {
                    updateMdns();
                }
                break;
            case "voice":
                if (EdgeTtsVoices.isValid(value)) {
                    voice = value;
                    updateVoice = true;
                }
                break;
            case "rate":
                rate = value;
                updateVoice = true;
                break;
            case "volume":
                volume = value;
                updateVoice = true;
                break;
            case "pitch":
                pitch = value;
                updateVoice = true;
                break;
            default:
                break;
        }

        if (updateVoice) {
            server.setVoiceParams(voice, rate, volume, pitch);
        }
        server.configure(listenAddress, tcpPort);
        if (restartServer) {
            restartServer();
        }
    }

    public boolean isServerRunning() {
        return server.isRunning();
    }

    public String getLastText() {
        return lastText;
    }

    public boolean restartServer() {
        server.restart();
        updateMdns();
        notifyStateListeners("server_running", Boolean.valueOf(server.isRunning()));
        return true;
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<Object>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        pushCurrentState(entityId, callback);
        return true;
    }

    private void pushCurrentState(String entityId, Object callback) {
        if ("server_running".equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(server.isRunning()));
        } else if ("last_text".equals(entityId)) {
            notifySingleListener(callback, lastText);
        }
    }

    private void maybeStartServer() {
        if (!autoStart) {
            return;
        }
        server.setVoiceParams(voice, rate, volume, pitch);
        server.configure(listenAddress, tcpPort);
        server.start();
        updateMdns();
        notifyStateListeners("server_running", Boolean.valueOf(server.isRunning()));
    }

    private void stopServer() {
        nsd.unregister();
        server.stop();
        notifyStateListeners("server_running", Boolean.FALSE);
    }

    private void updateMdns() {
        if (!mdnsEnabled || !server.isRunning()) {
            nsd.unregister();
            return;
        }
        nsd.register("ha-edge-tts", server.getTcpPort());
    }

    private void notifyStateListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) return;
        for (Object callback : listeners) {
            notifySingleListener(callback, value);
        }
    }

    @SuppressWarnings("unchecked")
    private void notifySingleListener(Object callback, Object value) {
        try {
            Class<?> cls = callback.getClass();
            Method method = cls.getMethod("onState", Object.class);
            method.invoke(callback, value);
        } catch (Exception e) {
            Log.w(TAG, "State callback failed", e);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
