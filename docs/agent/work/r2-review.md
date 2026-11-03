# Task R2 Independent Review: Candidate Selection Under Load (100-Item Default Fix)

Date: 2026-11-03  
Task: R2 (Decisions D-104, D-105, D-107, D-108, T-B)  
Reviewers: Adversarial Code Reviewer, Migration Guard, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
Eliminate the silent 100-item default cutoff across all storage reads and candidate selection paths, scaling safely to 5000+ active items. Prevent undated pinned and severity > 0 candidate starvation by future-dated nodes, filter stale nodes in SQL/storage before candidate assembly, ensure exact title duplicate lookup without in-memory scan, and paginate resolver candidate lookups up to 5000 hard-cap with composite keyset cursors.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: None found. R2 operates cleanly on schema v5 without schema modifications. Existing indexes (`idx_node_status_due` on `(status, due_at)`) support range queries for `getNodesDueBetween`, `getOverdueNodes`, and Tier 1/3 candidate ranking.
- **concurrency and cancellation**: None found. Keyset pagination streams sequentially via immutable composite cursors (`${dueAt}:${id}`) without stateful server-side cursors or persistent transaction locks. Read queries run safely concurrently with writes under SQLCipher WAL mode.
- **process death and reboot**: None found. Cursor state is purely caller-local in coroutine memory; no ephemeral uncommitted locks or persistent pagination state exists that could be corrupted by process death.
- **failure paths (network, 429, malformed model output, null args)**: None found. `TooManyActiveNodesException(hardCap = 5000)` is caught in `Gateway.handleResolveChange` and returns explicit, actionable user feedback (`"There are too many active items (5000+). Please be more specific with the item title or due time."`). Malformed cursors (missing colon, unparseable due date) fall back safely to undated ordering without throwing.
- **idempotency (runs twice, already-closed item)**: None found. All added storage methods (`findActiveNodeByTitle`, `getRankedCandidates`, `getNodesDueBetween`, `getOverdueNodes`, `getActiveNodeCounts`) are strictly read-only and idempotent.
- **non-Latin text**: Minor note. `findActiveNodeByTitle` uses SQLite `TRIM(title) = ? COLLATE NOCASE`. Built-in SQLite `NOCASE` only case-folds ASCII `[A-Z]` to `[a-z]`, whereas Kotlin's JVM `equals(ignoreCase = true)` handles full Unicode case folding. For non-Latin titles (or extended accented Latin), SQLite matches on exact case or trimmed exact case. Tokenization in `Resolver` uses Unicode classes `[\p{L}\p{M}\p{N}]+`.
- **main-thread work**: None found. All database queries in `AndroidStorage` execute on `ioDispatcher` (`Dispatchers.IO`), preventing UI thread jank even with 5000 items in the database.
- **alarm and notification correctness**: None found. Checkin morning agenda facts use strict logical day partitioning (`getNodesDueBetween(logicalTodayStart, logicalTodayEnd)` and `getOverdueNodes(logicalTodayStart)`), guaranteeing exact reminder and agenda item counts without leaking tomorrow's or yesterday's tasks.
- **privacy (secrets in logs, local-only data)**: None found. Candidate selection and counts run entirely in local SQLCipher storage; only the final budget-capped context pack (top 25 items, ~700 tokens) is exposed to the local model.
- **blast radius (what else uses this code)**: None found. Replaced all legacy `getActiveNodes(100)` invocations across `Gateway`, `ContextBuilder`, and `CheckinBuilder` with specialized targeted queries. Call sites in tests were audited and updated to specify explicit limits.

### Project Rules Verification
- **LLM proposes / code decides**: Upheld. Code strictly determines candidate ranking, staleness filtering, and pagination; model receives only the deterministically ranked, budget-capped pack.
- **Nothing hard-deleted**: Upheld. Soft status transitions and queries remain strictly read-only.
- **Dates resolved in code**: Upheld. Day boundaries (`logicalTodayStart`, `logicalTodayEnd`) are resolved in `CheckinBuilder` using `DateResolver` and user day rollover minutes.
- **Append-only raw tables**: Upheld. No raw tables modified.
- **SPDX headers**: Upheld on all new and edited files (`Storage.kt`, `AndroidStorage.kt`, `SharedFakeStorage.kt`, `R2CandidateSelectionTest.kt`).
- **No vibration**: Upheld.
- **No new dependencies**: Upheld. Zero new Gradle dependencies added.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: SQLite `COLLATE NOCASE` in `findActiveNodeByTitle` handles ASCII case-insensitivity; non-ASCII case variants require exact case match in SQLite compared to Unicode `equals(ignoreCase=true)` in JVM fake storage. (Standard SQLite behavior on Android).

### Tests
- `testR2_1_Resolver_300ActiveNodes_FindsNode250` (fails-before: yes)
- `testR2_2_DuplicateTitle_FoundWhen500NodesExist` (fails-before: yes)
- `testR2_3_ContextBuilder_150FutureNodesAndPinnedAndSeverity3_BothAppearInPack` (fails-before: yes)
- `testR2_4_ContextBuilder_100StaleOverdueAnd30Fresh_PackShowsFreshAndCorrectCount` (fails-before: yes)
- `testR2_5_CheckinBuilder_200Nodes_AccurateAgendaCounts` (fails-before: yes)
- `testR2_6_StorageContract_AndroidStorageAndSharedFakeStorageParity` (fails-before: yes)
- `testR2_7_Resolver_5000HardCapExceeded_ThrowsAndExplainsToUser` (fails-before: yes)
- `testR2_StorageContract_AndroidStorage` (instrumented androidTest on device: PASS)

**VERDICT**: APPROVE

---

## 2. Migration Guard Report

### Schema Verification Matrix

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. DB Version | PASS | `MineDatabaseHelper.kt` | Stays at version 5. No DDL change required. |
| 2. Index Utilization | PASS | `MineDatabaseHelper.kt:42-45` | `idx_node_status_due` on `node(status, due_at)` utilized by `getNodesDueBetween`, `getOverdueNodes`, and Tier 1/3 ranking. |
| 3. Raw Triggers Intact | PASS | `MineDatabaseHelper.kt:107-131` | Append-only triggers on `raw_message` and `raw_event` unmodified. |
| 4. Node Insertion Safety | PASS | `AndroidStorage.kt` | Plain `INSERT INTO node` preserved. |
| 5. Query Parameterization | PASS | `AndroidStorage.kt:125-242` | All limits, cutoffs, timestamps, and title filters parameterized via `?` bindings. |

---

## 3. Test Auditor Report

### Suite Health
- `./gradlew :core:test`: 84 tests executed, 0 failures, 0 skipped, 100% successful.
- `:app:compileDebugAndroidTestKotlin`: SUCCESS, compiles all 25 instrumented test methods across 6 files.
- Connected Android Instrumentation Test (`testR2_StorageContract_AndroidStorage`): Verified on physical device (moto g73 5G, Android 14); all 25 tests passed cleanly (`OK (25 tests)`, 0 failures, 0 errors).
- Red test baseline verified: Prior to R2 implementation, `R2CandidateSelectionTest` failed on node #250 resolution, candidate pack starvation, and count calculations.
