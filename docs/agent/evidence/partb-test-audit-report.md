# Part B Test Audit Report — B1..B6 (2026-11-06)

Independent test-auditor agent, fresh context, run after B6 commit cca1507.
Scope: commits a3764f2 (B3), bde78fd (B4), 1194119 (B1), d161ba0 (B2 test),
d359a7b (B5), cca1507 (B6). Evidence verified against the working tree
(ExportTest, BootReceiverIgnoreTest, B3ReliabilityIssuesTest, B6CrashWindowTest
read verbatim; production counterparts re-confirmed including
`insertRawMessage`'s chunking rule at AndroidStorage.kt:71 and
`rebuildMsgChunks` at AndroidExportManager.kt:388-417).

**Verdict: PASS with notes.**

Report text below is verbatim from the agent.

---

All six artifacts genuinely exercise the behavior they claim, no assertion set
is vacuous, and results are consistent with ground truth (core 249/249, app
9/9, connected 53/53 + 1/1). However, the batch has one vacuous wait construct
(B2), real isolation/cleanup defects in the two on-device tests (B4, B5), a
timing-flake risk in B5's control loop, and several provable-but-unmade
assertions. These are hardening items, not failures.

## Per-test findings (ranked by severity)

### B5 — BootReceiverIgnoreTest (app connected) — weakest of the batch

**HIGH-1: Shared-static mutation with no restore + real-DB pollution.**
`@Before` sets `AlarmReceiver.lastFiredId = null` (BootReceiverIgnoreTest.kt:34)
but there is no `@After`. The control section then leaves `lastFiredId = rId`
set for every subsequently-run test in the instrumented process; sibling
`BootReceiverTest.kt` shares this static. Worse, the canary lives in the
**real app storage** (`MineApp.sharedStorage`, line 47): if the control
assertions throw mid-flight (e.g. on timeout), the canary reminder row
persists; on the success path, a `reminder_fire(rId, "missed")` row is written
to the device DB and the `rId.hashCode()` notification is posted and never
cancelled. There is no try/finally anywhere.
Suggested: save/restore `lastFiredId` in `@After`; wrap the control in
try/finally that deletes `reminder`/`reminder_fire` rows for `rId` and cancels
`activeNotifications.find { it.id == rId.hashCode() }`.

**HIGH-2: Control timeout too tight for a real device chain.**
BootReceiverIgnoreTest.kt:77-81 polls for 6000ms over the full chain
onReceive → goAsync → IO coroutines → `reconcile(force=true)` → `runSweeper()`
→ `sendBroadcast(EXTRA_MISSED)` → AlarmReceiver IO → static set. On a slow CI
device this is a flake factory, and on timeout it fails *and* leaves the
mutated state from HIGH-1. Suggested: budget ≥15s, and poll a DB effect
(`storage.hasReminderFire(rId, "missed")`) in addition to the static.

**MEDIUM-1: Only `ACTION_BOOT_COMPLETED` is exercised as a valid action**
(line 76). The allowlist in BootReceiver.kt has three other valid actions.
Since the test invokes `receiver.onReceive(...)` directly (no broadcast
permission needed), parameterizing over `MY_PACKAGE_REPLACED`, `TIME_CHANGED`,
`TIMEZONE_CHANGED` is trivial and would catch a typo in the allowlist (exactly
the class of bug this test exists to guard).

**MEDIUM-2: Real scheduler side effects.** The reconciler built by BootReceiver
uses the app's real `AndroidAlarmScheduler`; `reconcile(force=true)` will
schedule/cancel real alarms for whatever pending reminders exist in the real
DB at that moment. Canary design is correct (75s stale > 60s grace, so
AlarmReconciler.kt:44 excludes it from `validPending`, and the sweeper's
`fireAt < now - 60000` picks it up). But any other pending real reminder would
be armed on the test device as an untracked side effect of a connected test.

**LOW-1: Negative phase is a 1.5s grace heuristic** (line 66). Fine on current
code (early return is synchronous before `goAsync`), but it asserts absence of
async work via wall clock — document it.

**LOW-2: Copy/style.** `assertTrue(!storage.hasReminderFire(...))` (line 71)
should be `assertFalse(...)`. Method name `..._NoSideEffects` (line 45) is
accurate only for phase 1; the same method deliberately performs side effects
in the control section — minor, the doc comment explains it.

### B2 — R7ReceiptsTest.testTransientReceipts_CappedAtTwentyAndExpiredAfter24h

