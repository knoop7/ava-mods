#!/bin/bash

# Ava Mod Build Script
# Compiles Java source to DEX-based JAR for Android DexClassLoader
# Requirements: Android SDK, Java 11+
# Usage: ./build.sh [version]  e.g. ./build.sh 1.0.3

# Get script directory (absolute path)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/a64-device-support.jar"
MOD_ID="a64-device-support"
MODS_DIR="$REPO_ROOT/mods/devices/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

# Version from argument
NEW_VERSION="$1"

# Change to script directory
cd "$SCRIPT_DIR"

# Check Android SDK
if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    echo "Please set ANDROID_HOME or install Android SDK platform 34"
    exit 1
fi

# Find d8 tool
if [ ! -f "$D8_TOOL" ]; then
    D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$D8_TOOL" ] || [ ! -f "$D8_TOOL" ]; then
    echo "Error: d8 tool not found in Android SDK build-tools"
    exit 1
fi

# Clean and create directories
rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR
mkdir -p libs

# Compile Java sources
echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d $BUILD_DIR \
    src/com/ava/mods/a64/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

# Convert to DEX format (requires Java 11+)
echo "Converting to DEX..."
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null)
export PATH="$JAVA_HOME/bin:$PATH"
"$D8_TOOL" --output $BUILD_DIR $BUILD_DIR/com/ava/mods/a64/*.class

if [ $? -ne 0 ]; then
    echo "DEX conversion failed!"
    exit 1
fi

# Package JAR with DEX
echo "Creating JAR with DEX..."
cd $BUILD_DIR
jar cvf ../$OUTPUT_JAR classes.dex
cd ..

echo "Done! JAR created at $OUTPUT_JAR"
ls -la $OUTPUT_JAR
unzip -l $OUTPUT_JAR

# Calculate MD5 hash
JAR_HASH=$(md5 -q $OUTPUT_JAR)
echo ""
echo "JAR Hash: $JAR_HASH"

# Copy to mods directory
echo "Copying JAR to mods directory..."
mkdir -p "$MODS_DIR/libs"
cp $OUTPUT_JAR "$MODS_DIR/libs/"
echo "Copied to $MODS_DIR/libs/"

# Update manifests and store.json if version provided
if [ -n "$NEW_VERSION" ]; then
    echo ""
    echo "Updating version to $NEW_VERSION and hash to $JAR_HASH..."
    
    # Update source manifest.json
    sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$NEW_VERSION\"/" manifest.json
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" manifest.json
    echo "Updated: manifest.json"
    
    # Update mods manifest.json
    sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$NEW_VERSION\"/" "$MODS_DIR/manifest.json"
    sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MODS_DIR/manifest.json"
    echo "Updated: $MODS_DIR/manifest.json"
    
    # Update store.json (more complex - need to update specific mod entry)
    # Using perl for multi-line matching
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$NEW_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
    echo "Updated: $STORE_JSON"
    
    echo ""
    echo "=== Build Complete ==="
    echo "Version: $NEW_VERSION"
    echo "Hash: $JAR_HASH"
    echo "Ready to commit and push!"
else
    echo ""
    echo "=== Build Complete ==="
    echo "Hash: $JAR_HASH"
    echo "Note: Run with version argument to auto-update manifests"
    echo "  e.g. ./build.sh 1.0.3"
fi
