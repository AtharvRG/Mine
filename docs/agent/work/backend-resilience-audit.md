# Deep Backend Resilience & Defensive Edge Case Audit: Mine v1

**Date:** 2026-11-10  
**Scope:** Core backend & runtime resilience (`:core` and `:app` runtime backends: `ChatController`, `Gateway`, `Resolver`, `DateResolver`, `ContextBuilder`, Outbox, `TemplateEngine`, `PersonaEngine`, `LearningEngine`, `SettingsManager`, `AndroidStorage`, `DbKeyProvider`, background receivers and schedulers).  
**Lens:** Titanium-grade resilience — degrade gracefully, never silently lose user data, never claim success not backed by storage, eliminate crashes, eliminate race conditions, and never swallow coroutine cancellation.

---

## Executive Summary of Findings

| ID | Category | Severity | File & Line | Summary |
|---|---|---|---|---|
| **V-01** | UI / Lifecycle | **CRITICAL** | `ChatController.kt:275, 523, 682, 692, 710...` & `MainActivity.kt:222-245` | Benign background tick or action failures set `globalError`, permanently locking the entire app on a non-dismissible "Fatal Error" screen. |
| **V-02** | Crash Vector | **CRITICAL** | `ChatController.kt:339-499, 526-557` | 12+ UI Actions (`ArchiveNode`, `DeleteNode`, `Undo`, `ConfirmProposal`, `AcceptSoulFact`, etc.) lack `try/catch`; unexpected SQLite/I/O errors crash the app process. |
| **V-03** | Concurrency / Race | **CRITICAL** | `ChatController.kt:945-976, 1030-1040` | `drainQueuedTurns` does not set `isChatLoading = true`; races foreground `SendMessage`, leading to stream overwrite and in-memory message wipes. |
| **V-04** | Logic Defect | **CRITICAL** | `QuickCapture.kt:94-98, 106-111` | Inverted chronological index math in `QuickCapture.pending` and `isUnanswered` causes queued offline captures to be permanently abandoned on startup. |
| **V-05** | Logic / Nudges | **HIGH** | `NudgeEngine.kt:53, 63` & `AndroidStorage.kt:860` | `sentToday` is incremented on each nudge but never reset on day rollover, permanently disabling proactive nudges after 40 lifetime nudges. |
| **V-06** | Concurrency / Race | **HIGH** | `ChatController.kt:849-853` | Unsynchronized check-then-act on `_state.value.isChatLoading` in `sendText` allows concurrent turns to execute simultaneously and overwrite streaming state. |
| **V-07** | Concurrency / CAS | **HIGH** | `Gateway.kt:437, 525, 624, 653, 685, 725...` | 16 call sites call `consumeProposal` without CAS; concurrent chip taps or resolver executions create duplicate or conflicting node states. |
| **V-08** | Platform / Locale | **HIGH** | `DateResolver.kt:124` | `dueText.lowercase(Locale.getDefault())` transforms `"in"` / `"minutes"` to dotless `"ın"` on Turkish/Azerbaijani locales, breaking all relative time parsing. |
| **V-09** | Concurrency / Logic | **MEDIUM** | `ReliabilityManager.kt:28-46` & `AlarmReceiver.kt:58-66` | Sweeper races foreground cancellation/completion of a reminder; `AlarmReceiver` bypasses null-row check for missed reminders, alerting for cancelled tasks. |
| **V-10** | Arithmetic Overflow | **MEDIUM** | `ReliabilityManager.kt:40` | `(now - reminder.fireAtMillis).toInt()` overflows to a negative integer if a reminder is overdue by >24.8 days, corrupting `p95LatencyMs` calculations. |
| **V-11** | Arithmetic Overflow | **MEDIUM** | `Resolver.kt:111, 118, 141` | `Math.abs(due - targetTimeMillis)` with extreme timestamps overflows `Long.MIN_VALUE`, yielding negative numbers and erroneous match selection. |
| **V-12** | Coroutine Safety | **MEDIUM** | `ExportController.kt:102-106` | Generic `catch (e: Exception)` in export/import launch blocks swallows `CancellationException` and surfaces spurious error results. |
| **V-13** | LLM Streaming | **MEDIUM** | `ChatController.kt:1468-1488` | `extractReplyFromJsonStream` fails to extract reply if LLM outputs `"reply": "..."` (whitespace after colon) or backslash-escaped strings. |
| **V-14** | Resource Limits | **MEDIUM** | `AndroidStorage.kt:473-488` | `getActiveSoulFacts()` has no `LIMIT` clause, loading all lifetime soul facts into heap memory on every single chat turn and UI rebuild. |
| **V-15** | Platform / Security | **LOW** | `AndroidAlarmScheduler.kt:31` & `AlarmReceiver.kt:69, 118` | Unchecked casts `as AlarmManager` and `as NotificationManager` can throw `NullPointerException` on unsupported or headless devices. |

