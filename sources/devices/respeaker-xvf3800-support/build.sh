#!/bin/bash
# Builds the mod: Java -> DEX -> JAR, writes jar_hash into manifest.json and,
# inside the ava-mods repository, publishes the package to mods/devices/.
# Works on Linux and macOS.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." 2>/dev/null && pwd)"

MOD_ID="respeaker-xvf3800-support"
OUTPUT_JAR="libs/$MOD_ID.jar"
BUILD_DIR="build"
MODS_DIR="$REPO_ROOT/mods/devices/$MOD_ID"

if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then
        ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

cd "$SCRIPT_DIR"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    echo "Set ANDROID_HOME or install Android SDK platform 34."
    exit 1
fi

D8_TOOL=$(find "$ANDROID_HOME/build-tools" -name d8 -type f 2>/dev/null | sort -V | tail -1)
if [ -z "$D8_TOOL" ]; then
    echo "Error: d8 not found in $ANDROID_HOME/build-tools"
    exit 1
fi

# d8 needs Java 11 or newer to run; javac --release 8 keeps the bytecode
# compatible with Ava's DexClassLoader.
if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null || true)
    [ -n "$JAVA_HOME" ] && export JAVA_HOME && export PATH="$JAVA_HOME/bin:$PATH"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" libs

echo "Compiling Java sources..."
javac --release 8 -encoding UTF-8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    $(find src -name '*.java')

echo "Converting to DEX..."
# --lib and --min-api avoid desugaring warnings about Android classes.
find "$BUILD_DIR/classes" -name '*.class' -print0 \
    | xargs -0 "$D8_TOOL" --min-api 21 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex"

echo "Creating JAR..."
rm -f "$OUTPUT_JAR"
(cd "$BUILD_DIR/dex" && jar cf "../../$OUTPUT_JAR" classes.dex)

if command -v md5sum >/dev/null 2>&1; then
    JAR_HASH=$(md5sum "$OUTPUT_JAR" | cut -d' ' -f1)
else
    JAR_HASH=$(md5 -q "$OUTPUT_JAR")
fi
echo "JAR: $OUTPUT_JAR, MD5 $JAR_HASH"

# perl instead of sed -i: the in-place syntax differs between GNU and BSD sed.
perl -i -pe "s/(\"jar_hash\"\\s*:\\s*\")[^\"]*(\")/\${1}$JAR_HASH\${2}/" manifest.json

if [ -d "$REPO_ROOT/mods" ]; then
    mkdir -p "$MODS_DIR/libs"
    cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
    cp manifest.json "$MODS_DIR/manifest.json"
    [ -f README.md ] && cp README.md "$MODS_DIR/README.md"
    STORE_JSON="$REPO_ROOT/store.json"
    if [ -f "$STORE_JSON" ]; then
        perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
    fi
    echo "Published to $MODS_DIR"
fi
