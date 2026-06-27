# WiFi & ADB Keep-Alive

Prevent accidental loss of WiFi connectivity and ADB authorization on headless or kiosk-style Ava devices. All features are **off by default**.

## Two switches (mod settings only)

| Switch | Default | Purpose |
|--------|---------|---------|
| WiFi Keep-Alive | Off | Keep WiFi on and reconnect to the last known network |
| ADB Keep-Alive | Off | Keep USB/wireless ADB on and preserve host authorization |

No Home Assistant entities — control everything from **Ava mod settings**. The manager bootstraps via Ava's `ModDeviceSupport` path and reads `mod_configs/connectivity-keepalive.json` directly (Ava only calls `applyConfig` when HA entities exist).

## Behaviour

### WiFi

- **Listens** for `WIFI_STATE_CHANGED` and network disconnect — reacts immediately when WiFi is turned off
- **Polls** every 10 seconds as backup
- Records the last connected SSID and network ID, then reconnects automatically
- Restores guard state on mod load without waiting for service restart

### ADB

- **Observes** `adb_enabled` / `adb_wifi_enabled` — reacts immediately when ADB is turned off
- **Polls** every 10 seconds as backup
- Preserves authorization keys via root/Shizuku

### Safety

This mod **never** turns WiFi or ADB off. Disabling a switch only stops monitoring.

## Privileged access

Shizuku authorization is requested **once per Ava process** when a guard starts.

## Permissions

Declares **no runtime permissions** — uses root or Shizuku shell for WiFi/ADB maintenance.

## Build

```bash
cd sources/features/connectivity-keepalive
chmod +x build.sh
./build.sh
```

Release artifacts are copied to `mods/features/connectivity-keepalive/`.

Every `./build.sh` run keeps these **4 places** in sync:

| # | Path |
|---|------|
| 1 | `sources/features/connectivity-keepalive/manifest.json` |
| 2 | `sources/features/connectivity-keepalive/libs/*.jar` |
| 3 | `mods/features/connectivity-keepalive/manifest.json` + `libs/*.jar` |
| 4 | `store.json` entry (`version` + `jar_hash`) |

## Usage

1. Install and enable the mod in Ava.
2. Open mod settings; turn on **WiFi Keep-Alive** and/or **ADB Keep-Alive**.
3. Connect WiFi and authorize ADB once so the mod can snapshot state.
