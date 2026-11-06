# HANDOFF (P1-D 20 Complete — Unit-Verified; Next: P1-E 21)

## Current Work & Status
- **Task #108 / P1-D 20 [COMPLETED]**: Home priority list (code-ranked), at-risk flag, active threads, recently-closed with undo validity, structured chat items (bubble, receipt, chips, nudge+reason, check-in snapshot, system line). Queries and core wiring only — **no UI** (per task scope; UI consumes these in a later roadmap item).
- Includes an in-scope bug fix: UPDATE op-type was missing from the UI-visible event filters and ReceiptFormatter, making P1-B 16 UPDATE receipts invisible (D-115 regression).
- Decision **D-121** recorded in docs/agent/DECISIONS.md (next free: D-122).
- Core suite: 242/242 passed (231 prior + 11 new), 0 compiler warnings. App unit suite: 9/9 passed.
- Independent verification verdict: **PASS** — clean re-run of both suites (exit 0, 242/0/0 core + 9/0/0 app from XML), `:app:compileDebugSources` exit 0, implementation matches D-121 in every claimed spot, and an 18-assertion adversarial probe harness (compiled against core classes) confirmed: strict-24h boundary on recently-closed, newest-first sort, undo-validity flip true->false after a real Gateway undo (node exits getRecentlyClosed, status restored), second-undo idempotency ("Already undone"), orphan token ("Undo token not found"), and the 24h gateway gate. Non-blocking observations: benign fake/SQL parity drift on future-dated `closedAt` (fake includes, SQL excludes via `closed_at <= now` — only reachable via clock skew; inherited from getRankedCandidates precedent) and a same-tsUtc tie-break difference in `getLatestEventForTarget` (unobservable through the controller path).

## 1. What Changed (all uncommitted in working tree)
- `core/src/main/java/com/anchor/core/storage/Storage.kt`:
  - Three new interface queries: `getHomePriorityList(now, limit=5)`, `getRecentlyClosed(now, limit=10)`, `getLatestEventForTarget(targetType, targetId)`.
  - New top-level `rankHomeNodes(nodes, now)`: code-side Home ranking. Order: 1) overdue first; 2) in_progress; 3) pinned; 4) severity desc; 5) priority desc; 6) due soonest (undated last via `Long.MAX_VALUE`); 7) most recently updated; 8) id tiebreak.
- `core/src/main/java/com/anchor/core/ui/ChatItems.kt` (new):
  - `ChatItem` sealed interface: `DayHeader(dayKey, tsUtc)`, `TimeDivider` (reserved, not yet emitted), `Message(id, role, content, tsUtc)`, `SystemEvent(text, tsUtc)`, `ReceiptItem(receipt)`, `ProposalRow(proposal)`, `CheckinCard(title, summary, tsUtc)` (reserved for P1-E 25), `Nudge(content, tag, reason, tsUtc)` (reserved for P1-E 24), `StatusNotice(text, isError, tsUtc)`.
  - `RecentlyClosed(node, undoValid, undoToken)`, `ActiveThread(msgId, openedAt)`.
- `core/src/main/java/com/anchor/core/ui/ChatTimelineBuilder.kt` (new):
  - `build(messages, receipts, openProposals)`: DayHeader inserted on dayKey change; system role with non-blank content -> SystemEvent; other roles -> Message; receipts anchored directly under their causeMsgId message; orphaned receipts (null cause or cause outside window) appended at end; open proposals -> ProposalRow at end.
- `core/src/main/java/com/anchor/core/ui/ChatContract.kt`:
  - UiState += `atRisk: Boolean`, `activeThread: ActiveThread?`, `homePriorityList: List<Node>`, `recentlyClosed: List<RecentlyClosed>`, `chatItems: List<ChatItem>` (toString updated).
- `core/src/main/java/com/anchor/core/ui/ChatController.kt`:
  - `init` + `refreshUI`: wire `getHomePriorityList`, `getRecentlyClosed` + per-node undo validity (`getLatestEventForTarget("node", id)` + `hasUndo`, D-115 formula `(now - tsUtc < 24h) && !hasUndo`), `atRisk` from ReliabilityState, `activeThread` from ProactiveState, and rebuild `chatItems` via ChatTimelineBuilder on every state update.
  - Warning fix: `event!!.opId` -> smart-cast `event.opId` (2 sites).
- `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`:
  - Implements the three queries (freshness filter mirrors D-104: `dueAt > now` OR recency within 30 days OR zero timestamps; recently-closed = done/cancelled + closedAt within 24h; latest event by max tsUtc). Calls shared `rankHomeNodes`.
  - UPDATE added to the `getRecentEvents` SQL op-type IN list (UI-visible fix).
