package com.ava.mods.gps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GpsManager implements LocationListener {

    private static final String TAG = "GpsManager";
    private static final long DEFAULT_UPDATE_INTERVAL_MS = 5000L;
    private static final float DEFAULT_MIN_DISTANCE_METERS = 1.0f;
    private static final String DEFAULT_PROVIDER = LocationManager.GPS_PROVIDER;
    private static volatile GpsManager instance;

    private final Context context;
    private final LocationManager locationManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile String provider = DEFAULT_PROVIDER;
    private volatile long updateIntervalMs = DEFAULT_UPDATE_INTERVAL_MS;
    private volatile float minDistanceMeters = DEFAULT_MIN_DISTANCE_METERS;

    private double latitude = 0.0;
    private double longitude = 0.0;
    private double altitude = 0.0;
    private float speed = 0.0f;
    private float accuracy = 0.0f;
    private long lastUpdateTime = 0L;

    private GpsManager(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        startLocationUpdates();
        scheduleIpLocationFallback();
    }

    public static GpsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (GpsManager.class) {
                if (instance == null) {
                    instance = new GpsManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }

        boolean needsRestart = false;
        switch (key) {
            case "provider": {
                String normalized = normalizeProvider(value);
                if (!normalized.equals(provider)) {
                    provider = normalized;
                    needsRestart = true;
                }
                break;
            }
            case "update_interval_seconds": {
                long parsed = parseLong(value, 5L);
                long intervalMs = Math.max(1000L, parsed * 1000L);
                if (intervalMs != updateIntervalMs) {
                    updateIntervalMs = intervalMs;
                    needsRestart = true;
                }
                break;
            }
            case "min_distance_meters": {
                float parsed = Math.max(0.0f, parseFloat(value, DEFAULT_MIN_DISTANCE_METERS));
                if (parsed != minDistanceMeters) {
                    minDistanceMeters = parsed;
                    needsRestart = true;
                }
                break;
            }
            default:
                break;
        }

        if (needsRestart) {
            restartLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted");
            return;
        }

        try {
            String activeProvider = resolveProvider();
            if (activeProvider == null) {
                Log.w(TAG, "No enabled location provider available");
                return;
            }

            locationManager.requestLocationUpdates(
                    activeProvider,
                    updateIntervalMs,
                    minDistanceMeters,
                    this,
                    Looper.getMainLooper()
            );

            Location lastKnown = getLastKnownLocation(activeProvider);
            if (lastKnown != null) {
                onLocationChanged(lastKnown);
            }
            Log.d(TAG, "Location updates started with provider=" + activeProvider
                    + " intervalMs=" + updateIntervalMs
                    + " minDistanceMeters=" + minDistanceMeters);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start location updates", e);
        }
    }

    private void restartLocationUpdates() {
        stopLocationUpdates();
        startLocationUpdates();
    }

    public void refreshLocation() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot refresh location without permission");
            return;
        }

        try {
            String activeProvider = resolveProvider();
            Location lastKnown = getLastKnownLocation(activeProvider);
            if (lastKnown != null) {
                onLocationChanged(lastKnown);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh location", e);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        altitude = location.getAltitude();
        speed = location.getSpeed();
        accuracy = location.getAccuracy();
        lastUpdateTime = System.currentTimeMillis();
        Log.d(TAG, "Location updated: " + latitude + ", " + longitude);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {
        Log.d(TAG, "Provider enabled: " + provider);
        restartLocationUpdates();
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.d(TAG, "Provider disabled: " + provider);
        restartLocationUpdates();
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getAltitude() {
        return altitude;
    }

    public float getSpeed() {
        return speed;
    }

    public float getAccuracy() {
        return accuracy;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void stopLocationUpdates() {
        try {
            locationManager.removeUpdates(this);
            Log.d(TAG, "Location updates stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop location updates", e);
        }
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String resolveProvider() {
        List<String> preferredProviders = Arrays.asList(
                provider,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        );

        for (String candidate : preferredProviders) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            try {
                if (locationManager.isProviderEnabled(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Location getLastKnownLocation(String activeProvider) {
        List<String> providers = Arrays.asList(
                activeProvider,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        );

        for (String candidate : providers) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            try {
                Location location = locationManager.getLastKnownLocation(candidate);
                if (location != null) {
                    return location;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Missing permission for provider=" + candidate, e);
                return null;
            } catch (Exception e) {
                Log.w(TAG, "Failed to get last known location for provider=" + candidate, e);
            }
        }
        return null;
    }

    private String normalizeProvider(String value) {
        String normalized = value.trim().toLowerCase();
        switch (normalized) {
            case "gps":
                return LocationManager.GPS_PROVIDER;
            case "network":
                return LocationManager.NETWORK_PROVIDER;
            case "passive":
                return LocationManager.PASSIVE_PROVIDER;
            default:
                return DEFAULT_PROVIDER;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void scheduleIpLocationFallback() {
        mainHandler.postDelayed(() -> {
            if (latitude == 0.0 && longitude == 0.0) {
                Log.d(TAG, "No GPS data, trying IP location...");
                requestIpLocation();
            }
            scheduleIpLocationFallback();
        }, 15000);
    }

    private void requestIpLocation() {
        executor.execute(() -> {
            try {
                URL url = new URL("http://ip-api.com/json/?fields=status,lat,lon,city");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject resp = new JSONObject(sb.toString());
                    if ("success".equals(resp.optString("status"))) {
                        double lat = resp.optDouble("lat", 0.0);
                        double lon = resp.optDouble("lon", 0.0);
                        String city = resp.optString("city", "");

                        if (lat != 0.0 && lon != 0.0) {
                            mainHandler.post(() -> {
                                latitude = lat;
                                longitude = lon;
                                accuracy = 5000.0f;
                                lastUpdateTime = System.currentTimeMillis();
                                Log.d(TAG, "IP location: " + lat + ", " + lon + " (" + city + ")");
                            });
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "IP location error", e);
            }
        });
    }
}
