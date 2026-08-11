#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/35.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/screen-capture.jar"
MOD_ID="screen-capture-mod"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"

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

JDK_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null || true)"
if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/javac" ]; then
    for candidate in \
        "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        /Library/Java/JavaVirtualMachines/*/Contents/Home; do
        if [ -x "$candidate/bin/javac" ]; then
            JDK_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/javac" ]; then
    echo "Error: no JDK 11+ found"
    exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using JDK: $JAVA_HOME"

echo "Compiling Java sources..."
javac --release 8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR" \
    src/com/ava/mods/screencapture/*.java
if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Converting to DEX..."
find "$BUILD_DIR" -name "*.class" -print0 | xargs -0 "$D8_TOOL" --lib "$ANDROID_JAR" --min-api 21 --output "$BUILD_DIR"
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
echo "JAR Hash: $JAR_HASH"

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"
cp README.md "$MODS_DIR/README.md"

if grep -q '"jar_hash"' manifest.json; then
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" manifest.json
else
    sed -i '' "s/\"manager\": \"com.ava.mods.screencapture.ScreenCaptureManager\",/\"manager\": \"com.ava.mods.screencapture.ScreenCaptureManager\",\\
  \"jar_hash\": \"$JAR_HASH\",/" manifest.json
fi
cp manifest.json "$MODS_DIR/manifest.json"
if grep -q '"jar_hash"' "$MODS_DIR/manifest.json"; then
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MODS_DIR/manifest.json"
fi

echo "Published to $MODS_DIR"
