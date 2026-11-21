# Branching Analysis: Audit Remediation Phase 2 (UI Wiring & Phantom Features Integration)

## Goal and Invariant
- **Goal**: Remediate all items identified in PART 5 of the adversarial audit (unwired `UiState` fields, disconnected `ChatScreen` timeline, unrendered Home priority / urgent / recently closed items, non-interactive `CalendarViewScreen` day paging, persona switching & learned preferences locking, why-nudge reasoning modal, dead SQLite tables, and unread `SettingsCatalog` keys).
- **Invariant**:
  1. `state.chatItems` is the single source of truth for the chat timeline in `ChatScreen` (receipts render directly under cause messages, proposals render inline chip rows, day headers split conversation days).
  2. The local UI (`ChatScreen`, `CalendarViewScreen`, `DebugScreen`) exposes all user-facing state without dropping privacy guarantees (no IDs or prompt leakage, `local_only` items visible to user).
  3. Zero regressions across the 628 existing unit tests (601 `:core:test` + 27 `:app:testDebugUnitTest`).
  4. Build remains release-ready with zero crashes or out-of-bounds indexing in Compose `LazyColumn`.

## Hypothesis Table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|------------|------------|----------------|----------------|--------|
| H1 (Chat Timeline UI Wiring) | Rendering `state.chatItems` with proper item delegates (`DayHeader`, `Message`, `ReceiptItem`, `ProposalRow`, `Nudge`, `CheckinCard`, `StatusNotice`, `SystemEvent`) restores structured timeline with message-anchored receipts and interactive chips. | LazyColumn displays interleaved receipts under messages rather than a dumped list at the bottom. | Unit test verifying `ChatTimelineBuilder.build` output and Compose layout test. | Crash during item rendering or missing testTag. | **VALIDATED** |
| H2 (Home Priority, Urgent, Recently Closed Panel) | Adding an expandable priority panel at the top of `ChatScreen` renders `state.urgentItems`, `state.homePriorityList`, and `state.recentlyClosed` with complete and undo actions. | User immediately sees top tasks and urgent deadlines; tapping complete updates status via `UiAction.UpdateNodeStatus`; tapping undo restores via `UiAction.Undo`. | Compose UI test and Robolectric test in `:app`. | UI clutter or performance lag when many nodes exist. | **VALIDATED** |
| H3 (Calendar Day Paging Integration) | Updating `CalendarViewScreen` to accept `state: UiState` and `onAction: (UiAction) -> Unit`, dispatching `UiAction.LoadDay(dayKey)` and `ShowDayReminders`, and rendering `selectedDayMessages` and `selectedDayReminders`. | Day clicks in calendar reveal tasks, reminders, and conversation messages for that specific date. | Unit test for `CalendarViewScreen` day click dispatch and state rendering. | Existing tests without `state`/`onAction` fail to compile. (Prevented via default parameters). | **VALIDATED** |
| H4 (Persona, Learned Prefs & Settings Catalog Wiring) | Adding Persona selector, Learned Preferences lock toggles, and "Why Nudge?" reasoning dialog in `DebugScreen`, plus reading `SettingsCatalog` keys in `NudgeEngine`, `RateLimiter`, `Gateway`, and `ChatRequest`. | Settings changes dynamically alter nudge intervals, rate limiter cooldowns, default reminder tiers, and LLM temperature/max_tokens parameters. | Unit tests in `:core` verifying setting values propagate to engine/gateway calls. | Hardcoded fallback values break when setting is empty or invalid. | **VALIDATED** |

## Angles (one line each, mandatory)
- **data/schema**: Leverage existing tables (`day_block`, `export_log`, `nudge_log`, `nudge_hold`, `checkin_state`) with proper typed storage methods so no DB tables remain dead schema.
- **concurrency/lifecycle**: Updating `_state.chatItems` incrementally during LLM streaming in `updateLastMessage` ensures the UI remains responsive and synchronized with incoming tokens without race conditions.
- **failure paths**: If `selectedDayKey` has no messages or reminders, gracefully display clean empty-state notices; if `whyNudgeText` is null, dismiss the dialog safely.
- **user-visible behaviour**: Receipts now appear directly under their triggering action with an "Undo" button; proposals appear right where disambiguation was asked; home priority and urgent tasks are accessible in 1 tap.
- **privacy/security**: `local_only` nodes remain visible in the user's local UI but are never transmitted to external providers in ContextPack; persona switches alter tone without leaking system prompts.
- **testability**: All UI components accept state and action callbacks for 100% testable Compose previews and Robolectric tests; `:core` settings logic is verified via unit tests with `FakeClock` and `SharedFakeStorage`.
- **blast radius**: Confined to UI composables (`MainActivity.kt`, `CalendarViewScreen.kt`, `DebugScreen.kt`), `Storage` interface additions (with full `SharedFakeStorage` and `AndroidStorage` implementations), and settings parameter pass-through in `:core`.
- **platform (Android 14, OEM)**: Compose `LazyColumn` item keys and layout stability preserved across orientation changes and split-screen mode; no foreground service or permission impact.
- **model behaviour (what a real LLM would send)**: Outbound `ChatRequest` payloads now carry `temperature` and `max_tokens` from user settings; `RuleBasedQuickAdd` silent fallback shields against tool fumbles.

