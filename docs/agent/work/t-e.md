HISTORICAL: contains unverified or incorrect claims; superseded by the session1 review (see TASKS.md and DECISIONS.md)

# Task T-E: Calendar Queries (D-107) Branching Analysis

**Date**: 2026-11-02  
**Task**: T-E (D-107)  
**Module**: `:core` and `:app`  

---

## 1. Goal and Invariant

### Goal
Implement calendar navigation and day queries per Decision D-107 and UI Spec E6:
1. `getDaysWithMessages(startDayKey: String, endDayKey: String): Set<String>`: Identify days with active conversation in a date range for month view highlighting.
2. `getHeatMapCounts(startDayKey: String, endDayKey: String): Map<String, Int>`: Activity heatmap query grouping message volume per `day_key`.
3. `getMessagesForDay(dayKey: String): List<ChatMessage>`: Tap on a date with conversation loads and displays that day's message history.
4. `getRemindersBetween(startMs: Long, endMs: Long): List<Reminder>`: Query reminders in a timestamp window for calendar day badges or date ranges.
5. `getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long): List<Reminder>`: Long-press/hold on a calendar day displays that day's scheduled reminders in a tooltip/sheet.
6. Wire `UiAction.LoadDay(dayKey)` and `UiAction.ShowDayReminders(dayKey, startOfDayMs, endOfDayMs)` into `UiState` and `ChatController.kt`.
7. Implement in `Storage.kt`, `SharedFakeStorage.kt`, and `AndroidStorage.kt` with zero warnings/lint regressions, zero `android.*` in `:core`.
8. Write comprehensive unit tests in `:core` and instrumented tests in `:app`.

### Invariant
1. D-107 is strictly satisfied: Tap on a day with messages opens that day's conversation; long press/hold shows reminders tooltip.
2. Zero `android.*` imports in `:core`.
3. Range queries on `raw_message` use existing index `idx_raw_message_day ON raw_message(day_key, ts_utc)`.
4. Range queries on `reminder` use existing index `idx_reminder_fire ON reminder(fire_at)`.
5. Redacted messages (`content IS NULL`) can be included or excluded consistently; conversation count only counts valid messages.
6. Clean fallback: Days without messages or reminders return empty collections, never throwing exceptions.

---

## 2. Hypothesis Table (4 approaches across layers)

| # | Hypothesis / Approach | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Indexed Direct SQL Queries in Storage + Controller State Integration)** | Implement dedicated indexed queries in `Storage` (`getDaysWithMessages`, `getHeatMapCounts`, `getMessagesForDay`, `getRemindersBetween`) and route through `ChatController`. | Clean separation of concerns, O(log N) indexed queries, testable in unit tests without Android dependencies. | Unit tests in `:core` + instrumented tests in `:app`. | Query scan performance or schema mismatch. | **CHOSEN** |
| **H2 (Load All Messages into Memory and Filter in App)** | Fetch all messages from `raw_message` table and compute heatmap and day groupings in Kotlin in-memory. | Simpler SQL (`SELECT * FROM raw_message`). | Profiling with 10,000+ messages. | Severe memory bloat, high GC pressure, slow UI startup; violates Blueprint 11 & 50. | Killed |
| **H3 (Store Precomputed Heatmap in Separate Table)** | Maintain an auxiliary `calendar_stat` table updated on every message insert. | Fast read at query time. | Trace write paths and triggers. | Write amplification on every single message; trigger overhead; sync bugs on redaction/import. | Killed |
| **H4 (Modal-only Navigation with Separate ViewModel)** | Introduce a separate CalendarViewModel and modal screens for reminders and days. | Segregates calendar state. | Check D-107 explicit directive. | D-107 explicitly rejects modal navigation: "Rejected: Single tap for reminders or requiring modal navigation." | Killed |

---

## 3. Angles (Mandatory one-line checks)

- **data/schema**: Leverage existing SQLite indexes `idx_raw_message_day` and `idx_reminder_fire`. No schema version bump needed as indexes already exist.
- **concurrency/lifecycle**: Read queries run on `ioDispatcher` inside `withContext`. State updates use atomic `_state.update {}`.
- **failure paths**: Null dates, empty date ranges, and days without messages return empty lists/maps gracefully.
- **user-visible behaviour**: Calendar dates with messages highlight and open day view on tap; long press surfaces reminder items.
- **privacy/security**: Redacted messages retain null content; SQL injection prevented via parameterized query arguments.
- **testability**: Fast unit test suite in `:core` with `SharedFakeStorage` + real SQLCipher verification in `:app`.
- **blast radius**: Purely additive to `Storage`, `UiState`, and `UiAction`. All existing tests continue to pass.
- **platform (Android 14, OEM)**: Compatible across all Android versions; runs on JVM and Android runtime.
- **model behaviour**: UI layer directly triggers calendar queries without consuming LLM token budget.
