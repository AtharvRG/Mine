# Current Task: P1-B 15 (Reminder Policy per Blueprint 30)

Status: Completed (Verified with Unit Tests & Connected Android Tests)

## Objective
Implement P1-B 15: Reminder policy per blueprint 30 (lead times, reminder only when asked or per default). Document the choice with the section number.

## Blueprint references
- `docs/blueprints/30-reminders.md`:
  - s3.1: "A reminder is a scheduled alarm or notification pointing at a node... Explicit user temporal commands create reminders by default (lead time = 0 at `dueAt` for dated tasks); undated items create no alarms."
  - s3.2: "Relative lead times: offset expressions anchored to node dueAt ('15 minutes before', '1 hour before')."
  - s3.3: "Duplicate reminder policy: If a reminder for the same node already exists within 30 minutes, schedule_reminder returns needs_confirm asking 'A reminder is already set for <existing>. Update it to <new>, or cancel?' with options to update or cancel. Quiet hours: Explicit user reminders ring even outside the day window (requested-class). AI-suggested or auto-created reminders outside the active day window are moved to day_start + 30 min."
  - s3.4: "Vague day-parts: morning (07:00..11:00), afternoon (12:00..16:00), evening (17:00..20:00), night (20:00..23:00). Position inside window determined by node severity: Sev 3 = 10%, Sev 2 = 35%, Sev 1 = 60%, Sev 0 = 75%. Soon = now + 30m, Later = now + 2h."
  - s3.9: "Reminder settings & default lead times."
- `docs/blueprints/52-settings.md` line 174:
  - `reminder.ai_suggest`: "ask" (default) | "auto". In "ask" mode, AI-inferred reminders return `needs_confirm` proposal with chips `[Set <time>]`, `[Change]`, `[No]`. In "auto" mode, creates immediately with undo.
- Decision D-035: Explicit reminders ring even in quiet hours.

## Scope & Implementation Completed
1. Deterministic Temporal Resolution (`DateResolver.kt`):
   - Added `severity: Int? = null` parameter to `DateResolver.resolve`.
   - Vague day-part calculation based on standard windows and severity percentage:
     - Morning (07:00..11:00, 240m): Sev 3 -> 07:24, Sev 2 -> 08:24, Sev 1 -> 09:24, Sev 0 -> 10:00, default midpoint 09:00.
     - Afternoon (12:00..16:00, 240m): Sev 3 -> 12:24, Sev 2 -> 13:24, Sev 1 -> 14:24, Sev 0 -> 15:00, default midpoint 14:00.
     - Evening (17:00..20:00, 180m): Sev 3 -> 17:18, Sev 2 -> 18:03, Sev 1 -> 18:48, Sev 0 -> 19:15, default midpoint 18:30.
     - Night (20:00..23:00, 180m): Sev 3 -> 20:18, Sev 2 -> 21:03, Sev 1 -> 21:48, Sev 0 -> 22:15, default midpoint 21:30.
     - "soon" -> `now + 30 minutes`.
     - "later" -> `now + 2 hours`.
   - Added `DateResolver.parseOffsetDuration(text)` to handle relative lead time offsets ("30 minutes before", "1 hour before", etc.).
2. Gateway Policy Enforcement (`Gateway.kt`):
   - In `handleScheduleReminder`:
     - Resolves relative lead time offsets (`offset_text` or relative phrases) anchored to `node.dueAt` (`fireAt = node.dueAt - offsetMillis`).
     - Detects near-duplicate reminders on the same node within 30 minutes (`Math.abs(existing.fireAtMillis - fireAt) <= 30 * 60 * 1000L`). Emits `ToolResult.NeedsConfirm` with options `["Update existing", "Cancel"]` and persists proposal.
     - Respects quiet hours: explicit user reminders remain at requested fire time (requested-class). AI-inferred reminders (`inferred == true`) outside `dayRule.startMinute..dayRule.endMinute` shift to `day_start + 30 min`.
     - Checks `reminder.ai_suggest` setting for AI-inferred reminders: `ask` (default) returns `ToolResult.NeedsConfirm` with options `[Set <time>, Change, Cancel]`; `auto` creates reminder directly with undo capability.
   - In `handleConfirmChange`:
     - Resolves duplicate reminder update proposals: `"update"` updates existing reminder timestamp and tier, `"cancel"` consumes proposal via CAS with 0 mutations.
3. Verification & Evidence:
   - Dedicated unit tests in `P1B15ReminderPolicyTest.kt` (7 tests, 100% pass):
     - `testVagueDayParts_MorningWindowAndSeverity`
     - `testVagueDayParts_EveningWindowAndSeverity`
     - `testVagueDayParts_SoonAndLater`
     - `testScheduleReminder_RelativeLeadTime_AnchoredToNodeDueAt`
     - `testScheduleReminder_DuplicateWithin30Minutes_ReturnsNeedsConfirm`
     - `testScheduleReminder_QuietHoursAdjustment_AiInferredOnly`
     - `testScheduleReminder_AiSuggestSetting_AskVsAuto`
   - Core unit tests: 214/214 passed (100%).
   - App unit tests: 9/9 passed (100%).
   - Connected tests on physical phone (Motorola moto g73 5G - Android 14): 53/53 tests passed (100%) across 10 test suites.
   - Decision D-120 recorded in `docs/agent/DECISIONS.md`.

## Next Task
P1-B 16: UPDATE and RESCHEDULE intents implemented through gateway and resolver, with undo.
