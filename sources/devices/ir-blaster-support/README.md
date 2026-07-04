# IR Blaster (Home Assistant)

Turns the device's infrared emitter into a **native Home Assistant IR transmitter**.

The mod auto-detects the built-in Consumer IR blaster (and known USB IR dongles), then
runs a small **ESPHome native-API server** that advertises a single **infrared emitter
entity**. Home Assistant **2026.4+** discovers it as an ESPHome device and exposes it on
the new [infrared platform](https://www.home-assistant.io/integrations/infrared/), so
consumer integrations (LG, Samsung, Daikin, Edifier, …) can send IR commands through this
device — just like an ESPHome IR blaster or a Broadlink RM.

## How it works

```
Home Assistant  ──ESPHome native API (plaintext, TCP 6054)──▶  this mod
   infrared platform  ◀── ListEntitiesInfraredResponse (emitter)
   consumer integration ── InfraredRFTransmitRawTimings ──▶ ConsumerIrManager.transmit()
```

- Detection: `ConsumerIrManager.hasIrEmitter()` / `getCarrierFrequencies()`, plus
  `UsbManager` VID/PID matching and `/dev/lirc*` probing (root, best effort).
- Transmit: HA sends protocol-agnostic raw timings (µs, mark/space) + carrier frequency;
  the mod maps them to Android's `ConsumerIrManager.transmit(carrierFrequency, int[])`.
- Discovery: mDNS `_esphomelib._tcp`, or add manually in HA.

## Setup in Home Assistant

1. Enable the mod in Ava (**Settings → Mod Store**). Leave **Enable IR API Server** on.
2. In Home Assistant: **Settings → Devices & Services → Add Integration → ESPHome**.
   - Host: this device's LAN IP (or wait for auto-discovery).
   - Port: **6054** (the mod's default; change it in the mod config if needed).
   - Encryption key: **leave blank** (this build uses the plaintext API).
3. The device appears with an **IR Blaster** infrared emitter entity.
4. Add an IR-controlled device integration (e.g. *Samsung Infrared*, *LG Infrared*,
   *Edifier Infrared*) and pick this emitter when asked.

## Configuration

| Key | Default | Description |
|-----|---------|-------------|
| `enable_server` | `true` | Advertise the device as an ESPHome IR emitter |
| `tcp_port` | `6054` | Native API port (kept off Ava's own 6053) |
| `listen_address` | `0.0.0.0` | Bind address |
| `mdns_enabled` | `true` | Broadcast `_esphomelib._tcp` for auto-discovery |
| `show_diagnostic_entities` | `false` | Expose status/emitter/last-transmit/restart in HA |

## Requirements & limitations

- **Home Assistant 2026.4+** for the infrared platform (2026.7 adds the dedicated IR panel).
- **Consumer IR transmit only.** USB / lirc devices are detected and reported, but raw TX
  over lirc needs native ioctl access and is not implemented in this build.
- **`TRANSMIT_IR` permission** must be granted to the Ava host app. On MIUI/AOSP this is a
  normal install-time permission and generally works; if `Last IR Transmit` reports
  "TRANSMIT_IR permission missing in host app", the host APK does not declare it.
- Plaintext API only (no Noise encryption). Use on a trusted LAN. Noise support is a
  possible future addition.

## Build

```bash
./build.sh
```

Compiles to `libs/ir-blaster-support.jar`, copies the release package into
`mods/devices/ir-blaster-support/`, and updates `store.json`.
