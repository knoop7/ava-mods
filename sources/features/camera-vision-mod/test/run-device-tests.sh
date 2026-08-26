#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MOD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$SCRIPT_DIR/build"
DEVICE_DIR="/data/local/tmp/cv-test"

ANDROID_JAR="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar"
TFLITE_JAR="$MOD_DIR/deps/tensorflow-lite.jar"
TFLITE_API_JAR="$MOD_DIR/deps/tensorflow-lite-api.jar"
ZXING_JAR="$MOD_DIR/deps/zxing-core.jar"

if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE '"(1[7-9]|[2-9][0-9])'; then
    for candidate in \
        /Library/Java/JavaVirtualMachines/temurin-24.jdk/Contents/Home \
        "$HOME/Library/Java/JavaVirtualMachines/jbr-17.0.9/Contents/Home" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"

echo "==> Compiling mod + test sources..."
find "$MOD_DIR/src" "$SCRIPT_DIR/src" -name "*.java" > "$OUT_DIR/sources.txt"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" \
    -source 11 -target 11 \
    -classpath "$ANDROID_JAR:$TFLITE_JAR:$TFLITE_API_JAR:$ZXING_JAR" \
    -d "$OUT_DIR/classes" \
    @"$OUT_DIR/sources.txt"

echo "==> DEXing..."
d8 --min-api 24 \
    --lib "$ANDROID_JAR" \
    --output "$OUT_DIR" \
    "$ZXING_JAR" \
    "$TFLITE_JAR" \
    "$TFLITE_API_JAR" \
    $(find "$OUT_DIR/classes" -name "*.class")

echo "==> Packaging test.jar..."
mkdir -p "$OUT_DIR/jar/models" "$OUT_DIR/jar/jni/arm64-v8a" "$OUT_DIR/jar/jni/armeabi-v7a"
cp "$OUT_DIR/classes.dex" "$OUT_DIR/jar/"
cp "$MOD_DIR"/models/*.tflite "$OUT_DIR/jar/models/"
cp "$MOD_DIR/deps/jni/arm64-v8a/libtensorflowlite_jni.so" "$OUT_DIR/jar/jni/arm64-v8a/"
cp "$MOD_DIR/deps/jni/armeabi-v7a/libtensorflowlite_jni.so" "$OUT_DIR/jar/jni/armeabi-v7a/"
(cd "$OUT_DIR/jar" && jar cf "$OUT_DIR/test.jar" classes.dex models jni)

echo "==> Pushing to device..."
adb shell mkdir -p "$DEVICE_DIR"
adb push "$OUT_DIR/test.jar" "$DEVICE_DIR/test.jar"
for asset in "$SCRIPT_DIR"/assets/*.jpg; do
    [ -f "$asset" ] && adb push "$asset" "$DEVICE_DIR/$(basename "$asset")"
done

echo "==> Running on device..."
adb shell "CLASSPATH=$DEVICE_DIR/test.jar app_process $DEVICE_DIR com.ava.mods.vision.test.VisionTestMain"
