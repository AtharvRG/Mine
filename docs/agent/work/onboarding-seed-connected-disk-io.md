# Branching Analysis & Root Cause Investigation: OnboardingSeedConnectedTest SQLiteDiskIOException

## Goal and Invariant
- **Goal**: Identify the exact root cause of the failure in `com.anchor.mine.ui.OnboardingSeedConnectedTest.relaunch_AfterFinishing_DoesNotShowTheFlowAgain` during connected Android tests on moto g73 5G (gate failure, code 522 on `SELECT COUNT(*) FROM sqlite_schema;`).
- **Invariant**: The connected test suite must run hermetically without thread leaks, orphaned coroutines, unlinked file descriptor collisions, or uncaught background exceptions that crash the instrumentation process.

---

## 1. Symptom

In `app/build/reports/connected-gate/gate.json` and `TEST-moto g73 5G - 14.xml`:
- 101 tests run: 100 passed, 1 failed (`com.anchor.mine.ui.OnboardingSeedConnectedTest.relaunch_AfterFinishing_DoesNotShowTheFlowAgain`, elapsed 3.110s).
- Failure stack trace:
```
android.database.sqlite.SQLiteDiskIOException: disk I/O error (code 522): , while compiling: SELECT COUNT(*) FROM sqlite_schema;
	at net.zetetic.database.sqlcipher.SQLiteConnection.nativePrepareStatement(Native Method)
	at net.zetetic.database.sqlcipher.SQLiteConnection.acquirePreparedStatement(SQLiteConnection.java:973)
	at net.zetetic.database.sqlcipher.SQLiteConnection.executeForLong(SQLiteConnection.java:628)
	at net.zetetic.database.sqlcipher.SQLiteConnection.open(SQLiteConnection.java:240)
	at net.zetetic.database.sqlcipher.SQLiteConnection.open(SQLiteConnection.java:202)
	at net.zetetic.database.sqlcipher.SQLiteConnectionPool.openConnectionLocked(SQLiteConnectionPool.java:474)
	at net.zetetic.database.sqlcipher.SQLiteConnectionPool.reconfigure(SQLiteConnectionPool.java:317)
	at net.zetetic.database.sqlcipher.SQLiteDatabase.enableWriteAheadLogging(SQLiteDatabase.java:2300)
	at com.anchor.mine.storage.MineDatabaseCallback.onConfigure(MineDatabaseHelper.kt:17)
	at net.zetetic.database.sqlcipher.SupportHelper$1.onConfigure(SupportHelper.java:42)
	at net.zetetic.database.sqlcipher.SQLiteOpenHelper.getDatabaseLocked(SQLiteOpenHelper.java:374)
	at net.zetetic.database.sqlcipher.SQLiteOpenHelper.getReadableDatabase(SQLiteOpenHelper.java:302)
	at net.zetetic.database.sqlcipher.SupportHelper.getReadableDatabase(SupportHelper.java:65)
	at com.anchor.mine.storage.AndroidStorage$getSetting$2.invokeSuspend(AndroidStorage.kt:128)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:101)
	at kotlinx.coroutines.internal.LimitedDispatcher$Worker.run(LimitedDispatcher.kt:113)
	at kotlinx.coroutines.scheduling.TaskImpl.run(Tasks.kt:89)
	at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:589)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:823)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:720)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:707)
	Suppressed: kotlinx.coroutines.internal.DiagnosticCoroutineContextException: [StandaloneCoroutine{Cancelling}@c8c408d, Dispatchers.Unconfined]
```

Device logcat (`logcat-com.anchor.mine.ui.OnboardingSeedConnectedTest-relaunch_AfterFinishing_DoesNotShowTheFlowAgain.txt`):
```text
11-09 17:13:46.287 SQLiteLog: (5) statement aborts at 2: database is locked; [PRAGMA journal_mode=delete]
11-09 17:13:46.288 SQLiteConnection: Could not change the database journal mode of '/data/user/0/com.anchor.mine/databases/mine_onboarding_test.db' from 'wal' to 'delete' because the database is locked.
11-09 17:13:46.289 SQLiteLog: (28) file unlinked while open: /data/data/com.anchor.mine/databases/mine_onboarding_test.db
11-09 17:13:47.904 SQLiteLog: (1032) statement aborts at 2: attempt to write a readonly database; [PRAGMA journal_mode=delete]
11-09 17:13:47.905 SQLiteLog: (28) file unlinked while open: /data/data/com.anchor.mine/databases/mine_onboarding_test.db
11-09 17:13:48.531 sqlcipher: ERROR CORE sqlcipher_page_cipher: hmac check failed for pgno=1
11-09 17:13:48.531 sqlcipher: ERROR CORE sqlite3Codec: error decrypting page 1 data: 1
11-09 17:13:48.531 SQLiteLog: (522) disk I/O error in "SELECT COUNT(*) FROM sqlite_schema;"
11-09 17:13:48.539 MonitoringInstr: Handling an uncaught exception thrown on the thread DefaultDispatcher-worker-6.
11-09 17:13:48.542 AndroidJUnitRunner: Bringing down the entire Instrumentation process.
```

