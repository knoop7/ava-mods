package com.ava.mods.irblaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.security.MessageDigest;

/**
 * IR Blaster device mod entry point.
 *
 * Auto-detects the device's infrared emitter(s) (built-in Consumer IR + USB/lirc) and,
 * when enabled, runs an ESPHome native-API server that advertises a single infrared
 * emitter entity. Home Assistant (2026.4+) discovers it as an ESPHome device and exposes
 * it on the infrared platform, so consumer integrations (LG, Samsung, Daikin, ...) can
 * transmit IR through this device.
 *
 * Ava calls applyConfig only when HA entities exist, so — like connectivity-keepalive —
 * we also bootstrap by reading mod_configs/<id>.json directly.
 */
public class IrBlasterManager {

    private static final String TAG = "IrBlaster";
    private static final String MOD_ID = "ir-blaster-support";
    private static final String PREFS = "ir_blaster_manager";

    private static final String KEY_ENABLE = "enable_server";
    private static final String KEY_PORT = "tcp_port";
    private static final String KEY_ADDR = "listen_address";
    private static final String KEY_MDNS = "mdns_enabled";

    // Default port avoids clashing with Ava's own ESPHome API (typically 6053).
    private static final int DEFAULT_PORT = 6054;
    private static final String DEFAULT_ADDR = "0.0.0.0";
    private static final String ESPHOME_VERSION = "2026.7.0";

    private static volatile IrBlasterManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final PrivilegedShell shell;
    private final IrCapabilityDetector detector;
    private final IrTransmitter transmitter;
    private final EsphomeNsd nsd;
    private final EsphomeApiServer server;

    private final String macColon;
    private final String macNoColon;

    private volatile boolean enableServer;
    private volatile int tcpPort = DEFAULT_PORT;
    private volatile String listenAddress = DEFAULT_ADDR;
    private volatile boolean mdnsEnabled = true;
    private volatile IrCapabilityDetector.Result lastResult;

    // Last-applied state so repeated lifecycle callbacks are idempotent (no re-detect / re-register spam).
    private boolean stateApplied;
    private boolean appliedEnable;
    private int appliedPort = -1;
    private String appliedAddr;
    private boolean appliedMdns;
    private boolean mdnsActive;

    private IrBlasterManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.shell = new PrivilegedShell(this.context);
        this.detector = new IrCapabilityDetector(this.context, shell);
        this.transmitter = new IrTransmitter(this.context);
        this.nsd = new EsphomeNsd(this.context);

        this.macColon = stableMac(true);
        this.macNoColon = stableMac(false);
        String nodeName = "ava-ir-" + macNoColon.substring(macNoColon.length() - 6);

        this.server = new EsphomeApiServer(
                nodeName,
                "Ava IR Blaster",
                macColon,
                "Ava IR Blaster",
                "Ava",
                ESPHOME_VERSION,
                stableEntityKey(),
                "ir_blaster",
                "IR Blaster",
                "mdi:remote",
                new EsphomeApiServer.TransmitHandler() {
                    @Override
                    public void onTransmit(int carrierFrequency, int[] timings, int repeatCount) {
                        transmitter.enqueue(carrierFrequency, timings, repeatCount);
                    }
                });

