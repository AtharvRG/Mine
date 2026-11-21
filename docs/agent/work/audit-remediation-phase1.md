# Branching Analysis: Audit Remediation Phase 1 (Critical Core Logic, Data-Loss & Security/Privacy Bugs)

## Goal and Invariant
Remediate all Part 2 critical bugs (Bugs 1-5), Part 3 security/privacy violations (export secrets leak, device_id overwrite, lockscreen notification privacy), Part 4 calendar/export bottlenecks, and Part 6 Item 1 stream recovery & cover-up in `:core` and `:app`.
- Invariant 1: LLM per-turn aliases (`n_1`, `n_2`) and `target_ref` must reliably resolve to existing nodes without falling through to literal title matching.
- Invariant 2: User conversational answers to ambiguity proposals ("the first one", "yes", "tomorrow's") must be accepted when the choice option is valid and message is not negated.
- Invariant 3: `local_only = 1` items must be fully visible in all local user UI queries (Home, Calendar, Urgent, Stale, Insights) and strictly excluded only from remote AI context packs.
- Invariant 4: Mid-stream failover must emit `StreamEvent.Clear` in JSON mode, preventing corrupted JSON envelopes. If model prose claims an action but tool calling fails, silent rule-based cover-up ensures the reminder is created.
- Invariant 5: Denied notification permission must NOT silently delete fired reminders; reminders must be preserved with `outcome = "errored"`.
- Invariant 6: `.mnex` exports must never leak fallback API keys or backup passphrases. Importing an export must never overwrite the target phone's `device_id`.
- Invariant 7: Calendar queries must include completed (`done`) items for historical review.
- Invariant 8: `privacy.hide_notification_content` must apply `NotificationCompat.VISIBILITY_PRIVATE` and a generic public notification version.

## Hypothesis Table (Different Layers)
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | `Gateway.handleResolveChange` fails on `n_1` because it passes the alias directly to `Resolver.resolve` without `resolveRef` | Checking `resolveRef(hint, "n")` and fetching node directly bypasses `Resolver.resolve` and succeeds | Unit test with `propose_change` using `target_ref = "n_1"` and `hint = "n_1"` | Node is not found even with `resolveRef` | CONFIRMED: `Gateway` lacked `resolveRef` on `hint`/`target_ref` in `handleResolveChange` and `handleUpdate`. |
| H2 | `matchesUserMessage` fails because it checks token overlap between user words and option title | Accepting choice when choice is a valid option in `proposal.options` and `!isNegated(userMessage)` allows natural language responses | Unit test with proposal confirmation where user says "the first one" or "yes" | User explicitly negates ("no", "neither") | CONFIRMED: Token overlap rejected non-title words. |
| H3 | `AndroidStorage` SQL queries have hardcoded `local_only = 0` | Removing `local_only = 0` from UI queries while retaining it in `getFtsCandidates` and `ContextBuilder` restores local visibility without prompt leakage | Unit test querying urgent/home nodes with `local_only = 1` | `local_only` items leak to LLM prompt | CONFIRMED: SQL queries filtered `local_only = 0`. |
| H4 | Mid-stream failure without `StreamEvent.Clear` leaves truncated JSON in `ChatController` buffer | Emitting `StreamEvent.Clear` unconditionally on failover in `ProviderOrchestrator` allows fallback target to stream clean JSON | Unit test simulating 5xx after 5 tokens of JSON | Clear wipes previous turns | CONFIRMED: Buffer concatenation caused JSON parse failure. |
| H5 | `AlarmReceiver` calls `deleteReminder` even if `NotificationManagerCompat.areNotificationsEnabled()` is false | Checking `areNotificationsEnabled()` and aborting delete when false preserves reminder | Unit test / code review in `AlarmReceiver` | Notifications enabled check throws | CONFIRMED: Android drops notification silently while DB deletes row. |

## Angles (Mandatory)
- **data/schema**: No schema bumps needed; queries modified to include `local_only` in UI and `done` in calendar.
- **concurrency/lifecycle**: Failover streaming thread safety; atomic reminder state updates.
- **failure paths**: Network drop mid-stream; notifications disabled by user; corrupted export payloads.
- **user-visible behaviour**: "The first one" now resolves proposals; local_only tasks show on Home; calendar shows completed tasks; lockscreen content hidden when enabled.
- **privacy/security**: Zero API keys or passphrases in `.mnex` exports; device_id preserved; lockscreen notification privacy respected.
- **testability**: Pure Kotlin tests in `:core:test` for Gateway, Resolver, Storage, and ProviderOrchestrator.
- **blast radius**: `Gateway`, `Storage`, `AndroidStorage`, `SharedFakeStorage`, `ProviderOrchestrator`, `AlarmReceiver`, `AndroidExportManager`.
- **platform (Android 14, OEM)**: `NotificationManagerCompat.areNotificationsEnabled()`, `NotificationCompat.VISIBILITY_PRIVATE`.
- **model behaviour**: Real LLMs send `"target_ref": "n_1"` and users reply with "the first one" or "yes".

## Decision
Implement the Phase 1 fixes across `:core` and `:app`:
1. Resolve `target_ref` / `hint` via `aliasRegistry.resolveRef` in `Gateway` `handleResolveChange` and `handleUpdate`.
2. Update `matchesUserMessage` to validate option existence and negation instead of strict lexical word overlap.
3. Remove `local_only = 0` filter from local UI queries in `AndroidStorage` and `SharedFakeStorage`.
4. Ensure `ProviderOrchestrator` always emits `StreamEvent.Clear` on failover, and wire silent quick-add cover-up in `ChatController` if action claimed.
5. In `AlarmReceiver`, guard reminder deletion with `areNotificationsEnabled()`; record `outcome = "errored"` when blocked.
6. Scrub fallback keys, passphrases, and preserve `device_id` in `AndroidExportManager`.
7. Include `status = 'done'` in `getCalendarBuckets`.
8. Enforce `privacy.hide_notification_content` in `AlarmReceiver`.
9. Stream `ReadableMarkdownExporter` with `BufferedWriter`.

## Pre-Mortem ("It is a week later and this fix caused a bug; what was it?")
1. *Risk*: Removing `local_only = 0` from `AndroidStorage` leaks private items into AI prompts.
   *Mitigation*: Verify that `ContextBuilder` and `HybridRetriever` explicitly retain the `local_only == 0` filter.
2. *Risk*: Accepting any option in `matchesUserMessage` causes accidental confirmations when user says something unrelated.
   *Mitigation*: Check `isNegated` and require either valid option selection by LLM, ordinal/number match, or general affirmative intent ("yes", "first", "sure", "confirm").
3. *Risk*: Emitting `StreamEvent.Clear` on failover wipes text in plain text streaming mode.
   *Mitigation*: In plain text mode, only clear if partial text is incomplete or user hasn't consumed it. In JSON envelope mode, clearing is mandatory because partial JSON cannot be continued.
