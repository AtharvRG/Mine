# Branching Analysis: v2 Call Nudge (Blueprint 44)

## Goal and invariant
- **Goal**: Implement deterministic, privacy-first post-call nudge hooks and lifecycle per Blueprint 44.
- **Invariants**:
  1. Pure Kotlin in `:core` (zero Android framework imports for business logic, timing, coalescing, and gate evaluation).
  2. Absolute Zero Audio: Never access, record, or buffer call audio (strictly impossible and prohibited).
  3. Absolute Zero Phone Numbers: Never read, store, or log phone numbers in SQLite, memory, or prompts.
  4. Coarse & Non-Intrusive: Calls < 45s (or missed/rejected) produce zero nudges; calls within 10 min coalesce into 1 candidate; expires in 3h.
  5. 3-Ignore Stop: 3 ignored call nudges in a row triggers an automatic preference query rather than nagging.
  6. Device-Local Data: `call_event` table is device-local, excluded from exports/backups, and pruned after 30 days.

## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| H1 | Pure Kotlin `CallNudgeEngine` can handle off-hook -> idle duration calculation, coalescing, and 3-ignore backoff | Tests with `FakeClock` can verify all state transitions, short call drop, and 10-min coalesce | Unit test in `:core/src/test/` | Requires Android classes | CONFIRMED: Pure state machine |
| H2 | DB Schema Version 10 adds `call_event` with zero phone numbers, and auto-excludes from export whitelist | `AndroidExportManager.EXPORTED_TABLES` excludes `call_event` | Inspection & unit test | `call_event` exported in backup | CONFIRMED: Whitelist excludes it |
| H3 | PhoneState receiver in `:app` catches `TelephonyManager.EXTRA_STATE_IDLE` and dispatches to `CallNudgeEngine` without background crashes | Receiver runs safely with permission guards and dark-ship flag `flag.call_nudge` | Robolectric/Unit + AndroidManifest check | Crash if permission missing | CONFIRMED: Safe try-catch & flag check |
| H4 | User reply to call nudge tags `attrs.reply_to` with nudge ID and `source = "call_nudge"` for node creation | Extracted tasks carry `source = "call_nudge"` without forcing `proposed` status | Integration test in `ChatController` | Auto-accept broken or forced proposed | CONFIRMED: Normal user words path |

## Angles (mandatory):
- **data/schema**: DB Schema Version 10; `call_event` table (`id`, `started_at`, `ended_at`, `duration_s`, `direction`, `contact_ref`, `nudge_id`, `state`, `created_at`). Indexed on `ended_at`.
- **concurrency/lifecycle**: Telephony broadcasts arrive asynchronously on background threads; engine synchronizes state transitions.
- **failure paths**: Missing `READ_PHONE_STATE` permission or rapid call bursts handled gracefully without crash.
- **user-visible behaviour**: Notification with "Reply", "Voice note", "Skip", or inline message if app is open.
- **privacy/security**: Zero phone number stored. Contact display name only if optional `know_contact` is enabled, never sent in prompts.
- **testability**: Injected Clock and explicit call event signals enable 100% deterministic test coverage.
- **blast radius**: Zero impact on regular chat, alarms, or existing nudges; dark-shipped behind `flag.call_nudge = false`.
- **platform (Android 14, OEM)**: Android 14 `READ_PHONE_STATE` install/runtime permission handling.
- **model behaviour**: LLM receives user's typed or spoken reply, not telemetry or raw call data.

## Evidence log (commands and outputs)
- Pre-work unit test count: 562/562 PASS (535 core, 27 app).
- Working tree clean at commit `ea4117b`.

## Decision: chosen change, rejected options and why
- **Chosen**: Pure Kotlin `CallNudgeEngine` in `core/src/main/java/com/anchor/core/nudge/CallNudgeEngine.kt`. DB Version 10 adding `call_event` table and 30-day retention pruning.
- **Rejected**: Listening to VoIP calls via notification listener (requires high-privilege restricted permissions); recording call audio (violates core security invariants).

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
1. *Nagging on dialer drops*: User dials a number, rings twice, hangs up after 10s, but off-hook was triggered. *Mitigation*: Minimum duration filter strictly enforces $\ge 45$s duration.
2. *Multiple rapid calls produce notification storm*: User has 3 quick back-to-back calls in 5 minutes. *Mitigation*: 10-minute coalescing window groups them into a single post-call nudge.
3. *Stale call nudge wakes user at 6 AM*: Call ended at 11 PM, delivered at 6 AM. *Mitigation*: 3-hour hard expiry drops stale candidates.
