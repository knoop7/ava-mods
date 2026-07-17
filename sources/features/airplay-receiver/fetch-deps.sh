#!/bin/bash
# Fetch compile-only AndroidX / Media3 AARs (Google Maven) and extract classes.jar
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPS_DIR="$SCRIPT_DIR/build-deps"
mkdir -p "$DEPS_DIR"
GOOGLE="https://dl.google.com/dl/android/maven2"

copy_from_gradle() {
  local pattern="$1" dest="$2"
  local hit
  hit=$(find "$HOME/.gradle/caches" -name "$pattern" 2>/dev/null | head -1 || true)
  if [ -n "$hit" ] && [ -f "$hit" ]; then
    if [[ "$hit" == *.aar ]]; then
      local tmp="$DEPS_DIR/_t_$$"
      mkdir -p "$tmp"
      unzip -q -o "$hit" -d "$tmp"
      cp "$tmp/classes.jar" "$dest"
      rm -rf "$tmp"
    else
      cp "$hit" "$dest"
    fi
    echo "OK $(basename "$dest") <- gradle cache"
    return 0
  fi
  return 1
}

fetch_aar_classes() {
  local maven_path="$1" name="$2"
  local jar="$DEPS_DIR/${name}.jar"
  if [ -f "$jar" ]; then
    echo "OK $name.jar (cached)"
    return 0
  fi
  if copy_from_gradle "${name}-*.aar" "$jar"; then return 0; fi
  if copy_from_gradle "${name}-*-runtime.jar" "$jar"; then return 0; fi
  if copy_from_gradle "${name}-*-api.jar" "$jar"; then return 0; fi

  local aar="$DEPS_DIR/${name}.aar"
  echo "Fetching $name ..."
  curl -fsSL -o "$aar" "$GOOGLE/$maven_path"
  local tmp="$DEPS_DIR/_extract_$name"
  rm -rf "$tmp"; mkdir -p "$tmp"
  unzip -q -o "$aar" -d "$tmp"
  cp "$tmp/classes.jar" "$jar"
  rm -rf "$tmp" "$aar"
  echo "OK $name.jar"
}

fetch_jar() {
  local url="$1" name="$2"
  local jar="$DEPS_DIR/${name}.jar"
  if [ -f "$jar" ]; then
    echo "OK $name.jar (cached)"
    return 0
  fi
  if copy_from_gradle "${name}*.jar" "$jar"; then return 0; fi
  echo "Fetching $name.jar ..."
  curl -fsSL -o "$jar" "$url"
  echo "OK $name.jar"
}

# Plain jars
fetch_jar "$GOOGLE/androidx/annotation/annotation-jvm/1.8.2/annotation-jvm-1.8.2.jar" annotation \
  || fetch_jar "https://repo1.maven.org/maven2/androidx/annotation/annotation-jvm/1.8.2/annotation-jvm-1.8.2.jar" annotation

fetch_jar "https://repo1.maven.org/maven2/androidx/collection/collection-jvm/1.4.0/collection-jvm-1.4.0.jar" collection \
  || fetch_aar_classes "androidx/collection/collection/1.4.0/collection-1.4.0.aar" collection

# AARs
fetch_aar_classes "androidx/versionedparcelable/versionedparcelable/1.2.0/versionedparcelable-1.2.0.aar" versionedparcelable
fetch_aar_classes "androidx/core/core/1.13.1/core-1.13.1.aar" core
# Exact Maven AAR only (avoid gradle cache glob matching the wrong "media-*.aar").
rm -f "$DEPS_DIR/media.jar" "$DEPS_DIR/media.aar"
{
  aar="$DEPS_DIR/media.aar"
  jar="$DEPS_DIR/media.jar"
  echo "Fetching media (androidx.media:media:1.7.0) ..."
  curl -fsSL -o "$aar" "$GOOGLE/androidx/media/media/1.7.0/media-1.7.0.aar"
  tmp="$DEPS_DIR/_extract_media"
  rm -rf "$tmp"; mkdir -p "$tmp"
  unzip -q -o "$aar" -d "$tmp"
  cp "$tmp/classes.jar" "$jar"
  rm -rf "$tmp" "$aar"
  echo "OK media.jar"
}
if jar tf "$DEPS_DIR/media.jar" | grep -q 'androidx/media/session/MediaSessionCompat.class'; then
  echo "OK media.jar has androidx.media.session.MediaSessionCompat"
elif jar tf "$DEPS_DIR/media.jar" | grep -q 'android/support/v4/media/session/MediaSessionCompat.class'; then
  echo "OK media.jar has MediaSessionCompat (android.support.v4.media.session; androidx.media AAR bytecode)"
else
  echo "ERROR: media.jar missing MediaSessionCompat" >&2
  exit 1
fi
fetch_aar_classes "androidx/media3/media3-common/1.8.0/media3-common-1.8.0.aar" media3-common
fetch_aar_classes "androidx/media3/media3-database/1.8.0/media3-database-1.8.0.aar" media3-database
fetch_aar_classes "androidx/media3/media3-datasource/1.8.0/media3-datasource-1.8.0.aar" media3-datasource
fetch_aar_classes "androidx/media3/media3-decoder/1.8.0/media3-decoder-1.8.0.aar" media3-decoder
fetch_aar_classes "androidx/media3/media3-extractor/1.8.0/media3-extractor-1.8.0.aar" media3-extractor
fetch_aar_classes "androidx/media3/media3-exoplayer/1.8.0/media3-exoplayer-1.8.0.aar" media3-exoplayer
fetch_aar_classes "androidx/media3/media3-exoplayer-hls/1.8.0/media3-exoplayer-hls-1.8.0.aar" media3-exoplayer-hls

echo "----"
ls -lh "$DEPS_DIR"/*.jar
