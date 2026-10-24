# Accountability Check 0.2.0 (prerelease)

Active intentions now have an ongoing notification showing what you wrote and a live countdown. Compatible phones can also show the timer as a Live Update in the status bar. Enable notifications from **Intention timer** on Today or Settings; Android controls Live Update promotion and lock-screen privacy. The setup card disappears from Today once notifications are enabled, with controls still available in Settings.

Each active intention has its own notification. Timers keep their original deadlines while switching apps or locking the phone. Notifications clear at expiry or when protection is paused, its mode changes, the selected app is removed, or the service disconnects. Dismissing a notification where Android permits it hides that notification without ending the session.

This release also adds Linux/Omarchy development setup and a Linux script for building signed, optimized release APKs.

Requires Android 10 or newer. Review and accept the Accessibility disclosure, enable the service in Android Settings, and select your apps. For sideloaded installs, Android may require **Allow restricted settings** before enabling Accessibility. Notification permission is optional; intention prompts continue without it.

**Signing key change:** The original Windows release key is unavailable. Version 0.2.0 uses a new dedicated release key. Android cannot update the old v0.1.0 release or a debug build with this APK. Uninstall the existing app before installing this release; this deletes its local settings and history. Future release updates must use the new release key.

Validation: all 30 release unit tests passed, lint passed with 19 warnings and no errors, and APK alignment and signature verification passed. Five focused notification checks also passed against the debug build on a Pixel 8a running Android 17, where Android confirmed Live Update promotion. The complete Accessibility smoke suite was not rerun for this release, and visual status-bar behavior across devices remains a manual check.

This remains an early voluntary aid and has not been submitted to Google Play. Interception is reactive, and returning Home does not stop background uploads or audio.
