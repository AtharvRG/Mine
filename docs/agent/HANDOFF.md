# HANDOFF (v1 scope audit done; `search_history` + per-turn aliases shipped and device-verified — full suite 86/86)

## Current Work & Status
- **Two code commits landed this segment, each independently green, each one logical change:**
  - `96f255b` — `search_history` tool (blueprint 23 §5).
  - `78efc25` — per-turn aliases + `unknown_ref` (blueprint 23 §2.2/§4).
- **The docs commit is the last one outstanding** — `STATE.md`, `TASKS.md`, `DECISIONS.md` (new D-130),
  `LEARNINGS.md`, `HANDOFF.md` and the new `docs/agent/work/audit-2026-11-08-v1-scope.md`.
- **#121 D-105** — both halves are captured; the **by-eye verdict is still yours** — see §5.1.
- **The device is reachable** — `10.175.15.5:5555` reported `device` this session.
- **The first device run of a new test remains the only proof it works.** `testSearchHistory_…`
  compiled clean and passed review, then failed on device with a duplicate-row bug (§7).

## 1. What Changed
### `96f255b` — `search_history` (blueprint 23 §5)
- **`core/.../models/Memory.kt`** — `SearchResult` gained `val tsUtc: Long? = null` (defaulted, so
  every existing construction site is unchanged). Chat hits need a timestamp to sort newest-first.
- **`core/.../storage/Storage.kt`** — new `suspend fun searchHistory(query: String, limit: Int):
  List<SearchResult>`; the false KDoc on `searchMemoryFts` (it does return chat hits) corrected.
- **`app/.../storage/AndroidStorage.kt`** — `searchMemoryFts` now selects `msg_chunk.ts_start` as
  `tsUtc`; new `searchHistory` joins
  `raw_message -> msg_chunk (id = 'chunk_' || raw_message.id) -> msg_chunk_fts (rowid = msg_chunk.rowid)`,
  re-checks `content IS NOT NULL AND redacted_at IS NULL`, clamps limit to 1..10,
  `ORDER BY raw_message.ts_utc DESC`, and wraps in `catch { logDiagError(...) }` per R-23.
  **The join shape is load-bearing** — see §7 "external-content FTS5".
- **`core/.../gateway/Gateway.kt`** — dispatch branch for `search_history`; `formatSearchResults`
  with `soul_fact` / `chat` / `"user","assistant"` / `else` (node) arms.
- **`core/.../ui/ChatController.kt`** — the prompt tool list gained the `search_history` line.
  Without it the model can never call the tool; the storage code would be dead.
- **`core/src/test/.../SharedFakeStorage.kt`** — fake `searchHistory` mirroring the real ordering.
- **`core/src/test/.../gateway/GatewayTest.kt`** — 3 new cases: newest-first blueprint format,
  blank query is an error, limit defaults to 5 and clamps to 10.
- **`app/src/androidTest/.../AndroidStorageTest.kt`** — `testSearchHistory_RealSqlite_NewestFirst_
  AndRedactionSafe`; it hand-inserts a **stale** `msg_chunk` row for a redacted message to prove the
  `raw_message` re-check (not the chunk deletion) keeps redacted text out.

### `78efc25` — per-turn aliases + `unknown_ref` (blueprint 23 §2.2/§4)
- **`core/.../gateway/Gateway.kt`** — `aliasMap: ConcurrentHashMap<String, String>` (one per instance,
  i.e. one per chat controller), `aliasFor(prefix, id)` emitting `n_`/`s_`/`m_`/`p_`/`r_`/`u_`,
  `resolveRef(ref, prefix)` mapping an issued alias back, `MAX_ALIASES = 500` guard, and
  `unknown_ref` at the three storage lookups (`:317`, `:439`, `:459`).
- **`core/src/test/.../gateway/GatewayTest.kt`** — `testGateway_ScheduleReminder_UnknownAlias_IsUnknownRef`,
  `testGateway_ConfirmChange_UnknownAlias_IsUnknownRef`, and the `search_history` case now asserts the
  leading token is an issued alias and not the real id.
- **`docs/agent/DECISIONS.md`** — D-130 records why the map is in memory rather than an `alias_map` table.

### Docs (this commit)
- **`docs/agent/work/audit-2026-11-08-v1-scope.md`** (new) — the 22-row §3.1 audit. Every row is
  BUILT / PARTIAL / ABSENT with a file path or a zero-hit grep as evidence. Summary: **BUILT 8**
  (rows 2,3,5,6,7,8,12,15), **PARTIAL 8** (1,4,10,13,14,17,18,22), **ABSENT 6** (9,11,16,19,20,21).
  By area: Data 2/3, Memory 3/4, Intelligence 1/4, Proactive 1/3, Platform 1/3, **UI 0/4**,
  Release 0/1. Plus 7 ordered severities, the false-confidence tests, 2 blueprint contradictions,
  a 7-item ordered task list each with its proving test, and 3 questions for you.
