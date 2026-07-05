#!/usr/bin/env bash
# Grant BLE runtime permissions for ble-adv-proxy standalone scan/TX.
# Same permissions declared in manifest.json — use when Shizuku is unavailable.
set -euo pipefail

PKG="${1:-com.example.ava}"
ADB="${ADB:-adb}"

echo "Granting BLE ADV proxy permissions for $PKG"

"$ADB" shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN 2>/dev/null || true
"$ADB" shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
"$ADB" shell pm grant "$PKG" android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true

echo "Force-stopping $PKG so permission state is picked up on next launch..."
"$ADB" shell am force-stop "$PKG" || true

echo "Done. Re-open Ava and enable ble-adv-proxy mod if needed."
