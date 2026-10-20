#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
TASK_CACHE=${CLEANREP_TOOL_CACHE:-/tmp/amazon-devpost-cleanrep}
export GRADLE_USER_HOME="$TASK_CACHE/gradle"
export ANDROID_SDK_HOME="$TASK_CACHE"
export ANDROID_USER_HOME="$TASK_CACHE/.android"
export CLEANREP_DEBUG_KEYSTORE="$ANDROID_USER_HOME/debug.keystore"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
if [[ -z "${ANDROID_HOME:-}" || ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
 printf '%s\n' 'Missing Android SDK35. Set ANDROID_HOME to an SDK containing platforms;android-35 and build-tools;35.0.0.' >&2
 exit 2
fi
if [[ ! -f "$CLEANREP_DEBUG_KEYSTORE" ]]; then
 keytool -genkeypair -keystore "$CLEANREP_DEBUG_KEYSTORE" -storepass android -alias androiddebugkey -keypass android -dname 'CN=CleanRep Local Debug,O=CleanRep,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
npm --prefix "$ROOT/apps/web" run build
GRADLE_BIN=${CLEANREP_GRADLE_BIN:-$ROOT/apps/phone/android/gradlew}
if ! command -v "$GRADLE_BIN" >/dev/null 2>&1; then printf '%s\n' 'Missing Gradle 8.11.1. Install it or set CLEANREP_GRADLE_BIN to its bin/gradle.' >&2; exit 2; fi
"$GRADLE_BIN" -p "$ROOT/apps/phone/android" --no-daemon --console=plain --max-workers=2 "${1:-assembleDebug}"
