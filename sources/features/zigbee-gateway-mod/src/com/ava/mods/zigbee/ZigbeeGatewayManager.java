package com.ava.mods.zigbee;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ZigbeeGatewayManager {

    private static final String TAG = "ZigbeeGatewayManager";
    private static volatile ZigbeeGatewayManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();

    private volatile String serialPort = "/dev/ttyUSB0";
    private volatile int baudrate = 115200;
    private volatile int tcpPort = 8888;
    private volatile String listenAddress = "0.0.0.0";
    private volatile boolean autoStart = true;
    private volatile boolean rtsctsFlow = false;

    private final AtomicBoolean serverRunning = new AtomicBoolean(false);
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);

    private ServerSocket serverSocket;
    private FileInputStream serialIn;
    private FileOutputStream serialOut;
    private FileDescriptor serialFd;
    private volatile boolean stopRequested = false;

    private ZigbeeGatewayManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static ZigbeeGatewayManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ZigbeeGatewayManager.class) {
                if (instance == null) {
                    instance = new ZigbeeGatewayManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) return;

        boolean needsRestart = false;
        switch (key) {
            case "serial_port":
                if (!value.equals(serialPort)) {
                    serialPort = value.trim();
                    needsRestart = serverRunning.get();
                }
                break;
            case "baudrate":
                int newBaud = parseInt(value, 115200);
                if (newBaud != baudrate) {
                    baudrate = newBaud;
                    needsRestart = serverRunning.get();
                }
                break;
            case "tcp_port":
                int newPort = parseInt(value, 8888);
                if (newPort != tcpPort) {
                    tcpPort = newPort;
                    needsRestart = serverRunning.get();
                }
                break;
            case "listen_address":
                if (!value.equals(listenAddress)) {
                    listenAddress = value.trim();
                    needsRestart = serverRunning.get();
                }
                break;
            case "auto_start":
                autoStart = "true".equalsIgnoreCase(value);
                if (autoStart && !serverRunning.get()) {
                    startServer();
                }
                break;
            case "rtscts_flow":
                boolean newFlow = "true".equalsIgnoreCase(value);
                if (newFlow != rtsctsFlow) {
                    rtsctsFlow = newFlow;
                    needsRestart = serverRunning.get();
                }
                break;
        }

        if (needsRestart) {
            restartServer();
        }
    }

    public void startServer() {
        if (serverRunning.get()) {
            Log.d(TAG, "Server already running");
            return;
        }

        executor.execute(() -> {
            try {
                if (!openSerialPort()) {
                    Log.e(TAG, "Failed to open serial port: " + serialPort);
                    return;
                }

                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(listenAddress, tcpPort));
                serverRunning.set(true);

                Log.i(TAG, "Server started - Serial: " + serialPort + "@" + baudrate 
                        + " TCP: " + listenAddress + ":" + tcpPort);

                startSerialReader();

                while (!stopRequested && serverRunning.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (IOException e) {
                        if (!stopRequested) {
                            Log.e(TAG, "Accept error", e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error", e);
            } finally {
                cleanup();
            }
        });
    }

    public void stopServer() {
        stopRequested = true;
        serverRunning.set(false);
        cleanup();
        Log.i(TAG, "Server stopped");
    }

    public void restartServer() {
        executor.execute(() -> {
            stopServer();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            stopRequested = false;
            startServer();
        });
    }

    private boolean openSerialPort() {
        try {
            File device = new File(serialPort);
            if (!device.exists()) {
                Log.e(TAG, "Serial device not found: " + serialPort);
                return false;
            }

            if (!device.canRead() || !device.canWrite()) {
                Log.e(TAG, "No read/write permission for: " + serialPort);
                return false;
            }

            Process process = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "chmod 666 " + serialPort
            });
            process.waitFor();

            process = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "stty -F " + serialPort + " " + baudrate + " raw -echo"
            });
            process.waitFor();

            serialIn = new FileInputStream(device);
            serialOut = new FileOutputStream(device);

            Log.d(TAG, "Serial port opened: " + serialPort + "@" + baudrate);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open serial port", e);
            return false;
        }
    }

    private void startSerialReader() {
        executor.execute(() -> {
            byte[] buffer = new byte[4096];
            while (!stopRequested && serverRunning.get()) {
                try {
                    if (serialIn == null) break;
                    
                    int available = serialIn.available();
                    if (available > 0) {
                        int len = serialIn.read(buffer, 0, Math.min(available, buffer.length));
                        if (len > 0) {
                            bytesReceived.addAndGet(len);
                            broadcastToClients(buffer, len);
                        }
                    } else {
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    if (!stopRequested) {
                        Log.e(TAG, "Serial read error", e);
                    }
                    break;
                }
            }
        });
    }

    private void handleClient(Socket client) {
        clients.add(client);
        clientCount.incrementAndGet();
        Log.i(TAG, "Client connected: " + client.getRemoteSocketAddress());

        executor.execute(() -> {
            try {
                InputStream in = client.getInputStream();
                byte[] buffer = new byte[4096];

                while (!stopRequested && !client.isClosed()) {
                    int len = in.read(buffer);
                    if (len < 0) break;
                    if (len > 0 && serialOut != null) {
                        serialOut.write(buffer, 0, len);
                        serialOut.flush();
                        bytesSent.addAndGet(len);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Client disconnected: " + e.getMessage());
            } finally {
                clients.remove(client);
                clientCount.decrementAndGet();
                try {
                    client.close();
                } catch (IOException ignored) {}
                Log.i(TAG, "Client removed, count: " + clientCount.get());
            }
        });
    }

    private void broadcastToClients(byte[] data, int len) {
        for (Socket client : clients) {
            try {
                if (!client.isClosed()) {
                    OutputStream out = client.getOutputStream();
                    out.write(data, 0, len);
                    out.flush();
                }
            } catch (Exception e) {
                Log.d(TAG, "Broadcast error to client", e);
            }
        }
    }

    private void cleanup() {
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        clients.clear();
        clientCount.set(0);

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }

        if (serialIn != null) {
            try {
                serialIn.close();
            } catch (IOException ignored) {}
            serialIn = null;
        }

        if (serialOut != null) {
            try {
                serialOut.close();
            } catch (IOException ignored) {}
            serialOut = null;
        }

        serverRunning.set(false);
    }

    public boolean isServerRunning() {
        return serverRunning.get();
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public String getSerialPort() {
        return serialPort;
    }

    public int getBaudrate() {
        return baudrate;
    }

    public int getClientCount() {
        return clientCount.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
