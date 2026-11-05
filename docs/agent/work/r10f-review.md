# Task R10f Independent Review: Weekday and Vocabulary Gaps in DateResolver

Date: 2026-11-04  
Task: R10f (session1-remediation-spec.md line 312, ROADMAP.md P1-B 14)  
Reviewers: Adversarial Code Reviewer, Timezone & Calendar Auditor, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
Ensure that `DateResolver` resolves all conversational weekday and natural vocabulary gaps specified in P1-B 14 and `session1-remediation-spec.md` line 312:
1. Weekday abbreviations ("mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun") and compound prefixes ("on next friday", "coming friday", "this coming monday").
2. Day-part descriptors ("morning", "afternoon", "evening", "night") both standalone and attached to weekdays ("friday morning", "friday night") or relative days ("tomorrow morning", "this evening").
3. Natural duration expressions without "in" ("half an hour", "an hour", "a minute", "in a half hour").
4. Timezone support: caller-supplied `timeZone` parameter wired from `dayRule.tzId` in `Gateway`.
5. 2:00 AM post-midnight / pre-rollover edge cases: "tonight" strictly resolves to the upcoming evening (21:00 current calendar day, never yesterday), "today at 1am" evaluates to `DateResolution.PastTime`, and "today at 7pm" evaluates to 19:00 current calendar day.
6. Ambiguity rule: "no am/pm => ask" (returns `DateResolution.NeedsClarification` with 2 candidate chips, and `parse()` returns `null`).
7. Clear past-time error: returns `DateResolution.PastTime` yielding `"That time is in the past; tell me a future time."` in `Gateway`.
8. System prompt documentation in `ChatController.kt` documenting accepted `due_text` patterns.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: None found. No SQLite schema changes; pure in-memory date resolution.
- **concurrency and cancellation**: None found. All methods are thread-safe and stateless with respect to instance mutations.
- **process death and reboot**: None found. Does not hold process locks or lifecycle-bound state.
- **failure paths (network, 429, malformed model output, null args)**: None found. Null, blank, and unrecognized temporal expressions cleanly return `DateResolution.Failed` without throwing exceptions.
- **idempotency (runs twice, already-closed item)**: Upheld. Pure deterministic functions parameterized on `clock.now()` and `timeZone`.
- **non-Latin text**: Upheld. Digits are normalized via `normalizeDigits` before regex evaluation.
- **main-thread work**: None found. Pure lightweight regex matching and calendar math; sub-millisecond execution.
- **alarm and notification correctness**: Upheld. Resolved millisecond timestamps accurately target future alarm fire times.
- **privacy (secrets in logs, local-only data)**: Upheld. No user data logged.
- **blast radius (what else uses this code)**: Evaluated all 24 call sites across `:core` and `:app`. Constructor signature allows both named and positional parameters as well as trailing lambda syntax `DateResolver(clock) { is24Hour }` without ambiguity.

### Project Rules Verification
- **Injected clock rule (CLAUDE.md Rules 4 & 5)**: Upheld. All resolution and formatting math strictly relies on `clock.now()`.
- **No guessing on ambiguous times**: Upheld. "no am/pm => ask" returns `NeedsClarification` and `parse()` returns `null`.
- **Zero seconds and milliseconds**: Upheld. All Calendar instances have SECOND and MILLISECOND zeroed out.
- **SPDX headers**: Upheld on all touched files.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: None.

### Tests
- `testWeekdayAbbreviations`: PASS
- `testCompoundPrefixes`: PASS
- `testWeekdayAndRelativeDayParts`: PASS
- `testDurationVocabularyGaps`: PASS
- `testTwoAmCases_TonightNeverYesterday`: PASS
- `testAmbiguousBareHour_NoAmPmAsks`: PASS
- `testTimezoneSupport`: PASS

**VERDICT**: APPROVE

---

## 2. Timezone & Calendar Auditor Report

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. Injected TimeZone Propagation | PASS | `DateResolver.kt:19-24`, `Gateway.kt:182` | `effectiveTz` resolved from `dayRule.tzId` and passed to Calendar & SimpleDateFormat. |
| 2. Trailing Lambda Disambiguation | PASS | `DateResolver.kt:19-24` | Primary constructor with secondary `constructor(clock, is24HourFormat)` ensures zero lambda binding collisions. |
| 3. 2am Rollover Boundary | PASS | `DateResolver.kt:111`, `DateResolverR10fTest.kt:177` | 02:00 AM clock strictly sets 21:00 future time; never rolls back to yesterday. |
| 4. AM/PM Ambiguity Clarification | PASS | `DateResolver.kt:423-431` | Produces 2 distinct candidates and localized chips. |

**VERDICT**: APPROVE

---

## 3. Test Auditor Report

- Unit tests in `:core:test`: 100% PASS (all test classes passed).
- Unit tests in `:app:testDebugUnitTest`: 100% PASS.
- Execution time: Clean compile and pass in under 9s.

**VERDICT**: APPROVE
