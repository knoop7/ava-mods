# Qualcomm Audio Concurrency Fix

> See [Issue #56](https://github.com/knoop7/Ava/issues/56)

When wake sound plays during `VOICE_ASSISTANT_STT_START`, the HAL driver blocks microphone access on devices that disallow concurrent audio sessions.

## Problem

```
platform_stdev_check_and_update_concurrency: concurrency active 0, tx 2, rx 1, concurrency session_allowed 0
Failed to fetch the lookup information of the device 0000000E
ACDB-LOADER: Error: ACDB AFE returned = -19
```

Error code `-19` is the standard Linux error `ENODEV`.

## Supported Devices

- ThinkSmart View (Android 11 via treble)
- Other Qualcomm devices using `msm8953-snd-card-mtp` sound card
- Run `cat /proc/asound/cards` to confirm compatibility

## Requirements

- **Root access**

## Usage

### Manual Application via ADB

```bash
# Get root
adb root
adb wait-for-device
adb shell "mount -o rw,remount /vendor"

# Update sound trigger config
adb shell "sed -i 's/<param rx_concurrency_disabled=\"true\" \/>/<param rx_concurrency_disabled=\"false\" \/>/g' /vendor/etc/sound_trigger_platform_info.xml"
adb shell "sed -i 's/<param concurrent_capture=\"false\" \/>/<param concurrent_capture=\"true\" \/>/g' /vendor/etc/sound_trigger_platform_info.xml"
adb shell "sed -i 's/<param concurrent_voip_call=\"false\" \/>/<param concurrent_voip_call=\"true\" \/>/g' /vendor/etc/sound_trigger_platform_info.xml"
adb shell "sed -i 's/<param concurrent_voice_call=\"false\" \/>/<param concurrent_voice_call=\"true\" \/>/g' /vendor/etc/sound_trigger_platform_info.xml"

# Update build.prop
adb shell "sed -i 's/vendor.voice.playback.conc.disabled=true/vendor.voice.playback.conc.disabled=false/g' /vendor/build.prop"
adb shell "sed -i 's/vendor.voice.voip.conc.disabled=true/vendor.voice.voip.conc.disabled=false/g' /vendor/build.prop"

# Update mixer paths (ThinkSmart View)
adb shell "sed -i 's/SLIM_0_TX/MI2S_TX/g' /vendor/etc/sound_trigger_platform_info.xml"
adb shell "sed -i 's/SLIMBUS_0_TX/TERT_MI2S_TX/g' /vendor/etc/sound_trigger_platform_info.xml"

# Verify
adb shell "grep 'rx_concurrency_disabled' /vendor/etc/sound_trigger_platform_info.xml"
adb shell "grep 'vendor.voice.playback.conc.disabled' /vendor/build.prop"

# Reboot device
adb reboot
```

### Via Java API

```java
import com.ava.mods.qualcomm.audio.AudioConcurrencyFix;

// Check compatibility
if (AudioConcurrencyFix.isCompatibleDevice()) {
    // Check if already applied
    if (!AudioConcurrencyFix.isConcurrencyEnabled()) {
        // Apply fix
        AudioConcurrencyFix.FixResult result = AudioConcurrencyFix.applyFix(context);
        if (result.success) {
            // Reboot required
            if (result.requiresReboot) {
                // Prompt user to reboot
            }
        } else {
            Log.e(TAG, "Fix failed: " + result.message);
        }
    }
}

// Get diagnostic info
String diagnostic = AudioConcurrencyFix.getDiagnosticInfo();
```

## How It Works

Modifies the following configuration files:

1. `/vendor/etc/sound_trigger_platform_info.xml`
   - `rx_concurrency_disabled`: `true` → `false`
   - `concurrent_capture`: `false` → `true`
   - `concurrent_voip_call`: `false` → `true`
   - `concurrent_voice_call`: `false` → `true`

2. `/vendor/build.prop`
   - `vendor.voice.playback.conc.disabled`: `true` → `false`
   - `vendor.voice.voip.conc.disabled`: `true` → `false`

3. Mixer paths (device-specific)
   - `SLIM_0_TX` → `MI2S_TX`
   - `SLIMBUS_0_TX` → `TERT_MI2S_TX`

## Credits

Original solution by [@pantherale0](https://github.com/pantherale0).
