# HANDOFF (v1 audit §6 item 6 — offline queue, unit-verified + independently verified, COMMITTED as `20fa046`)

Read this first. Facts only; every claim below has a command and an exit code behind it.

## 1. What changed

**One logical change: audit §6 item 6, "offline queue"** (row 16 / blueprint 41 §3.3, D-138).
Committed as `20fa046` (`feat(core,app): offline chat queue via outbox, drain on start/open`).
Item 3 (D-137, onboarding) is `77dbeca`, already in `git log`.

| File | Status | What |
|---|---|---|
| `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt` | modified | DB **v5→v6**: `CREATE TABLE IF NOT EXISTS outbox` + indexes, shared DDL between onCreate and onUpgrade |
| `core/src/main/java/com/anchor/core/storage/Storage.kt` | modified | outbox CRUD on the interface |
| `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt` | modified | outbox CRUD (`INSERT OR IGNORE` on `idem_key` UNIQUE) |
| `core/src/test/java/com/anchor/core/SharedFakeStorage.kt` | modified | outbox CRUD in the fake |
| `app/src/main/java/com/anchor/mine/provider/ConnectivityOracle.kt` | **new** | wraps `ConnectivityManager` for the pre-send offline check |
| `app/src/main/AndroidManifest.xml` | modified | `ACCESS_NETWORK_STATE` |
| `core/src/main/java/com/anchor/core/ui/ChatController.kt` | modified | `enqueueChatTurn`, `drainQueuedTurns` (one aggregated turn, instruction injected into the pack, `markBatchAnswered` only after the reply is stored, claim-by-re-enqueue `next_try_at = now + 60s`), drain on `init` after first frame, mid-send `OfflineException` re-enqueue |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` / `MainViewModel.kt` | modified | drain on `onResume`; `offlineQueuedText` banner |
| `core/src/main/java/com/anchor/core/ui/ChatContract.kt` | modified | `UiState.queuedChatIds` / `offlineQueuedText` derived every `refreshUI()` |
| `core/src/main/java/com/anchor/core/provider/ProviderOrchestrator.kt` / `Models.kt` / `models/Memory.kt` | modified | supporting types |
| `core/src/test/java/com/anchor/core/ui/OfflineQueueTest.kt` | **new** | 6 cases |
| `docs/agent/*.md` | modified | D-138 (next free **D-139**), STATE/TASKS (V1-G7) / LEARNINGS / HANDOFF |

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| Unit suites + assemble | `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --offline` | `BUILD SUCCESSFUL`, EXIT=0; XML recount **core 341/0/0/0** (335 + 6 `OfflineQueueTest`), **app 13/0/0/0** |
| RED probe | offline branch in `sendText` → `if (false)` | 5 of 6 cases red |
| Independent verification | verification agent re-ran build/suites, read drain/migration/manifest code | **VERDICT: PASS** on all 6 checks; `markBatchAnswered` only after reply stored; mid-send `OfflineException` re-enqueue covered; migration additive-only; `idem_key UNIQUE` + `INSERT OR IGNORE`; permission present |

## 3. NOT Verified

- **No device run on this tree.** The airplane-mode round trip (blueprint 41 §8 test 1) is unproven.
- **Claim re-enqueue not red-probed** — unreachable on the unconfined harness (the first drain
  completes and settles the job inside the same drain); documented in the D-138 residual note.
  It is defence-in-depth for "process stopped after claiming, before the reply is stored".
- **Queue drains on app open, not the instant network returns** — accepted residual (D-138): no
  WorkManager job, no retry timer, no `ConnectivityManager` callback.
- **Banner wording** ("Offline: your messages are saved.") remains even when the device is online
  but the provider is down — it is derived from the queue, not connectivity.
- Carried over: threshold-vs-base-value vacuous (D-136); rule 6 local-only soul-fact invisibility
  (D-135/D-137 open question); nudge tables schema-only beyond the tick path; D-105 by-eye verdict.

## 4. Current failing tests

None known. `:core:test` 341/0/0/0, `:app:testDebugUnitTest` 13/0/0/0.

## 5. Open decisions needing you

- **Device half:** run the connected suite + airplane-mode round trip when the phone is available.
- **Next slice: audit §6 item 7** — release signing; the release is signed with the **debug key**
  at `app/build.gradle.kts:61` (row 22).
- Carried from earlier: rule 6 for local-only soul facts (hidden vs listed-and-locked); D-105
  by-eye verdict.

## 6. Traps for the next run

Carried over verbatim from the previous handoff (all still true):

- **A green connected run still prints `> Task :app:connectedDebugAndroidTest FAILED` /
  `BUILD FAILED`** with `test-result-exit-code.txt` = `1`. Read `CONNECTED GATE: …` and `TEST-*.xml`,
  never the task's status (D-123).
- **The `CONNECTED GATE:` line comes from `:app:connectedDebugAndroidTestVerify`** — run it after
  the suite; it reads the XML already on disk.
- **`onAction` returning does not mean the write landed.** `AndroidStorage` writes on
  `Dispatchers.IO`; connected tests must poll (`awaitDone`).
- **`-Pandroid.testInstrumentationRunnerArguments.class=A,B` runs only A.** Run classes separately.
- **Never start a RED probe while a background connected run may still be alive.** Check
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'gradle|utp|am instrument|adb logcat' }"`
  — ignore idle Gradle/Kotlin daemons; look for `utp`, `am instrument`, `adb logcat`. `cp file /tmp/file.bak` **before** editing.
- **After a probe, prove the restore with `cmp`.**
- **Do not edit large tracked docs with an inline `python` heredoc.** Write the script with the
  Write tool, run it, assert on the result (a mid-write encode error truncates the file to 0 bytes).
- **`STATE.md`'s `Updated:` line is one ~7k-character line.** Carry the old tail forward verbatim.
- **`core` has no `compileDebugKotlin`.** Use `:core:compileKotlin --offline`.
- **Append tabs, don't insert them.** `when (selectedTab)` is positional; connected tests click by
  title then assume the index.
- **Recount XML per immediate child directory** of `build/test-results/`; the connected recount must
  scope to `connected/debug/` (`connected/release/` holds a stale 6-test `ExportTest` XML).

## 7. Exact next step

When the phone is available: connected suite (`:app:connectedDebugAndroidTest` +
`:app:connectedDebugAndroidTestVerify`) and the airplane-mode round trip, then audit §6 item 7
(release signing) from `docs/agent/work/audit-2026-11-08-v1-scope.md` §6.

Optional, on an idle machine: the E-17 tiebreak RED probe (see the previous handoff §7 for the recipe —
`AndroidStorage.kt`, remove `, rowid DESC` from `getRecentMessages` only).
