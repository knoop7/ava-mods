#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="$SCRIPT_DIR/build"
RELEASE_DIR="$SCRIPT_DIR/../../mods/features/camera-vision-mod"

ANDROID_JAR="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar"
TFLITE_JAR="${SCRIPT_DIR}/deps/tensorflow-lite.jar"
ZXING_JAR="${SCRIPT_DIR}/deps/zxing-core.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found at $ANDROID_JAR"
    echo "Set ANDROID_HOME or install Android SDK platform 34"
    exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"

CLASSPATH="$ANDROID_JAR"
if [ -f "$TFLITE_JAR" ]; then
    CLASSPATH="$CLASSPATH:$TFLITE_JAR"
fi
if [ -f "$ZXING_JAR" ]; then
    CLASSPATH="$CLASSPATH:$ZXING_JAR"
fi

echo "==> Compiling Java sources..."
find "$SRC_DIR" -name "*.java" > "$OUT_DIR/sources.txt"
javac \
    -source 11 -target 11 \
    -classpath "$CLASSPATH" \
    -d "$OUT_DIR/classes" \
    @"$OUT_DIR/sources.txt"

echo "==> Converting to DEX..."
d8 --min-api 24 \
    --output "$OUT_DIR" \
    $(find "$OUT_DIR/classes" -name "*.class")

echo "==> Packaging JAR..."
mkdir -p "$OUT_DIR/jar"
cp "$OUT_DIR/classes.dex" "$OUT_DIR/jar/"
(cd "$OUT_DIR/jar" && jar cf "$OUT_DIR/camera-vision.jar" classes.dex)

echo "==> Assembling release package..."
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/libs"
mkdir -p "$RELEASE_DIR/models"

cp "$OUT_DIR/camera-vision.jar" "$RELEASE_DIR/libs/"
cp "$SCRIPT_DIR/manifest.json" "$RELEASE_DIR/manifest.json"

echo "==> Bundling models..."
cp "$SCRIPT_DIR/models/face_detection_full_range_sparse.tflite" "$RELEASE_DIR/models/"
cp "$SCRIPT_DIR/models/face_detection_short_range.tflite" "$RELEASE_DIR/models/"
cp "$SCRIPT_DIR/models/palm_detection_lite.tflite" "$RELEASE_DIR/models/"
cp "$SCRIPT_DIR/models/hand_landmark_lite.tflite" "$RELEASE_DIR/models/"

JAR_HASH=$(md5sum "$RELEASE_DIR/libs/camera-vision.jar" 2>/dev/null | cut -d' ' -f1 || md5 -q "$RELEASE_DIR/libs/camera-vision.jar" 2>/dev/null || echo "")
echo "==> Done. JAR hash: $JAR_HASH"
echo "    Release: $RELEASE_DIR"
echo ""
echo "    Release contents:"
find "$RELEASE_DIR" -type f | sort | while read -r f; do
    sz=$(wc -c < "$f" | tr -d ' ')
    echo "      $(basename "$f") ($sz bytes)"
done
