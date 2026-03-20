# Ava Mods

A modular extension system for [Ava](https://github.com/knoop7/Ava) - the Android voice assistant that turns devices into smart home control panels.

## Overview

Ava Mods enables dynamic hardware support without rebuilding the main APK. Users can download, install, enable, and manage device-specific mods directly from the app.

### Key Features

- **Dynamic Loading** - Mods are loaded at runtime, no APK rebuild required
- **Online Store** - Browse and download mods from the central repository
- **Auto Restart** - Service automatically restarts when mods are enabled/disabled
- **Bidirectional Sync** - Full Home Assistant entity integration with state sync
- **Custom Hardware** - Support for GPIO, LED, sensors, and custom device APIs
- **Hot Update** - Update mods without updating the main app

## Architecture

```
ava-mods/
├── store.json                         # Store index consumed by Ava
├── mods/                              # Published packages referenced by store.json
│   ├── devices/                       # Device-specific release packages
│   │   ├── echo-show-support/
│   │   └── yx-led-controller/
│   └── features/                      # Feature-oriented release packages
│       └── gps-mod/
├── sources/                           # Source trees and build scripts
│   ├── devices/                       # Device compatibility or hardware source mods
│   │   └── echo-show-support/
│   └── features/                      # Feature source mods
│       └── gps-mod/
├── docs/                              # Documentation
└── examples/                          # Minimal example manifests
    ├── devices/
    └── features/
```

### Organization Rules

- `mods/` contains only release-ready packages downloaded by Ava
- `sources/` contains source code, build scripts, and local build outputs
- `devices/` is for model-specific or hardware-specific mods
- `features/` is for reusable functional mods not tied to one device family

## Quick Start

### For Users

1. Open Ava app
2. Go to **Settings → Experimental → Mod Store**
3. Browse available mods
4. Tap **Download** on desired mod
5. Enable the mod
6. Service will auto-restart and entities appear in Home Assistant

### For Developers

See [Creating Mods](docs/CREATING_MODS.md) for a complete guide.

#### Build Tools

Each mod source directory contains a `build.sh` script that compiles Java to DEX-based JAR.

**Requirements:**
- Android SDK (platform 34+)
- Java 11 or higher
- d8 tool (included in Android SDK build-tools)

**Build Steps:**

```bash
cd sources/features/your-mod/
chmod +x build.sh
./build.sh
```

The script will:
1. Compile Java sources with `javac`
2. Convert `.class` files to DEX format using `d8`
3. Package the DEX into a JAR file in `libs/`

After building, copy the JAR to the release directory:

```bash
cp libs/your-manager.jar ../../mods/features/your-mod/libs/
```

#### Basic Mod Structure

```json
{
  "id": "my-custom-device",
  "name": "My Custom Device",
  "version": "1.0.0",
  "author": "Your Name",
  "description": "Control my custom hardware",
  "icon": "mdi:chip",
  "entities": [
    {
      "type": "switch",
      "id": "power",
      "name": "Power",
      "icon": "mdi:power"
    }
  ]
}
```

## Store Index Format

The `store.json` file contains the mod catalog:

```json
{
  "version": 1,
  "lastUpdated": "2026-03-10",
  "baseUrl": "https://raw.githubusercontent.com/knoop7/ava-mods/main/",
  "mods": [
    {
      "id": "yx-led-controller",
      "name": "YX LED Controller",
      "version": "1.0.0",
      "author": "slovebj",
      "description": "LED and motion sensor support for YX M5612 devices",
      "icon": "mods/devices/yx-led-controller/icon.png",
      "path": "mods/devices/yx-led-controller/",
      "size": "45KB",
      "downloads": 0,
      "rating": 0,
      "tags": ["led", "gpio", "motion", "yx"],
      "requires": {
        "minAvaVersion": "3.4.0",
        "hardware": ["yx_m5612"]
      }
    }
  ]
}
```

## Device Compatibility Mods

In addition to entity mods, Ava also supports device compatibility mods. These mods provide optional manager hooks for model-specific behavior such as minimum brightness overrides, BLE low-end tuning, or privileged overlay permission setup.

Example: `echo-show-support`

## Entity Types

Mods can expose the following entity types to Home Assistant:

| Type | Description | HA Domain |
|------|-------------|-----------|
| `switch` | On/off control | `switch` |
| `binary_sensor` | Binary state (motion, door, etc.) | `binary_sensor` |
| `sensor` | Numeric/text values | `sensor` |
| `number` | Adjustable numeric value | `number` |
| `button` | Trigger action | `button` |
| `select` | Option selector | `select` |
| `light` | Light with brightness/color | `light` |

## Contributing

We welcome contributions! To add a new mod:

1. Fork this repository
2. Create source files in `sources/devices/` or `sources/features/`
3. Publish the release package to `mods/devices/` or `mods/features/`
4. Add or update the entry in `store.json`
4. Submit a Pull Request

### Guidelines

- Use English for all documentation and code comments
- Follow the [Manifest Specification](docs/MANIFEST_SPEC.md)
- Test on actual hardware before submitting
- Include clear description and usage instructions

## License

MIT License - see [LICENSE](LICENSE) for details.

## Related

- [Ava](https://github.com/knoop7/Ava) - Main application
- [ESPHome](https://esphome.io/) - Protocol foundation
- [Home Assistant](https://www.home-assistant.io/) - Smart home platform
