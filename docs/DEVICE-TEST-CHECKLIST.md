# Device verification

This file is a reproducible test plan, not a claim that every device or scenario has passed. Record actual runs in `IMPLEMENTATION-STATUS.md` when available. Use an emulator or a dedicated test phone with synthetic intentions for automated testing.

## Install the debug fixture

From this repository, with a compatible Java toolchain (JDK 17 or 21 recommended), Android SDK 36, and an unlocked connected device:

```powershell
.\gradlew.bat :app:installDebug :test-target:installDebug
```

`Focus test app` is an offline, debug-only companion package (`nz.co.accountabilitycheck.testtarget`). It contains a text field, a second Activity, and a deep-link destination so tests do not need personal accounts or third-party apps. Its release variant is disabled. Do not include this fixture in product distribution.

Launch Accountability Check, accept the disclosure, enable its Accessibility service in Android Settings, turn protection on, choose selected-app mode, and select **Focus test app**. Set the device screen timeout to more than one minute for the static-screen cutoff test.

To enter a known destination directly:

```powershell
adb shell am start -W -a android.intent.action.VIEW -d accountability-test://destination/story -n nz.co.accountabilitycheck.testtarget/.MainActivity
```

The destination `accountability-test://destination/story` must still be visible after accepting the gate. This checks that the implementation uncovers the original screen instead of relaunching the target.

## Core acceptance checks

Record device manufacturer/model, Android version/API, security update, app commit/build, navigation mode, keyboard, date, and result for each run.

| Check | Steps | Expected result |
| --- | --- | --- |
| Initial gate | Launch the fixture without a session. Try tapping where its button would be. | One intention form appears; target interaction is blocked. |
| Input validation | Submit blank/whitespace intention, then invalid/blank duration, then valid intention and one minute. | Invalid submissions stay gated; valid submission reveals the existing target screen. |
| Keyboard | Type in the gate using the device keyboard; dismiss the keyboard, then use the form. | All controls remain reachable with no duplicate form or trapped keyboard. |
| Target keyboard | Start a session and type in the fixture text field. | No second gate; original deadline remains. |
| Same-package navigation | Tap **Open second screen**, then **Return to first screen** during a session. | Navigation works without another gate or deadline reset. |
| Return early | Start a one-minute session, go Home, then reopen the fixture before expiry. | Existing intention and deadline continue. |
| Cancel | Use the gate's Home/cancel action without starting. Reopen the fixture. | Device goes Home; re-entry is gated. |
| Static foreground expiry | Start a one-minute session and leave the fixture on screen without interaction. | At deadline, a reminder appears and Home is requested. The reminder stays visible until you tap **Dismiss**; it does not switch to a new intention while you read it. |
| Fresh re-entry | Reopen the fixture after expiry. | A fresh intention and duration are required. |
| Reverse the recent-apps gesture | With the form showing, swipe slowly up toward recent apps, then drag the same app back down. Repeat at different speeds. Enter a partial intention once the form returns. | The form returns whenever the selected app is interactive, appears once after the transition settles, and retains the draft without flashing. |
| Reopen from recent apps | Leave a chosen app, open the recent-apps view, and select its card. | One stable form appears and the app cannot be used without a session. |
| Expiry elsewhere | Start a one-minute session, then open Android Settings and stay there past deadline. | Settings remains usable and is not sent Home; reopening the fixture is gated. |
| Rapid transition | Around expiry, move between fixture, Home, Settings, and the app itself. | No stale navigation sends a different app Home. |
| Permission escape | Open Accessibility settings while a gate is visible and disable the service. | Settings remains accessible; overlay disappears; the app reports protection unavailable. |
| Screen off | Start a session and turn the screen off past its deadline, then unlock. | No overlay covers authentication. Returning to fixture requires a new session. |
| Service reconnect | Start a session, disable/re-enable the Accessibility service, reopen target. | Same-boot access never gains extra time. If no valid session remains, gate again. |
| Process recreation | Start a session, use Android's development process-kill control or kill the process without clearing app data, then reconnect service. | Stored deadline is preserved or conservatively invalidated; never reset to a fresh budget. |
| Reboot | Start a session, reboot, unlock, and reopen target. | Session from the previous boot is invalidated. |
| Wall clock | During a session, change wall-clock time in Settings, then return. | Deadline still follows elapsed time. Restore the test device clock afterwards. |
| History | Complete and interrupt sessions, then delete history. | Status and reasons are accurate; deletion removes history without granting extra access. |

## Intention notifications

Enable notifications using **Intention timer** on Today or Settings. On supported phones, allow **Live Updates** in the app's Android notification settings. Record notification and status-bar results separately: eligibility alone does not prove Android displayed a chip.

