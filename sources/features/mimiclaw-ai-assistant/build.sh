#!/bin/bash

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL="$ANDROID_SDK/build-tools/34.0.0/d8"
BUILD_DIR="build"
OUTPUT_JAR="libs/mimiclaw-manager.jar"
MANIFEST_JSON="manifest.json"
BUILD_INFO_JAVA="src/com/ava/mods/mimiclaw/BuildInfo.java"
GRADLE_CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
find_cached_jar() {
    find "$GRADLE_CACHE" -path "$1" \
        | grep -v -- '-sources\.jar$' \
        | grep -v -- '-javadoc\.jar$' \
        | sort \
        | head -1
}

OKHTTP_JAR=$(find_cached_jar '*com.squareup.okhttp3/okhttp/4.12.0/*/*.jar')
OKIO_JAR=$(find_cached_jar '*com.squareup.okio/okio-jvm/3.9.1/*/*.jar')
KOTLIN_STDLIB_JAR=$(find_cached_jar '*org.jetbrains.kotlin/kotlin-stdlib/2.2.21/*/*.jar')
KOTLIN_STDLIB_JDK7_JAR=$(find_cached_jar '*org.jetbrains.kotlin/kotlin-stdlib-jdk7/*/*.jar')
KOTLIN_STDLIB_JDK8_JAR=$(find_cached_jar '*org.jetbrains.kotlin/kotlin-stdlib-jdk8/*/*.jar')
TERMUX_JAR="libs/termux/classes.jar"
THIRD_PARTY_CP="$OKHTTP_JAR:$OKIO_JAR:$KOTLIN_STDLIB_JAR:$KOTLIN_STDLIB_JDK7_JAR:$KOTLIN_STDLIB_JDK8_JAR:$TERMUX_JAR"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    echo "Please set ANDROID_HOME or install Android SDK platform 34"
    exit 1
fi

for dep in "$OKHTTP_JAR" "$OKIO_JAR" "$KOTLIN_STDLIB_JAR" "$TERMUX_JAR"; do
    if [ -z "$dep" ] || [ ! -f "$dep" ]; then
        echo "Error: missing compile dependency jar: $dep"
        exit 1
    fi
done

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

if [ -n "$1" ]; then
    VERSION="$1"
    sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" "$MANIFEST_JSON"
else
    VERSION=$(sed -n 's/.*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$MANIFEST_JSON" | head -1)
fi

if [ -z "$VERSION" ]; then
    echo "Error: failed to read version from $MANIFEST_JSON"
    exit 1
fi

mkdir -p "$(dirname "$BUILD_INFO_JAVA")"
cat > "$BUILD_INFO_JAVA" <<EOF
package com.ava.mods.mimiclaw;

public final class BuildInfo {
    private BuildInfo() {}

    public static final String VERSION = "v$VERSION";
}
EOF

echo "Synced BuildInfo.VERSION -> v$VERSION"

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
    src/com/ava/mods/mimiclaw/speech/*.java \
    src/com/ava/mods/mimiclaw/task/*.java \
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
    $BUILD_DIR/com/ava/mods/mimiclaw/speech/*.class \
    $BUILD_DIR/com/ava/mods/mimiclaw/task/*.class \
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
