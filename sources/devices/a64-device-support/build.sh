#!/bin/bash

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
BUILD_DIR="build"
OUTPUT_JAR="libs/a64-device-support.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    echo "Please set ANDROID_HOME or install Android SDK platform 34"
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p libs

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR" \
    src/com/ava/mods/a64/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Creating JAR..."
cd "$BUILD_DIR" || exit 1
jar cvf ../$OUTPUT_JAR com/
cd .. || exit 1

echo "Done! JAR created at $OUTPUT_JAR"
ls -la "$OUTPUT_JAR"
