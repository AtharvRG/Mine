# Part B Reviewer Report — B1..B6 (2026-11-06)

Independent reviewer agent, fresh context, run after B6 commit cca1507.
Scope: commits a3764f2 (B3), bde78fd (B4), 1194119 (B1), d161ba0 (B2 test),
d359a7b (B5), cca1507 (B6). Full diff base a5b5838..cca1507
(`C:\Users\Banespire\AppData\Local\Temp\partb-full.diff`).

**VERDICT: APPROVE (with notes).**

Report text below is verbatim from the agent.

---

Read pass is complete. All six commits (a3764f2, bde78fd, 1194119, d161ba0,
d359a7b, cca1507) read in full from `C:\Users\Banespire\AppData\Local\Temp\partb-full.diff`,
with every callee file (ChatController, AlarmReconciler, ContextBuilder, Gateway,
AlarmReceiver, BootReceiver, ReliabilityManager, AndroidStorage,
MineDatabaseHelper/DatabaseProvider, SharedFakeStorage) read directly, not via
the summary.

What the batch is supposed to guarantee: (1) the model sees option titles and
due times in pending clarifications (B1) and transient receipts cannot grow
unbounded past 20/24h (B2); (2) reliability issues use one human-readable
structured format across both writers (B3); (3) imported chat history becomes
FTS-searchable without re-indexing redacted/system rows (B4); (4) BootReceiver
ignores non-intended broadcasts (B5 device proof) and the
consume-without-effect crash window is documented as characterization tests (B6).

## BLOCKING

None. No data loss, no crash, no unbounded growth, no redaction leak (the
rebuild correctly excludes `content IS NULL`, blank, and `role != 'system'`
rows via the shared trigger set), no raw-JSON leak to the user banner, no
unguarded receiver work.

## IMPORTANT

1. `core\src\main\java\com\anchor\core\context\ContextBuilder.kt`, `buildPack`
   lines 71-83 — B1 amplifies a budget bypass. Each option now renders up to
   ~80 (title) + 40 (dueTime) + id/parens chars; a proposal with several easily
   exceeds `maxProposalChars = 500` by itself, and the loop only breaks when
   `shownProposals.isNotEmpty()`, so one option-heavy first proposal is always
   included and crowds out proposals 2-5 entirely (pre-batch lines were
   id-only and always fit). The model can lose pending clarifications it
   previously saw. Fix: sub-budget each proposal line (e.g., clamp `optionsText`
   to ~280 chars inside the join) and/or drop the first-line bypass so the
   first line counts against `maxProposalChars`.
2. `app\src\androidTest\java\com\anchor\mine\alarms\BootReceiverIgnoreTest.kt`,
   `testBootReceiver_IgnoresNonIntendedActions_NoSideEffects` lines 82-90 —
   two flake vectors, currently 1/1 green. (a) The control path polls
   `AlarmReceiver.lastFiredId` under a hard 6000ms wall clock covering
   broadcast dispatch → SQLCipher open → reconcile+sweep → second broadcast →
   AlarmReceiver's IO coroutine; on a cold loaded emulator this is the
   realistic CI flake point. (b) `lastFiredId = id` is set at
   AlarmReceiver.kt:56 before `storage.deleteReminder(id)` at :86, so the poll
   can exit and the final `assertNull` on the pending row can race the delete.
   Fix: poll on the storage row deletion (the more meaningful outcome) with a
   larger budget (15-20s), or poll both conditions; also derive the canary
   staleness from the sweeper constant (`fireAtMillis < now - 60000L`,
   ReliabilityManager.kt:22) instead of hardcoded 75000.
3. `core\src\test\java\com\anchor\core\gateway\B6CrashWindowTest.kt`, both
   tests — passes-by-design characterizations of the kill window at
   `core\src\main\java\com\anchor\core\gateway\Gateway.kt:607-608` (consume
   then save as two durable writes, verified verbatim). Fidelity is genuine
   (Storage-delegation intercept asserts via public API that `p_near_1` is
   already "consumed" when the replacement save begins), but any future fix
   merging the two writes will spuriously break assertion "consumed"→ and the
   hardcoded strings ("Proposal already resolved", "No open proposals.") are
   secondary spurious-break vectors. The doc comment acknowledges this. Fix at
   fix-time: update these tests with the fix commit and assert the
   intent-level outcome (no window) plus, to the extent possible, keep string
   coupling out. Notice: fails-before is NO for both, correctly — they
   characterize pre-batch behavior.
4. `app\src\main\java\com\anchor\mine\export\AndroidExportManager.kt`,
   `rebuildMsgChunks` lines 388-417 — rule parity is a duplicated literal, not
   a shared source of truth: the exclusion predicates, `["$id"]` msg_ids
   encoding, and `'v1'` extractor version appear in
   `AndroidStorage.insertRawMessage` (AndroidStorage.kt:71-76) and again here
   (and again hardcoded in the ExportTest mirror JOIN). A future extractor
   bump ('v2') or chunking change silently diverges between live inserts and
   post-import rebuild. Fix: extract a shared `MsgChunker` (or at least
   constants for EXTRACTOR_VERSION and the predicates) that both paths use;
   keep the test asserting against the shared constant.

## MINOR

- `AndroidExportManager.kt:397` — `TRIM(content) != ''` only trims 0x20;
  content that is whitespace-only-without-spaces (tab/newline-only) is chunked
  by rebuild but skipped by `isNotBlank()` in insertRawMessage — rare,
  cosmetic divergence; either use a Kotlin-side check or accept and comment it.
