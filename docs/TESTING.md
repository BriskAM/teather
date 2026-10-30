# Validation — 0.1.0

Tested locally on 30 October 2026 with an Android 15/API 35 arm64 emulator and an Apple Silicon Mac. The Android app compiles against SDK 37.0 and targets API 35; minimum Android is API 28. The Mac app targets macOS 13+.

## Automated checks

- **11 Mac sync/security checks:** silent initial snapshot; duplicate suppression; live updates; Android removal without an echo; version-specific Mac dismissal; protected/local-only behavior; pending dismissal expiry; resolved commands; stale snapshot removal; fragmented/multiple/oversized frames; tailnet address validation; authentication.
- **Live Mac TCP checks:** reject unauthenticated traffic and wrong keys; authenticate the expected device; ping/pong; receive a snapshot; send a dismissal; reject a different phone using the same key; reconnect the paired phone.
- **Android unit test:** accept only the Tailscale IPv4 range and reject LAN, loopback, DNS, malformed and out-of-range inputs.
- **Two emulator instrumentation tests:** encrypted Keystore-backed pairing-key round trip; and a full notification lifecycle against an isolated socket peer. The lifecycle posts real notifications from a separate fixture APK, verifies updates and revision changes, rejects a stale dismissal, removes a notification by remote command, observes phone-side removal, refuses protected ongoing notifications, reconnects and restores the current snapshot, hides content, applies local-only dismissal, and removes filtered-out apps.
- **Android debug and release lint:** passed, with non-blocking dependency/style suggestions.
- **Signed/R8-shrunk release APK:** built and verified with `apksigner`; installed separately from the debug APK for release UI smoke testing.
- **Real emulator UI smoke checks:** home, app picker, settings, pairing sheet, and dark theme verified through UIAutomator and screenshots. The tests run against the installable release as well as the debug UI.
- **Mac app:** release binary built and ad-hoc signed; launched successfully; settings window visually inspected and captured; no bundled non-system dylibs. Menu bar control is implemented with AppKit `NSStatusItem`.

`./scripts/test-macos.sh`, `./scripts/test-android.sh`, and `python3 scripts/capture-android.py` reproduce the checks. Quit the Mac companion before running its TCP test so port 41691 is available. The Android fixture APK is only for tests and is excluded from the release artifact. The test script grants notification access only on the connected test device; run it on an emulator.

## Efficiency evidence

The implemented idle path has a blocking socket read and a coroutine waiting on a conflated change channel, with a 30-second heartbeat rather than a polling loop. Selected notification bursts are coalesced for 150 ms, unchanged records are omitted, application labels are cached, and unavailable-host retries back off to 30 seconds. Frame sizes, notification counts and body lengths are bounded. No app wake lock or disk notification queue is used. R8 removes unused Android code and resources in release builds.

In one local idle observation after several minutes, the Mac app showed 0.0% CPU and approximately 50 MiB resident memory. This is a point observation, not a battery or performance guarantee. Emulator tests do not establish physical-phone battery drain, radio wakeups, or manufacturer background-process behavior.

## Acceptance tests still requiring hardware / OS permission

- Real Android phone → Mac delivery over Tailscale with the phone on cellular and Mac on another network, including tailnet policy and firewall configuration.
- Long idle/Doze and overnight battery behavior on the user's actual phone; Tailscale reconnects after network changes and phone reboot.
- Notification authorization and actual banner delivery on this Mac. The settings window launched, but notification permission has not been granted by the user.
- Native Mac Notification Center close, Clear All, and group-dismiss gestures, including Focus modes and macOS-version differences. Explicit dismiss callbacks are handled and a **Dismiss on both devices** action is provided, but native gestures have not been exercised end to end here.
- Launch-at-login approval from `/Applications`, and Gatekeeper behavior for a downloaded ad-hoc-signed build.

macOS Accessibility permission was unavailable, so the desktop's native notification UI could not be driven automatically. The simulator/socket checks validate protocol and state transitions; they are not represented as a hardware Tailscale or native Notification Center acceptance test. The release is marked as a prerelease for these reasons.
