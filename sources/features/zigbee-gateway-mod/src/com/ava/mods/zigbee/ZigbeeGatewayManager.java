package com.ava.mods.zigbee;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
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
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final List<String> serialPortCandidates = Arrays.asList(
            "/dev/ttyS5",
            "/dev/ttyUSB0",
            "/dev/ttyACM0",
            "/dev/ttyAMA0",
            "/dev/ttyS4",
            "/dev/ttyS3"
    );

    private volatile String serialPort = "/dev/ttyS5";
    private volatile int baudrate = 115200;
    private volatile int tcpPort = 8888;
    private volatile String listenAddress = "0.0.0.0";
    private volatile boolean autoStart = true;
    private volatile boolean rtsctsFlow = false;

    private final AtomicBoolean serverRunning = new AtomicBoolean(false);
    private final AtomicBoolean serverStarting = new AtomicBoolean(false);
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);

    private ServerSocket serverSocket;
    private FileInputStream serialIn;
    private FileOutputStream serialOut;
    private volatile boolean stopRequested = false;

    private ZigbeeGatewayManager(Context context) {
        this.context = context.getApplicationContext();
        String detectedSerialPort = detectSerialPort();
        if (detectedSerialPort != null) {
            serialPort = detectedSerialPort;
        }
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
                    needsRestart = isServerRunning();
                }
                break;
            case "baudrate":
                int newBaud = parseInt(value, 115200);
                if (newBaud != baudrate) {
                    baudrate = newBaud;
                    needsRestart = isServerRunning();
                }
                break;
            case "tcp_port":
                int newPort = parseInt(value, 8888);
                if (newPort != tcpPort) {
                    tcpPort = newPort;
                    needsRestart = isServerRunning();
                }
                break;
            case "listen_address":
                if (!value.equals(listenAddress)) {
                    listenAddress = value.trim();
                    needsRestart = isServerRunning();
                }
                break;
            case "auto_start":
                autoStart = "true".equalsIgnoreCase(value);
                if (autoStart && !isServerRunning()) {
                    startServer();
                }
                break;
            case "rtscts_flow":
                boolean newFlow = "true".equalsIgnoreCase(value);
                if (newFlow != rtsctsFlow) {
                    rtsctsFlow = newFlow;
                    needsRestart = isServerRunning();
                }
                break;
            default:
                break;
        }

        if (needsRestart) {
            restartServer();
        }
    }

    public boolean isSupported() {
        return detectSerialPort() != null;
    }

    public boolean isSupported(Context context) {
        return isSupported();
    }

    public void startServer() {
        synchronized (lifecycleLock) {
            if (hasActiveServerLocked()) {
                serverRunning.set(true);
                Log.d(TAG, "Server already running");
                return;
            }
            if (serverStarting.get()) {
                Log.d(TAG, "Server start already in progress");
                return;
            }
            stopRequested = false;
            serverStarting.set(true);
        }

        executor.execute(this::runServerLoop);
    }

    public void stopServer() {
        stopRequested = true;
        serverStarting.set(false);
        releaseOwnedResources(null, null, null);
        Log.i(TAG, "Server stopped");
    }

    public void restartServer() {
        if (!restartInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Restart already in progress");
            return;
        }
        executor.execute(() -> {
            try {
                stopServer();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                stopRequested = false;
                startServer();
            } finally {
                restartInProgress.set(false);
            }
        });
    }

    private void runServerLoop() {
        ServerSocket localServerSocket = null;
        FileInputStream localSerialIn = null;
        FileOutputStream localSerialOut = null;
        try {
            localServerSocket = new ServerSocket();
            localServerSocket.setReuseAddress(true);
            localServerSocket.bind(new InetSocketAddress(listenAddress, tcpPort));

            SerialConnection connection = openSerialPort();
            if (connection == null) {
                Log.e(TAG, "Failed to open serial port: " + serialPort);
                return;
            }
            localSerialIn = connection.inputStream;
            localSerialOut = connection.outputStream;

            synchronized (lifecycleLock) {
                if (stopRequested) {
                    Log.d(TAG, "Stop requested during startup, aborting server start");
                    return;
                }
                if (hasActiveServerLocked()) {
                    serverRunning.set(true);
                    Log.d(TAG, "Another server instance became active during startup");
                    return;
                }
                serverSocket = localServerSocket;
                serialIn = localSerialIn;
                serialOut = localSerialOut;
                serverRunning.set(true);
                serverStarting.set(false);
            }

            Log.i(TAG, "Server started - Serial: " + serialPort + "@" + baudrate
                    + " TCP: " + listenAddress + ":" + tcpPort);

            startSerialReader(localSerialIn);

            while (!stopRequested && isOwnedServerSocket(localServerSocket)) {
                try {
                    Socket client = localServerSocket.accept();
                    handleClient(client);
                } catch (IOException e) {
                    if (!stopRequested && isOwnedServerSocket(localServerSocket)) {
                        Log.e(TAG, "Accept error", e);
                    }
                }
            }
        } catch (BindException e) {
            if (hasActiveServer()) {
                serverRunning.set(true);
                Log.w(TAG, "Skipped duplicate server start because an instance is already listening", e);
            } else {
                Log.e(TAG, "Server bind error", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Server error", e);
        } finally {
            synchronized (lifecycleLock) {
                serverStarting.set(false);
            }
            releaseOwnedResources(localServerSocket, localSerialIn, localSerialOut);
            closeQuietly(localServerSocket);
            closeQuietly(localSerialIn);
            closeQuietly(localSerialOut);
        }
    }

    private SerialConnection openSerialPort() {
        try {
            String detectedSerialPort = detectSerialPort();
            if (detectedSerialPort != null && !new File(serialPort).exists()) {
                serialPort = detectedSerialPort;
            }

            File device = new File(serialPort);
            if (!device.exists()) {
                Log.e(TAG, "Serial device not found: " + serialPort);
                return null;
            }

            Process process = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "chmod 666 " + serialPort
            });
            process.waitFor();

            if (!device.canRead() || !device.canWrite()) {
                Log.e(TAG, "No read/write permission for: " + serialPort);
                return null;
            }

            process = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "stty -F " + serialPort + " " + baudrate + " raw -echo"
            });
            process.waitFor();

            FileInputStream inputStream = new FileInputStream(device);
            FileOutputStream outputStream = new FileOutputStream(device);

            Log.d(TAG, "Serial port opened: " + serialPort + "@" + baudrate);
            return new SerialConnection(inputStream, outputStream);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open serial port", e);
            return null;
        }
    }

    private void startSerialReader(FileInputStream inputStream) {
        executor.execute(() -> {
            byte[] buffer = new byte[4096];
            while (!stopRequested && isOwnedSerialInput(inputStream)) {
                try {
                    if (inputStream == null) break;

                    int available = inputStream.available();
                    if (available > 0) {
                        int len = inputStream.read(buffer, 0, Math.min(available, buffer.length));
                        if (len > 0) {
                            bytesReceived.addAndGet(len);
                            broadcastToClients(buffer, len);
                        }
                    } else {
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    if (!stopRequested && isOwnedSerialInput(inputStream)) {
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
                    if (len > 0) {
                        FileOutputStream currentSerialOut;
                        synchronized (lifecycleLock) {
                            currentSerialOut = serialOut;
                        }
                        if (currentSerialOut != null) {
                            currentSerialOut.write(buffer, 0, len);
                            currentSerialOut.flush();
                            bytesSent.addAndGet(len);
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Client disconnected: " + e.getMessage());
            } finally {
                clients.remove(client);
                synchronized (lifecycleLock) {
                    int currentCount = clientCount.get();
                    clientCount.set(Math.max(0, currentCount - 1));
                }
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

    private boolean hasActiveServer() {
        synchronized (lifecycleLock) {
            return hasActiveServerLocked();
        }
    }

    private boolean hasActiveServerLocked() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    private boolean isOwnedServerSocket(ServerSocket socket) {
        synchronized (lifecycleLock) {
            return socket != null && socket == serverSocket && !socket.isClosed();
        }
    }

    private boolean isOwnedSerialInput(FileInputStream inputStream) {
        synchronized (lifecycleLock) {
            return inputStream != null && inputStream == serialIn;
        }
    }

    private void releaseOwnedResources(
            ServerSocket ownedServerSocket,
            FileInputStream ownedSerialIn,
            FileOutputStream ownedSerialOut) {
        ServerSocket serverSocketToClose = null;
        FileInputStream serialInToClose = null;
        FileOutputStream serialOutToClose = null;

        synchronized (lifecycleLock) {
            boolean releaseCurrent =
                    ownedServerSocket == null
                            || serverSocket == ownedServerSocket
                            || serialIn == ownedSerialIn
                            || serialOut == ownedSerialOut;
            if (!releaseCurrent) {
                return;
            }

            serverSocketToClose = serverSocket;
            serialInToClose = serialIn;
            serialOutToClose = serialOut;
            serverSocket = null;
            serialIn = null;
            serialOut = null;
            serverRunning.set(false);
        }

        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        clients.clear();
        clientCount.set(0);

        closeQuietly(serverSocketToClose);
        closeQuietly(serialInToClose);
        closeQuietly(serialOutToClose);
    }

    public boolean isServerRunning() {
        synchronized (lifecycleLock) {
            return serverStarting.get()
                    || serverRunning.get()
                    || (serverSocket != null && !serverSocket.isClosed());
        }
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

    private String detectSerialPort() {
        if (isUsableSerialPort(serialPort)) {
            return serialPort;
        }
        for (String candidate : serialPortCandidates) {
            if (isUsableSerialPort(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isUsableSerialPort(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        File file = new File(path);
        return file.exists() && !file.isDirectory();
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void closeQuietly(FileInputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {}
        }
    }

    private void closeQuietly(FileOutputStream outputStream) {
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException ignored) {}
        }
    }

    private static final class SerialConnection {
        private final FileInputStream inputStream;
        private final FileOutputStream outputStream;

        private SerialConnection(FileInputStream inputStream, FileOutputStream outputStream) {
            this.inputStream = inputStream;
            this.outputStream = outputStream;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
