# Accountability Check 0.1.0 (MVP prerelease)

This is the first signed APK release of the native Android MVP. Choose apps that should ask for an intention and time budget before use. At the deadline, the app reminds you of your intention and returns you Home. Settings also offers an experimental phone-unlock mode, a choice of whether locking ends a phone intention, and System, Light, or Dark appearance. Session history stays on the device.

Requires Android 10 or newer. Install the APK, open the app, review its Accessibility disclosure, enable Accountability Check in Android Accessibility settings, and choose your apps. Android may require **Allow restricted settings** in the app's system information before the Accessibility service can be enabled for a sideloaded APK.

**Existing debug installs:** This release uses a dedicated signing key. Android cannot install it over a debug-signed build. Uninstalling the debug build first deletes its locally stored settings and history; the app has no export or migration feature yet.

The app is an early, voluntary aid and has not been submitted to Google Play. App interception is reactive, so a chosen app may appear briefly before the prompt. Returning Home does not stop background uploads or audio.

APK SHA-256: `3465204D626B35F31350B2B2FE02AF8506F21A5E3A1B9E2B104618E0B9CFC8F2`
