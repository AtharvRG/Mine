# Branching Analysis: Ambient "Remember Last 2 Minutes" Bookmark (v4 / P-019 / Blueprint 61 §9)

## Goal and invariant
- **Goal**: Implement the "Remember the last 2 minutes" ambient bookmark (P-019, Blueprint 61 §9) in both the Ambient notification and the in-app UI. This allows the user to manually trigger the presence gate for the preceding 120 seconds of audio/transcription buffer when a short exchange or low-confidence dialogue was missed by automatic presence detection.
- **Invariants**:
  - Pure Kotlin in `:core` (zero Android dependencies).
  - Absolute zero raw audio persisted on disk or SQLite (in-memory RAM ring buffers only).
  - Zero vibration across all notification channels.
  - Proposal-only invariant: extracted items are strictly `status = "proposed"`, `origin = "ambient"`. Never mutates or closes active items.
  - Bounded RAM ring buffer: segments pruned at 120s TTL (never unbounded memory growth).

## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| H1 | Adding `manualBookmark: Boolean` to `PresenceGate.evaluateWindowGate` allows manual override without relaxing automatic safety thresholds | Gate opens when `manualBookmark == true` even if all segments are `SpeakerLabel.OTHER` | Unit test in `:core` | Automatic gate opens without user speech when `manualBookmark == false` | Confirmed |
| H2 | Retaining `recentTurns` for 120s in `AmbientForegroundService` enables retroactive dialogue window assembly without keeping raw PCM audio | Transcripts of turns within $[now - 120s, now]$ are available in RAM | Measure memory footprint of 40 Turn objects | Memory leaks or raw audio stored | Confirmed (< 100 KB RAM) |
| H3 | `AmbientProposalExtractor.fallbackRuleBasedExtraction` with `manualBookmark = true` should inspect all turns for commitments and flag them with `manual_bookmark` | Commitments in non-user turns are captured with `attribution_uncertain` and `manual_bookmark` flags | Unit test with other-only dialogue | Misses commitments or assigns high confidence to non-user speech | Confirmed |
| H4 | Adding a `"Remember 2m"` action to the foreground service notification and a button in `AmbientControlCard` allows 1-tap capture from anywhere | Notification and UI button trigger `ACTION_BOOKMARK_2M` | Broadcast/service intent test | Crashes on background execution or exceeds 3 notification actions | Confirmed |

## Angles (mandatory)
- **data/schema**: No schema migration needed. `flags` column in `ambient_report_item` already holds comma-separated strings (`attribution_uncertain`, `possible_duplicate`, `manual_bookmark`).
- **concurrency/lifecycle**: Guarded under `serviceScope` in `AmbientForegroundService` and `sendMutex` in `ChatController`.
- **failure paths**: If `recentTurns` is empty (silence in last 2m), shows calm Toast/receipt: "No speech detected in the last 2 minutes" without crashing or saving empty reports.
- **user-visible behaviour**: Notification shows "Remember 2m" action; Ambient card shows "Remember Last 2 Min" button; captured items appear in today's Ambient report card with "manual_bookmark" badge.
- **privacy/security**: Zero audio on disk; number scrubbing applies to all bookmark text before storage; proposal-only.
- **testability**: Unit tests in `:core` (`PresenceGateTest`, `AmbientProposalExtractorTest`, `DialogueWindowEngineTest`) + app/device tests.
- **blast radius**: Confined to ambient listening subsystem (`:core:ambient` and `app/src/main/java/com/anchor/mine/ambient/`).
- **platform (Android 14, OEM)**: Notification PendingIntent uses `FLAG_IMMUTABLE` and `PendingIntent.getService` or `BroadcastReceiver`; service handles intent without restart.
- **model behaviour (what a real LLM would send)**: Proposal extractor handles both LLM JSON response and deterministic rule-based fallback.

## Decision: chosen change, rejected options and why
- **Chosen**:
  1. Core: In `PresenceGate`, add `manualBookmark: Boolean = false` to `evaluateWindowGate`. In `AmbientProposalExtractor`, add `manualBookmark: Boolean = false` to extract commitments from all turns in the bookmark window and tag with `manual_bookmark`. In `DialogueWindowEngine`, add `createBookmarkWindow(turns, nowMs, windowDurationMs = 120_000L)`.
  2. Service: In `AmbientForegroundService`, add `ACTION_BOOKMARK_2M`, prune `recentTurns` at 120s, add "Remember 2m" notification action, implement `bookmarkLast2Minutes()`.
  3. UI: In `ChatContract`, add `UiAction.BookmarkAmbientLast2Min`. In `AmbientControlCard`, add "Remember Last 2 Min" button. In `ChatController`, handle action with transient receipt and UI refresh.
- **Rejected**:
  - Keeping raw audio for 2 minutes: rejected because Blueprint 61 §1 Invariant 2 strictly forbids storing audio. Ring buffer holds transcribed & scrubbed `Turn` objects only.
  - Auto-accepting bookmarked proposals: rejected because Proposal-Only Invariant requires user review.

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
1. *Pre-mortem 1*: User taps "Remember 2m" multiple times in quick succession, creating duplicate proposals.
   - *Mitigation*: The existing `existingSummaries` deduplication check against `storage.getAmbientReportItems(report.id)` prevents duplicate items from being inserted.
2. *Pre-mortem 2*: Memory leak from `recentTurns` growing indefinitely if the service runs for days.
   - *Mitigation*: Time-based pruning `recentTurns.removeAll { it.tEndMs < sliceEnd - 120_000L }` runs on every 3-second slice, strictly capping the buffer at 120 seconds.
3. *Pre-mortem 3*: Notification action fails on Android 14 due to background service start restrictions.
   - *Mitigation*: The service is *already running* in the foreground when the notification action is tapped, so `getService` delivering an intent to an existing foreground service is allowed. Defensively, also wire via receiver/activity if needed.
