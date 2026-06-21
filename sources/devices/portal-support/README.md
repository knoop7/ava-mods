# Facebook Portal

Expose Facebook Portal hardware sensors and controls to Home Assistant through Ava. Sensor logic is adapted from [portal-ha-bridge](https://github.com/RoadRunner-1024/portal-ha-bridge) without MQTT.

## Supported devices

Facebook Portal family on Android 9–10: Portal (10"), Portal Mini, Portal+ (1st and 2nd gen).

## Entities

All features are disabled by default. Enable each one in the mod settings before it appears in Home Assistant.

| Entity | Type | Notes |
|--------|------|-------|
| Portal Presence | binary_sensor | Meta face-presence via logcat (`READ_LOGS`) |
| Presence Detection | switch | Enable/disable presence monitoring |
| Ambient Light | sensor | Lux (TCS34x0) |
| Light R / G / B | sensor | Colour channels (hardware dependent) |
| Temperature | sensor | Ambient temperature (hardware dependent) |
| Temperature Offset | number | Offset applied to temperature |
| Tap Tilt | text_sensor | left/right/up/down/front/back |
| Tap Tilt Sensitivity | number | Tap detection threshold |
| Accel X / Y / Z | sensor | Raw accelerometer |
| Sound Level | sensor | 0–100 ambient loudness; mic released during Portal calls |
| Doorbell / Alert | button | Synthesized tones on the media stream |
| Screen Timeout | switch | Idle screen-off timer |
| Screen Timeout Minutes | number | 1–240 minutes; presence keeps the screen awake |

## Permissions

The mod auto-requests permissions at runtime through Shizuku first, then root, then falls back to a manual provision. You no longer need to run `provision.sh` if Shizuku or root is available on the device.

| Permission / app-op | Used for |
|---------------------|----------|
| `READ_LOGS` | Portal presence sensor |
| `RECORD_AUDIO` | Sound level sensor |
| `WRITE_SECURE_SETTINGS` | Screen sleep fallback |
| `CAMERA` | Reserved for future camera features |
| `WRITE_SETTINGS` (app-op) | Brightness control |
| `SYSTEM_ALERT_WINDOW` (app-op) | Background overlay access |

Manual provision (only needed when Shizuku/root are unavailable):

```bash
./provision.sh com.example.ava
```

## Screen timeout

When enabled, the timer sleeps the screen after the configured idle period. If presence detection is running and reports occupancy, the countdown resets — same behaviour as portal-ha-bridge.

Screen sleep tries Ava `AccessibilityBridge` first, then a root `input keyevent` fallback. Without root or an accessible lock-screen hook, timeout will log a warning and stay on.

## Build

```bash
cd sources/devices/portal-support
chmod +x build.sh provision.sh
./build.sh
```

Release artifacts are copied to `mods/devices/portal-support/`.

## Usage

1. Install the mod in Ava on a Facebook Portal device.
2. Run `provision.sh` with your Ava package name.
3. Open mod settings and enable the features you need.
4. For presence or screen timeout, also turn on the corresponding switch in Home Assistant.
