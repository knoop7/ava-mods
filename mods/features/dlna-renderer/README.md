# DLNA Renderer Mod

Makes Ava discoverable as a standard **UPnP/DLNA MediaRenderer:1** on the local
network, so any DLNA controller can push audio directly to the device — no
Music Assistant or Home Assistant required for playback:

- BubbleUPnP, mconnect, Hi-Fi Cast (Android)
- foobar2000 (UPnP output), JRiver, AudioStation / DS Audio (Synology NAS)
- Windows Explorer "Cast to Device"

## Architecture

- **UPnP stack**: [jUPnP 3.0.4](https://github.com/jupnp/jupnp) (the maintained
  successor of Cling, which is dead upstream and whose artifacts are no longer
  downloadable). Jetty 9.4 provides the HTTP transport, as configured by
  jUPnP's own `AndroidUpnpServiceConfiguration`.
- **DMR layer**: ported from
  [DLNA-Cast](https://github.com/devin1014/DLNA-Cast) `dlna-dmr`
  (`AVTransportServiceImpl`, `AudioRenderServiceImpl`, controllers), converted
  from Kotlin/Cling to headless Java/jUPnP. Ava mods cannot register Android
  Services, so the `UpnpService` runs inside the mod manager instead of a
  `DLNARendererService`.
- **Playback**: `android.media.MediaPlayer` (http/https MP3, AAC/M4A, FLAC,
  WAV, OGG — whatever the platform codecs support).

## Services exposed

| Service | Actions |
|---|---|
| AVTransport:1 | SetAVTransportURI, SetNextAVTransportURI, Play, Pause, Stop, Seek, Next, GetPositionInfo, GetTransportInfo, ... with LastChange (GENA) eventing |
| RenderingControl:1 | GetVolume, SetVolume, GetMute, SetMute (maps to STREAM_MUSIC) |
| ConnectionManager:1 | GetProtocolInfo (audio sink formats) |

## Behavior

- **Preemptive playback**: a new `SetAVTransportURI` always replaces the
  current stream — same model as Ava's Sendspin protocol. No local queue;
  the controller owns the playlist (`SetNextAVTransportURI` is honored for
  gapless advance when a track completes).
- **Audio focus**: takes `AUDIOFOCUS_GAIN` while playing, so Ava's own media
  (HA media, built-in player) pauses; releases focus on stop/completion.
- **Voice ducking**: subscribes to Ava's voice pipeline events and ducks DLNA
  playback during wake/listen/TTS, restoring volume afterwards
  (`voice_ducking` config).

## Config

| Key | Default | Description |
|---|---|---|
| `device_name` | `Ava` | Friendly name shown in controller apps |
| `auto_start` | `true` | Start renderer when the mod loads |
| `allow_volume_control` | `true` | Allow controllers to set device volume |
| `voice_ducking` | `true` | Duck playback during voice interactions |
| `show_diagnostic_entities` | `false` | Expose diagnostic entities in HA |

## Entities

- `binary_sensor.server_running` — renderer online (diagnostic)
- `text_sensor.now_playing` — current track (artist - title)
- `text_sensor.playback_state` — playing / paused / loading / stopped (diagnostic)
- `button.restart_server` — restart the UPnP stack (diagnostic)
- `button.stop_playback` — stop DLNA playback

## Building

```bash
./fetch-deps.sh   # downloads jUPnP / Jetty / slf4j jars from Maven Central
./build.sh        # javac + d8 -> libs/dlna-renderer-manager.jar (~1.3 MB)
```

Requires Android SDK (android-34 platform + a recent build-tools d8; old d8
8.2.x crashes on these jars) and any JDK 11+.

## Licenses

- jUPnP: CDDL-1.0
- Jetty: Apache-2.0 / EPL-1.0
- slf4j: MIT
- DLNA-Cast (ported DMR layer): Apache-2.0
