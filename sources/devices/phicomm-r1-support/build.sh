#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/35.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/phicomm-r1-support.jar"
MOD_ID="phicomm-r1-support"
MANAGER="com.ava.mods.phicomm.PhicommR1SupportManager"
MODS_DIR="$REPO_ROOT/mods/devices/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

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

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" libs

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR" \
    src/com/ava/mods/phicomm/*.java \
    src/com/unisound/jni/*.java \
    src/com/phicomm/speaker/player/light/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

JAVA11_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null \
    || /usr/libexec/java_home -v 21 2>/dev/null \
    || /usr/libexec/java_home -v 11 2>/dev/null)
if [ -n "$JAVA11_HOME" ]; then
    export JAVA_HOME="$JAVA11_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "Converting to DEX..."
"$D8_TOOL" --min-api 21 --lib "$ANDROID_JAR" --output "$BUILD_DIR" \
    $(find "$BUILD_DIR" -name '*.class')

if [ $? -ne 0 ]; then
    echo "DEX conversion failed!"
    exit 1
fi

echo "Creating JAR..."
cd "$BUILD_DIR"
jar cvf "../$OUTPUT_JAR" classes.dex
cd ..

JAR_HASH=$(md5 -q "$OUTPUT_JAR")
echo "JAR Hash: $JAR_HASH"

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"
[ -f README.md ] && cp README.md "$MODS_DIR/README.md"

for f in manifest.json "$MODS_DIR/manifest.json"; do
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$f"
done

MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

if grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON" 2>/dev/null; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$MOD_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
    echo "Updated store.json"
else
    echo "NOTE: add store.json entry for $MOD_ID on first publish."
fi

echo "Build complete: v$MOD_VERSION hash $JAR_HASH"
