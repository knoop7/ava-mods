# Creating Mods

This guide explains how to create a mod for Ava.

## Basic Structure

## Repository Layout

Use the repository in two layers:

- `sources/devices/...` and `sources/features/...` for source code and build scripts
- `mods/devices/...` and `mods/features/...` for release packages consumed by Ava

If your mod is only a simple manifest without source code, it can live directly in the release tree.

A mod consists of a single `manifest.json` file:

```
my-mod/
└── manifest.json
```

For hardware mods with native libraries:

```
my-mod/
├── manifest.json
└── libs/
    └── device-api.jar
```

## Manifest Format

### Minimal Example

```json
{
  "id": "my-mod",
  "name": "My Mod",
  "version": "1.0.0",
  "entities": [
    {
      "type": "switch",
      "id": "power",
      "name": "Power"
    }
  ]
}
```

### Full Example

```json
{
  "id": "my-device-mod",
  "name": "My Device Controller",
  "version": "1.0.0",
  "author": "Your Name",
  "description": "Control my custom device",
  "icon": "mdi:chip",

  "libs": ["libs/mydevice.jar"],
  "manager": "com.mydevice.DeviceManager",

  "entities": [
    {
      "type": "switch",
      "id": "power",
      "name": "Power",
      "icon": "mdi:power",
      "on": "setPower:1",
      "off": "setPower:0"
    },
    {
      "type": "binary_sensor",
      "id": "motion",
      "name": "Motion",
      "icon": "mdi:motion-sensor",
      "class": "motion",
      "gpio": 4
    },
    {
      "type": "sensor",
      "id": "temperature",
      "name": "Temperature",
      "icon": "mdi:thermometer",
      "unit": "°C",
      "read": "getTemperature"
    },
    {
      "type": "number",
      "id": "brightness",
      "name": "Brightness",
      "icon": "mdi:brightness-6",
      "min": 0,
      "max": 100,
      "step": 1,
      "set": "setBrightness"
    },
    {
      "type": "button",
      "id": "reboot",
      "name": "Reboot",
      "icon": "mdi:restart",
      "press": "reboot"
    }
  ]
}
```

## Entity Types

### switch

On/off control.

```json
{
  "type": "switch",
  "id": "led",
  "name": "LED",
  "icon": "mdi:led-on",
  "on": "methodName:arg1",
  "off": "methodName:arg2"
}
```

### binary_sensor

Binary state sensor (motion, door, etc).

```json
{
  "type": "binary_sensor",
  "id": "motion",
  "name": "Motion",
  "class": "motion",
  "gpio": 4
}
```

Device classes: `motion`, `door`, `window`, `smoke`, `moisture`, `occupancy`

### sensor

Numeric or text sensor.

```json
{
  "type": "sensor",
  "id": "temp",
  "name": "Temperature",
  "unit": "°C",
  "read": "getTemperature"
}
```

### number

Adjustable number.

```json
{
  "type": "number",
  "id": "volume",
  "name": "Volume",
  "min": 0,
  "max": 100,
  "step": 1,
  "set": "setVolume"
}
```

### button

Trigger action.

```json
{
  "type": "button",
  "id": "reset",
  "name": "Reset",
  "press": "reset"
}
```

### select

Option list exposed to Home Assistant as a dropdown. Use `read` for the current value and `set` for `methodName:option`.

```json
{
  "type": "select",
  "id": "color_filter",
  "name": "Screen Color Filter",
  "icon": "mdi:palette",
  "options": ["off", "red", "blue", "dark", "yellow", "green", "gray"],
  "read": "getFilterColor",
  "set": "setFilterColor"
}
```

Optional: implement `registerStateListener(String entityId, Object callback)` on the manager so device-side changes push back to Home Assistant.

## Action Format

Actions use the format `methodName:arg1,arg2`:

- `setPower:1` → calls `manager.setPower("1")`
- `setOemFunc:io21` → calls `manager.setOemFunc("io21")`
- `setRGB:255,0,0` → calls `manager.setRGB("255", "0", "0")`

## Device Compatibility Hooks

Mods with a `manager` class can optionally expose device-level compatibility hooks used by Ava core. These hooks are useful when a device needs custom brightness behavior, BLE tuning, or root-based overlay permission setup without modifying the main APK.

Supported optional methods:

