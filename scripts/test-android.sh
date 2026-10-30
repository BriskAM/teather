#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ROOT/android/gradlew" -p "$ROOT/android" :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :fixture:assembleDebug
"$ADB" -e wait-for-device
"$ADB" -e install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -e install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$ADB" -e install -r "$ROOT/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
"$ADB" -e shell pm grant dev.kunnect android.permission.POST_NOTIFICATIONS
"$ADB" -e shell pm grant dev.kunnect.fixture android.permission.POST_NOTIFICATIONS
"$ADB" -e shell cmd notification allow_listener dev.kunnect/.NotificationBridge
RESULT="$ROOT/output/android-instrumentation.txt"
mkdir -p "$ROOT/output"
"$ADB" -e shell am instrument -w dev.kunnect.test/androidx.test.runner.AndroidJUnitRunner | tee "$RESULT"
rg -q 'OK \([0-9]+ tests?\)' "$RESULT"
