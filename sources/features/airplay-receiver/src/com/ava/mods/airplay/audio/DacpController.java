package com.ava.mods.airplay.audio;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends DACP commands back to the AirPlay sender over HTTP.
 * Discovers {@code _dacp._tcp} via NSD browse (direct resolve often fails on Android).
 */
public final class DacpController {

    private static final String TAG = "DacpController";
    private static final String SERVICE_TYPE = "_dacp._tcp.";
    private static final long RESOLVE_RETRY_MS = 2500L;
    private static final int MAX_QUEUED = 8;

    private final NsdManager nsdManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Queue<String> pending = new ArrayDeque<>();

    private volatile ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile String dacpId = "";
    private volatile String activeRemote = "";
    private volatile String host = "";
    private volatile int port = 0;

    private NsdManager.DiscoveryListener discoveryListener;
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final Runnable retryResolve = new Runnable() {
        @Override
        public void run() {
            if (!host.isEmpty() || dacpId.isEmpty()) return;
            startDiscovery();
        }
    };

    public DacpController(Context ctx) {
        this.nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    }

    public String getDacpId() {
        return dacpId;
    }

    public String getActiveRemote() {
        return activeRemote;
    }

    public boolean isResolved() {
        return !host.isEmpty() && port > 0 && !activeRemote.isEmpty();
    }

    public void update(String dacpId, String activeRemote) {
        String id = dacpId != null ? dacpId : "";
        String remote = activeRemote != null ? activeRemote : "";
        boolean changed = !id.equals(this.dacpId) || !remote.equals(this.activeRemote);
        this.dacpId = id;
        this.activeRemote = remote;
        if (id.isEmpty()) return;
        if (changed) {
            host = "";
            port = 0;
        }
        if (host.isEmpty()) startDiscovery();
    }

    public void play() { send("/ctrl-int/1/play"); }
    public void pause() { send("/ctrl-int/1/pause"); }
    public void nextItem() { send("/ctrl-int/1/nextitem"); }
    public void prevItem() { send("/ctrl-int/1/previtem"); }
    public void volumeUp() { send("/ctrl-int/1/volumeup"); }
    public void volumeDown() { send("/ctrl-int/1/volumedown"); }
    public void muteToggle() { send("/ctrl-int/1/mutetoggle"); }
    public void beginFastForward() { send("/ctrl-int/1/beginff"); }
    public void beginRewind() { send("/ctrl-int/1/beginrew"); }
    public void playResume() { send("/ctrl-int/1/playresume"); }

    /** Seek sender to absolute position (ms). DACP {@code dacp.playingtime}. */
    public void seekTo(long positionMs) {
        long ms = Math.max(0L, positionMs);
        send("/ctrl-int/1/setproperty?dacp.playingtime=" + ms);
    }

    public void release() {
        mainHandler.removeCallbacks(retryResolve);
        stopDiscovery();
        synchronized (lock) {
            pending.clear();
        }
        try {
            exec.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    private void ensureExecutor() {
        ExecutorService e = exec;
        if (e != null && !e.isShutdown()) return;
        synchronized (lock) {
            if (exec == null || exec.isShutdown()) {
                exec = Executors.newSingleThreadExecutor();
            }
        }
    }

    private void startDiscovery() {
        if (nsdManager == null || dacpId.isEmpty()) return;
        // Prefer browse — fabricate+resolve often fails with FAILURE_INTERNAL_ERROR.
        if (!discovering.compareAndSet(false, true)) {
            tryDirectResolve();
            return;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "DACP discover start failed: " + errorCode);
                discovering.set(false);
                tryDirectResolve();
                scheduleRetry();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                discovering.set(false);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "DACP discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                discovering.set(false);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null) return;
                String name = serviceInfo.getServiceName();
                String want = "iTunes_Ctrl_" + dacpId;
                if (name == null) return;
                if (!name.equalsIgnoreCase(want) && !name.toLowerCase(Locale.US).contains(dacpId.toLowerCase(Locale.US))) {
                    return;
                }
                resolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // keep last known host until next update
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            // Also try direct resolve in parallel for devices that already know the name.
            tryDirectResolve();
            scheduleRetry();
        } catch (Exception e) {
            Log.w(TAG, "DACP discover error", e);
            discovering.set(false);
            tryDirectResolve();
            scheduleRetry();
        }
    }

    private void stopDiscovery() {
        NsdManager.DiscoveryListener dl = discoveryListener;
        discoveryListener = null;
        discovering.set(false);
        if (dl == null || nsdManager == null) return;
        try {
            nsdManager.stopServiceDiscovery(dl);
        } catch (Exception ignored) {
        }
    }

    private void tryDirectResolve() {
        if (dacpId.isEmpty() || nsdManager == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType("_dacp._tcp");
        info.setServiceName("iTunes_Ctrl_" + dacpId);
        resolve(info);
    }

    private void resolve(NsdServiceInfo info) {
        if (info == null || nsdManager == null) return;
        if (!resolving.compareAndSet(false, true)) return;
        try {
            nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo si, int code) {
                    resolving.set(false);
                    Log.w(TAG, "DACP resolve failed: " + code);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo si) {
                    resolving.set(false);
                    String h = si.getHost() != null ? si.getHost().getHostAddress() : null;
                    if (h == null || si.getPort() <= 0) return;
                    host = h;
                    port = si.getPort();
                    Log.i(TAG, "DACP resolved: " + host + ":" + port);
                    stopDiscovery();
                    mainHandler.removeCallbacks(retryResolve);
                    flushPending();
                }
            });
        } catch (Exception e) {
            resolving.set(false);
            Log.w(TAG, "DACP resolve error", e);
        }
    }

    private void scheduleRetry() {
        mainHandler.removeCallbacks(retryResolve);
        if (host.isEmpty() && !dacpId.isEmpty()) {
            mainHandler.postDelayed(retryResolve, RESOLVE_RETRY_MS);
        }
    }

    private void send(final String path) {
        if (activeRemote.isEmpty()) return;
        if (host.isEmpty() || port <= 0) {
            enqueue(path);
            startDiscovery();
            return;
        }
        dispatch(path);
    }

    private void enqueue(String path) {
        synchronized (lock) {
            if (pending.size() >= MAX_QUEUED) pending.poll();
            pending.offer(path);
        }
    }

    private void flushPending() {
        Queue<String> copy;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            copy = new ArrayDeque<>(pending);
            pending.clear();
        }
        for (String path : copy) dispatch(path);
    }

    private void dispatch(final String path) {
        ensureExecutor();
        try {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection conn = null;
                    try {
                        String url = "http://" + host + ":" + port + path;
                        conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Active-Remote", activeRemote);
                        conn.setRequestProperty("Host", host + ":" + port);
                        conn.setConnectTimeout(2000);
                        conn.setReadTimeout(2000);
                        int code = conn.getResponseCode();
                        try {
                            InputStream in = conn.getInputStream();
                            byte[] buf = new byte[1024];
                            while (in.read(buf) > 0) { /* drain */ }
                            in.close();
                        } catch (Exception ignored) {
                        }
                        if (code < 200 || code >= 300) {
                            Log.w(TAG, "DACP " + path + " -> HTTP " + code);
                        } else {
                            Log.i(TAG, "DACP ok " + path);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "DACP send failed: " + path, e);
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            ensureExecutor();
            Log.w(TAG, "DACP enqueue failed: " + path, e);
        }
    }
}
