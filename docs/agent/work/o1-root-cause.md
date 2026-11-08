# O-1 root cause: "plausible confirmation, no receipt, no reminder, no alarm"

Status: analysis only, no source edited. All line numbers verified against the working tree
(branch `master`, after commit `eb63e21`).

---

## 1. Symptom

On a minified RELEASE build (no `run-as`, no logcat) the assistant answers with a fluent,
correct-looking sentence but **nothing happens**:

| Utterance | Observed |
|---|---|
| `remind me to stretch in 2 minutes` | WORKS: receipt `Created: Stretch ...`, `AlarmReceiver` armed |
| `set alarm for drink water in 3 minutes` | sentence only |
| `set alarm to buy milk in 3 minutes` | sentence only |
| `read for 20 minutes` | sentence only |
| `set alarm for water at 4pm` | sentence only |
| `gentle reminder to test the banner in 20 minutes` | sentence only |

Observable signature for the failing set: **no "Active Reminders" row**, **no receipt**, **no armed
alarm**, **assistant message persists after app restart** (it was written to `raw_message`), and
**no error receipt** (so `ToolResult.Error` was not produced).

Notably `set alarm to buy milk` is textually almost identical to the *prompt's own* CREATE example
(`ChatController.kt:581`). So this is not a model-comprehension failure of an exotic phrasing.

## 2. Hypotheses

Six genuinely different layers. Each entry states its prediction, the cheapest check, and the kill
condition.

| # | Hypothesis (layer) | If true, then visible at | Cheapest check | Kill condition |
|---|---|---|---|---|
| **H1** | **Parse failure (serialization layer).** `EnvelopeParser.parse` is all-or-nothing; any exception yields `null`, and `ChatController` then stores the raw stream as a normal reply. | A `catch` fires inside `EnvelopeParser.kt:39` for shapes `isLenient` cannot repair; `ChatController.kt:500-506` runs. | Read the parser + decoder source for which exceptions `isLenient` does *not* suppress. Replay a malformed envelope through `EnvelopeParser.parse`. | Parser returns non-null for every shape the model plausibly emits. |
| **H2** | **Prompt contradiction (model-behaviour layer).** The spec says arg `hint`; every example uses `"title"`; line 572 teaches that `set an alarm for X` has *no* subject (so the model replies "For what?" and makes **no** call). | `ChatController.kt:572` vs `:581-589`; `Gateway.kt:123`. | Read the prompt block verbatim; compare spec vs examples; check `Gateway` arg names. | Gateway accepts arbitrary synonyms, or the model never follows the negative example. |
| **H3** | **Duplicate-title rejection (logic layer).** `findActiveNodesByTitle` finds "Drink water"/"Milk" from context and refuses to create. | `Gateway.kt:242-281`. | Read the duplicate branch and `isNearMatch`. | It returns `Error`, or nothing. |
| **H4** | **Error swallowed (UI layer).** `ToolResult.Error` is dropped before reaching receipts. | `ChatController.kt:436-448`. | Read the `when` arms. | Error reaches `turnReceipts`. |
| **H5** | **Release-only bug (platform layer).** R8/minification breaks kotlinx-serialization reflection or the SSE normalizer. | `app/proguard-rules.pro`. | Read keep rules; check `isMinifyEnabled`. | `com.anchor.core.**` (and annotations) are kept wholesale. |
| **H6** | **Local, not chat (scope layer).** The utterance is intercepted before ever reaching the LLM, or the LLM is never called. | `MainViewModel` / `MainActivity` command routing. | Grep for local intent shorthand; check the stream. | No local regex router exists; the model clearly ran (a sentence exists). |

## 3. Evidence pass

### H1 - parse failure

`core/src/main/java/com/anchor/core/gateway/EnvelopeParser.kt:29-42`
```kotlin
fun parse(rawOutput: String): ToolEnvelope? {
    val clean = rawOutput.replace("```json", "").replace("```", "").trim()
    val start = clean.indexOf("{")
    val end = clean.lastIndexOf("}")
    if (start == -1 || end == -1) return null
    return try {
        val parsed = json.decodeFromString<ToolEnvelope>(clean.substring(start, end + 1))
        if (parsed.reply.isBlank() && parsed.calls.isEmpty()) null else parsed
    } catch (_: Exception) { null }          // <- zero diagnostics
}
```
`Json { ignoreUnknownKeys = true; isLenient = true }` (line 27).