---

## 2. Hypotheses Table

| # | Hypothesis | Layer | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|---|
| H1 | **Leaked CoroutineScope across test lifecycle**: `OnboardingSeedConnectedTest.controller()` creates untracked `CoroutineScope(Dispatchers.Unconfined)`. Background jobs (`QuoteManager`, `loadInitialState`, `drainQueuedTurns`) from test 1, test 2, and `first` in test 3 continue running after test completion. When `@Before setup()` runs `context.deleteDatabase()`, the file is unlinked while still open by leaked threads. Subsequent queries reopen/read mismatched inodes, causing page 1 HMAC failure (code 522). | Concurrency / Lifecycle | Logcat will show `file unlinked while open` and thread dump will show multiple workers executing queries from different controllers concurrently. | Read test runner logcat thread dump. | Single thread accessing DB; no unlinked file log. | **CONFIRMED** |
| H2 | **Flawed WAL configuration in `onConfigure`**: `MineDatabaseCallback.onConfigure` calls `db.enableWriteAheadLogging()`. Because WAL was not configured at factory construction time, SQLCipher initializes in DELETE mode (`PRAGMA journal_mode=delete`) and reconfigures pool connections to WAL inside `onConfigure`. If another connection/thread exists, journal mode switching fails with `database is locked`, causing secondary connection corruption. | Config / Architecture | Logcat will show `PRAGMA journal_mode=delete` aborts and `database is locked` warnings during connection open. | Search logcat for journal_mode statements. | Database opens directly with WAL openFlags; no PRAGMA journal_mode lock contention. | **CONFIRMED** (compounding mechanism) |
| H3 | **Data contention between `first` and `relaunched` within Test 3**: `first` and `relaunched` operate concurrently on the same `AndroidStorage` / `dbHelper` without `first` being cancelled, causing connection pool exhaustion solely within test 3. | Logic / Data | The failure will happen even if tests 1 and 2 did not run. | Compare timestamp of `file unlinked while open` vs test start. | `file unlinked while open` occurs at 17:13:46.289 during `setup()`, before `relaunched` was constructed. | **KILLED** (contributing factor, but unlinking originated from prior test leak) |
| H4 | **Platform / OEM Android 14 SQLite VFS Quirk**: Moto g73 5G on Android 14 MediaTek kernel has a filesystem bug with SQLCipher WAL/SHM file unlinking. | Platform (Android 14) | Other connected tests deleting SQLCipher databases on moto g73 5G will fail similarly. | Check result of other 100 connected tests. | All 100 other tests passed cleanly on the exact same device and kernel. | **KILLED** |
| H5 | **Hardware / Storage space exhaustion**: Device storage is full, causing real OS write error 522. | Hardware / Storage | `df /data` will show near 100% capacity; ENOSPC in logs. | `adb shell df /data` | `/data` has 53GB free (55% use); error is HMAC mismatch, not ENOSPC. | **KILLED** |

---

## 3. Evidence Pass

1. **Device Storage (`adb shell df /data`)**:
   `Available: 53382028 KB (~53 GB)`. Storage exhaustion killed.
2. **Suite Results (`TEST-moto g73 5G - 14.xml`)**:
   100 other connected tests passed, including `AndroidStorageTest`, `ExportTest`, and tests 1 & 2 of `OnboardingSeedConnectedTest`. Platform defect killed.
3. **Logcat Analysis (`logcat-...relaunch_AfterFinishing_DoesNotShowTheFlowAgain.txt`)**:
   - At `17:13:46.289` (immediately after test start during `@Before setup()`):
     `SQLiteLog: (28) file unlinked while open: /data/data/com.anchor.mine/databases/mine_onboarding_test.db`
   - At `17:13:48.531`:
     `sqlcipher: ERROR CORE sqlcipher_page_cipher: hmac check failed for pgno=1`
     `sqlcipher: ERROR CORE sqlite3Codec: error decrypting page 1 data: 1`
     `SQLiteLog: (522) disk I/O error in "SELECT COUNT(*) FROM sqlite_schema;"`
   - Thread dump at `17:13:48.548`:
     - `DefaultDispatcher-worker-4`: `AndroidStorage.createSoulFactAndCommit` (from `first.onAction(OnboardingFinish)`)
     - `DefaultDispatcher-worker-6`: `AndroidStorage.getSetting` (threw uncaught 522)
     - `DefaultDispatcher-worker-7`: `AndroidStorage.getReliabilityState` (from `loadInitialState` or `getQuoteStateCounts`)
     - `DefaultDispatcher-worker-9`: `AndroidStorage.getRecentlyClosed` (from `loadInitialState`)
