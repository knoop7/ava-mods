# Flashlight Mod

Torch control for [Ava](https://github.com/knoop7/Ava), exposed to Home Assistant.

## Entities

| Entity | Type | Description |
|--------|------|-------------|
| `switch.<device>_flashlight` | switch | Turn the torch on or off, with state |

## Features

- Turn the torch on or off from Home Assistant
- **Real state**, read from the system rather than from the last command sent, so it stays correct
  when Android turns the torch off by itself
- Push state updates — no polling
- Works on devices whose flash is not on the back camera
- Turns the torch off when the mod is disabled

## Requirements

- Android 6.0+ (API 23) — `CameraManager.setTorchMode` is not available before that
- Camera permission
- A camera reporting a flash unit

## Implementation

Uses `CameraManager.setTorchMode()`, which drives the flash without opening a camera session. The
torch therefore works while nothing is capturing, and never competes with a camera stream or
snapshot for the device.

State comes from `CameraManager.TorchCallback`, so Home Assistant sees the torch going out when
another app opens that camera or when the device overheats — not just when something asked for it.

## API

```java
FlashlightManager manager = FlashlightManager.getInstance(context);

boolean hasFlash = manager.hasFlashlight();

manager.turnOn();
manager.turnOff();
manager.toggle();

boolean isOn = manager.isOn();
```

## Build

```bash
cd sources/features/flashlight-mod
./build.sh
```

Outputs `libs/flashlight-manager.jar`.

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.
