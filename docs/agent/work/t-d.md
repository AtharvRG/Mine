HISTORICAL: contains unverified or incorrect claims; superseded by the session1 review (see TASKS.md and DECISIONS.md)

# Task T-D: New Edge Type `follow_up` (D-106) Branching Analysis

**Date**: 2026-11-02  
**Task**: T-D (D-106)  
**Module**: `:core` and `:app`  

---

## 1. Goal and Invariant

### Goal
Implement the `follow_up` edge type per Decision D-106:
1. Define `Edge` data class and `EdgeTypes.FOLLOW_UP = "follow_up"` in `:core` (`com.anchor.core.models.Memory.kt`).
2. Add Edge query and transaction methods to `Storage` interface:
   - `saveEdge(edge: Edge)`
   - `getEdge(id: String): Edge?`
   - `getEdgesForSource(srcId: String, type: String? = null): List<Edge>`
   - `getEdgesForDestination(dstId: String, type: String? = null): List<Edge>`
   - `deleteEdge(id: String)` (soft-delete via `deleted_at = ts`)
   - `createEdgeAndCommit(edge: Edge, event: RawEvent)`
   - `deleteEdgeAndCommit(edgeId: String, event: RawEvent)`
3. Implement Edge methods in `AndroidStorage.kt` (real SQLCipher) and `SharedFakeStorage.kt` (unit testing fake).
4. Bump SQLite database version from 2 to 3 in `MineDatabaseHelper.kt`. Add idempotent upgrade logic for v3 creating indexes on `edge(src_id, type)` and `edge(dst_id, type)`.
5. Support `follow_up` in `UiAction` (`UiAction.AddFollowUp`, `UiAction.RemoveFollowUp`) and `ChatController.kt`.
6. Support `follow_up_to` / `follow_up_of` in `Gateway.kt` for linking new or existing nodes with a `follow_up` edge.
7. Add unit tests in `:core` (`EdgeTest.kt`) and instrumented migration tests on real device in `:app` (`AndroidStorageTest.kt`).

### Invariant
1. D-106 is strictly adhered to: edge type is named `follow_up` (not `depends_on`, not overloaded).
2. Existing databases at version 1 and version 2 migrate idempotently to version 3 without data loss.
3. Edge soft-deletes set `deleted_at`; queries default to excluding soft-deleted edges unless specified.
4. Zero `android.*` imports in `:core`.
5. All edge creations and deletions are tracked in `raw_event` for auditability and 24h undo compliance.

---

## 2. Hypothesis Table (4 approaches across layers)

| # | Hypothesis / Approach | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Core Domain Model + Storage Methods + SQLite V3 Index Migration + UiAction)** | Define `Edge` model and `EdgeTypes.FOLLOW_UP` in `:core`, add transactional storage methods, bump DB to v3 with indexes on `(src_id, type)` and `(dst_id, type)`, handle in `ChatController` and `Gateway`. | High query performance, clean domain separation, robust event logging, fast traversal of follow-up dependency chains. | Unit test in `:core` + instrumented migration test on device. | Query regression if indexes cannot be created or column types mismatch. | **CHOSEN** |
| **H2 (Ad-hoc String in Node Attrs JSON without Edge table)** | Store `follow_up_ids: [...]` inside `node.attrs` JSON column instead of using the `edge` relational table. | Avoids touching `edge` table and DB version bump. | Check relational integrity, queries, and graph traversal. | Breaks relational schema; impossible to perform reverse lookups efficiently (`getEdgesForDestination`) without full table scans; violates D-106. | Killed |
| **H3 (Hardcode `depends_on` type and alias to follow_up)** | Use existing `depends_on` edge type under the hood and alias in UI. | Reuses any legacy edge naming. | Check D-106 explicit directive. | D-106 explicitly rejects `depends_on`: "New edge type `follow_up` (not `depends_on`). Represents subsequent follow-up actions between nodes. Reason: Developer directive / UI spec alignment. Rejected: `depends_on` or overloading existing edge types." | Killed |
| **H4 (In-Memory Follow-up Graph Only)** | Keep follow-up links in ViewModel memory without database persistence. | Simplest UI wiring. | Check app restart / process death. | Links lost on process death or sweeper restart; completely violates offline-first invariants. | Killed |

