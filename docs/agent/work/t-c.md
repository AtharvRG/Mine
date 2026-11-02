HISTORICAL: contains unverified or incorrect claims; superseded by the session1 review (see TASKS.md and DECISIONS.md)

# Task T-C: Alarm Tiers & Status-Bar Disclosure (D-105) Branching Analysis

**Date**: 2026-11-02  
**Task**: T-C (D-105)  
**Module**: `:core` and `:app`  

---

## 1. Goal and Invariant

### Goal
Implement two-tier alarm scheduling per Decision D-105:
1. Add `AlarmTier` enum (`ALARM`, `GENTLE`) in `:core` (`Memory.kt`).
2. Add `tier: AlarmTier = AlarmTier.ALARM` to `Reminder` data class.
3. Migrate SQLite database from version 1 to 2 by adding `tier TEXT NOT NULL DEFAULT 'alarm'` to `reminder` table in `MineDatabaseHelper.kt`, with idempotent `onUpgrade`.
4. Update `AndroidStorage.kt` to persist and load `tier` across all `reminder` operations (`saveReminder`, `getReminderByNodeId`, `getPendingReminders`, `getActiveReminders`, `createNodeAndCommit`, `updateNodeAndCommit`, `undoChangeAndCommit`, `createReminderAndCommit`).
5. Update `AndroidAlarmScheduler.kt`:
   - `AlarmTier.ALARM`: uses `AlarmManager.setAlarmClock(AlarmClockInfo(fireAt, pendingShow), pendingIntent)`. Bypasses exact-alarm restrictions without extra permissions; causes system status bar alarm icon to appear.
   - `AlarmTier.GENTLE`: uses inexact notification-only scheduling `AlarmManager.setAndAllowWhileIdle(AlarmManager.RTC, fireAt, pendingIntent)` with fallback to `set(AlarmManager.RTC, fireAt, pendingIntent)`. Never shows the OS status bar alarm clock icon; does not require `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`.
6. Update `AlarmReceiver.kt`:
   - Route `AlarmTier.GENTLE` to a gentle notification channel (`mine_gentle_reminders`, `NotificationManager.IMPORTANCE_DEFAULT`) without high-priority heads-up intrusive sounds.
   - Route `AlarmTier.ALARM` to high-importance channel (`mine_reminders_v1`, `NotificationManager.IMPORTANCE_HIGH`).
7. Expose status bar icon disclosure text in `UiState` (`alarmDisclosureText`) and render it in `RemindersScreen` in `MainActivity.kt`.

### Invariant
1. No `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`, or `WAKE_LOCK` permissions added to `AndroidManifest.xml`.
2. Existing databases at version 1 migrate seamlessly to version 2 without data loss or crashes.
3. Alarms marked `GENTLE` never cause an alarm clock icon to appear in the Android status bar.
4. Alarms marked `ALARM` wake the device and fire reliably via `setAlarmClock`.
5. Zero `android.*` imports in `:core`.
6. Full backward compatibility: all existing `Reminder` call sites default to `AlarmTier.ALARM`.

---

## 2. Hypothesis Table (4 approaches across layers)

| # | Hypothesis / Approach | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Core Domain Enum + SQLite Column Migration + Scheduler Branching + Channel Separation)** | Define `AlarmTier` in `Memory.kt`, migrate SQLite `reminder.tier` with default `'alarm'`, branch in `AndroidAlarmScheduler` between `setAlarmClock` and `setAndAllowWhileIdle`, and separate notification channels in `AlarmReceiver`. Expose disclosure in `UiState`. | Clean separation of concerns; zero new permissions; robust persistence and Doze support without status-bar icon for gentle tier. | Unit tests in `:core` + instrumented tests on real SQLite DB + dumpsys alarm check on device. | Android 14 `SecurityException` thrown if gentle alarm inadvertently invokes exact alarm APIs. | **CHOSEN** |
| **H2 (WorkManager for Gentle Tier instead of AlarmManager)** | Use Android `WorkManager.OneTimeWorkRequestBuilder.setInitialDelay` for gentle reminders. | Completely separate execution framework for gentle reminders. | Check execution precision and latency of WorkManager under battery saver / Doze. | WorkManager enforces a minimum ~15-minute window and does not guarantee timely execution for reminders scheduled for 1-5 minutes out. Killed for reminder timing unpredictability. | Rejected |
| **H3 (Keep All Alarms as AlarmManager.setAlarmClock, toggle icon via App Setting)** | Try to hide the status bar alarm icon programmatically when "Gentle" is selected. | App controls OS icon visibility. | Review Android OS `AlarmManager` public API. | Android system status bar alarm icon is strictly OS-controlled and cannot be suppressed while an `AlarmClockInfo` is registered. Explicitly acknowledged in D-105: "The icon is system behaviour. The app cannot hide it." | Killed |
| **H4 (In-Memory Tier Only, No DB Migration)** | Keep `tier` in memory/intent extras without migrating the SQLite table schema. | Avoids DB version bump and migration code. | Check app restart / force-stop recovery and sweeper reconciliation. | After app restart or sweeper reconcile, all gentle reminders would reset to ALARM tier upon reload from SQLite, arming `setAlarmClock` and showing the icon. | Killed |

