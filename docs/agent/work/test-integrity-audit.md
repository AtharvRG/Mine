# Test-Suite Integrity Audit

**Scope:** 55 test files under `core/src/test`, `app/src/test`, `app/src/androidTest`.
**Lens:** which tests give *false confidence* under the project's own standard ("titanium-grade failsafe"; every feature claims "verified by N tests")?
**Read-only:** no production or test file was modified by this audit (see disclosure at the end).

All `file:line` references were opened during the audit and are pinned to the current working tree
(`master`, `eb63e21` + uncommitted modifications). Paths are abbreviated:
`SFS` = `core/src/test/java/com/anchor/core/SharedFakeStorage.kt`,
`AS` = `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`,
`GW` = `core/src/main/java/com/anchor/core/gateway/Gateway.kt`,
`CC` = `core/src/main/java/com/anchor/core/ui/ChatController.kt`.

Severity: **HIGH** = the test backs a user-visible data/reminder guarantee that the real app
does not honour; **MEDIUM** = test cannot fail for the reason it claims, or is flaky/order-dependent;
**LOW** = latent gap, wrong signal, or fake-only artifact.

---

## Findings

| ID | Sev | Test file:line | Problem class | Description |
|----|-----|----------------|---------------|-------------|
| T-01 | HIGH | `core/src/test/java/com/anchor/core/gateway/P1B16UpdateIntentTest.kt:85` | Fake-vs-real masking | `assertEquals("Buy oat milk", updated.title)` after a chat rename passes only because `SFS:333-334` replaces the whole node; real `AS:582-583` `UPDATE node SET status, due_at, closed_at, priority, severity, attrs, updated_at` **never writes `title`**, so on device the rename is silently discarded while `GW:1032` reports `"title now 'Buy oat milk'"`. |
| T-02 | HIGH | `core/src/test/java/com/anchor/core/gateway/P1B16UpdateIntentTest.kt:256` (sanity) and `:262` (undo) | Fake-vs-real masking / false-success | The rename+undo round-trip asserts the title is restored. `GW:1230` restores via `updateNodeAndCommit`, whose SQL (`AS:582-583`) has no title column; the undo receipt `"Restored: <old title>"` (`GW:1232`) is shown verbatim as the user's chip (`CC:263-267`). Test passes; device lies twice. |
| T-03 | HIGH | `app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt:993-996` | Vacuous (relative to claim) | `testAndroidStorage_getNode_HydratesPrioritySeverityPinned_AndEnablesUndo` calls real `undoChangeAndCommit` but asserts **only** `status == "cancelled"`. It never asserts title/dueAt/priority/severity/attrs restoration, although `AS:611` claims snapshots "roll back accurately". |
| T-04 | HIGH | `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt:22` | Coverage gap on the real DB | `testFreshV5SmokeTest_RunsEveryStorageMethod` calls `updateNodeAndCommit` (line 75) but **never calls `undoChangeAndCommit`** — the real undo SQL path has exactly one real-DB caller (T-03) which asserts nothing. |
| T-05 | HIGH | `core/src/main/java/com/anchor/core/alarms/AlarmReconciler.kt:44` + `core/src/test/java/com/anchor/core/alarms/AlarmReconcilerTest.kt:152` | Test encodes a bug | `validPending = pending.filter { it.fireAtMillis >= now - 60000L }` drops reminders overdue by >60 s from reconciliation tracking, and the test asserts exactly that (`assertFalse(... "r_exp")`) without asserting the reminder was routed to the missed-fire sweeper. `AlarmReconcilerTest` thus freezes silent reminder loss as correct. |
| T-06 | HIGH | `app/src/androidTest/java/com/anchor/mine/smoke/LiveSmoke15Test.kt:266-267` | Vacuous assertion | The end-to-end "live smoke" — the test that is supposed to prove a real LLM round-trip works — asserts only `transcriptFile.length() > 0` and `dbJsonFile.length() > 0`. It passes if the model returns garbage, if zero nodes were created, and if the DB is empty. |
| T-07 | HIGH | `app/src/main/java/com/anchor/mine/export/AndroidExportManager.kt:297-300` | Missing negative test (process death / ENOSPC) | Import "safe swap" does `dbHelper.close()` then `tempFile.copyTo(originalFile, overwrite = true)` with no fsync/rename and WAL/SHM deleted at 302-303. A kill or ENOSPC mid-copy leaves a truncated *live* DB. `ExportTest.kt` (tests at 110/183/247/282/317/387/445) has no crash/partial-copy case; `ChunkedAesStreamTest.kt` has no truncation or missing-final-chunk case despite the dedicated branch in `loadNextChunk`. |
| T-08 | HIGH | `core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:129-130` and `:137` | Mutation-proven vacuous | `testReconcileAlarms_UpdatesTime_And_CancelsOrphans` mutates the fake's list directly, then reconciles and asserts "re-scheduled because time changed". Mutation-tested: make the registry already equal the new time and the test **still passes** (the earlier `force=true` reconcile from `init` does the scheduling). It does not isolate `reg.registeredFor != reminder.fireAtMillis`. |
| T-09 | MEDIUM | `core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:218` | Unasserted production call | `gateway.executeTool(undoCall)` result is discarded; only `reminders.size` is checked. A `ToolResult.Error("Node state has changed, cannot undo")` would be invisible. |
| T-10 | MEDIUM | `app/src/androidTest/java/com/anchor/mine/quotes/QuoteAlarmReceiverTest.kt:26-34` | Zero assertions | `testQuoteAlarmReceiver_onReceive_executesCleanly` calls `onReceive`, sleeps 1 s, asserts nothing. Passes if the receiver is an empty function. |
| T-11 | MEDIUM | `app/src/androidTest/java/com/anchor/mine/alarms/StatusBarAlarmIconTest.kt:74-80` | Zero assertions + order dependence | `cleanUpAll()` is a `@Test` whose body only cancels two alarms; its "pass" depends on running after the other tests, and it asserts nothing. |
| T-12 | MEDIUM | `app/src/androidTest/java/com/anchor/mine/alarms/StatusBarAlarmIconTest.kt:66-68` and `app/src/androidTest/java/com/anchor/mine/alarms/AlarmSelfTest.kt:133-135` | Conditional assertion | The only meaningful assertion is wrapped in `if (nextClock != null)`. On a device with no next-alarm clock (the common case), the test passes vacuously — it cannot prove GENTLE tier is excluded from the status-bar icon. |
| T-13 | MEDIUM | `app/src/androidTest/java/com/anchor/mine/alarms/StatusBarAlarmIconTest.kt` (`delay(20_000L)` holds) | Flaky / slow / wall-clock | Two tests hold 20 s "for adb screenshot capture"; `AlarmSelfTest.kt:43-67` busy-waits up to 10 s on a +1.5 s alarm. Timing-dependent on emulator load. |
| T-14 | MEDIUM | `core/src/test/java/com/anchor/core/ui/R12ConcurrencyLifecycleTest.kt:120-125` (also 190-195, 256, 271-272, 665-666) | Flaky deadline | Interleavings are correctly gate-driven (`CompletableDeferred`), but completion is awaited with a 2000 ms `System.currentTimeMillis()` spin on `Dispatchers.Default`. Under CI load this fails spuriously; on a fast machine it can hide a hang. |
| T-15 | MEDIUM | `core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:35-44` | Flaky helper | `waitUntil` uses `System.currentTimeMillis()` + `delay(5)` polling rather than virtual time; 1000 ms default is tight for a chat turn on slow machines. |
| T-16 | MEDIUM | `core/src/test/java/com/anchor/core/storage/StaleNodesClockTest.kt:46-58, 73-77` | Fake-only, ordering unproven | Asserts only `size` and `id`, never order. Real `AS:285-289` has `ORDER BY created_at DESC`; `SFS:182-188` returns insertion order. Nothing catches a regression in stale-item ordering. |
| T-17 | MEDIUM | `app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt:142-143, 154-155` | Broad catch | `catch (_: Exception) { threw = true }` treats any failure (NPE, schema error, keystore loss) as the expected "abort". |
| T-18 | MEDIUM | `app/src/test/java/com/anchor/mine/export/ChunkedAesStreamTest.kt:21-107` | Missing negative tests | Only 4 tests: bad length (negative, <16, >max) and a happy round-trip. No wrong-key, corrupted-tag, truncated stream, or missing-final-flag test — the branches most likely to cause silent data loss on import. |
| T-19 | MEDIUM | `app/src/androidTest/java/com/anchor/mine/quotes/QuoteAlarmReceiverTest.kt:22-23` | Wall-clock | `assertTrue(next > System.currentTimeMillis())` — passes for any future value; does not check the slot boundary logic (that is covered separately by the strong unit test). |
| T-20 | MEDIUM | `core/src/test/java/com/anchor/core/gateway/B6CrashWindowTest.kt:38-88` (intentional) | Characterization, not a guard | Documents the hard-kill window (proposal consumed, effect uncommitted → clarification permanently lost). Passing on purpose; there is no failing test to prevent the design staying as-is. Correctly labelled in its own KDoc, listed here so it is not counted as safety coverage. |
| T-21 | LOW | `SFS:254` | Fake artifact | `getDebugStats()` returns `emptyMap()`; any UI/controller test asserting debug stats content is fake-only. |
| T-22 | LOW | `SFS:412-414` | Fake artifact | `saveRegisteredAlarm` drops `requestCode`; real `AS:783-788` persists it. Tests cannot detect request-code allocation bugs. |
| T-23 | LOW | `SFS:382-385` vs `AS:647-649` | Fake-vs-real semantic | `saveSoulFact` persists on the fake but is an explicit no-op stub on the real DB. A passing fake test proves nothing about soul-fact writes. |
| T-24 | LOW | `core/src/test/java/com/anchor/core/gateway/GatewayTest.kt:23` | Wall-clock | `private val clock = SystemClock()` — most Gateway tests use the real clock. Undo tests (77-96, 111-131) assert only `status`, never titles, never the undo receipt text, never `undo_of`. |
| T-25 | LOW | `core/src/test/java/com/anchor/core/ui/R7ReceiptsTest.kt:82, 122, 154, 189, 236, 263, 324` | Strengths with one gap | Receipts are well tested (persistence across recreate, 24 h expiry, ordering, no model text, transient cap) but assertions use only CREATE/COMPLETE, so the UPDATE-path receipt strings (`GW:1032`) are never checked — which is how T-01/T-02 escaped. |
| T-26 | LOW | `SFS:321` vs `AS:395-398` | Fake-vs-real (local_only) | `getUrgentNodes` on the fake ignores `localOnly`; real SQL filters `local_only IS NULL OR local_only = 0`. A test proving private nodes are excluded from the "urgent" card would pass on real, fail on fake — none exists. |
| T-27 | LOW | `SFS:392-406` vs `AS:667-706` | Fake-vs-real (search semantics) | Fake uses substring `contains(ignoreCase)`; real uses sanitized FTS5 `MATCH "tok"* … AND …` with a silent `catch (_: Exception) {}` at `AS:704`. Punctuation-only queries, prefix vs infix, and AND semantics differ; no test pins either contract for the real DB beyond `DatabaseMigrationTest`'s FTS smoke. |

