package com.ava.mods.hasttengine;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class HaSttEngineManager {
    private static final String TAG = "HaSttEngineManager";
    private static volatile HaSttEngineManager instance;

    private final Context context;
    private final SenseVoiceEngine engine = new SenseVoiceEngine();
    private final ModelDownloader downloader;
    private final WyomingTcpServer server;
    private final WyomingNsd nsd;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Object>> stateListeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();

    private volatile String listenAddress = "0.0.0.0";
    private volatile int tcpPort = 10300;
    private volatile boolean autoStart = true;
    private volatile boolean mdnsEnabled = true;
    private volatile boolean autoDownload = true;
    private volatile int numThreads = 2;
    private volatile String recognitionLanguage = SenseVoiceLanguages.DEFAULT;

    private volatile String modelStatus = "not_ready";
    private volatile int downloadProgress = 0;
    private volatile String lastTranscript = "";
    private volatile String lastEmotion = "";
    private volatile String lastAudioEvent = "";
    private volatile String downloadErrorMessage = "";

    private HaSttEngineManager(Context context) {
        this.context = context.getApplicationContext();
        this.downloader = new ModelDownloader(this.context);
        this.downloader.setListener(new ModelDownloader.ProgressListener() {
            @Override
            public void onStatus(String status) {
                modelStatus = status;
                notifyStateListeners("model_status", getModelStatusDisplay());
            }

            @Override
            public void onProgress(int percent) {
                downloadProgress = percent;
                notifyStateListeners("download_progress", Integer.valueOf(percent));
            }

            @Override
            public void onFinished(boolean success, String message) {
                if (success) {
                    loadRecognizer();
                    maybeStartServer();
                    downloadErrorMessage = "";
                } else if (message != null && message.toLowerCase().contains("paused")) {
                    modelStatus = "paused";
                    downloadErrorMessage = "";
                    notifyStateListeners("model_status", getModelStatusDisplay());
                } else {
                    modelStatus = "error";
                    downloadErrorMessage = message == null ? "Download failed" : message;
                    notifyStateListeners("model_status", getModelStatusDisplay());
                }
                Log.i(TAG, "Model download finished success=" + success + " message=" + message);
            }
        });

        this.server = new WyomingTcpServer(engine, new WyomingTcpServer.TranscriptListener() {
            @Override
            public void onTranscript(RecognitionResult result) {
                lastTranscript = result.text;
                lastEmotion = result.emotion;
                lastAudioEvent = result.audioEvent;
                notifyStateListeners("last_transcript", lastTranscript);
                notifyStateListeners("last_emotion", lastEmotion);
                notifyStateListeners("last_audio_event", lastAudioEvent);
            }
        });
        this.nsd = new WyomingNsd(this.context);

        if (ModelStore.isReady(this.context)) {
            modelStatus = "ready";
            loadRecognizer();
        } else {
            modelStatus = "not_ready";
            if (autoDownload) {
                downloadModel();
            }
        }
        maybeStartServer();
    }

    public static HaSttEngineManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HaSttEngineManager.class) {
                if (instance == null) {
                    instance = new HaSttEngineManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }

        boolean restartServer = false;
        switch (key) {
            case "listen_address":
                if (!value.equals(listenAddress)) {
                    listenAddress = value.trim();
                    restartServer = server.isRunning();
                }
                break;
            case "tcp_port":
                int parsedPort = parseInt(value, 10300);
                if (parsedPort != tcpPort) {
                    tcpPort = parsedPort;
                    restartServer = server.isRunning();
                }
                break;
            case "auto_start":
                autoStart = "true".equalsIgnoreCase(value);
                if (autoStart) {
                    maybeStartServer();
                } else if (server.isRunning()) {
                    stopServer();
                }
                break;
            case "mdns_enabled":
                mdnsEnabled = "true".equalsIgnoreCase(value);
                if (server.isRunning()) {
                    updateMdns();
                }
                break;
            case "num_threads":
                int parsedThreads = parseInt(value, 2);
                if (parsedThreads != numThreads) {
                    numThreads = Math.max(1, Math.min(8, parsedThreads));
                    if (ModelStore.isReady(context)) {
                        loadRecognizer();
                    }
                }
                break;
            case "auto_download":
                autoDownload = "true".equalsIgnoreCase(value);
                if (autoDownload && !ModelStore.isReady(context) && !downloader.isRunning()) {
                    downloadModel();
                }
                break;
            case "recognition_language":
                String normalizedLanguage = SenseVoiceLanguages.normalize(value);
                if (!normalizedLanguage.equals(recognitionLanguage)) {
                    recognitionLanguage = normalizedLanguage;
                    if (ModelStore.isReady(context)) {
                        loadRecognizer();
                    }
                }
                break;
            default:
                break;
        }

        server.configure(listenAddress, tcpPort);
        if (restartServer) {
            restartServer();
        }
    }

    public boolean isServerRunning() {
        return server.isRunning();
    }

    public String getModelStatus() {
        return modelStatus;
    }

    public String getModelStatusDisplay() {
        if ("ready".equals(modelStatus)) {
            return "Ready";
        }
        if ("downloading".equals(modelStatus)) {
            return "Downloading in background…";
        }
        if ("paused".equals(modelStatus)) {
            return "Download paused";
        }
        if ("error".equals(modelStatus)) {
            return downloadErrorMessage == null || downloadErrorMessage.isEmpty()
                    ? "Download failed"
                    : "Error: " + downloadErrorMessage;
        }
        return "Not downloaded";
    }

    public float getDownloadProgress() {
        return downloadProgress;
    }

    public String getLastTranscript() {
        return lastTranscript;
    }

    public String getLastEmotion() {
        return lastEmotion;
    }

    public String getLastAudioEvent() {
        return lastAudioEvent;
    }

    public boolean downloadModel() {
        if (ModelStore.isReady(context)) {
            modelStatus = "ready";
            notifyStateListeners("model_status", getModelStatusDisplay());
            loadRecognizer();
            maybeStartServer();
            return true;
        }
        if (downloader.isRunning()) {
            return true;
        }
        modelStatus = "downloading";
        notifyStateListeners("model_status", getModelStatusDisplay());
        downloader.downloadAsync();
        return true;
    }

    public boolean pauseDownload() {
        if (!downloader.isRunning()) {
            return false;
        }
        downloader.cancelDownload();
        modelStatus = "paused";
        notifyStateListeners("model_status", getModelStatusDisplay());
        return true;
    }

    public boolean restartServer() {
        if (!engine.isLoaded()) {
            if (ModelStore.isReady(context)) {
                loadRecognizer();
            } else {
                Log.w(TAG, "Cannot restart server without model");
                return false;
            }
        }
        server.restart();
        updateMdns();
        notifyStateListeners("server_running", Boolean.valueOf(server.isRunning()));
        return true;
    }

    public boolean deleteModel() {
        downloader.cancelDownload();
        stopServer();
        engine.release();
        ModelStore.clear(context);
        modelStatus = "not_ready";
        downloadProgress = 0;
        downloadErrorMessage = "";
        notifyStateListeners("model_status", getModelStatusDisplay());
        notifyStateListeners("download_progress", Integer.valueOf(0));
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
        } else if ("model_status".equals(entityId)) {
            notifySingleListener(callback, getModelStatusDisplay());
        } else if ("download_progress".equals(entityId)) {
            notifySingleListener(callback, Integer.valueOf(downloadProgress));
        } else if ("last_transcript".equals(entityId)) {
            notifySingleListener(callback, lastTranscript);
        } else if ("last_emotion".equals(entityId)) {
            notifySingleListener(callback, lastEmotion);
        } else if ("last_audio_event".equals(entityId)) {
            notifySingleListener(callback, lastAudioEvent);
        }
    }

    private void maybeStartServer() {
        if (!autoStart || !engine.isLoaded()) {
            return;
        }
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
        nsd.register("ha-stt-engine", server.getTcpPort());
    }

    private void loadRecognizer() {
        if (!ModelStore.isReady(context)) {
            return;
        }
        try {
            engine.load(
                    ModelStore.modelFile(context).getAbsolutePath(),
                    ModelStore.tokensFile(context).getAbsolutePath(),
                    numThreads,
                    recognitionLanguage
            );
            modelStatus = "ready";
            notifyStateListeners("model_status", getModelStatusDisplay());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load recognizer", e);
            modelStatus = "error";
            notifyStateListeners("model_status", getModelStatusDisplay());
        }
    }

    private void notifyStateListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Object listener : listeners) {
            notifySingleListener(listener, value);
        }
    }

    private void notifySingleListener(Object listener, Object value) {
        try {
            Method method = listener.getClass().getMethod("onStateChanged", Object.class);
            method.invoke(listener, value);
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify state listener", e);
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
