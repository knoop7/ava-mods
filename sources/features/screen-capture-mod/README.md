# 屏幕拍摄 (Screen Capture)

Capture the current Ava device screen and expose it to Home Assistant.

## Entities

| Entity | Type | Description |
|--------|------|-------------|
| 截取屏幕 | button | Capture the current device screen |
| 屏幕画面 | camera | Last captured JPEG frame |
| 上次截取时间 | text_sensor | ISO timestamp of the last capture (opt-in in mod settings) |

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
