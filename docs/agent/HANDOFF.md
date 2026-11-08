# HANDOFF (v1 row 19 quick capture shipped and device-verified — full suite 89/89)

## Current Work & Status
- **v1 row 19 (quick capture) is complete and green on the device**, uncommitted as of this handoff.
  The audit rated the row **ABSENT** (`docs/agent/work/audit-2026-11-08-v1-scope.md:30`); it is now
  BUILT with the audit's own stated proof passing: *an `RemoteInput` action is present on the alarm
  notification*.
- **The commit is the only thing outstanding.** Per the one-change-per-commit rule this is a single
  logical change (row 19), so code + its docs go in one commit.
- **The device is reachable** — `moto g73 5G - 14` (Android 14 / API 34).
- **The first device run of a new test remains the only proof it works.** `QuickCaptureDeviceTest`
  compiled clean and failed on device 5/5 times; the cause was the test, not the receiver (§6).

## 1. What Changed (all uncommitted)

### Core
- **`core/.../capture/QuickCapture.kt`** (new) — the single write path. `store(storage, text): String?`
  trims, rejects blank, writes a `role="user"` row with id `q_<uuid4>`. `isUnanswered(storage, id)` is
  true until a non-blank assistant message follows. `pending(storage)` returns unanswered `q_` rows
  oldest-first — the E-17 restart retry. `isCaptureNotificationEnabled` (true only when the setting is
  exactly `"true"`) and `isShortcutEnabled` (true unless exactly `"false"`) — **new this segment**;
  before them all four capture constants were read by nothing.
- **`core/.../ui/ChatController.kt`** — new `fun sendText(text: String, userMsgId: String? = null)`:
  sets loading, reads `key`/`ep`/`mod` from storage (a capture can be the first thing after a cold
  start), calls `QuickCapture.notifyCaptureFailed` on a blank key, then `handleSendMessage`.
  `handleSendMessage` gained `existingUserMsgId` and skips its own insert when the row is already
  stored, so the `q_` row is never duplicated.
- **`core/src/test/.../capture/QuickCaptureTest.kt`** (new) — 11 cases; the last two pin the setting
  defaults.

### App
- **`app/.../capture/CaptureNotifications.kt`** (new) — `RESULT_KEY`, `replyAction(context, id)`
  (`FLAG_UPDATE_CURRENT or FLAG_MUTABLE`, **request code = notification id**), `showSending`
  ("Sending…"), `showFailed` ("Saved — will send when you next open Mine.").
- **`app/.../capture/QuickCaptureReceiver.kt`** (new) — stores **first**, then "Sending…", then the
  turn; `goAsync()` so the process is not killed mid-turn; `as? MineApp ?: return@launch` per R-55.
- **`app/.../alarms/AlarmReceiver.kt`** — `.addAction(CaptureNotifications.replyAction(...))` on both
  the normal notification and the storage-failure fallback.
- **`app/.../MineApp.kt`** — `@Volatile var captureController`, `captureNotificationIds`, and the
  `QuickCapture.onCaptureFailed` hook.
- **`app/.../MainViewModel.kt`** — publishes the controller and retries `QuickCapture.pending()`.
- **`app/.../MainActivity.kt`** — `onNewIntent` → `handleShortcutIntent`, which bumps a counter (not a
  boolean, so two taps both focus) and honours `capture.shortcut`. Needed
  `import kotlinx.coroutines.launch`.
- **`res/xml/shortcuts.xml`**, `res/drawable/ic_capture_reply.xml`, `AndroidManifest.xml`
  (shortcut meta-data + receiver registration), `strings.xml`.
- **`app/src/androidTest/.../capture/QuickCaptureDeviceTest.kt`** (new) — the row's proof, 3 cases.

### Docs
- **`DECISIONS.md`** — **D-132** (`q_` prefix instead of a `raw_message.tags` column; no schema bump)
  and **D-133** (setting defaults; the manifest shortcut cannot be unregistered so the switch is
  honoured in `handleShortcutIntent`). Next free: **D-134**.
- **`TASKS.md`** — `[x] V1-G3`.
- **`STATE.md`**, **`LEARNINGS.md`**, this file.

