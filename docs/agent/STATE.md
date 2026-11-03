# STATE (update every session; facts only)
Updated: 2026-11-03   Commit: in-progress (Task R2 completion; R1, R2, R11 implemented)
## Build/test status (command + exit code)
- `./gradlew :core:test`: SUCCESS (exit 0, 85/85 tests passed)
- `./gradlew :app:assembleDebug`: SUCCESS (exit 0)
- `./gradlew :app:assembleRelease`: SUCCESS (exit 0, R8 minification + lintVitalRelease)
- `./gradlew :app:compileDebugAndroidTestKotlin`: SUCCESS (exit 0, compiles 25 androidTest test methods across 6 classes)
- `./gradlew :app:testDebugUnitTest`: SUCCESS (exit 0, task reported NO-SOURCE; app/src/test contains no tests)
- `./gradlew :app:connectedDebugAndroidTest`: SUCCESS (exit 0, 25/25 instrumented tests passed on moto g73 5G - Android 14)
- `./gradlew :app:lintDebug`: SUCCESS (exit 0)
## Verified working (with evidence)
- `:core:test` passes all unit tests across resolver, time, context, gateway, provider, reliability, chat controller, and R2 candidate selection.
- `:app:connectedDebugAndroidTest` passes 25/25 instrumented tests on physical device moto g73 5G (API 34).
- R2: Candidate selection under load (>100 to 5000+ active items, D-104, D-113): eliminated silent 100-item default cutoff across all storage reads. Keyset cursor pagination over `(due_at, id)` up to 5000 hard-cap with `TooManyActiveNodesException` handling in `Gateway.handleResolveChange` informing the user to be more specific; targeted case-insensitive duplicate check `findActiveNodeByTitle` on active nodes (`TRIM(title) = ? COLLATE NOCASE`) in `Gateway.handleCreate` without loading items into memory; multi-tier candidate ranking `getRankedCandidates` across 6 tiers (Overdue, In-Progress, Due within 48h, Pinned, High severity, Recency) filtering out stale nodes in SQL/storage to prevent undated pinned and severity 3 items from being starved by future-dated nodes; logical calendar day range queries `getNodesDueBetween` and `getOverdueNodes` in `CheckinBuilder` providing exact morning agenda counts. Hardened untitled alarm handling to preserve title if provided (`isUntitled && targetTimeMillis != null && hint.isBlank()`), and bounded `parseDuration` against overflow. Verified by 7 unit tests in `R2CandidateSelectionTest.kt` and on-device instrumentation `testR2_StorageContract_AndroidStorage` in `AndroidStorageTest.kt`.
- R11: Compound duration parsing, date prompt enforcement, and untitled alarm fallbacks (D-112): unanchored multi-unit compound duration scanner `parseDuration` in `DateResolver` (seconds, minutes, hours, days, optional prefixes `in`/`after`/`within`, conjunctions `and`/`,`), hardened with `Locale.US` and `toLongOrNull()`, D-103 single-letter prefix retention ("15h" preserved as 15:00), prompt temporal authority rule enforcing verbatim user time copying and forbidding LLM clock calculation or "Now:" format reuse, untitled alarm handling in `Gateway` creating `<MM:SS> <Label>` / `<H:MM:SS> <Label>` / `<HH:mm> <Label>` titles with whitelisted labels (`alarm`, `reminder`, `notification`), direct durationMillis math preventing clock jitter, and duplicate-title bypass. Verified by 8 new unit tests in `DateResolverTest`, `SystemPromptTest`, `GatewayTest`, and `R11UntitledAlarmsTest`.
- `:app:compileDebugAndroidTestKotlin` compiles all 24 instrumented test methods across 6 test classes without error.
- R1: Reschedule/cancel/complete after ambiguity (D-110, D-111): SQLite v5 schema migration with nullable proposal payload column, ProposalPayload model, RESCHEDULE resolver isolation (passes null time to prevent +0.5 time override crown), proposal payload persistence for zero-model-call chip execution, handleConfirmChange destination time validity checks (> clock.now()), reminder tier preservation, explicit ToolResult.Error on unsupported intents (UPDATE, PAUSE, RESUME) before mutation, prompt alignment removing UPDATE - unit-verified in core and migration-verified in androidTest.
- Self-test & Active Reminders fix: `UiAction.RunSelfTest` refreshes UI `activeReminders` immediately upon arming, aligned to 5-sec countdown (`clock.now() + 5000L`). `AndroidAlarmScheduler` falls back to `setAndAllowWhileIdle(RTC_WAKEUP)` if exact alarm permission is restricted on Android 14+, preventing silent dropping of reminders. `AlarmReceiver` updates `AndroidAlarmScheduler.notifyFired()`, which is collected by `ChatController` to refresh `activeReminders` and dismiss the fired item in real time.
- T-A: DateResolver 24h + ambiguity chips (D-103) - unit-verified.
- T-B: Keyset cursor pagination over (due_at, id) on SQLite, getActiveNodeCounts, getStaleNodes, deterministic multi-tier ranking in ContextBuilder, 700-token budget cap, and unshown count summary (D-104) - unit-verified.
- T-C: Alarm tiers (Alarm vs Gentle), SQLite v2 migration, setAlarmClock vs setAndAllowWhileIdle, notification channel routing, and status bar disclosure text (D-105) - unit-verified; NOT VERIFIED on device (phone offline, D-105 status-bar check postponed).
- T-D: New edge type `follow_up`, relational edge schema, SQLite v3 migration with indices (`idx_edge_src_type`, `idx_edge_dst_type`), soft-deletion queries, undo integration, and full storage/gateway coverage (D-106) - unit-verified.
- T-E: Calendar queries (D-107): `getDaysWithMessages`, `getHeatMapCounts`, `getMessagesForDay`, `getRemindersBetween`, `getRemindersForDay`, UI state wiring for `LoadDay`, `ShowDayReminders`, `ClearDaySelection` - unit-verified only.
- T-F: Home quote slots (D-108, D-109): `quote_slot` table, SQLite v4 migration with idempotent upgrade, preset generic quote pool per 24h slot (`MORNING`, `AFTERNOON`, `EVENING`, `NIGHT`), async live generation with state hash and max 2 regens cap - unit-verified only.
- T16: Compose auto-scroll on `ChatScreen` via `rememberLazyListState` and `imePadding()` to handle keyboard overlap.
- T18: Housekeeping complete: official 674-line GPLv3 text in `LICENSE`, deletion of stray file `a`, removal of device IP from `.idea/deploymentTargetSelector.xml`, and documentation alignment in `docs/ui-contract.md`.
- T19: Release (R8) build smoke test passing (`:app:assembleRelease`) with proguard keep rules for serializable models (`Node`, `Reminder`, `SoulFact`).
- 6 custom agents (`reviewer`, `debugger`, `blueprint-checker`, `migration-guard`, `test-auditor`, `android-device`), 5 commands (`bootstrap`, `audit`, `fix`, `verify`, `handoff`), 5 skills, and safety hooks installed in repo `.claude/` and `.openclaude/` directories (OpenClaude canonical is `.openclaude/`).
## Known broken
- B5 (Export UI picker & passphrase dialog) is not implemented; dark-shipped pending v1.1 SAF integration.
- Tasks T-A, T-B, T-C, T-D, T6, T13, T20 set to in-progress [~] pending remediation (R3..R10; R1, R2, R11 completed).
## UNVERIFIED (from old chat / unproven)
- T-E, T-F are unit-verified only.
- R3 through R10 remediation tasks open (R1, R2, R11 completed).
## Environment notes (device, Android version, OEM, toolchain versions)
- Physical test device is connected: moto g73 5G running Android 14 (API 34). Instrumented test suite (:app:connectedDebugAndroidTest) verified 25/25 passed.
- Windows 11 Pro, Bash environment.
- Toolchains: AGP 9.4.0, Kotlin 2.4.21, Gradle 9.6.0, daemon JDK toolchain 25 (Eclipse Adoptium 25.0.4.1+1-LTS), JVM target 17.