Decoder source (kotlinx-serialization-json **1.7.3**, extracted to `%TEMP%\kxs_o1`), showing what
`isLenient` cannot repair:
- `commonMain/.../StreamingJsonDecoder.kt:197` - `decodeMapIndex` does `lexer.consumeNextToken(COLON)`;
- `commonMain/.../StreamingJsonDecoder.kt:228` - `decodeObjectIndex` likewise requires a colon;
- `commonMain/.../lexer/StringJsonLexer.kt:82-106` - an object key **must** be a STRING token;
- `AbstractJsonLexer.kt:26` - `internal const val COLON = ':'`.

`isLenient` relaxes *unquoted strings* and *quoted keys*, nothing else. It does **not** accept a
missing `:`, a stray `,`, an unescaped `"` inside `reply`, or JSON stuffed into a JSON-encoded
string. `ToolCall.args` is `Map<String, JsonElement>` (`EnvelopeParser.kt:23`), so `args` walks the
*map* decoder path - exactly the one that hard-requires COLON.

```
// fails: missing colon before due_text
{"reply":"Setting a reminder.","calls":[{"tool":"propose_change","args":{"intent":"CREATE","title":"Drink water" "due_text":"in 3 minutes"}}]}
// fails: unescaped quote in reply
{"reply":"Setting a reminder for "drink water".","calls":[...]}
// fails: greedy lastIndexOf("}") when the model appends prose after the object
{"reply":"...","calls":[...]} Let me know if you need anything else!
```
All three throw inside `decodeFromString` -> caught at line 39 -> `parse` returns `null`.

Downstream, `core/src/main/java/com/anchor/core/ui/ChatController.kt:403-404, 500-506`
```kotlin
val rawBot = botResponse.toString()
val envelope = EnvelopeParser.parse(rawBot)
...
} else {
    val finalTxt = rawBot.ifBlank { "..." }
    updateLastMessage(botId, finalTxt)
    storage.insertRawMessage(botId, "assistant", finalTxt)
    currentBotId = null
    turnComplete = true
}
```
**No receipt, no error, no log, no retry.** The raw stream is persisted as if it were a legitimate
chat reply.

But this alone would be visible on screen: `extractReplyFromJsonStream`
(`ChatController.kt:676-699`) streams the `"reply":"..."` slice into the bubble *before* the parse
fails, so with a fully malformed object the user would see either the JSON tail or nothing. The
on-device report is a *clean* sentence. So H1 must be narrowed: which malformation leaves the
streamed `reply` intact **and** the final text clean?

Answer: **a body the parser never sees.** `extractReplyFromJsonStream` copies the raw stream when the
envelope does not start with `{` (line 681-684). And a *second model turn* within the same user
message (loop `ChatController.kt:374-507`, `loops < 4`) has `pack` extended with
`System feedback: <data>Tool Result...</data>` (line 483-485) and `buildJsonPayload` passes it as a
`user` message. So any shape that survives one round can be re-emitted differently on the next -
including as **plain prose**. That path lands in the same `else` branch at 500-506.

**Verdict: CONFIRMED mechanism, but it is a *silent-loss amplifier*, not yet the sole
differentiator** - nothing on this path prefers `drink water` over `stretch`. That distinction is
H2's job (see section 5).

### H2 - prompt contradiction

`ChatController.kt:557` (spec):
> `propose_change (intent: CREATE|CANCEL|COMPLETE|RESCHEDULE|UPDATE, **hint**: target text, due_text: ...)`

