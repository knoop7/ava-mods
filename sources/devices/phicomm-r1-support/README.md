# Phicomm R1 Support

Replicates stock 小讯 (Unisound) voice LED behavior on Phicomm R1 via Ava voice pipeline + msgcenter IPC.

## Stock behavior

| Phase | IPC |
|-------|-----|
| Wake (DOA) | `sendMessage(4096, 1-24, 0, null)` directional white sweep |
| User finished speaking | off 1-24 + accent breathing ring (JNI) or stock `4096, 203` blue breathing |
| TTS finished | off loading |
| Session interrupt | off 1-24 + off 203 |

## JNI ring control

`libledLight-jni.so` exports a single symbol bound to the stock class name, so the mod ships
`com.phicomm.speaker.player.light.LedLight` as the binding class. Physical mask layout:
bits 0–14 = RGB ring (LEDs 1–15), bits 15–38 = 24 directional white LEDs (LEDs 16–39);
msgcenter wakeup id N maps to bit `14 + N`.

Music light is a port of stock `PlayerVisualizer` modes 0–3 (`music_light_mode` config).
While running it claims msgcenter 519 — in the real `lights_effects.conf` this blanks the ring
and holds channel 0 so `LightsEffectService` does not draw over the JNI writes. Without the JNI
library the music light stays off (519 alone renders black).

If the wake LED lights on the opposite side, enable **Reverse DOA Angle** (`reverse_doa`).

Build: `./build.sh`

## 4-mic array (mod-owned)

Ava uses mono `AudioRecord` for wake/STT. Optional **4-mic array** (settings → **4-Mic Array (DOA)**, **default OFF**) loads stock `libUni4micHalJNI.so` for directional wake LEDs only when explicitly enabled.

1. Load `libUni4micHalJNI.so` (system/vendor → legacy 小讯 APK lib → root `find` + cache copy)
2. `init(1)` + stock options (`set4MicDebugMode`, `close4MicAlgorithm(0)`, `set4MicWakeUpStatus`)
3. `openAudioIn(2)` + `startRecorder` + background `readData` pump (required for live DOA)
4. On Ava `wake_detected`: `set4MicWakeUpStatus(1)` → `get4MicDoaResult()` → directional LED 1–24

If the HAL library is missing, a lightweight `AudioRecord` fallback estimator is used (less accurate, may conflict with Ava's mic).

Device auto-detection: `isSupported()` checks Phicomm/rk322x build props **and** `msgcenter` IPC availability.
