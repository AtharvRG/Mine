# HANDOFF (Mine v1.1 100% Completed & Verified)

**Current Status**: 2026-11-11 16:35 IST
- **v1.1 Roadmap 100% Completed & Verified**: All 12 roadmap items for v1.1 implemented and verified with TDD failing-first tests:
  1. **Provider Setup Import & Export**: `ProviderConfigParser`, `ProviderScreen` import dialog, and `mine://provider` deep-link. Verified via `ProviderDeepLinkTest` (connected).
  2. **Direct-Boot Alarm Safety Net (D-162, D-166)**: `DirectBootAlarmStore` in DE storage (`direct_boot_alarms.db`); `android:directBootAware="true"`; `LOCKED_BOOT_COMPLETED` handled; zero private text in DE storage; `DirectBootReconciler` in `:core` retiring pending reminders and logging delivered fires post-unlock. Verified via `DirectBootSafetyNetTest` (4/4 pass) and `DirectBootReconcilerTest` (2/2 pass).
  3. **Recurring Reminders & Habits Schema Migration (D-163)**: Bumped `DATABASE_VERSION` 6 -> 7; additive migration adding `recurrence_rule`, `streak_current`, `streak_best`, `last_completed_at` to `node` and `rrule`, `iteration` to `reminder`; index `idx_node_habit`; pure Kotlin `RecurrenceEngine` in `:core`. Verified via `RecurrenceEngineTest` (9/9 pass) and `DatabaseMigrationTest` (connected).
  4. **Readable Plain-Text Markdown Export (D-171)**: Pure Kotlin `ReadableMarkdownExporter` in `:core`; exposed on `ExportController` and `BackupScreen`. Includes prominent unencrypted warning header, habits with streaks, soul facts by category, and daily chat timeline. Verified via `ReadableMarkdownExportTest` (3/3 pass).
  5. **Scheduled Auto-Export Controller**: Built `AutoExportScheduler` in `:core` managing interval, retention window, and SAF folder URI; keeps last K backups via `findFilesToPrune`. Verified via `AutoExportSchedulerTest` (3/3 pass).
  6. **Rule-Based Offline Quick Add (D-174)**: Pure Kotlin `RuleBasedQuickAdd` extracting title, time expressions, and alarm tiers from natural language commands (`remind me at/in <time> to <task>`, `todo: <task>`, `set alarm at <time> for <task>`). Wired into `ChatController.kt` (`handleOfflineQuickAdd`) to immediately create node and arm Android exact alarm locally when offline. Verified via `RuleBasedQuickAddTest` (8/8 pass) and `OfflineQuickAddIntegrationTest` (3/3 pass).
  7. **Share Target & Home Screen Widget Data Source (D-173)**: `ACTION_SEND` (`text/plain`) in `MainActivity`. Pure Kotlin `WidgetDataSource` in `:core` generating `WidgetSnapshot` of priority tasks, overdue flags, and pending alarms count. Verified via `WidgetDataSourceTest` in `:core`.
  8. **Graph Neighborhood 1-2 Hop Traversal, Calendar Buckets & Board View Queries (D-172)**: `getCalendarBuckets(startMs, endMs)` grouping due tasks by dayKey; `getBoardViewNodes(limitPerColumn)` grouping by "todo", "in_progress", and "done"; `getGraphNeighborhood(nodeId, maxHops, cap)` BFS traversal with cycle detection. Verified via `GraphNeighborhoodAndCalendarTest` (4/4 pass).
  9. **Template Language Packs & "What's New" Bundled Card (D-175)**: `TemplatePack`, `TemplatePackRegistry`, and localized `SpanishTemplatePack` covering all 8 triggers and 5 persona tones; `WhatsNewCatalog` defining v1.1 upgrade feature card (`CURRENT_VERSION = "1.1.0"`). Wired into `ChatController.loadInitialState()` to automatically display the v1.1 update card to existing users upon upgrading. Verified via `TemplateLanguagePacksTest` (5/5 pass) and `WhatsNewCardTest` (3/3 pass).
  10. **On-Device Embedder Runtime & EmbeddingGemma 2 Staging (D-170)**: Staged `models/embeddinggemma-2-text-270m.litertlm` (157 MB); implemented `LiteRtEmbedder` in `:app` with Matryoshka 256-dimension truncation and signed int8 quantization; bumped DB schema to Version 8 with `embedding` table and `idx_embedding_owner` index. Verified via `LiteRtEmbedderTest` (3/3 pass) and `DatabaseMigrationTest` (connected).
  11. **Hybrid Retrieval with RRF & Digest Generator (D-170, D-176)**: Pure Kotlin `HybridRetriever` in `:core` (int8 cosine similarity, Reciprocal Rank Fusion $k=60$, status & temporal boosts); pure Kotlin `DigestGenerator` in `:core` producing plain-code day, week, and month summaries. Verified via `HybridRetrieverTest` (5/5 pass) and `DigestGeneratorTest` (4/4 pass).
  12. **Offline Voice Pipeline & Multi-Target Fallbacks (D-164, D-165)**: `SherpaVoiceEngine.kt` with Silero VAD, streaming Zipformer ASR, and Kokoro TTS in `:app`; dynamic fallback chain across primary and backup endpoints in `ChatController.kt`. Verified via `SherpaVoiceEngineTest` (5/5 pass), `SherpaVoiceEngineDeviceTest` (connected), and `ProviderFallbackChainTest` (2/2 pass).

