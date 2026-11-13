# Branching Analysis: Insights & Analytics Queries (v4 / Blueprint 32 §3.5-§3.6 / P-007, P-008)

- **Date**: 2026-11-13
- **Author**: Lead Engineer & Quality Gate
- **Status**: IN PROGRESS
- **Decision**: D-196 (pending review)

---

## 1. Goal and Invariant

### Goal
Implement comprehensive **Insights & Analytics Queries** (v4 Roadmap line 52, Blueprint 32 §3.5 & §3.6, Parking Lot `P-007` & `P-008`):
1. **Weekly & Monthly Review Rollups**: Computes task creation, completion, cancellation, carried-over backlog, and completion rate for arbitrary time windows `[startMs, endMs]` (including calendar week, 30-day rolling month, and calendar month-to-date `P-007`).
2. **Weekly Daily Bars (`week_bars`)**: Daily buckets of created vs completed tasks across 7 days.
3. **Goal Progress Rollups (`goal_progress`)**: Sub-task completion percentage and counts for active goal entities linked via `part_of` or hierarchical edges.
4. **Habit Consistency & Streaks (`streaks`)**: Summaries of active habits with current streaks, best streaks, and last completion times.
5. **24-Hour Activity Heat Strip (`activity_strip`)**: Hourly distribution (0..23) of user actions across the day over the last N days from `raw_message` and `raw_event`.
6. **Opt-in Mood Trends (`mood_line`, P-008)**: Historical reflection sentiment points from `signal_log` (`kind = 'mood'`) or `day_digest`, gated by setting `flag.mood_tracking` (default: "0" / opt-in).

### Invariant
- **Pure Kotlin in `:core`**: Analytics calculation and data transformations live strictly in `:core` without Android SDK dependencies.
- **Privacy & Local-Only Filtering**: `local_only` items are excluded from remote/shared analytics; mood tracking is strictly opt-in and local-only.
- **Append-Only & Zero-Destruction**: Queries read from immutable `raw_event`, `raw_message`, `node`, `edge`, `day_digest`, and `signal_log`. Zero table writes or mutations required during queries.
- **Deterministic & Zero-Hallucination**: Native chart cards and review metrics are calculated in code from database rows; the LLM never fabricates numbers or chart data.
- **Performance**: Keyset-bounded queries, indexed lookups, and single-pass aggregation to ensure zero main-thread stutters even with thousands of historical events.

---

## 2. Hypothesis Table (Min 4, Different Layers)

| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Pure SQL Aggregation)** | Run complex `GROUP BY strftime('%H', ...)` and window functions directly inside SQLite/SQLCipher. | Minimizes data fetched into memory, but `strftime` behaves differently in SQLCipher/SQLite on Android than in unit test mocks (`SharedFakeStorage`), risking divergence. | Compare SQLite date formatting vs pure Kotlin `Calendar` / `DayRule`. | SQLite date functions fail or diverge from local timezone / 4am day rollover in `:core:test`. | **KILLED** (Diverges from 4am rollover & breaks pure-Kotlin `:core:test` parity). |
| **H2 (Pure Kotlin In-Memory Aggregation over Raw Entities)** | Expose generic entity fetch methods in `Storage` and perform all grouping, bucketing, and math in pure Kotlin `InsightsEngine`. | 100% testable in `:core:test` without SQLite, guarantees identical behavior across Android and Desktop/Test, but loading all historical events into memory could cause memory spikes on old databases. | Measure memory of fetching bounded events in `[startMs, endMs]`. | Fetching unboundedly large collections without range filtering. | **ALIVE** (with time-range bounded SQL queries in storage). |
| **H3 (Hybrid: Time-Bounded Range Queries + Pure Kotlin Aggregation Engine)** | `Storage` provides targeted range queries (`getNodesUpdatedBetween`, `getEventsBetween`, `getMessagesBetween`, `getSignalLogs`) with SQL index use, and `InsightsEngine` calculates the rollups, heat strips, goal hierarchies, and streaks. | Optimal performance: SQLite uses indexes on `ts_utc`, `updated_at`, `closed_at`; `:core` engine does deterministic business logic and timezone rollover math. | Unit test with synthetic entities; benchmark on physical device. | Any query performs full table scan on unindexed columns. | **SELECTED (CHOSEN OPTION)**. |
| **H4 (Dedicated Analytics/Cache Table Migration)** | Add a new `analytics_cache` table (DB v13 migration) storing precomputed daily rollups. | Fast retrieval, but adds schema migration overhead, potential cache invalidation bugs when past events are edited or undone, and unnecessary complexity. | Check whether on-demand range queries over indexed columns take < 15ms. | Range queries over 1000 items take < 5ms on phone; migration is unnecessary overhead. | **KILLED** (Unnecessary schema complexity). |

