# Task R10j Independent Review: Chat History Search Indexing via msg_chunk_fts

Date: 2026-11-04  
Task: R10j (docs/agent/TASKS.md line 61, session1-remediation-spec.md line 316)  
Reviewers: Adversarial Code Reviewer, Migration Guard & SQLite Auditor, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
Ensure that `msg_chunk_fts` is continuously synchronized with conversation history so `searchMemoryFts` accurately searches past messages:
1. SQLite FTS5 external content sync triggers (`trg_msg_chunk_fts_insert`, `trg_msg_chunk_fts_delete`, `trg_msg_chunk_fts_update`) automatically update `msg_chunk_fts` on `msg_chunk` mutations.
2. `AndroidStorage.insertRawMessage` transactionally inserts a 1:1 chunk into `msg_chunk` for non-blank user and assistant messages (`content.isNotBlank() && role != "system"`).
3. Zero-leakage redaction: `AndroidStorage.redactMessage` deletes matching `msg_chunk` rows (`DELETE FROM msg_chunk WHERE msg_ids LIKE ?`), and SQLite `AFTER DELETE` trigger purges the FTS index immediately.
4. `AndroidStorage.searchMemoryFts` joins `msg_chunk` and `msg_chunk_fts`, returning `SearchResult(id, "chat", dayKey, text)`.
5. `SharedFakeStorage.searchMemoryFts` provides full behavioral parity for unit tests in `:core`.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: Tables `msg_chunk` and `msg_chunk_fts` were already defined in DB v5. Adding sync triggers in `MineDatabaseHelper.createTriggers(db)` uses `CREATE TRIGGER IF NOT EXISTS`, executing seamlessly on both `onCreate` and `onUpgrade`.
- **concurrency and cancellation**: In `AndroidStorage.insertRawMessage`, insertion into `raw_message` and `msg_chunk` is wrapped in a single SQLite transaction (`db.beginTransaction() ... db.setTransactionSuccessful()`), ensuring atomicity.
- **process death and reboot**: Full disk persistence in SQLiteCipher. Survives process death and device reboots.
- **failure paths (network, 429, malformed model output, null args)**: Blank messages and system instructions are safely filtered out; corrupted or unmatched FTS queries safely handled in try-catch.
- **idempotency (runs twice, already-closed item)**: Deterministic IDs (`chunk_$id`) and unique message IDs.
- **non-Latin text**: FTS5 unicode61 tokenizer correctly handles multilingual conversation text.
- **main-thread work**: All storage interactions run on `Dispatchers.IO`.
- **alarm and notification correctness**: N/A; does not affect alarm scheduling.
- **privacy (secrets in logs, local-only data)**: Redacted messages are scrubbed from `msg_chunk` and `msg_chunk_fts` via triggers. System prompts are excluded.
- **blast radius**: Isolated to chat storage insertion, FTS triggers, and memory retrieval.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: None.

**VERDICT**: APPROVE

---

## 2. Migration Guard & SQLite Auditor Report

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. FTS5 External Content Triggers | PASS | `MineDatabaseHelper.kt:141-143` | `trg_msg_chunk_fts_insert`, `trg_msg_chunk_fts_delete`, `trg_msg_chunk_fts_update` strictly follow SQLite FTS5 external content table sync specifications. |
| 2. Transaction Atomicity | PASS | `AndroidStorage.kt:64-79` | `raw_message` and `msg_chunk` written in the same transaction block. |
| 3. Redaction Deletion & Scrubbing | PASS | `AndroidStorage.kt:92` | `DELETE FROM msg_chunk WHERE msg_ids LIKE ?` triggers FTS delete trigger. |
| 4. FTS Query Join & Ranking | PASS | `AndroidStorage.kt:610-616` | Joins `msg_chunk.rowid = msg_chunk_fts.rowid`, filters via `MATCH ?`, orders by `rank`, limits to safe limit. |

**VERDICT**: APPROVE

---

## 3. Test Auditor Report

- `ChatHistoryFtsTest`:
  - `testSearchMemoryFts_FindsConversationHistory`: PASS
  - `testSearchMemoryFts_RedactionScrubsConversationHistory`: PASS
  - `testSearchMemoryFts_SystemMessagesNotIndexed`: PASS
- `DatabaseMigrationTest`:
  - `testFreshV5SmokeTest_RunsEveryStorageMethod`: Verified FTS trigger firing and redaction scrubbing on SQLiteCipher.
- `AndroidStorageTest`:
  - `testAndroidStorage_MsgChunkFts_SearchAndRedact`: PASS
- Full `:core:test`: 100% PASS (119 unit tests passing).
- Full `:app:testDebugUnitTest`: 100% PASS.

**VERDICT**: APPROVE

---

## 4. Reviewer Hardening & Recommendations Applied
1. **Trigger Registration on Existing Databases (`onOpen`)**:
   - Added `override fun onOpen(db: SupportSQLiteDatabase)` in `MineDatabaseCallback` calling `createTriggers(db)`.
   - Ensures that databases already at version 5 register `trg_msg_chunk_fts_insert`, `trg_msg_chunk_fts_delete`, and `trg_msg_chunk_fts_update` upon opening without requiring a schema version bump.
2. **Search Limit Bounds Protection**:
   - Clamped `safeLimit = limit.coerceIn(1, 20)` in `AndroidStorage.searchMemoryFts` and returned `results.take(safeLimit)`, preventing negative limits or unconstrained query memory usage.
   - Added `results.take(limit.coerceAtLeast(0))` in `SharedFakeStorage.searchMemoryFts`.
3. **DayKey Contract Alignment in Fake Storage**:
   - In `SharedFakeStorage.insertRawMessage`, computed `dayKey` dynamically via `DayRollover.computeDayKey(clock.now(), getEffectiveDayRule(clock.now()))` instead of leaving `dayKey` null.