---

## Detailed Vulnerability & Edge Case Findings

### 1. V-01: Non-Fatal Action & Background Errors Brick the Entire UI via `globalError`
- **Code Location:** `core/.../ui/ChatController.kt:275, 523, 682, 692, 710, 719, 729, 746, 755, 764, 773` and `app/.../MainActivity.kt:222-245`
- **Failure Scenario:**
  `ChatController` catches non-fatal exceptions in UI actions (e.g., `SaveProvider`, `LoadMemory`, `SetPersona`, `SetAppLock`) and background operations (`TickProactive`, `DrainQueuedTurns`) by calling:
  ```kotlin
  _state.update { it.copy(globalError = "Could not run the proactive check: ${e.message}") }
  ```
  In `MainActivity.kt`:
  ```kotlin
  if (state.globalError != null) {
      Box(modifier = Modifier.fillMaxSize(), ...) {
          Text(if (state.globalError == "KEYSTORE_LOST") "Keystore Key Lost" else "Fatal Error")
          ...
      }
      return // Skips rendering the entire app UI
  }
  ```
  If a background proactive check or outbox drain encounters a transient SQLite lock or I/O glitch, `globalError` is populated. `MainActivity` intercepts this and replaces the entire app with a non-dismissible fatal error screen with no back button, locking the user out of their chat, memory, and settings until the app process is force-killed.
- **Titanium-Grade Defensive Fix:**
  1. Reserve `globalError` strictly for unrecoverable startup database failure (`KEYSTORE_LOST` or permanent database corruption).
  2. For action and background errors, use a transient error channel (e.g. `transientReceipt` or a dedicated `actionError: String?` StateFlow field that auto-dismisses).
- **Test That Catches It:**
  ```kotlin
  @Test
  fun backgroundProactiveError_DoesNotBrickUiState() = runTest {
      val controller = createTestChatControllerWithFailingStorage()
      controller.onAction(UiAction.TickProactive)
      assertNull(controller.state.value.globalError)
  }
  ```

---

### 2. V-02: Missing Exception Boundaries in 12+ UI Actions in `ChatController`
- **Code Location:** `core/.../ui/ChatController.kt:339-499, 526-557`
- **Failure Scenario:**
  The following UI actions launched via `coroutineScope.launch(defaultDispatcher)` contain zero `try/catch` blocks:
  - `UiAction.ArchiveNode` (lines 526-541)
  - `UiAction.DeleteNode` (lines 542-557)
  - `UiAction.AcceptSoulFact` (lines 339-348)
  - `UiAction.RejectSoulFact` (lines 349-358)
  - `UiAction.Undo` (lines 363-382)
  - `UiAction.ConfirmProposal` (lines 383-431)
  - `UiAction.AddFollowUp` (lines 432-448)
  - `UiAction.RemoveFollowUp` (lines 449-465)
  - `UiAction.LoadDay` (lines 466-469)
  - `UiAction.ShowDayReminders` (lines 470-473)
  - `UiAction.KeepStale` (lines 483-497)
  - `UiAction.CancelStale` (lines 498-512)
  - `UiAction.RunSelfTest` (lines 309-338)
  If the disk is full (`SQLiteFullException`), or if a transient SQLite constraint / I/O error occurs, the exception propagates unhandled out of the `coroutineScope.launch` block. In Android coroutine scopes (such as `viewModelScope`), an unhandled exception triggers the default thread `UncaughtExceptionHandler`, instantly terminating the app process with a fatal crash.
