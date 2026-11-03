# HANDOFF (Session 2 - Tasks R3 & R3b Complete)

## 1. What Changed

- **Commit `3ed71b3`**: Baseline commit capturing initial repo state prior to remediation review.
- **Commit `81ec87a`** (`chore: record correction, hygiene, R1-R10 plan`):
  - Recorded architectural decisions D-109, D-110, D-111, and clarified D-105 gate status in `docs/agent/DECISIONS.md`.
  - Re-opened tasks T-A, T-B, T-C, T-D, T6, T13, T20 to in-progress `[~]` in `docs/agent/TASKS.md`.
  - Appended remediation tasks R1 through R10 and restored dropped roadmap backlog items (P1-B 15, 17, DateResolver weekday names, P1-D 20 remainder, P1-E 21..34) in `docs/agent/TASKS.md`.
  - Corrected toolchain versions, accurate test counts, and device offline reality in `docs/agent/STATE.md`.
  - Redacted device identifiers to git-ignored `device-local.properties`.
  - Deleted 0-byte dead stub `core/src/main/java/com/anchor/core/alarms/AlarmReceiver.kt`.
  - Hardened `scripts/ralph.py` against blind `git add -A`, `--allow-empty`, and continued loop on test failure.
  - Repointed hook commands in `.openclaude/settings.json` to `.openclaude/hooks/*.py`.
  - Aligned `docs/ui-contract.md` to remove unimplemented storage query claims.
  - Created `docs/agent/BLUEPRINT_PATCHES.md` with Patch 1 for Blueprint 13 s3.6 (`follow_up` edge type).
