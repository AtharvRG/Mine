# Task T-A: DateResolver 24h + Ambiguity Chips (D-103) Branching Analysis

**Date**: 2026-11-02  
**Task**: T-A (D-103)  
**Module**: `:core`  

---

## 1. Goal and Invariant

### Goal
Upgrade `DateResolver` to support 12-hour and 24-hour input forms ("15:30", "1530", "15h", "at 15", "3pm", "3:30 pm", "3 in the evening", "tonight at 9"), normalize non-Latin digits (Devanagari/Hindi digits), detect bare ambiguous hours 1-12 without am/pm/day-part, and return `DateResolution.NeedsClarification` with AM and PM candidate options and formatted chips without guessing. Format echoed times using the injected 12/24h setting including weekday and date.

### Invariant
1. Unambiguous times (13-23, 00:xx, explicit am/pm, explicit relative offsets, day-part words like "evening", "tonight", "morning") resolve to an exact epoch millisecond timestamp.
2. Bare hours 1-12 without am/pm or day-part qualifiers ("at 5", "5:30", "tomorrow at 5") NEVER guess: they MUST return `NeedsClarification` with 2 options (morning/afternoon).
3. `DateResolver.parse()` maintains backward compatibility by returning `Long?` (`Exact` -> millisecond timestamp, `NeedsClarification`/`Failed` -> `null`).
4. Pure logic remains strictly in `:core` without `android.*` imports.

---

## 2. Hypothesis Table (4 approaches across layers)

| # | Hypothesis / Approach | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Logic / Regex Cascade)** | Add modular regex stages: (1) Digit normalization, (2) Relative durations, (3) Day tokens, (4) Time pattern matcher distinguishing 24h (13-23, 00), 12h (with am/pm or day-part), and bare 1-12. | Accurately extracts all documented forms and cleanly separates ambiguous from unambiguous cases. | Unit test against string table of all D-103 examples. | Inability to parse 24h compact forms ("1530") or false positive on non-time numbers. | **CHOSEN** |
| **H2 (External Time Parser Library)** | Introduce `java.time.format.DateTimeFormatter` with complex fallback patterns. | Standard Java 8+ time parsing. | Check if patterns can handle informal expressions ("in 2 hrs", "15h", "3 in the evening"). | Cannot handle conversational relative English and Devanagari numerals without extensive custom preprocessing anyway. | Rejected |
| **H3 (LLM Secondary Call for Disambiguation)** | If resolver fails or encounters bare hours, prompt LLM to infer user intent based on context. | High natural language coverage. | Check CLAUDE.md Non-Negotiable Rule 1 & Rule 2 ("The LLM proposes, code decides. Fail safe, never fail silent. Never guess."). | Violates project rules and D-103. | Killed |
| **H4 (Platform Android DateFormat Hook)** | Import `android.text.format.DateFormat` to read device 24h setting. | Direct access to OS settings. | Check Gradle `:core` compile dependencies. | Module `:core` has no `android.*` dependencies (Rule 5). Breaks headless JUnit tests. | Killed |

---

## 3. Angles (Mandatory one-line checks)

- **data/schema**: No database schema change. `DateResolution` is in-memory for `Gateway` and `DateResolver`.
- **concurrency/lifecycle**: Pure stateless functions operating on immutable input strings and injected `Clock` timestamps; thread-safe.
- **failure paths**: Malformed strings, out-of-range hours (>23) or minutes (>59) return `DateResolution.Failed` without throwing.
- **user-visible behaviour**: When time is ambiguous, user sees two explicit chips ("05:30" / "17:30" or "5:30 AM" / "5:30 PM"); echoed time shows "Tue 27 Oct, 17:30".
- **privacy/security**: Local string parsing only; no data logged or leaked outside device.
- **testability**: 100% testable via unit tests in `:core` with `FakeClock` and synthetic 12/24h toggle lambda.
- **blast radius**: `DateResolver` callers are `Gateway.kt` and tests. Preserving `parse(): Long?` ensures no regressions in callers.
- **platform (Android 14, OEM)**: Relies on standard Java Calendar/Locale; device 12/24h format injected via lambda `is24HourFormat: () -> Boolean`.
- **model behaviour (what a real LLM would send)**: Real models emit "15:30", "5pm", "17:00", or bare "5:00". Parser must tolerate extra whitespace and casing.

---

## 4. Evidence Log

- Existing tests in `DateResolverTest.kt` pass: `assertNull(resolver.parse("tomorrow at 9:30"))` proved bare hour currently returns `null`.
- Current `format()` uses `"EEE d MMM h:mm a"` which lacks 24h formatting and weekday comma matching D-103 specification.

---

## 5. Decision

- **Chosen Change**: Implement H1 in `DateResolver.kt`:
  1. Digits normalization mapping Unicode digits (Devanagari `\u0966-\u096F`, Arabic-Indic, etc.) to ASCII `0-9`.
  2. Implement `resolve(dueText: String?, rolloverMinuteOfDay: Int = 240): DateResolution`.
  3. Recognize 24h formats: `"HH:mm"`, `"HHmm"`, `"HHh"`, `"at HH"`.
  4. Recognize 12h formats: `"h[:mm] am/pm"`, `"h[:mm] in the morning/afternoon/evening"`, `"tonight at h"`.
  5. Recognize bare hours 1-12 without day-part: return `DateResolution.NeedsClarification(options = listOf(morningMs, eveningMs), chips = listOf(...))`.
  6. Support 24-hour setting injection via `is24Hour: () -> Boolean = { false }`.
  7. Keep `parse(): Long?` as a helper delegating to `resolve()`.

- **Rejected Options**:
  - H2: `DateTimeFormatter` is brittle for informal conversational phrasing.
  - H3: LLM guessing violates D-103 and CLAUDE.md Rule 2.
  - H4: `android.text.format.DateFormat` in `:core` violates Rule 5.

---

## 6. Pre-Mortem & Breaking Inputs

1. **Pre-mortem 1**: "1530" could match a four-digit year (e.g. "January 1530").  
   *Mitigation*: Match "1530" only when in time context (standalone, after "at ", or prefixed by day token).
2. **Pre-mortem 2**: "3 in the evening" vs "3 in the morning" parsed wrong due to overlapping substring checks.  
   *Mitigation*: Strict tokenized day-part regexes for `morning` (AM), `afternoon` (PM), `evening` (PM), `night` (PM).
3. **Pre-mortem 3**: 4 AM logical day rollover interaction: "at 5" at 2 AM could produce candidate times across logical days.  
   *Mitigation*: Both candidates anchor to the logical day base, then roll forward if already passed.

**Breaking Input Test**:
`"tomorrow at ५:३०"` (Devanagari 5:30) with no AM/PM -> Must return `NeedsClarification` with 05:30 and 17:30 options for tomorrow.
