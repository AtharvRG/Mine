# Branching Analysis: v2 Context Sensing (Blueprint 43)

## Goal and invariant
- **Goal**: Implement deterministic, privacy-first context sensing (Place & Motion independent axes) per Blueprint 43.
- **Invariants**:
  1. Pure Kotlin in `:core` (zero Android framework imports).
  2. Absolute Zero Vibration (`vibrate = false`, no `VIBRATE` permission in manifest).
  3. Strict Privacy: Zero SSID, BSSID, or GPS coordinates ever leaked to LLM prompts, context packs, or exports.
  4. Non-polling: Transitions are driven by OS signals; no battery-draining continuous polling.
  5. Vehicle Hold: Conversational nudges held while in `VEHICLE` motion state.
  6. Flap Guard: Temporary Wi-Fi loss while `STILL` retains `HOME` state for 15 minutes.

## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| H1 | Pure Kotlin state machine in `:core` can manage place and motion debounces with injected clock | Unit tests with `FakeClock` can test all transitions, flap guard, and fast path in milliseconds | Test file in `:core/src/test/` | Requires Android classes | CONFIRMED: `:core` state machine works purely with timestamps |
| H2 | DB Schema Version 9 with `place`, `place_signal`, `sensing_event` preserves encrypted migration invariants | `DatabaseMigrationTest` passes V8->V9 migration cleanly | Android SQLite test on device | Table recreation fails or triggers break | CONFIRMED: Standard additive tables |
| H3 | `ContextBuilder` can format `Context: at <Label>, <motion> (since <time>)` while omitting unknown axes | When axis is `UNKNOWN`, prompt omits the axis without empty commas or guessing | Unit test in `ContextBuilderTest` | LLM prompt contains raw BSSID or coords | CONFIRMED: Labels only |
| H4 | NudgeEngine can consume context triggers (`context_left_home`, `context_arrived`, `context_home_again`) without breaking existing 1-open-thread rule | `NudgeEngineTest` confirms trigger priority and vehicle suppression | NudgeEngine unit test | Multiple simultaneous conversational threads opened | CONFIRMED: Normal gate rules apply |

## Angles (mandatory):
- **data/schema**: DB Version 9; `place` table (synced label/kind), `place_signal` (device-local, never exported), `sensing_event` (device-local, 30-day pruned).
- **concurrency/lifecycle**: Signals arrive on background threads; state transitions protected by thread-safe atomic state or mutex in `ContextEngine`.
- **failure paths**: Missing permissions or Wi-Fi off for >30m cleanly marks axis `UNKNOWN` without crash or log spam.
- **user-visible behaviour**: When enabled, chat header shows optional context chip; context nudges fire only when outside-relevant items exist.
- **privacy/security**: SSIDs and GPS coordinates are stored only in device-local `place_signal`; never exported in backups or sent to providers.
- **testability**: Injected Clock and explicit signal events allow 100% deterministic unit tests of flap guards, 90s fast path, and 15m debounce.
- **blast radius**: Zero impact on existing task creation, alarm scheduling, or chat turns; dark-shipped behind `flag.sensing = false` and `sensing.enabled = false`.
- **platform (Android 14, OEM)**: No background location needed for Wi-Fi SSID when user configures saved place; permissions properly checked.
- **model behaviour**: LLM receives high-level coarse label ("Home", "Work", "College"), never raw telemetry.

## Evidence log (commands and outputs)
- Pre-work unit test count: 552/552 PASS (525 core, 27 app). Connected test count: 125/125 PASS.
- Working tree clean at commit `7d5c4f3`.

## Decision: chosen change, rejected options and why
- **Chosen**: Pure Kotlin `ContextEngine` in `core/src/main/java/com/anchor/core/sensing/` with `PlaceAxis` and `MotionAxis`. Schema migration V8->V9 adding `place`, `place_signal`, `sensing_event`.
- **Rejected**: Polling location services in background (violates battery and privacy constraints); sending Wi-Fi names to LLM (violates privacy).

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
1. *Flap guard regression*: Wi-Fi router reboot causes immediate "Left home" alert with errands when user was still sitting on couch. *Mitigation*: Flap guard requires both Wi-Fi loss AND non-still motion before triggering `context_left_home`.
2. *Vehicle alert*: User gets conversational prompt while driving at 60 mph. *Mitigation*: Vehicle hold suppresses all conversational nudges while motion is `VEHICLE`.
3. *Data leak in export*: `place_signal` with home Wi-Fi SSID exported in backup. *Mitigation*: Explicit table whitelist in `AndroidExportManager` and `ReadableMarkdownExporter` excludes `place_signal` and `sensing_event`.