- `core/src/test/java/com/anchor/core/SharedFakeStorage.kt`:
  - Implements the three queries with SQLite parity; `import com.anchor.core.storage.rankHomeNodes` added (top-level fn lives in another package).
  - UPDATE added to `uiVisibleOps` (UI-visible fix).
- `core/src/main/java/com/anchor/core/ui/ReceiptFormatter.kt`:
  - UPDATE branch added: "Updated: <new title> (<due time>)" from after-snapshot.
- `core/src/test/java/com/anchor/core/ui/P1D20HomeAndTimelineTest.kt` (new): 11 tests.
- Docs: `docs/ui-contract.md` (UiState fields; §1b ChatItem taxonomy + composition rules; §1c Home data ranking/undo-validity/activeThread; Home Queries line incl. UPDATE visibility), `docs/agent/DECISIONS.md` (D-121), `docs/agent/TASKS.md` (P1-D 20 -> [x]).

## 2. What Is Verified (command + result)
- `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL (exit 0) — **242/242 core** (incl. P1D20HomeAndTimelineTest 11/11), **9/9 app** (counts from build/test-results XML), 0 compiler warnings.
- First run was green but emitted 2 "Unnecessary non-null assertion" warnings (`event!!.opId` in ChatController init/refreshUI); fixed via smart-cast and re-run: exit 0, 0 warnings.
- New tests cover: ranking order (overdue -> in_progress -> pinned -> severity -> priority -> dueAt; pinned/severity rank above plain due-sooner items), exclusion of closed/stale/localOnly + limit cap, recently-closed 24h window + undo validity + invalidation after undo, `getRecentEvents` includes UPDATE, ReceiptFormatter UPDATE branch, full timeline composition (day headers, receipt anchoring under causeMsgId, orphan receipts at end, proposal row), empty inputs, undated nodes last, priority breaks severity ties.

## 3. NOT Verified
- Connected/instrumented tests NOT run this session (device not invoked). The three new Storage queries have no real-SQLite instrumented coverage yet (SharedFakeStorage only). Last connected run: 53/53 (P1-B 15 session).
- No independent code-review (reviewer agent) pass for P1-D 20 yet; the verification agent PASS covered builds, tests, implementation spot-checks, and adversarial probes but is not a style/design review.
- No live-model smoke; UI does not yet consume `chatItems`/`homePriorityList`/`recentlyClosed`/`activeThread` (UI implementation is a later roadmap item).

## 4. Current Failing Tests
- None (242 core + 9 app green).

## 5. Open Decisions Needing User
- None new. D-121 recorded; next free D-122.

## 6. Exact Next Step
- `git commit -s` the P1-D 20 changes (one commit), then stop and await user approval. Next roadmap task after approval: **P1-E 21** (day_rule/day_key monotonic, rollover; Blueprint 11, 50 s3.13) per docs/agent/TASKS.md line 74.

## 7. Traps for the Next Run
- **Ranking contract (D-121)**: pinned/severity/priority rank ABOVE due-soonness among non-overdue items. `testHomePriorityList_PinnedAndSeverityRankAbovePlainActive` pins this exact order — do not "fix" the comparator back to dueAt-first.
- `rankHomeNodes` is a top-level function in `com.anchor.core.storage` (Storage.kt). SharedFakeStorage (package `com.anchor.core`) needs `import com.anchor.core.storage.rankHomeNodes` — removing it breaks compilation.
- The UI-visible op filter lives in TWO places: `uiVisibleOps` in SharedFakeStorage AND the SQL IN list in AndroidStorage.getRecentEvents. Any new ReceiptFormatter op branch must be added to both.
- Orphan receipts (null causeMsgId or cause outside the loaded message window) render at the END of the timeline, not dropped; `ProposalRow.tsUtc = 0L` (proposals are terminal items, not timestamp-ordered); `TimeDivider` exists but is never emitted yet.
- `CheckinCard` and `Nudge` are reserved for P1-E 25 / P1-E 24 — the timeline builder does not produce them yet.
- Test-helper freshness: nodes created with `createdAt = now` always pass the 30-day freshness filter; set `updatedAt`/`createdAt` in the past to test stale exclusion.
- Undo validity formula is shared with receipts (D-115): `(now - event.tsUtc < 24h) && !storage.hasUndo(event.opId)`; recently-closed uses the LATEST closing event per node via `getLatestEventForTarget`.

## Operating Rules
- Commit per task using `git commit -s`. Never commit failing tests. One task per run; stop and await user approval before proceeding to the next task.