**MEDIUM-1 (vacuous wait):** `waitUntil { controller.state.value.receipts != null }`
(R7ReceiptsTest.kt:333) can never be false — `UiState.receipts` is a non-null
`List<ReceiptState>` defaulting to `emptyList()` (ChatContract.kt:21). This
wait is a no-op that reads as init-synchronization but provides none; if
controller init ever became delayed, the test would proceed on empty state and
*subsequent* waits, not this one, would absorb the race. Replace with a
condition that can actually be observed (e.g. that undo-processing is
reachable) or delete it.

**MEDIUM-2 (boundary gaps):**
- The cap asserts only `== 20` after 30 adds (lines 341-343). It never verifies
  *which* 20 survive. `addTransientReceipt` trims the oldest
  (`subList(0, size-20).clear()`, ChatController.kt:51-68), so the survivors
  must be exactly the last 20 additions. Suggested: capture each error
  receipt's `id` per iteration and assert the final state's transient ids
  equal the last 20 and none of the first 10 remain — this pins the LRU
  discipline instead of merely "some cap exists."
- The exact 24h boundary is untested. Production uses
  `clock.now() - it.timestamp >= dayMs`. Suggested: add one receipt at t0,
  advance `24h - 1ms` + RefreshStats → still present; advance `+1ms` → gone.
  The current test jumps straight from 0 to 25h.
- Fractional note: the test's own expiry claim in its name ("ExpiredAfter24h")
  is verified only at 25h — the boundary itself is unproven.

**LOW-1:** The cap loop's `waitUntil { receipts.size >= minOf(i+1, 20) }`
counts *all* receipts, not transients. It works only because the storage is
empty (transport emits only `[DONE]`), so persisted-receipt contamination is
impossible. A comment making that dependency explicit would prevent silent
breakage if the test gains a setup that writes events.

**LOW-2:** Only the error path drives `addTransientReceipt` (undo with a
missing token → gateway "Undo token not found", Gateway.kt:1134). The
Committed/success transient path (if any receipt is added there) is
unexercised by this batch.

**Positive:** The production logic itself (purge-then-add, synchronized, LRU
trim) is genuinely covered by the happy path; naming is accurate; per-test
`FakeClock`/storage gives clean isolation in core.

### B1 — ContextBuilderTest.testPendingClarifications_IncludeOptionTitlesAndDueTimes

**MEDIUM-1 (coverage gaps the user explicitly called out — confirmed real):**
- No pre-existing test anywhere in ContextBuilderTest covers the proposal
  budget (`maxProposalChars = 500`, `take(5)`, ContextBuilder.kt:71-83). With
  the B1 change, each option line is now id + `sanitize(title, 80)` +
  `sanitize(dueTime, 40)` — long lines interact with the 500-char break.
  Untested: 3 proposals × ~200-240 chars/line → the third must be dropped
  while the first two survive; and 6+ options → `take(5)` boundary. Suggested:
  add a `testPendingClarifications_CapsOptionCountAndBudget` with oversized
  titles/dueTimes and distinct markers per option, asserting (a) at most 5
  options render, (b) budget-break drops later proposals but keeps the
  prefix, (c) the count/suffix behavior.
- Sanitizer boundaries at this site untested: 100-char title with
  newlines/`|`/collapsing whitespace must render as ≤80 chars, single-line,
  pipes→`-` (PromptSanitizer rule). The failure mode B1 changed (model can't
  match user words to a choice) is never tested end-to-end: no confirm_change
  round trip where the user's message text matches an option title.
- `opt.dueTime == null` rendering branch (title-only line) untested.

**LOW-1:** Test-internal clock mismatch: `SharedFakeStorage()`
(ContextBuilderTest.kt:28) uses the real system clock for `proposalCreatedAt`,
while the builder runs on `FakeClock(1000L)` (line 44). Harmless today
(proposal is fresh relative to the real clock), but it is exactly the setup
that would mask a 24h-expiry regression.

**Positive:** Assertions are meaningful and falsifiable; the
`assertFalse(contains("Options: opt_a, opt_b"))` (line 56) correctly guards
regression to the old id-only format. B1 production code matches the comment
exactly. Naming accurate.

### B3 — B3ReliabilityIssuesTest

**MEDIUM-1 (dead legacy code — the codec's compatibility branches are
untested):** `ReliabilityIssueCodec.parse` has two branches that exist purely
for backward compatibility — the plain-string `JsonPrimitive` fallback
(AlarmReconciler.kt:126-133) and the `JsonObject` "previous" chain (lines
135-147). Nothing in this batch exercises them, so a regression in either
(including NPE/`ClassCastException` paths) ships covered by zero tests.
Suggested unit tests: parse `["plain legacy string"]` → single
`ReliabilityIssue("plain legacy string", 0)`; parse a 3-deep `previous` chain
→ 3 flattened issues; parse garbage → `emptyList()`.

