package com.ava.mods.airplay.audio;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
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
 * Discovers {@code _dacp._tcp} via NSD browse; prefers the RTSP peer IP as host
 * (shairport-sync style) because iOS mDNS host is often unusable.
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
    /** RTSP/RAOP peer IP — preferred DACP host for iPhone. */
    private volatile String clientIp = "";
    private volatile String host = "";
    private volatile int port = 0;

    private NsdManager.DiscoveryListener discoveryListener;
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final Runnable retryResolve = new Runnable() {
        @Override
        public void run() {
            if (port > 0 && !effectiveHost().isEmpty()) return;
            if (dacpId.isEmpty()) return;
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
        return isUsableDacpHost(effectiveHost()) && port > 0 && !activeRemote.isEmpty();
    }

    public void update(String dacpId, String activeRemote) {
        update(dacpId, activeRemote, null);
    }

    public void update(String dacpId, String activeRemote, String clientIp) {
        String id = dacpId != null ? dacpId.trim() : "";
        String remote = activeRemote != null ? activeRemote.trim() : "";
        String ip = normalizeIp(clientIp);
        boolean idChanged = !id.equals(this.dacpId) || !remote.equals(this.activeRemote);
        this.dacpId = id;
        this.activeRemote = remote;
        if (!ip.isEmpty()) this.clientIp = ip;
        if (id.isEmpty()) return;
        if (idChanged) {
            // Keep clientIp; drop stale mDNS endpoint.
            host = "";
            port = 0;
        }
        // Never overwrite a good IPv4 host with link-local peer IP.
        if (port > 0 && isUsableDacpHost(host)) {
            flushPending();
            return;
        }
        if (port <= 0 || !isUsableDacpHost(effectiveHost())) startDiscovery();
    }

    // --- MA AirPlay + classic iTunes / iOS Music DACP ---
    public void play() { send("/ctrl-int/1/play"); }
    public void pause() { send("/ctrl-int/1/pause"); }
    public void playPause() { send("/ctrl-int/1/playpause"); }
    public void stop() { send("/ctrl-int/1/stop"); }
    public void nextItem() { send("/ctrl-int/1/nextitem"); }
    public void prevItem() { send("/ctrl-int/1/previtem"); }
    public void volumeUp() { send("/ctrl-int/1/volumeup"); }
    public void volumeDown() { send("/ctrl-int/1/volumedown"); }
    public void shuffleSongs() { send("/ctrl-int/1/shuffle_songs"); }

    // --- AirPlay/iTunes remote (NOT handled by MA AirPlay provider) ---
    public void muteToggle() { send("/ctrl-int/1/mutetoggle"); }
    public void beginFastForward() { send("/ctrl-int/1/beginff"); }
    public void beginRewind() { send("/ctrl-int/1/beginrew"); }
    public void playResume() { send("/ctrl-int/1/playresume"); }

    /**
     * Absolute seek via {@code dacp.playingtime}.
     * Music Assistant's AirPlay DACP server does <b>not</b> handle this path
     * (only logged); kept for iTunes / forked-daapd senders.
     */
    public void seekTo(long positionMs) {
        long ms = Math.max(0L, positionMs);
        Log.w(TAG, "DACP seek playingtime=" + ms
                + " (MA AirPlay ignores this; iTunes/daapd only)");
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

    private static String normalizeIp(String ip) {
        if (ip == null) return "";
        String s = ip.trim();
        if (s.isEmpty()) return "";
        // Strip IPv6 zone id ("fe80::1%wlan0") — HttpURLConnection cannot use it.
        int pct = s.indexOf('%');
        if (pct > 0) s = s.substring(0, pct);
        // Drop brackets if present.
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** IPv4 dotted-quad, or non-link-local IPv6. fe80:: without zone is unusable. */
    private static boolean isIpv4(String h) {
        if (h == null || h.isEmpty()) return false;
        int dots = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    private static boolean isLinkLocalV6(String h) {
        if (h == null) return false;
        String lower = h.toLowerCase(Locale.US);
        return lower.startsWith("fe80:");
    }

    private static boolean isUsableDacpHost(String h) {
        if (h == null || h.isEmpty()) return false;
        // Link-local IPv6 needs a zone id; we strip it for URLs → connect always fails.
        return !isLinkLocalV6(h);
    }

    /**
     * Prefer mDNS IPv4 (iPhone publishes this) over RTSP peer link-local IPv6.
     * Log evidence: mdns=192.168.0.87 peer=fe80::… — only IPv4 connects.
     */
    private String pickBestHost(String mdnsHost, String peer) {
        if (isIpv4(mdnsHost)) return mdnsHost;
        if (isIpv4(peer)) return peer;
        if (isUsableDacpHost(mdnsHost)) return mdnsHost;
        if (isUsableDacpHost(peer)) return peer;
        if (mdnsHost != null && !mdnsHost.isEmpty()) return mdnsHost;
        return peer != null ? peer : "";
    }

    private String effectiveHost() {
        if (isUsableDacpHost(host)) return host;
        if (isUsableDacpHost(clientIp)) return clientIp;
        return host; // may be empty; caller checks isUsable / port
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
                Log.i(TAG, "DACP discovery started for " + dacpId);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                discovering.set(false);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null) return;
                String name = serviceInfo.getServiceName();
                if (name == null || dacpId.isEmpty()) return;
                String want = "iTunes_Ctrl_" + dacpId;
                boolean exact = name.equalsIgnoreCase(want);
                boolean fuzzy = name.toLowerCase(Locale.US)
                        .contains(dacpId.toLowerCase(Locale.US));
                // Strict: only our session's DACP-ID (never arbitrary iTunes_Ctrl_*).
                if (!exact && !fuzzy) return;
                Log.i(TAG, "DACP service found: " + name);
                resolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // keep last known host until next update
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
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
                    String mdnsHost = null;
                    try {
                        InetAddress addr = si.getHost();
                        if (addr != null) mdnsHost = normalizeIp(addr.getHostAddress());
                    } catch (Exception ignored) {
                    }
                    int p = si.getPort();
                    if (p <= 0) return;
                    port = p;
                    host = pickBestHost(mdnsHost, clientIp);
                    Log.i(TAG, "DACP resolved: " + host + ":" + port
                            + " (mdns=" + mdnsHost + " peer=" + clientIp + ")");
                    if (!isUsableDacpHost(host)) {
                        Log.w(TAG, "DACP host unusable (need IPv4); keep discovering");
                        host = "";
                        port = 0;
                        scheduleRetry();
                        return;
                    }
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
        if ((port <= 0 || !isUsableDacpHost(effectiveHost())) && !dacpId.isEmpty()) {
            mainHandler.postDelayed(retryResolve, RESOLVE_RETRY_MS);
        }
    }

    private void send(final String path) {
        if (activeRemote.isEmpty()) {
            Log.w(TAG, "DACP skipped (no Active-Remote): " + path);
            return;
        }
        String h = effectiveHost();
        if (!isUsableDacpHost(h) || port <= 0) {
            Log.i(TAG, "DACP queue (unresolved): " + path
                    + " id=" + dacpId + " peer=" + clientIp + " host=" + host);
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
        final String h = effectiveHost();
        final int p = port;
        final String remote = activeRemote;
        if (h.isEmpty() || p <= 0 || remote.isEmpty()) {
            enqueue(path);
            return;
        }
        ensureExecutor();
        try {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection conn = null;
                    try {
                        String url = "http://" + formatHost(h) + ":" + p + path;
                        conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Active-Remote", remote);
                        conn.setRequestProperty("Host", formatHost(h) + ":" + p);
                        conn.setConnectTimeout(2500);
                        conn.setReadTimeout(2500);
                        int code = conn.getResponseCode();
                        try {
                            InputStream in = conn.getInputStream();
                            byte[] buf = new byte[1024];
                            while (in.read(buf) > 0) { /* drain */ }
                            in.close();
                        } catch (Exception ignored) {
                        }
                        if (code < 200 || code >= 300) {
                            Log.w(TAG, "DACP " + path + " -> HTTP " + code + " @ " + h + ":" + p);
                        } else {
                            Log.i(TAG, "DACP ok " + path + " @ " + h + ":" + p);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "DACP send failed: " + path + " @ " + h + ":" + p, e);
                        // Force rediscovery — iOS port often rotates per session.
                        host = "";
                        port = 0;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                startDiscovery();
                            }
                        });
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

    private static String formatHost(String h) {
        // Bracket IPv6 for URL / Host header.
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) return "[" + h + "]";
        return h;
    }
}
