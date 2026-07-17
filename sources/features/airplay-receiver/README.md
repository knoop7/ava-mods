# AirPlay Receiver — Ava Mod (Java + .so)

Same packaging model as other Ava feature mods (e.g. DLNA, Mimiclaw):

- **Java** sources under `src/com/ava/mods/airplay/`
- **Native** `libairplay_native.so` (+ `libc++_shared.so`) under `libs/jni/<abi>/`
- Built with `./build.sh` → `javac` + `d8` + NDK CMake
- Published to `mods/features/airplay-receiver/` and listed in `store.json`

## Manager

`com.ava.mods.airplay.AirPlayReceiverManager`

## Advertised name

`Ava - {device_name} [Airplay]`

Blank `device_name` uses the phone model (never `Ava - Ava`).

## Lifecycle

Starts only when:

1. Ava has pushed `auto_start=true` via `applyConfig`, and
2. Voice satellite master switch is running (`VoiceSatelliteService.isSatelliteStarted`).

Stops when either gate turns off.

## Build

```bash
cd sources/features/airplay-receiver
chmod +x build.sh fetch-deps.sh
./build.sh
```

Requires Android SDK (platform 34+), build-tools `d8`, and NDK. First build may run `fetch-deps.sh`. **androidx.media** (MediaSessionCompat) is packaged into the mod DEX; **Media3** stays compile-only and is provided by Ava at runtime.

## Layout

```
airplay-receiver/
├── manifest.json
├── build.sh / fetch-deps.sh
├── src/com/ava/mods/airplay/   # Java manager + engine + overlay + renderers
├── assets/                     # badge + video control icons
├── native/                     # CMake, JNI bridge, UxPlay / plist / ALAC / OpenSSL
├── build-deps/                 # optional compile-only jars cache
└── libs/
    ├── airplay-receiver-manager.jar
    └── jni/<abi>/libairplay_native.so
```
