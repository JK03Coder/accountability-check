# Android technical research

Checked: 25 September 2026. These findings use Android and Google Play primary sources. Proposed architecture and product choices are identified as recommendations; nothing here has been experimentally validated on a phone yet.

## 1. Feasibility and the enforcement boundary

The proposed consumer app is technically plausible without root. A user-enabled AccessibilityService can receive UI events, draw an accessibility overlay, and request the Home global action. This supports the intended interruption-and-timer experience. The service is enabled through Android Settings and its lifecycle is managed by Android. [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

This is reactive interception: Android has already begun showing the target app when its event is observed. A brief glimpse or interaction race is possible. There is no ordinary consumer API here that vetoes every app launch before it happens. This is an architectural consequence of the event-driven approach, not a measured latency claim.

Returning Home does not terminate the target process, cancel uploads, or reliably stop audio or picture-in-picture. Android 14+ restricts `killBackgroundProcesses()` to the caller's own app. Do not present this feature as force-stopping Instagram. [Android 14 changes](https://developer.android.com/about/versions/14/behavior-changes-all#kill-background-processes)

Actual package suspension is available to device/profile owners or appropriately delegated enterprise apps. Those privileges do not come with a normal consumer installation. Managed-device provisioning is a different product route. [DevicePolicyManager.setPackagesSuspended](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPackagesSuspended(android.content.ComponentName,%20java.lang.String[],%20boolean))

## 2. Detecting app entry with minimal data

`AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` includes a package name and event timestamp. It describes window/pane changes, not exclusively app launches. Repeated events must be classified and deduplicated; IME, System UI, permission dialogs, our own overlay, and in-app navigation must not each create a new session. [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)

The prototype initially requested only window-state events and set `canRetrieveWindowContent=false`. Emulator testing exposed an out-of-order launcher event: the target remained visibly foreground, but the service's last window callback named Home, so it safely deferred the deadline action. The implementation now declares `canRetrieveWindowContent=true` and reads only `rootInActiveWindow.packageName` to confirm the foreground app at a deadline, wake, or service connection. It does not traverse the node tree, read text, capture screenshots, perform gestures, or inspect keystrokes. The in-app disclosure describes both Android's broader capability and the app's narrower use. This change still needs verification on physical devices. [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow())

**Important constraint:** `TYPE_WINDOWS_CHANGED` and `getWindows()` require `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, which in turn requires the window-content capability. The implementation does not request that flag or enumerate windows. If single-root confirmation also proves insufficient on physical devices, evaluate this broader capability and update the disclosure before using it. [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS)

Event metadata is an inference about foreground ownership. `GLOBAL_ACTION_HOME` has no package-conditional atomic variant. The design must suppress automated navigation when foreground ownership is uncertain; this trades a possible missed intervention for avoiding dismissal of an unrelated app.

Android 16 also protects sensitive views from services that are not legitimate accessibility tools. Do not set `isAccessibilityTool=true` to circumvent this. Test transitions involving login/payment screens and record unavailable observations rather than assuming uniform access. The MVP does not need to inspect those views. [Android sensitive accessibility data](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)

## 3. Showing the intention form

`TYPE_ACCESSIBILITY_OVERLAY` is a window type reserved for a connected accessibility service and can intercept touches above an app. Use a clearly branded, opaque, focusable form. Keyboard focus, insets, Back handling, rotation, and coexistence with other accessibility tools require a real-device proof. [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY)

Recommendation: make this overlay the primary gate. Successful submission removes it and reveals the original app screen, preserving notification/deep-link destinations without restarting the target. A dedicated Activity remains a contingency only if tested successfully.

Android restricts background Activity launches. Neither an unlock broadcast nor a generic foreground service grants unrestricted launch rights. Current documentation describes specific exceptions and newer PendingIntent opt-ins. An Activity-based gate should not be assumed to work on every target release. [Activity security](https://developer.android.com/guide/components/activities/secure-bal)

## 4. Session time and recovery

Use `SystemClock.elapsedRealtime()` for interval deadlines: it is monotonic and includes sleep. Wall-clock time can change. Handler scheduling uses uptime, so a callback is a wake-up hint; compare the persisted deadline with elapsed time whenever execution resumes. [SystemClock](https://developer.android.com/reference/android/os/SystemClock)

Recommendation: persist accepted sessions before removing the gate; reconcile deadlines on events, service reconnection, and wake/unlock. While connected and the screen is active, schedule the nearest deadline. If time expires with the screen off or the target absent, expire the session without navigating an unrelated screen. Enforce on the next relevant foreground observation.

WorkManager and inexact alarms are inappropriate as the sole mechanism for a short, precise, interactive cutoff. Exact alarms introduce special access and policy considerations. They are not necessary for the first design because it does not promise an exact wake-up notification during sleep. A separate foreground service is also not assumed necessary. Add either only after a measured requirement and an appropriate API/policy review. [Alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)

No in-process design guarantees enforcement while the service is disconnected or the package is force-stopped. Reboot resets elapsed time; active sessions should be invalidated and require a new intention rather than accidentally receive a fresh full budget.

## 5. Unlock prompting

`ACTION_USER_PRESENT` indicates presence after wake/keyguard dismissal. Runtime-register it with `ACTION_SCREEN_ON` and `ACTION_SCREEN_OFF` while the service is connected. `ACTION_USER_UNLOCKED` refers to credential-encrypted storage becoming available; it is not a broadcast for every phone unlock. [Intent](https://developer.android.com/reference/android/content/Intent#ACTION_USER_PRESENT)

Check that the device is interactive and that `KeyguardManager.isKeyguardLocked()` is false before displaying a form. The latter checks whether keyguard is showing, including a dismissible swipe screen. Newer keyguard listeners require privileged/role-restricted access; do not assume an ordinary productivity app can use them. [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager), [Restricted listener permission](https://developer.android.com/reference/android/Manifest.permission#SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)

Runtime receivers depend on a live registration. Android can defer broadcasts for cached processes, so these signals are not an unconditional interception guarantee. Reconcile current state on service connection and relevant events. [Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)

Recommendation: implement post-unlock prompting as an opt-in, device-validated mode after app gating works. Never replace or obscure Android authentication. Test fingerprint, PIN, face, swipe, no-lock, trusted-state unlock, notification entry, and rapid screen toggles. A no-lock device should describe the feature as prompting after wake.

## 6. App picker and permissions

Discover ordinary launchable apps using `PackageManager.queryIntentActivities()` with `ACTION_MAIN` and `CATEGORY_LAUNCHER`, with an appropriate scoped manifest `<queries>` declaration. This is sufficient for the proposed app picker; test visibility on target devices. Exclude essential/system flows. [Intent matching](https://developer.android.com/guide/components/intents-filters), [Package visibility](https://developer.android.com/training/package-visibility/declaring)

Avoid `QUERY_ALL_PACKAGES`: Play disallows broad visibility when a narrower approach meets the use case. Installed-app inventory is sensitive and should serve app selection only. [Package visibility policy](https://support.google.com/googleplay/android-developer/answer/10158779?hl=en)

The starting design needs accessibility enablement and optionally notification permission for session reminders. An accessibility overlay does not require a separate application-overlay permission. Do not request Usage Access, Device Admin, exact-alarm access, or `SYSTEM_ALERT_WINDOW` speculatively.

### Active intention countdowns

Session notifications use Android's native chronometer in countdown mode, with the wall-time target derived from the remaining elapsed-time budget. The application refreshes that conversion on wall-clock changes; enforcement continues to use the original elapsed deadline. `setTimeoutAfter` removes the notification at the remaining deadline without a per-second application update loop. [Notification builder](https://developer.android.com/reference/android/app/Notification.Builder)

The notification uses a standard BigText style, is ongoing, and requests Live Update promotion with `POST_PROMOTED_NOTIFICATIONS`. Android decides whether to show the status-bar chip, subject to the user's settings and device support. Dismissal is recorded per session so ordinary state changes do not repost a dismissed Live Update. AndroidX Core 1.17 provides the promotion request API while retaining compile SDK 36. [Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update), [Core 1.17](https://developer.android.com/jetpack/androidx/releases/core#1.17.0)

On Android 13+, posting also requires runtime `POST_NOTIFICATIONS` permission. The app requests it only from its notification control, and protection continues if permission is denied. Intention notifications use private visibility so lock-screen content follows Android's privacy settings. [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

Some Android 13+ installation paths restrict accessibility enablement until the user explicitly allows restricted settings. Account for that during APK testing and onboarding. [Android restricted settings](https://support.google.com/android/answer/12623953?hl=en)

## 7. Google Play route

Play allows non-disability uses of AccessibilityService and deterministic rules defined by people. Our inference is that a user-selected app and deadline leading to a reminder/Home action fits that permitted automation category. This is not a guarantee of approval.

Use `isAccessibilityTool=false` or leave it unset for this general productivity product. Provide a separate prominent disclosure with affirmative consent before opening accessibility settings. Prepare the Accessibility declaration and demonstration video, including permission refusal and the actual feature. [Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)

The service must not prevent disabling or uninstalling it. Keep Settings, permission management, and a pause/disable route available. Explain accessibility use in the listing and use narrower APIs where they actually satisfy the need. [Sensitive APIs policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)

As checked on 25 September 2026, new mobile apps and updates submitted to Play must target Android 16 / API 36 or higher; the requirement began 31 August 2026. Target SDK and minimum supported Android version are separate. Recheck before submission. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)

## 8. Alternatives considered

| Approach | What it contributes | Why it is not the starting architecture |
| --- | --- | --- |
| UsageStatsManager | Query recorded app-use events with user-granted access | Query/polling does not itself provide a launch veto, UI gate, or Home action. Consider for later statistics, not the enforcement core. |
| Custom launcher | Prompt before launches initiated through our launcher | Notifications, links, Recents, and other entry routes remain; it changes the user's home-screen experience. |
| Application overlay + polling service | Another possible interruption mechanism | Adds permissions, polling, and background-service constraints. |
| VPN | Controls network traffic | Does not stop opening an app or using cached/offline content. |
| Device owner / root | Stronger device control | Different distribution/setup model and unnecessary for a voluntary consumer MVP. |

These comparisons are engineering conclusions from the API boundaries above. Usage Stats is a query API requiring appropriate user access; it is not an app-launch interception API. [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)

The MVP can remind users of their stated intention and time. It cannot establish whether they actually uploaded a story without additional app-specific observation or integration, which is outside this scope.
