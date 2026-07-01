#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MOD_ID="ha-edge-tts"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/ha-edge-tts-manager.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    exit 1
fi

if [ ! -f "$D8_TOOL" ]; then
    D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
fi
if [ ! -f "$D8_TOOL" ]; then
    echo "Error: d8 tool not found"
    exit 1
fi

export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home -v 1.8 2>/dev/null)
JAVAC_BIN="${JAVAC8:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}/bin/javac"
if [ ! -x "$JAVAC_BIN" ]; then
    JAVAC_BIN="javac"
fi
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" libs

echo "Compiling Java sources..."
"$JAVAC_BIN" -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    src/com/ava/mods/edgetts/*.java

echo "Converting mod classes to DEX..."
mkdir -p "$BUILD_DIR/mod-dex"
"$D8_TOOL" --output "$BUILD_DIR/mod-dex" "$BUILD_DIR/classes/com/ava/mods/edgetts/"*.class
jar cf "$OUTPUT_JAR" -C "$BUILD_DIR/mod-dex" classes.dex

JAR_HASH=$(md5 -q "$OUTPUT_JAR")
MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"

for MANIFEST in manifest.json "$MODS_DIR/manifest.json"; do
    if grep -q '"jar_hash"' "$MANIFEST"; then
        sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MANIFEST"
    fi
done

if grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON"; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$MOD_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
else
    echo "Warning: $MOD_ID not found in store.json, skipping store update"
fi

echo "Done."
echo "JAR Hash: $JAR_HASH"
ls -lh "$OUTPUT_JAR"
echo "Release package copied to $MODS_DIR"
