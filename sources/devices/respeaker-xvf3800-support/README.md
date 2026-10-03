# reSpeaker XVF3800 Support

Support for the [Seeed reSpeaker XVF3800](https://wiki.seeedstudio.com/respeaker_xvf3800_introduction/)
4-mic USB array in [Ava](https://github.com/knoop7/Ava), for setups where the array is plugged into
the Android device and serves as Ava's microphone and speaker output.

- **LED ring** shows Ava's voice session instead of every sound in the room.
- **Beam lock** (optional, off by default) holds the beamformer on the person who said the wake
  word for the duration of the command.
- **Diagnostics** in Home Assistant: control channel state, event log, learned maps.

## Requirements

- reSpeaker XVF3800 running **USB firmware** (not the I2S firmware used with the XIAO ESP32-S3 /
  ESPHome). The LED ring needs USB firmware **2.0.7 or newer**. Tested with 2.1.0 (48k2ch).
- The array connected to the Android device by USB, with Ava using it for audio.
- Android 5.0+ (API 21). Tested on a Galaxy S21 with Ava Pro 0.7.6 and 0.7.7.

After the array is plugged in, Android asks once for **USB permission** for Ava. Tap *OK*. The
permission lasts while the device stays plugged in; Ava's manifest has no USB device filter, so
a mod cannot make it permanent.

## LED ring

| State | Animation |
|-------|-----------|
| Idle | dark (or the firmware's direction effect, see *LED ring* mode) |
| Wake word | cyan comet |
| Listening | breathing cone of light pointing at the speaker (when the beam lock knows the direction) |
| Thinking | violet/magenta colour swirl |
| Answering | turquoise waves |
| Error | two red flashes |

The animations are computed on the phone and written to the ring at 20 frames per second.
When the mod is disabled, the ring is handed back to the firmware's direction effect and the
USB channels go back to their factory routing.

## Beam lock

The XVF3800 picks its beam on its own. When a TV or loudspeaker talks louder than you, it may
listen to that instead. With the beam lock on:

1. A background poll keeps measuring direction and speech energy of the beams while idle.
2. At the wake word the mod takes the direction where speech **started** in the 1.5 s before
   the event, compared with the 4.5 s before that. A TV that was already talking does not count
   as an onset.
3. The fixed beams are pinned to that direction, and **both** USB channels are switched to the
   fixed beam - Android records mono and mixes left and right, so switching one is not enough.
4. The beam follows small movements during the command and is released when speech recognition
   finishes, at the end of the session, or by the watchdog.

**Interference zones** are learned automatically: a direction that carries speech steadily from
one place for about a minute (TV, radio, speakers) is blocked. People who move or pause are not
learned, and directions where commands came from before learn more slowly (the *speaker map*).
Zones of a source that went quiet fade after about ten minutes. Up to three directions can also
be blocked by hand (*Interferer direction*, `-1` = unused).

If Ava dies during a lock (crash, force stop, update), the channels would stay on the fixed beam.
On the next connection the mod detects this and restores the idle routing.

### What to expect

Measured on a real setup, honestly:

- **Audio played through the reSpeaker itself** (Ava's music, TTS, video with Ava as the player):
  the chip's echo cancellation removes it, because it has the playback as reference. 5 of 5 wake
  words with video and a robot vacuum running, 9 of 9 commands transcribed correctly. Beam lock on
  or off made **no measurable difference** here.
- **External loudspeakers close to the array** (PC speakers on the same desk): the locks landed on
  the person every time, but the external audio still reached the transcript. In the near field
  the array's measured directivity is under 2 dB, so pinning the beam cannot remove a nearby
  source.
- **TV at a distance, louder than the speaker**: the wake word itself barely got through, and no
  direction could be measured.

The beam lock is therefore off by default. It is most useful against a quieter external source at
a distance and in a different direction than you. It cannot separate a source that is louder than
you or in the same direction.

## Entities

| Entity | Type | Description |
|--------|------|-------------|
| LED ring | select | *Voice status* (dark when idle), *Voice status and direction* (firmware direction effect when idle), *Do not control* |
| LED brightness | select | 10 / 25 / 50 / 100 % |
| LED speed | select | Slow / Medium / Fast |
| Beam lock | switch | Turns the beam lock on or off |
| Locked direction | sensor | Direction of the current lock in degrees; Home Assistant shows 0 while no lock is held |
| Release beam | button | Releases a running lock immediately |
| Wake word channels | select | What Ava hears while waiting for the wake word: *Factory default* (right ASR beam + left conference), *Both ASR*, *Both conference* |
| Interferer direction 1-3 | number | Manually blocked directions, `-1` = unused |
| Reset learned maps | button | Clears interference zones and speaker map, e.g. after moving the device |
| Control channel | text sensor (diagnostic) | USB control connection and firmware version |
| Last event | text sensor (diagnostic) | Last line of the event log |
| Learned interference zones | text sensor (diagnostic) | Blocked directions with weight |
| Speaker map | text sensor (diagnostic) | Directions where commands came from |

Directions are in the array's own frame: 0 to 359 degrees as reported by the XVF3800.

## Settings

All beam lock settings are in the mod's settings in Ava (*Mod Store → Installed*).

| Setting | Default | Description |
|---------|---------|-------------|
| Learn interference zones | on | Learn and block stationary speech sources |
| Lookback lock | on | Take the direction from the moment the wake word was spoken |
| Release after speech recognition | on | Release as soon as the command is recognised |
| Correct at speech start | off | Move the lock if speech starts far from it (only logged when off) |
| Fast lock | on | Fallback: direction of the chip's auto-select beam at the wake word |
| Switch channels | on | Route both USB channels to the locked beam during a command |
| Track the speaker | on | Follow small movements during the command |
| Gating | off | Mute beams outside the active direction; for experiments only |
| Tracking window | 35° | How far the speaker may move and still be followed |
| Minimum speech energy | 1000 | Readings below are ignored (silence reads 0, speech 50,000+) |
| Search time | 8 s | How long to look for the speaker when the lookback finds nothing |
| Watchdog | 30 s | Release after this time at the latest |

## Notes

- **Updating the mod:** Ava keeps a mod's class loader for the life of its process. After
  importing a new version, force-stop Ava (or reboot) before the new code runs.
- **Interferer directions reset:** Ava sets number entities to their minimum (`-1`) when the voice
  service starts, so manual interferer directions do not survive a restart. Learned zones do.
- **Control protocol:** all parameters are USB control transfers to the device on endpoint 0, so no
  interface is claimed and Ava's audio interfaces are not disturbed.
- **Log:** `adb logcat -s ReSpeaker`

## Credits

- Control protocol and parameter IDs from Seeed's
  [reSpeaker XVF3800 repository](https://github.com/respeaker/reSpeaker_XVF3800_USB_4MIC_ARRAY)
  (`python_control`).
- Locking on the direction from before the wake word, both fixed beams on the speaker and the
  LED index mapping follow [FormatBCE's ESPHome integration](https://github.com/formatBCE/Respeaker-XVF3800-ESPHome-integration).

## Building

```bash
./build.sh
```

Needs a JDK (11+), Android SDK platform 34 and the build-tools (`d8`). The script compiles with
`--release 8`, converts to DEX, writes `libs/respeaker-xvf3800-support.jar`, updates `jar_hash` in
`manifest.json` and, inside the ava-mods repository, copies the package to
`mods/devices/respeaker-xvf3800-support/`.
