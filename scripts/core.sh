#!/usr/bin/env bash
# The TV app's plain-Java server (apps/core): compile against Java 8's API, then run every *Test class.
# No dependencies and no Gradle, so it runs the same on a laptop and inside a sandbox.
# Usage: bash scripts/core.sh [filter]   (filter: a substring of Class.method, to run a subset)
#        bash scripts/core.sh --compile-only   (main sources only, for `make browser-test` and `make server`)
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
CORE="$ROOT/apps/core"
OUT="$CORE/build"

# The TV app has minSdk 25. These Java 8 APIs arrived in Android API 26, so Lint's NewApi check would fail.
if grep -rnE 'java\.util\.Base64|java\.time\.|java\.nio\.file\.|String\.join\(|Unsigned|ThreadLocal\.withInitial|getInstanceStrong' \
  "$CORE/src/main/java"; then
  echo "core: the lines above use an API that Android 7.1 (API 25) lacks" >&2
  exit 1
fi
# Android's ICU regex refuses this flag at run time (its \d, \w and \b are Unicode already). Only
# Wording.regex() may try it, because it falls back to a plain compile.
if grep -rn 'UNICODE_CHARACTER_CLASS' "$CORE/src/main/java" | grep -v '/Wording\.java:'; then
  echo "core: compile regexes through Wording.regex(); Android refuses UNICODE_CHARACTER_CLASS" >&2
  exit 1
fi

rm -rf "$OUT/classes" "$OUT/test-classes"
mkdir -p "$OUT/classes" "$OUT/test-classes"
mapfile -t main_src < <(find "$CORE/src/main/java" -name '*.java' | sort)
javac --release 8 -encoding UTF-8 -Xlint:all,-options -d "$OUT/classes" "${main_src[@]}"
if [[ "${1:-}" == "--compile-only" ]]; then
  echo "core: compiled to $OUT/classes"
  exit 0
fi
mapfile -t test_src < <(find "$CORE/src/test/java" -name '*.java' | sort)
# The TLS tests shake hands with the phone app's own pinning client, compiled from its sources (not copied).
PHONE="$ROOT/apps/phone/android/app/src/main/java/org/cleanrep/phone"
javac --release 8 -encoding UTF-8 -Xlint:all,-options -cp "$OUT/classes" -d "$OUT/test-classes" \
  "${test_src[@]}" "$PHONE/PinnedTls.java" "$PHONE/PhonePolicy.java"
java -Dcleanrep.root="$ROOT" -cp "$OUT/classes:$OUT/test-classes" org.cleanrep.core.testing.TestRunner \
  "$OUT/test-classes" "${1:-}"
