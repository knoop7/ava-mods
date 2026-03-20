# Echo Show Support

Device compatibility mod for Amazon Echo Show models.

This mod does not expose Home Assistant entities. Instead, it provides optional manager hooks consumed by Ava core through `ModDeviceSupport`.

## Provided Hooks

- `isSupported()`
- `getMinBrightness()`
- `isLowEndBleChip()`
- `grantOverlayPermissionIfNeeded(Context)`

## Current Behavior

- Forces a minimum screen brightness of `10`
- Marks Echo Show devices as low-end BLE chip targets
- Attempts to grant `SYSTEM_ALERT_WINDOW` via root `appops`

## Build

```bash
cd echo-show-support
./build.sh
```

The output JAR is written to:

- `libs/echo-show-support.jar`

Publish by copying these files into:

- `mods/echo-show-support/manifest.json`
- `mods/echo-show-support/libs/echo-show-support.jar`
