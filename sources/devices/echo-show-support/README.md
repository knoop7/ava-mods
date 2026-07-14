# Echo Show Support v1.1.4

Device compatibility mod for Amazon Echo Show models (crown, checkers, cronos).

This mod does not expose Home Assistant entities. It provides optional manager hooks consumed by Ava core through `ModDeviceSupport`.

## Provided Hooks

| Hook | Purpose |
|------|---------|
| `isSupported()` | Echo Show device detection |
| `getMinBrightness()` | Minimum backlight (10) for dim/safe touch |
| `isLowEndBleChip()` | BLE scan tuning |
| `grantOverlayPermissionIfNeeded(Context)` | Root `appops` for overlay |
| `sleepScreenForDark(Context)` | Screensaver **Turn off in dark** — Shizuku/root sleep |
| `wakeScreenFromDark(Context)` | Restore screen when ambient light returns |

## Screensaver dark-off (v1.1+)

When the user enables **Turn off in dark** in Screensaver settings **and** this mod is **enabled**:

1. Ava detects darkness (lux ≤ 1.5, debounced) in `ScreensaverController`
2. Core calls `ModDeviceSupport.trySleepScreenForDark()` — **no Echo-specific code in main APK**
3. This mod runs only if `isSupported()` (Echo Show hardware)
4. Sleep order: Shizuku `setDisplayPower(0)` → root `keyevent 223` → `keyevent 26` → min brightness 10
5. After sleep, the mod keeps a renewing `PARTIAL_WAKE_LOCK` and restores on light via:
   - **Primary:** privileged poll of JSA1214 ALS sysfs `lux` (amazon-oss mt8163 `alsps/jsa1214`) — still works after display power-off when `TYPE_LIGHT` stalls
   - **Secondary:** `SensorManager` `TYPE_LIGHT` when the HAL still delivers events
6. Wake order: Shizuku `setDisplayPower(2)` → `keyevent 224` (KEYCODE_WAKEUP) → brightness restore (avoid power key 26 — it toggles and can leave the panel off)
7. If mod returns `false`, Ava falls back to built-in `ScreenControlUtils.setScreenOn(false/true)`

**Requires** root and/or Shizuku on LineageOS Echo Show builds (typical for crown / checkers / cronos).

### Backlight sysfs paths

`EchoShowPrivilegedShell.writeBacklightBrightness` tries these nodes in order:

1. `/sys/class/backlight/lcd-backlight/brightness`
2. `/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness`
3. `/sys/class/leds/lcd-backlight/brightness`

### ALS lux sysfs (dark restore)

Polls candidate nodes such as `/sys/bus/platform/drivers/als_ps/lux`, and falls back to `find … -name lux`.

Headless enable:

```bash
adb shell am broadcast -a com.example.ava.ACTION_SET_MOD_ENABLED \
  --es mod_id echo-show-support --ez mod_enabled true
```

## Build

Requires Android SDK platform 34 and `d8` (build-tools). Output jar must contain `classes.dex` for `DexClassLoader`.

```bash
cd sources/devices/echo-show-support
./build.sh
```

`build.sh` writes DEX jar to `libs/`, copies into `mods/devices/echo-show-support/`, and updates `jar_hash` in manifests / `store.json`.
