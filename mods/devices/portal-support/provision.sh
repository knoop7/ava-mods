#!/bin/bash
set -euo pipefail

PKG="${1:-com.example.ava}"
SERIAL="${2:-}"

ADB="${ADB:-adb}"
TARGET=()
if [ -n "$SERIAL" ]; then
    TARGET=(-s "$SERIAL")
fi

# Keep in sync with PortalPermissionHelper.GRANT_PERMISSIONS / GRANT_APPOPS
PM_PERMISSIONS=(
    android.permission.WRITE_SECURE_SETTINGS
    android.permission.RECORD_AUDIO
    android.permission.CAMERA
    android.permission.READ_LOGS
)
APPOPS=(
    WRITE_SETTINGS
    SYSTEM_ALERT_WINDOW
)

grant_pm() {
    local perm="$1"
    if "$ADB" "${TARGET[@]}" shell pm grant "$PKG" "$perm" 2>/dev/null; then
        echo "  granted $perm"
    else
        echo "  warn: pm grant failed for $perm"
    fi
}

grant_appop() {
    local op="$1"
    if "$ADB" "${TARGET[@]}" shell appops set "$PKG" "$op" allow 2>/dev/null; then
        echo "  appops $op = allow"
    else
        echo "  warn: appops set failed for $op"
    fi
}

echo "Granting Portal permissions for $PKG"
"$ADB" "${TARGET[@]}" get-state >/dev/null

echo "pm grant:"
for perm in "${PM_PERMISSIONS[@]}"; do
    grant_pm "$perm"
done

echo "appops:"
for op in "${APPOPS[@]}"; do
    grant_appop "$op"
done

cat <<EOF

Note: Presence detection normally reads logcat through a Shizuku or root shell at runtime.
After running this script, still install Shizuku (https://shizuku.rikka.app/) and authorize Ava
if Portal Presence stays unavailable. Restart Ava after pm grant if a permission was newly granted.

Done.
EOF