        bootstrapFromAvaConfig();
        Log.i(TAG, "manager ready node=" + nodeName + " enable=" + enableServer
                + " port=" + tcpPort);
    }

    public static IrBlasterManager getInstance(Context context) {
        IrBlasterManager current = instance;
        if (current == null) {
            synchronized (IrBlasterManager.class) {
                current = instance;
                if (current == null) {
                    current = new IrBlasterManager(context);
                    instance = current;
                }
            }
        } else {
            current.bootstrapFromAvaConfig();
        }
        return current;
    }

    /** Keeps the manager alive across restarts even without HA entities. */
    public boolean isSupported() {
        return true;
    }

    public boolean isSupported(Context context) {
        return true;
    }

    public boolean grantOverlayPermissionIfNeeded() {
        bootstrapFromAvaConfig();
        return false;
    }

    public boolean grantOverlayPermissionIfNeeded(Context context) {
        return grantOverlayPermissionIfNeeded();
    }

    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        nsd.unregister();
        server.stop();
        transmitter.shutdown();
        instance = null;
    }

    // ---- Ava-bridge diagnostic entities (optional, gated by show_diagnostic_entities) ----

    public boolean isServerRunning() {
        return server.isRunning();
    }

    public String getEmitterInfo() {
        IrCapabilityDetector.Result r = lastResult;
        return r != null ? r.summary() : "not detected";
    }

    public String getLastTransmit() {
        String err = transmitter.getLastError();
        if (err != null) {
            return "error: " + err;
        }
        long at = transmitter.getLastTransmitAt();
        if (at == 0) {
            return "none";
        }
        return "ok via " + transmitter.getLastPath()
                + " (" + transmitter.getTransmitCount() + " total)";
    }

    public void restartServer() {
        Log.i(TAG, "restartServer requested");
        applyServerState(true);
    }

    // ---- config ----

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        switch (key) {
            case KEY_ENABLE:
                enableServer = parseBoolean(value);
                prefs.edit().putBoolean(KEY_ENABLE, enableServer).apply();
                updateServer();
                break;
            case KEY_PORT:
                tcpPort = parseInt(value, DEFAULT_PORT);
                prefs.edit().putInt(KEY_PORT, tcpPort).apply();
                updateServer();
                break;
            case KEY_ADDR:
                listenAddress = value.trim().isEmpty() ? DEFAULT_ADDR : value.trim();
                prefs.edit().putString(KEY_ADDR, listenAddress).apply();
                updateServer();
                break;
            case KEY_MDNS:
                mdnsEnabled = parseBoolean(value);
                prefs.edit().putBoolean(KEY_MDNS, mdnsEnabled).apply();
                updateServer();
                break;
            default:
                break;
        }
    }

    private void bootstrapFromAvaConfig() {
        if (!syncFromAvaConfigStore()) {
            restorePersistedState();
        }
    }

    private boolean syncFromAvaConfigStore() {
        File configFile = new File(context.getFilesDir(), "mod_configs/" + MOD_ID + ".json");
        if (!configFile.exists()) {
            return false;
        }
        try {
            String json = readAll(configFile);
            if (json.trim().isEmpty()) {
                return false;
            }
            JSONObject root = new JSONObject(json);
            enableServer = root.has(KEY_ENABLE)
                    ? parseBoolean(root.getString(KEY_ENABLE))
                    : prefs.getBoolean(KEY_ENABLE, false);
            tcpPort = root.has(KEY_PORT)
                    ? parseInt(root.getString(KEY_PORT), DEFAULT_PORT)
                    : prefs.getInt(KEY_PORT, DEFAULT_PORT);
            listenAddress = root.has(KEY_ADDR) && !root.getString(KEY_ADDR).trim().isEmpty()
                    ? root.getString(KEY_ADDR).trim()
                    : prefs.getString(KEY_ADDR, DEFAULT_ADDR);
            mdnsEnabled = root.has(KEY_MDNS)
                    ? parseBoolean(root.getString(KEY_MDNS))
                    : prefs.getBoolean(KEY_MDNS, true);

            prefs.edit()
                    .putBoolean(KEY_ENABLE, enableServer)
                    .putInt(KEY_PORT, tcpPort)
                    .putString(KEY_ADDR, listenAddress)
                    .putBoolean(KEY_MDNS, mdnsEnabled)
                    .apply();

            updateServer();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "failed to read Ava mod config: " + e.getMessage());
            return false;
        }
    }

    private void restorePersistedState() {
        enableServer = prefs.getBoolean(KEY_ENABLE, false);
        tcpPort = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        listenAddress = prefs.getString(KEY_ADDR, DEFAULT_ADDR);
        mdnsEnabled = prefs.getBoolean(KEY_MDNS, true);
        updateServer();
    }

    private void updateServer() {
        applyServerState(false);
    }

    private synchronized void applyServerState(boolean forceRestart) {
        boolean serverParamsChanged = forceRestart
                || !stateApplied
                || appliedEnable != enableServer
                || appliedPort != tcpPort
                || !equalsStr(appliedAddr, listenAddress);
        boolean mdnsChanged = forceRestart
                || !stateApplied
                || appliedMdns != mdnsEnabled;
        if (!serverParamsChanged && !mdnsChanged) {
            return;
        }
        stateApplied = true;
        appliedEnable = enableServer;
        appliedPort = tcpPort;
        appliedAddr = listenAddress;
        appliedMdns = mdnsEnabled;

        if (!enableServer) {
            if (server.isRunning() || mdnsActive) {
                nsd.unregister();
                mdnsActive = false;
                server.stop();
                Log.i(TAG, "server stopped (disabled)");
            }
            return;
        }

        // Detect once and cache; only re-probe on an explicit restart.
        if (lastResult == null || forceRestart) {
            lastResult = detector.detect();
        }
        if (!lastResult.anyEmitter()) {
            Log.w(TAG, "no IR emitter detected — server not started");
            nsd.unregister();
            mdnsActive = false;
            server.stop();
            return;
        }

        // (Re)start the API server only when its own parameters changed; toggling mDNS alone must
        // not drop an active Home Assistant connection. The actual bound port (which may differ
        // from the configured one after collision avoidance) drives the mDNS advertisement.
        if (serverParamsChanged) {
            server.configure(listenAddress, tcpPort);
            if (server.isRunning()) {
                server.restart();
            } else {
                server.start();
            }
            // Bound port may have changed → the current mDNS record is stale.
            if (mdnsActive) {
                nsd.unregister();
                mdnsActive = false;
            }
        }

        if (!server.isRunning()) {
            Log.w(TAG, "API server not running — skipping mDNS registration");
            if (mdnsActive) {
                nsd.unregister();
                mdnsActive = false;
            }
            return;
        }

        if (mdnsEnabled) {
            if (!mdnsActive) {
                nsd.register(server.nodeName, server.getBoundPort(), macNoColon, ESPHOME_VERSION,
                        server.friendlyName);
                mdnsActive = true;
            }
        } else if (mdnsActive) {
            nsd.unregister();
            mdnsActive = false;
        }
    }

    private static boolean equalsStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- stable identity ----

    private String stableMac(boolean withColons) {
        byte[] mac = new byte[6];
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            String seed = (androidId != null ? androidId : "ava") + "|ir-blaster";
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8"));
            System.arraycopy(digest, 0, mac, 0, 6);
        } catch (Exception e) {
            for (int i = 0; i < 6; i++) {
                mac[i] = (byte) (0x10 + i);
            }
        }
        // Locally administered, unicast.
        mac[0] = (byte) ((mac[0] & 0xFE) | 0x02);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (withColons && i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", mac[i] & 0xFF));
        }
        return withColons ? sb.toString() : sb.toString().toLowerCase();
    }

    private int stableEntityKey() {
        int h = macNoColon.hashCode();
        if (h == 0) {
            h = 0x49520001;
        }
        return h;
    }

    // ---- helpers ----

    private static String readAll(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static boolean parseBoolean(String value) {
        String v = value.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