- `AndroidExportManager.kt:297-317` — B4 runs after the pre-existing wal/shm
  delete while MineApp's long-lived helper may still hold an open connection to
  the swapped file; the fresh helper here is fine, but the pre-existing
  swap-with-live-connections landmine should at least be checkpointed
  (`PRAGMA wal_checkpoint(TRUNCATE)`) before the deletes; not introduced by
  this batch.
- `AndroidExportManager.kt:394-409` — row-by-row rechunk inside the transaction
  scales O(history) with per-row FTS triggers on every import; fine for now,
  note for large histories.
- `core\src\main\java\com\anchor\core\context\ContextBuilder.kt:77` —
  `prop.options.isEmpty()` renders a dangling "Options: " for the model; emit
  "no options" or omit the suffix.
- `core\src\main\java\com\anchor\core\ui\ChatController.kt:130` —
  `schedError ?: it.schedulingError` can pin a stale banner from the earlier
  `reconcileAlarms` `_state.update` (line 628-633) even when the fresh read is
  healthy; benign, pre-existing pattern extended by B3.
- `core\src\main\java\com\anchor\core\alarms\AlarmReconciler.kt:135-147` — the
  JsonObject "previous"-chain and plain-string legacy recovery are good, but a
  pre-B3 string containing `"`/`\` was invalid JSON and now falls to the
  catch-all `emptyList()` (banner degrades to the generic "Alarms or
  notifications at risk"); acceptable, document it.
- Prompt-injection boundary: `PromptSanitizer.sanitize` enforces
  line-shape/length only, so misleading instruction text inside an option
  title still reaches the system prompt verbatim (self-injection path — titles
  derive from user text); the batch's sanitization is correct per contract,
  but the threat model should state that semantic neutralization is out of
  scope.

## TESTS

- ContextBuilderTest.testPendingClarifications_IncludeOptionTitlesAndDueTimes —
  fails-before: YES (old line rendered `opt_a, opt_b` only; both assertTrue on
  title and the assertFalse would fail).
- R7ReceiptsTest.testTransientReceipts_CappedAtTwentyAndExpiredAfter24h —
  fails-before: YES (no cap: assertEquals(20, count) with 30 accumulated; no
  expiry: post-advance assert fails).
- B3ReliabilityIssuesTest.testHealthFailure_WritesStructuredIssueJson_AndHumanReadableBanner —
  fails-before: YES (old writer emitted a plain-string JSON array that the
  structured `decodeIssues` cannot decode; banner also leaked `["`).
- B3ReliabilityIssuesTest.testReconcilerPreservesControllerWrittenIssue —
  fails-before: YES (old reconciler parse dropped non-decodable plain-string
  entries, so the controller-written issue was lost on reconcile).
- B3ReliabilityIssuesTest.testBothWritersAppend_IssuesBoundedToFiveStructuredObjects —
  fails-before: YES (old controller `dropLast(1)+","reason"]` string surgery on
  structured JSON produced malformed JSON; decode throws).
- B6CrashWindowTest.testCrashWindow_ConsumedWithoutEffect_SilentlyVanishesAfterRestart —
  fails-before: NO, by explicit characterization design.
- B6CrashWindowTest.testCrashWindow_NearMatchDedupe_ConsumePersistsBeforeReplacementProposalSave —
  fails-before: NO, by explicit characterization design; the delegation wrapper
  intercepts via the public `Storage` interface (not internals) and asserts
  outcomes.
- ExportTest.testPostImport_RebuildsMsgChunkForSearch — fails-before: YES (per
  the batch's own red-phase evidence; without `rebuildMsgChunks` the FTS hits
  are empty).
- BootReceiverIgnoreTest.testBootReceiver_IgnoresNonIntendedActions_NoSideEffects —
  fails-before: NO (guard predates the batch, from eb63e21); acceptable as a
  device regression characterization, noted above.
- Assertion quality: outcomes-based throughout (prompt content, receipts
  count, decoded issue arrays, FTS hits, DB row counts). The only internal
  reads are `storage.savedProposals`/`reliabilityState` on the project's
  sanctioned SharedFakeStorage, used to seed/verify through the public storage
  contract — no fake reads private production internals.
- What I would additionally test that the batch does not: (a) double-import
  idempotency of `rebuildMsgChunks` (import twice, assert chunk count and FTS
  stable); (b) FTS5 `integrity-check` after import; (c) boundary tests — 21st
  receipt exactly, receipt at 24h-minus-1ms survives; (d) empty-options
  rendering; (e) B1 many-long-options scenario asserting proposals 2+ still
  present (locks in fix for finding 1); (f) legacy issuesJson in one mixed
  array (strings + objects) parses with all entries recovered; (g)
  reconciler-clean-pass-then-health-failure sequencing (banner continuity
  across the two writes); (h) B6 intent-level post-fix characterization to pair
  with the current brittle one, so the fix commit flips assertions
  deliberately; (i) a reminder older than the 60s grace but younger than the
  previous sweep, verifying no duplicate "missed" fire after two intended
  broadcasts.

## VERDICT

APPROVE (with notes). No blocking defect; four important follow-ups
(prompt-budget crowding, device-test flakiness hardening, B6 planned
obsolescence discipline, chunk-rule deduplication) should be tracked before
the fixes they anticipate.
