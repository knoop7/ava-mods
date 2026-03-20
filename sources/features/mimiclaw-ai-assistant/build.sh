#!/bin/bash

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/mimiclaw-manager.jar"
GRADLE_CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
OKHTTP_JAR=$(find "$GRADLE_CACHE" -path '*com.squareup.okhttp3/okhttp/4.12.0/*/*.jar' | head -1)
OKIO_JAR=$(find "$GRADLE_CACHE" -path '*com.squareup.okio/okio-jvm/3.9.1/*/*.jar' | head -1)
KOTLIN_STDLIB_JAR=$(find "$GRADLE_CACHE" -path '*org.jetbrains.kotlin/kotlin-stdlib/2.2.21/*/*.jar' | head -1)
KOTLIN_STDLIB_JDK7_JAR=$(find "$GRADLE_CACHE" -path '*org.jetbrains.kotlin/kotlin-stdlib-jdk7/*/*.jar' | grep -v sources | head -1)
KOTLIN_STDLIB_JDK8_JAR=$(find "$GRADLE_CACHE" -path '*org.jetbrains.kotlin/kotlin-stdlib-jdk8/*/*.jar' | grep -v sources | head -1)
TERMUX_JAR="libs/termux/classes.jar"
THIRD_PARTY_CP="$OKHTTP_JAR:$OKIO_JAR:$KOTLIN_STDLIB_JAR:$KOTLIN_STDLIB_JDK7_JAR:$KOTLIN_STDLIB_JDK8_JAR:$TERMUX_JAR"

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

rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR
mkdir -p libs

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR:$THIRD_PARTY_CP" \
    -d $BUILD_DIR \
    src/com/ava/mods/mimiclaw/*.java \
    src/com/ava/mods/mimiclaw/agent/*.java \
    src/com/ava/mods/mimiclaw/bus/*.java \
    src/com/ava/mods/mimiclaw/channel/*.java \
    src/com/ava/mods/mimiclaw/context/*.java \
    src/com/ava/mods/mimiclaw/cron/*.java \
    src/com/ava/mods/mimiclaw/llm/*.java \
    src/com/ava/mods/mimiclaw/memory/*.java \
    src/com/ava/mods/mimiclaw/skills/*.java \
    src/com/ava/mods/mimiclaw/terminal/*.java \
    src/com/ava/mods/mimiclaw/tools/*.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Converting to DEX..."
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null)
export PATH="$JAVA_HOME/bin:$PATH"
"$D8_TOOL" --lib "$ANDROID_JAR" --output $BUILD_DIR $BUILD_DIR/com/ava/mods/mimiclaw/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/agent/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/bus/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/channel/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/context/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/cron/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/llm/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/memory/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/skills/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/terminal/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/tools/*.class \
    "$TERMUX_JAR" \
    "$OKHTTP_JAR" \
    "$OKIO_JAR" \
    "$KOTLIN_STDLIB_JAR" \
    "$KOTLIN_STDLIB_JDK7_JAR" \
    "$KOTLIN_STDLIB_JDK8_JAR"

if [ $? -ne 0 ]; then
    echo "DEX conversion failed!"
    exit 1
fi

echo "Creating JAR with DEX..."
cd $BUILD_DIR
jar cvf ../$OUTPUT_JAR classes.dex
cd ..

echo "Done! JAR created at $OUTPUT_JAR"
ls -la $OUTPUT_JAR
unzip -l $OUTPUT_JAR
