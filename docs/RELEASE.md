# Teather 0.2.2 — Android notification setup and touch feedback

- Notification setup explains Android's restricted-settings consent for APK installs, with App info and app-specific notification-access links plus a fallback for older phones.
- Connection-notification permission is shown separately from access to other apps' notifications.
- Card actions use symmetric padding and at least 48 dp height. Switch rows respond across their label and control, with a single accessible switch target and clipped feedback. Primary buttons can grow with text size.
- Screen content consumes Scaffold insets and clips at the navigation bar boundary.

Install `Teather-Android.apk` over the existing app to keep pairing and settings. This is an Android-only patch; use the existing [macOS 0.2.1 installer](https://github.com/BriskAM/teather/releases/tag/v0.2.1).

If Android shows **Restricted setting**, first attempt notification access, then open **Settings → Apps → Teather → ⋮ → Allow restricted settings**, authenticate, and return to enable access. The option only appears when Android has restricted the installation; device labels vary. The app cannot grant this consent for you. See [Android's restricted settings guide](https://support.google.com/android/answer/12623953?hl=en).

## Teather 0.2.1 — the new name for Kunnect

- App branding, Android/Swift project names, Mac menu bar text, DMG installer and downloadable artifacts now use **Teather**.
- GitHub repository and local project folder are named `teather`.
- App IDs, preferences, pairing/Keychain entries, and the existing Android signing identity are preserved for upgrades.
- Finder volumes use `/Teather/` for updated pairs, while retaining `/Kunnect/` compatibility with existing companions.

Install `Teather-Android.apk` over your existing Android app. On Mac, quit Kunnect, open `Teather-macOS-arm64.dmg`, drag Teather into Applications, and remove the old Kunnect.app copy. Existing pairing and settings should remain available; macOS may request Keychain access when launching the renamed app.

Notification mirroring, two-way dismissal, read-only-by-default Android folder mounting, optional write access and battery controls continue unchanged. The Mac app remains ad-hoc signed, not Apple notarized.