- **Commit `982dbc7` (R0b)**:
  - Created `docs/agent/work/session1-remediation-spec.md` preserving the full verbatim session 1 specification prompt.
  - Added pointer to `session1-remediation-spec.md` at top of `docs/agent/TASKS.md`.
  - Added historical warning banner to `docs/agent/INBOX_HANDOFF.md`, `docs/agent/work/session0-plan.md`, and `docs/agent/work/t-a.md` through `t-f.md`.
  - Reconciled all cited test names in `docs/agent/work/session0-plan.md` to verified existing test names or marked "does not exist".
  - Replaced `getEdgesFrom`/`getEdgesTo` with `getEdgesForSource`/`getEdgesForDestination` in `docs/ui-contract.md` and verified all UiState fields, UiActions, and Storage methods exist in `:core`.
  - Verified directory junction `docs/blueprints -> D:\CnP\MDocs (Attempt 3)`, added `docs/blueprints/` to `.gitignore`, and added deny rules for `Edit` and `Write` on `docs/blueprints/**` in both `.openclaude/settings.json` and `.claude/settings.json`.
    - *Safety Note*: The `Edit`/`Write` deny rules on `docs/blueprints/**` only block those two tools, not Bash, and may not match once the path resolves to `D:\CnP\...`. The developer will set the source folder to read-only.
  - Added developer-lock comment to `scripts/ralph.py` and gated `scripts/ralph_prompt.txt` behind `RALPH: ENABLED`.
  - Re-ran `:core:test`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:compileDebugAndroidTestKotlin`, and `:app:lintDebug` with full status captured.
- **Commit `ff78d71` (R0c)**:
  - Verified blueprint junction directory listing and gitignore compliance (`git check-ignore -v docs/blueprints` matches; `git ls-files docs/blueprints` empty).
  - Split `:app:compileDebugAndroidTestKotlin` and `:app:testDebugUnitTest` in `STATE.md` and re-ran `:app:testDebugUnitTest` (SUCCESS, exit 0, `NO-SOURCE`).
  - Corrected `docs/ui-contract.md` for `AcceptSoulFact` (sets status to "active") and `inboxCount` (proposals + soul facts; stale items excluded pending R4).
  - Appended R1 notes to `docs/agent/work/session1-remediation-spec.md` (v5 migration assertions update, `MineDatabaseCallback.onUpgrade` trigger limitation recorded).
  - Logged trigger limitation task in `docs/agent/TASKS.md`.
- **Commit `11f5bc4` (Task R1 - Reschedule/cancel/complete after ambiguity, D-110, D-111)**:
  - SQLite schema bumped to v5 with nullable `payload TEXT` on `proposal` table, with idempotent `PRAGMA table_info` upgrade logic in `MineDatabaseCallback`.
  - Added `ProposalPayload` serializable data class and updated `Proposal` model.
  - Implemented `propose_change` intent validation in `Gateway`: immediately reject unsupported `UPDATE`, `PAUSE`, `RESUME` with `ToolResult.Error` before mutating state.
  - For `RESCHEDULE`, pass `timeForResolver = null` to `Resolver.resolve` to prevent +0.5 time proximity bonus biasing candidate selection; store destination timestamp in `proposal.payload`.
  - In `handleConfirmChange`, branch on `RESCHEDULE`: validate destination time `> clock.now()`, preserve node `active` status, update node `dueAt`, update/create reminder preserving existing tier (`oldRem?.tier ?: AlarmTier.ALARM`), snapshot before/after state for undo, atomically consume proposal in SQLite transaction, format deterministic receipt `"Rescheduled: <title> to <date>"`. Stale/past destination times consume the proposal and return explicit error `"That time has already passed; tell me the new time again"`.
  - Removed `UPDATE` from prompt instructions in `ChatController.kt`.
  - Authored comprehensive TDD test suite `R1RescheduleAmbiguityTest.kt` in `:core`.
  - Authored v4-to-v5 migration test `testDatabaseMigration_V4toV5_AddsProposalPayloadColumn` and updated smoke test in `DatabaseMigrationTest.kt`.
  - Authored branching analysis `docs/agent/work/r1.md` and independent 3-agent review `docs/agent/work/R1-review.md`.
- **Self-Test and Active Reminders Live Fix**:
  - `ChatController.kt`: Updated `UiAction.RunSelfTest` to arm for 5 seconds (`clock.now() + 5000L`) and immediately emit updated `activeReminders` to UI state so the reminder is instantly visible in the Compose UI list. Added a collection loop on `alarmScheduler.lastFiredId` to invoke `refreshUI()` on firing, removing expired reminders from screen in real time.
  - `AndroidAlarmScheduler.kt`: Replaced private unused `_lastFiredId` flow with companion `_lastFiredId` and `notifyFired(id)` hook. Added graceful fallback to `setAndAllowWhileIdle(RTC_WAKEUP)` if `canScheduleExactAlarms()` is false or `SecurityException` is caught on Android 14+, preventing silent dropping of reminders.
  - `AlarmReceiver.kt`: Notifies `AndroidAlarmScheduler.notifyFired(id)` upon delivery.
  - Built and deployed updated APK directly to connected mobile device.
- **Task R11 (Compound duration parsing, date prompt enforcement, and untitled alarm fallbacks, D-112)**:
  - `DateResolver.kt`: Implemented unanchored `parseDuration` scanning compound units (e.g. "in 2 min 32 seconds"), seconds ("in 10 secs"), prefixes (`in`, `after`, `within`), and conjunctions (`and`, `,`). Retained D-103 single-letter unit rule without prefix ("15h" resolves to 15:00, not duration). Hardened parsing with `Locale.US` normalization, `toLongOrNull()`, and added `formatTimeOfDay`.
  - `ChatController.kt`: Added temporal authority prompt instructions strictly forbidding the LLM from calculating clock timestamps or reusing "Now:" format, instructing verbatim copying into `due_text`. Added untitled alarm prompt rule to ask "For what?" if no subject is given, and call `propose_change CREATE` with `untitled=true` and whitelisted `label` if no subject is confirmed.
  - `Gateway.kt`: Synthesizes deterministic fallback title `<MM:SS> <Label>` (durations < 1h), `<H:MM:SS> <Label>` (durations >= 1h), or device time-of-day `<HH:mm> <Label>` (absolute times) with label whitelist (`alarm`, `reminder`, `notification`), using direct durationMillis math to eliminate millisecond clock jitter, and bypassing duplicate title deduplication for auto-titled items. Reworded blank title error to `"Missing title: ask the user what it is for."`. Frozen label at creation.
  - TDD test suites: Authored `R11UntitledAlarmsTest.kt` in `:core`, added unit tests in `DateResolverTest.kt`, `SystemPromptTest.kt`, and updated `GatewayTest.kt`.
  - Recorded architectural decision D-112 in `docs/agent/DECISIONS.md`.

- **Task R2 (Candidate selection broken by 100-item default, T-B regression, D-104)**:
  - Eliminated the silent 100-item default cutoff across all storage reads and candidate selection paths, scaling safely to 5000+ active items.
  - `Storage.kt`:
    - Defined `TooManyActiveNodesException(val cap: Int)`.
    - Added extension function `getAllActiveNodesPaged(pageSize = 200, hardCap = 5000)` streaming records via keyset pagination with composite cursor `${dueAt}:${id}` (`due_at IS NULL, due_at ASC, id ASC`).
    - Declared new targeted contract methods: `findActiveNodeByTitle(title: String): Node?`, `getNodesDueBetween(startMs: Long, endMs: Long, limit: Int = 500): List<Node>`, `getOverdueNodes(beforeMs: Long, limit: Int = 500): List<Node>`, and `getRankedCandidates(now: Long, staleCutoffMs: Long, perTierLimit: Int = 25): List<Node>`.
  - `Gateway.kt`:
    - `handleCreate`: Uses `storage.findActiveNodeByTitle(title)` for exact case-insensitive match on active/in_progress/paused items without scanning in memory.
    - `handleResolveChange`: Pages through active items up to 5000 using `getAllActiveNodesPaged(200, 5000)`. Catches `TooManyActiveNodesException` and returns explicit user feedback: `"There are too many active items (${e.cap}+). Please be more specific with the item title or due time."`.
    - Hardened `untitled` handling: `isAutoTitled = isUntitled && targetTimeMillis != null && hint.isBlank()`. When `untitled="true"` and an explicit title/hint is given ("Buy milk"), the title is preserved as "Buy milk".
  - `DateResolver.kt`:
    - Hardened `parseDuration` against overflow and excessive values: amounts > 100,000 return null, and total duration is bounded to `1..366 days`.
  - `ContextBuilder.kt`:
    - Replaced `storage.getActiveNodes(100)` with `storage.getRankedCandidates(now, staleCutoffMs, perTierLimit = 25)` and `storage.getActiveNodeCounts(now, staleCutoffMs)`.
    - Context pack candidate ranking gathers items across 6 tiers (Overdue, In-Progress, Due within 48h, Pinned, High severity, Recency), filtering out stale nodes in SQL/storage to prevent undated pinned and severity 3 items from being starved by future-dated nodes.
  - `CheckinBuilder.kt`:
    - Replaced `storage.getActiveNodes(100)` with `storage.getNodesDueBetween(logicalTodayStart, logicalTodayEnd, limit = 500)` and `storage.getOverdueNodes(logicalTodayStart, limit = 500)` to compute exact morning agenda and overdue facts.
  - `AndroidStorage.kt`:
    - Implemented helper `queryNodes(sql, args)` executing under `ioDispatcher`.
    - Implemented `findActiveNodeByTitle` using `WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') AND TRIM(title) = ? COLLATE NOCASE LIMIT 1`.
    - Implemented `getNodesDueBetween` and `getOverdueNodes` leveraging existing index `idx_node_status_due`.
    - Implemented `getRankedCandidates` querying 6 indexed tiers excluding stale items (`now - max(updated_at, created_at) <= staleCutoffMs`) and deduplicating by ID preserving tier precedence.
  - `SharedFakeStorage.kt`:
    - Implemented matching in-memory behavior using `LinkedHashMap<String, Node>` for exact parity with `AndroidStorage`.
  - Recorded architectural decision D-113 in `docs/agent/DECISIONS.md`.
  - TDD test suites:
    - Authored `R2CandidateSelectionTest.kt` in `:core` (7 unit tests covering resolution at 300 nodes, duplicate lookup at 500 nodes, context pack tier survival, stale overdue exclusion, checkin agenda counts, storage contract parity, and 5000 hard-cap exceeded).
    - Authored `testR2_StorageContract_AndroidStorage` in `AndroidStorageTest.kt` verifying identical behavior on real SQLite/SQLCipher on Android.
    - Added untitled title-preservation test in `R11UntitledAlarmsTest.kt`.
    - Added duration overflow and bounding tests in `DateResolverTest.kt`.
  - Authored branching analysis `docs/agent/work/r2.md` and independent 3-agent review `docs/agent/work/r2-review.md`.
