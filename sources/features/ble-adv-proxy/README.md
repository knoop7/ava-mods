# BLE ADV Proxy Mod

Android port of [esphome-ble_adv_proxy](https://github.com/NicoIIT/esphome-ble_adv_proxy) for Ava + [ha-ble-adv](https://github.com/NicoIIT/ha-ble-adv).

## Protocol surface (1:1 with ESP32 component)

| ESPHome service / event | Mod handler |
|-------------------------|-------------|
| `setup_svc_v0` | `onServiceCall` → `handleSetup` |
| `adv_svc` (legacy) | → `adv_svc_v1` with `repeat=3` |
| `adv_svc_v1` | queued raw ADV transmit |
| `esphome.ble_adv.raw_adv` event | `onScanResult` → `fireHomeassistantEvent` |
| `ble_adv_proxy_name` text_sensor (optional) | `getAdapterName()` — off by default; enable **Show Adapter Name Sensor** in mod config |

## Ava core requirements

**Standalone mode (default, `ble_adv_standalone: true`):**

- Mod owns `BluetoothLeScanner` scan + exclusive MGMT/HCI TX
- Ava `detect_enabled` stays off; host does not forward proxy scan
- Manifest declares `bluetooth_scan`, `bluetooth_connect`, `bluetooth_advertise` (granted on mod enable)
- `BleAdvHostApi` still used for `fireHomeassistantEvent` only

**Legacy integrated mode (`ble_adv_standalone: false`):**

- `ModBleAdvProxyBridge` unified proxy scan forwards to `onScanResult`
- `BleAdvHostApi` + `BleOperationCoordinator` pause host scan during transmit
- Ava Bluetooth detect must stay on

Both modes need:

- `ModBleAdvProxyBridge` with `"ble_adv_proxy": true` manifest opt-in
- ESPHome services: `setup_svc_v0`, `adv_svc`, `adv_svc_v1`
- `SubscribeHomeassistantServicesRequest` handling

## Permissions

Standalone scan/TX needs runtime BLE permissions. Ava grants them when you enable the mod
(Shizuku/root `pm grant` first, then system dialog). Manual adb:

```bash
./provision.sh com.example.ava
```

## Build

```bash
cd sources/features/ble-adv-proxy
./build.sh
```

Output: `mods/features/ble-adv-proxy/` + `libs/ble-adv-proxy-manager.jar`

## Raw ADV fidelity on Android

ESP32 transmits the full raw PDU verbatim via `esp_ble_gap_config_adv_data_raw()`.
ha-ble-adv sends the complete payload, e.g. `02 01 1A | 1B 03 <26 bytes>`:

- `02 01 1A` — a codec-specific **Flags AD** (value `0x19/0x1A/0x02/0x00`, not the standard `0x06`).
- `1B 03 …` — the real data carried inside an AD structure whose type is `0x03/0x05/0x07`
  (UUID lists used as a data container), `0x16` (service data) or `0xFF` (manufacturer data).

Android's public advertising API cannot emit an arbitrary raw PDU, so `RawAdvParser` maps
each data structure back to the framework calls that serialise the identical bytes:

| Raw AD type | Android mapping | Byte-exact? |
|-------------|-----------------|-------------|
| `0xFF` manufacturer | `addManufacturerData(le16 id, rest)` | yes |
| `0x16` service data 16 | `addServiceData(uuid16, rest)` | yes |
| `0x20 / 0x21` service data 32 / 128 | `addServiceData(uuid32/128, rest)` | yes |
| `0x02 / 0x03` 16-bit UUID list | split into `addServiceUuid(uuid16)` per pair | yes (even length) |
| `0x04 / 0x05` 32-bit UUID list | split into `addServiceUuid(uuid32)` | yes |
| `0x06 / 0x07` 128-bit UUID list | split into `addServiceUuid(uuid128)` | yes |
| `0x01` Flags | **dropped** — framework controls Flags | no |
| `0x08 / 0x09` name | not reproducible (Android uses its own name) | no |

Net result: every data structure is reproduced byte-for-byte; only the 3-byte Flags prefix is
lost. Most ble_adv fan / lamp devices match on the data structure and ignore Flags, so control
works without root.

Transmit timing: ha-ble-adv sends short per-burst windows (`duration` ~20-30ms) `repeat` times.
Android's LOW_LATENCY advertise interval is ~100ms, so the mod collapses the whole
`repeat x duration` budget into one continuous window (clamped 120-1800ms) to guarantee real
ADV events are emitted.

## True 1:1 (including Flags) — root path

Byte-exact reproduction of the **entire** PDU (Flags included) bypasses `AdvertiseData` and
injects the raw bytes through the kernel Bluetooth socket, exactly like ha-ble-adv's own
`BluetoothHCIAdapter`. Enable **Raw HCI (root, 1:1)** in the mod config to activate it.

Pipeline (`RawHciAdvertiser` + bundled `native/<abi>/ble_adv_hci` ELF):

1. The full raw PDU (Flags included, no re-encoding) is passed to a small native helper.
2. The helper opens an `AF_BLUETOOTH` socket and tries, in order:
   - **HCI raw** — `LE Set Advertising Parameters` (0x2006) → `LE Set Advertising Data` (0x2008,
     the 31-byte payload verbatim) → `LE Set Advertise Enable` (0x200A). Byte layouts mirror
     ha-ble-adv.
   - If the controller is owned by the stack (`enable` returns `0x0C Command Disallowed`), it
     falls back to **MGMT `Add Advertising` (0x003E)** with `flags=0` so the raw `adv_data`
     (Flags included) is emitted as-is, then `Remove Advertising` (0x003F) after the window.
3. On any failure (no root, no kernel HCI transport, SELinux denial) it reports back and the mod
   automatically falls back to the A-layer `AdvertiseData` path — nothing breaks.

Requirements & scope:

- **Root** (`su`, `CAP_NET_ADMIN`). Host `RootUtils` is reused for detection to avoid extra prompts.
- Only works on devices whose Bluetooth uses the **kernel HCI transport**. Phones that drive the
  controller entirely from userspace Fluoride via the vendor HAL have no kernel `hci0`, so both
  HCI-raw and MGMT paths are unavailable there — same hard limit as ha-ble-adv's HCI adapter.
- The helper ELF is compiled for arm64-v8a / armeabi-v7a / x86_64 / x86 and shipped inside the jar
  (`native/<abi>/ble_adv_hci`); it is extracted to the app data dir and executed via `su` from
  `/data/local/tmp`.
- Not runnable on a non-rooted device — the switch simply no-ops into the A-layer there.
