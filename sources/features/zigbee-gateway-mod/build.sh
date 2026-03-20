#!/bin/bash

# Ava Mod Build Script - Zigbee Gateway
# Compiles Java source to DEX-based JAR for Android DexClassLoader
# Requirements: Android SDK, Java 11+

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/zigbee-gateway-manager.jar"

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
    src/com/ava/mods/zigbee/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

# Convert to DEX format (requires Java 11+)
echo "Converting to DEX..."
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null)
export PATH="$JAVA_HOME/bin:$PATH"
"$D8_TOOL" --output $BUILD_DIR $BUILD_DIR/com/ava/mods/zigbee/*.class

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