- **Titanium-Grade Defensive Fix:**
  Wrap each action handler in a structured `runCatching` block. Catch `Throwable` (re-throwing `CancellationException`), log the failure to `diag_log`, and surface a user-friendly error receipt:
  ```kotlin
  is UiAction.ArchiveNode -> coroutineScope.launch(defaultDispatcher) {
      try {
          val result = gateway.handleArchiveNode(action.nodeId)
          ...
      } catch (e: CancellationException) {
          throw e
      } catch (e: Throwable) {
          addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not archive item: ${e.message}", null, isError = true, timestamp = clock.now()))
      }
  }
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun uiAction_WhenStorageThrows_DoesNotCrashScope() = runTest {
      val fakeStorage = FailingStorage(throwOnCommit = true)
      val controller = ChatController(fakeStorage, ...)
      controller.onAction(UiAction.ArchiveNode("n_123"))
      // Verify scope is active and receipt reflects error
      assertTrue(controller.state.value.receipts.any { it.isError })
  }
  ```

---

### 3. V-03: `drainQueuedTurns` Collides with Foreground `SendMessage`
- **Code Location:** `core/.../ui/ChatController.kt:945-976, 1030-1040`
- **Failure Scenario:**
  1. `drainQueuedTurns()` claims pending outbox jobs and prepares an offline batch turn. It explicitly avoids setting `_state.update { it.copy(isChatLoading = true) }`.
  2. Inside `drainQueuedTurns()`, it calls:
     ```kotlin
     handleSendMessage(offlineBatchInstruction(ids, now), existingUserMsgId = null, queuedBatch = true)
     ```
  3. `handleSendMessage()` launches a coroutine onto `currentStreamJob`.
  4. While the batch is streaming, `_state.value.isChatLoading` remains `false`.
  5. The user types a message in the composer and presses Send (`UiAction.SendMessage`). Because `isChatLoading` is `false`, the check `if (_state.value.isChatLoading) return` passes!
  6. `SendMessage` launches a SECOND `handleSendMessage()` coroutine, immediately overwriting `currentStreamJob`.
  7. Two independent coroutines are now streaming from the LLM provider orchestrator simultaneously into `_state.chatMessages`.
  8. Furthermore, line 1040 executes `refreshUI()`, which evaluates `chatMessages = if (it.isChatLoading) it.chatMessages else storage.getRecentMessages(30)`. Because `isChatLoading` is `false`, it re-reads from disk and wipes the streaming assistant placeholder (`botId`) out of memory!
- **Titanium-Grade Defensive Fix:**
  1. Protect the chat streaming pipeline behind a coroutine `Mutex`:
     ```kotlin
     private val sendMutex = Mutex()
     ```
  2. In `drainQueuedTurns`, set `isChatLoading = true` atomically when acquiring the outbox lock, and maintain it until the turn's `finally` block terminates.
- **Test That Catches It:**
  ```kotlin
  @Test
  fun drainQueuedTurns_WhileUserSendsMessage_DoesNotCorruptTimeline() = runTest {
      // Simulate slow outbox drain and concurrent SendMessage
      ...
  }
  ```

---

### 4. V-04: Inverted Chronological Search Drops Stored Quick Captures on Startup
- **Code Location:** `core/.../capture/QuickCapture.kt:94-98, 106-111`
- **Failure Scenario:**
  `QuickCapture.pending(storage)` and `QuickCapture.isUnanswered(storage, messageId)` attempt to find quick-capture messages (`q_...`) that have not yet been answered by an assistant turn:
  ```kotlin
  suspend fun isUnanswered(storage: Storage, messageId: String): Boolean {
      val recent = storage.getRecentMessages(200) // Sorted DESCENDING (newest first)
      val mine = recent.indexOfFirst { it.id == messageId }
      if (mine < 0) return false
      return !hasAssistantAfter(recent, mine)
  }

  private fun hasAssistantAfter(messages: List<ChatMessage>, index: Int): Boolean =
      messages.drop(index + 1).any { it.role == "assistant" && !it.content.isNullOrBlank() }
  ```
  `storage.getRecentMessages(200)` returns messages in descending order (index 0 is newest, index 199 is oldest).
  `messages.drop(index + 1)` inspects all messages with an index *greater* than `index` — which in a descending list are messages **older** than the user's capture!
  If any assistant message existed in conversation history prior to the capture, `hasAssistantAfter` returns `true`. Consequently, `isUnanswered` returns `false`.
  At cold start, `MainViewModel.kt:91` calls `QuickCapture.pending(loadedStorage)` to drain offline captures. Due to this inverted index logic, `pending()` returns an empty list. The user's notification reply is permanently abandoned and never sent to the assistant.
