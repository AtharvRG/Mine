# Review & Verification Record: Task R10a (Day Rollover Single Source of Truth)

Date: 2026-11-04  
Status: APPROVED  
Scope: Blueprint 50 s3.13, Blueprint 11 s3.2, Blueprint 52 s3.5, Task R10a  

---

### 1. Verification Summary
- **Implementation**:
  - `DayRule` data model defined in `Memory.kt`.
  - `DayRollover` pure domain helper implemented in `core/time/DayRollover.kt` (`computeDayKey`, `getLogicalDayStart`, `getNextRollover`, `scheduleNextRule`, `safeTimeZone`).
  - `Storage` contract expanded with `getEffectiveDayRule(tsUtc: Long): DayRule` and `saveDayRule(rule: DayRule)`.
  - Implemented in `AndroidStorage.kt` (using SQLite query `SELECT * FROM day_rule WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1`) and `SharedFakeStorage.kt`.
  - All callsites (`AndroidStorage.insertRawMessage`, `Gateway.propose_change`, `CheckinBuilder.buildMorningFacts`) migrated to `storage.getEffectiveDayRule(ts)`. Conflicting/stale `day_start_minute` setting reads deprecated and removed.
  - Strict `day_key` monotonicity preserved in `AndroidStorage.insertRawMessage`.
- **Reviewer Findings & Resolution**:
  - Finding 1: `DayRollover.getNextRollover` initialized `targetCal` from `nextRolloverCurrent` rather than evaluating the target timezone relative to `nowUtc`. Fixed: `targetCal` initialized in `targetTz` relative to `nowUtc` with next-day advance if `<= nowUtc`, ensuring the boundary is strictly future.
  - Finding 2: `safeTimeZone` caught `Exception` but Java's `TimeZone.getTimeZone` returns `"GMT"` on invalid input without throwing. Fixed: explicitly checked `tz.id == "GMT"` when input was not GMT/UTC and fell back to `TimeZone.getDefault()`.
  - Connected check: `AndroidStorageTest.testAndroidStorage_DayRuleEffectiveFrom_AndNextRollover` verified on connected physical `moto g73 5G` (1 test, 0 failures, 100% success).
  - All unit tests in `:core` and `:app` passed (100%).

---

### 2. Test Commands & Evidence
- `./gradlew :core:test`: 100% PASS (including `DayRolloverTest.kt` with all 6 test scenarios).
- `./gradlew :app:testDebugUnitTest`: 100% PASS.
- `./gradlew :app:connectedDebugAndroidTest`: 100% PASS on moto g73 5G (`AndroidStorageTest`).

---

### 3. Verdict
APPROVED. Single source of truth established across `core` and `app`.
