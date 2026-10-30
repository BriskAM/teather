# Kunnect 0.1.0 — first personal-use prerelease

Android notification mirroring and two-way dismissal over an existing Tailscale connection, with a Material 3 Expressive Android app and a native Mac menu bar companion.

Assets:

- `Kunnect-Android.apk`: signed, R8-shrunk release for Android 9+.
- `Kunnect-macOS-arm64.zip`: ad-hoc-signed app for Apple Silicon, macOS 13+.
- `SHA256SUMS`: asset integrity hashes.

Install the Mac app in `/Applications`; grant notifications on Mac and notification access on Android. Keep Tailscale connected on both devices, allow phone → Mac TCP port 41691, then pair using the Mac's Tailscale IPv4 and pairing key. See the repository README for installation and sideloading instructions.

Verified: Android emulator notification lifecycle and encrypted pairing storage, update/filter/privacy/protected-notification behavior, stale-dismissal rejection, reconnect, Swift sync checks, live Mac TCP checks, lint, signed APK verification, release UI smoke checks, and Mac app launch/visual inspection.

Still to validate on hardware: cellular-to-Mac Tailscale delivery, long idle battery behavior, native macOS notification close/clear/group gestures, Focus modes, and launch-at-login approval. macOS notification permission was not granted during automated testing. Dismissal does not mark a conversation as read. Some Android notification content is redacted by the OS.

The Mac app is not Apple notarized. macOS may require **Privacy & Security → Open Anyway**. Android and Mac signing are independent: the APK uses a persistent local signing key; the Mac app uses an ad-hoc signature.
