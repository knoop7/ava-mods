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
Keep Tuya S8E rotary state persistent while adding manual diagnostic reset

This release removes the rotary sensor's automatic reset-to-zero behavior
so the counter can stay stable for automations and diagnostics. A new
diagnostic button lets Home Assistant reset the rotary state on demand,
while gesture direction behavior remains unchanged.

Constraint: Rotary input arrives as repeated KEY_F2/KEY_F3 pulses rather than an absolute hardware position
Constraint: Reset behavior must remain accessible from Home Assistant without restoring automatic zeroing
Rejected: Keep inactivity-based auto reset | hides persistent rotary state needed for automation logic
Rejected: Remove reset entirely | makes it harder to recover counter state during diagnostics
Confidence: high
Scope-risk: narrow
Reversibility: clean
Directive: Keep the manual reset button bound to rotary_position semantics unless the entity model changes in Ava/HASS
Tested: sources/devices/tuya-s8e-support/build.sh 1.0.10; JSON validation for manifests and store.json
Not-tested: End-to-end Home Assistant button press against an installed 1.0.10 package
EOF

git commit -F "$COMMIT_MSG_FILE"
rm -f "$COMMIT_MSG_FILE"

git push origin main

echo "=== Done! v$VERSION deployed ==="
