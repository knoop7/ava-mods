#!/bin/bash
set -e

VERSION="${1:-1.0.0}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

echo "=== Deploying GeckoView Browser v$VERSION ==="

# Update version in manifests
echo "Updating version..."
python3 -c "
import json
for path in ['$SCRIPT_DIR/manifest.json', '$ROOT_DIR/mods/features/geckoview-browser/manifest.json']:
    try:
        with open(path, 'r') as f:
            data = json.load(f)
        data['version'] = '$VERSION'
        with open(path, 'w') as f:
            json.dump(data, f, indent=2)
            f.write('\n')
    except: pass
# Update store.json
with open('$ROOT_DIR/store.json', 'r') as f:
    data = json.load(f)
for mod in data.get('mods', []):
    if mod.get('id') == 'geckoview-browser':
        mod['version'] = '$VERSION'
with open('$ROOT_DIR/store.json', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
"

# Build
echo "Building..."
cd "$SCRIPT_DIR"
./build.sh
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# Copy JAR + manifest to release dir
echo "Copying artifacts..."
mkdir -p "$ROOT_DIR/mods/features/geckoview-browser/libs"
cp "$SCRIPT_DIR/libs/geckoview-browser.jar" "$ROOT_DIR/mods/features/geckoview-browser/libs/"
cp "$SCRIPT_DIR/manifest.json" "$ROOT_DIR/mods/features/geckoview-browser/manifest.json"

# Git commit and push
echo "Committing and pushing..."
cd "$ROOT_DIR"
git add -A
git commit -m "v$VERSION: GeckoView Browser mod"
git push origin main

echo "=== Done! v$VERSION deployed ==="
