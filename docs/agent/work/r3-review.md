# Independent Review: Task R3 (Alarm truth, permission and silent failures)

Date: 2026-11-03  
Reviewer: Independent Reviewer Agent  
Verdict: APPROVE (after addressing MainViewModel sweeper intent extras)

---

### 1. Guarantee Restatement
Guarantee exact alarm delivery on Android 12+ without declaring `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` permissions via `setAlarmClock`, while eliminating silent scheduling failures through explicit `ScheduleResult` handling. Deduplicate alarm reconciliation into a shared core `AlarmReconciler` that selectively re-arms only modified reminders during UI refreshes, labels swept missed reminders, and restores automated `AlarmSelfTest` verification.

---

### 2. Files Inspected & Full git diff Verification
The following files were inspected in full alongside all callers and dependencies:
- `app/src/main/AndroidManifest.xml`
- `core/src/main/java/com/anchor/core/alarms/AlarmScheduler.kt`
- `app/src/main/java/com/anchor/mine/alarms/AndroidAlarmScheduler.kt`
- `core/src/main/java/com/anchor/core/alarms/AlarmReconciler.kt`
- `core/src/main/java/com/anchor/core/ui/ChatController.kt`
- `core/src/main/java/com/anchor/core/ui/ChatContract.kt`
- `core/src/main/java/com/anchor/core/models/Stats.kt`
- `app/src/main/java/com/anchor/mine/alarms/BootReceiver.kt`
- `app/src/main/java/com/anchor/mine/alarms/AlarmReceiver.kt`
- `app/src/main/java/com/anchor/mine/MainViewModel.kt`
- `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`
- `app/src/androidTest/java/com/anchor/mine/alarms/ManifestPermissionTest.kt`
- `app/src/androidTest/java/com/anchor/mine/alarms/AlarmSelfTest.kt`
- `app/src/androidTest/java/com/anchor/mine/alarms/AlarmReceiverTest.kt`
- `app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt`
- `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt`
- `core/src/test/java/com/anchor/core/alarms/AlarmReconcilerTest.kt`
- `core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt`

---

### 3. Bug Hunt Along 10 Axes
- **data/schema/migration**: None found, because table `reliability_state` already defined `issues_json TEXT` in schema v1-v5 (`MineDatabaseHelper.kt:37`), and `AndroidStorage.kt` maps column index 2 in `getReliabilityState()` and binds it in `saveReliabilityState()`.
- **concurrency and cancellation**: None found, because `AlarmReconciler.reconcile` runs in `goAsync()` on `Dispatchers.IO` in `BootReceiver` and on `defaultDispatcher` in `ChatController`, operating on immutable snapshots from `storage.getPendingReminders()` and `storage.getRegisteredAlarms()`.
- **process death and reboot**: Resolved in both `BootReceiver` and `MainViewModel`. `BootReceiver` handles `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, and `TIMEZONE_CHANGED` with `reconcile(force = true)` and forwards sweeper missed reminders; `MainViewModel` forwards `EXTRA_MISSED = true` and `EXTRA_TIER`.
- **failure paths (network, 429, malformed model output, null args)**: None found, because `AndroidAlarmScheduler.schedule` catches `Throwable` and returns `ScheduleResult.Failed`, logging score `"at_risk"` in `reliability_state` and exposing `UiState.schedulingError`, while `AlarmReceiver` falls back to blind notifications if storage fails.
- **idempotency (runs twice, already-closed item)**: None found, because `reconcile(force = false)` skips alarms when registry fire times match DB fire times, orphans are purged on each pass, and `AlarmReceiver` deletes the reminder row upon posting.
- **non-Latin text**: None found, because `Reminder.text` and notification extras preserve full UTF-8 strings.
- **main-thread work**: None found, because alarm scheduling, notification posts, and storage operations are offloaded to `Dispatchers.IO` or background coroutine dispatchers.
- **alarm and notification correctness**: None found, because `ALARM` tier reminders use `setAlarmClock` waking from Doze without requiring exact-alarm permissions, and `GENTLE` tier reminders use `setAndAllowWhileIdle` without registering in `alarmManager.nextAlarmClock`.
- **privacy (secrets in logs, local-only data)**: None found, because alarm scheduling and reliability status operate strictly on local SQLite and Android `AlarmManager` with no network egress.
- **blast radius (what else uses this code)**: None found, because all test fakes and call sites were updated to conform to `AlarmScheduler.schedule(): ScheduleResult`.

---

### 4. Project Rules Check
- **LLM proposes / code decides**: Upheld; alarm times and registration are evaluated strictly in deterministic code.
- **Nothing hard-deleted**: Upheld; user task nodes remain soft-deleted or marked closed.
- **Dates resolved in code**: Upheld; epoch millisecond timestamps are resolved deterministically before scheduling.
- **Append-only raw tables**: Upheld; raw messages and events are unaffected.
- **SPDX headers**: Upheld; present in all newly introduced and modified files.
- **No vibration**: Upheld; `android.permission.VIBRATE` is absent and notification channels do not enable vibration.
- **No new dependencies**: Upheld; no changes made to `build.gradle.kts`.

---

### 5. Addressed Items
1. **`MainViewModel.kt`**: Added `putExtra("EXTRA_MISSED", true)` and `putExtra("EXTRA_TIER", missedRem.tier.name)` to the sweeper intent.
2. **`ChatController.kt`**: Set `_state.update { it.copy(schedulingError = failure?.reason) }` so `schedulingError` is cleared when a subsequent reconciliation succeeds.
3. **`AlarmReconciler.kt`**: Hardened JSON string escaping for `\`, `"`, `\n`, `\r`, `\t`.
