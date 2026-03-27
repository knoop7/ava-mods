# Allwinner A64 Custom

Device compatibility mod for Allwinner A64 chip devices.

## Supported Devices

- Ococci QUAD-CORE A64 tablets
- Other Allwinner A64/sun50i based smart screens

## Features

- **Device Detection**: Auto-detect A64 devices via cpuinfo, model, board, hardware
- **GPIO AEC Control**: Audio echo cancellation via GPIO116
- **Physical Key Handling**: Volume mute, menu key with long press brightness toggle
- **Screen Control**: Brightness control via root commands
- **CPU Core Management**: Enable all 4 cores for performance
- **Bluetooth Hooks**: Low-end BLE chip quirks (passive scan broken, batch scan broken)
- **Volume Control**: Volume up/down with custom UI

## Resources

The following resources are included for volume overlay UI:
- `res/drawable/sound_low.png`
- `res/drawable/sound_medium.png`
- `res/drawable/sound_high.png`

## Device Compatibility Hooks

This mod provides the following hooks used by Ava core:

| Method | Description |
|--------|-------------|
| `isSupported()` | Returns true for A64 devices |
| `getMinBrightness()` | Returns 10 (minimum safe brightness) |
| `isLowEndBleChip()` | Returns true (A64 has limited BLE) |
| `grantOverlayPermissionIfNeeded()` | Grants overlay via root appops |

## Bluetooth Hook Methods

| Method | Description |
|--------|-------------|
| `isBluetoothHookSupported()` | Returns true for A64 devices |
| `getChipVendor()` | Returns "Allwinner" |
| `getChipModel()` | Returns "A64" |
| `getMaxRealConnections()` | Returns 3 |
| `getScannerQuirks()` | Returns PASSIVE_SCAN_BROKEN, BATCH_SCAN_BROKEN, NEEDS_LOCATION_ENABLED |

## Build

```bash
chmod +x build.sh
./build.sh
```

Then copy to release directory:

```bash
cp libs/a64-device-support.jar ../../mods/devices/a64-device-support/libs/
cp manifest.json ../../mods/devices/a64-device-support/
```
