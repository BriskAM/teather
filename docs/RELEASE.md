# Teather 0.2.1 — the new name for Kunnect

- App branding, Android/Swift project names, Mac menu bar text, DMG installer and downloadable artifacts now use **Teather**.
- GitHub repository and local project folder are named `teather`.
- App IDs, preferences, pairing/Keychain entries, and the existing Android signing identity are preserved for upgrades.
- Finder volumes use `/Teather/` for updated pairs, while retaining `/Kunnect/` compatibility with existing companions.

Install `Teather-Android.apk` over your existing Android app. On Mac, quit Kunnect, open `Teather-macOS-arm64.dmg`, drag Teather into Applications, and remove the old Kunnect.app copy. Existing pairing and settings should remain available; macOS may request Keychain access when launching the renamed app.

Notification mirroring, two-way dismissal, read-only-by-default Android folder mounting, optional write access and battery controls continue unchanged. The Mac app remains ad-hoc signed, not Apple notarized.
