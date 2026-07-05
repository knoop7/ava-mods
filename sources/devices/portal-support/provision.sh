#!/bin/bash
set -euo pipefail

PKG="${1:-com.example.ava}"
SERIAL="${2:-}"

ADB="${ADB:-adb}"
TARGET=()
if [ -n "$SERIAL" ]; then
    TARGET=(-s "$SERIAL")
fi

echo "Granting Portal permissions for $PKG"

"$ADB" "${TARGET[@]}" get-state >/dev/null

for perm in \
    android.permission.WRITE_SECURE_SETTINGS \
    android.permission.RECORD_AUDIO \
    android.permission.CAMERA
do
    "$ADB" "${TARGET[@]}" shell pm grant "$PKG" "$perm"
    echo "  granted $perm"
done

"$ADB" "${TARGET[@]}" shell appops set "$PKG" WRITE_SETTINGS allow
"$ADB" "${TARGET[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
echo "  set WRITE_SETTINGS + SYSTEM_ALERT_WINDOW = allow"

echo "Done."
