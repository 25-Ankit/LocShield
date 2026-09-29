#!/bin/bash
# Run one diagnostic action headlessly and capture LS_DIAG logcat lines.
# Usage: ./run-action.sh <serial> <package> <action> [extra am args...] <outdir> <wait_secs>
set -u
SERIAL="$1"; PKG="$2"; ACTION="$3"; OUTDIR="$4"; WAIT="$5"
shift 5
ADB="adb -s $SERIAL"
mkdir -p "$OUTDIR"
CLS="$PKG/.MainActivity"
$ADB logcat -c 2>/dev/null
# shellcheck disable=SC2068
$ADB shell am start -n "$CLS" --es action "$ACTION" $@ > "$OUTDIR/am-start-$ACTION.txt" 2>&1
sleep "$WAIT"
$ADB logcat -d -s LS_DIAG > "$OUTDIR/logcat-$ACTION.txt" 2>&1
$ADB logcat -d | grep -i -E "location|gnss|geofence|passive" | tail -n 60 > "$OUTDIR/logcat-location-$ACTION.txt" 2>&1
$ADB shell dumpsys location > "$OUTDIR/dumpsys-location-$ACTION.txt" 2>&1
echo "captured $ACTION -> $OUTDIR"
