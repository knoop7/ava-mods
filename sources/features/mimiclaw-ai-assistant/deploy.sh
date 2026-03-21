#!/bin/bash
set -e

if [ -z "$1" ]; then
    echo "Usage: ./deploy.sh <version>"
    echo "Example: ./deploy.sh 1.1.28"
    exit 1
fi

VERSION="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

echo "=== Deploying OpenClaw(Mini) v$VERSION ==="

# Always build first
echo "Building..."
cd "$SCRIPT_DIR"
./build.sh
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# Update version in 4 places
echo "Updating version numbers..."
sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" "$ROOT_DIR/sources/features/mimiclaw-ai-assistant/manifest.json"
sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/manifest.json"
sed -i '' "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" "$ROOT_DIR/sources/mods/features/mimiclaw-ai-assistant/manifest.json"

# Update store.json (only mimiclaw-ai-assistant entry) - version will be updated, jar_hash later
python3 -c "
import json
with open('$ROOT_DIR/store.json', 'r') as f:
    data = json.load(f)
for mod in data.get('mods', []):
    if mod.get('id') == 'mimiclaw-ai-assistant':
        mod['version'] = '$VERSION'
with open('$ROOT_DIR/store.json', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"

echo "Version updated to $VERSION"

# Copy JAR
echo "Copying JAR..."
cp "$ROOT_DIR/sources/features/mimiclaw-ai-assistant/libs/mimiclaw-manager.jar" "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/"

# Calculate JAR hash and update manifest + store.json
JAR_HASH=$(md5 -q "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/mimiclaw-manager.jar")
echo "JAR hash: $JAR_HASH"
python3 -c "
import json
# Update manifest files
for path in ['$ROOT_DIR/mods/features/mimiclaw-ai-assistant/manifest.json', '$ROOT_DIR/sources/features/mimiclaw-ai-assistant/manifest.json', '$ROOT_DIR/sources/mods/features/mimiclaw-ai-assistant/manifest.json']:
    try:
        with open(path, 'r') as f:
            data = json.load(f)
        data['jar_hash'] = '$JAR_HASH'
        with open(path, 'w') as f:
            json.dump(data, f, indent=2)
            f.write('\n')
    except: pass
# Update store.json
try:
    with open('$ROOT_DIR/store.json', 'r') as f:
        data = json.load(f)
    for mod in data.get('mods', []):
        if mod.get('id') == 'mimiclaw-ai-assistant':
            mod['jar_hash'] = '$JAR_HASH'
    with open('$ROOT_DIR/store.json', 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')
except: pass
"

# Copy native libraries
echo "Copying native libraries..."
mkdir -p "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/arm64-v8a"
mkdir -p "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/armeabi-v7a"
mkdir -p "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/x86_64"
mkdir -p "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/x86"
cp "$ROOT_DIR/sources/features/mimiclaw-ai-assistant/libs/termux/jni/arm64-v8a/libtermux.so" "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/arm64-v8a/"
cp "$ROOT_DIR/sources/features/mimiclaw-ai-assistant/libs/termux/jni/armeabi-v7a/libtermux.so" "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/armeabi-v7a/"
cp "$ROOT_DIR/sources/features/mimiclaw-ai-assistant/libs/termux/jni/x86_64/libtermux.so" "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/x86_64/"
cp "$ROOT_DIR/sources/features/mimiclaw-ai-assistant/libs/termux/jni/x86/libtermux.so" "$ROOT_DIR/mods/features/mimiclaw-ai-assistant/libs/termux/jni/x86/"

# Git commit and push
echo "Committing and pushing..."
cd "$ROOT_DIR"
git add -A
git commit -m "v$VERSION: OpenClaw(Mini) update"
git push origin main

echo "=== Done! v$VERSION deployed ==="