4. **Code Inspection**:
   - `OnboardingSeedConnectedTest.kt` lines 79–93:
     `controller()` instantiates `ChatController(..., coroutineScope = CoroutineScope(Dispatchers.Unconfined), ...)`.
     The scope is never tracked, never bound to a `Job`, and never cancelled in `@After fun teardown()`.
   - `OnboardingSeedConnectedTest.kt` line 74–77:
     `teardown()` calls only `dbHelper.close()`. It does not cancel running jobs.
   - `ChatController.kt` lines 152–155 & `QuoteManager.kt` lines 268–273:
     `QuoteManager` launches background quote generation jobs without a `try/catch`, running `storage.getSetting("ep")` on `Dispatchers.IO`.
   - `MineDatabaseHelper.kt` lines 15–20 vs `DatabaseProvider.kt`:
     `MineDatabaseCallback.onConfigure` calls `db.enableWriteAheadLogging()`.
     `DatabaseProvider.getHelper` does not pass `enableWriteAheadLogging = true` to `SupportOpenHelperFactory`. Thus every open begins in journal mode `DELETE`, triggering journal mode renegotiation under active concurrency.

---

## 4. Elimination
- **H5 (Hardware/Storage Full)**: KILLED. 53 GB free; error is HMAC decryption failure.
- **H4 (Platform/OEM Bug)**: KILLED. 100/101 tests passed on the same OS and hardware.
- **H3 (Contention strictly within Test 3)**: KILLED as root cause. The file unlinking warning occurred at 17:13:46.289 inside `@Before setup()`, proving prior test background threads were still holding the database open when Test 3 attempted clean deletion.
- **H2 (WAL configuration in `onConfigure`)**: CONFIRMED as compounding architectural factor. Calling `db.enableWriteAheadLogging()` inside `onConfigure` causes connection pool reconfiguration and journal mode switching locks (`database is locked; [PRAGMA journal_mode=delete]`).
- **H1 (Leaked CoroutineScope)**: CONFIRMED as PRIMARY ROOT CAUSE. Leaked unconfined coroutines outlived the test methods, accessed closed/unlinked database files, and threw an uncaught exception on `DefaultDispatcher-worker-6` that aborted the instrumentation runner.

---

## 5. Root Cause

### Location
- **Primary**: `app/src/androidTest/java/com/anchor/mine/ui/OnboardingSeedConnectedTest.kt`:
  - Lines 74–77: `teardown()` only closes `dbHelper`, leaving background coroutines running.
  - Lines 79–93: `controller()` creates an anonymous, untracked `CoroutineScope(Dispatchers.Unconfined)` on every call.
  - Lines 136–143: `relaunch_AfterFinishing_DoesNotShowTheFlowAgain` creates two controllers (`first` and `relaunched`) concurrently without cancelling `first`.
- **Secondary**: `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt`:
  - Line 17: `db.enableWriteAheadLogging()` in `onConfigure`.
  - Lines 208–217: `DatabaseProvider.getHelper` uses `SupportOpenHelperFactory(password)` which defaults `enableWriteAheadLogging = false`.

### Mechanism
1. In `OnboardingSeedConnectedTest`, each test method creates one or more `ChatController` instances with untracked `CoroutineScope(Dispatchers.Unconfined)`.
2. Inside `ChatController.init` and `QuoteManager`, multiple background coroutines are launched (`loadInitialState`, `onHomeOpened -> generateLiveQuote`, `drainQueuedTurns`).
3. When test 1 and test 2 complete, `@After teardown()` calls `dbHelper.close()`, but does **not** cancel the coroutine scopes.
4. The orphaned coroutines continue executing on `Dispatchers.IO`.
5. When test 3 starts, `@Before setup()` calls `context.deleteDatabase("mine_onboarding_test.db")`.
6. Because orphaned coroutines still hold open SQLite file handles, Linux unlinks the inode while file descriptors remain open (`file unlinked while open`).
7. Test 3 creates a fresh `mine_onboarding_test.db` with a new `dbHelper`.
8. Concurrently, orphaned coroutines and `first`'s coroutines attempt to query `storage.getSetting(...)` / `storage.getRecentlyClosed(...)` through their open helpers.
9. SQLCipher tries to re-key or read page 1 on an unlinked or newly overwritten file. The 48-byte HMAC verification fails (`sqlcipher: ERROR CORE sqlcipher_page_cipher: hmac check failed for pgno=1`).
10. SQLCipher translates the HMAC check failure to `SQLITE_IOERR_SHORT_READ (code 522)` during `SELECT COUNT(*) FROM sqlite_schema;`.
11. The exception is thrown inside `StandaloneCoroutine` (with no try/catch in `QuoteManager` or `drainQueuedTurns`), bubbling up to `MonitoringInstr` on `DefaultDispatcher-worker-6`, which brings down the test runner.

