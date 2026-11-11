# Branching Analysis: P1-E 35 (App Lock UI Gate, Security Settings & In-App Version/Update Info)

## Goal and Invariant
- **Goal:** Implement the App Lock UI gate (`BiometricPrompt` / device credential with relock timeout) and security settings (`privacy.app_lock`, `privacy.relock_timeout_sec`, `privacy.block_screenshots` / `FLAG_SECURE`) per Blueprint 16 §3.2 & Blueprint 52 §3.4, plus the in-app version, license, and update check affordance per Blueprint 92 §2 (closing Row 1 and Row 22 of Blueprint 90).
- **Invariant 1:** Background alarms and notifications must continue to fire while the app is locked; the database key must remain un-gated (D-030).
- **Invariant 2:** Zero vibration: biometric prompt and lock screen must never introduce vibration calls or vibrate permissions.
- **Invariant 3:** When `privacy.app_lock == "1"`, the app UI must never expose chat, reminders, or memory to an unauthenticated user on launch or after timeout.
- **Invariant 4:** Pure architecture: `:core` must remain free of `android.*` dependencies. All BiometricPrompt and WindowManager logic lives in `:app`.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|------------|------------|----------------|----------------|--------|
| H1 | Binding database decryption to biometric authentication would protect data better against cold extraction. | If DB is authentication-bound, scheduled alarms firing in the background while phone is locked will fail to decrypt storage, breaking all reminders. | Review D-030 and Blueprint 16 §3.2 | Background alarms fail under lock | Confirmed: D-030 strictly requires app lock to be a UI gate only, keeping SQLCipher storage accessible to background alarm receivers. |
| H2 | Using `androidx.biometric:biometric` requires adding a new dependency to `app/build.gradle.kts`. | Since `minSdk = 34` (Android 14), `android.hardware.biometrics.BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is natively available in the Android SDK. | Compile check with framework BiometricPrompt | Any compile error on API 34 | Confirmed: framework `BiometricPrompt` is available natively; zero new dependencies required. |
| H3 | When `FLAG_SECURE` is active, Compose screenshots in connected tests might fail or be blocked. | `FLAG_SECURE` is controlled by `privacy.block_screenshots`, which defaults to "0" (off), leaving automated test screenshots unobstructed. | Verify default setting in SettingsCatalog | Tests fail with FLAG_SECURE | Confirmed: defaults to "0". |
| H4 | Activity recreation on configuration changes (rotation/theme) could spuriously lock the app mid-usage. | Storing the unlock session state in `ChatController` / `UiState` preserves unlocked state across configuration changes, relocking only on background timeout. | Rotate screen while unlocked | Screen relocks immediately | Confirmed: session unlock state lives in controller/state flow. |

## Angles
- **data/schema:** No schema change; settings are stored in `app_meta` keys (`privacy.app_lock`, `privacy.relock_timeout_sec`, `privacy.block_screenshots`) via existing `Storage.getSetting`/`saveSetting`.
- **concurrency/lifecycle:** `onPause`/`onStop` records background timestamp; `onResume` checks elapsed time against `relockTimeoutSec` and sets `isAppLocked = true` if expired.
- **failure paths:** If device has no biometric or PIN set, `BiometricPrompt` falls back gracefully or informs the user; cancelled authentication leaves UI locked.
- **user-visible behaviour:** When locked, `LockScreen` replaces tabs with a clean lock graphic and "Unlock Mine" button.
- **privacy/security:** UI gate blocks shoulder surfing and borrowed phone access; `FLAG_SECURE` blocks recent apps thumbnail preview and screenshots.
- **testability:** Unit-testable in `:core:test` for state transitions (`UiAction.LockApp`, `UnlockApp`, `SetAppLock`, `SetRelockTimeout`); Compose-testable in `:app`.
- **blast radius:** UI shell (`MainActivity.kt`) and `ChatContract.kt` / `ChatController.kt`; non-UI background processes (receivers, services) untouched.
- **platform:** Runs natively on Android 14+ (`minSdk = 34`); Motorola moto g73 5G compatible.
- **model behaviour:** Unaffected; models never interact with lock state.

## Decision
1. In `core/.../ui/ChatContract.kt`:
   - Add `appLockEnabled`, `isAppLocked`, `relockTimeoutSec`, `blockScreenshots` to `UiState`.
   - Add `UiAction.UnlockApp`, `UiAction.LockApp`, `UiAction.SetAppLock(enabled)`, `UiAction.SetRelockTimeout(seconds)`, `UiAction.SetBlockScreenshots(enabled)`.
2. In `core/.../ui/ChatController.kt`:
   - Initialize `appLockEnabled` and `isAppLocked` from storage on start.
   - Handle lock/unlock actions and settings persistence.
3. In `app/.../MainActivity.kt`:
   - Add `LockScreen` composable.
   - Track `lastPauseTimeMillis` in activity lifecycle (`onStop`/`onResume`) and trigger `UiAction.LockApp` when elapsed >= timeout.
   - Implement `launchBiometricPrompt()` using `android.hardware.biometrics.BiometricPrompt`.
   - Apply `WindowManager.LayoutParams.FLAG_SECURE` dynamically based on `state.blockScreenshots`.
   - Add Security & About section in `DebugScreen` (or dedicated settings section) showing version name, version code, schema version, export format, license, and "Check for updates" link button to GitHub releases.

## Pre-mortem: 3 Potential Regressions & Mitigations
1. *Risk:* An alarm notification arrives while locked, and clicking it bypasses the lock screen directly into chat.
   *Mitigation:* `MainActivity.onCreate`/`onNewIntent` respects the lock gate: opening from a notification still displays `LockScreen` if `isAppLocked == true`.
2. *Risk:* Immediate lock timeout (0 seconds) causes an infinite unlock loop because launching BiometricPrompt pauses the activity.
   *Mitigation:* Track an in-progress authentication flag (`isAuthenticating`) so launching the biometric dialog does not trigger a re-lock.
3. *Risk:* Tests that instantiate `ChatController` fail because `isAppLocked` starts true.
   *Mitigation:* Default `privacy.app_lock` is "0" (disabled), so default state is `appLockEnabled = false`, `isAppLocked = false`.
