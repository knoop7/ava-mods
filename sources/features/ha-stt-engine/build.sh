#!/bin/bash
set -euo pipefail

# Ava Mod Build Script - HA STT Engine
# Compiles Java sources and bundles sherpa-onnx for DexClassLoader.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MOD_ID="ha-stt-engine"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/ha-stt-engine-manager.jar"
SHERPA_JAR="libs/sherpa-onnx.jar"
SHERPA_CLASSES_JAR="libs/sherpa-onnx-classes.jar"
SHERPA_VERSION="1.12.33"
SHERPA_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"

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
mkdir -p "$BUILD_DIR/classes" libs libs/jni/arm64-v8a libs/jni/armeabi-v7a

if [ ! -f "$SHERPA_JAR" ] || [ ! -f libs/jni/arm64-v8a/libsherpa-onnx-jni.so ]; then
    echo "Fetching sherpa-onnx ${SHERPA_VERSION}..."
    TMP_DIR="$BUILD_DIR/sherpa"
    mkdir -p "$TMP_DIR"
    curl -fsSL "$SHERPA_AAR_URL" -o "$TMP_DIR/sherpa.aar"
    unzip -q -o "$TMP_DIR/sherpa.aar" -d "$TMP_DIR/extracted"
    cp "$TMP_DIR/extracted/jni/arm64-v8a/"*.so libs/jni/arm64-v8a/
    cp "$TMP_DIR/extracted/jni/armeabi-v7a/"*.so libs/jni/armeabi-v7a/
    cp "$TMP_DIR/extracted/classes.jar" "$SHERPA_CLASSES_JAR"
    mkdir -p "$BUILD_DIR/sherpa-dex"
    KOTLIN_STDLIB=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-*.jar" 2>/dev/null | sort -V | tail -1)
    if [ -n "$KOTLIN_STDLIB" ] && [ -f "$KOTLIN_STDLIB" ]; then
        "$D8_TOOL" --output "$BUILD_DIR/sherpa-dex" "$SHERPA_CLASSES_JAR" "$KOTLIN_STDLIB"
    else
        echo "Warning: kotlin-stdlib not found; sherpa runtime may fail"
        "$D8_TOOL" --output "$BUILD_DIR/sherpa-dex" "$SHERPA_CLASSES_JAR"
    fi
    jar cf "$SHERPA_JAR" -C "$BUILD_DIR/sherpa-dex" classes.dex
elif [ ! -f "$SHERPA_CLASSES_JAR" ]; then
    echo "Fetching sherpa classes for compile classpath..."
    TMP_DIR="$BUILD_DIR/sherpa"
    mkdir -p "$TMP_DIR"
    curl -fsSL "$SHERPA_AAR_URL" -o "$TMP_DIR/sherpa.aar"
    unzip -q -o "$TMP_DIR/sherpa.aar" -d "$TMP_DIR/extracted"
    cp "$TMP_DIR/extracted/classes.jar" "$SHERPA_CLASSES_JAR"
fi

echo "Compiling Java sources..."
"$JAVAC_BIN" -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR:$SHERPA_CLASSES_JAR" \
    -d "$BUILD_DIR/classes" \
    src/com/ava/mods/hasttengine/*.java

echo "Converting mod classes to DEX..."
mkdir -p "$BUILD_DIR/mod-dex"
"$D8_TOOL" --output "$BUILD_DIR/mod-dex" "$BUILD_DIR/classes/com/ava/mods/hasttengine/"*.class
jar cf "$OUTPUT_JAR" -C "$BUILD_DIR/mod-dex" classes.dex

JAR_HASH=$(md5 -q "$OUTPUT_JAR")
MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

mkdir -p "$MODS_DIR/libs/jni/arm64-v8a" "$MODS_DIR/libs/jni/armeabi-v7a"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp "$SHERPA_JAR" "$MODS_DIR/libs/"
cp libs/jni/arm64-v8a/*.so "$MODS_DIR/libs/jni/arm64-v8a/"
cp libs/jni/armeabi-v7a/*.so "$MODS_DIR/libs/jni/armeabi-v7a/"
cp manifest.json "$MODS_DIR/manifest.json"
cp README.md "$MODS_DIR/README.md"

for MANIFEST in manifest.json "$MODS_DIR/manifest.json"; do
    if grep -q '"jar_hash"' "$MANIFEST"; then
        sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MANIFEST"
    fi
done

if grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON"; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$MOD_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
fi

echo "Done."
echo "JAR Hash: $JAR_HASH"
ls -lh "$OUTPUT_JAR" "$SHERPA_JAR"
ls -lh libs/jni/arm64-v8a/
echo "Release package copied to $MODS_DIR"
echo "Synced 4 places: sources+mods manifest (v$MOD_VERSION, hash $JAR_HASH) and store.json"