- `boolean isSupported()` or `boolean isSupported(Context context)`
- `int getMinBrightness()` or `int getMinBrightness(Context context)`
- `boolean isLowEndBleChip()` or `boolean isLowEndBleChip(Context context)`
- `boolean suppressHostBleAdvertisingDuringProxy()` or `boolean suppressHostBleAdvertisingDuringProxy(Context context)` — pause Ava's own BLE service advertisement while the Home Assistant proxy scanner owns a constrained controller
- `int getBleProxyHandoverDelayMs()` or `int getBleProxyHandoverDelayMs(Context context)` — delay proxy scan startup after releasing that advertisement; Ava clamps the result to 0–5000 ms
- `boolean recoverBluetoothProxyScanFailure(int errorCode)` or `boolean recoverBluetoothProxyScanFailure(Context context, int errorCode)` — optionally recover a device-specific Bluetooth stack failure before Ava rebuilds the proxy scan session; blocking work runs off the main thread
- `boolean grantOverlayPermissionIfNeeded()` or `boolean grantOverlayPermissionIfNeeded(Context context)`
- `boolean sleepScreenForDark(Context context)` — screensaver dark-off; mod tries Shizuku/root on supported hardware only
- `boolean wakeScreenFromDark(Context context)` — restore screen after dark-off

These methods are optional. Mods that only expose entities do not need to implement them.

When `sleepScreenForDark` / `wakeScreenFromDark` are absent or return `false`, Ava core uses its default screen control path.

### Global overlay z-order (`overlay_z_order`)

Set `"overlay_z_order": true` in `manifest.json` when the mod draws a full-screen overlay that must stay above Ava foreground overlays (notifications, voice UI, volume bar, etc.).

The manager may implement:

```java
public void bringOverlayToFrontIfActive(Context context) {
    // removeView + addView on the mod overlay when active
}
```

Ava calls this at the end of overlay z-order reassert. When no enabled mod declares `overlay_z_order`, Ava skips this path entirely (cached zero-cost fast path).

## Publishing

1. Create source files in `sources/devices/` or `sources/features/`
2. Build and copy the release package into `mods/devices/` or `mods/features/`
3. Add or update the `store.json` entry
4. Submit Pull Request

## Testing

Before publishing, test your mod locally:

1. Copy `manifest.json` to device: `/data/data/com.example.ava/files/mods/your-mod/`
2. Enable in Ava settings
3. Check Home Assistant for entities

## Voice pipeline API (opt-in, zero cost when unused)

Use this when a mod (or a separate APK) must react to satellite lifecycle — e.g. LED ring on wake, thinking, TTS.

**Ava does nothing** unless:

1. An **enabled mod** sets `"voice_pipeline": true` in `manifest.json` **and** implements `onVoicePipelineEvent`, **or**
2. Another installed app registers a manifest receiver for `com.example.ava.VOICE_PIPELINE_EVENT`.

### Manifest

```json
{
  "id": "echo-dot-led",
  "name": "Echo Dot LED",
  "voice_pipeline": true,
  "manager": "com.example.EchoDotLedManager",
  "libs": ["libs/echo-dot-led.jar"]
}
```

### Java manager

```java
public class EchoDotLedManager {
    private static EchoDotLedManager instance;

    public static EchoDotLedManager getInstance(Context context) {
        if (instance == null) instance = new EchoDotLedManager();
        return instance;
    }

    public void onVoicePipelineEvent(Context context, String event, android.os.Bundle extras) {
        switch (event) {
            case "wake_detected":
                // extras: wake_word, wake_word_id, wake_confidence, synthetic_wake
                break;
            case "listening_started":
                // extras: accent_color
                break;
            case "stt_vad_start":
            case "stt_end":       // extras: stt_text
            case "processing_started":
            case "tts_start":     // extras: tts_text (optional)
            case "tts_playback_started":
            case "tts_finished":
            case "session_ended":
            case "run_start":
            case "run_end":
            case "pipeline_error": // extras: error_code, error_message
                break;
        }
    }
}
```

### External app (broadcast only)

```xml
<receiver android:name=".AvaVoiceReceiver" android:exported="true">
  <intent-filter>
    <action android:name="com.example.ava.VOICE_PIPELINE_EVENT" />
  </intent-filter>
</receiver>
```

Intent extras: `event` (string) plus event-specific keys (`stt_text`, `tts_text`, `wake_word`, …).

No mod and no external receiver → **no ClassLoader load, no broadcast, no background thread**.
