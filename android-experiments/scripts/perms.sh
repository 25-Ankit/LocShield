#!/bin/bash
# Grant/revoke location permissions for a test package.
# Usage: ./perms.sh <serial> <package> <fine|coarse|none>
set -u
SERIAL="$1"; PKG="$2"; MODE="$3"
ADB="adb -s $SERIAL"
$ADB shell pm revoke "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null
$ADB shell pm revoke "$PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
$ADB shell pm revoke "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null
case "$MODE" in
  fine) $ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION ;;
  coarse) $ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION ;;
  none) ;;
esac
$ADB shell dumpsys package "$PKG" 2>/dev/null | grep -A6 "runtime permissions" | tr -d '\r'
