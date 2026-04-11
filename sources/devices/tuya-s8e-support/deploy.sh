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
Expose Tuya S8E screen and climate controls as a dedicated device mod

This release adds a Tuya S8E-specific device mod that separates
main-screen and small-screen backlight control, and mirrors the
existing shell-based temperature and humidity parsing logic in Java
so the mod can surface the same device capabilities directly.

Constraint: Tuya S8E backlight control depends on root-writable sysfs nodes
Constraint: Temperature and humidity parsing must stay aligned with the existing device shell script behavior
Rejected: Reuse generic Zigbee gateway mod for screen controls | unrelated scope and device semantics
Rejected: Invent simplified climate parsing | would drift from the device's current shell-based behavior
Confidence: medium
Scope-risk: moderate
Reversibility: clean
Directive: Keep Tuya S8E sysfs paths and climate parsing in sync with the on-device shell scripts before extending this mod
Tested: sources/devices/tuya-s8e-support/build.sh; JSON validation for manifests and store.json
Not-tested: On-device validation of screen toggles and climate readings through Ava UI
EOF

git commit -F "$COMMIT_MSG_FILE"
rm -f "$COMMIT_MSG_FILE"

git push origin main

echo "=== Done! v$VERSION deployed ==="
