# Kunnect 0.2.0 — Android folder volumes and DMG installer

- Open `Kunnect-macOS-arm64.dmg` and drag Kunnect to Applications. A ZIP remains available as an alternative.
- Select a folder in Android **Files**, enable sharing, then choose **Mount Android folder** from the Mac menu bar. Finder sees a native WebDAV network volume; no FUSE driver or background mirroring.
- Read-only by default. Enable **Allow changes from Mac** separately to create, replace, rename, move, and delete documents. Eject/remount after changing access. Android enforces revocation immediately.
- Optional, skippable battery-optimization exemption prompt, with current status in Settings. An exemption improves availability; it does not lower power use by itself.
- Two-minute idle heartbeats and retry backoff up to two minutes; file streaming uses fixed buffers and bounded workers, with no background folder scans or wake locks.

Tested: Android emulator notification regression and WebDAV authentication, traversal rejection, read-only enforcement, ranged reads, staged replacement/interrupted-upload preservation, chunked uploads, moves, locks and revocation; real native macOS WebDAV mount against the emulator, read-only enforcement, writable create/read/rename/delete/file-copy, and eject; Mac sync/TCP checks; release lint; DMG mount/layout and embedded app signature.

Install both updated apps. Existing pairing and Android settings are preserved when updating from the previous signed APK. Tailscale policy must permit phone → Mac TCP 41691 and Mac → phone TCP 41692; incoming connections must be allowed on the phone. Choose the shared folder again if Android reports its permission unavailable.

Limits: SAF is not a full POSIX filesystem. Extended attributes/timestamps, recursive server-side directory COPY and destination-overwriting MOVE are unsupported; provider capabilities govern moves. Prefer local device storage. Finder previews and transfers can use battery. Physical-phone idle drain, cross-network tailnet conditions, and macOS notification dismissal gestures still need hardware acceptance. The Mac app is ad-hoc signed, not Apple notarized; downloaded builds may require **Privacy & Security → Open Anyway**.
