# HA STT Engine

Local Wyoming offline STT for Home Assistant (zh/en).

## Overview

- Wyoming protocol on TCP port 10300 (configurable)
- mDNS discovery (`_wyoming._tcp`) for Home Assistant auto-discovery
- On-demand SenseVoice model download to external app storage (~230MB)
- Offline recognition with emotion and audio-event metadata
- Home Assistant diagnostic entities for server status, download progress, and last transcript

## Home Assistant Setup

1. Enable this mod in Ava and wait for the model download to finish.
2. In Home Assistant, add the **Wyoming Protocol** integration.
3. Connect to the Ava device IP on port `10300` (or your configured port).
4. Select **HA STT Engine** as the ASR engine in your voice assistant pipeline.

## Build

```bash
cd sources/features/ha-stt-engine
chmod +x build.sh
./build.sh
```

Every `./build.sh` run keeps these **4 places** in sync:

| # | Path |
|---|------|
| 1 | `sources/features/ha-stt-engine/manifest.json` |
| 2 | `sources/features/ha-stt-engine/libs/ha-stt-engine-manager.jar` |
| 3 | `mods/features/ha-stt-engine/manifest.json` + `libs/` |
| 4 | `store.json` entry (`version` + `jar_hash`) |

Requirements: Android SDK platform 34, Java 8 (javac) + Java 11+ (d8), network access for the first sherpa-onnx download.

## Model Storage

Models are stored under the app's external files directory:

```
Android/data/com.example.ava/files/ha-stt-engine/
├── model.int8.onnx
├── tokens.txt
└── downloaded
```

## Technical Notes

- Based on the SenseVoice int8 model from ModelScope (`xiaowangge/sherpa-onnx-sense-voice-small`)
- Inference via sherpa-onnx JNI (bundled per ABI)
- Audio format: 16 kHz, 16-bit PCM, mono
- Recognition runs when Wyoming sends `audio-stop` (same offline batch pattern as the reference add-on)
