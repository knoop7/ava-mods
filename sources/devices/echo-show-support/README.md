# Echo Show Support v1.1.8

Device compatibility mod for supported Amazon Echo displays (crown, checkers, cronos, rook).

This mod does not expose Home Assistant entities. It provides optional manager hooks consumed by Ava core through `ModDeviceSupport`.

## Provided Hooks

| Hook | Purpose |
|------|---------|
| `isSupported()` | Strict Crown, Checkers, Cronos, or Rook detection |
| `getHardwareProfileStatus()` | Detected codename, board revision, and evidence |
| `getDeviceCodename()` | Stable product key for future per-device behavior |
| `getBoardRevision()` | Device-tree hardware revision, or `unknown` |
| `getMinBrightness()` | Product-aware minimum backlight |
| `isLowEndBleChip()` | MT76x8-only BLE scan tuning |
| `suppressHostBleAdvertisingDuringProxy()` | Pause Ava's own BLE service advertisement while proxy scanning |
| `getBleProxyHandoverDelayMs()` | Allow the controller to settle before proxy scan start |
| `recoverBluetoothProxyScanFailure(Context, int)` | Root-only Crown GATT recovery after a proxy scan failure |
| `getBluetoothLeFeatureStatus()` | Echo display BLE feature declaration and repair status |
| `grantOverlayPermissionIfNeeded(Context)` | Root `appops` for overlay |
| `sleepScreenForDark(Context)` | Screensaver **Turn off in dark** — Shizuku/root sleep |
| `wakeScreenFromDark(Context)` | Restore screen when ambient light returns |

On rooted Crown devices, a proxy scan registration failure can indicate that Android's GATT
service did not bind. The mod performs one cooldown-limited system Bluetooth cycle and lets Ava
rebuild the proxy scan session. The Android version and scan error code are recorded for diagnosis,
not used as compatibility gates. The mod never opens `/dev/stpbt` or competes with the vendor HAL.

When the mod manager loads on Crown, Checkers, Cronos, or Rook, it automatically checks the ROM's
`android.hardware.bluetooth_le` declaration in the background. The mod never replaces an existing
permission XML. When both the system feature and file are missing and root is available, it stages
and validates the standard AOSP XML, restores the original read-only mount state, and reports that
a device restart is required.

## Hardware compatibility layer

`EchoShowCompatibility` is the single source of truth for hardware gating. It recognizes codenames
only as complete tokens in `Build.DEVICE`, `Build.PRODUCT`, `Build.BOARD`, or `Build.MODEL`, then
reads the NUL-terminated device-tree `version` property when available. Conflicting or unknown
identifiers fail closed: diagnostics remain available, but root repair and device hooks do not run.

| Codename | Product | Product-specific behavior currently enabled |
|----------|---------|---------------------------------------------|
| `crown` | Echo Show 8 (2019) | MT8163 / MT76x8 / JSA1214; Crown-only GATT recovery |
| `checkers` | Echo Show 5 (2019) | MT8163 / MT76x8 / JSA1214 |
| `cronos` | Echo Show 5 (2021) | MT8163 / MT76x8 / JSA1214; privacy hardware remains device-specific |
| `rook` | Echo Spot (2017) | MT8163 / BCM43569 / OPT3001; BLE feature repair only for now |

Board revision is reported separately because one codename can include EVT, DVT, HVT, and PVT
hardware variants. Future hardware writes must add an explicit capability gate and verify their
actual sysfs/device node; codename alone is not sufficient. Shared behavior follows the detected
chipset or sensor. Product-specific overrides are reserved for real hardware exceptions.

Rook deliberately does not inherit MT76x8 proxy tuning, advertising suppression, handover delay,
Crown GATT recovery, or the JSA1214 dark-screen path. Its OPT3001 path remains disabled until it is
validated on real hardware.

## Screensaver dark-off (v1.1+)

When the user enables **Turn off in dark** in Screensaver settings **and** this mod is **enabled**:

1. Ava detects darkness (lux ≤ 1.5, debounced) in `ScreensaverController`
2. Core calls `ModDeviceSupport.trySleepScreenForDark()` — **no Echo-specific code in main APK**
3. This path runs only when the detected hardware has `DARK_SCREEN_CONTROL` (currently Crown, Checkers, and Cronos)
4. Sleep order: Shizuku `setDisplayPower(0)` → root `keyevent 223` → `keyevent 26` → min brightness 10
5. After sleep, the mod keeps a renewing `PARTIAL_WAKE_LOCK` and restores on light via privileged JSA1214 ALS sysfs `lux` (+ `TYPE_LIGHT` when available)
6. **Manual power wake (v1.1.5):** if the user presses power while still dark, Ava stays in dark-off mode and would never sleep again. This mod listens for `SCREEN_ON` / panel-on heuristics, waits **90s grace**, then re-arms dark sleep while ambient lux stays ≤ 1.5
7. Wake order: Shizuku `setDisplayPower(2)` → `keyevent 224` → brightness restore
8. If mod returns `false`, Ava falls back to built-in `ScreenControlUtils`

**Requires** root and/or Shizuku on supported LineageOS Echo display builds.

### Backlight sysfs paths

1. `/sys/class/backlight/lcd-backlight/brightness`
2. `/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness`
3. `/sys/class/leds/lcd-backlight/brightness`

### ALS lux sysfs

Polls nodes such as `/sys/bus/platform/drivers/als_ps/lux`, with `find … -name lux` fallback.

## Build

```bash
cd sources/devices/echo-show-support
./build.sh
```
