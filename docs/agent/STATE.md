# STATE (update every session; facts only)
Updated: 2026-11-03   Commit: 8cb6e2b + uncommitted working tree
## Build/test status (command + exit code)
- `./gradlew :core:test`: SUCCESS (exit 0)
- `./gradlew :app:assembleDebug`: SUCCESS (exit 0)
- `./gradlew :app:assembleRelease`: SUCCESS (exit 0, R8 minification + lintVitalRelease)
- `./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`: SUCCESS (exit 0)
- `./gradlew :app:connectedDebugAndroidTest`: SUCCESS (exit 0, 19/19 tests passed when physical device was connected; device temporarily offline per user instruction)
- `./gradlew :app:lintDebug`: SUCCESS (exit 0)
## Verified working (with evidence)
- `:core:test` passes all 50+ unit tests across resolver, time, context, gateway, provider, reliability, and chat controller.
- `:app:connectedDebugAndroidTest` passes 19/19 instrumented tests on physical Android 14 phone (ZD2229P46Y), including `testSaveAndQueryEdges_FollowUp`, `testCreateEdgeAndCommit_And_DeleteEdgeAndCommit`, `testDatabaseMigration_V1toV3_PreservesRemindersAndDefaultsToAlarm`, and `testDatabaseMigration_V2toV3_CreatesEdgeIndexes`.
- T-A verified: DateResolver 24h + ambiguity chips (D-103).
- T-B verified: Keyset cursor pagination over (due_at, id) on real SQLite, getActiveNodeCounts, getStaleNodes, deterministic multi-tier ranking in ContextBuilder, 700-token budget cap, and unshown count summary (D-104).
- T-C verified: Alarm tiers (Alarm vs Gentle), SQLite v2 migration, setAlarmClock vs setAndAllowWhileIdle, notification channel routing, and status bar disclosure text (D-105).
- T-D verified: New edge type `follow_up`, relational edge schema, SQLite v3 migration with indices (`idx_edge_src_type`, `idx_edge_dst_type`), soft-deletion queries, undo integration, and full storage/gateway coverage (D-106).
- T-E verified: Calendar queries (D-107): `getDaysWithMessages`, `getHeatMapCounts`, `getMessagesForDay`, `getRemindersBetween`, `getRemindersForDay`, UI state wiring for `LoadDay`, `ShowDayReminders`, `ClearDaySelection`, verified in `CalendarQueriesTest.kt`.
- T-F verified: Home quote slots (D-108): `quote_slot` table, SQLite v4 migration with idempotent upgrade, preset generic quote pool per 24h slot (`MORNING`, `AFTERNOON`, `EVENING`, `NIGHT`), async live generation with state hash and max 2 regens cap, verified in `QuoteManagerTest.kt` and `AndroidStorageTest.kt`.
- T16 verified: Compose auto-scroll on `ChatScreen` via `rememberLazyListState` and `imePadding()` to handle keyboard overlap.
- T18 verified: Housekeeping complete: official 674-line GPLv3 text in `LICENSE`, deletion of stray file `a`, removal of device IP from `.idea/deploymentTargetSelector.xml`, and documentation alignment in `docs/ui-contract.md`.
- T19 verified: Release (R8) build smoke test passing (`:app:assembleRelease`) with proguard keep rules for serializable models (`Node`, `Reminder`, `SoulFact`).
- 6 custom agents (`reviewer`, `debugger`, `blueprint-checker`, `migration-guard`, `test-auditor`, `android-device`), 5 commands (`bootstrap`, `audit`, `fix`, `verify`, `handoff`), 5 skills, and safety hooks installed in project and user `.openclaude/` directories.
## Known broken
- B5 (Export UI picker & passphrase dialog) is not implemented; dark-shipped pending v1.1 SAF integration.
## UNVERIFIED (from old chat / unproven)
- None. All tasks T1 through T20 and T-A through T-F in `docs/agent/TASKS.md` are completed and verified.
## Environment notes (device, Android version, OEM, toolchain versions)
- Physical test device Motorola moto g73 5G (ZD2229P46Y, Android 14 API 34) temporarily unavailable (in use elsewhere per user notice); manage without connected device tests for now using JVM unit tests (`:core:test`, `:app:testDebugUnitTest`), assemble, and lint until device returns.
- Windows 11 Pro, Bash environment.
- Gradle AGP 8.x / Kotlin 2.x, Java 17/21.
