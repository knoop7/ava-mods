package com.ava.mods.edgetts;

import android.util.Log;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class WyomingTtsServer {
    private static final String TAG = "EdgeTtsWyServer";

    interface SynthesisListener {
        void onSynthesized(String text);
    }

    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService sessionExecutor = Executors.newCachedThreadPool();
    private final SynthesisListener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    private volatile String listenAddress = "0.0.0.0";
    private volatile int tcpPort = 10301;
    private volatile boolean stopRequested;
    private ServerSocket serverSocket;

    private volatile String voice = EdgeTtsVoices.DEFAULT_VOICE;
    private volatile String rate = "+0%";
    private volatile String volume = "+0%";
    private volatile String pitch = "+0Hz";

    WyomingTtsServer(SynthesisListener listener) {
        this.listener = listener;
    }

    void configure(String listenAddress, int tcpPort) {
        this.listenAddress = listenAddress == null || listenAddress.trim().isEmpty()
                ? "0.0.0.0"
                : listenAddress.trim();
        this.tcpPort = tcpPort;
    }

    void setVoiceParams(String voice, String rate, String volume, String pitch) {
        if (voice != null && EdgeTtsVoices.isValid(voice)) {
            this.voice = voice;
        }
        if (rate != null) this.rate = rate;
        if (volume != null) this.volume = volume;
        if (pitch != null) this.pitch = pitch;
    }

    boolean isRunning() {
        return running.get();
    }

    int getTcpPort() {
        return tcpPort;
    }

    void start() {
        synchronized (lifecycleLock) {
            if (running.get() || starting.get()) {
                return;
            }
            stopRequested = false;
            starting.set(true);
        }
        acceptExecutor.execute(new Runnable() {
            @Override
            public void run() {
                runServerLoop();
            }
        });
    }

    void stop() {
        stopRequested = true;
        starting.set(false);
        ServerSocket socket;
        synchronized (lifecycleLock) {
            socket = serverSocket;
            serverSocket = null;
            running.set(false);
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    void restart() {
        stop();
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopRequested = false;
        start();
    }

    private void runServerLoop() {
        ServerSocket localSocket = null;
        try {
            localSocket = new ServerSocket();
            localSocket.setReuseAddress(true);
            localSocket.bind(new InetSocketAddress(listenAddress, tcpPort));

            synchronized (lifecycleLock) {
                if (stopRequested) {
                    return;
                }
                serverSocket = localSocket;
                running.set(true);
                starting.set(false);
            }

            Log.i(TAG, "Wyoming TTS server listening on " + listenAddress + ":" + tcpPort);

            while (!stopRequested && isOwnedSocket(localSocket)) {
                try {
                    Socket client = localSocket.accept();
                    sessionExecutor.execute(new WyomingTtsSession(client, voice, rate, volume, pitch,
                            new WyomingTtsSession.Callback() {
                                @Override
                                public void onSynthesized(String text) {
                                    if (listener != null) {
                                        listener.onSynthesized(text);
                                    }
                                }
                            }));
                } catch (Exception e) {
                    if (!stopRequested && isOwnedSocket(localSocket)) {
                        Log.e(TAG, "Accept failed", e);
                    }
                }
            }
        } catch (BindException e) {
            Log.e(TAG, "Port already in use: " + tcpPort, e);
        } catch (Exception e) {
            Log.e(TAG, "Server failed", e);
        } finally {
            running.set(false);
            starting.set(false);
            if (localSocket != null) {
                try {
                    localSocket.close();
                } catch (Exception ignored) {
                }
            }
            synchronized (lifecycleLock) {
                if (serverSocket == localSocket) {
                    serverSocket = null;
                }
            }
        }
    }

    private boolean isOwnedSocket(ServerSocket socket) {
        synchronized (lifecycleLock) {
            return serverSocket == socket;
        }
    }
}
