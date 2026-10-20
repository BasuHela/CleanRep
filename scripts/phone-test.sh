#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d /tmp/cleanrep-phone-test.XXXXXX)
trap 'rm -rf "$WORK"' EXIT

KS1="$WORK/tv1.p12"
KS2="$WORK/tv2.p12"
PASS="cleanreptest"

keytool -genkeypair -keystore "$KS1" -storetype PKCS12 -storepass "$PASS" \
 -alias tv -keypass "$PASS" -dname 'CN=CleanRep TV 1,O=CleanRep,C=US' \
 -keyalg EC -keysize 256 -validity 365 >/dev/null 2>&1

keytool -genkeypair -keystore "$KS2" -storetype PKCS12 -storepass "$PASS" \
 -alias tv -keypass "$PASS" -dname 'CN=CleanRep TV 2,O=CleanRep,C=US' \
 -keyalg EC -keysize 256 -validity 365 >/dev/null 2>&1

mkdir -p "$WORK/classes"
javac --release 17 -Xlint:-options -d "$WORK/classes" \
 "$ROOT/apps/phone/android/app/src/main/java/org/cleanrep/phone/PhonePolicy.java" \
 "$ROOT/apps/phone/android/app/src/main/java/org/cleanrep/phone/PinnedTls.java" \
 "$ROOT/apps/phone/tests/PhonePolicyTest.java"

java -cp "$WORK/classes" org.cleanrep.phone.PhonePolicyTest "$KS1" "$KS2" "$PASS"