**Counts: HIGH = 8, MEDIUM = 12, LOW = 7 (total 27).**

---

## Fake vs real divergences

Method-by-method comparison of `SharedFakeStorage` (SFS) and `AndroidStorage` (AS) for the methods
that tests rely on. **17 methods diverge.** Each row states what a real-DB test would catch that the
fake cannot.

| # | Method | Fake (SFS) | Real (AS) | Divergence | What a real-DB test catches |
|---|--------|-----------|-----------|------------|------------------------------|
| D-01 | `updateNodeAndCommit` | `SFS:324-348` — remove + add whole `Node` and whole `Reminder`; proposal CAS throws `ProposalAlreadyResolvedException` | `AS:577-605` — `UPDATE node SET status, due_at, closed_at, priority, severity, attrs, updated_at`; `INSERT OR REPLACE` reminder; **no title/type/local_only columns; no rowsAffected check** | Whole-object replace vs column whitelist | **Rename is dropped on device** (T-01). Also type change and local_only change are dropped. |
| D-02 | `undoChangeAndCommit` | `SFS:350-358` — whole-object replace, no CAS | `AS:607-620` — same restricted `UPDATE` as D-01 | Whole-object vs column whitelist | **Undo of a rename does not restore the title** (T-02/T-03). |
| D-03 | `getUrgentNodes` | `SFS:321` — no `localOnly` filter, `take(3)` | `AS:395-398` — `AND (local_only IS NULL OR local_only = 0)` | Privacy filter | A private node leaking into the urgent card. |
| D-04 | `getStaleNodes` | `SFS:182-188` — insertion order | `AS:285-289` — `ORDER BY created_at DESC` | Ordering | Stale list ordering regression (T-16). |
| D-05 | `getActiveReminders` | `SFS:190` — `fireAtMillis > clock.now() - 1000` (injected clock) | `AS:291-301` — `fire_at > ?` with `System.currentTimeMillis()` | Clock source + 1 s grace | Gradle/instrumentation runs where the injected clock and wall clock disagree; off-by-one at the boundary. |
| D-06 | `getPendingReminders` | `SFS:191` — returns the live `MutableList` (aliasing) | `AS:303` — fresh query, no ordering | Aliasing vs copy | Tests mutating the returned list change storage in the fake but not in reality (see T-08's direct `reminders.clear()`). |
| D-07 | `getProposal` (24 h expiry) | `SFS:203-208` — expiry from `proposalCreatedAt` map, injected clock | `AS:321-335` — expiry from stored `created_at`, wall clock | Expiry source | Proposal vanishing early/late after restart; expiry reset by `saveProposal` in the fake but not in SQL. |
| D-08 | `getActiveNodes` (pagination) | `SFS:70-90` — in-memory filter + sort, null-due handling in Kotlin | `AS:184-196` — SQL with `ORDER BY` and a cursor | Null-due cursor semantics | A node with `due_at = NULL` being skipped or duplicated across pages. |
| D-09 | `insertRawMessage` | `SFS:37-40` — inserts the message only | `AS:40-76` — computes `day_key` with monotonic clamp, `tz_offset_min`, and derives a `msg_chunk` row | Derived rows | Search failing to find a message because no chunk was written; day-key monotonicity under clock rollback. |
| D-10 | `redactMessage` | `SFS:42-50` — clears the message only | `AS:83-95` — also scrubs `msg_chunk` and relies on the redaction trigger | Derived rows | **A redacted message still searchable through FTS** on device if chunk scrub regresses. |
| D-11 | `getRecentMessages` | `SFS:54` — all messages, no limit/order | `AS:111-122` — `ORDER BY ts_utc DESC LIMIT ?` | Ordering + limit + redaction visibility | Recent-context ordering regressions; redacted rows leaking into the context pack. |
| D-12 | `searchMemoryFts` | `SFS:392-406` — `contains(ignoreCase)` | `AS:667-706` — FTS5 `MATCH`, prefix tokens, AND semantics, silent `catch` at 704 | Query semantics | Punctuation-only queries returning nothing; prefix vs infix; search silently returning `[]` on an FTS exception. |
| D-13 | `saveSoulFact` | `SFS:382-385` — persists | `AS:647-649` — **no-op stub** | Persistence | Any feature relying on `saveSoulFact` works in tests and does nothing on device. |
| D-14 | `saveRegisteredAlarm` | `SFS:412-414` — drops `requestCode` | `AS:783-788` — persists `request_code` | Field fidelity | Request-code collisions when the alarm registry round-trips. |
| D-15 | `getRecentlyClosed` | `SFS:276-281` — filter only | `AS:409-413` — 24 h cutoff, `ORDER BY closed_at DESC, id ASC`, `LIMIT` | Window + ordering | A closed item older than 24 h still showing, or the wrong item first. |
| D-16 | `getHomePriorityList` | `SFS:267-274` — no freshness predicate | `AS:401-407` — 30-day freshness SQL + `rankHomeNodes` | Candidate pool | Items silently dropping off the home card after 30 days on device only. |
| D-17 | `getDebugStats` | `SFS:254` — `emptyMap()` | `AS:481` — real table counts | Content | Any assertion on debug stats is fake-only (T-21). |

**Divergence count: 17.**

Test-side anti-pattern that amplifies all of the above: two tests mutate the fake's backing list
directly (`ChatControllerEdgeCaseTest.kt:104`, and `:129-130`), which is impossible against real SQLite
and hides the aliasing divergence D-06.

---

## The 10 tests I would write next, in priority order

1. **Real-DB rename round-trip** (`app/src/androidTest`, AndroidStorageTest): create node
   "Draft", `updateNodeAndCommit` with title "Final", then `getNode` → assert `title == "Final"`;
   then `undoChangeAndCommit` with the snapshot and assert the title is restored. *Protects:* the
   single most common chat action (rename) and its undo, both of which currently silently do nothing
   on device while claiming success (T-01/T-02).
2. **Real-DB undo restores from a mixed snapshot** (DatabaseMigrationTest): rename + change dueAt +
   priority + pinned, undo, assert every field *and* the reminder row. *Protects:* the undo guarantee
   "the last action can always be reversed" (T-03/T-04).
3. **Reconciler never drops a reminder silently** (`AlarmReconcilerTest`): reminder 10 min overdue →
   reconcile → assert it was *not* scheduled as a future alarm *and* that a missed-fire
   (`reminderFires.outcome == "missed"`) or equivalent report exists. *Protects:* the user is told
   about a missed reminder instead of it vanishing (T-05; today `ReliabilityManagerTest.kt:36-38`
   proves the sweeper works, but the reconcile path bypasses it).
4. **Import crash-window** (`ExportTest`): begin import with a fault-injecting `FileSystem`/copy
   stub that throws after writing half the temp file, then reopen the app's DB and assert the
   pre-import data is intact and queryable. *Protects:* the entire vault against a mid-import kill or
   full disk (T-07).
5. **Import wrong-key / truncated stream** (`ChunkedAesStreamTest`): decrypt with a different DEK →
   expect failure; truncate the final chunk → expect failure, never partial output. *Protects:*
   importing a corrupted backup cannot silently produce a half-populated DB (T-18).
6. **End-to-end smoke with real assertions** (`LiveSmoke15Test`): assert the transcript contains a
   `tool_call` for a specific user sentence, the DB gained exactly one node with the resolved dueAt,
   an undo token exists, and undo restores the pre-state. *Protects:* the only test that exercises the
   real model/provider wire format (T-06).
7. **Reconcile drift isolation** (`ChatControllerEdgeCaseTest` replacement): set registry
   `registeredFor = 9000` and reminder `fireAtMillis = 9000` before *any* reconcile, then reconcile
   with `force = false` → assert `schedule` was **not** called; then set `registeredFor = 5000` →
   assert it **was**. *Protects:* no redundant re-arming (battery, and API drift), and makes the
   drift predicate actually tested (T-08).
8. **Undo result is honoured by the UI** (`ChatControllerEdgeCaseTest`,
   `testUndoAfterReschedule_NodeWithNoReminder`): make the node change after the reschedule, tap undo,
   assert the receipt is an *error* ("Node state has changed") and the reminders are unchanged.
   *Protects:* the undo chip never reports success for a refused undo (T-09).
9. **Quote receiver side effects** (`QuoteAlarmReceiverTest`): assert that after `onReceive`, the
   quote/alarm state actually advanced (next prepare alarm registered, or a quote row updated), and
   a second identical broadcast does not double-schedule. *Protects:* the daily quote alarm silently
   stopping (T-10).
10. **GENTLE tier exclusion, positive control** (`StatusBarAlarmIconTest`/`AlarmSelfTest`): first
    schedule an ALARM-tier reminder and assert `nextAlarmClock` *is* set and equals its fire time;
    then schedule a GENTLE one and assert it is not. Remove the `if (nextClock != null)` guard — fail
    if `nextAlarmClock` is null. *Protects:* the "no full-screen takeover for gentle reminders"
    promise, which today has no test that can fail on a device without a pre-existing alarm clock
    (T-12/T-13).

---

## Genuinely strong tests (keep and use as templates)

- `app/src/androidTest/java/com/anchor/mine/alarms/ManifestPermissionTest.kt:17-45` — asserts *absence*
  of SCHEDULE_EXACT_ALARM / VIBRATE / WAKE_LOCK and presence of USE_EXACT_ALARM; catches a one-line
  manifest regression.
- `app/src/androidTest/java/com/anchor/mine/alarms/BootReceiverIgnoreTest.kt:44-85` — three
  non-intended broadcasts with a >60 s-overdue canary, plus a **positive control** that the same
  canary fires on `ACTION_BOOT_COMPLETED`. Best negative-test in the repo.
- `app/src/androidTest/java/com/anchor/mine/alarms/BootReceiverTest.kt` — boot → sweep →
  `"Missed: …"` notification → `reminderFires` row → deletion, then second boot fires nothing.
- `app/src/androidTest/java/com/anchor/mine/alarms/AlarmReceiverTest.kt:97, 111` — channel must not
  vibrate; `"Missed: "` prefix asserted.
- `core/src/test/java/com/anchor/core/ui/SelfTestAndCancelReminderTest.kt:83-160` — failing scheduler
  leaves no phantom row; ghost-chip cancel still cancels the OS alarm and writes no DELETE receipt;
  real cancel emits a `beforeJson`-bearing undoable event.
- `core/src/test/java/com/anchor/core/alarms/AlarmReconcilerTest.kt` (other tests, 42-133, 157-248) —
  force vs non-force, orphan cancel, tier↔api mismatch, reschedule on time change.
- `core/src/test/java/com/anchor/core/reliability/ReliabilityManagerTest.kt:17-59` — sweeper records
  `"missed"` exactly once, heartbeat set, row not deleted.
- `core/src/test/java/com/anchor/core/ui/R7ReceiptsTest.kt:82-350` — receipts survive controller
  recreation, expire at 24 h, keep order/`causeMsgId`, never contain model text, transient list capped
  at 20.
- `core/src/test/java/com/anchor/core/ui/R12ConcurrencyLifecycleTest.kt` — gate-driven
  `CompletableDeferred` interleavings (technique is right; deadlines are the weakness, T-14).
- `core/src/test/java/com/anchor/core/gateway/B6CrashWindowTest.kt:91-145` — deliberately characterizes
  the crash window via a wrapped `Storage` that observes the durable "consumed" status at the second
  write. Good forensic test, not a safety guard.
- `app/src/test/java/com/anchor/mine/quotes/QuoteAlarmSchedulerTest.kt` — five UTC-pinned boundary
  tests for the daily slot.
- `app/src/androidTest/java/com/anchor/mine/export/ExportTest.kt:110, 183, 247, 282, 317, 387, 445` —
  wrong password/tampering rejection, header KDF bounds, unknown table/column rejection, API-key
  exclusion, post-import reconcile, msg_chunk rebuild.
- `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt:96-107` — FTS triggers on
  a real DB, including redaction removing the row from the index.
- `app/src/androidTest/java/com/anchor/mine/storage/StorageTest.kt:58-62` — `assertThrows` plus
  message-content check instead of a bare catch.

---

## Disclosure

During an exploratory mutation experiment one test file was temporarily edited
(`core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt`: direct
`storage.reminders.add(...)` replaced with `saveReminder(...)`). The mutation experiment was the source
of T-08's proof that the test is vacuous. The file was restored with `git checkout --` and
`git status` for that path is clean; no other file was modified.
