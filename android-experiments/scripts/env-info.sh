#!/bin/bash
# Environment fingerprint for the runtime validation report.
# Usage: ./env-info.sh <avd-name> <serial> <outdir>
set -u
AVD="${1:?avd}"; SERIAL="${2:?serial}"; OUT="${3:?outdir}"
ADB="adb -s $SERIAL"
mkdir -p "$OUT"
{
  echo "avd=$AVD"
  echo "serial=$SERIAL"
  echo "date_utc=$(date -u +%FT%TZ)"
  echo "api=$($ADB shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "release=$($ADB shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "fingerprint=$($ADB shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
  echo "security_patch=$($ADB shell getprop ro.build.version.security_patch 2>/dev/null | tr -d '\r')"
  echo "abi=$($ADB shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
  echo "gms_version=$($ADB shell dumpsys package com.google.android.gms 2>/dev/null | grep -m1 versionName | tr -d '\r')"
  echo "host_image=$ANDROID_HOME/system-images/android-34/google_apis/x86_64"
} | tee "$OUT/env.txt"
$ADB shell dumpsys location > "$OUT/dumpsys-location-baseline.txt" 2>&1
$ADB shell pm list packages 2>/dev/null | grep -i -E "shield|gms|location" | tr -d '\r' | tee "$OUT/packages.txt"