---

## 3. Angles (One Line Each, Mandatory)

- **Data / Schema**: No schema bump needed; all 5 required tables (`node`, `edge`, `raw_event`, `raw_message`, `day_digest`, `signal_log`) exist in v12 with indexes on `due_at`, `status`, `ts_utc`.
- **Concurrency & Lifecycle**: All reads are read-only and suspendable on `Dispatchers.IO`; thread-safe with zero write locks.
- **Failure Paths**: Missing or empty data ranges return zeroed metrics (0 completions, 0% rate, zeroed 24-hour heat bins); opt-in mood returns empty list when disabled.
- **User-Visible Behavior**: Backs Native Chart Cards (`week_bars`, `goal_progress`, `activity_strip`, `streaks`, `mood_line`), Weekly Reviews, and Monthly Reviews.
- **Privacy & Security**: `local_only` tasks are excluded; mood data is gated behind explicit opt-in setting; zero network calls.
- **Testability**: Pure Kotlin `InsightsEngineTest` verifies exact math, edge cases, leap years, month-end rollovers, and empty ranges with 100% test coverage.
- **Blast Radius**: Strictly additive query and engine layer; zero changes to existing mutating transactions or alarm scheduling.
- **Platform (Android 14, OEM)**: Zero new permissions; zero background service requirements; runs smoothly within standard coroutine scope.
- **Model Behavior**: LLM is supplied with pre-aggregated facts blocks, strictly forbidden from hallucinating numbers or stats.

---

## 4. Evidence Log

1. Checked existing tables in `MineDatabaseHelper.kt`:
   - `node` (indexes: `idx_node_status_due`, `idx_node_habit`).
   - `raw_event` (index: `idx_raw_event_target`).
   - `raw_message` (indexes: `idx_raw_message_ts`, `idx_raw_message_day`).
   - `day_digest` (primary key: `day_key`).
   - `signal_log` (primary key: `id`).
2. Current test pass rate:
   - `:core:test`: 561 / 561 PASS.
   - `:app:testDebugUnitTest`: 27 / 27 PASS.
   - Connected device tests: 8 / 8 PASS.

---

## 5. Decision & Architecture (D-196)

- **Architecture**:
  - `core/src/main/java/com/anchor/core/insights/InsightsModels.kt`: Data contracts for `WeeklyReviewStats`, `MonthlyReviewStats`, `DayBucket`, `GoalProgress`, `HabitStreakSummary`, `ActivityHeatStrip`, `MoodPoint`, and `FullInsightsReport`.
  - `core/src/main/java/com/anchor/core/insights/InsightsEngine.kt`: Pure Kotlin engine computing rollups, percentages, daily bars, goal progress, habit streaks, 24-hour activity heat strip, and mood trends.
  - `core/src/main/java/com/anchor/core/storage/Storage.kt`: Add `getInsightsReport(...)`, `getEventsBetween(startMs, endMs)`, `getMessagesBetween(startMs, endMs)`, `getSignalLogs(kind, startMs, endMs)`.
  - `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`: SQLCipher implementations with indexed range scans.
  - `core/src/test/java/com/anchor/core/storage/SharedFakeStorage.kt`: In-memory implementation.
  - `core/src/main/java/com/anchor/core/ui/ChatContract.kt` & `ChatController.kt`: Wire `latestInsightsReport` in `UiState` and `UiAction.LoadInsights(days)`.

---

## 6. Pre-Mortem: "It is a week later and this fix caused a bug; what was it?"

1. **Bug 1: Timezone offset / Day Rollover Mismatch in Daily Bucketing**: If `DayBucket` uses UTC calendar while the user's day starts at 4:00 AM local time, tasks completed at 1:00 AM would be attributed to the wrong day.
   - *Mitigation*: Inject `DateResolver` / `DayRule` so `dayKey` matches the user's configured logical day.
2. **Bug 2: Division by Zero in Completion Rate**: If a user creates 0 tasks and completes 0 tasks in a week, computing `completed / total` could throw `ArithmeticException` or yield `NaN`.
   - *Mitigation*: Guard with `if (total == 0) 0.0f else (completed.toFloat() / total.toFloat()) * 100f`.
3. **Bug 3: Opt-in Mood Data Leak**: If mood points are returned when `flag.mood_tracking` is turned off ("0" or unset).
   - *Mitigation*: Strictly check `includeMood: Boolean` which reads setting `flag.mood_tracking == "1"`. If false, returns empty list.
