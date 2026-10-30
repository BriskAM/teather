#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$ROOT/android/keystore.properties" ]; then
  echo 'Create or restore android/keystore.properties before building a signed release.' >&2
  exit 1
fi
"$ROOT/android/gradlew" -p "$ROOT/android" :app:assembleRelease :app:lintRelease
mkdir -p "$ROOT/output"
cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$ROOT/output/Teather-Android.apk"