- **Titanium-Grade Defensive Fix:**
  In a newest-first list, messages that arrived *after* message `index` are located at indices `0 until index`:
  ```kotlin
  private fun hasAssistantAfter(messages: List<ChatMessage>, index: Int): Boolean =
      messages.take(index).any { it.role == "assistant" && !it.content.isNullOrBlank() }
  ```
  Additionally, reverse the list returned by `pending()` so captures are processed in chronological (oldest-first) order.
- **Test That Catches It:**
  ```kotlin
  @Test
  fun pendingCaptures_WithPriorAssistantHistory_AreRecognizedAsPending() = runTest {
      val storage = FakeStorage()
      storage.insertRawMessage("a_old", "assistant", "Hello")
      storage.insertRawMessage("q_new", "user", "Buy milk")
      val pending = QuickCapture.pending(storage)
      assertEquals(listOf("q_new"), pending)
  }
  ```

---

### 5. V-05: Permanent Nudge Fuse Lockout (`sentToday` Never Reset)
- **Code Location:** `core/.../nudge/NudgeEngine.kt:53, 63` and `app/.../storage/AndroidStorage.kt:860-871`
- **Failure Scenario:**
  In `NudgeEngine.evaluate()`:
  ```kotlin
  // 5. Hidden Fuse (e.g., max 40 per day)
  if (state.sentToday >= 40) return GateResult.Held("fuse_tripped")
  ```
  In `NudgeEngine.onNudgeSent()`:
  ```kotlin
  sentToday = state.sentToday + 1
  ```
  `ProactiveState` has a `dayKey: String?` column in SQLite. However, nowhere in `NudgeEngine`, `AndroidStorage`, `DayRollover`, or `ChatController` is `sentToday` ever checked against the current day key or reset to 0 upon day rollover.
  Once 40 nudges have been sent over the lifespan of the device installation, `sentToday >= 40` becomes permanently true. Every subsequent overdue and stale item check is rejected with `fuse_tripped`, permanently disabling proactive assistance.
- **Titanium-Grade Defensive Fix:**
  In `NudgeEngine.evaluate` (or when retrieving `ProactiveState` from storage), check if the state's `dayKey` matches the current calendar day key. If it does not match, reset `sentToday = 0` and update `dayKey`:
  ```kotlin
  val effectiveState = if (state.dayKey != currentDayKey) {
      state.copy(sentToday = 0, dayKey = currentDayKey)
  } else state
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun nudgeEngine_WhenDayRolloverOccurs_ResetsSentTodayFuse() {
      val state = ProactiveState(sentToday = 40, dayKey = "2026-11-09")
      val nextDayResult = nudgeEngine.evaluate(candidate, state, currentDayKey = "2026-11-10")
      assertNotEquals(GateResult.Held("fuse_tripped"), nextDayResult)
  }
  ```

---

### 6. V-06: Unsynchronized Check-Then-Act Race in `ChatController.sendText`
- **Code Location:** `core/.../ui/ChatController.kt:849-853`
- **Failure Scenario:**
  In `sendText`:
  ```kotlin
  while (_state.value.isChatLoading) {
      val job = currentStreamJob ?: break
      job.join()
  }
  _state.update { it.copy(isChatLoading = true, connectionError = null) }
  ...
  handleSendMessage(text, userMsgId)
  ```
  If two background events fire near-simultaneously (e.g. `QuickCaptureReceiver` notification reply while a scheduled capture drain runs), both coroutines read `_state.value.isChatLoading == false`. Both exit the while loop, set `isChatLoading = true`, and both launch streaming jobs via `handleSendMessage`.
  Job 2 overwrites `currentStreamJob`. Both stream chunks and mutate `_state.chatMessages` concurrently. When Job 1 finishes, its `finally` block sets `isChatLoading = false` while Job 2 is still in flight.
- **Titanium-Grade Defensive Fix:**
  Use a coroutine `Mutex` to serialize turn execution:
  ```kotlin
  private val sendMutex = Mutex()
  fun sendText(...) = coroutineScope.launch(defaultDispatcher) {
      sendMutex.withLock {
          handleSendMessageInternal(...)
      }
  }
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun concurrentSendText_ExecutesSequentiallyWithoutStateCorruption() = runTest {
      val controller = createTestChatController()
      val job1 = async { controller.sendText("First") }
      val job2 = async { controller.sendText("Second") }
      joinAll(job1, job2)
      // Assert exactly 2 user messages and 2 assistant responses in timeline
  }
  ```

