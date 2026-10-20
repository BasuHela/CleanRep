#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ADB_BIN=${CLEANREP_ADB_BIN:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
APK="$ROOT/apps/tv/android/app/build/outputs/apk/debug/app-debug.apk"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then printf '%s\n' 'Missing adb. Install Android platform-tools or set CLEANREP_ADB_BIN.' >&2; exit 2; fi
if [[ ! -f "$APK" ]]; then printf '%s\n' 'Build the debug APK with scripts/tv-build.sh first.' >&2; exit 2; fi
if [[ -n "${CLEANREP_ADB_SERIAL:-}" ]]; then adb_args=(-s "$CLEANREP_ADB_SERIAL"); else adb_args=(); fi
"$ADB_BIN" "${adb_args[@]}" install -r "$APK"
# The app serves 8105 itself. A reverse left over from another session would hold that port on the device.
"$ADB_BIN" "${adb_args[@]}" reverse --remove tcp:8105 2>/dev/null || true
"$ADB_BIN" "${adb_args[@]}" shell am start -n org.cleanrep.tv/.MainActivity
