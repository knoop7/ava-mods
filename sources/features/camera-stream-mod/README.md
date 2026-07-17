# Camera Stream Mod

LAN camera forwarder for Frigate / go2rtc / ffmpeg.

## Formats

- **mjpeg** — HTTP `multipart/x-mixed-replace` (easy in browsers / ffmpeg)
- **rtsp** — H.264 over RTSP (TCP interleaved), for Frigate / NVR

## Lifecycle (important)

- **No auto-start.** Enabling the mod does not open the camera.
- Stream starts only when you turn on the **Camera Stream** switch **and** the Ava voice satellite **master service is running**.
- When the master service stops (or the mod is destroyed), the stream **always** stops.

## Quality

Config: FPS, resolution (short edge), JPEG quality (MJPEG), bitrate (RTSP), front/rear camera, port, path, optional token.

## Build

```bash
./build.sh
```

Install from Mod Store after publishing under `mods/features/camera-stream-mod/`.