**MEDIUM-2 (weaker than provable assertion):** Test 3 asserts
`issues.size <= 5` (B3ReliabilityIssuesTest.kt:156) where the frozen
`FakeClock` makes `== 5` provable (4 seeded + 2 new, dedupe by distinct
errors, sort desc, take(5)). `<= 5` would pass a regression that drops seeded
issues to 2. Suggested: `assertEquals(5, issues.size)` plus ordering assert
(the two new issues, at `clock.now()`, sort ahead of the seeded ones).

**LOW-1:** No `time` field assertion. Clock is frozen at 1700000000000L →
`assertEquals(1700000000000L, issues.first { it.error == healthReason }.time)`
is exact and would pin the time capture.

**LOW-2:** Same-error dedupe (existing issue filtered when the new failure has
the identical reason) is untested — a duplicate-failure inflation bug (6
issues from 3 error types) would pass this suite. Suggested: run the same
failing reconcile twice, assert exactly one issue per distinct error.

**LOW-3:** Line 144 pokes `storage.reliabilityState = ...` directly (fake
internals) rather than through `saveReliabilityState` — couples the test to
the fake's representation.

**Positive:** The banner assertions (lines 112-114, human-readable, no raw
JSON leakage) are sharp and falsifiable; the `providerEndpoint.isNotBlank()`
guard on line 110 legitimately discriminates final init state from
intermediate emissions; `decodeIssues` (lines 89-90) *is* the
structured-format proof (legacy plain strings would fail decode → test fails →
correct). Test 2's cross-writer preservation is the exact regression
ChatController.reconcileAlarms (lines ~611-634) could introduce, and it is
covered. Naming accurate.

### B4 — ExportTest.testPostImport_RebuildsMsgChunkForSearch

**MEDIUM-1 (resource leak):** `destHelper` (ExportTest.kt:466) is never
closed — lines 498-500 close `srcHelper` and `checkHelper` and delete the
export file, but the destination SQLCipher handle leaks per run. To be fair
this mirrors an existing class-wide pattern (the sibling
`testPostImport_TriggersAlarmReconciliation` also never closes its destHelper
at line 414, and the keys test leaks `dest1Helper`/`dest2Helper`), but each
Part-B-new test should not propagate it. Suggested: wrap in try/finally and
close all three helpers (and note the pattern for a cleanup follow-up across
the class).

**MEDIUM-2 (untested equivalence paths):**
- **Idempotent re-import:** production deletes all chunks then re-inserts
  (AndroidExportManager.kt:394), so re-importing the same file must be a
  no-op-with-rebuild. Untested. Suggested: after the first import, manually
  insert a bogus `msg_chunk` row, re-import, assert the bogus row is gone and
  the mirror count is back to 2 — this also covers the FTS delete trigger
  (`trg_msg_chunk_fts_delete`, MineDatabaseHelper.kt:147) keeping the index
  consistent, which matters because `searchMemoryFts` reads the index, not the
  table.
- **Redact after import:** the imported chunk ids must satisfy the
  `DELETE FROM msg_chunk WHERE msg_ids LIKE '%"<id>"%'` rule
  (AndroidStorage.kt:93). Suggested: `redactMessage("chunk_m_user")` →
  `searchMemoryFts("zebra")` no longer returns `m_user`, proving imported
  history honors redaction. This is the "redaction path after import" gap and
  it is real — nothing in the batch proves a redact on imported rows deletes
  their rebuilt chunks.
- **Whitespace-content row:** `insertRawMessage` skips chunking on
  `!content.isNotBlank()` (AndroidStorage.kt:71) and rebuild skips
  `TRIM(content) != ''` (AndroidExportManager.kt:397) — consistent, but the
  blank row (and also the NULL-content redacted row on the rebuild's
  `content IS NOT NULL` side) is untested.

**Positive:** The core assertion set is unusually strong: system-role and
redacted rows demonstrably export as raw rows (`stats.messages == 4`, line
470) while getting zero chunks (direct `COUNT` query, lines 483-486), and the
mirror query (lines 489-495) proves **exact** column-level fidelity
(`msg_ids = '["<id>"]'`, `extractor_version = 'v1'`, ts/day_key/text) between
`insertRawMessage` and `rebuildMsgChunks` — the auditor verified the two SQL
rules byte-for-byte identical in intent. FTS searchability asserted at the
API level, not the table level. Naming accurate.