`ChatController.kt:581-589` (examples) - CREATE uses `"title"`, not `"hint"`:
```kotlin
581: {"reply":"Setting a reminder to buy milk.","calls":[{"tool":"propose_change","args":{"intent":"CREATE", "title":"Buy milk", "due_text": "tomorrow at 4pm"}}]}
588: INDIC_DIGITS: ... {"intent":"CREATE", "title":"Take medicine", "due_text":"in 2 mins"}
589: GENTLE:       ... {"intent":"CREATE", "title":"Gentle stretch", "due_text":"in 30 mins", "tier":"gentle"}
```
`ChatController.kt:571-573`:
```
* When a subject IS present: ... Call propose_change CREATE with title="<subject>" and due_text.
* A subject is MISSING ONLY when the message contains NO action, task, or purpose at all
  (e.g., ONLY "cue me after 2 mins", ONLY "notify me in 2 mins", ONLY..., ONLY "set an alarm for 7am").
* When the subject is MISSING: Make NO tool call. Reply with one short question:
  {"reply":"For what?","calls":[]}.
```
`Gateway.kt:123`:
```kotlin
val hint = (args["hint"] ?: args["title"] ?: "").take(200)
```
The gateway accepts **only** `hint`/`title`; there is no synonym folding (contrast `schedule_reminder`,
which accepts `target_ref`/`target`, `Gateway.kt:96-98` - so the codebase *knows* the pattern).

Prediction check against the failing set:
- `set alarm for drink water in 3 minutes` - starts with the exact phrase declared **subject-less**
  at line 572 (`set an alarm for 7am`). Model sees `set alarm for ...`, may collapse to the negative
  example and emit `{"reply":"For what?","calls":[]}` -> no receipt, no row, no alarm. **Matches.**
- `set alarm to buy milk in 3 minutes` - "to buy milk" is a subject per 568-569, but the phrase
  `set alarm to` is not in the taught vocabulary (only `remind me to`, `notify me in ... for`).
- `read for 20 minutes` - "read" is an action; but the model may read `for 20 minutes` as the whole
  message and answer conversationally.
- `gentle reminder to test the banner in 20 minutes` - `gentle reminder` is not in the trigger list;
  the model may just say "OK".
- `set alarm for water at 4pm` - line 572's negative example uses a *time without subject*, and
  `for water` looks like a prepositional phrase rather than a task. Per `HANDOFF.md:55` this one
  also "rewrote the title to 'drink water' (prior-context bleed)" - i.e. the model *did* call the
  tool (with `hint`/`title` from prior context), yet nothing was created.

The duplicate check is the only CREATE-side gate that swallows silently. Which brings us to H3.

### H3 - duplicate title

`Gateway.kt:242-281`. If `!isAutoTitled`, it calls `storage.findActiveNodesByTitle(title)`
(`AndroidStorage.kt:216-220`: `TRIM(title) = ? COLLATE NOCASE`, active/in_progress/paused only) and
`isNearMatch(existing.dueAt, targetTimeMillis)` (2 h window; returns `true` when either is null).
On duplicate it **saves a `NeedsConfirm` proposal and returns `ToolResult.NeedsConfirm` (line 279)**,
not an `Error`.
`ChatController.kt:423-426` then sets `turnComplete = true` and writes *no receipt*, but
`refreshUI()` shows `openProposals` as chip rows at the end of the timeline
(`ChatTimelineBuilder.kt:44-45`). The user reported **no chips**.
-> **For "drink water"/"milk" (new titles) the duplicate branch cannot fire.** And even if it did, the
observable would be a chip row, not silence.

**Verdict: RULED OUT as the cause of these five failures.**

### H4 - Error swallowed

`ChatController.kt:436-448` builds `turnReceipts.add(ReceiptState(..., isError = true, causeMsgId = userMsgId))`
and line 487 ends the turn. Receipts are flushed at line 455 then `refreshUI()`. A CREATE with a blank
title returns `ToolResult.Error("Missing title: ask the user what it is for.")` (`Gateway.kt:137-144`)
and would surface as a **red receipt**. The user sees none.
**Verdict: RULED OUT** for the no-receipt signature. (It is still a defect that *any* tool error
aborts the remaining calls in the envelope - see section 7.)

### H5 - release-only

`app/proguard-rules.pro:9-10` keeps `ToolEnvelope`/`ToolCall`; `:19-20` keep **all** of
`com.anchor.core.**`; `:7` keeps annotations. `app/build.gradle.kts:28` `isMinifyEnabled = true` is
therefore behaviour-neutral for this path.
**Verdict: RULED OUT.** (This also means the bug **should reproduce in debug** - important for the
repro in section 6.)

### H6 - local routing

No utterance-to-command router exists; the failing responses are free-form sentences written to
`raw_message` by `ChatController.kt:475`/`503`, so the LLM path definitely ran.
**Verdict: RULED OUT.**