### Confidence
- **99%**. Demonstrated by the exact logcat lines (`file unlinked while open`, `hmac check failed for pgno=1`, thread dump showing 4 workers across multiple controller tasks, uncaught exception on `DefaultDispatcher-worker-6`).
- **What would change my mind**: If the test runner failed with code 522 when `controller()` was passed an explicit `Job` that was cancelled and joined before `dbHelper.close()`.

---

## 6. Mandatory Angles

- **data/schema**: The database schema and rows are valid; code 522 is not database corruption on disk, but an HMAC check mismatch from an unlinked inode file descriptor.
- **concurrency/lifecycle**: Test lifecycle does not match coroutine lifecycle. Background coroutines must be tied to a test supervisor job and cancelled in `@After`.
- **failure paths**: Any unhandled exception on a standalone `launch` in `CoroutineScope` kills the instrumentation runner.
- **user-visible behaviour**: In production, `ChatController` is scoped to Android's `viewModelScope`, which is cancelled on activity destroy. Users would only see this if a background process outlived DB file deletion (e.g. during backup restore or data wipe).
- **privacy/security**: SQLCipher's security model is operating correctly: it strictly aborts when a page's HMAC does not validate against the key.
- **testability**: Connected tests must strictly isolate database and coroutine lifetimes.
- **blast radius**: Isolated to `OnboardingSeedConnectedTest` and any other tests instantiating `ChatController` with unbounded coroutine scopes.
- **platform (Android 14, OEM)**: Android 14 on Linux enforces POSIX unlinking semantics; deleting an open file removes the directory entry while existing file descriptors continue reading the orphaned inode until closed.
- **model behaviour**: Not applicable (`NoTransport` is used during onboarding).

---

## 7. Collateral

- If `OnboardingSeedConnectedTest` scopes are properly cancelled in `teardown()`, no other tests or production features are affected.
- If WAL mode configuration in `DatabaseProvider` is moved from `onConfigure` to `SupportOpenHelperFactory(password, null, true)`, all database opens will start natively in WAL mode, eliminating `PRAGMA journal_mode=delete` lock contention across the entire app.

---

## 8. Fix Options

### Option 1 (Smallest, Test-Focused): Manage CoroutineScope in `OnboardingSeedConnectedTest`
- In `OnboardingSeedConnectedTest`:
  - Maintain a `private val testJobs = mutableListOf<Job>()` (or a single `var testJob = SupervisorJob()`).
  - In `controller()`: pass `CoroutineScope(Dispatchers.Unconfined + testJob)`.
  - In `teardown()`: call `testJob.cancel()`, yield/delay, and then `dbHelper.close()`.
  - In `relaunch_AfterFinishing_DoesNotShowTheFlowAgain`: cancel `first`'s job before launching `relaunched`.
- **Trade-offs**: Smallest diff, fixes the test harness leak immediately, eliminates the gate failure. Does not touch production code.

### Option 2 (Production Hardening + Test Fix): Proper WAL Initialization in `DatabaseProvider` & Safe Test Scope
- In `DatabaseProvider.kt`:
  - Pass `enableWriteAheadLogging = true` to `SupportOpenHelperFactory`:
    `val factory = SupportOpenHelperFactory(password, null, true)`
  - Remove `db.enableWriteAheadLogging()` from `MineDatabaseCallback.onConfigure`.
- In `OnboardingSeedConnectedTest`:
  - Properly manage and cancel test coroutine scopes in `@After teardown()`.
- **Trade-offs**: Fixes both the test lifecycle leak and the underlying SQLCipher journal mode locking issue in production.

---

## 9. Pre-Mortem (3 Potential Regressions if Fixed Carelessly)
1. If `testJob.cancel()` is called after `dbHelper.close()`, leaked coroutines will throw `IllegalStateException: database already closed` instead of `SQLiteDiskIOException`. Cancellation must strictly happen *before* closing the database helper.
2. If `first` is cancelled before `awaitDone(first)` in `relaunch_AfterFinishing_DoesNotShowTheFlowAgain`, onboarding data will not be persisted to `app_meta`, causing `relaunched.state.value.onboardingDone` to assert false.
3. If WAL is enabled in `SupportOpenHelperFactory` but `onConfigure` continues to call `db.enableWriteAheadLogging()`, unnecessary pool reconfiguration still occurs. The call in `onConfigure` must be cleaned up if factory WAL is enabled.
