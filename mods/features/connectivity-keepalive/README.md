# WiFi & ADB Keep-Alive

Prevent accidental loss of WiFi connectivity and ADB authorization on headless or kiosk-style Ava devices. All features are **off by default**.

## Four switches

| Switch | Location | Default | Purpose |
|--------|----------|---------|---------|
| WiFi Keep-Alive | Mod settings | Off | Expose the WiFi guard in Home Assistant |
| ADB Keep-Alive | Mod settings | Off | Expose the ADB guard in Home Assistant |
| WiFi Keep-Alive | Home Assistant | Off | Keep WiFi on and reconnect to the last known network |
| ADB Keep-Alive | Home Assistant | Off | Keep USB/wireless ADB on and preserve authorization keys |

Both layers must be enabled for a guard to run:

1. Turn on the feature in **Ava mod settings** (config switch).
2. Turn on the matching **Home Assistant** switch.

## Behaviour

### WiFi

- Records the last connected SSID and network ID while connected.
- Every 30 seconds, if WiFi was turned off, re-enables it.
- If WiFi is on but disconnected (crash, reboot, flaky driver), attempts reconnect to the saved network.
- Uses `WifiManager` when possible; falls back to `svc wifi`, `settings put`, and `cmd wifi` via privileged shell.

### ADB

- Keeps `adb_enabled` and developer settings on.
- Remembers whether wireless ADB was active and restores it.
- With root or Shizuku, verifies `/data/misc/adb/adb_keys` permissions so host authorization survives restarts.

### Safety

This mod **never** turns WiFi or ADB off. Disabling a guard only stops monitoring.

## Privileged access

Shell commands prefer **root**, then **Shizuku**. Without either, WiFi reconnect uses public APIs only and ADB key maintenance is skipped.

Install Shizuku and grant Ava permission, or use a rooted device, for full protection.

## Permissions

| Permission | Used for |
|------------|----------|
| `ACCESS_WIFI_STATE` | Read WiFi state |
| `CHANGE_WIFI_STATE` | Re-enable WiFi via API |
| `ACCESS_NETWORK_STATE` | Detect active WiFi connection |

## Build

```bash
cd sources/features/connectivity-keepalive
chmod +x build.sh
./build.sh
```

Release artifacts are copied to `mods/features/connectivity-keepalive/`.

## Usage

1. Install and enable the mod in Ava.
2. Open mod settings; enable **WiFi Keep-Alive** and/or **ADB Keep-Alive** as needed.
3. In Home Assistant, turn on the corresponding switches.
4. Connect WiFi and authorize ADB once while the guards are active so the mod can snapshot state.