- **`docs/agent/TASKS.md`** — `:25` trigger-upgrade row → `[x]` cross-referencing the completed T19
  schema-repair at `:155`; `:309` "next is H-04, then T19" replaced; P1-E 23/24/25 annotated with the
  audit's findings (esp. **`NudgeEngine` has no production caller**); new
  `## v1 scope audit (2026-11-08)` section with the V1-G1 and V1-G2 rows.
- **`docs/agent/STATE.md`** — header now names the four commits and no longer claims uncommitted work.

## 2. What Is Verified
| Claim | Command | Result |
|---|---|---|
| `search_history` core behaviour | `./gradlew :core:test --tests "com.anchor.core.gateway.GatewayTest"` | **EXIT=0**; `core/build/test-results/test/TEST-com.anchor.core.gateway.GatewayTest.xml` → `tests="13" skipped="0" failures="0" errors="0"`; the 3 new cases present by name |
| No core regression after `96f255b` | `./gradlew :core:test` | **EXIT=0**; aggregated over `core/build/test-results/test/TEST-*.xml` → `tests=303 skipped=0 failures=0 errors=0 files=49` |
| `search_history` **on device** | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` | `CONNECTED GATE: PASS - 86 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/full-suite-search-history-fixed.log` |
| `search_history` RED was real | same command, first run before the join fix | `CONNECTED GATE: FAIL - 86 test(s), 1 failed` — `AssertionError` at `AndroidStorageTest.kt:1384`; probe showed `msg_hist_sys` returned **twice** — `build/tmp/full-suite-search-history.log` |
| No core regression after `78efc25` | `./gradlew :core:test` | **EXIT=0**; recount over `core/build/test-results/test/TEST-*.xml` → `tests=305 skipped=0 failures=0 errors=0 files=49` |
| Aliases + `unknown_ref` **on device** | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` | `CONNECTED GATE: PASS - 86 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/full-suite-aliases.log` |
| H-04 GREEN | `…connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue -P…class=…export.ExportTest` | `CONNECTED GATE: PASS - 1 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/h04-green3.log` |
| T19 GREEN | `… -P…class=…storage.DatabaseMigrationTest` | `CONNECTED GATE: PASS - 2 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/t19-green.log` |
| T19 RED is real | same, with the `DROP TRIGGER IF EXISTS` loop removed | `CONNECTED GATE: FAIL - 2 test(s), 1 failed` — `build/tmp/t19-red.log` |
| Full connected suite (pre-`search_history`) | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` | `CONNECTED GATE: PASS - 85 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/full-suite-h04-t19.log` |
| D-105 ALARM half — icon appears | Pair C screenshots | `visibleState=ICON` 252 px vs `visibleState=HIDDEN` 197 px, 55 px delta |
| D-105 GENTLE half — armed, icon absent | `d105-gentle-alarms-dump.txt` + `d105-gentle-systemui.txt`/`-2.txt` | `*walarm*` + `window=+1m29s998ms` + `flags=0x20`; `grep -c "Alarm clock:"` = 0; 6 `slot='alarm_clock'` views all `visibleState=2` |

## 3. What Is NOT Verified
- **D-105 by-eye verdict — both halves.** I cannot close a `[HUMAN-GATED]` item.
- **The Gentle PNG is not a differential.** It is dark-theme; the Pair C light-glyph scan is
  inapplicable, and there is no same-session dark "nothing armed" control.
- **T19 behavioural risk on real opens:** `createTriggers` drops and recreates all 13 triggers on
  **every** `onOpen`. The migration test exercises `onUpgrade` directly, not the drop-then-fail path.
- **H-04 seam residual:** `copyFile` is a production constructor parameter whose only non-default
  value is supplied by tests (D-129). The shipped path is the default value.
- **Aliases do not survive a process restart mid-turn.** A model echoing a previous turn's alias
  gets `unknown_ref` and must search again. Intended (D-130), not tested on device.

## 4. Current Failing Tests
None known. `BUILD FAILED` appears in `t19-green.log` alongside `CONNECTED GATE: PASS` — that is the
AGP 9.4.0 exit-code defect (D-123), not a failure.

## 5. Open Decisions Needing You
1. **D-105 final by-eye verdict is yours.** Preferred pair: `docs/agent/evidence/d105-clean-alarm-tier.png`
   vs `d105-clean-no-alarm.png` (Pair C). Then `d105-gentle-statusbar-crop.png` for the Gentle half.
2. **The audit's three questions** (`audit-2026-11-08-v1-scope.md` §7):
   (a) ~~build the `alias_map` per blueprint 23 §4, or amend §3.1 row 7?~~ — **answered and shipped**
   (`78efc25`, D-130: in-memory aliases, no table);
   (b) which ABSENT row is the v1 blocker to take first — my recommendation is the **UI trio 19/20/21**,
   because v1 cannot be a daily driver without onboarding; **you accepted this**;
   (c) is the debug-key release (`app/build.gradle.kts:61`) intentional for now? **Still open.**
3. **T19's trigger-drop-on-every-open cost.** Deliberate trade for correctness. If you'd rather pay
   it only on upgrade, say so and I'll gate the drop behind a version comparison.

## 6. Exact Next Step (one command)
```
adb devices
```
Then, if a device is listed as `device`:
```
./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
```
and read **only** the `CONNECTED GATE: PASS|FAIL - N test(s), X failed, Y skipped (source: xml)` line.
If no device is listed, the next action is the **UI trio (audit rows 19/20/21)** — start with row 19's
proving test, per your accepted recommendation.

## 7. Traps for the Next Run
- **Never run two connected invocations at once.** The second dies with
  `java.nio.file.FileSystemException: …logcat-…: The process cannot access the file because it is
  being used by another process`, which the gate reports as the phantom
  `CONNECTED GATE: FAIL - no readable result artifact`.
- **A stale XML is not the failing run's result.** When a run dies before UTP writes, the XML on disk
  is the *previous* run's. The failure detail only exists in the gate line / `gate.json`.
- **A filtered extract is not a dump.** `d105-gentle-alarms-dump.txt` was produced by a `grep` and
  never contained the `Next alarm clock information` header. Say which claims a filtered capture
  can support.
- **A pixel-scan discriminator does not transfer across themes.** The Pair C scan (`r>=251`) is
  meaningless on a dark screenshot. Re-validate any scan on its own controls first.
- **`File.copyTo(overwrite = true)` deletes a directory target recursively.** Every file-system
  injection for H-04 silently succeeded. Use the seam.
- **`copyTo` is a `kotlin.io` extension**, so `override fun copyTo` is `NOTHING_TO_OVERRIDE`.
- **A rollback adds a copy.** backup + failed swap + rollback = 3; `expected:<2>` was the test's bug.
- **Run `connectedDebugAndroidTest` and `…Verify` in the same `--continue` invocation.** Verify alone
  reads the previous run's XML and reports a phantom `FAIL - 1 test(s)`.
- **Comma-separated `class=A#m,B#n` silently selects only the first.** One class per invocation.
- **`connectedDebugAndroidTest` exits 1 even on a green run (D-123).** Only the gate line counts.
- **The T19 identifier is overloaded.** `TASKS.md:24` is the completed "T19 Release (R8)" smoke test;
  the schema-repair T19 is at `:155`. `TASKS.md:25` was a *third* duplicate of the schema-repair item
  and is now folded into `:155`.
