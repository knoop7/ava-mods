#!/bin/bash
# Ava mod build: Java (javac+d8) → DEX jar + NDK libairplay_native.so
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MOD_ID="airplay-receiver"
MODS_DIR="$REPO_ROOT/mods/features/$MOD_ID"
STORE_JSON="$REPO_ROOT/store.json"

cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  ANDROID_JAR="$ANDROID_SDK/platforms/android-35/android.jar"
fi
D8_TOOL=$(find "$ANDROID_SDK/build-tools" -name "d8" 2>/dev/null | sort -V | tail -1)
NDK_DIR="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
  NDK_DIR=$(ls -d "$ANDROID_SDK/ndk"/* 2>/dev/null | sort -V | tail -1 || true)
fi

BUILD_DIR="build"
DEPS_DIR="build-deps"
OUTPUT_JAR="libs/airplay-receiver-manager.jar"
NATIVE_DIR="$SCRIPT_DIR/native"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "Error: android.jar not found under $ANDROID_SDK/platforms"
  exit 1
fi
if [ -z "$D8_TOOL" ] || [ ! -f "$D8_TOOL" ]; then
  echo "Error: d8 tool not found"
  exit 1
fi
if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
  echo "Error: Android NDK not found"
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
echo "Using NDK: $NDK_DIR"

mkdir -p "$DEPS_DIR" libs/jni "$BUILD_DIR/classes" "$BUILD_DIR/mod-dex"

# --- Compile-time AndroidX / Media3 ---
# Media3 stays on Ava host ClassLoader at runtime.
# androidx.media (MediaSessionCompat) is bundled into this mod's DEX so AirPlay
# does not depend on the host retaining those classes under R8.
# Force a fresh androidx.media jar when rebuilding (avoids stale/wrong cache).
if [ ! -f "$DEPS_DIR/media3-exoplayer.jar" ] || [ ! -f "$DEPS_DIR/media.jar" ]; then
  chmod +x ./fetch-deps.sh
  ./fetch-deps.sh
fi
# Ensure media.jar matches androidx imports used by AirPlayEngine.
if ! jar tf "$DEPS_DIR/media.jar" 2>/dev/null | grep -qE 'androidx/media/session/MediaSessionCompat.class|android/support/v4/media/session/MediaSessionCompat.class'; then
  echo "Refreshing media.jar (needs MediaSessionCompat)..."
  chmod +x ./fetch-deps.sh
  ./fetch-deps.sh
fi

DEP_JARS=(
  "$DEPS_DIR/annotation.jar"
  "$DEPS_DIR/collection.jar"
  "$DEPS_DIR/versionedparcelable.jar"
  "$DEPS_DIR/core.jar"
  "$DEPS_DIR/media.jar"
  "$DEPS_DIR/media3-common.jar"
  "$DEPS_DIR/media3-database.jar"
  "$DEPS_DIR/media3-datasource.jar"
  "$DEPS_DIR/media3-decoder.jar"
  "$DEPS_DIR/media3-extractor.jar"
  "$DEPS_DIR/media3-exoplayer.jar"
  "$DEPS_DIR/media3-exoplayer-hls.jar"
)
for jar in "${DEP_JARS[@]}"; do
  if [ ! -f "$jar" ]; then
    echo "Error: missing $jar (run ./fetch-deps.sh)"
    exit 1
  fi
done
DEP_CP=$(IFS=:; echo "${DEP_JARS[*]}")

# Host-provided at runtime (not packaged into mod DEX).
HOST_RUNTIME_JARS=(
  "$DEPS_DIR/annotation.jar"
  "$DEPS_DIR/collection.jar"
  "$DEPS_DIR/versionedparcelable.jar"
  "$DEPS_DIR/core.jar"
  "$DEPS_DIR/media3-common.jar"
  "$DEPS_DIR/media3-database.jar"
  "$DEPS_DIR/media3-datasource.jar"
  "$DEPS_DIR/media3-decoder.jar"
  "$DEPS_DIR/media3-extractor.jar"
  "$DEPS_DIR/media3-exoplayer.jar"
  "$DEPS_DIR/media3-exoplayer-hls.jar"
)

echo "Compiling Java sources..."
rm -rf "$BUILD_DIR/classes"
mkdir -p "$BUILD_DIR/classes"
find src -name '*.java' > "$BUILD_DIR/sources.list"
javac -source 1.8 -target 1.8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$ANDROID_JAR:$DEP_CP" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.list"

echo "Dexing mod classes + bundled androidx.media (Media3 still from Ava host)..."
rm -rf "$BUILD_DIR/mod-dex"
mkdir -p "$BUILD_DIR/mod-dex"
D8_ARGS=(--min-api 24 --lib "$ANDROID_JAR" --output "$BUILD_DIR/mod-dex")
for hj in "${HOST_RUNTIME_JARS[@]}"; do
  D8_ARGS+=(--classpath "$hj")
done
MOD_CLASSES=()
while IFS= read -r -d '' f; do MOD_CLASSES+=("$f"); done < <(find "$BUILD_DIR/classes" -name '*.class' -print0)
"$D8_TOOL" "${D8_ARGS[@]}" "${MOD_CLASSES[@]}" "$DEPS_DIR/media.jar"

# Mod-owned assets (AirPlay badge PNG) — served by DexClassLoader from the jar
# (same packing as DLNA CinemaOverlay assets/dlna-icon.png).
if [ -d assets ]; then
  mkdir -p "$BUILD_DIR/mod-dex/assets"
  cp -R assets/. "$BUILD_DIR/mod-dex/assets/"
fi

mkdir -p libs
jar cf "$OUTPUT_JAR" -C "$BUILD_DIR/mod-dex" .

# --- Native .so via CMake / NDK ---
build_abi() {
  local abi="$1"
  local api=24
  local out="$BUILD_DIR/native-$abi"
  mkdir -p "$out"
  echo "Building native ($abi)..."

  # Apply UxPlay patches once per tree
  if [ -d "$NATIVE_DIR/third_party/UxPlay/.git" ] && [ -d "$NATIVE_DIR/patches/UxPlay" ]; then
    (
      cd "$NATIVE_DIR/third_party/UxPlay"
      patches=("$NATIVE_DIR"/patches/UxPlay/*.patch)
      touched=()
      for p in "${patches[@]}"; do
        [ -f "$p" ] || continue
        while read -r _ _ f; do
          [ -n "$f" ] && touched+=("$f")
        done < <(git apply --numstat "$p" 2>/dev/null || true)
      done
      if [ ${#touched[@]} -gt 0 ]; then
        git checkout -- "${touched[@]}" 2>/dev/null || true
      fi
      for p in "${patches[@]}"; do
        [ -f "$p" ] || continue
        git apply "$p" || true
      done
    )
  fi

  cmake -S "$NATIVE_DIR" -B "$out" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$api" \
    -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$out" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

  mkdir -p "libs/jni/$abi"
  cp "$out/libairplay_native.so" "libs/jni/$abi/"
  # NDK libc++_shared (path differs by host triple / ABI)
  case "$abi" in
    arm64-v8a) CXX_GLOB='*aarch64-linux-android*' ;;
    armeabi-v7a) CXX_GLOB='*arm-linux-androideabi*' ;;
    *) CXX_GLOB='' ;;
  esac
  CXX_SHARED=""
  if [ -n "$CXX_GLOB" ]; then
    CXX_SHARED=$(find "$NDK_DIR/toolchains/llvm/prebuilt" -path "$CXX_GLOB/libc++_shared.so" 2>/dev/null | head -1)
  fi
  if [ -n "$CXX_SHARED" ] && [ -f "$CXX_SHARED" ]; then
    cp "$CXX_SHARED" "libs/jni/$abi/"
  else
    echo "Warning: libc++_shared.so not found for $abi"
  fi
}

# Ava devices: arm64 + armeabi only (no x86_64)
for abi in arm64-v8a armeabi-v7a; do
  build_abi "$abi"
done

JAR_HASH=$(md5 -q "$OUTPUT_JAR" 2>/dev/null || md5sum "$OUTPUT_JAR" | awk '{print $1}')
MOD_VERSION=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")

# Publish release package
mkdir -p "$MODS_DIR/libs/jni/arm64-v8a" "$MODS_DIR/libs/jni/armeabi-v7a"
cp "$OUTPUT_JAR" "$MODS_DIR/libs/"
cp -R libs/jni/. "$MODS_DIR/libs/jni/"
cp manifest.json "$MODS_DIR/manifest.json"
[ -f README.md ] && cp README.md "$MODS_DIR/README.md"
[ -f LICENSE ] && cp LICENSE "$MODS_DIR/LICENSE"

for MANIFEST in manifest.json "$MODS_DIR/manifest.json"; do
  if grep -q '"jar_hash"' "$MANIFEST"; then
    if [[ "$(uname)" == Darwin ]]; then
      sed -i '' "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MANIFEST"
    else
      sed -i "s/\"jar_hash\": \"[^\"]*\"/\"jar_hash\": \"$JAR_HASH\"/" "$MANIFEST"
    fi
  fi
done

python3 << PY
import json
from pathlib import Path
store_path = Path(r"$STORE_JSON")
store = json.loads(store_path.read_text())
entry = {
    "id": "$MOD_ID",
    "name": "AirPlay Receiver",
    "version": "$MOD_VERSION",
    "author": "Ava",
    "description": "Cast from iPhone or Mac. Turn this device into your AirPlay screen and speaker.",
    "detail_description": "Mirror the display, stream albums with artwork, or play video fullscreen. Shows up as {device name} [Airplay] on the same Wi-Fi. Ties to the voice satellite; PIN and ducking when you want them.",
    "path": "mods/features/$MOD_ID/",
    "jar_hash": "$JAR_HASH",
}
mods = store.setdefault("mods", [])
found = False
for i, m in enumerate(mods):
    if m.get("id") == "$MOD_ID":
        mods[i] = {**m, **entry}
        found = True
        break
if not found:
    mods.insert(0, entry)
# Keep store order: AirPlay, DLNA, then HA Edge TTS / STT, then the rest.
pinned = ("airplay-receiver", "dlna-renderer", "ha-edge-tts", "ha-stt-engine")
by_id = {m.get("id"): m for m in mods}
rest = [m for m in mods if m.get("id") not in pinned]
ordered = [by_id[i] for i in pinned if i in by_id] + rest
store["mods"] = ordered
store_path.write_text(json.dumps(store, indent=2, ensure_ascii=False) + "\n")
print("store.json updated")
PY

echo "Done."
echo "JAR: $OUTPUT_JAR"
echo "JAR Hash: $JAR_HASH"
ls -lh "$OUTPUT_JAR"
find libs/jni -name '*.so' -exec ls -lh {} \;
echo "Release: $MODS_DIR"
