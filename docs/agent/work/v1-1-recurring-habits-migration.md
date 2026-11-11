# Branching Analysis: Recurring Reminders & Habits Schema Migration (v1.1 Item 3)
Date: 2026-11-11
Author: Antigravity
Status: Decided

## Goal and Invariant
- **Goal:** Promote recurrence and habit streak fields from JSON `attrs` to first-class schema columns in `node` and `reminder` tables; provide robust recurrence calculation and streak tracking in pure Kotlin (`:core`); implement idempotent V6->V7 database migration.
- **Invariant 1 (Additive Migration):** Database version bumps from 6 to 7. `onUpgrade` adds columns with default values (`streak_current = 0`, `streak_best = 0`) ensuring zero data loss on existing databases.
- **Invariant 2 (Habit Persistence):** Completing a habit advances its streak (`streak_current`, `streak_best`, `last_completed_at`) and calculates its next due occurrence, keeping the habit node active for tomorrow/next cycle rather than terminating it.
- **Invariant 3 (Reminder Advance):** When a recurring reminder or habit is completed/fired, its next occurrence is computed (> `now`) and the OS alarm is re-armed for the future iteration.

## Schema Changes (V6 -> V7)
1. **`node` table:**
   - `recurrence_rule`: TEXT (e.g. `FREQ=DAILY`, `FREQ=WEEKLY`, `daily`, etc.)
   - `streak_current`: INTEGER NOT NULL DEFAULT 0
   - `streak_best`: INTEGER NOT NULL DEFAULT 0
   - `last_completed_at`: INTEGER
2. **`reminder` table:**
   - `rrule`: TEXT
   - `iteration`: INTEGER NOT NULL DEFAULT 0
3. **Index:**
   - `CREATE INDEX IF NOT EXISTS idx_node_habit ON node(type, streak_current)`

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|------------|------------|----------------|----------------|--------|
| H1 | First-class columns in `node` and `reminder` with V7 migration | Clean SQL queries, fast streak ranking, type-safe models in `:core` | `PRAGMA table_info(node)` after `onUpgrade(db, 6, 7)` | Fails to preserve existing data | **Selected (Blueprint-mandated)** |
| H2 | Keep recurrence in JSON `attrs` | No schema migration | Inspect SQL query performance for habit streaks | Cannot index or query habits by streak without full table scan | Rejected |
| H3 | Separate `habit_streak` table | Separate normalized table | Check complexity vs Blueprint 13 | Blueprint 90 Item 3 explicitly mandates "promote attrs fields to columns" | Rejected |
| H4 | Create brand-new node on every habit completion | Generates thousands of duplicate nodes | Check graph bloat over 1 year | Node spam, breaks node identity | Rejected |

## Angles (Mandatory)
1. **Data / Schema:** V6 -> V7 additive migration. Nullable `recurrence_rule`, default 0 for streaks. Recreate triggers and rebuild FTS per `room-migrations` skill.
2. **Concurrency / Lifecycle:** CAS proposals and transactional commits in `AndroidStorage` and `SharedFakeStorage` preserve streak updates atomically.
3. **Failure Paths:** Invalid or unparseable `rrule` falls back gracefully to `FREQ=DAILY` (24 hours).
4. **User-Visible Behaviour:** Completing a habit shows updated streak count (e.g. "🔥 5 day streak!") and schedules tomorrow's check-in.
5. **Privacy / Security:** Recurrence rules contain only frequencies/intervals, zero personal data.
6. **Testability:** Core unit tests for `RecurrenceEngine` (daily, weekly, weekdays, custom intervals, streak rollover/grace period); device migration test from V6 to V7; fresh V7 install smoke test.
7. **Blast Radius:** `Node`, `Reminder`, `Storage`, `AndroidStorage`, `SharedFakeStorage`, `Gateway`, `MineDatabaseHelper`, `DatabaseMigrationTest`.
8. **Platform (Android 14, OEM):** Tested on physical moto g73 5G.
9. **Model Behaviour:** LLM proposal includes `recurrence_rule` string in `attrs` or node creation params.

## Pre-Mortem: 3 Failure Scenarios
1. *Late completion resets streak prematurely:* User completes habit at 12:05 AM (5 minutes past midnight) and streak resets to 0. -> **Guard:** 36-hour grace window for daily habits (allows yesterday + today).
2. *Infinite loop in recurrence calculation:* An invalid rule or past date results in next occurrence <= `now`. -> **Guard:** While loop advances timestamp until `candidate > now`.
3. *V6->V7 migration breaks existing alarms:* Missing default values causes SQLite column constraint violation. -> **Guard:** `NOT NULL DEFAULT 0` and nullable TEXT fields.
