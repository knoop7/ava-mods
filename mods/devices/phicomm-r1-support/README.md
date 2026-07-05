# Phicomm R1 Support

Replicates stock 小讯 (Unisound) voice LED behavior on Phicomm R1 via Ava voice pipeline + msgcenter IPC.

## Stock behavior

| Phase | IPC |
|-------|-----|
| Wake (DOA) | `sendMessage(4096, 1-24, 0, null)` directional white |
| User finished speaking | off 1-24 + `sendMessage(4096, 203, 0, null)` blue loading |
| TTS finished | `sendMessage(4096, 203, 1, null)` |
| Session interrupt | off 1-24 + off 203 |

Build: `./build.sh`

## 4-mic array (mod-owned)

Ava uses mono `AudioRecord` for wake/STT. Optional **4-mic array** (settings → **4-Mic Array (DOA)**, **default OFF**) loads stock `libUni4micHalJNI.so` for directional wake LEDs only when explicitly enabled.

1. Load `libUni4micHalJNI.so` (system/vendor → legacy 小讯 APK lib → root `find` + cache copy)
2. `init(1)` + stock options (`set4MicDebugMode`, `close4MicAlgorithm(0)`, `set4MicWakeUpStatus`)
3. `openAudioIn(2)` + `startRecorder` + background `readData` pump (required for live DOA)
4. On Ava `wake_detected`: `set4MicWakeUpStatus(1)` → `get4MicDoaResult()` → directional LED 1–24

If the HAL library is missing, a lightweight `AudioRecord` fallback estimator is used (less accurate, may conflict with Ava's mic).

Device auto-detection: `isSupported()` checks Phicomm/rk322x build props **and** `msgcenter` IPC availability.