---

### 7. V-07: Non-CAS `consumeProposal` Across 16 Gateway Sites
- **Code Location:** `core/.../gateway/Gateway.kt:437, 525, 532, 624, 653, 685, 692, 725, 750, 756, 989, 1014, 1018, 1050, 1115, 1121`
- **Failure Scenario:**
  While transactional node mutations use `consumeProposalInTx` / `consumeProposalCas`, multiple branches call plain `storage.consumeProposal(id)`:
  - Lines 624-625: A time proposal duplicate detection consumes the old proposal and saves a new proposal outside of a transaction.
  - Lines 682-685 & 689-692: Check-then-act on proposal status:
    ```kotlin
    if (storage.getProposal(resolvedProposalId)?.status != "open") {
        return ToolResult.Error("Proposal already resolved")
    }
    storage.consumeProposal(proposal.id)
    ```
    If two chip taps occur concurrently, both read `status == "open"` before either writes `consumed`, leading to double execution.
- **Titanium-Grade Defensive Fix:**
  Enforce compare-and-swap on every single proposal consumption:
  ```kotlin
  if (!storage.consumeProposalCas(proposal.id)) {
      return ToolResult.Error("Proposal already resolved")
  }
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun concurrentProposalConfirmation_OnlyFirstTapWins() = runTest {
      val proposal = createOpenProposal()
      val res1 = async { gateway.handleConfirmChange(proposal.id, "opt1") }
      val res2 = async { gateway.handleConfirmChange(proposal.id, "opt2") }
      val results = listOf(res1.await(), res2.await())
      assertTrue(results.any { it is ToolResult.Committed })
      assertTrue(results.any { it is ToolResult.Error && it.reason == "Proposal already resolved" })
  }
  ```

---

### 8. V-08: Turkish/Azerbaijani Locale (`Locale.getDefault()`) Breaks Relative Time Parsing
- **Code Location:** `core/.../time/DateResolver.kt:124`
- **Failure Scenario:**
  In `DateResolver.resolve()`:
  ```kotlin
  val normalized = normalizeDigits(dueText).lowercase(Locale.getDefault()).trim()
  ```
  On devices configured with Turkish (`tr`) or Azerbaijani (`az`) locales, the Latin uppercase `'I'` lowercases to the dotless small `'ı'` (`\u0131`).
  When a user enters `"in 15 minutes"` or `"IN AN HOUR"`:
  - `"in"` becomes `"ın"`.
  - `"minutes"` becomes `"mınutes"`.
  The regex patterns in `DateResolver` (`PREFIX_RE = Regex("""^(?:in|after|within)\s+""")` and `UNIT_RE = Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|s|m|h|d)\b""")`) strictly expect ASCII `'i'` (`\u0069`).
  Consequently, `parseDuration()` returns `null`, and `DateResolver` returns `DateResolution.Failed` for all relative times.
- **Titanium-Grade Defensive Fix:**
  Always normalize user time strings using `Locale.US` or `Locale.ROOT`:
  ```kotlin
  val normalized = normalizeDigits(dueText).lowercase(Locale.US).trim()
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun resolve_InTurkishLocale_ParsesRelativeDurationsCorrectly() {
      val defaultLocale = Locale.getDefault()
      try {
          Locale.setDefault(Locale("tr", "TR"))
          val resolver = DateResolver(FakeClock(1000L))
          val res = resolver.resolve("in 10 minutes", 0)
          assertTrue(res is DateResolution.Exact)
      } finally {
          Locale.setDefault(defaultLocale)
      }
  }
  ```

---

### 9. V-09: Sweeper Broadcast Alerting for Cancelled/Completed Reminders
- **Code Location:** `core/.../reliability/ReliabilityManager.kt:28-46` and `app/.../alarms/AlarmReceiver.kt:58-66`
- **Failure Scenario:**
  1. The background sweeper runs and queries `storage.getPendingReminders()`. It finds reminder `r_123` scheduled for 10 minutes ago.
  2. Concurrently, the user in the foreground chat cancels or completes the item (`node.status = "done"`, `deleteReminder("r_123")`).
  3. The sweeper's broadcast arrives at `AlarmReceiver`:
     ```kotlin
     val rem = storage.getPendingReminders().find { it.id == id } ?: storage.getReminderById(id)
     if (rem == null && !isMissed) return@launch
     ```
  4. Because `isMissed == true`, the condition `!isMissed` is `false`, and `AlarmReceiver` DOES NOT RETURN even though `rem == null`!
  5. `AlarmReceiver` uses the fallback text and posts a high-priority notification: `"Missed: [Title]"`.
  6. The user is alerted with a missed reminder notification for an item they had deliberately completed or deleted.
