# Accountability Check

Native Android app concept: before using an app, state what you intend to accomplish and how long it should take. At the deadline, see that intention again and leave the app.

**Status: native Android MVP implemented.** This repository contains the app, an isolated test app, and automated tests. Physical-phone behavior still needs validation on the user's device.

- [MVP plan and implementation handoff](docs/MVP-PLAN.md)
- [Technical research and official sources](docs/ANDROID-RESEARCH.md)
- [Implementation status and known limits](docs/IMPLEMENTATION-STATUS.md)
- [Device verification checklist](docs/DEVICE-TEST-CHECKLIST.md)

The app is written in Kotlin for Android 10+ (`minSdk 29`, `targetSdk 36`). It uses a user-enabled Accessibility service to detect a selected app, a focusable overlay for the intention and duration form, an elapsed-time deadline, and Android's Home action at expiry. The time-up reminder stays on screen until dismissed; a new intention appears when the selected app is opened again. Window-change events and a short active-window recheck cover recent-apps gestures that return to the same app without a new app-open event. Its on-device DataStore keeps settings and a bounded session history. The dashboard lets the user pause protection, select apps, and delete history. **Settings** provides System (default), Light, and Dark appearance choices for both the dashboard and prompts, plus the experimental phone-unlock mode and its lock behavior. The launcher uses an adaptive icon that fills the launcher shape. No account or backend is needed.

Enable **Intention timer** notifications from Today or Settings to see each active intention with Android's live countdown. The setup card disappears from Today once notifications are enabled; its controls remain available in Settings. Compatible phones can also show a Live Update timer chip in the status bar; Android and the phone's Live Updates settings control promotion. Timers retain their original deadlines when you switch apps or lock the phone. Notifications end on expiry, pause, mode changes, removal of the protected app, or service disconnection. Swiping away a notification where Android allows it hides that session's notification without ending its timer. Lock-screen intention details follow Android's privacy settings.

## Linux / Omarchy development

Use Java 21 through Omarchy's existing `mise` installation. The repository's `mise.toml` selects Java and sets `ANDROID_HOME` to `~/Android/Sdk` without changing your global Java version.

```bash
omarchy pkg add android-tools android-udev
mise trust
mise install
```

Download the Linux command-line tools from the [Android SDK downloads](https://developer.android.com/studio#command-tools), verify the published SHA-256 checksum, and extract them so `sdkmanager` is at `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`. Then install the SDK packages (review and accept the license prompts):

```bash
mise exec -- sdkmanager --sdk_root="$HOME/Android/Sdk" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
mise exec -- ./scripts/build.sh
```

The build script compiles the app, test fixture, and instrumentation APK, runs unit tests, and checks Android lint. Gradle comes from the checked-in wrapper; no system Gradle or Kotlin installation is needed. Android Studio and an emulator are optional when developing with a physical phone. If you use Android Studio, select the mise Java 21 installation as its Gradle JDK and `~/Android/Sdk` as its SDK.

To connect a phone, enable Developer options and USB debugging, plug in a USB data cable, unlock the phone, and accept its **Allow USB debugging** prompt. Check the connection and install the debug APK on the explicitly selected device:

```bash
adb devices -l
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Replace `PHONE_SERIAL` with the serial shown by `adb`. If USB permissions fail after installing `android-udev`, unplug and reconnect the phone first. Follow the [device checklist](docs/DEVICE-TEST-CHECKLIST.md) for manual testing.

A fresh Linux installation creates a new debug signing key in `~/.android/debug.keystore`, or `$XDG_CONFIG_HOME/.android/debug.keystore` when `XDG_CONFIG_HOME` is set (on this Omarchy setup: `~/.config/.android/debug.keystore`). Keep this key to install future debug updates without clearing app data. Updating an APK signed on Windows requires its original signing key; if installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, recover that key before trying again. Uninstalling the existing app deletes its settings and history. Signed release updates likewise require the original release key and its password; the Windows release script's encrypted password file cannot be decrypted by Linux.

For automated tests, use a **dedicated test device or emulator**: the suite changes Accessibility/protection settings and interrupts active sessions. Build first, then select its serial explicitly:

```bash
adb -s TEST_SERIAL install -r test-target/build/outputs/apk/debug/test-target-debug.apk
adb -s TEST_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TEST_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s TEST_SERIAL shell am instrument -w -r -e class nz.co.accountabilitycheck.AccessibilitySmokeTest nz.co.accountabilitycheck.test/androidx.test.runner.AndroidJUnitRunner
```

Verify the output ends with `OK (11 tests)` and has no failures; `adb`'s exit code alone does not establish that instrumentation passed.

The five focused notification checks use synthetic notifications and do not change protection settings or saved sessions. Enable notifications in the app first (otherwise the posting/expiry check is skipped), then run:

```bash
adb -s PHONE_SERIAL shell am instrument -w -r -e class nz.co.accountabilitycheck.SessionNotificationTest nz.co.accountabilitycheck.test/androidx.test.runner.AndroidJUnitRunner
```

To build an optimized, signed release APK on Linux:

```bash
mise exec -- ./scripts/build-release.sh
```

This runs release unit tests and lint, aligns and signs the APK, verifies it, and writes the APK and SHA-256 file to `artifacts/release/`. The first run creates a dedicated release key and password file in `${XDG_CONFIG_HOME:-$HOME}/.android/accountability-check-signing` (on this Omarchy setup: `~/.config/.android/accountability-check-signing`). The directory and files are private to your user. **Back up both `release-key.p12` and `release-password` securely**; they are required for future release updates and never belong in Git.

Version 0.2.0 starts a new release signing lineage because the Windows release key is unavailable. It cannot install over the original v0.1.0 release or a debug build. Uninstalling first deletes local settings and history. See the [0.2.0 release notes](docs/RELEASE-NOTES-v0.2.0.md).

## Windows development

Build and verify on Windows with Android SDK 36 and JDK 17–23 (the tested Windows toolchain uses JDK 20):

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

The first run creates a dedicated signing key at `%USERPROFILE%\.android\accountability-check-signing\release-key.p12` and stores its password in a Windows user-encrypted file beside it. Neither file belongs in Git. Keep a secure backup of the key; later releases must use the **same key** to update an installed release build. For builds continuing the 0.2.0 Linux signing lineage, import that existing key and its password instead of generating another Windows key. The encrypted password file works only for this Windows user on this computer, so save the decrypted password in a secure password manager before moving to another computer. The script runs release unit tests and lint, aligns and signs the APK, then verifies its signature and prints its SHA-256 hash. The APK is written to `artifacts\release\AccountabilityCheck-v<version>.apk`. A release signed with this key cannot install over the earlier debug-signed APK without first uninstalling that debug build, which deletes its local settings and history.

For manual testing, install only the app APK, open Accountability Check, read and accept the separate Accessibility disclosure, enable the service in Android Settings, choose one or more launchable apps under **Your apps**, and open a selected app. Some sideloaded builds may require **Allow restricted settings** in Android's app info first. Use **Pause** or Android Settings to disable the service whenever desired.

The app returns the user Home; it cannot terminate other apps, verify whether a task was finished, or enforce its rules while the service is disabled. Google Play publication has not been requested or attempted.
