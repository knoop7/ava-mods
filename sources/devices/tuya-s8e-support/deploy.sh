#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MANIFEST_PATH="$SCRIPT_DIR/manifest.json"
RELEASE_DIR="$ROOT_DIR/mods/devices/tuya-s8e-support"
RELEASE_MANIFEST_PATH="$RELEASE_DIR/manifest.json"
STORE_PATH="$ROOT_DIR/store.json"
SOURCE_JAR="$SCRIPT_DIR/libs/tuya-s8e-support.jar"
RELEASE_JAR="$RELEASE_DIR/libs/tuya-s8e-support.jar"

VERSION="${1:-$(python3 -c "import json; print(json.load(open('$MANIFEST_PATH'))['version'])")}"

echo "=== Deploying Tuya S8E v$VERSION ==="

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
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")

with open("$STORE_PATH", "r", encoding="utf-8") as f:
    store = json.load(f)

for mod in store.get("mods", []):
    if mod.get("id") == "tuya-s8e-support":
        mod["version"] = "$VERSION"

with open("$STORE_PATH", "w", encoding="utf-8") as f:
    json.dump(store, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY

echo "Building..."
cd "$SCRIPT_DIR"
./build.sh "$VERSION"

echo "Copying artifacts..."
mkdir -p "$RELEASE_DIR/libs"
cp "$SOURCE_JAR" "$RELEASE_JAR"
cp "$MANIFEST_PATH" "$RELEASE_MANIFEST_PATH"

echo "Validating manifests..."
python3 - <<PY
import json
for path in ["$MANIFEST_PATH", "$RELEASE_MANIFEST_PATH", "$STORE_PATH"]:
    with open(path, "r", encoding="utf-8") as f:
        json.load(f)
    print("OK", path)
PY

echo "Committing and pushing..."
cd "$ROOT_DIR"
git add \
  "$SCRIPT_DIR/README.md" \
  "$SCRIPT_DIR/build.sh" \
  "$SCRIPT_DIR/deploy.sh" \
  "$SCRIPT_DIR/manifest.json" \
  "$SCRIPT_DIR/src/com/ava/mods/tuyas8e/TuyaS8EManager.java" \
  "$SOURCE_JAR" \
  "$RELEASE_MANIFEST_PATH" \
  "$RELEASE_JAR" \
  "$STORE_PATH"

COMMIT_MSG_FILE="$(mktemp)"
cat > "$COMMIT_MSG_FILE" <<EOF
Make Tuya S8E mod sensors push rotary and gesture updates in real time

This release keeps the rotary sensor as an automation-friendly counter
while adding listener-based state push hooks so Ava can reflect rotary
and gesture changes immediately instead of waiting for the default mod
polling interval. The rotary value still resets to 0 after inactivity,
and gesture direction still returns to idle after touch end.

Constraint: Ava's default mod entity refresh cadence is too slow for interactive rotary feedback
Constraint: Tuya S8E rotary input arrives as repeated KEY_F2/KEY_F3 pulses rather than absolute positions
Rejected: Increase global Ava polling frequency | risks unnecessary system-wide overhead
Rejected: Revert rotary sensor to transient direction pulses | breaks automation scenarios that need accumulated state
Confidence: high
Scope-risk: moderate
Reversibility: clean
Directive: Keep listener callbacks aligned with entity ids rotary_position and gesture_direction unless Ava's listener bridge changes
Tested: sources/devices/tuya-s8e-support/build.sh 1.0.5; JSON validation for manifests and store.json; adb input event capture on 192.168.0.60:5555
Not-tested: End-to-end Ava listener bridge behavior on-device after both sides are installed
EOF

git commit -F "$COMMIT_MSG_FILE"
rm -f "$COMMIT_MSG_FILE"

git push origin main

echo "=== Done! v$VERSION deployed ==="
