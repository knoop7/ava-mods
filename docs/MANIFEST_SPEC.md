# Manifest Specification

Complete reference for `manifest.json` fields.

## Root Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique mod identifier (lowercase, hyphens) |
| `name` | string | Yes | Display name |
| `version` | string | Yes | Semantic version (e.g., "1.0.0") |
| `author` | string | No | Author name |
| `description` | string | No | Short description |
| `icon` | string | No | MDI icon name (e.g., "mdi:led-on") |
| `libs` | array | No | JAR files to load |
| `manager` | string | No | Manager class name |
| `manager` device hooks | methods | No | Optional methods used by Ava core for device compatibility: `isSupported`, `getMinBrightness`, `isLowEndBleChip`, `grantOverlayPermissionIfNeeded` |
| `entities` | array | Yes | Entity definitions |

## Entity Fields

### Common Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | Entity type |
| `id` | string | Yes | Unique entity ID within mod |
| `name` | string | Yes | Display name |
| `icon` | string | No | MDI icon |

### Type-Specific Fields

#### switch

| Field | Type | Description |
|-------|------|-------------|
| `on` | string | Action for turning on |
| `off` | string | Action for turning off |

#### binary_sensor

| Field | Type | Description |
|-------|------|-------------|
| `class` | string | Device class (motion, door, etc) |
| `gpio` | int | GPIO pin number |

#### sensor

| Field | Type | Description |
|-------|------|-------------|
| `unit` | string | Unit of measurement |
| `read` | string | Method to read value |

#### number

| Field | Type | Description |
|-------|------|-------------|
| `min` | float | Minimum value |
| `max` | float | Maximum value |
| `step` | float | Step size |
| `set` | string | Method to set value |

#### button

| Field | Type | Description |
|-------|------|-------------|
| `press` | string | Action on press |

## Action Format

```
methodName:arg1,arg2,arg3
```

Examples:
- `setPower:1`
- `setOemFunc:io21`
- `setRGB:255,128,0`

## Release Package Location

Published mod packages should be stored under one of these directories:

- `mods/devices/<mod-id>/` for device-specific mods
- `mods/features/<mod-id>/` for feature-oriented mods

Source code and build scripts should live separately under `sources/`.