## 2. Verified (command + exit code)
| What | Command | Result |
|---|---|---|
| Core unit | `./gradlew :core:test` | exit 0, **316 / 0 failed / 0 skipped** |
| App unit | `./gradlew :app:testDebugUnitTest` | exit 0, **13 / 0 failed / 0 skipped** |
| androidTest compiles | `./gradlew :app:compileDebugAndroidTestKotlin` | exit 0 |
| Full device suite | `./gradlew :app:connectedDebugAndroidTest` then `:app:connectedDebugAndroidTestVerify` | `CONNECTED GATE: PASS - 89 test(s), 0 failed, 0 skipped (source: xml)`; gate `BUILD SUCCESSFUL in 6s`, exit 0 |
| Row 19 proof | `QuickCaptureDeviceTest` (inside the 89) | 3/3 — `alarmNotification_CarriesInlineReplyAction`, `reply_StoresTheTextAsAnUnansweredCapture_Immediately`, `launcherShortcut_IsRegistered` |

The AGP task's own exit 1 is the known D-123 defect; the gate is the authoritative verdict.

## 3. NOT Verified
- **The optional persistent capture notification is not posted.** Only its off-by-default switch
  exists (`isCaptureNotificationEnabled`, read by nothing in production yet). Recorded in D-133 and
  in the TASKS V1-G3 "still open" note.
- **`TAG_QUICK_REPLY` is defined and unread** (D-132) — nothing writes a `quick_reply` tag.
- **The reply path was proven with a forged broadcast, not a human tapping Reply** on a real
  notification. The forged intent uses the platform's own `addResultsToIntent` shape, so it is the
  same bytes the system writes, but the SystemUI surface itself was not exercised.
- **No manual swipe-away / process-kill test** of the "store first, then send" ordering. The unit
  tests pin `store` + `pending`; the crash-window claim is a code-reading argument.

## 4. Current failing tests
None. `:core:test` 316/0, `:app:testDebugUnitTest` 13/0, connected gate 89/0.

## 5. Open decisions needing you
- **Next slice:** rows 20 (memory viewer/editor, Trash and Erase) and 21 (onboarding, settings
  catalog, Quick setup) — the remaining UI trio rows, in that order.
- **D-105 by-eye verdict** is still yours (unchanged from the previous handoff).
- **Should the capture notification actually be posted?** Row 19 lists it as "optional"; the switch
  exists but nothing reads it. Posting it is a small follow-up, not a blocker.

## 6. Traps for the next run
- **`RemoteInput.getResultsFromIntent` reads exactly one `ClipData` shape** — mime
  `text/vnd.android.intent`, label `android.remoteinput.results`. A `newPlainText` ClipData is
  silently rejected and the receiver's `?: return` drops the reply with no log. In tests always use
  `RemoteInput.addResultsToIntent(...)`. Full detail in LEARNINGS 2026-11-08.
- **"Never invoked" and "dropped the payload" are indistinguishable from outside.** Check the
  receiver's null guard before blaming registration.
- **`connectedDebugAndroidTest` uninstalls the test APK**, so a follow-up `am instrument` needs a
  reinstall of both APKs.
- **A package left `stopped=true` silently drops explicit broadcasts** until the activity is
  launched once.
- **Reply `PendingIntent` request code must be the notification id** — request code 0 is shared, and
  two armed reminders would overwrite each other's extras.
- **`ShortcutInfo.getIntents()` is nullable.**
- **Kotlin extension functions need their own import** — `lifecycleScope` does not bring
  `kotlinx.coroutines.launch` into scope.
- **`git checkout -- <file>` is destructive to uncommitted work** (previous session's lesson, still
  true — everything above is uncommitted right now).

## 7. Exact next step
Commit row 19 (code + docs, one change), then start **row 20**:
```
git add app/src/main/java/com/anchor/mine/capture app/src/main/res/xml/shortcuts.xml \
        app/src/main/res/drawable/ic_capture_reply.xml \
        app/src/androidTest/java/com/anchor/mine/capture \
        core/src/main/java/com/anchor/core/capture core/src/test/java/com/anchor/core/capture \
        app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml \
        app/src/main/java/com/anchor/mine/MainActivity.kt \
        app/src/main/java/com/anchor/mine/MainViewModel.kt \
        app/src/main/java/com/anchor/mine/MineApp.kt \
        app/src/main/java/com/anchor/mine/alarms/AlarmReceiver.kt \
        core/src/main/java/com/anchor/core/ui/ChatController.kt \
        docs/agent/DECISIONS.md docs/agent/TASKS.md docs/agent/STATE.md \
        docs/agent/LEARNINGS.md docs/agent/HANDOFF.md
```
Then re-read `docs/agent/work/audit-2026-11-08-v1-scope.md` §6 row 6 for row 20's stated proof.
