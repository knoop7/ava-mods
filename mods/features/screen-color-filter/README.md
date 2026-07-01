# Screen Color Filter Mod

Global screen tint overlay for [Ava](https://github.com/knoop7/Ava), controllable from Home Assistant.

## Features

- Home Assistant `select` entity with options: off, red, blue, dark, yellow, green, gray
- Full-screen translucent overlay above all Ava foreground layers
- Window layout matches Ava overlay services (Weather, Quick Entity, WebView): edge-to-edge with cutout support
- Touch passthrough (`FLAG_NOT_TOUCHABLE`)
- Configurable opacity (0–100) in Ava mod settings
- Persists selected color across restarts
- Opt-in `overlay_z_order` hook keeps the tint above notification/voice overlays

## Requirements

- Android 6.0+ recommended (overlay permission prompt on API 23+)
- Ava build with `ModOverlayZOrderBridge` support
- Display over other apps permission granted to Ava

## Build

```bash
cd sources/features/screen-color-filter
chmod +x build.sh
./build.sh
cp manifest.json ../../mods/features/screen-color-filter/
cp libs/screen-color-filter-manager.jar ../../mods/features/screen-color-filter/libs/
```

## Home Assistant

Entity object id: `screen_color_filter_color_filter`

Example service call:

```yaml
service: select.select_option
target:
  entity_id: select.<device>_screen_color_filter_color_filter
data:
  option: red
```

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.
