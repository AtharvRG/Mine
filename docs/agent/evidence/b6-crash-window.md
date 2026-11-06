# B6 — Crash window: proposal consumed but effect not committed (2026-11-06)

Task spec (user batch): "Add a test of the crash window: proposal consumed but
effect not committed. Record the observed behaviour and propose a startup/inbox
note. Do not pick (a) or (b) yet; show a design sketch for (b)."

This is a **characterization + design sketch**, not a fix. Nothing in production
code changed for B6. Option (a) vs (b) is **not** recorded in DECISIONS.md.

## 1. Taxonomy of the window

The confirm path performs the effect inside a transaction with an in-transaction
CAS on the proposal (D-118): the proposal is consumed only inside
`createNodeAndCommit` / `updateNodeAndCommit` / `createReminderAndCommit`, and a
guarded catch in `Gateway.handleConfirmChange` reopens the proposal if the effect
throws. Two distinct failure shapes remain:

| Path | What happens | Covered by |
|---|---|---|
| EXCEPTION (effect transaction throws) | Guarded rollback reopens the proposal; nothing is lost | `testCrashWindow_EffectTransactionThrows_ProposalRemainsOpenAndNoEffectRow` (R12ConcurrencyLifecycleTest, D-118) |
| HARD KILL (process dies between two durable writes, no exception) | No catch/finally runs; the proposal stays `consumed` with no effect row | **`B6CrashWindowTest` (this task)** |

## 2. Reachable hard-kill windows in current code (Gateway.kt)

- Near-match dedupe pair, **Gateway.kt:607-608**: `consumeProposal(old)` then
  `saveProposal(replacement)` — two separate durable writes. Proven reachable by
  `testCrashWindow_NearMatchDedupe_ConsumePersistsBeforeReplacementProposalSave`.
- Terminal bare-consume sites (consume is the last write, effect is a pure
  rejection — killing after these loses no user data, only the trace):
  Gateway.kt:429-432 (bad target), 510/518 (needs_confirm node gone),
  636-637 (UPDATE missing hint), 708-709 (RESCHEDULE past time),
  733-734 / 739-741 (UPDATE past time / no changes).

## 3. Observed behaviour after a hard kill mid-window

Recorded by `B6CrashWindowTest.testCrashWindow_ConsumedWithoutEffect_SilentlyVanishesAfterRestart`
(`core/src/test/java/com/anchor/core/gateway/B6CrashWindowTest.kt`):

1. The proposal row survives the kill, permanently `status="consumed"`.
2. It is invisible to `getOpenProposals()` — no chip is rendered anywhere.
3. The system prompt's Pending Clarifications section reads "No open proposals."
   — the model cannot see that a clarification was pending.
4. Effect not committed: no node, no raw_event, no receipt, no undo trace.
5. Re-tapping the chip after restart is rejected: `confirm_change` returns
   `ToolResult.Error("Proposal already resolved")` — the user's confirmed choice
   is silently lost.
6. After 24h expiry even the forensic trace is gone: `getProposal` returns null.

Both fake (SharedFakeStorage) and real (AndroidStorage) storage share this
lifecycle: created_at = now, 24h read-level expiry, unconditional
`status='consumed'` UPDATE with no precondition — so the characterization is
portable to device behaviour.

## 4. Design sketch — option (b): startup/inbox reconciliation note

**Goal:** after a restart, surface the loss to the user instead of silently
swallowing it. This is the "startup/inbox note" option; it does not attempt to
make the writes atomic.

### Detection query (startup, one pass)

```sql
SELECT p.* FROM proposal p
WHERE p.status = 'consumed'
  AND p.updated_at > :startupTime - 24h   -- only recent ones
  AND NOT EXISTS (
    SELECT 1 FROM raw_event e
    WHERE e.op_type = 'CONFIRM_CHANGE'    -- or match on proposal id in payload
      AND e.target_id = p.id
  )
```

Open question: raw_event rows do not currently carry the proposal id for all
confirm paths (some carry `causeMsgId`, some carry target node id). The sketch
assumes either (i) a new `proposal_id` column on raw_event, or (ii) matching via
the proposal's `committed_at` timestamp. This is the main cost of (b) beyond the
query itself.

### Surfacing options (pick later, not now)

- **b1 — Home inbox line:** a system line in the Home feed: "A pending
  confirmation was interrupted. Your choice for 'Crash window task' may not have
  been saved." Tappable to re-open the proposal (`reopenProposal(p.id)`).
- **b2 — Chat system message:** injected as a system-role message on next
  ContextBuilder.buildPack so the model can apologise/re-ask naturally.
- **b3 — Silent reopen:** no user-visible note; just `reopenProposal(p.id)` at
  startup so the chip reappears. Cheapest, but the user sees a stale chip
  without explanation.

### Sketch wiring

```
App startup (MineApp / MainActivity init)
  └── AlarmReconciler-style pass: Storage.findOrphanedConsumedProposals(24h)
        └── if non-empty: emit UiState.inboxNote(orphaned list)
              └── user taps → reopenProposal(id) → chip re-renders → normal flow
```

### Why not picked yet

- (a) harden the writes (single-transaction consume+effect, or a write-ahead
  "intent" row) is cleaner long-term but touches every confirm path and the
  storage schema.
- (b) is a small, additive, user-visible safety net that does not change any
  write path.
- The user explicitly said not to pick yet; this file is the sketch for (b).

## 5. Evidence

- `b6-test-run.txt` — first run of B6CrashWindowTest alone: BUILD SUCCESSFUL,
  exit 0, XML 2 tests / 0 failures.
- `b6-core-run.txt` — forced-fresh `:core:cleanTest :core:test`: BUILD
  SUCCESSFUL, `UNIT_EXIT=0`, XML total **249 tests / 0 failures** (247 + B6 x2).
