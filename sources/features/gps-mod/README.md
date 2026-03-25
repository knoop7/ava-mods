# GPS Mod

A GPS location tracking module for [Ava](https://github.com/knoop7/Ava) with real-time updates and reverse geocoding.

## Features

- Real-time GPS location tracking
- Configurable update intervals and distance thresholds
- Automatic provider selection (GPS/Network)
- Reverse geocoding via OpenStreetMap Nominatim
- Location history caching
- Permission checking and fallback handling

## Requirements

- Android 5.0+ (API 21)
- Location permission (ACCESS_FINE_LOCATION)
- GPS or network location provider

## API

```java
GpsManager manager = GpsManager.getInstance(context);

// Start tracking
manager.startUpdates();
manager.startUpdates(5000L, 1.0f); // 5s, 1m

// Stop tracking
manager.stopUpdates();

// Current location
Location loc = manager.getCurrentLocation();
double lat = manager.getLatitude();
double lon = manager.getLongitude();
double alt = manager.getAltitude();
float speed = manager.getSpeed();
float accuracy = manager.getAccuracy();
long time = manager.getLastUpdateTime();

// Address lookup
String address = manager.reverseGeocode(lat, lon);
```

## Configuration

- `updateIntervalMs` - Update interval in ms (default: 5000)
- `minDistanceMeters` - Minimum movement to trigger update (default: 1.0)
- `provider` - GPS_PROVIDER or NETWORK_PROVIDER (auto-select)

## Implementation

Uses Android LocationManager with LocationListener for real-time updates. Nominatim API for reverse geocoding (no API key required). Thread-safe singleton with background executor.

## Build

```bash
cd sources/features/gps-mod
./build.sh
```

Outputs `libs/gps-manager.jar`.

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.
