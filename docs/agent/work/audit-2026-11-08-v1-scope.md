# Audit: v1 scope vs. code — 2026-11-08

Contract: `docs/blueprints/90-v1-scope.md` §3.1 (21 ship rows), §3.6 (gates X-01…X-10).
Method: read every row, then grep/read the code for evidence. Status is one of
**BUILT** (code exists and is wired into a user path), **PARTIAL** (some of the row exists),
**ABSENT** (no code). Evidence is a file path, not a claim.

## 1. §3.1 row-by-row

| # | Row (abridged) | Status | Evidence |
|---|---|---|---|
| 1 | SQLCipher DB, Keystore-wrapped key, app lock as UI gate | PARTIAL | `app/.../storage/DbKeyProvider.kt` (Keystore), `MineDatabaseHelper.kt` (SQLCipher factory). **No app-lock code**: `grep -rE "appLock\|AppLock\|biometric\|Biometric" app/src/main core/src/main` → 0 hits. |
| 2 | Raw/derived/operational layers; migrations with snapshots | BUILT | `MineDatabaseHelper.kt` (versioned `onUpgrade`, `INDEXES`, `TRIGGER_NAMES`), `.bak` snapshot in `AndroidExportManager.kt` (H-04, D-129). |
| 3 | Passphrase-encrypted export (`.mnex`) and restore | BUILT | `core/.../export/ExportManager.kt`, `ExportController.kt`, `app/.../BackupScreen.kt`, `AndroidExportManager.kt`. |
| 4 | Soul with propose/approve, seeded in onboarding | PARTIAL | Propose/approve: `Storage.getProposedSoulFacts`, `updateSoulFactStatus*`, `ChatController`. **Onboarding seeding: absent** (no onboarding code, see row 21). |
| 5 | Node/edge graph with status lifecycle | BUILT | `models/Memory.kt` (`Node`, `Edge`, `EdgeTypes`), `Storage` commit methods, `rankHomeNodes`. |
| 6 | Resolver + Undo | BUILT | `core/.../resolver/Resolver.kt`, `Gateway.handleUndo`, `Storage.hasUndo`. |
| 7 | Tool gateway, alias IDs, `search_history` | **BUILT (this session)** | Gateway dispatch in `core/.../gateway/Gateway.kt`; `search_history` added this session (`Storage.searchHistory`, `AndroidStorage.searchHistory`, `Gateway` branch, `formatSearchResults`, prompt line in `ChatController.kt`). **Alias IDs are not stored**: `grep -rn "alias" core/src/main app/src/main` → 0 hits; the formatter emits `n_<id>`/`s_<id>`/`m_<id>` on output but there is no `alias_map` and no `unknown_ref` handling. |
| 8 | Fixed-budget context pack | BUILT | `core/.../context/ContextBuilder.kt` (`maxTokens = 700`, char budgets, newest-message guarantee). |
| 9 | On-device EmbeddingGemma 2, hybrid retrieval, summaries | **ABSENT** | `grep -rE "EmbeddingGemma\|embedding\|Embedder\|hybrid" core/src/main app/src/main` → 0 hits. Retrieval is FTS5 only. |
| 10 | Personas (presets and custom) | PARTIAL | Only a `persona_id` column in `MineDatabaseHelper.kt` and a `persona` mention in `ContextBuilder`/`QuoteManager`/`ChatController`. No preset list, no persona editor, no persona-scoped prompt. |
| 11 | Visible behavioral learning | **ABSENT** | `learned_pref` and `signal_log` tables exist in `MineDatabaseHelper.kt`; nothing reads or writes them (`grep -rn "learned_pref\|LearnedPref\|signal_log"` → only the schema). |
| 12 | Reminder engine: alarm, then conversation (alarm only offline) | BUILT | `alarms/AlarmReceiver.kt`, `AndroidAlarmScheduler.kt`, `core/.../alarms/AlarmReconciler.kt`, `AlarmScheduler.kt`. Alarm-only-offline branch not separately evidenced. |
| 13 | Nudge engine: one open thread, ignore-backoff, quiet hours, hidden fuse | PARTIAL | `core/.../nudge/NudgeEngine.kt` implements threshold/gap/ignore/reply. **Not wired**: `grep -rn "NudgeEngine" core/src/main app/src/main` outside its own file → 0 hits. No production caller. |
| 14 | Morning/evening check-ins, post-task review, weekly review, native charts | PARTIAL | `CheckinBuilder.buildMorningFacts()` wired into `ContextBuilder:104`. No evening check-in, no post-task review, no weekly review (`grep -rni weekly` → 0), no charts. |
| 15 | Provider layer: OpenAI-compatible, RPM limiter, 429 cooldown, fallback chain, normalizer | BUILT | `core/.../provider/ProviderOrchestrator.kt`, `RateLimiter.kt`, `SseModels.kt`, `Models.kt`; `app/.../provider/AndroidHttpTransport.kt`. |
| 16 | Offline queue, templates, alarm-only mode | **ABSENT** | `grep -rE "OfflineQueue\|offlineQueue\|alarmOnly\|alarm_only\|template" core/src/main app/src/main` → 0 hits. |
| 17 | Reliability: reboot re-arm, sweeper, OEM guides, self-test, reliability score | PARTIAL | Reboot re-arm `BootReceiver.kt`; sweeper `ReliabilityManager.runSweeper()` + H-02 periodic trigger; self-test `UiAction.RunSelfTest` (`ChatController:259`). **No OEM guides** (`grep -rni oem` → 0). **No reliability score**: `reliability_state.score` column exists, nothing computes it. |
| 18 | Daily session timeline, scrubber, receipts | PARTIAL | `ChatTimelineBuilder.kt`, `ReceiptFormatter.kt`, `ChatItems.kt`, day navigation in `Storage` (`getMessagesForDay`, `getRemindersForDay`). No scrubber widget found in `MainActivity.kt` (screens: `ChatScreen`, `RemindersScreen`, `ProviderScreen`, `DebugScreen`, `BackupScreen`). |
| 19 | Quick capture: notification reply, optional capture notification, launcher shortcut | **ABSENT** | `AlarmReceiver.kt` has no `RemoteInput`, no `addAction` reply, no `ShortcutManager`. |
| 20 | Memory viewer/editor (seven areas + Ambient), Trash and Erase | **ABSENT** | `grep -rE "MemoryViewer\|Trash\|Erase\|memory viewer"` → 0 hits. No such screen in `MainActivity.kt`. |
| 21 | Onboarding, settings catalog, Quick setup on Welcome | **ABSENT** | `grep -rni "onboarding\|settings catalog\|quick setup"` → 0 hits. Settings are the four ad-hoc screens in `MainActivity.kt`. |
| 22 | Signed APK on GitHub Releases, in-app update check, LICENSE + third-party notices | PARTIAL | `LICENSE` in repo root. **Release is signed with the debug key** (`app/build.gradle.kts:61` `signingConfig = signingConfigs.getByName("debug")`). No in-app update check (`grep -rni "check for updates\|updateCheck"` → 0). No third-party notices file (only a mention in `PROJECT_STATE.md`). |