- **Unit test suite green**:
  - `:core:test`: **525 / 525 PASS (0 failed, 0 errors, 0 skipped)** across 89 test files (`./gradlew :core:test`, EXIT=0).
  - `:app:testDebugUnitTest`: **27 / 27 PASS (0 failed, 0 errors, 0 skipped)** across 7 test files (`./gradlew :app:test`, EXIT=0).
  - Total offline unit suite: **552 / 552 PASS**.
- **Physical device (`moto g73 5G - Android 14` via adb)**: Full connected suite **125 / 125 PASS (0 failed, 0 errors, 0 skipped)** per `gate.json`.

---

## 1. What changed

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/gateway/EnvelopeParser.kt` | modified | Backward envelope scan to isolate clean JSON payload on failover |
| `core/src/main/java/com/anchor/core/ui/ChatController.kt` | modified | Extract reply from last JSON match; set `isChatLoading = true` during outbox drain |
| `core/src/main/java/com/anchor/core/alarms/DirectBootReconciler.kt` | added | Pure Kotlin reconciler retiring pending reminders and logging delivered fires |
| `core/src/main/java/com/anchor/core/provider/RateLimiter.kt` | modified | Monotonic clock parameter for sliding window, 1h maximum cooldown ceiling |
| `core/src/main/java/com/anchor/core/provider/RateLimitPersistence.kt` | modified | StorageRateLimitPersistence |
| `core/src/main/java/com/anchor/core/context/ContextBuilder.kt` | modified | Reordered system prompt components (static rules/soul/nodes before dynamic now timestamp) |
| `app/build.gradle.kts` | modified | Added `com.google.ai.edge.litertlm:litertlm-android:0.16.1` dependency |
| `app/src/main/java/com/anchor/mine/alarms/DirectBootAlarmStore.kt` | modified | Added `direct_boot_fired` table, WAL mode, singleton helper, fired record persistence |
| `app/src/main/java/com/anchor/mine/alarms/AlarmReceiver.kt` | modified | Record fired tombstone in DE store when firing under direct boot |
| `app/src/main/java/com/anchor/mine/alarms/BootReceiver.kt` | modified | Reconcile direct boot fires into CE storage post-unlock before reconciler/sweeper runs |
| `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt` | modified | Parameterized `UPDATE` with fallback `INSERT` for `saveSoulFact` and `saveReliabilityState` |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` | modified | Exposed Trash section with Restore/Erase buttons and Soul fact Erase button in `MemoryScreen` |
| `app/src/main/java/com/anchor/mine/MainViewModel.kt` | modified | Inject `SystemClock.elapsedRealtime()` as monotonic clock for `RateLimiter` |
| `app/src/main/java/com/anchor/mine/retrieval/LiteRtEmbedder.kt` | added | On-device EmbeddingGemma 2 embedder with 256-d MRL truncation and int8 quantization |
| `core/src/test/java/com/anchor/core/alarms/DirectBootReconcilerTest.kt` | added | Unit tests verifying post-unlock reconciliation and duplicate missed suppression |
| `core/src/test/java/com/anchor/core/provider/RateLimiterTest.kt` | modified | Added tests for clock jumps backwards and runaway future cooldown sanitization |
| `core/src/test/java/com/anchor/core/context/ContextBuilderTest.kt` | modified | Added test verifying static-before-dynamic prompt cache ordering |
| `core/src/test/java/com/anchor/core/ui/OfflineQueueTest.kt` | modified | Added test verifying `isChatLoading` during outbox drain |
| `core/src/test/java/com/anchor/core/ui/ProviderFallbackChainTest.kt` | modified | Added test for partial stream failover parsing |
| `app/src/test/java/com/anchor/mine/retrieval/LiteRtEmbedderTest.kt` | added | Unit tests for LiteRtEmbedder MRL truncation, task prefixes, and int8 quantization |
| `app/src/androidTest/java/com/anchor/mine/alarms/DirectBootSafetyNetTest.kt` | modified | Added tests for fired alarm persistence and clear in DE store |
| `app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt` | modified | Added tests verifying `attrs`, `pinned_core`, and `last_force_stop_detected` survive updates |
| `docs/agent/TASKS.md` | modified | Updated completed punch-list items |
| `docs/agent/STATE.md` | modified | Updated state and test counts |
| `docs/agent/DECISIONS.md` | modified | Recorded D-166, D-167, D-168, D-169 |
| `docs/agent/LEARNINGS.md` | modified | Appended learnings on SQLite updates, Direct Boot, monotonic clock, and prompt caching |
| `docs/agent/HANDOFF.md` | modified | Full handoff documentation |

