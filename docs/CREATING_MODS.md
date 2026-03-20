# Creating Mods

This guide explains how to create a mod for Ava.

## Basic Structure

## Repository Layout

Use the repository in two layers:

- `sources/devices/...` and `sources/features/...` for source code and build scripts
- `mods/devices/...` and `mods/features/...` for release packages consumed by Ava

If your mod is only a simple manifest without source code, it can live directly in the release tree.

A mod consists of a single `manifest.json` file:

```
my-mod/
└── manifest.json
```

For hardware mods with native libraries:

```
my-mod/
├── manifest.json
└── libs/
    └── device-api.jar
```

## Manifest Format

### Minimal Example

```json
{
  "id": "my-mod",
  "name": "My Mod",
  "version": "1.0.0",
  "entities": [
    {
      "type": "switch",
      "id": "power",
      "name": "Power"
    }
  ]
}
```

### Full Example

```json
{
  "id": "my-device-mod",
  "name": "My Device Controller",
  "version": "1.0.0",
  "author": "Your Name",
  "description": "Control my custom device",
  "icon": "mdi:chip",

  "libs": ["libs/mydevice.jar"],
  "manager": "com.mydevice.DeviceManager",

  "entities": [
    {
      "type": "switch",
      "id": "power",
      "name": "Power",
      "icon": "mdi:power",
      "on": "setPower:1",
      "off": "setPower:0"
    },
    {
      "type": "binary_sensor",
      "id": "motion",
      "name": "Motion",
      "icon": "mdi:motion-sensor",
      "class": "motion",
      "gpio": 4
    },
    {
      "type": "sensor",
      "id": "temperature",
      "name": "Temperature",
      "icon": "mdi:thermometer",
      "unit": "°C",
      "read": "getTemperature"
    },
    {
      "type": "number",
      "id": "brightness",
      "name": "Brightness",
      "icon": "mdi:brightness-6",
      "min": 0,
      "max": 100,
      "step": 1,
      "set": "setBrightness"
    },
    {
      "type": "button",
      "id": "reboot",
      "name": "Reboot",
      "icon": "mdi:restart",
      "press": "reboot"
    }
  ]
}
```

## Entity Types

### switch

On/off control.

```json
{
  "type": "switch",
  "id": "led",
  "name": "LED",
  "icon": "mdi:led-on",
  "on": "methodName:arg1",
  "off": "methodName:arg2"
}
```

### binary_sensor

Binary state sensor (motion, door, etc).

```json
{
  "type": "binary_sensor",
  "id": "motion",
  "name": "Motion",
  "class": "motion",
  "gpio": 4
}
```

Device classes: `motion`, `door`, `window`, `smoke`, `moisture`, `occupancy`

### sensor

Numeric or text sensor.

```json
{
  "type": "sensor",
  "id": "temp",
  "name": "Temperature",
  "unit": "°C",
  "read": "getTemperature"
}
```

### number

Adjustable number.

```json
{
  "type": "number",
  "id": "volume",
  "name": "Volume",
  "min": 0,
  "max": 100,
  "step": 1,
  "set": "setVolume"
}
```

### button

Trigger action.

```json
{
  "type": "button",
  "id": "reset",
  "name": "Reset",
  "press": "reset"
}
```

## Action Format

Actions use the format `methodName:arg1,arg2`:

- `setPower:1` → calls `manager.setPower("1")`
- `setOemFunc:io21` → calls `manager.setOemFunc("io21")`
- `setRGB:255,0,0` → calls `manager.setRGB("255", "0", "0")`

## Device Compatibility Hooks

Mods with a `manager` class can optionally expose device-level compatibility hooks used by Ava core. These hooks are useful when a device needs custom brightness behavior, BLE tuning, or root-based overlay permission setup without modifying the main APK.

Supported optional methods:

- `boolean isSupported()` or `boolean isSupported(Context context)`
- `int getMinBrightness()` or `int getMinBrightness(Context context)`
- `boolean isLowEndBleChip()` or `boolean isLowEndBleChip(Context context)`
- `boolean grantOverlayPermissionIfNeeded()` or `boolean grantOverlayPermissionIfNeeded(Context context)`

These methods are optional. Mods that only expose entities do not need to implement them.

## Publishing

1. Create source files in `sources/devices/` or `sources/features/`
2. Build and copy the release package into `mods/devices/` or `mods/features/`
3. Add or update the `store.json` entry
4. Submit Pull Request

## Testing

Before publishing, test your mod locally:

1. Copy `manifest.json` to device: `/data/data/com.example.ava/files/mods/your-mod/`
2. Enable in Ava settings
3. Check Home Assistant for entities
