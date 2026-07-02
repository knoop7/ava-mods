#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MOD_ID="ble-adv-proxy"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
BUILD_DIR="build"
OUTPUT_JAR="libs/ble-adv-proxy-manager.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    exit 1
fi

if [ -z "$D8_TOOL" ] || [ ! -f "$D8_TOOL" ]; then
    echo "Error: d8 tool not found"
    exit 1
fi

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
    echo "Error: no JDK found"
    exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using JDK: $JAVA_HOME"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" libs

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    src/com/ava/mods/bleadv/*.java

echo "Converting to DEX..."
mkdir -p "$BUILD_DIR/mod-dex"
"$D8_TOOL" --min-api 21 --lib "$ANDROID_JAR" --output "$BUILD_DIR/mod-dex" \
    $(find "$BUILD_DIR/classes" -name "*.class")

jar cf "$OUTPUT_JAR" -C "$BUILD_DIR/mod-dex" .

JAR_HASH=$(md5 -q "$OUTPUT_JAR")
MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

mkdir -p "$MODS_DIR/libs"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp manifest.json "$MODS_DIR/manifest.json"
[ -f README.md ] && cp README.md "$MODS_DIR/README.md"

for MANIFEST in manifest.json "$MODS_DIR/manifest.json"; do
    if grep -q '"jar_hash"' "$MANIFEST"; then
        sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MANIFEST"
    else
        python3 - <<PY
import json
path = "$MANIFEST"
with open(path) as f:
    data = json.load(f)
data["jar_hash"] = "$JAR_HASH"
with open(path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PY
    fi
done

if [ -f "$STORE_JSON" ] && grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON"; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$MOD_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
else
    echo "Note: $MOD_ID not in store.json yet"
fi

echo "Done."
echo "JAR Hash: $JAR_HASH"
ls -lh "$OUTPUT_JAR"
echo "Release package copied to $MODS_DIR"
