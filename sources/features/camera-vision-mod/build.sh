#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="$SCRIPT_DIR/build"
RELEASE_DIR="$SCRIPT_DIR/../../../mods/features/camera-vision-mod"

ANDROID_JAR="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar"
TFLITE_JAR="${SCRIPT_DIR}/deps/tensorflow-lite.jar"
TFLITE_API_JAR="${SCRIPT_DIR}/deps/tensorflow-lite-api.jar"
ZXING_JAR="${SCRIPT_DIR}/deps/zxing-core.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found at $ANDROID_JAR"
    echo "Set ANDROID_HOME or install Android SDK platform 34"
    exit 1
fi

if [ ! -f "$ZXING_JAR" ]; then
    echo "ERROR: zxing-core.jar missing from deps/ — QR decode would crash at runtime"
    exit 1
fi

# d8 needs JDK 17+; the default `java` on this machine may still be 8.
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
echo "==> JAVA_HOME=${JAVA_HOME:-<system default>}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
CLASSPATH="$ANDROID_JAR:$TFLITE_JAR:$TFLITE_API_JAR:$ZXING_JAR"

echo "==> Compiling Java sources..."
find "$SRC_DIR" -name "*.java" > "$OUT_DIR/sources.txt"
"$JAVAC" \
    -source 11 -target 11 \
    -classpath "$CLASSPATH" \
    -d "$OUT_DIR/classes" \
    @"$OUT_DIR/sources.txt"

# ZXing and the full TFLite runtime are dexed into the mod. The host also carries
# TFLite, but R8 renames most of it, and the loader in ChildFirstLoader.java keeps
# the two copies apart at runtime.
echo "==> Converting to DEX (bundling ZXing + TFLite)..."
d8 --min-api 24 \
    --lib "$ANDROID_JAR" \
    --output "$OUT_DIR" \
    "$ZXING_JAR" \
    "$TFLITE_JAR" \
    "$TFLITE_API_JAR" \
    $(find "$OUT_DIR/classes" -name "*.class")

# Models and the TFLite JNI library ride inside the JAR: the host only fetches
# manifest "libs" entries, and ModCameraStreamBridge withholds camera ownership
# unless every entry is a .jar. ModelStore extracts them on first use.
echo "==> Packaging JAR with bundled models and JNI libs..."
mkdir -p "$OUT_DIR/jar/models" "$OUT_DIR/jar/jni/arm64-v8a" "$OUT_DIR/jar/jni/armeabi-v7a"
cp "$OUT_DIR/classes.dex" "$OUT_DIR/jar/"
cp "$SCRIPT_DIR"/models/*.tflite "$OUT_DIR/jar/models/"
cp "$SCRIPT_DIR/deps/jni/arm64-v8a/libtensorflowlite_jni.so" "$OUT_DIR/jar/jni/arm64-v8a/"
cp "$SCRIPT_DIR/deps/jni/armeabi-v7a/libtensorflowlite_jni.so" "$OUT_DIR/jar/jni/armeabi-v7a/"
(cd "$OUT_DIR/jar" && jar cf "$OUT_DIR/camera-vision.jar" classes.dex models jni)

echo "==> Assembling release package..."
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/libs"

cp "$OUT_DIR/camera-vision.jar" "$RELEASE_DIR/libs/"
cp "$SCRIPT_DIR/manifest.json" "$RELEASE_DIR/manifest.json"

JAR_HASH=$(md5sum "$RELEASE_DIR/libs/camera-vision.jar" 2>/dev/null | cut -d' ' -f1 || md5 -q "$RELEASE_DIR/libs/camera-vision.jar" 2>/dev/null || echo "")

# The JAR hash changes on every build, so sync store.json here instead of by hand.
STORE_JSON="$SCRIPT_DIR/../../../store.json"
python3 - "$STORE_JSON" "$SCRIPT_DIR/manifest.json" "$JAR_HASH" <<'PY'
import json
import re
import sys

store_path, manifest_path, jar_hash = sys.argv[1], sys.argv[2], sys.argv[3]
version = json.load(open(manifest_path))["version"]
text = open(store_path).read()

# Patch in place rather than re-serializing, so the diff stays to two lines.
entry = re.search(
    r'\{[^{}]*"id":\s*"camera-vision-mod"[^{}]*\}', text, re.DOTALL)
if not entry:
    sys.exit("camera-vision-mod entry not found in store.json")

patched = re.sub(r'("version":\s*")[^"]*(")',
                 lambda m: m.group(1) + version + m.group(2), entry.group(0), count=1)
patched, n = re.subn(r'("jar_hash":\s*")[^"]*(")',
                     lambda m: m.group(1) + jar_hash + m.group(2), patched, count=1)
if n != 1:
    sys.exit("jar_hash key missing from camera-vision-mod entry")

text = text[:entry.start()] + patched + text[entry.end():]
json.loads(text)
open(store_path, "w").write(text)
print(f"==> store.json synced: version={version} jar_hash={jar_hash}")
PY

echo "==> Done. JAR hash: $JAR_HASH"
echo "    Release: $RELEASE_DIR"
echo ""
echo "    Release contents:"
find "$RELEASE_DIR" -type f | sort | while read -r f; do
    sz=$(wc -c < "$f" | tr -d ' ')
    echo "      $(basename "$f") ($sz bytes)"
done