(22 rows counted: the table has 22 lines; §3.1's own count is not numbered.)

## 2. Summary

- **BUILT: 8** — rows 2, 3, 5, 6, 7 (now), 8, 12, 15.
- **PARTIAL: 8** — rows 1, 4, 10, 13, 14, 17, 18, 22.
- **ABSENT: 6** — rows 9, 11, 16, 19, 20, 21.

By area: Data 2/3 built; Memory 3/4 (row 7 closed this session; alias storage still missing);
Intelligence 1/4 (context pack only); Proactive 1/3; Platform 1/3; UI 0/4; Release 0/1.

## 3. Highest-severity gaps (ordered)

1. **Row 19 + 20 + 21 (UI) are entirely absent.** v1 has no onboarding, no memory viewer, and
   no quick capture. The app is a chat screen plus three debug-ish screens.
2. **Row 9 (embeddings/hybrid retrieval) absent.** Retrieval is FTS5 keyword only; §3.1 names
   EmbeddingGemma explicitly.
3. **Row 16 (offline queue / alarm-only mode) absent.** X-08 (airplane-mode day) cannot pass.
4. **Row 13 (nudge engine) exists but is never called.** Dead code that looks like a feature.
5. **Row 22 release is signed with the debug key** — X-09 cannot pass.
6. **Row 1 app lock absent** — the "app lock as a UI gate" half of the row.
7. **Row 7 alias IDs are output-only** — no `alias_map`, so a model echoing `n3` back in
   `target_id` cannot be resolved; `unknown_ref` does not exist.

## 4. Tests that give false confidence

- `NudgeEngineTest` passes against a class no production code constructs. A green suite implies
  nudges work; they do not run.
- `ReliabilityManagerTest` covers the sweeper but nothing asserts a reliability *score*, because
  none is computed.
- No test covers `search_history` end-to-end on device yet (added this session in
  `AndroidStorageTest`, **not yet run** — device offline).

## 5. Blueprint contradictions

- §3.1 row 7 says "alias IDs"; blueprint 23 §4 defines an `alias_map` table and `unknown_ref`
  errors. Neither exists. Either the row shrinks to "output prefixes" or the table must be built.
- §3.1 row 17 says "reliability score"; the column exists but §42's scoring rule has no
  implementation.

## 6. Proposed ordered task list (with the test that proves each)

| Order | Task | Proof |
|---|---|---|
| 1 | Alias map: store `n/s/m/p/r/u` aliases, resolve `target_id` through them, `unknown_ref` on miss | `GatewayTest` case: propose_change with `target_id = "n3"` resolves to the node aliased `n3`; unknown alias → `ToolResult.Error` containing `unknown_ref` |
| 2 | Wire `NudgeEngine` into the proactive path | `NudgeEngine` constructed in `MainViewModel`; integration test asserting a nudge is emitted after the threshold |
| 3 | Onboarding + soul seeding (row 4/21, S-sized slice) | Instrumented test: fresh DB → onboarding completion writes the seeded soul facts |
| 4 | Memory viewer read-only (row 20, S-sized slice) | Compose test: viewer lists nodes/soul facts |
| 5 | Notification reply (row 19) | Instrumented: `RemoteInput` action present on the alarm notification |
| 6 | Offline queue + alarm-only mode (row 16) | Test: send fails offline → message queued → sent on reconnect |
| 7 | Release signing + third-party notices (row 22) | Build check: release APK signed with a non-debug key |

Rows 9 (embeddings) and 11 (visible learning) are L-sized and should be scheduled last or
renegotiated under §3.8.

## 7. Questions for the user

1. Alias IDs: build the `alias_map` (blueprint 23 §4) or amend §3.1 row 7 to "output prefixes only"?
2. Which of the six ABSENT rows is the v1 blocker you want first? My recommendation is the UI
   trio (19/20/21) because v1 cannot be used as a daily driver without onboarding.
3. Row 22: is the debug-key release intentional for now, or should a real signing config land
   before the next device run?