- **Task R3 (Alarm truth, permission, and silent failures, D-102, D-114)**:
  - Manifest gate: Removed `SCHEDULE_EXACT_ALARM` from `app/src/main/AndroidManifest.xml` per D-102. Authored `ManifestPermissionTest` asserting that the merged manifest declares none of `VIBRATE`, `WAKE_LOCK`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`.
  - Zero silent failure swallowing: Refactored `AlarmScheduler.schedule` to return sealed `ScheduleResult` (`Armed` or `Failed(reason)`). `AndroidAlarmScheduler` returns `Failed` on `SecurityException` or scheduling errors instead of silently swallowing or falling back.
  - Reliability & UI error exposure: Added `issuesJson` to `ReliabilityState` in `Stats.kt`, updated `AndroidStorage.kt` and `SharedFakeStorage.kt` to persist/load `issues_json` in table `reliability_state`. Added `val schedulingError: String? = null` to `UiState` in `ChatContract.kt`. When scheduling fails, reliability state is marked `at_risk` with error details and `UiState.schedulingError` is surfaced.
  - Unified core reconciler: Implemented `AlarmReconciler(storage, scheduler, clock)` with `reconcile(force: Boolean)`. `force = true` unconditionally re-arms all active pending reminders within 60s grace on boot, init, and time changes. `force = false` selectively schedules changed/new reminders and cancels/purges orphaned registry entries during UI updates. Unified reconciliation across `ChatController` and `BootReceiver`.
  - Sweeper missed reminders: `AlarmReceiver` inspects `EXTRA_MISSED = true` and prefixes notification text with `"Missed: "` for both DB and fallback flows. `BootReceiver` sets `EXTRA_MISSED = true` when dispatching missed alarms.
  - Fixed `AlarmSelfTest`: Removed `canScheduleExactAlarms()` skip, added `alarmManager.nextAlarmClock` verification (`triggerTime == fireAt`, `showIntent.creatorPackage == packageName`) for `ALARM` tier, and verified `GENTLE` tier exclusion.
  - TDD test suites: Authored `AlarmReconcilerTest.kt` in `:core`, added `testReconcileAlarms_SchedulerFailure_ExposesSchedulingErrorInUiState` in `ChatControllerEdgeCaseTest.kt`, updated `DatabaseMigrationTest.kt` smoke test, added `testReliabilityState_IssuesJsonRoundTrip` in `AndroidStorageTest.kt`, added `testAlarmReceiver_MissedReminder_PrefixesMissedText` in `AlarmReceiverTest.kt`, and authored `ManifestPermissionTest.kt`.
  - Recorded architectural decision D-114 in `docs/agent/DECISIONS.md`.
  - Authored branching analysis `docs/agent/work/r3.md` and independent review report `docs/agent/work/r3-review.md`.
  - Hardening from reviewer: added `EXTRA_MISSED = true` and `EXTRA_TIER` in `MainViewModel.kt` sweeper broadcast, cleared `UiState.schedulingError` when reconciliation succeeds without failure in `ChatController.kt`, and hardened control character JSON escaping in `AlarmReconciler.kt`.
  - **Physical Device Connected Tests on moto g73 5G (Android 14 / API 34)**:
    - 29/29 tests passed across all 7 test classes (100% success rate: 3 `AlarmReceiverTest`, 2 `AlarmSelfTest`, 1 `ManifestPermissionTest`, 1 `ExportTest`, 19 `AndroidStorageTest`, 1 `DatabaseMigrationTest`, 2 `StorageTest`).
    - Merged manifest on-device test `ManifestPermissionTest` confirmed none of `VIBRATE`, `WAKE_LOCK`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` are declared.
    - Zero-Vibration Protocol verified: Notification channels `mine_reminders_v3` and `mine_gentle_reminders_v3` explicitly set `enableVibration(false)` and `vibrationPattern = null`, no vibration calls, safely accommodating damaged vibration motor hardware.

