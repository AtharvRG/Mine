# STATE (update every session; facts only)
Updated: 2026-11-03   Commit: 3ed71b3 (baseline) + uncommitted working tree
## Build/test status (command + exit code)
- `./gradlew :core:test`: SUCCESS (exit 0)
- `./gradlew :app:assembleDebug`: SUCCESS (exit 0)
- `./gradlew :app:assembleRelease`: SUCCESS (exit 0, R8 minification + lintVitalRelease)
- `./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`: SUCCESS (exit 0)
- `./gradlew :app:connectedDebugAndroidTest`: NOT VERIFIED on device since the phone is offline (23 androidTest test methods across 6 files)
- `./gradlew :app:lintDebug`: SUCCESS (exit 0)
## Verified working (with evidence)
- `:core:test` passes all unit tests across resolver, time, context, gateway, provider, reliability, and chat controller.
- `:app:compileDebugAndroidTestKotlin` compiles all 23 instrumented test methods across 6 test classes without error.
- T-A: DateResolver 24h + ambiguity chips (D-103) - unit-verified.
- T-B: Keyset cursor pagination over (due_at, id) on SQLite, getActiveNodeCounts, getStaleNodes, deterministic multi-tier ranking in ContextBuilder, 700-token budget cap, and unshown count summary (D-104) - unit-verified.
- T-C: Alarm tiers (Alarm vs Gentle), SQLite v2 migration, setAlarmClock vs setAndAllowWhileIdle, notification channel routing, and status bar disclosure text (D-105) - unit-verified; NOT VERIFIED on device (phone offline, D-105 status-bar check postponed).
- T-D: New edge type `follow_up`, relational edge schema, SQLite v3 migration with indices (`idx_edge_src_type`, `idx_edge_dst_type`), soft-deletion queries, undo integration, and full storage/gateway coverage (D-106) - unit-verified.
- T-E: Calendar queries (D-107): `getDaysWithMessages`, `getHeatMapCounts`, `getMessagesForDay`, `getRemindersBetween`, `getRemindersForDay`, UI state wiring for `LoadDay`, `ShowDayReminders`, `ClearDaySelection` - unit-verified only.
- T-F: Home quote slots (D-108, D-109): `quote_slot` table, SQLite v4 migration with idempotent upgrade, preset generic quote pool per 24h slot (`MORNING`, `AFTERNOON`, `EVENING`, `NIGHT`), async live generation with state hash and max 2 regens cap - unit-verified only.
- T16: Compose auto-scroll on `ChatScreen` via `rememberLazyListState` and `imePadding()` to handle keyboard overlap.
- T18: Housekeeping complete: official 674-line GPLv3 text in `LICENSE`, deletion of stray file `a`, removal of device IP from `.idea/deploymentTargetSelector.xml`, and documentation alignment in `docs/ui-contract.md`.
- T19: Release (R8) build smoke test passing (`:app:assembleRelease`) with proguard keep rules for serializable models (`Node`, `Reminder`, `SoulFact`).
- 6 custom agents (`reviewer`, `debugger`, `blueprint-checker`, `migration-guard`, `test-auditor`, `android-device`), 5 commands (`bootstrap`, `audit`, `fix`, `verify`, `handoff`), 5 skills, and safety hooks installed in project and user `.openclaude/` directories.
## Known broken
- B5 (Export UI picker & passphrase dialog) is not implemented; dark-shipped pending v1.1 SAF integration.
- Tasks T-A, T-B, T-C, T-D, T6, T13, T20 set to in-progress [~] pending remediation (R1..R10).
## UNVERIFIED (from old chat / unproven)
- All device execution claims: NOT VERIFIED on device since the phone is offline.
- T-E, T-F are unit-verified only.
- R1 through R10 remediation tasks open.
## Environment notes (device, Android version, OEM, toolchain versions)
- Physical test device is offline (details stored in ignored device-local.properties). Manage without connected device tests using JVM unit tests (`:core:test`), compile tasks (`:app:compileDebugAndroidTestKotlin`), assemble, and lint until device returns.
- Windows 11 Pro, Bash environment.
- Toolchains: AGP 9.4.0, Kotlin 2.4.21, Gradle 9.6.0, daemon JDK toolchain 25 (Eclipse Adoptium 25.0.4.1+1-LTS), JVM target 17.
