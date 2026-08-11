# Screen Capture

Capture the current Ava device screen and expose it to Home Assistant.

## Settings (mod config, not HA entities)

| Key | Options | Default | Description |
|-----|---------|---------|-------------|
| Image size | `small` / `medium` / `original` | `small` | Longest-edge JPEG size for the HA camera (480 / 720 / full). Same rule in portrait and landscape. |
| Last capture time | on / off | — | Optional text sensor with last capture ISO time |

## Entities

| Entity | Type | Description |
|--------|------|-------------|
| Take Screenshot | button | Capture the current device screen |
| Screen | camera | Last captured JPEG frame |
| Image URL | text_sensor | LAN fleet URL (`/v1/screen/last.jpg?password=…`, ≤255 chars) after each capture |
| Last Screenshot | text_sensor | ISO timestamp of the last capture (opt-in in mod settings) |

Open Image URL in a browser on the same LAN (fleet agent must be running).

## Capture paths

1. Shizuku / root `screencap` (silent)
2. Ava accessibility `takeScreenshot` on Android 11+ (API 30+)

No MediaProjection dialog. On Android 10 and below without Shizuku/root, capture is unavailable until a privileged shell is available.

## Permissions

- `needs_accessibility: true` — Ava surfaces Accessibility in Settings → Permissions when this mod is enabled
- Optional `WRITE_SECURE_SETTINGS` — lets Ava enable accessibility silently when already granted via ADB / Shizuku

## Build

```bash
cd sources/features/screen-capture-mod
./build.sh
```

## Requirements

- Ava host with `ModScreenCapture` + mod `camera` entity support
- Home Assistant via the Ava ESPHome API connection