- **Task R3b (Session 2 Correction, Re-Verification, and Reliability State Hardening)**:
  - Addressed all items from Part 1 Sections B, C, D, and E of Session 2 directive.
  - Manifest gate & D-102 amended: Declared `USE_EXACT_ALARM` per Android 14 requirements for `setAlarmClock()` (normal auto-granted permission for alarm apps). Confirmed `SCHEDULE_EXACT_ALARM`, `VIBRATE`, and `WAKE_LOCK` are strictly forbidden. Verified on physical device in `ManifestPermissionTest`.
  - Zero-Vibration Protocol: Verified on device via `dumpsys notification` that channels `mine_reminders_v3` and `mine_gentle_reminders_v3` have `mVibration=null` and `mVibrationEnabled=false`, with zero vibration code or permissions across app and tests.
  - Clean-Install Physical Verification: Connected test run on Motorola moto g73 5G (Android 14 / API 34) preceded by clean uninstall (`./gradlew.bat :app:uninstallDebug :app:uninstallDebugAndroidTest`). 31/31 connected tests passed across 8 test classes (100% success rate).
  - AlarmSelfTest rewrite: Removed all `canExact` branching. Asserts `ScheduleResult.Armed` unconditionally, verifies `alarmManager.nextAlarmClock` matches trigger time and package name, and verifies `GENTLE` tier exclusion.
  - Connected BootReceiver test: Authored `BootReceiverTest.kt` verifying `BootReceiver` -> `ReliabilityManager.runSweeper` -> `AlarmReceiver` end-to-end for reminders >60s overdue (verifies `"Missed: "` prefix, single record in `reminder_fire`, pending row deleted, zero duplicate notifications on subsequent boots).
  - Reliability state recovery & bounding: Recomputed `score` to `"healthy"` on a clean reconciliation pass (zero failures), clearing `issuesJson`. Bounded `issuesJson` to at most 5 distinct recent issues with timestamps.
  - UiState multi-failure exposure: Added `schedulingFailedCount` to `UiState`, populated in `ChatController.reconcileAlarms` alongside `schedulingError`, and cleared on a clean pass.
  - AndroidStorage.getNode column hydration: Hydrated all 11 columns in `getNode` (parsing `priority`, `severity`, and `pinned` from `attrs`), ensuring snapshot equality in `Gateway.handleUndo`.
  - Prompt budgeting: Capped soul facts to 1250 characters and open proposals to 500 characters in `ContextBuilder.kt`.
  - Keyset pagination parity: Implemented composite `(due_at, id)` keyset comparison in `SharedFakeStorage.getActiveNodes` matching SQLite ordering semantics when a cursor node is missing.
  - Untitled auto-titling guard: Enforced `isAutoTitled = isUntitled && targetTimeMillis != null && hint.isBlank()`.