- **Titanium-Grade Defensive Fix:**
  When `rem == null` and `isMissed == true`, look up the associated node by `id` or `nodeId`. If the node exists and is in `cancelled`, `done`, or `deleted` state, discard the broadcast:
  ```kotlin
  if (rem == null) {
      if (!isMissed) return@launch
      val node = storage.getNode(id.removePrefix("r_"))
      if (node != null && node.status in listOf("cancelled", "done")) return@launch
  }
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun alarmReceiver_WhenMissedBroadcastArrivesForCompletedNode_SuppressesNotification() = runTest {
      ...
  }
  ```

---

### 10. V-10: Overdue Latency Integer Overflow in `ReliabilityManager.runSweeper`
- **Code Location:** `core/.../reliability/ReliabilityManager.kt:40`
- **Failure Scenario:**
  In `runSweeper`:
  ```kotlin
  latencyMs = (now - reminder.fireAtMillis).toInt()
  ```
  `Int.MAX_VALUE` in milliseconds is ~2,147,483,647 ms (~24.8 days).
  If a user leaves a secondary phone turned off for over a month (e.g. 30 days = 2,592,000,000 ms), `(now - reminder.fireAtMillis)` exceeds `Int.MAX_VALUE`.
  In Kotlin, `.toInt()` overflows without throwing an exception, wrapping to a negative integer (e.g. `-1,702,967,296`).
  This negative value is persisted in `reminder_fire.latency_ms`. When `evaluateReliabilityScore()` sorts recent fire latencies, negative numbers pollute the p95 percentile calculation, corrupting reliability monitoring.
- **Titanium-Grade Defensive Fix:**
  Clamp the duration using saturation arithmetic:
  ```kotlin
  latencyMs = (now - reminder.fireAtMillis).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun runSweeper_WithReminderOverdueBy60Days_ClampsLatencyWithoutOverflow() = runTest {
      val now = 100_000_000_000L
      val fireAt = now - (60L * 24 * 3600 * 1000L) // 60 days in past
      ...
      assertEquals(Int.MAX_VALUE, recordedFire.latencyMs)
  }
  ```

---

### 11. V-11: `Math.abs(Long.MIN_VALUE)` Overflow in `Resolver.kt`
- **Code Location:** `core/.../resolver/Resolver.kt:111, 118, 141`
- **Failure Scenario:**
  ```kotlin
  if (due == null || Math.abs(due - targetTimeMillis) < (1000L * 60 * 60 * 24))
  ```
  If `targetTimeMillis` is malformed (e.g. parsed from negative input or `Long.MIN_VALUE`), `(due - targetTimeMillis)` can equal `Long.MIN_VALUE`.
  In Java/Kotlin, `Math.abs(Long.MIN_VALUE)` is `Long.MIN_VALUE` (a negative number).
  Because any negative number is `< (1000L * 60 * 60 * 24)`, the check evaluates to `true`, incorrectly treating completely disparate dates as matching the same time window.
- **Titanium-Grade Defensive Fix:**
  Use safe duration difference:
  ```kotlin
  val diff = kotlin.math.abs(due.toDouble() - targetTimeMillis.toDouble())
  if (diff < (1000.0 * 60 * 60 * 24))
  ```
  Or saturation check on `due - targetTimeMillis`.
- **Test That Catches It:**
  ```kotlin
  @Test
  fun resolver_WithExtremeDueTimestamp_DoesNotUnderflowTimeWindow() {
      val node = createNode(dueAt = Long.MAX_VALUE)
      val res = resolver.resolve(IntentType.RESCHEDULE, "test", Long.MIN_VALUE, listOf(node))
      assertNotEquals(Resolution.ClearWinner(node), res)
  }
  ```

---

