# HANDOFF (P1-B 16 Complete — Reviewed, Verified, Fixes Applied; Next: P1-D 20)

## Current Work & Status
- **Task #106 / P1-B 16 [COMPLETED]**: UPDATE and RESCHEDULE intents through gateway and resolver, with undo. RESCHEDULE was already implemented in R1; this task implemented UPDATE end-to-end.
- Independent review verdict: **Approve with suggestions** — 3 MEDIUM findings, all fixed this session with regression tests.
- Independent verification verdict: **PASS** (ran on pre-fix code; fixes covered by 3 new tests + full green re-run).
- Core suite: 231/231 passed, 0 compiler warnings. App unit suite: 9/9 passed.

## 1. What Changed (all uncommitted in working tree)
- `core/src/main/java/com/anchor/core/gateway/Gateway.kt`:
  - `propose_change` intent UPDATE routes to new `handleUpdate` (Gateway.kt:850) — previously returned "UPDATE is not supported yet".
  - `handleUpdate`: requires `hint`; parses `new_title`, `due_text`/`new_due_text`, `priority`, `severity`, `pinned`, `tier` (alarm|gentle); errors "No changes specified for UPDATE..." if nothing requested; resolves due text via DateResolver (NeedsClarification -> `kind="time"` proposal carrying full ProposalPayload + NeedsTime chips; PastTime/Failed -> Error).
  - `resolveAndUpdate` (Gateway.kt:914): pages active nodes (200/5000 cap), Resolver over candidates; Ambiguous -> `kind="ambiguous"` proposal with payload + chips; NotFound/ClearWinner paths; status guard ("active"/"in_progress"/"paused").
  - `applyNodeUpdate` (Gateway.kt:973): null-merge of fields onto node copy; reminder sync (text<-title, fireAt<-dueAt, tier only if explicitly set); creates reminder when undated node gains due; RawEvent UPDATE with before/after NodeStateSnapshot for undo.
  - `handleConfirmChange` UPDATE branches: ambiguous node-choice (payload decode, past-time guard, all-null guard) and time-chip (targetId==null -> resolveAndUpdate with chosen millis; non-null keeps legacy schedule_reminder shift).
  - **Review fix 1**: dead `deleteReminderId` branch removed from `applyNodeUpdate` (was unreachable: `effectiveDue == null` implies `oldNode.dueAt == null`, contradicting the guard's `oldNode.dueAt != null`).
  - **Review fix 2**: tier-only UPDATE on an undated node returns honest Error "Cannot change tier: 'X' has no due date, so there is no reminder to change. Set a due time first." (consuming the proposal on the confirm path) instead of a false Committed.
  - **Review fix 3**: blank `new_due_text` no longer shadows a populated `due_text` — both normalize to null when blank before the elvis fallback (`new_due_text ?: dueText`).
- `core/src/main/java/com/anchor/core/models/Memory.kt`: `ProposalPayload.newTitle: String? = null`.
- `core/src/main/java/com/anchor/core/resolver/Resolver.kt`: STOPWORDS += update/rename/change/edit/mark; exact-match strip regex += update|rename.
- `core/src/main/java/com/anchor/core/ui/ChatController.kt`: system prompt UPDATE tool docs + UPDATE_RENAME/UPDATE_TIME/UPDATE_PIN examples; UPDATE added to the intent enumeration (line 517).
- `core/src/test/java/com/anchor/core/gateway/P1B16UpdateIntentTest.kt`: 17 tests (14 original + 3 new fix regressions).

## 2. What Is Verified (command + result)
- `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL (exit 0) — 231/231 core (37 suites, P1B16 17/17), 9/9 app (counts from build/test-results XML), 0 warnings.
- New regression tests: `testUpdateTierOnly_UndatedNode_ReturnsErrorNoMutation` (direct path: Error, no mutation, no event, no reminder), `testUpdateTierOnly_UndatedNode_ViaAmbiguousConfirm_ConsumesProposalAndErrors` (confirm path: Error + proposal consumed, both nodes untouched), `testUpdateBlankNewDueText_FallsBackToDueText` (blank new_due_text falls back to due_text; due + reminder move to 4h).
- Independent verification (pre-fix code): PASS — 228/228 core, 9/9 app, 0 new warnings, all 14 scenarios present with post-state assertions, 8 adversarial probes OK (replay confirm -> "Proposal already resolved"; ghost id -> "Invalid choice" with proposal kept open; 8 parallel confirms -> exactly 1 committed; stale undo -> "Node state has changed"; double undo -> "Already undone"; exact-24h undo ok / beyond -> "Cannot undo changes older than 24 hours."; orphan token not found; past chip refused; 10K title truncated to 200; blank title -> Error).
- Independent review: APPROVE with suggestions; all 3 MEDIUM findings fixed this session (see section 1). LOW findings left as-is (unclamped priority/severity consistent with CREATE; corrupted-payload error text; "Mark's report" stopword edge; optional test gaps).

## 3. NOT Verified
- Connected/instrumented tests NOT re-run this session (device not invoked). Last connected run: 53/53 (P1-B 15 session).
- The three MEDIUM fixes passed a full green re-run with new regression tests but were NOT independently re-verified by a second verification-agent pass.
- No live-model smoke of UPDATE utterances yet ("rename X to Y", "move X to 5pm").

## 4. Current Failing Tests
- None (231 core + 9 app green).

## 5. Open Decisions Needing User
- None new. (D-121 still free in DECISIONS.md if you want the UPDATE design recorded as a decision entry.)

## 6. Exact Next Step
- `git commit -s` the P1-B 16 changes (one commit), then start **P1-D 20** (Home priority list, at-risk flag, active threads, recently-closed with undo validity, structured chat items) per docs/agent/TASKS.md line 73.

## 7. Traps for the Next Run
- `ProposalPayload.targetId` on time proposals is the discriminator in handleConfirmChange: non-null means legacy schedule_reminder shift, null means propose_change flow. Don't "simplify" this without checking both call sites.
- `applyNodeUpdate` tier semantics: tier only changes when explicitly passed; resolver/undo paths rely on `NodeStateSnapshot` equality — partial hydration of reminder fields breaks undo (see LEARNINGS: partial column hydration). If a "clear due date" feature is requested, wire a reminder-delete branch back into `applyNodeUpdate` (the dead guard was removed; the R10-style `deleteReminderId` parameter on `updateNodeAndCommit` is the hook).
- `R1RescheduleAmbiguityTest` now asserts the NEW error text; any change to the "No changes specified for UPDATE..." message breaks two tests there plus one in P1B16UpdateIntentTest.
- Tier-only UPDATE on an undated node errors ("no due date... no reminder to change"); two P1B16 tests pin this text.
- UPDATE cannot currently REMOVE a due date (only set/move it). If requested, see trap 2 for the hook.
- Don't re-add a past-time re-check after the DateResolver when-branch: `DateResolution.Exact` is future-only; the check re-introduces the "Condition is always 'true'" warning.

## Operating Rules
- Commit per task using `git commit -s`. Never commit failing tests. One task per run; stop and await user approval before proceeding to the next task.
