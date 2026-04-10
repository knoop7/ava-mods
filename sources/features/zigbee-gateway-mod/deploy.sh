#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MANIFEST_PATH="$SCRIPT_DIR/manifest.json"
RELEASE_DIR="$ROOT_DIR/mods/features/zigbee-gateway-mod"
RELEASE_MANIFEST_PATH="$RELEASE_DIR/manifest.json"
STORE_PATH="$ROOT_DIR/store.json"
SOURCE_JAR="$SCRIPT_DIR/libs/zigbee-gateway-manager.jar"
RELEASE_JAR="$RELEASE_DIR/libs/zigbee-gateway-manager.jar"

VERSION="${1:-$(python3 -c "import json; print(json.load(open('$MANIFEST_PATH'))['version'])")}"

echo "=== Deploying Zigbee Gateway v$VERSION ==="

# Update version in manifests and store.json
echo "Updating version..."
python3 - <<PY
import json

paths = [
    "$MANIFEST_PATH",
    "$RELEASE_MANIFEST_PATH",
]

for path in paths:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data["version"] = "$VERSION"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

with open("$STORE_PATH", "r", encoding="utf-8") as f:
    store = json.load(f)

for mod in store.get("mods", []):
    if mod.get("id") == "zigbee-gateway-mod":
        mod["version"] = "$VERSION"

with open("$STORE_PATH", "w", encoding="utf-8") as f:
    json.dump(store, f, indent=2)
    f.write("\n")
PY

# Build
echo "Building..."
cd "$SCRIPT_DIR"
./build.sh

# Copy artifacts
echo "Copying artifacts..."
mkdir -p "$RELEASE_DIR/libs"
cp "$SOURCE_JAR" "$RELEASE_JAR"
cp "$MANIFEST_PATH" "$RELEASE_MANIFEST_PATH"

# Validate manifests
echo "Validating manifests..."
python3 - <<PY
import json
for path in ["$MANIFEST_PATH", "$RELEASE_MANIFEST_PATH", "$STORE_PATH"]:
    with open(path, "r", encoding="utf-8") as f:
        json.load(f)
    print("OK", path)
PY

# Git commit and push
echo "Committing and pushing..."
cd "$ROOT_DIR"
git add \
  "$SCRIPT_DIR/deploy.sh" \
  "$SCRIPT_DIR/src/com/ava/mods/zigbee/ZigbeeGatewayManager.java" \
  "$MANIFEST_PATH" \
  "$SOURCE_JAR" \
  "$RELEASE_MANIFEST_PATH" \
  "$RELEASE_JAR" \
  "$STORE_PATH"

COMMIT_MSG_FILE="$(mktemp)"
cat > "$COMMIT_MSG_FILE" <<EOF
Improve Zigbee gateway compatibility and declutter diagnostics

This release narrows Zigbee diagnostics to the service state,
keeps the published artifacts in sync, and improves serial-port
detection for devices that expose the coordinator on /dev/ttyS5.

Constraint: Zigbee gateways in our target devices often appear on /dev/ttyS5 instead of /dev/ttyUSB0
Constraint: Release artifacts must remain mirrored between sources/ and mods/
Rejected: Keep transport counters and port details as exposed entities | creates noisy diagnostics with little operational value
Rejected: Require manual serial-port entry for every install | fails on devices where auto-detection is needed
Confidence: high
Scope-risk: narrow
Reversibility: clean
Directive: Keep source manifest, release manifest, and release JAR aligned for every Zigbee deploy
Tested: sources/features/zigbee-gateway-mod/build.sh; JSON validation for manifests and store.json
Not-tested: On-device runtime verification against live Zigbee hardware
EOF

git commit -F "$COMMIT_MSG_FILE"
rm -f "$COMMIT_MSG_FILE"

git push origin main

echo "=== Done! v$VERSION deployed ==="
