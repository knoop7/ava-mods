# WiFi & ADB Keep-Alive

Prevent accidental loss of WiFi connectivity and ADB authorization on headless or kiosk-style Ava devices. All features are **off by default**.

## Two switches (mod settings only)

| Switch | Default | Purpose |
|--------|---------|---------|
| WiFi Keep-Alive | Off | Keep the WiFi radio on when something turns it off |
| ADB Keep-Alive | Off | Keep USB/wireless ADB on and preserve host authorization |

No Home Assistant entities — control everything from **Ava mod settings**.

## Behaviour

### WiFi

- **Listens** for `WIFI_STATE_CHANGED` — turns the radio back on if the WiFi switch is turned off
- **Polls** every 10 seconds as backup
- **Does not** force reconnect or call `cmd wifi connect-network` — Android reconnects to saved networks on its own

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
3. Connect WiFi and authorize ADB once while the guards are active.
