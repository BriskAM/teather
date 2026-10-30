#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
swift run --package-path "$ROOT/macos" TeatherChecks
# Quit the Mac app first: the transport check binds its normal port.
swift run --package-path "$ROOT/macos" TeatherWireChecks
swift build --package-path "$ROOT/macos" -c release --product Teather