### 12. V-12: Swallowed `CancellationException` in `ExportController`
- **Code Location:** `core/.../export/ExportController.kt:102-106`
- **Failure Scenario:**
  ```kotlin
  job = scope.launch {
      try {
          finish(block(), isError = false)
      } catch (e: Exception) {
          finish(e.message ?: e::class.java.simpleName, isError = true)
      }
  }
  ```
  In Kotlin coroutines, `CancellationException` inherits from `IllegalStateException` -> `Exception`.
  When a backup/export job is cancelled (e.g. user leaves the backup screen or cancels the scope), `catch (e: Exception)` catches the cancellation exception.
  It invokes `finish("Job was cancelled", isError = true)`, setting `_state.resultIsError = true` and suppressing the coroutine cancellation protocol.
- **Titanium-Grade Defensive Fix:**
  Explicitly re-throw `CancellationException`:
  ```kotlin
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      finish(e.message ?: e::class.java.simpleName, isError = true)
  }
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun exportJob_WhenScopeCancelled_RethrowsCancellationException() = runTest {
      val controller = ExportController(...)
      controller.export(...)
      controller.cancel()
      assertFalse(controller.state.value.resultIsError)
  }
  ```

---

### 13. V-13: Incremental Stream Extraction Fails on JSON Whitespace & Backslashes
- **Code Location:** `core/.../ui/ChatController.kt:1468-1488`
- **Failure Scenario:**
  `extractReplyFromJsonStream` performs substring parsing to stream the `"reply"` field:
  ```kotlin
  val replyMarker = "\"reply\":\""
  val start = jsonStream.indexOf(replyMarker)
  ```
  1. If an LLM or proxy formats JSON with whitespace (e.g. `{"reply": "Hello"}` or `{"reply" : "Hello"}`), `jsonStream.indexOf(replyMarker)` returns `-1`. The parser yields `null` and streaming output is completely suppressed until the stream closes.
  2. When looking for the closing quote:
     ```kotlin
     while (end != -1 && jsonStream[end - 1] == '\\') {
         end = jsonStream.indexOf("\"", end + 1)
     }
     ```
     If the string ends with a backslash (e.g. a Windows path `"C:\\"` -> JSON `"reply":"C:\\\\"`), `jsonStream[end - 1]` is a backslash, but it is an escaped backslash, not an escaped quote. The loop treats the closing quote as escaped and walks past the true string end.
- **Titanium-Grade Defensive Fix:**
  Support optional whitespace in the reply marker (`Regex("""\"reply\"\s*:\s*\"""")`) and count preceding backslashes to verify quote escaping parity (odd count = escaped quote, even count = escaped backslash).
- **Test That Catches It:**
  ```kotlin
  @Test
  fun extractReplyFromJsonStream_WithSpacesAndBackslashes_ExtractsCorrectly() {
      val json = """{"reply" : "Folder C:\\Users\\"}"""
      val reply = ChatController.extractReplyFromJsonStream(json)
      assertEquals("""Folder C:\Users\""", reply)
  }
  ```

---

### 14. V-14: Unbounded Heap Allocation in `getActiveSoulFacts`
- **Code Location:** `app/.../storage/AndroidStorage.kt:473-488` and `core/.../context/ContextBuilder.kt:30, 105`
- **Failure Scenario:**
  `storage.getActiveSoulFacts(includeLocalOnly)` executes:
  ```sql
  SELECT id, category, statement, ... FROM soul_fact WHERE (status = 'active' OR status = 'pinned')
  ```
  There is no `LIMIT` clause.
  Over months of active usage, thousands of soul facts accumulate. Every chat turn calls `ContextBuilder.buildPack()`, which executes `getActiveSoulFacts()`, instantiating thousands of `SoulFact` objects on the heap. Even though `ContextBuilder` caps the prompt character budget and breaks early, the memory allocation and cursor iteration in `AndroidStorage` incur severe GC churn and potential `OutOfMemoryError` on lower-end devices.
- **Titanium-Grade Defensive Fix:**
  Add a sensible database-level limit (e.g. `LIMIT 100`) ordered by pinned status and recency (`ORDER BY pinned_core DESC, updated_at DESC LIMIT 100`).
- **Test That Catches It:**
  ```kotlin
  @Test
  fun getActiveSoulFacts_WithThousandsOfFacts_CapsQueryLimit() = runTest {
      val storage = AndroidStorage(...)
      // Insert 500 facts
      val facts = storage.getActiveSoulFacts(includeLocalOnly = false)
      assertTrue(facts.size <= 100)
  }
  ```

---

