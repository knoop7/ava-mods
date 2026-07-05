# Facebook Portal

Expose Facebook Portal hardware sensors and controls to Home Assistant through Ava. Sensor logic is adapted from [portal-ha-bridge](https://github.com/RoadRunner-1024/portal-ha-bridge) without MQTT.

## Supported devices

Facebook Portal family on Android 9–10: Portal (10"), Portal Mini, Portal+ (1st and 2nd gen).

## Entities

All features are disabled by default. Enable each one in the mod settings before it appears in Home Assistant.

| Entity | Type | Notes |
|--------|------|-------|
| Portal Presence | binary_sensor | Meta face-presence via logcat, read through a Shizuku/root shell |
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
| Enhanced Presence | mod setting | Sound threshold fallback when face logcat is weak (low light) |

## Permissions

Presence tails Meta's `PresenceManager` logcat heartbeat (same as [portal-ha-bridge](https://github.com/RoadRunner-1024/portal-ha-bridge)).
Grant `READ_LOGS` to Ava via `./provision.sh`, Shizuku, or
`adb shell am broadcast -a com.example.ava.ACTION_GRANT_READ_LOGS com.example.ava`,
then **restart Ava** so the permission applies. Shizuku/root shell is a fallback when the app grant is pending.

The mod auto-requests permissions at runtime through Shizuku first, then root, then falls back to manual provision.

| Permission / app-op | Used for |
|---------------------|----------|
| `RECORD_AUDIO` | Sound level sensor |
| `WRITE_SECURE_SETTINGS` | Screen sleep fallback |
| `CAMERA` | Reserved for future camera features |
| `READ_LOGS` | Portal presence — granted to Ava via ADB or Shizuku `pm grant`; in-app logcat (same as portal-ha-bridge) |
| `WRITE_SETTINGS` (app-op) | Brightness control |
| `SYSTEM_ALERT_WINDOW` (app-op) | Background overlay access |

Presence reads `logcat` in the Ava process when `READ_LOGS` is granted (portal-ha-bridge path).
Shizuku or root shell is used only as a fallback before the grant takes effect after restart.
Without any log access channel, the presence sensor stays clear but the HA switch stays on.

Manual provision via adb (grants the same permissions the mod requests at runtime):

```bash
./provision.sh com.example.ava
```

Script: [provision.sh](https://github.com/knoop7/ava-mods/blob/main/sources/devices/portal-support/provision.sh) — grants permissions and force-stops Ava so `READ_LOGS` applies. Shizuku can grant at runtime without ADB once Ava is authorized.

Screen sleep uses Ava accessibility (`GLOBAL_ACTION_LOCK_SCREEN`) when `WRITE_SECURE_SETTINGS` enables it, then Shizuku display-off / shell keyevent fallbacks.

## Screen timeout

When enabled, the timer sleeps the screen after the configured idle period. If presence detection (face or enhanced sound) reports occupancy, the countdown resets — same behaviour as portal-ha-bridge.

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
