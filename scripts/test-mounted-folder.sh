#!/bin/bash
# Run after test-android.sh has installed the debug/test/fixture APKs in an emulator.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
mkdir -p "$ROOT/output"
"$ADB" -e forward tcp:41692 tcp:41692
"$ADB" -e shell am instrument -w -e class dev.kunnect.FileShareTest#serveNativeMacMountFixture -e mountFixture true -e mountWritable true dev.kunnect.test/androidx.test.runner.AndroidJUnitRunner > "$ROOT/output/native-mount-fixture.txt" &
FIXTURE_PID=$!
cleanup() {
    "$ADB" -e shell am broadcast -a dev.kunnect.test.STOP_SHARE -p dev.kunnect >/dev/null 2>&1 || true
    "$ADB" -e forward --remove tcp:41692 >/dev/null 2>&1 || true
}
trap cleanup EXIT
python3 - <<'PY'
import urllib.request, urllib.error, time
for _ in range(80):
    try: urllib.request.urlopen('http://127.0.0.1:41692/Kunnect/', timeout=1)
    except urllib.error.HTTPError as error:
        if error.code == 401: break
    except OSError: pass
    time.sleep(.25)
else: raise SystemExit('Android file-share fixture did not start')
PY
swift run --package-path "$ROOT/macos" KunnectDiskChecks
"$ADB" -e shell am broadcast -a dev.kunnect.test.STOP_SHARE -p dev.kunnect >/dev/null
wait "$FIXTURE_PID"
rg -q 'OK \(1 test\)' "$ROOT/output/native-mount-fixture.txt"
