package com.ava.mods.irblaster;

import android.util.Log;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal ESPHome native-API (plaintext) server that advertises a single infrared
 * emitter entity. Home Assistant's ESPHome integration connects here and exposes the
 * entity on its 2026.4+ infrared platform, letting consumer integrations (LG, Samsung,
 * Daikin, ...) transmit through this device.
 */
final class EsphomeApiServer {

    private static final String TAG = "IrBlaster";

    interface TransmitHandler {
        void onTransmit(int carrierFrequency, int[] timings, int repeatCount);
    }

    // Device identity reported over the API (stable across reconnects).
    final String nodeName;
    final String friendlyName;
    final String macAddress;
    final String model;
    final String manufacturer;
    final String esphomeVersion;

    // The single infrared emitter entity.
    final int entityKey;
    final String entityObjectId;
    final String entityName;
    final String entityIcon;

    private final TransmitHandler transmitHandler;

    // If the configured port is taken (Ava's own ESPHome API may randomize into 6054..7052),
    // scan upward for a free one so the two servers never collide.
    private static final int PORT_SCAN_RANGE = 20;

    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService sessionExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    private volatile String listenAddress = "0.0.0.0";
    private volatile int tcpPort = 6053;
    private volatile int boundPort = -1;
    private volatile boolean stopRequested;
    private ServerSocket serverSocket;

    EsphomeApiServer(String nodeName, String friendlyName, String macAddress, String model,
                     String manufacturer, String esphomeVersion, int entityKey,
                     String entityObjectId, String entityName, String entityIcon,
                     TransmitHandler transmitHandler) {
        this.nodeName = nodeName;
        this.friendlyName = friendlyName;
        this.macAddress = macAddress;
        this.model = model;
        this.manufacturer = manufacturer;
        this.esphomeVersion = esphomeVersion;
        this.entityKey = entityKey;
        this.entityObjectId = entityObjectId;
        this.entityName = entityName;
        this.entityIcon = entityIcon;
        this.transmitHandler = transmitHandler;
    }

    void configure(String listenAddress, int tcpPort) {
        this.listenAddress = (listenAddress == null || listenAddress.trim().isEmpty())
                ? "0.0.0.0" : listenAddress.trim();
        this.tcpPort = tcpPort;
    }

    int getTcpPort() {
        return tcpPort;
    }

    /** Port actually bound (may differ from the configured one after collision avoidance). */
    int getBoundPort() {
        return boundPort > 0 ? boundPort : tcpPort;
    }

    boolean isRunning() {
        return running.get();
    }

    void onTransmit(int carrierFrequency, int[] timings, int repeatCount) {
        if (transmitHandler != null) {
            transmitHandler.onTransmit(carrierFrequency, timings, repeatCount);
        }
    }

    /** Binds synchronously (with port-collision avoidance) then serves on a background thread. */
    boolean start() {
        final ServerSocket sock;
        synchronized (lifecycleLock) {
            if (running.get()) {
                return true;
            }
            stopRequested = false;
            ServerSocket bound = bindWithFallback();
            if (bound == null) {
                Log.e(TAG, "could not bind any port in " + tcpPort + ".." + (tcpPort + PORT_SCAN_RANGE)
                        + " (Ava's ESPHome API may be occupying the range)");
                return false;
            }
            serverSocket = bound;
            boundPort = bound.getLocalPort();
            running.set(true);
            sock = bound;
        }
        if (boundPort != tcpPort) {
            Log.w(TAG, "tcp_port " + tcpPort + " busy (likely Ava's own ESPHome API); bound "
                    + boundPort + " instead");
        }
        Log.i(TAG, "ESPHome API server listening on " + listenAddress + ":" + boundPort);
        acceptExecutor.execute(new Runnable() {
            @Override
            public void run() {
                runAcceptLoop(sock);
            }
        });
        return true;
    }

    void stop() {
        stopRequested = true;
        ServerSocket socket;
        synchronized (lifecycleLock) {
            socket = serverSocket;
            serverSocket = null;
            running.set(false);
            boundPort = -1;
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
        start();
    }

    /** Tries the configured port first, then successive ports, so it never clashes with Ava. */
    private ServerSocket bindWithFallback() {
        for (int offset = 0; offset <= PORT_SCAN_RANGE; offset++) {
            int p = tcpPort + offset;
            if (p > 65535) {
                break;
            }
            ServerSocket s = null;
            try {
                s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(listenAddress, p));
                return s;
            } catch (BindException e) {
                closeQuietly(s);
            } catch (Exception e) {
                closeQuietly(s);
                Log.w(TAG, "bind " + listenAddress + ":" + p + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    private void runAcceptLoop(ServerSocket sock) {
        try {
            while (!stopRequested && isOwnedSocket(sock)) {
                try {
                    Socket client = sock.accept();
                    sessionExecutor.execute(new EsphomeConnection(client, this));
                } catch (Exception e) {
                    if (!stopRequested && isOwnedSocket(sock)) {
                        Log.e(TAG, "accept failed", e);
                    }
                }
            }
        } finally {
            running.set(false);
            closeQuietly(sock);
            synchronized (lifecycleLock) {
                if (serverSocket == sock) {
                    serverSocket = null;
                }
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isOwnedSocket(ServerSocket socket) {
        synchronized (lifecycleLock) {
            return serverSocket == socket;
        }
    }
}