| Check | Expected result |
| --- | --- |
| Start an app or phone intention | Notification shows the app/phone label, the written intention, and a live countdown. Tap it to open Accountability Check. |
| Status bar | On a supported phone with Live Updates allowed, verify a timer chip appears and counts down without opening the shade. |
| Switch apps, return, or lock/wake | Countdown keeps the original deadline. Lock-screen details respect Android's privacy settings. End-on-lock still ends a phone-mode session when selected. |
| Multiple app sessions | Each intention has its own countdown. Expiring one removes only that notification. |
| Expire, pause, remove an app, change mode, or disable the service | Notifications for ended/unavailable sessions disappear. Reconnecting the service restores only still-valid, undismissed intentions. |
| Dismiss a notification where Android permits it | The timer keeps running. Window changes and process reconnection do not bring that notification back for the same session. |
| Deny or revoke notifications | Prompts and enforcement continue. Enabling notifications while a session is active shows its remaining time without restarting it. |
| Change the wall clock | Countdown continues to follow the original elapsed-time deadline. Restore the device clock afterwards. |

The five focused checks in `SessionNotificationTest` validate countdown data, wall-clock conversion, rejection of expired timers, Live Update eligibility, and Android's notification expiry. Unlike the Accessibility smoke suite, they do not change saved sessions or protection settings. Run them with the [README command](../README.md#linux--omarchy-development). Visual chip rendering and the manual cases above still need device verification.

## Phone-purpose mode (experimental)

Select the separate phone-purpose mode. Test with a protected fixture and an unselected harmless app. A protected-app fallback is required even if the device does not emit a reliable unlock signal.

| Check | Expected result |
| --- | --- |
| Wake/unlock with PIN/password | Prompt appears only after authentication, once per wake cycle. |
| Fingerprint, face, swipe, trusted state, and no-lock wake | Verify each supported configuration individually; record unsupported configurations. |
| Unlock directly into the fixture | One phone-purpose gate, no second per-app gate. |
| Switch apps during phone session | A single elapsed budget continues. |
| Phone deadline in a protected eligible app | Reminder and Home, followed by a fresh gate on protected re-entry. |
| Phone deadline already at Home | Reminder stays until **Dismiss**, without redundant navigation. |
| Deadline during Settings, call, emergency UI, or keyguard | No interruption or overlay trap; intervention is deferred. |
| Switch modes | Previous active sessions end as interrupted; no inherited access. |
| Lock/wake again with “End intention when I lock” off | The same phone session and original deadline remain; no new form before the deadline. |
| Lock/wake again with “End intention when I lock” on | Prior phone session ends; a new post-unlock decision is required. |
| Back/pause from unlock form | The phone form has no Go Home button. Back allows Home temporarily, and the next eligible app asks again; Pause disables prompts. |

Real fingerprint/face hardware, incoming calls, OEM power restrictions, work profiles, private space, cloned apps, split-screen, PiP, and other Accessibility services need separate physical-device testing. Passing the emulator smoke suite does not establish support for those cases.

## Timing and reliability evidence

Measure launch-to-overlay delay for 30 entries and deadline-to-cutoff for a static screen. Targets from the plan are at least 95% of entries within 500 ms and screen-on cutoff within one second. These are engineering targets, not guarantees. Record failures, missed prompts, duplicate forms, unrelated-screen navigation, and battery behavior during normal use. Do not report timing targets as achieved without measurements.

## Automation note

Run the 11 instrumentation smoke tests on a dedicated emulator. On Linux, use the explicit installation and instrumentation commands in the [README](../README.md#linux--omarchy-development). On Windows, the script selects the serial, installs all three APKs, and reports the result:

```powershell
.\scripts\device-test.ps1 -Serial emulator-5556
```

When several devices are attached, set `ANDROID_SERIAL` to the dedicated emulator serial before installing or running the tests. The tests accept the disclosure, change protection mode/app selection, enable the Accessibility service through test-only shell privileges, exercise the real overlay, then restore the previous service and selection settings. Existing active sessions are interrupted and synthetic completed records remain. Consent stays accepted. Use a dedicated test device; these tests are not appropriate for a personal phone with active sessions. The timeout test waits for a real one-minute deadline; it has no shortened production timer.

Automated assertions cover invalid/valid input, preserved deep-link destination, same-package navigation, returning before expiry without a new budget, keyboard dismissal retaining the draft, static-screen expiry/Home, gated re-entry, cancellation, Settings escape and safe expiry, wake into a selected app, post-wake phone prompt, and service reconnection. They do not substitute for the manual keyboard, secure authentication, power-management, or OEM matrix above.

Android's UI automation normally suppresses Accessibility services. Tests of this feature must set `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` through UI Automator's `Configurator` before obtaining the device and use the same flag for `Instrumentation.getUiAutomation(flags)`. Otherwise a passing or failing test may be exercising an artificially disconnected service. See [Android UiAutomation](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) and [UI Automator Configurator](https://developer.android.com/reference/androidx/test/uiautomator/Configurator#setUiAutomationFlags(int)).

On a personal phone, avoid `adb shell uiautomator dump` during the live gate test: it can temporarily suppress the Accessibility service and reset the overlay. Use direct screen capture and read-only system state checks, or the configured instrumentation runner on a dedicated test device.