## 4. Eliminate

| Hypothesis | Verdict | Decisive evidence |
|---|---|---|
| H1 parse failure is silently downgraded to chat | **CONFIRMED (mechanism)** | `EnvelopeParser.kt:39`; `ChatController.kt:500-506` |
| H1b malformation alone explains which utterances fail | **UNKNOWN / insufficient** | Parser has no input preference between `stretch` and `drink water` |
| H2 prompt contradiction | **LIKELY (primary differentiator)** | `ChatController.kt:557` vs `:581-589`; `:572`; `Gateway.kt:123` |
| H3 duplicate title | **RULED OUT** | `Gateway.kt:279` + `ChatTimelineBuilder.kt:44-45` (chips, not silence) |
| H4 Error swallowed | **RULED OUT** | `ChatController.kt:436-448` (error receipt) |
| H5 R8/minify | **RULED OUT** | `proguard-rules.pro:7,9-10,19-20` |
| H6 local routing | **RULED OUT** | free-form text persisted by `ChatController` |

## 5. Root cause

**Single most probable root cause: the system prompt's negative example at
`core/src/main/java/com/anchor/core/ui/ChatController.kt:572` (`ONLY "set an alarm for 7am"` is
taught as SUBJECT-LESS), combined with the `hint`/`title` mismatch between the spec (`:557`) and the
examples (`:581-589`), causes the model to emit either `{"reply":"For what?","calls":[]}` or a
prose-only answer for exactly the phrasings that begin `set alarm for/to ...` and for the
`gentle reminder ...` / `read for ...` shapes; `EnvelopeParser.parse` (`EnvelopeParser.kt:29-42`) then
turns any imperfect JSON into `null`, and `ChatController.kt:500-506` persists that text as a normal
reply with no receipt and no retry, making the failure completely silent.**

Confidence: **high (~80%)** that the *silence* is `EnvelopeParser` + `ChatController`; **medium
(~55%)** that the *utterance specificity* is line 572 + the examples. What would change my mind:
- a debug-build replay of these five utterances printing the raw stream - if the stream is valid JSON
  with `"calls":[]`, the cause is 100% prompt and the parser is innocent;
- if the stream is valid JSON with a `propose_change` CREATE, then the cause is in `Gateway` after
  line 123 (`DateResolver` returning `PastTime`, or `NeedsClarification` with no visible chip).

## 6. Minimal reproduction (fails today)

Add `core/src/test/java/com/anchor/core/ui/O1SilentLossTest.kt`, modelled on
`TurnCompletionTest.kt:84-114` (same `SharedFakeStorage` + `HttpTransport` harness):

```kotlin
@Test fun testUnparseableEnvelopeMustNotBePersistedAsAChatReply() = runBlocking {
    val storage = SharedFakeStorage(); storage.settings["key"] = "k"
    val clock = FakeClock(1_700_000_000_000L)
    val fakeTransport = object : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            // exactly what a model emits when it glues two args without a colon
            emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Setting a reminder for drink water.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Drink water\" \"due_text\":\"in 3 minutes\"}}]}"}}]}""")
            emit("data: [DONE]")
        }
    }
    val dr = DateResolver(clock)
    val ctx = ContextBuilder(storage, clock, dr, CheckinBuilder(storage, clock, dr))
    val controller = ChatController(storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), ctx,
        Gateway(storage, Resolver(), dr, clock),
        object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {} },
        TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock)

    controller.onAction(UiAction.UpdateDraft("set alarm for drink water in 3 minutes"))
    controller.onAction(UiAction.SendMessage)

    val last = storage.messages.last().content!!
    assertFalse("Raw envelope leaked into the transcript", last.contains("\"calls\""))   // FAILS today
    assertTrue("Parse failure must be surfaced", controller.state.value.receipts.any { it.isError }) // FAILS today
}

@Test fun testSetAlarmForPhraseIsNotTreatedAsSubjectless() {
    // the prompt itself; guards the H2 regression at ChatController.kt:572
    // inspect buildJsonPayload's instruction via the fake transport's `body` param and assert
    // that "set an alarm for 7am" is not listed as a MISSING-subject example
}

@Test fun testH1ParserMessageIsNotSwallowed() {
    // EnvelopeParser is an object; a future fix that returns a partial result must keep this green
    assertNull(EnvelopeParser.parse("""{"reply":"x","calls":[{"tool":"propose_change","args":{"intent":"CREATE" "title":"y"}}]}"""))
}
```
The first two assertions fail on `master` today; the third documents current behaviour and pins the
contract the fix must change deliberately.

