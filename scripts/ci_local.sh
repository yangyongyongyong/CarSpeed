#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_HOME_DEFAULT="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

echo "== [1/4] Unit Tests =="
./gradlew :app:testDebugUnitTest

echo "== [2/4] Debug Build =="
./gradlew :app:assembleDebug -x lint

if command -v adb >/dev/null 2>&1; then
  if adb get-state >/dev/null 2>&1; then
    echo "== [3/4] Connected Android Tests (Espresso/UIAutomator) =="
    ./gradlew :app:connectedDebugAndroidTest
  else
    echo "== [3/4] Skip connected tests: no running device/emulator =="
  fi
else
  echo "== [3/4] Skip connected tests: adb not found =="
fi

echo "== [4/4] Done =="
