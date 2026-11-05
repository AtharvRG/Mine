# Test and Build Verification Evidence

This directory stores verification evidence files, command transcripts, and hardware validation records.

## Ground Truth Test Counts (Post-R12d)
- **`:core:test`**: **197 tests**, 0 failures, 100% pass (transcription in `core-test.txt`).
- **`:app:testDebugUnitTest`**: **9 tests**, 0 failures, 100% pass (transcription in `app-testDebugUnitTest.txt`).
- **`androidTest` (Connected on physical `moto g73 5G` - Android 14 / API 34)**: **49 tests**, 0 failures, 100% pass (transcriptions in `connected-tests.txt` and `connected-tests-runner.txt`).
- **Total Project Tests**: **255 tests**, 0 failures, 100% pass.

## Automated Test Verification Summary

### Suite 1: `:core:test` (Unit and Architecture Verification)
- **Command**: `./gradlew :core:test --rerun-tasks --info`
- **Result**: BUILD SUCCESSFUL (197 tests, 0 failures, 100% pass)
- **Evidence File**: `docs/agent/evidence/core-test.txt`
- **Key Test Classes**:
  - `R12ConcurrencyLifecycleTest`: In-transaction CAS, guarded rollback (`reopenProposal`), crash-window rollback, 24h expiration parity, loser UI suppression.
  - `ChatControllerEdgeCaseTest`: Deterministic synchronization via `waitUntil`, stream interruption, alarm scheduling error exposure, soul facts lifecycle, multi-receipt mixed envelopes.
  - `GatewayTest`: Auto-commit confidence gating, exact-match preference, past time rejection, undo tokens, proposal lifecycle.
  - `R4TimeChipsTest`: Time disambiguation chips, pre-validation for missing title, duplicate title deduplication bypass for auto-titled items.
  - `R1RescheduleAmbiguityTest`: Fast-fail RESCHEDULE without time, proposal payload column, tier preservation on reschedule.
  - `DateResolverTest`: Compound duration parsing, bounds checking (<=366 days, <=100,000 units), multi-day chip labels, weekday parsing.
  - `ContextBuilderTest`: Candidate selection tier hierarchy (overdue, in_progress, 48h, pinned, severity 3, recency), stale item exclusion, context token budgeting.
  - `AlarmReconcilerTest`: Force and incremental reconciliation, tier awareness, orphan cleanup, bounded issuesJson.

### Suite 2: `:app:testDebugUnitTest` (App Unit Verification)
- **Command**: `./gradlew :app:testDebugUnitTest --rerun-tasks --info`
- **Result**: BUILD SUCCESSFUL (9 tests, 0 failures, 100% pass)
- **Evidence File**: `docs/agent/evidence/app-testDebugUnitTest.txt`
- **Key Test Classes**:
  - `ChunkedAesStreamTest` (4 tests): Stream encryption, chunk integrity, tampering rejection.
  - `QuoteAlarmSchedulerTest` (5 tests): Slot boundary calculations, prepare-ahead triggers.

### Suite 3: Connected Physical Device Tests (Clean Install)
- **Target Device**: Motorola moto g73 5G (Android 14 / API 34)
- **Commands**:
  - Uninstall: `./gradlew :app:uninstallDebug :app:uninstallDebugAndroidTest`
  - Connected test run: `adb shell am instrument -r -w com.anchor.mine.test/androidx.test.runner.AndroidJUnitRunner`
- **Result**: 49/49 passed across 9 test classes (100% success rate in 96.954s)
- **Evidence Files**:
  - `docs/agent/evidence/connected-tests.txt` (Full Gradle execution transcript)
  - `docs/agent/evidence/connected-tests-runner.txt` (Complete `AndroidJUnitRunner` log with `OK (49 tests)`)
  - `docs/agent/evidence/security-exception.txt` (Android 14 exact alarm SecurityException reproduction)
- **Test Breakdown (49 tests)**:
  - `AlarmReceiverTest`: 3 passed
  - `AlarmSelfTest`: 3 passed
  - `BootReceiverTest`: 1 passed
  - `ManifestPermissionTest`: 1 passed
  - `ExportTest`: 6 passed
  - `QuoteAlarmReceiverTest`: 2 passed
  - `AndroidStorageTest`: 30 passed (including `testInTransactionCas_RealSqlite_AbortOnAlreadyConsumed`, `testReopenProposal_RealSqlite_GuardedSemantics`, `testConcurrentConfirm_RealSqlite_SingleWinnerSemantics`, `testExpiryParity_MissingCreatedAtReturnsNull_RealSqlite`)
  - `DatabaseMigrationTest`: 1 passed (fresh install smoke test covering 100% Storage methods)
  - `StorageTest`: 2 passed
- **Artifacts**:
  - `app/build/reports/androidTests/connected/debug/index.html`
  - `app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml`
