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

This mod alone is not enough. Ava main app must provide:

- `ModBleAdvProxyBridge` with `"ble_adv_proxy": true` manifest opt-in
- `BleAdvHostApi` (`fireHomeassistantEvent`, `runExclusiveTransmit`)
- `BleOperationCoordinator` (pause proxy scan / presence ADV during transmit)
- ESPHome services: `setup_svc_v0`, `adv_svc`, `adv_svc_v1` (always)
- Optional diagnostic: `ble_adv_proxy_name` when **Show Adapter Name Sensor** is enabled in mod config
- `SubscribeHomeassistantServicesRequest` handling

## Build

```bash
cd sources/features/ble-adv-proxy
./build.sh
```

Output: `mods/features/ble-adv-proxy/` + `libs/ble-adv-proxy-manager.jar`

## Android limits

ESP32 uses `esp_ble_gap_config_adv_data_raw()`. Android maps raw AD bytes to `AdvertiseData` (manufacturer / service data). Most ceiling-fan protocols work; exotic layouts may need device-specific hooks.
