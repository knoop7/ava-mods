package com.ava.mods.airplay.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.Map;

public final class NsdServiceManager {

    private static final String TAG = "NsdServiceManager";

    private final Context ctx;
    private final NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.RegistrationListener raopRegistration;
    private NsdManager.RegistrationListener airplayRegistration;

    public NsdServiceManager(Context ctx) {
        this.ctx = ctx;
        this.nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    }

    public void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("airplay_mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    public void registerRaop(String serviceName, int port, Map<String, String> txtRecords) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType("_raop._tcp");
        info.setPort(port);
        if (txtRecords != null) {
            for (Map.Entry<String, String> e : txtRecords.entrySet()) {
                info.setAttribute(e.getKey(), e.getValue());
            }
        }

        raopRegistration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                Log.i(TAG, "RAOP registered: " + info.getServiceName());
            }
            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int code) {
                Log.e(TAG, "RAOP registration failed: " + code);
            }
            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                Log.i(TAG, "RAOP unregistered");
            }
            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int code) {
                Log.e(TAG, "RAOP unregister failed: " + code);
            }
        };
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, raopRegistration);
    }

    public void registerAirplay(String serviceName, int port, Map<String, String> txtRecords) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType("_airplay._tcp");
        info.setPort(port);
        if (txtRecords != null) {
            for (Map.Entry<String, String> e : txtRecords.entrySet()) {
                info.setAttribute(e.getKey(), e.getValue());
            }
        }

        airplayRegistration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                Log.i(TAG, "AirPlay registered: " + info.getServiceName());
            }
            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int code) {
                Log.e(TAG, "AirPlay registration failed: " + code);
            }
            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                Log.i(TAG, "AirPlay unregistered");
            }
            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int code) {
                Log.e(TAG, "AirPlay unregister failed: " + code);
            }
        };
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, airplayRegistration);
    }

    public void unregisterAll() {
        if (raopRegistration != null) {
            try { nsdManager.unregisterService(raopRegistration); } catch (Exception ignored) {}
            raopRegistration = null;
        }
        if (airplayRegistration != null) {
            try { nsdManager.unregisterService(airplayRegistration); } catch (Exception ignored) {}
            airplayRegistration = null;
        }
    }

    public void release() {
        unregisterAll();
        if (multicastLock != null) {
            multicastLock.release();
            multicastLock = null;
        }
    }
}
