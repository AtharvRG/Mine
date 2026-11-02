HISTORICAL: contains unverified or incorrect claims; superseded by the session1 review (see TASKS.md and DECISIONS.md)

# Task T-B: ContextBuilder Memory Paging & Token Budget (D-104) Branching Analysis

**Date**: 2026-11-02  
**Task**: T-B (D-104)  
**Module**: `:core` and `:app`  

---

## 1. Goal and Invariant

### Goal
Implement bounded memory paging and deterministic token budgeting for active items in `ContextBuilder` per Decision D-104:
1. `Storage.getActiveNodes` becomes `getActiveNodes(limit: Int = 100, cursor: String? = null, order: String = "ASC")` using the existing SQLite index on `(status, due_at)`.
2. `ContextBuilder` never loads all open nodes. It scores candidates in code using the deterministic priority: `overdue > in progress > due within 48 h > pinned > severity > recency`.
3. Bounded to K items (K=25 default, capped at Blueprint 20's 700 token budget).
4. Always appends the summary count line: `"N more open items not shown (M overdue)"`.
5. Stale items (no activity for > 30 days) leave the active context and appear in Inbox as "stale" via `storage.getStaleNodes()`.
6. Cursor pagination has no gaps or duplicates across 500 and 5,000 node sets.

### Invariant
1. Prompt size stays strictly bounded regardless of database size (500 or 50,000 nodes).
2. All overdue nodes (within top K capacity) are guaranteed to be included in the context pack.
3. The count line accurately reflects the unshown active nodes and unshown overdue nodes.
4. Pagination cursor produces a bijection over the active node set without gaps or duplicates.
5. Zero `android.*` imports in `:core`.

---

## 2. Hypothesis Table (4 approaches across layers)

| # | Hypothesis / Approach | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (SQL Count + Candidate Paging + Code Ranking)** | `Storage` provides `getActiveNodes(limit, cursor, order)` and `getActiveNodeCounts(now, staleCutoff)`. `ContextBuilder` queries counts and top candidate window, scores via pure Kotlin comparator, caps at K=25/budget, and formats count line. | Blazing fast O(1) memory footprint; exact count line; deterministic code-ranking without model guesswork. | Unit test with 500 and 5,000 synthetic nodes. | Memory spike or missing overdue nodes if candidate window is misaligned with due date ordering. | **CHOSEN** |
| **H2 (Pure SQL Ordering with Complex CASE expressions)** | Compute the full deterministic score entirely inside SQLite `ORDER BY CASE ...` and query `LIMIT 25`. | Shifts sorting to SQLite query engine. | Check SQLite query planner against index on `(status, due_at)`. | Complex CASE on `(now, status, due_at, pinned, severity, updated_at)` cannot use the `(status, due_at)` index, forcing a full table scan and filesort on 5,000 rows. Breaks CLAUDE.md Rule 5 (no DB heavy work on main thread). | Rejected |
| **H3 (Full In-Memory Loading and Scoring in ContextBuilder)** | `ContextBuilder` continues loading all active nodes with `getActiveNodes()`, filters and sorts in memory, then truncates. | Simplest code change. | Check D-104 non-negotiable rule. | Explicitly forbidden by D-104: "ContextBuilder never loads all open nodes." Causes OOM on 5,000+ nodes. | Killed |
| **H4 (Offload Selection to Model Function Calling)** | Omit active nodes from prompt; let LLM call `list_items(cursor)` on demand for every turn. | Minimizes initial context prompt. | Check latency and turn turn-around on mobile. | Increases latency by 1 extra round-trip per turn; LLM lacks awareness of open tasks unless prompted. | Killed |

---

## 3. Angles (Mandatory one-line checks)

- **schema and migration**: Index `idx_node_status_due` on `node(status, due_at)` already exists in `MineDatabaseHelper.kt`. No schema migration required.
- **concurrency/lifecycle**: Pure query functions with `ioDispatcher`; thread-safe; cancellation-safe.
- **failure paths**: Null cursors, corrupted cursors, empty node sets, all-stale node sets handled gracefully without throwing.
- **what a REAL model would send**: Real model expects compact lines (`id | type | title | due`) and count summary to know whether to invoke `list_items(cursor)`.
- **user-visible behaviour**: Prompt inspector shows top items and exact summary `"N more open items not shown (M overdue)"`.
- **privacy/security**: `local_only` nodes excluded; no private data leaked.
- **testability**: Unit testable in `:core` using `SharedFakeStorage` and `FakeClock` with 500 and 5,000 nodes.
- **battery and performance**: Fast index lookups; zero filesorts across 5,000 nodes; constant memory allocation in `ContextBuilder`.
- **blast radius**: Callers of `getActiveNodes` use default arguments `limit=100, cursor=null, order="ASC"`, preserving 100% backward compatibility.

---

## 4. Evidence Log

- `MineDatabaseCallback.onCreate` already defines `CREATE INDEX idx_node_status_due ON node(status, due_at)`.
- `Node` class in `Memory.kt` currently lacks `priority`, `severity`, and `pinned` fields; adding them with default values preserves 100% binary/source compatibility.
- In SQLite, `ORDER BY due_at IS NULL, due_at ASC, id ASC` ensures nulls drop to the bottom and dated items (including overdue) appear first.

---

## 5. Decision

- **Chosen Change**: Implement H1:
  1. Add default parameters `priority: Int = 0`, `severity: Int = 0`, `pinned: Boolean = false` to `Node` in `Memory.kt`.
  2. Update `Storage.kt` interface:
     - `suspend fun getActiveNodes(limit: Int = 100, cursor: String? = null, order: String = "ASC"): List<Node>`
     - `suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long): NodeCounts`
     - `suspend fun getStaleNodes(cutoffMs: Long = 30L * 24 * 3600 * 1000L): List<Node>`
  3. Implement keyset pagination `(due_at, id)` in `AndroidStorage.kt` and `SharedFakeStorage.kt` ensuring zero gaps and zero duplicates.
  4. Implement deterministic candidate scoring in `ContextBuilder.kt` with K=25 and 700 token capping, plus appending `"$n more open items not shown ($m overdue)"`.
  5. Add unit tests for 500 and 5,000 nodes, pagination completeness, deterministic scoring, and stale node isolation.

- **Rejected Options**:
  - H2 rejected due to index bypass and full table scan.
  - H3 rejected because D-104 explicitly forbids loading all open nodes.
  - H4 rejected due to user latency degradation.

---

## 6. Pre-Mortem & Breaking Inputs

1. **Pre-mortem 1**: A cursor with `due_at = null` causes cursor pagination to loop infinitely or skip items if string comparison fails on `"null"`.  
   *Mitigation*: Keyset pagination explicitly discriminates non-null `due_at` from null `due_at` with strict `id > cursorId` tie-breaking.
2. **Pre-mortem 2**: An overdue item not touched for 31 days is both overdue and stale; does it appear in context or Inbox?  
   *Mitigation*: Per D-104, stale items leave the active context and appear in Inbox as "stale". `ContextBuilder` excludes items with no activity for > 30 days.
3. **Pre-mortem 3**: Candidate limit (100) truncates overdue items if there are more than 100 overdue items.  
   *Mitigation*: Overdue items are ordered first by `due_at ASC`. If overdue count > K (25), unshown overdue count `m` accurately indicates remaining unshown overdue items.

**Breaking Input Test**:
Database containing 5,000 nodes (10 overdue, 4,900 undated, 90 future dated). Paginating with `limit = 50` must return exactly 5,000 distinct items with zero duplicates.
