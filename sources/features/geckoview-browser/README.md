# GeckoView Browser Mod

Independent Firefox-engine browser overlay for Ava with Home Assistant entity sync.

## Overview

This mod provides a full-featured web browser using Mozilla's GeckoView engine (v68) rendered as a system overlay window. It allows you to display web content on your Ava device and control it from Home Assistant.

**Key features:**
- Firefox/Gecko rendering engine (GeckoView v68, single-process mode)
- System overlay window — works on top of other apps
- Home Assistant entity integration (show/hide switch, URL text sensor)
- Configurable JavaScript, touch input, and user agent
- WebView fallback if GeckoView fails to initialize

## Requirements

- **arm64-v8a** device architecture
- **SYSTEM_ALERT_WINDOW** permission (overlay) — can be auto-granted via root
- **INTERNET** permission
- ~44MB download on first use (GeckoView native libraries)

## Architecture

GeckoView's native libraries (~44MB) are **not** bundled in the mod package. Instead, they are downloaded from Mozilla's Maven repository on first use and cached locally at:

```
/data/data/com.example.ava/files/mods/geckoview-browser/native/
├── geckoview.aar          # Full AAR (used by GeckoRuntime for package loading)
├── jni/arm64-v8a/         # Native .so libraries
│   ├── libmozglue.so
│   └── libxul.so
└── assets/                # Gecko runtime assets
    └── omni.ja
```

The mod uses GeckoView v68 specifically because it supports single-process mode via `useContentProcessHint(false)`, which avoids the need for 120+ manifest service declarations required by newer versions.

## Entities

| Type | ID | Name | Actions |
|------|----|------|---------|
| `switch` | `gecko_browser_visible` | GeckoView Browser | `on`: showBrowser, `off`: hideBrowser |
| `text_sensor` | `gecko_browser_url` | GeckoView URL | `set`: loadUrl, `read`: getCurrentUrl |

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `default_url` | text | *(empty)* | URL to load when browser opens |
| `javascript_enabled` | switch | true | Enable JavaScript in GeckoView |
| `touch_enabled` | switch | true | Allow touch interactions with the browser |
| `user_agent` | select | default | Browser identity: default, desktop, mobile |

## Device Compatibility Hooks

- `isSupported()` — Checks arm64-v8a ABI and overlay permission
- `grantOverlayPermissionIfNeeded()` — Auto-grants overlay permission via root

## Building

```bash
cd sources/features/geckoview-browser/
chmod +x build.sh
./build.sh
```

Requirements:
- Android SDK (platform 34+)
- Java 11+
- d8 tool (included in Android SDK build-tools)

## Technical Notes

### Why SurfaceView instead of GeckoView widget?

The `GeckoView` Android widget crashes with a native SIGSEGV when used inside a system overlay window. The mod works around this by using a plain `SurfaceView` and manually managing `GeckoSession` + `GeckoDisplay` to render browser content.

### ClassLoader and ApplicationInfo patching

Since the native libraries are loaded from a custom path (not the APK's lib directory), the mod:
1. Injects the native library path into `DexPathList.nativeLibraryDirectories` via reflection
2. Temporarily patches `ApplicationInfo.nativeLibraryDir` during `GeckoRuntime.create()`
3. Temporarily patches `ApplicationInfo.sourceDir` to point to the AAR file
4. Restores patched fields after initialization (with a 5-second delay for `nativeLibraryDir`)

### The -greomni argument

GeckoView needs to find its `omni.ja` resource package. The mod passes its location via `-greomni <path>` as a runtime argument through `GeckoRuntimeSettings.Builder.arguments()`. Without this, GeckoThread will fail to locate GRE resources and crash.