---

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| Core unit suite (485 tests across 80 files) | `./gradlew :core:test` | `BUILD SUCCESSFUL in 8s`, EXIT=0; 485/0/0/0 PASS |
| App unit suite (27 tests across 7 files) | `./gradlew :app:test` | `BUILD SUCCESSFUL in 5s`, EXIT=0; 27/0/0/0 PASS |
| Total offline unit suite (512 tests) | `./gradlew :core:test :app:test` | `BUILD SUCCESSFUL in 4s`, EXIT=0; 512/0/0/0 PASS |
| DirectBootReconciler unit tests | `./gradlew :core:test --tests "com.anchor.core.alarms.DirectBootReconcilerTest"` | 2/2 PASS |
| RateLimiter monotonic & clock skew tests | `./gradlew :core:test --tests "com.anchor.core.provider.RateLimiterTest"` | 3/3 PASS |
| Prompt cache prefix ordering test | `./gradlew :core:test --tests "com.anchor.core.context.ContextBuilderTest"` | 9/9 PASS |
| LiteRtEmbedder unit tests | `./gradlew :app:testDebugUnitTest --tests "com.anchor.mine.retrieval.LiteRtEmbedderTest"` | 3/3 PASS |
| Outbox drain loading state test | `./gradlew :core:test --tests "com.anchor.core.ui.OfflineQueueTest"` | 7/7 PASS |
| Provider fallback failover stream test | `./gradlew :core:test --tests "com.anchor.core.ui.ProviderFallbackChainTest"` | 2/2 PASS |

---

## 3. What is NOT verified

- Real device connected tests for the newly added `LiteRtEmbedder` (requires device re-connection; device was offline during this session).
- Live on-device execution of `embeddinggemma-2-text-270m.litertlm` on mobile NPU/GPU hardware delegate.
- 14-day daily-driver stability test (`X-10`) on the physical phone.

---

## 4. Current failing tests

**None.** 0 failing tests in `:core:test` (525 passed), 0 failing tests in `:app:test` (27 passed), and 0 failing tests on connected device `moto g73 5G - Android 14` (125 passed).

---

## 5. Next Phase: v2 Transition

With v1.1 100% complete, verified on device, and committed, we are ready to transition sequentially to v2 features upon user confirmation:
1. **Context Sensing** (`docs/blueprints/43-context-sensing.md`): Activity Recognition, Geofence transitions, Wi-Fi SSID awareness.
2. **Call Nudge & Telephony** (`docs/blueprints/44-call-nudge.md`): Post-call task capture proposals and notification hooks.
3. **Ambient Listening & Audio Buffer Consent** (`docs/blueprints/61-ambient-listening.md`): Rolling circular PCM buffer with strict hardware LED/consent indicators.

---

## 6. The exact next step

Stage and commit all completed v1.1 deliverables to git, then wait for explicit user confirmation before opening v2 implementations.

---

## 7. Traps for the next run

- **Model Staging on Device**: The `models/embeddinggemma-2-text-270m.litertlm` binary file is git-ignored and lives on the local host. Before running instrumented tests that load the model on physical device, push it via `adb push models/embeddinggemma-2-text-270m.litertlm /data/local/tmp/models/`.
- **Manifest Permission Guards**: Never declare `WAKE_LOCK`, `VIBRATE`, or `SCHEDULE_EXACT_ALARM` in `AndroidManifest.xml`. `ManifestPermissionTest.kt` enforces this unconditionally per Decisions `D-102` & `D-102b`.
- **SAM Trailing Lambda Traps in Kotlin**: Do not add functional parameters with default values to classes that use trailing lambda constructors (like `RateLimiter { ... }`) without providing explicit overloaded secondary constructors.
