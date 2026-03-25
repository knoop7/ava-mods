# Flashlight Mod

A simple Android flashlight control module for [Ava](https://github.com/knoop7/Ava).

## Features

- Detect device flashlight availability
- Turn flashlight on/off
- Get current flashlight state
- Thread-safe singleton implementation

## Requirements

- Android 5.0+ (API 21) with Camera2 API
- Camera permission
- Physical flashlight hardware

## API

```java
FlashlightManager manager = FlashlightManager.getInstance(context);

// Check availability
boolean hasFlash = manager.hasFlashlight();

// Control
manager.turnOn();
manager.turnOff();
manager.toggle();

// State
boolean isOn = manager.isOn();
```

## Implementation

Uses Camera2 API to control the rear camera flash unit. Falls back gracefully if no flashlight is available.

## Build

```bash
cd sources/features/flashlight-mod
./build.sh
```

Outputs `libs/flashlight-manager.jar`.

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.