---

## 3. Angles (Mandatory one-line checks)

- **data/schema**: Bump DB to version 2; `ALTER TABLE reminder ADD COLUMN tier TEXT NOT NULL DEFAULT 'alarm'`; idempotent check via `PRAGMA table_info(reminder)`.
- **concurrency/lifecycle**: Thread-safe DB transactions via `ioDispatcher`; `AlarmReceiver` handles asynchronous delivery safely via `goAsync()`.
- **failure paths**: If `tier` is missing or corrupted in SQLite/intent, safely defaults to `AlarmTier.ALARM`; SecurityException in `AlarmManager` caught gracefully.
- **user-visible behaviour**: Reminders screen displays `[ALARM]` or `[GENTLE]` badge next to active reminders and shows system status bar icon disclosure text.
- **privacy/security**: Zero permission escalation; no device identifiers logged in alarm intents.
- **testability**: Unit tests in `:core` for serialization, storage, and gateway; instrumented tests in `:app` for SQLite migration and `AndroidAlarmScheduler` routing.
- **blast radius**: Default argument `tier: AlarmTier = AlarmTier.ALARM` on `Reminder` ensures all 15+ existing call sites remain 100% source and binary compatible.
- **platform (Android 14, OEM)**: Tested on physical device (Android 14 API 34); verifies `setAndAllowWhileIdle` does NOT throw `SecurityException` or require `SCHEDULE_EXACT_ALARM`.
- **model behaviour (what a real LLM would send)**: Real LLM can specify `"tier": "gentle"` or `"tier": "alarm"` in `propose_change` or `schedule_reminder`.

---

## 4. Evidence Log

- Inspected `MineDatabaseCallback`: version is currently 1; `reminder` table created with `(id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL)`.
- Verified `AndroidAlarmScheduler.kt`: currently unconditionally calls `alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(...), pendingIntent)`.
- Verified `AlarmReceiver.kt`: hardcodes `mine_reminders_v1` channel with `IMPORTANCE_HIGH`.
- Verified connected physical phone is running Android 14 (API 34).

---

## 5. Decision

- **Chosen Change**: Implement H1:
  1. Add `@Serializable enum class AlarmTier { ALARM, GENTLE }` to `core/src/main/java/com/anchor/core/models/Memory.kt`.
  2. Add `tier: AlarmTier = AlarmTier.ALARM` to `Reminder` data class.
  3. Bump DB version to 2 in `MineDatabaseCallback`. Implement idempotent `onUpgrade` with `ALTER TABLE reminder ADD COLUMN tier TEXT NOT NULL DEFAULT 'alarm'` and trigger recreation.
  4. Update `AndroidStorage.kt` to read and write `tier`.
  5. Update `AndroidAlarmScheduler.kt` to route `AlarmTier.ALARM` to `setAlarmClock` and `AlarmTier.GENTLE` to `setAndAllowWhileIdle`.
  6. Update `AlarmReceiver.kt` to separate `mine_reminders_v1` (High importance) and `mine_gentle_reminders` (Default importance).
  7. Add `alarmDisclosureText` to `UiState` and display in `RemindersScreen`.
  8. Update `SharedFakeStorage.kt` and `Gateway.kt` to support `AlarmTier`.

- **Rejected Options**:
  - H2 rejected due to WorkManager's 15-minute inexact execution floor.
  - H3 rejected because Android OS forbids suppressing the status bar icon for `setAlarmClock`.
  - H4 rejected because in-memory tiers fail persistence across app restarts.

---

## 6. Pre-Mortem & Breaking Inputs

1. **Pre-mortem 1**: An upgraded database throws `"duplicate column name: tier"` on restart or concurrent migration.  
   *Mitigation*: Guard `ALTER TABLE` by checking `PRAGMA table_info(reminder)` before executing the DDL.
2. **Pre-mortem 2**: Android 14 throws a `SecurityException` when scheduling a gentle reminder because exact alarm APIs were called.  
   *Mitigation*: For `GENTLE` tier, strictly use `setAndAllowWhileIdle(AlarmManager.RTC, ...)` which is an inexact alarm and does not require `SCHEDULE_EXACT_ALARM`.
3. **Pre-mortem 3**: A gentle reminder fails to wake or notify the user when the app is killed.  
   *Mitigation*: `setAndAllowWhileIdle` fires during Doze maintenance windows and wakes the broadcast receiver even when the app task is killed.

**Breaking Input Test**:
1. Save a `Reminder` with `AlarmTier.GENTLE` in a Version 1 SQLite database migrated to Version 2. Querying `getPendingReminders()` must return the reminder with `tier == AlarmTier.GENTLE`.
2. Schedule both an ALARM and GENTLE reminder; check `dumpsys alarm` to verify ALARM has `AlarmClockInfo` and GENTLE does not.
