# Task R10c Independent Review: follow_up Edge Validation, Atomic Transactions & Undo

Date: 2026-11-04  
Task: R10c (Decisions D-106, session1-remediation-spec.md line 307)  
Reviewers: Adversarial Code Reviewer, Migration Guard, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
Validate source and destination existence for follow-up links (returning Error and creating nothing on missing reference); atomically create node + reminder + edge in a single database transaction; soft-delete connected edges on node CREATE undo; validate AddFollowUp and RemoveFollowUp (no self-loops, no duplicates, depth-bounded cycle detection with max depth 20); and generate reversible receipts with valid undo tokens for edge additions and removals.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: None found. Schema v5 already defines the `edge` table with columns `(id, src_id, dst_id, type, attrs, created_at, updated_at, deleted_at)`. No migration required.
- **concurrency and cancellation**: None found. All edge creations and soft-deletions inside `createNodeAndCommit` and `updateNodeAndCommit` occur within atomic SQLite transactions (`db.beginTransaction() ... db.setTransactionSuccessful()`).
- **process death and reboot**: None found. All operations are persisted to SQLite and logged as `RawEvent` with reversible undo tokens. Process death recovers state cleanly on restart.
- **failure paths (network, 429, malformed model output, null args)**: None found. Missing source or destination returns clear `ToolResult.Error` before mutating state. Cycle check prevents runaway graph recursion with `maxDepth = 20`.
- **idempotency (runs twice, already-closed item)**: None found. Duplicate edge check verifies if an active edge with identical source, destination, and type already exists, returning an explicit error.
- **non-Latin text**: None found. Edge ids, node ids, and titles are handled without Unicode truncation or corruption.
- **main-thread work**: None found. All gateway and storage edge operations run on `ioDispatcher` or `defaultDispatcher`.
- **alarm and notification correctness**: None found. Reminders for created follow-up tasks are committed atomically with node and edge. Undo cleanly cascades to both reminder and connected edges.
- **privacy (secrets in logs, local-only data)**: None found. Edge operations do not leak personal data outside encrypted SQLCipher database.
- **blast radius (what else uses this code)**: None found. Edge soft-deletes only affect edges connected to the undone or unlinked item.

### Project Rules Verification
- **LLM proposes / code decides**: Upheld. Code strictly validates source/destination existence, self-loops, duplicates, and cycles.
- **Nothing hard-deleted**: Upheld. Edges are soft-deleted via `deleted_at = tsUtc`.
- **Append-only raw tables**: Upheld. `raw_event` logs `LINK`, `UNLINK`, and `UNDO_UNLINK` events without modifying past rows.
- **SPDX headers**: Upheld on all touched files.
- **No vibration**: Upheld.
- **No new dependencies**: Upheld.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: None.

### Tests
- `testCreateFollowUp_FailsIfSourceNodeNotFound_CreatesNothing`: PASS
- `testCreateFollowUp_CreatesNodeReminderEdgeInSingleTransaction`: PASS
- `testUndoCreate_SoftDeletesFollowUpEdge`: PASS
- `testAddFollowUp_RejectsMissingSourceOrDestination`: PASS
- `testAddFollowUp_RejectsSelfLoop`: PASS
- `testAddFollowUp_RejectsDuplicateEdge`: PASS
- `testAddFollowUp_RejectsCyclicDependency_DepthBounded`: PASS
- `testRemoveFollowUp_ProducesUndoableReceipt_UndoRestoresEdge`: PASS

**VERDICT**: APPROVE

---

## 2. Migration Guard Report

### Schema Verification Matrix

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. DB Version | PASS | `MineDatabaseHelper.kt` | Schema v5 includes `edge` table. No DDL change needed. |
| 2. Transaction Integrity | PASS | `AndroidStorage.kt:314-367` | `createNodeAndCommit` inserts node, reminder, edge, and edgeEvent in one `beginTransaction()`. |
| 3. Cascade Soft-Delete | PASS | `AndroidStorage.kt:370-422` | `updateNodeAndCommit` executes soft-delete for `softDeleteEdgeIds` inside transaction. |
| 4. Raw Triggers Intact | PASS | `MineDatabaseHelper.kt` | Triggers on `raw_event` and `raw_message` unmodified. |
| 5. Query Parameterization | PASS | `AndroidStorage.kt` | All edge updates and inserts parameterized via `?` bindings. |

---

## 3. Test Auditor Report

### Suite Health
- `./gradlew :core:test`: 100% successful across all suites.
- `R10cFollowUpTest`: 8/8 tests passed cleanly.
- `./gradlew :app:compileDebugAndroidTestKotlin`: SUCCESS.
