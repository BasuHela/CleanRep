#!/usr/bin/env bash
# The TV app (debug only): builds the phone APK first, then stages it with the server's catalog and contracts under
# assets/cleanrep-home/, where the TV's 8105 listener serves the APK and the server reads the rest.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
TASK_CACHE=${CLEANREP_TOOL_CACHE:-/tmp/amazon-devpost-cleanrep}
export GRADLE_USER_HOME="$TASK_CACHE/gradle"
export ANDROID_SDK_HOME="$TASK_CACHE"
export ANDROID_USER_HOME="$TASK_CACHE/.android"
export CLEANREP_DEBUG_KEYSTORE="$ANDROID_USER_HOME/debug.keystore"
if [[ "${1:-assembleDebug}" == *[Rr]elease* ]]; then printf '%s\n' 'CleanRep is debug-only for now: build it with assembleDebug.' >&2; exit 2; fi
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
if [[ -z "${ANDROID_HOME:-}" || ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
 printf '%s\n' 'Missing Android SDK35. Set ANDROID_HOME to an SDK containing platforms;android-35 and build-tools;35.0.0.' >&2
 exit 2
fi
if [[ ! -f "$CLEANREP_DEBUG_KEYSTORE" ]]; then
 keytool -genkeypair -keystore "$CLEANREP_DEBUG_KEYSTORE" -storepass android -alias androiddebugkey -keypass android -dname 'CN=CleanRep Local Debug,O=CleanRep,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
# phone-build.sh also builds the web pages, which both apps package.
bash "$ROOT/scripts/phone-build.sh"
PHONE_APK="$ROOT/apps/phone/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$PHONE_APK" ]] || { printf '%s\n' "The phone build made no APK at $PHONE_APK." >&2; exit 2; }
GEN="$ROOT/apps/tv/android/app/build/generated/cleanrep-home-assets"
rm -rf "$GEN"
mkdir -p "$GEN/cleanrep-home/domain" "$GEN/cleanrep-home/contracts"
cp "$PHONE_APK" "$GEN/cleanrep-home/cleanrep-phone.apk"
cp "$ROOT/packages/domain/catalog.json" "$GEN/cleanrep-home/domain/"
cp "$ROOT"/packages/contracts/*.json "$GEN/cleanrep-home/contracts/"
GRADLE_BIN=${CLEANREP_GRADLE_BIN:-$ROOT/apps/tv/android/gradlew}
if ! command -v "$GRADLE_BIN" >/dev/null 2>&1; then printf '%s\n' 'Missing Gradle 8.11.1. Install it or set CLEANREP_GRADLE_BIN to its bin/gradle.' >&2; exit 2; fi
"$GRADLE_BIN" -p "$ROOT/apps/tv/android" --no-daemon --console=plain --max-workers=2 "${1:-assembleDebug}"
