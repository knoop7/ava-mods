package com.ava.mods.hasttengine;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

final class WyomingNsd {
    private static final String TAG = "HaSttWyomingNsd";
    private static final String SERVICE_TYPE = "_wyoming._tcp";

    private final Context context;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private boolean registered;

    WyomingNsd(Context context) {
        this.context = context.getApplicationContext();
    }

    void register(String serviceName, int port) {
        unregister();
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.w(TAG, "NSD service unavailable");
            return;
        }

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);
        serviceInfo.setAttribute("name", "HA STT Engine");
        serviceInfo.setAttribute("description", "Local Wyoming ASR/STT server on Ava");

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                registered = true;
                Log.i(TAG, "mDNS registered: " + nsdServiceInfo.getServiceName() + " port " + port);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                registered = false;
                Log.w(TAG, "mDNS registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registered = false;
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS unregistration failed: " + errorCode);
            }
        };

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register mDNS service", e);
        }
    }

    void unregister() {
        if (nsdManager != null && registrationListener != null && registered) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister mDNS service", e);
            }
        }
        registered = false;
        registrationListener = null;
    }
}