### 15. V-15: Unchecked System Service Casts
- **Code Location:** `app/.../alarms/AndroidAlarmScheduler.kt:31` and `app/.../alarms/AlarmReceiver.kt:69, 118`
- **Failure Scenario:**
  ```kotlin
  private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  ```
  If `getSystemService` returns `null` (e.g. in automotive Android variants, custom ROM restricted profiles, or headless instrumented testing environments), direct `as AlarmManager` / `as NotificationManager` throws `NullPointerException` / `TypeCastException` during class construction or broadcast processing.
- **Titanium-Grade Defensive Fix:**
  Use safe cast `as?` with fallback or typed check:
  ```kotlin
  val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      ?: return@launch
  ```
- **Test That Catches It:**
  ```kotlin
  @Test
  fun alarmScheduler_WhenSystemServiceNull_FailsGracefully() {
      // Mock context with null service
      ...
  }
  ```

---

## Edge Case Matrix & Input Resilience Summary

| Input / Edge Case | Component | Current Behavior | Resilience Verdict |
|---|---|---|---|
| **10,000 character user turn** | `ChatController`, `ContextBuilder` | Stored in `raw_message`; `ContextBuilder` includes full newest message without size cap, sending large payload to LLM. | **DEGRADED**: Works but risks LLM provider payload rejection / 400 Bad Request. Should truncate live turn to max token budget (e.g. 4,000 chars). |
| **Emojis, RTL, Unicode control chars** | `DateResolver`, `PromptSanitizer`, `Resolver` | `normalizeDigits` uses code points. `Resolver` uses Unicode regex `[\p{L}\p{M}\p{N}]+`. | **PASS**: Safe against non-ASCII numerals and script mixing. |
| **Turkish locale (`tr-TR`)** | `DateResolver` | `dueText.lowercase(Locale.getDefault())` transforms `'i'` to `'ı'`, breaking regexes. | **FAIL (V-08)**: Relative time parsing completely broken. |
| **Negative timestamps (`< 0`)** | `Gateway`, `DateResolver` | Checked against `clock.now()`; rejected as past time. | **PASS**: Handled cleanly. |
| **LLM Provider 429** | `ProviderOrchestrator`, `RateLimiter` | Applies cooldown from `Retry-After`, tries next target in chain. | **PASS**: Resilient failover. |
| **LLM Provider 500 / Socket Timeout** | `AndroidHttpTransport`, `ProviderOrchestrator` | Converted to `OfflineException`, queues turn for offline retry. | **PASS**: Clean queueing behavior. |
| **Mid-Stream Connection Cut** | `ChatController` | Cleans up streaming placeholder, but deletes partial response rather than marking `[Interrupted]`. | **MINOR**: User sees partial text disappear. |
| **Keystore Key Missing / Corrupt** | `DbKeyProvider`, `MineApp`, `MainViewModel` | Throws `KEYSTORE_LOST`, caught by `MineApp` and settled into `MainViewModel`, rendering Reset prompt. | **PASS**: Resilient fatal-recovery path. |
| **Local Storage Disk Full (`SQLiteFullException`)** | `ChatController`, `AlarmReceiver` | `handleSendMessage` handles it; UI actions crash coroutine scope (V-02); `AlarmReceiver` uses fallback notification. | **MIXED**: Fallback works, but UI actions crash. |

---

## Verification & Hardening Plan

1. **Immediate Scope (Eliminate Crashes & UI Bricking):**
   - Fix **V-01**: Decouple transient action/tick errors from fatal `globalError`.
   - Fix **V-02**: Wrap all 12+ UI actions in `ChatController` with `runCatching` exception handling.
   - Fix **V-04**: Correct chronological index in `QuickCapture.pending` and `hasAssistantAfter`.
2. **Concurrency Hardening:**
   - Fix **V-03** & **V-06**: Guard `sendText`, `handleSendMessage`, and `drainQueuedTurns` with a coroutine `Mutex`.
   - Fix **V-07**: Replace all non-CAS `consumeProposal` calls with `consumeProposalCas`.
3. **Defensive Edge Case Hardening:**
   - Fix **V-05**: Implement day-key comparison to reset `sentToday` in `NudgeEngine`.
   - Fix **V-08**: Switch `DateResolver` normalization to `Locale.US`.
   - Fix **V-10** & **V-11**: Implement saturation arithmetic on all duration and timestamp subtractions.
   - Fix **V-12**: Explicitly re-throw `CancellationException` in `ExportController`.
   - Fix **V-13**: Make `extractReplyFromJsonStream` whitespace-tolerant.
