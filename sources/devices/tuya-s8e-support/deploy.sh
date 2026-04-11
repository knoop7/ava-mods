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
Make Tuya S8E rotary and gesture sensors usable for automation

This release changes the Tuya S8E input handling so the rotary sensor
behaves like a real automation counter: F2 increments, F3 decrements,
the value updates in real time while rotating, and it resets to 0
after 30 seconds of inactivity. Gesture direction also updates during
movement and returns to idle shortly after touch end.

Constraint: Rotary input arrives as repeated KEY_F2/KEY_F3 pulses rather than absolute positions
Constraint: Idle state must be represented as 0 for rotary and idle for gesture to keep automations predictable
Rejected: Treat rotary input as a transient direction pulse | loses cumulative state needed for automation rules
Rejected: Keep the steps unit on Rotary Position | implies a physical unit rather than an automation-friendly counter
Confidence: high
Scope-risk: moderate
Reversibility: clean
Directive: Preserve rotary reset-to-zero semantics unless automation consumers are updated to handle a non-idle resting value
Tested: sources/devices/tuya-s8e-support/build.sh 1.0.4; JSON validation for manifests and store.json; adb input event capture on 192.168.0.60:5555
Not-tested: End-to-end automation execution through Ava UI after deployment
EOF

git commit -F "$COMMIT_MSG_FILE"
rm -f "$COMMIT_MSG_FILE"

git push origin main

echo "=== Done! v$VERSION deployed ==="