## 2. Verified Working (with Evidence)

### Command: `./gradlew :core:test` (Exit code: 0)
105 unit tests executed, 105 passed, 0 failures, 0 skipped (100% success rate).

### Connected Tests on Physical Device (Clean Install):
- Command: `./gradlew.bat :app:connectedDebugAndroidTest` (Exit code: 0)
- Device: Motorola moto g73 5G (Android 14 / API 34), serial `10.175.15.5:5555`.
- Result: 31/31 passed across 8 test classes (100% success rate).
  - `AlarmReceiverTest`: 3 passed.
  - `AlarmSelfTest`: 2 passed.
  - `BootReceiverTest`: 1 passed.
  - `ManifestPermissionTest`: 1 passed.
  - `ExportTest`: 1 passed.
  - `AndroidStorageTest`: 20 passed.
  - `DatabaseMigrationTest`: 1 passed.
  - `StorageTest`: 2 passed.
- HTML report: `app/build/reports/androidTests/connected/debug/index.html`
- XML report: `app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml`

### Other Verified Builds:
- `./gradlew :app:assembleDebug`: SUCCESS (exit 0)
- `./gradlew :app:assembleRelease`: SUCCESS (exit 0, R8 minification + lintVitalRelease)
- `:app:compileDebugAndroidTestKotlin`: SUCCESS (exit 0, compiles 31 instrumented test methods across 8 classes)
- `./gradlew :app:testDebugUnitTest`: SUCCESS (exit 0, NO-SOURCE; app/src/test contains no tests)
- `./gradlew :app:lintDebug`: SUCCESS (exit 0)

## 3. NOT Verified
- **D-105 Phone Gate**: Status-bar alarm clock icon display for Alarm-tier reminders remains to be checked on real UI while phone is active. Task T-C remains open.
- **T-E (Calendar Queries) & T-F (Home Quotes)**: Unit-verified only.
- **Remediation Tasks R4 through R10**: Open, pending execution (R1, R2, R3, R11, and R3b completed).
- **B5 Export UI**: SAF picker and passphrase dialog dark-shipped pending v1.1.

## 4. Operating Rules
- Commit per task using `git commit -s`.
- Never commit failing tests.
- One task per run; stop and await user approval before proceeding to the next task.

## 5. Next Step
- Reviewer subagent (`reviewer`) verdict: **APPROVE**.
- Blueprint checker subagent (`blueprint-checker`) verdict: **ALL SATISFIED**.
- Await user explicit "OK" to commit Task R3b (`git commit -s`).
- Next scheduled task is **Task R4**: Unreachable features: stale items and time chips (T-A, T-B) per `session1-remediation-spec.md`.
