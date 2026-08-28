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
| Physical Volume | sensor (diagnostic) | Read-only media volume 0–1; event-driven; no root; for HA→MA sync automations |
| Doorbell / Alert | button | Synthesized tones on the media stream |
| Screen Timeout | switch | Idle screen-off timer |
| Screen Timeout Minutes | number | 1–240 minutes; presence keeps the screen awake |
| System Chrome | select | `off` / `status` / `full` — hides Portal top menu via `policy_control` (same as Immortal); opt-in, starts at `off` |
| Enhanced Presence | mod setting | Sound threshold fallback when face logcat is weak (low light) |

## Permissions

The mod can be **enabled without Shizuku**. Sensors, physical volume, and alert tones work with normal app permissions. Privileged features degrade until a one-time grant:

| Feature | Needs |
|---------|--------|
| Sensors / volume / doorbell | None beyond normal runtime grants |
| System Chrome | `WRITE_SECURE_SETTINGS` (one-time ADB / Shizuku / `provision.sh`) — after grant, Shizuku does not need to stay running |
| Face presence (logcat) | `READ_LOGS` (same) — or a live Shizuku/root shell as fallback |
| Screen timeout sleep | Accessibility preferred; `WRITE_SECURE_SETTINGS` helps auto-enable it |

Presence tails Meta's `PresenceManager` logcat heartbeat (same as [portal-ha-bridge](https://github.com/RoadRunner-1024/portal-ha-bridge)).
Log lines are classified by content: explicit negatives (`presence: false`, `pausing presence`) clear the sensor immediately and presence-engine lifecycle chatter is ignored, so camera-arbitration noise cannot hold the sensor at Detected ([#205](https://github.com/knoop7/Ava/issues/205)). Each matched beat line is echoed (rate-limited) to the `PortalSupport` log tag for diagnosis.
Grant `READ_LOGS` to Ava via `./provision.sh`, Shizuku, or
`adb shell am broadcast -a com.example.ava.ACTION_GRANT_READ_LOGS com.example.ava`,
then **restart Ava** so the permission applies. Shizuku/root shell is a fallback when the app grant is pending.

When Shizuku/root is already available, the mod still auto-requests grants at runtime. Boot hooks do **not** force-launch Shizuku just because optional privileges are missing.

| Permission / app-op | Used for | Required to enable mod? |
|---------------------|----------|-------------------------|
| `RECORD_AUDIO` | Sound level sensor | Runtime (when sound features on) |
| `CAMERA` | Reserved for future camera features | Declared only |
| `WRITE_SECURE_SETTINGS` | System Chrome + screen sleep helpers | Optional |
| `READ_LOGS` | Portal face presence | Optional |
| `WRITE_SETTINGS` (app-op) | Brightness control | Optional |
| `SYSTEM_ALERT_WINDOW` (app-op) | Background overlay access | Optional |

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

## System chrome (Browser Display / kiosk)

Controls Android `Settings.Global.policy_control` so the Portal top Back / Wi‑Fi chrome stays hidden — the same approach [Immortal](https://github.com/starbrightlab/immortal) uses (`immersive.status=*`). Needs `WRITE_SECURE_SETTINGS` (already granted by `provision.sh` / Shizuku).

| Option | Effect |
|--------|--------|
| `status` | Hide status bar only |
| `full` | Hide status + navigation bars |
| `off` | Restore stock system chrome |

Swipe from the top can still briefly reveal the bar; blocking that gesture needs device-owner Lock Task (not wired yet). Change the mode from Home Assistant:

```yaml
service: select.select_option
data:
  entity_id: select.<device>_portal_support_system_ui
  option: status
```

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
5. Optional: enable **Physical Volume** to expose a diagnostic sensor (`sensor.*_portal_support_physical_volume`, 0–1). Use a Home Assistant automation to push that value into Music Assistant if you want MA to follow the hardware keys — Ava itself does not write MA from this sensor.
