package com.ava.mods.irblaster;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Serialised mDNS (_esphomelib._tcp) registration for the IR blaster.
 *
 * Ava's main app registers its OWN _esphomelib._tcp service through the same system
 * {@link NsdManager}. NsdManager operations are asynchronous and capped, so churning
 * register/unregister here (config toggles, port changes, restarts) can overlap the main
 * app's operations and trip {@code FAILURE_MAX_LIMIT}. This class therefore keeps a single
 * "desired" target and a tiny state machine driven by the registration callbacks: at most one
 * operation is ever in flight, requested changes are coalesced, and a max-limit failure is
 * retried with backoff — so the mod never fights the host's mDNS.
 */
final class EsphomeNsd {

    private static final String TAG = "IrBlaster";
    private static final String SERVICE_TYPE = "_esphomelib._tcp";
    private static final long RETRY_DELAY_MS = 1500L;
    private static final int MAX_RETRIES = 5;

    private enum State { IDLE, REGISTERING, REGISTERED, UNREGISTERING }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener activeListener;
    private State state = State.IDLE;

    // Desired target: null = should be unregistered; non-null = should be registered with these.
    private NsdServiceInfo desired;
    private String desiredKey;
    private String appliedKey;
    private int retries;

    EsphomeNsd(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void register(String serviceName, int port, String macNoColons, String version,
                               String friendlyName) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        setAttr(info, "mac", macNoColons);
        setAttr(info, "version", version);
        setAttr(info, "platform", "ava");
        setAttr(info, "board", "ava-ir");
        setAttr(info, "network", "wifi");
        setAttr(info, "friendly_name", friendlyName);

        desired = info;
        desiredKey = serviceName + "|" + port + "|" + macNoColons;
        retries = 0;
        reconcile();
    }

    synchronized void unregister() {
        desired = null;
        desiredKey = null;
        retries = 0;
        reconcile();
    }

    /** Moves one step toward the desired state; no-op while an async op is in flight. */
    private synchronized void reconcile() {
        if (nsdManager == null) {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                Log.w(TAG, "NSD service unavailable");
                return;
            }
        }
        switch (state) {
            case IDLE:
                if (desired != null) {
                    doRegister();
                }
                break;
            case REGISTERED:
                if (desired == null || !equalsStr(desiredKey, appliedKey)) {
                    // Need to drop the current registration (either stop, or re-register anew).
                    doUnregister();
                }
                break;
            case REGISTERING:
            case UNREGISTERING:
                // Busy — the callback will call reconcile() again.
                break;
            default:
                break;
        }
    }

    private void doRegister() {
        final NsdServiceInfo info = desired;
        final String key = desiredKey;
        if (info == null) {
            return;
        }
        activeListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                synchronized (EsphomeNsd.this) {
                    state = State.REGISTERED;
                    appliedKey = key;
                    retries = 0;
                    Log.i(TAG, "mDNS registered: " + nsdServiceInfo.getServiceName()
                            + " (" + key + ")");
                    reconcile();
                }
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (EsphomeNsd.this) {
                    state = State.IDLE;
                    appliedKey = null;
                    Log.w(TAG, "mDNS registration failed: " + errorCode + " (retry " + retries + ")");
                    scheduleRetryLocked();
                }
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                synchronized (EsphomeNsd.this) {
                    state = State.IDLE;
                    appliedKey = null;
                    reconcile();
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (EsphomeNsd.this) {
                    state = State.IDLE;
                    appliedKey = null;
                    Log.w(TAG, "mDNS unregistration failed: " + errorCode);
                    reconcile();
                }
            }
        };
        try {
            state = State.REGISTERING;
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, activeListener);
        } catch (Exception e) {
            state = State.IDLE;
            Log.w(TAG, "registerService threw: " + e.getMessage());
            scheduleRetryLocked();
        }
    }

    private void doUnregister() {
        if (activeListener == null) {
            state = State.IDLE;
            appliedKey = null;
            reconcile();
            return;
        }
        try {
            state = State.UNREGISTERING;
            nsdManager.unregisterService(activeListener);
        } catch (Exception e) {
            // Listener may not be registered yet; treat as idle and continue.
            state = State.IDLE;
            appliedKey = null;
            Log.w(TAG, "unregisterService threw: " + e.getMessage());
            reconcile();
        }
    }

    private void scheduleRetryLocked() {
        if (desired == null || retries >= MAX_RETRIES) {
            if (desired != null) {
                Log.w(TAG, "mDNS giving up after " + retries + " retries");
            }
            return;
        }
        retries++;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                reconcile();
            }
        }, RETRY_DELAY_MS * retries);
    }

    private static void setAttr(NsdServiceInfo info, String key, String value) {
        if (value == null) {
            return;
        }
        try {
            info.setAttribute(key, value);
        } catch (Exception ignored) {
        }
    }

    private static boolean equalsStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
