<p align="center">
  <strong>Ava Mods</strong><br>
  <sub>runtime extensions for <a href="https://github.com/knoop7/Ava">Ava</a></sub>
</p>

<p align="center">
  <code>load</code> · <code>store</code> · <code>sync</code> · <code>hot-update</code>
</p>

Ava Mods is the open extension layer for [Ava](https://github.com/knoop7/Ava) — voice-first smart-home panels on Android. Mods ship as DEX JARs with optional native libraries, loaded at runtime so you never rebuild the main APK to wire up GPIO, cast protocols, sensors, or Home Assistant entities. Enable one in the app and the voice satellite restarts; switches, sensors, lights, and buttons sync to HA bidirectionally, on the same contract as built-in integrations. The core APK stays stable and auditable; hardware and protocol evolution lives in the mod catalog — a deliberate split.

See [Creating Mods](docs/CREATING_MODS.md) and [Manifest Spec](docs/MANIFEST_SPEC.md) to develop — Android SDK 34+, JDK 11+, `d8`. `manifest.json` names the Manager class, dependency JARs, entities, and HA domains (switch, sensor, button, light, …); compatibility-only mods may expose overlay or ducking hooks with no entities at all. Copy `examples/` for a minimal skeleton; heavier mods bundle NDK libs and third-party JARs. `chmod +x build.sh && ./build.sh` usually compiles, copies into `mods/`, and patches the store. Contribute: fork → `sources/` → build → `mods/` → `store.json` → PR — English docs, real-hardware tests, no secrets in manifests. [MIT](LICENSE)

<p align="center">
  <sub><a href="https://github.com/knoop7/Ava">Ava</a> · <a href="https://www.home-assistant.io/">Home Assistant</a> · <a href="https://esphome.io/">ESPHome</a></sub>
</p>
