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
cp -R manifest.json libs ../../mods/features/ha-stt-engine/
```

Requirements: Android SDK platform 34, Java 11+, network access for the first sherpa-onnx download.

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
