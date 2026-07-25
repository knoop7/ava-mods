# Biometric Auth

Android biometric / fingerprint gate for secure Home Assistant actions ([Ava#137](https://github.com/knoop7/Ava/issues/137)).

## What it does

1. Home Assistant presses **Authenticate**.
2. If no print is enrolled: full-screen wizard → launches **system** Settings enroll (`FingerprintEnrollIntroduction` / `BiometricEnrollActivity`) so the TEE stores the template (apps cannot enroll themselves).
3. When you return to Ava, enrollment is re-checked; Authenticate then continues to verify automatically.
4. Verify uses system `BiometricPrompt`, with CinemaOverlay-style + `FingerprintManager` fallback.
5. Success pulses `binary_sensor.authenticated` for automations.

Host APK must declare `USE_BIOMETRIC`. Overlay needs Ava’s `SYSTEM_ALERT_WINDOW`.

## Home Assistant entities

| Entity | Type | Purpose |
|--------|------|---------|
| `authenticate` | button | Start biometric prompt; if none enrolled, opens system fingerprint enroll |
| `cancel` | button | Dismiss in-flight prompt |
| `authenticated` | binary_sensor | Short pulse after success |
| `available` | binary_sensor | Hardware + enrolled biometrics ready |
| `prompt_active` | binary_sensor | Dialog currently showing |

Entity IDs are prefixed by Ava with the device / mod naming rules (e.g. `binary_sensor.ava_xxxx_authenticated`).

## Example automation

```yaml
alias: Unlock door after Ava fingerprint
trigger:
  - platform: state
    entity_id: binary_sensor.YOUR_AVA_AUTHENTICATED
    to: "on"
action:
  - service: lock.unlock
    target:
      entity_id: lock.front_door
```

Dashboard button:

```yaml
type: button
name: Unlock with fingerprint
tap_action:
  action: call-service
  service: button.press
  target:
    entity_id: button.YOUR_AVA_AUTHENTICATE
```

## Config

- **Prompt Title / Subtitle** — text on the system dialog
- **Cancel Button Label** — negative button text
- **Success Hold Seconds** — how long `authenticated` stays on (default 3)

## Requirements

- Android 6.0+ with a fingerprint / biometric sensor and at least one enrolled print/face
- Permissions: `USE_BIOMETRIC`, `USE_FINGERPRINT`
- For Android 6–8 overlay UI: Ava overlay permission (`SYSTEM_ALERT_WINDOW`) helps show the prompt panel; auth still runs without it

## Build

```bash
cd sources/features/biometric-auth
chmod +x build.sh
./build.sh
```

Outputs `libs/biometric-auth-manager.jar` and syncs `mods/features/biometric-auth/` + `store.json`.

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.