- **A `[x]` can be wrong.** Cross-check a checkbox against the decision line it cites.
- **`msg_chunk` has no `raw_message_id` column.** Columns are `id, day_key, ts_start, ts_end,
  msg_ids, text, extractor_version`, and `msg_ids` is NOT NULL. The link to `raw_message` is the id
  string `'chunk_' || raw_message.id`. A hand-inserted chunk IS indexed, because
  `trg_msg_chunk_fts_insert` is `AFTER INSERT ON msg_chunk` — the stale-chunk test depends on that.
- **An external-content FTS5 table resolves a NULL rowid to a content row.** `msg_chunk_fts` is
  `content='msg_chunk'` and stores no text. Joining
  `msg_chunk_fts.rowid = (SELECT rowid FROM msg_chunk WHERE id = 'chunk_' || raw_message.id)` gives
  a NULL subquery for any message with no chunk (every `system` message), and SQLite then resolves
  that NULL to the content row at the same position — returning the system message **twice**. Always
  join `raw_message -> msg_chunk -> msg_chunk_fts` by id. This is why the device run mattered: the
  `:core` fake cannot reproduce it.
- **Real row ids share the alias prefixes.** Proposals are `p_<hex6>`, soul facts `s_<hex8>`, raw
  messages `mN`. An alias-shaped ref the map never issued must pass `resolveRef` unchanged and fail
  at the storage lookup; rejecting it at resolution rejects real ids (D-130).
- **Splitting entangled hunks: rebuild from HEAD, don't `git checkout`.** `formatSearchResults` is
  introduced by the `search_history` change but calls `aliasFor` from the alias change, so
  path-level staging cannot separate them. Extract `git show HEAD:<file>`, apply change-set A with
  Edit, commit, then re-apply change-set B. **Do not `git checkout -- <file>` to get back to HEAD**
  unless the work is committed or stashed — it destroyed the alias work once this session.
- **The Bash tool retains cwd between calls.** An earlier `cd core/build/test-results/test` made a
  later `ls app/src/` report "No such file or directory". Use absolute paths.
- Never leave `-Pandroid.testInstrumentationRunnerArguments.class=…` on a full-suite run.
- `connected/` holds a stale `release/` variant; scope recounts to `connected/debug`.