### B6 — B6CrashWindowTest (characterization)

**MEDIUM-1 (durability premise modeled only in memory):** Test 1's central
premise — "the consumed row survives the kill" (line 61) — is asserted
against `SharedFakeStorage`, an in-memory fake that cannot model durability at
all: it trivially survives because nothing can kill it. The premise is true
on-device (WAL SQLCipher), but no on-device test proves it. Suggested
androidTest: consume a proposal via `AndroidStorage`, close the helper,
reopen a new helper (process-restart approximation), assert status "consumed"
persists. Without this, the characterization's headline claim is technically
unverified on the production storage.

**LOW-1 (overfit / unasserted contract details):** Exact-match on the template
string `"No open proposals."` (line 71) couples the characterization to copy
that may legitimately reword. Acceptable for characterization, but a
`substringAfter` fallback would be more durable. Second test leaves two
documented facts unasserted: the replacement's options triple ("Update
existing" / "Add anyway" / "Cancel") and that `needs_confirm` payload carries
the duplicate's target id — both are part of the observed behavior the file
claims to record.

**LOW-2 (characterized window is narrower than the class doc implies):** The
doc names "terminal-consume sites" generally, but only the near-match dedupe
(Gateway.kt:607-608) is probed. The write-separated consume in
`handleScheduleReminderWithTime`'s bad-target path (~Gateway.kt:428-432) and
the node-not-found / no-longer-active consumes in `handleConfirmChange` are
equally hard-kill-exposed and characterized nowhere. The delegation probe
(lines 119-126) would work verbatim on those sites.

**Positive:** Fidelity is high. The `object : Storage by storage` override
capturing `statusAtSecondWrite` at the moment of the second write is a
legitimate mid-window probe through real Gateway code — it proves write
ordering, not an approximation. The file is honest about passing on purpose,
the class doc precisely delimits what D-118 closed (exception path) versus
what remains (hard kill), and test 1's chain (status → open-view invisibility
→ ContextBuilder absence → no effect rows → retry rejected with exact
"Proposal already resolved" per Gateway.kt:451 → 24h forensic expiry) matches
the production code exactly. This is the best test of the batch.

## Top coverage gaps (ranked)

1. **B5 positive-path coverage of the other three validActions** — the
   allowlist is the entire feature; only 1 of 4 entries is tested positive
   (and testing them is free since onReceive is invoked directly).
2. **B3 legacy codec branches** — ReliabilityIssueCodec's plain-string and
   "previous"-chain parse paths (AlarmReconciler.kt:121-153) have zero
   coverage; they exist only for compat and are prime silent-breakage sites.
3. **B1 budget/option-count interaction + confirm-by-title round trip** —
   confirmed: no existing test anywhere exercises `take(5)`/
   `maxProposalChars=500` at the Pending Clarifications site, and the
   user-facing motivation (model matching words to a choice) is never
   end-to-ended.
4. **B4 redact-after-import and re-import (DELETE + FTS trigger) paths** —
   the two equivalence properties the production comment explicitly touts
   (idempotent rebuild; FTS index consistency under delete) are both untested.
5. **B2 cap discipline and exact boundary** — which-20-survive (LRU order)
   and the exact `>= 24h` expiry edge.
6. **B6 on-device durability + the other two-write consume windows** — the
   "row survives the kill" premise unproven on AndroidStorage; sibling
   windows uncharacterized.
7. **B5 cleanup/isolation and control-timeout budget** — try/finally on
   real-DB state and static restore; 6s is under-provisioned.

## Cross-cutting isolation summary

- **core (B1, B2, B3, B6): clean.** Each test builds fresh
  `FakeClock`/`SharedFakeStorage`/controllers; no shared statics; leaked
  `CoroutineScope(Dispatchers.Unconfined)` instances are per-test and
  GC-able. The `waitUntil` wall-deadline pattern (1000ms) is duplicated in two
  classes and should live in a shared helper.
- **app connected (B4, B5): the risk lives here.** B5 mutates a process-wide
  static and writes to the real app DB with no restore/cleanup; B4 leaks one
  SQLCipher handle per run (class-wide pre-existing pattern, still worth
  fixing). Neither pollutes cross-module state because DB names are per-test
  and deleted first.

**Bottom line:** the batch tests what it says it tests; nothing currently
passes vacuously at the assertion level. The FAIL-grade risks are all
forward-looking — B5's timeout/isolation (flaky + dirty on failure), B3's
dead legacy codec paths, and B1's completely untested budget interaction.
Fix those and B4's helper leak, and this batch moves from "PASS with notes"
to structurally sound.
