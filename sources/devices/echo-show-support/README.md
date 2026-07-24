# Echo Show Support v1.1.5

Device compatibility mod for Amazon Echo Show models (crown, checkers, cronos).

This mod does not expose Home Assistant entities. It provides optional manager hooks consumed by Ava core through `ModDeviceSupport`.

## Provided Hooks

| Hook | Purpose |
|------|---------|
| `isSupported()` | Echo Show device detection |
| `getMinBrightness()` | Minimum backlight (10) for dim/safe touch |
| `isLowEndBleChip()` | BLE scan tuning |
| `suppressHostBleAdvertisingDuringProxy()` | Pause Ava's own BLE service advertisement while proxy scanning |
| `getBleProxyHandoverDelayMs()` | Allow the controller to settle before proxy scan start |
| `recoverBluetoothProxyScanFailure(Context, int)` | Root-only Crown GATT recovery after a proxy scan failure |
| `grantOverlayPermissionIfNeeded(Context)` | Root `appops` for overlay |
| `sleepScreenForDark(Context)` | Screensaver **Turn off in dark** — Shizuku/root sleep |
| `wakeScreenFromDark(Context)` | Restore screen when ambient light returns |

On rooted Crown devices, a proxy scan registration failure can indicate that Android's GATT
service did not bind. The mod performs one cooldown-limited system Bluetooth cycle and lets Ava
rebuild the proxy scan session. The Android version and scan error code are recorded for diagnosis,
not used as compatibility gates. The mod never opens `/dev/stpbt` or competes with the vendor HAL.

## Screensaver dark-off (v1.1+)

When the user enables **Turn off in dark** in Screensaver settings **and** this mod is **enabled**:

1. Ava detects darkness (lux ≤ 1.5, debounced) in `ScreensaverController`
2. Core calls `ModDeviceSupport.trySleepScreenForDark()` — **no Echo-specific code in main APK**
3. This mod runs only if `isSupported()` (Echo Show hardware)
4. Sleep order: Shizuku `setDisplayPower(0)` → root `keyevent 223` → `keyevent 26` → min brightness 10
5. After sleep, the mod keeps a renewing `PARTIAL_WAKE_LOCK` and restores on light via privileged JSA1214 ALS sysfs `lux` (+ `TYPE_LIGHT` when available)
6. **Manual power wake (v1.1.5):** if the user presses power while still dark, Ava stays in dark-off mode and would never sleep again. This mod listens for `SCREEN_ON` / panel-on heuristics, waits **90s grace**, then re-arms dark sleep while ambient lux stays ≤ 1.5
7. Wake order: Shizuku `setDisplayPower(2)` → `keyevent 224` → brightness restore
8. If mod returns `false`, Ava falls back to built-in `ScreenControlUtils`

**Requires** root and/or Shizuku on LineageOS Echo Show builds.

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
