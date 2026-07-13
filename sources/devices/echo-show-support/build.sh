#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/35.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/echo-show-support.jar"
MOD_ID="echo-show-support"
MODS_DIR="$REPO_ROOT/mods/devices/$MOD_ID"

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
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)
export PATH="$JAVA_HOME/bin:$PATH"
javac --release 8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR" \
    src/com/ava/mods/echoshow/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Converting to DEX..."
find "$BUILD_DIR" -name "*.class" -print0 | xargs -0 "$D8_TOOL" --output "$BUILD_DIR"

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

if grep -q '"jar_hash"' manifest.json; then
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" manifest.json
else
    sed -i '' "s/\"manager\": \"com.ava.mods.echoshow.EchoShowSupportManager\",/\"manager\": \"com.ava.mods.echoshow.EchoShowSupportManager\",\\
  \"jar_hash\": \"$JAR_HASH\",/" manifest.json
fi

if grep -q '"jar_hash"' "$MODS_DIR/manifest.json"; then
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MODS_DIR/manifest.json"
else
    cp manifest.json "$MODS_DIR/manifest.json"
fi

STORE_JSON="$REPO_ROOT/store.json"
if [ -f "$STORE_JSON" ]; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
fi

echo "Published to $MODS_DIR"
unzip -l "$OUTPUT_JAR"