**Note:** because of H5 (whole-module keep rules), this bug will also reproduce on a *debug* install,
so the first diagnostic step is to replay the five utterances on a debug APK with the raw stream
logged from `EnvelopeParser.parse`'s catch block. That single log line settles H1 vs H2 in one run.

## 7. Secondary defects in the same path (fix regardless)

1. **`catch (_: Exception) { null }`** - `EnvelopeParser.kt:39`. No cause, no raw length, no offset. A
   one-line `Log.e` (or returning a `ParseResult.Failed(reason)`) would have made O-1 a 5-minute fix.
2. **Raw model output persisted as a chat message** - `ChatController.kt:500-506`. Unparseable output
   must produce a visible error receipt, not a silent normal reply.
3. **Any `ToolResult.Error` aborts the remaining calls** - `ChatController.kt:487`. In one envelope a
   good CREATE plus one bad call loses the CREATE. (`Committed` also ends the turn, which is
   correct; `Error` is not.)
4. **Silent `.take(200)` / `.take(100)`** - `Gateway.kt:123-124`. Long titles/times are truncated with
   no receipt; a truncated `due_text` can drop `in 3 minutes` -> node created with `dueAt = null`, and
   `AlarmReconciler` only arms rows whose `fireAtMillis` is set (`AlarmReconciler.kt:44`).
5. **`reconcileAlarms` drops reminders older than 60 s** - `AlarmReconciler.kt:44`. A reminder whose
   fire time passed while the app was closed is never armed and never reported.
6. **`Gateway.kt:30-33` dedupe key** uses `args.toString()` on the *resolved* map, so two calls that
   differ only by a dropped `null` arg are treated as duplicates.
7. **`getActiveReminders` clock source** - `SharedFakeStorage.kt:190` uses `clock.now()`, while the
   production `AndroidStorage` uses wall-clock time; tests and reality diverge on reminder visibility.

**Collateral:** items 1-2 share one mechanism (all-or-nothing parse + silent fallback) with the
streaming preview `extractReplyFromJsonStream` (`ChatController.kt:676-699`), which is itself a
hardcoded `"reply":"` scan - it breaks on pretty-printed JSON (`"reply": "x"`, space after the colon),
so a model that pretty-prints streams *nothing* while it types. Fixing item 2 by showing an error
receipt will, on pretty-printed output, replace "silent success" with "error on every turn" unless
the streaming marker is made whitespace-tolerant in the same change.

## 8. Fix options (smallest first)

1. **Add a diagnostic + conservative sanitizer (~30 LOC, `EnvelopeParser`).** Before
   `decodeFromString`, log the exception class plus a 200-char window around the failure; optionally
   attempt one repair pass (insert a missing `:` between `"key" "value"`). Then in
   `ChatController.kt:500-506` emit an error receipt instead of persisting. Trade-off: repair can mask
   genuine model errors; keep it narrow and fail loudly afterwards.
2. **Close the prompt contradiction (small edit, `ChatController.kt:557` and `:572`).** Change the
   spec to `title` (matching every example) and delete or reword the `set an alarm for 7am` example so
   the prefix is no longer taught as subject-less. Trade-off: none functionally; risks weakening the
   "For what?" flow for genuinely subject-less alarms - cover it with the tests from section 6.
3. **Make parsing result-typed (larger, `EnvelopeParser` + `ChatController`).** Return
   `EnvelopeResult.Ok | Empty | Malformed(raw, cause)`; treat `Malformed` as a *retryable* protocol
   error: append a `System feedback: your previous reply was not valid envelope JSON; re-emit it`
   turn **once**. Trade-off: one extra LLM call on failure; best long-term fix, and the only one that
   recovers rather than merely reports.

The test that would have caught this: the first two assertions in section 6 - they turn "no receipt
but a sentence" into a red build.
