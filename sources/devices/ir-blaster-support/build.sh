#!/bin/bash

# Ava Mod Build Script — IR Blaster (Home Assistant)
# Compiles Java source to a DEX-based JAR for Android DexClassLoader.
# Requirements: Android SDK (platform 34 + build-tools with d8), Java 11+
# Usage: ./build.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/35.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/ir-blaster-support.jar"
MOD_ID="ir-blaster-support"
MANAGER="com.ava.mods.irblaster.IrBlasterManager"
MODS_DIR="$REPO_ROOT/mods/devices/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    echo "Please set ANDROID_HOME or install Android SDK platform 34"
    exit 1
fi

if [ ! -f "$D8_TOOL" ]; then
    D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$D8_TOOL" ] || [ ! -f "$D8_TOOL" ]; then
    echo "Error: d8 tool not found in Android SDK build-tools"
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p libs

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR" \
    src/com/ava/mods/irblaster/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Converting to DEX..."
# d8 needs Java 11+. Prefer java_home, then fall back to Android Studio JBR / installed JDKs.
JAVA11_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null \
    || /usr/libexec/java_home -v 21 2>/dev/null \
    || /usr/libexec/java_home -v 11 2>/dev/null)
if [ -z "$JAVA11_HOME" ]; then
    for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
             /Library/Java/JavaVirtualMachines/jbr-17*/Contents/Home \
             /Library/Java/JavaVirtualMachines/temurin-*/Contents/Home \
             /Library/Java/JavaVirtualMachines/zulu-17*/Contents/Home; do
        if [ -x "$c/bin/java" ]; then JAVA11_HOME="$c"; break; fi
    done
fi
if [ -n "$JAVA11_HOME" ]; then
    export JAVA_HOME="$JAVA11_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
"$D8_TOOL" --min-api 21 --lib "$ANDROID_JAR" --output "$BUILD_DIR" \
    "$BUILD_DIR"/com/ava/mods/irblaster/*.class

if [ $? -ne 0 ]; then
    echo "DEX conversion failed!"
    exit 1
fi

echo "Creating JAR with DEX..."
cd "$BUILD_DIR"
jar cvf ../$OUTPUT_JAR classes.dex
cd ..

echo "Done! JAR created at $OUTPUT_JAR"
ls -la "$OUTPUT_JAR"

JAR_HASH=$(md5 -q "$OUTPUT_JAR")
echo ""
echo "JAR Hash: $JAR_HASH"

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"
cp README.md "$MODS_DIR/README.md"

for f in manifest.json "$MODS_DIR/manifest.json"; do
    if grep -q '"jar_hash"' "$f"; then
        sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$f"
    else
        sed -i '' "s/\"manager\": \"$MANAGER\",/\"manager\": \"$MANAGER\",\\
  \"jar_hash\": \"$JAR_HASH\",/" "$f"
    fi
done

MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

if grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON"; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$MOD_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
    echo "Updated store.json entry for $MOD_ID (v$MOD_VERSION)"
else
    echo "NOTE: add a store.json entry for $MOD_ID manually (first publish)."
fi

echo "Release package copied to $MODS_DIR"
echo "Build complete: v$MOD_VERSION hash $JAR_HASH"
