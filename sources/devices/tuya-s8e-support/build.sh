#!/bin/bash

# Ava Mod Build Script
# Compiles Java source to DEX-based JAR for Android DexClassLoader
# Requirements: Android SDK, Java 11+
# Usage: ./build.sh [version]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/tuya-s8e-support.jar"
MOD_ID="tuya-s8e-support"
MODS_DIR="$REPO_ROOT/mods/devices/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

NEW_VERSION="$1"

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
    src/com/ava/mods/tuyas8e/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Converting to DEX..."
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null)
export PATH="$JAVA_HOME/bin:$PATH"
"$D8_TOOL" --output "$BUILD_DIR" "$BUILD_DIR"/com/ava/mods/tuyas8e/*.class

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
unzip -l "$OUTPUT_JAR"

JAR_HASH=$(md5 -q "$OUTPUT_JAR")
echo ""
echo "JAR Hash: $JAR_HASH"

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"

if [ -n "$NEW_VERSION" ]; then
    sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$NEW_VERSION\"/" manifest.json
    sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$NEW_VERSION\"/" "$MODS_DIR/manifest.json"

    if grep -q '"jar_hash"' manifest.json; then
        sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" manifest.json
    else
        sed -i '' "s/\"manager\": \"com.ava.mods.tuyas8e.TuyaS8EManager\",/\"manager\": \"com.ava.mods.tuyas8e.TuyaS8EManager\",\\
  \"jar_hash\": \"$JAR_HASH\",/" manifest.json
    fi

    if grep -q '"jar_hash"' "$MODS_DIR/manifest.json"; then
        sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MODS_DIR/manifest.json"
    else
        sed -i '' "s/\"manager\": \"com.ava.mods.tuyas8e.TuyaS8EManager\",/\"manager\": \"com.ava.mods.tuyas8e.TuyaS8EManager\",\\
  \"jar_hash\": \"$JAR_HASH\",/" "$MODS_DIR/manifest.json"
    fi

    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$NEW_VERSION\${2}/s" "$STORE_JSON"
    if grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON"; then
        if grep -q "\"id\": \"$MOD_ID\".*\"jar_hash\"" "$STORE_JSON"; then
            perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
        fi
    fi
fi

