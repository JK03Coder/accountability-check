# Accountability Check

Native Android app concept: before using an app, state what you intend to accomplish and how long it should take. At the deadline, see that intention again and leave the app.

**Status: native Android MVP implemented.** This repository contains the app, an isolated test app, and automated tests. Physical-phone behavior still needs validation on the user's device.

- [MVP plan and implementation handoff](docs/MVP-PLAN.md)
- [Technical research and official sources](docs/ANDROID-RESEARCH.md)
- [Implementation status and known limits](docs/IMPLEMENTATION-STATUS.md)
- [Device verification checklist](docs/DEVICE-TEST-CHECKLIST.md)

The app is written in Kotlin for Android 10+ (`minSdk 29`, `targetSdk 36`). It uses a user-enabled Accessibility service to detect a selected app, a focusable overlay for the intention and duration form, an elapsed-time deadline, and Android's Home action at expiry. The time-up reminder stays on screen until dismissed; a new intention appears when the selected app is opened again. Window-change events and a short active-window recheck cover recent-apps gestures that return to the same app without a new app-open event. Its on-device DataStore keeps settings and a bounded session history. The dashboard lets the user pause protection, select apps, and delete history. **Settings** provides System (default), Light, and Dark appearance choices for both the dashboard and prompts, plus the experimental phone-unlock mode and its lock behavior. The launcher uses an adaptive icon that fills the launcher shape. No account or backend is needed.

Build and verify on Windows with Android SDK 36 and JDK 17–23 (the tested local toolchain uses JDK 20):

```powershell
.\scripts\build.ps1
```

The installable debug APK is [app-debug.apk](app/build/outputs/apk/debug/app-debug.apk). To run the emulator smoke tests, start a dedicated Android 16 emulator, then explicitly name its serial:

```powershell
.\scripts\device-test.ps1 -Serial emulator-5556
```

The device script installs a small test target and temporarily enables the app's Accessibility service for its tests, then restores the device's previous service settings. Use it on a dedicated emulator or test device. The test target is not part of the product APK.

To build a signed, optimized release APK on Windows, run:

```powershell
.\scripts\build-release.ps1
```

The first run creates a dedicated signing key at `%USERPROFILE%\.android\accountability-check-signing\release-key.p12` and stores its password in a Windows user-encrypted file beside it. Neither file belongs in Git. Keep a secure backup of the key; later releases must use the **same key** to update an installed release build. The encrypted password file works only for this Windows user on this computer, so save the decrypted password in a secure password manager before moving to another computer. The script runs release unit tests and lint, aligns and signs the APK, then verifies its signature and prints its SHA-256 hash. The APK is written to `artifacts\release\AccountabilityCheck-v0.1.0.apk`. A release signed with this key cannot install over the earlier debug-signed APK without first uninstalling that debug build, which deletes its local settings and history.

For manual testing, install only the app APK, open Accountability Check, read and accept the separate Accessibility disclosure, enable the service in Android Settings, choose one or more launchable apps under **Your apps**, and open a selected app. Some sideloaded builds may require **Allow restricted settings** in Android's app info first. Use **Pause** or Android Settings to disable the service whenever desired.

The app returns the user Home; it cannot terminate other apps, verify whether a task was finished, or enforce its rules while the service is disabled. Google Play publication has not been requested or attempted.