---

## 3. Angles (Mandatory one-line checks)

- **data/schema**: Bump DB to version 3; add `CREATE INDEX IF NOT EXISTS idx_edge_src_type ON edge(src_id, type)` and `idx_edge_dst_type ON edge(dst_id, type)`; idempotent `onUpgrade`.
- **concurrency/lifecycle**: Pure query and atomic transaction methods over `ioDispatcher`; thread-safe.
- **failure paths**: Missing nodes, self-referential edges, soft-deleted edges, and duplicate edges handled cleanly without SQLite crashes.
- **user-visible behaviour**: Nodes linked as follow-ups can be displayed in child/subsequent lists and traversed.
- **privacy/security**: No external network leakage; edges are encrypted inside SQLCipher.
- **testability**: Unit tests in `:core` with `SharedFakeStorage`; instrumented tests on device for V2->V3 migration and real SQLCipher persistence.
- **blast radius**: Purely additive to `Storage`, `Memory.kt`, and `UiAction`. Zero breaking changes to existing call sites.
- **platform (Android 14, OEM)**: Verified on Android 14 test device; SQLCipher encrypted DB operations tested.
- **model behaviour (what a real LLM would send)**: Real LLM can send `follow_up_to` in `propose_change` to link tasks immediately.

---

## 4. Evidence Log

- Inspected `MineDatabaseCallback`: `edge` table created with columns `(id, device_id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at)`.
- Verified no indexes currently exist on `edge` table.
- Verified no `Edge` model currently exists in `:core/src/main/java/com/anchor/core/models/Memory.kt`.
- Verified `Storage` interface currently lacks edge CRUD methods.

---

## 5. Decision

- **Chosen Change**: Implement H1:
  1. Add `Edge` data class and `EdgeTypes.FOLLOW_UP = "follow_up"` to `Memory.kt`.
  2. Add `saveEdge`, `getEdge`, `getEdgesForSource`, `getEdgesForDestination`, `deleteEdge`, `createEdgeAndCommit`, `deleteEdgeAndCommit` to `Storage.kt`.
  3. Implement these methods in `AndroidStorage.kt` and `SharedFakeStorage.kt`.
  4. Bump database version to 3 in `MineDatabaseHelper.kt`, add indexes in `onCreate` and `onUpgrade`.
  5. Add `UiAction.AddFollowUp` and `UiAction.RemoveFollowUp` to `ChatContract.kt`, wired in `ChatController.kt`.
  6. Support `follow_up_to` parameter in `Gateway.handleCreate` to link follow-ups directly during task creation.
  7. Add test suites: `core/.../EdgeTest.kt` and `app/.../AndroidStorageTest.kt` migration test.

---

## 6. Pre-Mortem & Breaking Inputs

1. **Pre-mortem 1**: Upgrading a database from V1 or V2 throws an error when creating the index because old rows have NULL `type`.  
   *Mitigation*: SQLite indexes allow NULL values, and `CREATE INDEX IF NOT EXISTS` is fully non-destructive.
2. **Pre-mortem 2**: `getEdgesForSource` returns soft-deleted edges, causing deleted follow-up relationships to resurface.  
   *Mitigation*: Explicitly add `AND deleted_at IS NULL` to the SQL query `SELECT ... FROM edge WHERE src_id = ? AND (? IS NULL OR type = ?) AND deleted_at IS NULL`.
3. **Pre-mortem 3**: Undo of a follow-up link fails because the event lacks `targetType = "edge"` handling.  
   *Mitigation*: Update `handleUndo` in `Gateway.kt` to handle `targetType == "edge"`.

**Breaking Input Test**:
1. Create a follow-up edge, soft-delete it via `deleteEdge`, query `getEdgesForSource`. Result must be empty.
2. Create edge, undo operation within 24h via `handleUndo`. Edge must be marked deleted or removed.
