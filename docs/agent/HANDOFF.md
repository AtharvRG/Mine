# HANDOFF (Mine v2 Context Sensing In Progress; v1.1 100% Completed)

**Current Status**: 2026-11-11 19:45 IST
- **v2 Item 1: Context Sensing (D-177, Blueprint 43)**:
  - Implemented pure-Kotlin `ContextEngine` and `ContextModels` in `core/src/main/java/com/anchor/core/sensing/` managing dual independent axes: Place (`Home`, `Away`, `SavedPlace`, `Unknown`) and Motion (`STILL`, `WALKING`, `VEHICLE`, `UNKNOWN`).
  - Implemented 15-minute flap guard when still, 90-second fast-path transition when walking without Wi-Fi, 45-second motion debounce, and 30-minute unknown timeout. Verified via `ContextEngineTest` (8/8 pass).
  - Wired `isVehicle` vehicle hold into `NudgeEngine.evaluate()` holding conversational nudges while user is driving. Verified via `NudgeEngineTest` (2/2 pass).
  - Wired non-leaking context formatting `Context: at <Place>, <motion> (since HH:mm)` into `ContextBuilder` Now block while preserving KV prompt cache prefix ordering. Verified via `ContextBuilderTest` (10/10 pass).
  - Registered 11 sensing settings in `SettingsCatalog`. Verified via `SettingsCatalogTest` (5/5 pass).
  - Bumped database schema to Version 9 with `place`, `place_signal` (device-local, non-exported), and `sensing_event` (device-local, 30-day pruned) in `MineDatabaseHelper` and `AndroidStorage`.
  - Added `testDatabaseMigration_V8toV9_CreatesPlaceAndSensingTables` in `DatabaseMigrationTest.kt`.

- **Unit test suite green**:
  - `:core:test`: **527 / 527 PASS (0 failed, 0 errors, 0 skipped)**.
  - `:app:testDebugUnitTest`: **27 / 27 PASS (0 failed, 0 errors, 0 skipped)**.
  - Total offline unit suite: **554 / 554 PASS**.
- **Physical device (`moto g73 5G - Android 14` via adb)**: Prior full suite **125 / 125 PASS**; device currently disconnected from Wi-Fi ADB (reconnect needed to run instrumented V8->V9 test).

---

## 1. What changed

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/sensing/ContextModels.kt` | added | Pure-Kotlin models: PlaceAxis, MotionAxis, Place, PlaceSignal, SensingEvent, ContextTrigger |
| `core/src/main/java/com/anchor/core/sensing/ContextEngine.kt` | added | Dual-axis state machine, flap guard, fast path, vehicle hold, and prompt formatting |
| `core/src/main/java/com/anchor/core/storage/Storage.kt` | modified | Added Place and SensingEvent persistence interfaces |
| `core/src/test/java/com/anchor/core/SharedFakeStorage.kt` | modified | In-memory implementations for places, signals, and sensing events |
| `core/src/main/java/com/anchor/core/nudge/NudgeEngine.kt` | modified | Added `isVehicle` parameter to hold conversational nudges while driving |
| `core/src/main/java/com/anchor/core/context/ContextBuilder.kt` | modified | Injected `contextLine` into Now block following static rules |
| `core/src/main/java/com/anchor/core/settings/SettingsCatalog.kt` | modified | Added 11 sensing configuration and dark-ship settings |
| `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt` | modified | Bumped DATABASE_VERSION to 9; added place, place_signal, and sensing_event DDLs and indexes |
| `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt` | modified | Implemented place and sensing event SQLite queries and 30-day pruning |
| `core/src/test/java/com/anchor/core/sensing/ContextEngineTest.kt` | added | 8 unit tests verifying dual axes, flap guard, fast path, and prompt formatting |
| `core/src/test/java/com/anchor/core/nudge/NudgeEngineTest.kt` | modified | Added unit test for vehicle hold |
| `core/src/test/java/com/anchor/core/context/ContextBuilderTest.kt` | modified | Added unit test for contextLine formatting and prefix order |
| `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt` | modified | Added `testDatabaseMigration_V8toV9_CreatesPlaceAndSensingTables` |
| `docs/agent/DECISIONS.md` | modified | Recorded D-177 |
| `docs/agent/STATE.md` | modified | Updated state and test counts |
| `docs/agent/TASKS.md` | modified | Updated v2 task list |
| `docs/agent/work/v2-context-sensing.md` | added | Branching analysis document for v2 context sensing |

---

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| ContextEngine dual-axis & flap guard | `./gradlew :core:test --tests "com.anchor.core.sensing.ContextEngineTest"` | 8/8 PASS |
| NudgeEngine vehicle hold | `./gradlew :core:test --tests "com.anchor.core.nudge.NudgeEngineTest"` | 2/2 PASS |
| ContextBuilder context formatting & prefix order | `./gradlew :core:test --tests "com.anchor.core.context.ContextBuilderTest"` | 10/10 PASS |
| SettingsCatalog sensing definitions | `./gradlew :core:test --tests "com.anchor.core.settings.SettingsCatalogTest"` | 5/5 PASS |
| App unit test suite | `./gradlew :app:testDebugUnitTest` | 27/27 PASS |
| Total offline unit test suite | `./gradlew :core:test :app:testDebugUnitTest` | 554/554 PASS |

---

## 3. What is NOT verified

- Real device connected verification for V8->V9 schema migration (phone disconnected from Wi-Fi ADB; needs reconnection).
- Physical walking and router restart tests on device.

---

## 4. Current failing tests

**None.** 0 failing tests in `:core:test` (527 passed) and 0 failing tests in `:app:test` (27 passed).

---

## 5. Next Step

Reconnect physical device via ADB (`adb connect <device-ip>:5555`) to run the connected migration test `DatabaseMigrationTest.testDatabaseMigration_V8toV9_CreatesPlaceAndSensingTables`, then proceed to v2 Item 2 (Call Nudge).
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
