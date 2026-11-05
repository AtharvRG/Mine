# Task R10d Independent Review: getStaleNodes Required Injected now Parameter

Date: 2026-11-04  
Task: R10d (session1-remediation-spec.md line 310)  
Reviewers: Adversarial Code Reviewer, Migration Guard, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
Ensure that `Storage.getStaleNodes` strictly requires an injected `now: Long` timestamp with no default value falling back to `System.currentTimeMillis()`. The method signature must place `now: Long` first (required) and `cutoffMs: Long = 30L * 24 * 3600 * 1000L` second (optional with 30-day default), matching sister query `getActiveNodeCounts(now, staleCutoffMs)`. Callers in `ChatController`, `ContextBuilderTest`, and Android tests must pass the injected clock timestamp. Stray calls to `System.currentTimeMillis()` in `SharedFakeStorage` must also be replaced with `clock.now()`.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: None found. No SQL schema changes; SQLite query logic in `AndroidStorage.kt` remains identical with parameterized cutoff and now bounds.
- **concurrency and cancellation**: None found. `now` parameter is immutable and passed thread-safely across coroutines.
- **process death and reboot**: None found. Timestamps are supplied by caller clock at evaluation time.
- **failure paths (network, 429, malformed model output, null args)**: None found. `now: Long` is primitive non-null, enforced at compile time.
- **idempotency (runs twice, already-closed item)**: None found. Read-only query; idempotent for identical `now` and `cutoffMs`.
- **non-Latin text**: N/A. Node filtering operates on numeric timestamps and status strings.
- **main-thread work**: None found. `AndroidStorage.getStaleNodes` executes on `ioDispatcher`.
- **alarm and notification correctness**: N/A. Does not mutate alarms or reminders.
- **privacy (secrets in logs, local-only data)**: None found. No sensitive data logged or leaked.
- **blast radius (what else uses this code)**: All call sites audited: `ChatController.kt`, `ContextBuilderTest.kt`, `AndroidStorageTest.kt`, `DatabaseMigrationTest.kt`. All updated to supply `now` first.

### Project Rules Verification
- **Injected clock rule (CLAUDE.md Rules 4 & 5)**: Upheld. `now: Long` is strictly required; no reliance on `System.currentTimeMillis()` in core or test storage.
- **Append-only raw tables**: Upheld. Query is read-only.
- **SPDX headers**: Upheld on all touched files.
- **No new dependencies**: Upheld.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: None.

### Tests
- `testGetStaleNodes_RequiresNowAndDefaultsTo30Days`: PASS
- `testGetStaleNodes_PositionalCallRequiresNowFirst`: PASS
- `testD104_StaleNodes_MoveToInbox`: PASS
- `testFreshV5SmokeTest_RunsEveryStorageMethod`: PASS

**VERDICT**: APPROVE

---

## 2. Migration Guard Report

### Schema Verification Matrix

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. DB Version | PASS | `MineDatabaseHelper.kt` | Schema version unchanged (v5). |
| 2. Parameter Order | PASS | `Storage.kt:56` | `now: Long` first, `cutoffMs: Long = 30L * 24 * 3600 * 1000L` second. |
| 3. SQL Query Safety | PASS | `AndroidStorage.kt:397-401` | Query uses parameterized `arrayOf(cutoff.toString(), now.toString())`. |

**VERDICT**: APPROVE

---

## 3. Test Auditor Report

- Unit tests in `:core:test`: 100% PASS (including `StaleNodesClockTest`, `ContextBuilderTest`).
- Unit tests in `:app:testDebugUnitTest`: 100% PASS.
- Build time: 3s with Gradle configuration cache reused.

**VERDICT**: APPROVE
