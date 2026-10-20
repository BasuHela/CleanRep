#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ADB_BIN=${CLEANREP_ADB_BIN:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
APK="$ROOT/apps/phone/android/app/build/outputs/apk/debug/app-debug.apk"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then printf '%s\n' 'Missing adb. Install Android platform-tools or set CLEANREP_ADB_BIN.' >&2; exit 2; fi
if [[ ! -f "$APK" ]]; then printf '%s\n' 'Build the debug APK with scripts/phone-build.sh first.' >&2; exit 2; fi
if [[ -n "${CLEANREP_PHONE_REVERSE:-}" ]]; then
 if [[ ! "$CLEANREP_PHONE_REVERSE" =~ ^[0-9]+$ ]] || (( CLEANREP_PHONE_REVERSE < 1 || CLEANREP_PHONE_REVERSE > 65535 )); then
  printf '%s\n' 'CLEANREP_PHONE_REVERSE must be a TCP port in 1..65535 (e.g. 8105 or 8443).' >&2
  exit 2
 fi
fi
if [[ -n "${CLEANREP_ADB_SERIAL:-}" ]]; then adb_args=(-s "$CLEANREP_ADB_SERIAL"); else adb_args=(); fi
"$ADB_BIN" "${adb_args[@]}" install -r "$APK"
if [[ -n "${CLEANREP_PHONE_REVERSE:-}" ]]; then
 "$ADB_BIN" "${adb_args[@]}" reverse "tcp:$CLEANREP_PHONE_REVERSE" "tcp:$CLEANREP_PHONE_REVERSE"
fi
"$ADB_BIN" "${adb_args[@]}" shell am start -n org.cleanrep.phone/.MainActivity