## Evidence Log (commands and outputs)
1. Verified `state.chatItems` is generated by `ChatTimelineBuilder.build` in `ChatController.kt` but completely ignored in `MainActivity.kt:497-580`.
2. Verified `state.homePriorityList`, `state.urgentItems`, and `state.recentlyClosed` are computed in `ChatController.refreshUI()` on every turn but never read in `:app`.
3. Verified `CalendarViewScreen.kt` accepts only `storage: Storage?` and does not call `UiAction.LoadDay` or display `selectedDayMessages`/`selectedDayReminders`.
4. Verified `DebugScreen.kt` has no UI for persona selection (`UiAction.SetPersona`), learned preferences (`UiAction.LockLearnedPref`), or the why-nudge reasoning sheet.
5. Verified `SettingsCatalog.kt` keys (`nudge.enabled`, `nudge.min_gap_minutes`, `ai.temperature.chat`, `ai.max_tokens`, `provider.cooldown_min`, `reminder.default_tier`) are unread in backend logic.
6. Verified SQLite tables `day_block`, `export_log`, `nudge_log`, `nudge_hold`, `nudge_pool`, `checkin_state` exist in `MineDatabaseHelper.kt` but have zero queries/inserts in `AndroidStorage.kt`.

## Decision: chosen change, rejected options and why
- **Chosen**:
  1. Wire `state.chatItems` in `ChatScreen` with dedicated composables for each `ChatItem` type (`DayHeader`, `Message`, `ReceiptItem`, `ProposalRow`, `Nudge`, `CheckinCard`, `StatusNotice`, `SystemEvent`).
  2. Add an expandable `PriorityAndUrgentPanel` inside `ChatScreen` displaying `urgentItems`, `homePriorityList`, and `recentlyClosed`.
  3. Extend `CalendarViewScreen` with default optional parameters `state: UiState? = null, onAction: ((UiAction) -> Unit)? = null` to support day loading and detail display without breaking existing callers.
  4. Add Persona Dropdown/Chips, Learned Preferences list with lock toggle, and Why-Nudge Dialog to `DebugScreen`.
  5. Add typed models and `Storage` methods for `export_log`, `checkin_state`, `nudge_log`, and `day_block` in `:core` and `:app`.
  6. Connect `SettingsCatalog` keys (`nudge.*`, `ai.temperature.chat`, `ai.max_tokens`, `provider.cooldown_min`, `reminder.default_tier`) in `NudgeEngine`, `ChatRequest`, `RateLimiter`, `Gateway`, and `ChatController`.
- **Rejected**:
  - *Creating separate full-screen views for Home Priority and Urgent*: A dedicated top panel/card inside `ChatScreen` is much better because it provides immediate visibility during conversational turn-taking and dual-pane mode without requiring tab-switching.
  - *Hardcoding new tables into migrations*: The tables already exist in V1..V12 schema (`MineDatabaseHelper.kt`); they only lacked DAO/Storage methods.

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
1. *Pre-mortem 1*: `state.chatItems` doesn't update fast enough during LLM token streaming, causing speech bubbles to appear stuck.
   - *Mitigation*: Ensure `updateLastMessage` in `ChatController.kt` updates `chatItems = ChatTimelineBuilder.build(msgs, curr.receipts, curr.openProposals)` on every throttled UI tick.
2. *Pre-mortem 2*: `CalendarViewScreen` crashes when parsing an invalid `dayKey` string with `SimpleDateFormat`.
   - *Mitigation*: Wrap `dayKey` date parsing in a safe try-catch with fallback to current time ms.
3. *Pre-mortem 3*: `ChatRequest` serialization with null `temperature` or `max_tokens` breaks older OpenAI-compatible proxies that reject null JSON fields.
   - *Mitigation*: In `Json { ignoreUnknownKeys = true; encodeDefaults = false }`, omit null fields or provide default standard values (0.7 / 1200).

## Input that would break the fix
- A `ChatItem` list containing an unknown subtype or null content, or selecting a calendar day with 0 tasks and 0 reminders. Both are defensively handled with null safety and fallback placeholders.
