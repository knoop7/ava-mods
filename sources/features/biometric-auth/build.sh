#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MOD_ID="biometric-auth"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/biometric-auth-manager.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    exit 1
fi

if [ ! -f "$D8_TOOL" ]; then
    D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$D8_TOOL" ] || [ ! -f "$D8_TOOL" ]; then
    echo "Error: d8 tool not found"
    exit 1
fi

export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home -v 1.8 2>/dev/null || true)
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" libs

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    src/com/ava/mods/biometric/*.java

echo "Converting to DEX..."
mkdir -p "$BUILD_DIR/mod-dex"
find "$BUILD_DIR/classes" -name "*.class" -print0 | xargs -0 "$D8_TOOL" --output "$BUILD_DIR/mod-dex"
jar cf "$OUTPUT_JAR" -C "$BUILD_DIR/mod-dex" classes.dex

if command -v md5 >/dev/null 2>&1; then
    JAR_HASH=$(md5 -q "$OUTPUT_JAR")
else
    JAR_HASH=$(md5sum "$OUTPUT_JAR" | awk '{print $1}')
fi
MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"
cp README.md "$MODS_DIR/README.md"

python3 - <<PY
import json
from pathlib import Path

jar_hash = "$JAR_HASH"
mod_id = "$MOD_ID"
mod_version = "$MOD_VERSION"
store_path = Path("$STORE_JSON")
manifest_paths = [Path("manifest.json"), Path("$MODS_DIR/manifest.json")]

for path in manifest_paths:
    data = json.loads(path.read_text())
    data["jar_hash"] = jar_hash
    path.write_text(json.dumps(data, indent=2) + "\n")

store = json.loads(store_path.read_text())
entry = {
    "id": mod_id,
    "name": "Biometric Auth",
    "version": mod_version,
    "author": "Ava",
    "description": "Ask for a fingerprint on this device, then unlock secure Home Assistant actions.",
    "detail_description": "Home Assistant presses Authenticate → Ava shows the system biometric / fingerprint prompt on the panel. On success, binary_sensor.authenticated pulses on briefly so automations can unlock a door or run a sensitive script. Works with BiometricPrompt on Android 9+ and FingerprintManager on Android 6–8. Ava never sees fingerprint templates — only success or failure.",
    "path": "mods/features/%s/" % mod_id,
    "jar_hash": jar_hash,
    "icon": "mdi:fingerprint",
}

mods = store.get("mods", [])
found = False
for i, mod in enumerate(mods):
    if mod.get("id") == mod_id:
        mods[i] = entry
        found = True
        break
if not found:
    mods.append(entry)
store["mods"] = mods
store_path.write_text(json.dumps(store, indent=2) + "\n")
print("store.json updated (%s)" % ("replaced" if found else "appended"))
PY

echo "Done."
echo "JAR Hash: $JAR_HASH"
ls -lh "$OUTPUT_JAR"
echo "Release package copied to $MODS_DIR"
