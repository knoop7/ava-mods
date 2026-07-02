#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MOD_ID="dlna-renderer"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
# Use the newest d8: older releases (e.g. 34.0.0 / R8 8.2.x) crash with an NPE
# while dexing the jetty/jupnp jars.
D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
BUILD_DIR="build"
DEPS_DIR="build-deps"
OUTPUT_JAR="libs/dlna-renderer-manager.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Error: android.jar not found at $ANDROID_JAR"
    exit 1
fi

if [ ! -f "$D8_TOOL" ]; then
    D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$D8_TOOL" ] || [ ! -f "$D8_TOOL" ]; then
    echo "Error: d8 tool not found"
    exit 1
fi

# Resolve a JDK: prefer java_home, fall back to known install locations
# (java_home is unreliable in some sandboxed shells).
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

# Runtime dependency jars (jUPnP UPnP stack + Jetty transport + slf4j), all
# fetched from Maven Central. Cling 2.x is dead upstream; jUPnP is its
# maintained successor and is API-compatible.
DEP_JARS=(
    "$DEPS_DIR/org.jupnp-3.0.4.jar"
    "$DEPS_DIR/org.jupnp.support-3.0.4.jar"
    "$DEPS_DIR/org.jupnp.android-3.0.4.jar"
    "$DEPS_DIR/jetty-server-9.4.53.v20231009.jar"
    "$DEPS_DIR/jetty-servlet-9.4.53.v20231009.jar"
    "$DEPS_DIR/jetty-client-9.4.53.v20231009.jar"
    "$DEPS_DIR/jetty-http-9.4.53.v20231009.jar"
    "$DEPS_DIR/jetty-io-9.4.53.v20231009.jar"
    "$DEPS_DIR/jetty-util-9.4.53.v20231009.jar"
    "$DEPS_DIR/jetty-security-9.4.53.v20231009.jar"
    "$DEPS_DIR/javax.servlet-api-3.1.0.jar"
    "$DEPS_DIR/slf4j-api-2.0.11.jar"
    "$DEPS_DIR/slf4j-simple-2.0.11.jar"
)

for jar in "${DEP_JARS[@]}"; do
    if [ ! -f "$jar" ]; then
        echo "Error: missing dependency $jar (run fetch-deps.sh)"
        exit 1
    fi
done

DEP_CP=$(IFS=:; echo "${DEP_JARS[*]}")

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/deps-clean" libs

# Strip module-info.class and META-INF/versions (Java 9+ multi-release entries)
# which crash older d8 releases.
CLEAN_JARS=()
for jar in "${DEP_JARS[@]}"; do
    clean="$BUILD_DIR/deps-clean/$(basename "$jar")"
    cp "$jar" "$clean"
    zip -q -d "$clean" "module-info.class" "META-INF/versions/*" 2>/dev/null || true
    CLEAN_JARS+=("$clean")
done

echo "Compiling Java sources..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR:$DEP_CP" \
    -d "$BUILD_DIR/classes" \
    src/com/ava/mods/dlna/*.java

echo "Converting mod + dependency classes to DEX (single jar)..."
mkdir -p "$BUILD_DIR/mod-dex"
# --min-api 21 matches Ava's minSdk; d8 desugars Java 8/11 bytecode in jupnp/jetty.
"$D8_TOOL" --min-api 21 --lib "$ANDROID_JAR" --output "$BUILD_DIR/mod-dex" \
    $(find "$BUILD_DIR/classes" -name "*.class") \
    "${CLEAN_JARS[@]}"

# d8 keeps only .class files, but the runtime needs the jars' resources too:
# javax.servlet LocalStrings*.properties (GenericServlet <clinit> crashes without
# them), jetty mime/encoding tables, and slf4j's ServiceLoader entries.
# DexClassLoader serves these from the mod jar.
for jar in "${DEP_JARS[@]}"; do
    unzip -o -q "$jar" -d "$BUILD_DIR/mod-dex" \
        -x "*.class" "META-INF/MANIFEST.MF" "META-INF/*.SF" "META-INF/*.RSA" \
           "META-INF/*.DSA" "META-INF/maven/*" "META-INF/LICENSE*" \
           "META-INF/NOTICE*" "about.html" "*/" 2>/dev/null || true
done
find "$BUILD_DIR/mod-dex" -name "*.class" -delete

# Mod-owned assets (e.g. DLNA badge PNG) — served by DexClassLoader from the jar.
if [ -d assets ]; then
    mkdir -p "$BUILD_DIR/mod-dex/assets"
    cp -R assets/. "$BUILD_DIR/mod-dex/assets/"
fi

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
    fi
done

if grep -q "\"id\": \"$MOD_ID\"" "$STORE_JSON"; then
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"version\": \")[^\"]*(\")/\${1}$MOD_VERSION\${2}/s" "$STORE_JSON"
    perl -i -0pe "s/(\"id\": \"$MOD_ID\".*?\"jar_hash\": \")[^\"]*(\")/\${1}$JAR_HASH\${2}/s" "$STORE_JSON"
else
    echo "Warning: $MOD_ID not found in store.json, skipping store update"
fi

echo "Done."
echo "JAR Hash: $JAR_HASH"
ls -lh "$OUTPUT_JAR"
echo "Release package copied to $MODS_DIR"
