# Tuya S8E

Device-specific controls for Tuya S8E smart screens.

## Exposed controls

- **Screen Backlight** — controls `/sys/class/backlight/backlight/bl_power`
- **Small Screen Backlight** — controls `/sys/class/gpio/gpio115/value`

Both switches read back the current sysfs value so the UI can reflect
the real hardware state.

## Build

```bash
cd sources/devices/tuya-s8e-support
./build.sh 1.0.0
```

