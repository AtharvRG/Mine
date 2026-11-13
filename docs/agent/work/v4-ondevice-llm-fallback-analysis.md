# Branching Analysis: On-Device LLM as Last Fallback Chain Entry (v4 / P-010 / Roadmap line 52)

## Goal and Invariant
- **Goal**: Implement `P-010` ("Optional on-device LLM as last fallback chain entry"). When remote AI endpoints are unavailable (network offline, 5xx server errors, rate limits HTTP 429, or cloud outages), the system falls through to an on-device LLM engine at the end of the provider chain rather than failing or abandoning the turn.
- **Invariants**:
  1. Pure Kotlin in `:core`: zero Android dependencies for the fallback engine, dispatch logic, and orchestrator interfaces.
  2. Bounded latency & zero hangs: on-device inference must respect coroutine cancellation and turn timeouts without blocking the main/UI thread.
  3. Seamless failover: if cloud providers stream partial text before failing, the failover rule (H-12 / R-16) must not corrupt the assistant turn or erase buffered text unless the failure occurred before any tokens were emitted.
  4. Offline-first resilience: `OfflineException` must NOT abort the turn if an on-device target is present in the fallback chain.
  5. Privacy & Zero-leak: on-device execution stays 100% local; prompt content never touches any socket.

---

## Hypothesis Table (min 4, different layers)

| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Special Local Target Scheme)** | Represent on-device LLM as a `Target` with URI scheme `local://ondevice` in the provider chain. | `ProviderOrchestrator` recognizes `target.id.startsWith("local://")` or delegates to an `OnDeviceLlmEngine` via transport. | Inspect `Target` and `ProviderOrchestrator.kt`. | If `Target` requires a public HTTP URL that fails parsing. | **CONFIRMED** (`Target.id` is a plain string; `ProviderConfigParser` already supports target IDs). |
| **H2 (OfflineException Handling)** | Currently, `ProviderOrchestrator` catches `OfflineException` and throws immediately to prevent testing unreachable remote endpoints. | When `target.id` is local or when chain has a local target remaining, `OfflineException` must continue the loop to the local target instead of rethrowing. | Inspect `ProviderOrchestrator.kt:49-54`. | If catching `OfflineException` breaks queuing for cloud-only chains. | **CONFIRMED** (only suppress rethrow if a local target remains in the tail of the chain). |
| **H3 (Dual-Engine Execution: Real Native LiteRT + Deterministic Local Fallback)** | On-device execution can call `LiteRtLm` if model file is downloaded, or fall back to a deterministic rule-based local intent extractor if weights are missing. | Works seamlessly offline even before the user downloads a 2 GB Gemma model file, guaranteeing the app is never dead. | Check `LiteRtEmbedder.kt` and `AmbientProposalExtractor.kt`. | If an on-device engine crashes when model file is not present. | **CONFIRMED** (graceful degradation: native runner if file exists, robust rule-based intent fallback if missing). |
| **H4 (Separate Fallback Coroutine / Thread)** | Run on-device inference directly on the calling coroutine without dispatcher isolation. | Heavy matrix multiplication or native JNI calls freeze the UI or audio thread. | Inspect `Dispatchers.IO` / `Dispatchers.Default` usage in `:app`. | UI jank or ANR during on-device execution. | **KILLED** (must explicitly execute on `Dispatchers.Default` with cooperative cancellation checks). |

---

## Angles (Mandatory)
1. **Data / Schema**: No SQLite schema change required. Setting `flag.ondevice_llm` ("0" default, "1" opt-in) and `model.ondevice.path` stored in existing `setting` table.
2. **Concurrency / Lifecycle**: Local inference runs inside `sendMutex.withLock` under the turn's coroutine job. UI `Stop` action cleanly cancels the flow and aborts native/local generation.
3. **Failure Paths**: If cloud providers fail (429, 500, timeout, offline), fallback executes. If on-device inference also fails (e.g. out of memory), catch block reports clear error message without crashing.
4. **User-Visible Behaviour**: User sees "Generating locally..." or seamless text delta streaming. In offline mode, the user can still create tasks, ask for agenda, and set reminders without internet.
5. **Privacy / Security**: Zero network egress for local turns. Keystore/SQLCipher security unaffected.
6. **Testability**: In `:core`, testable with `FakeOnDeviceLlmEngine` asserting failover from offline cloud to local target.
7. **Blast Radius**: Isolated to `ProviderOrchestrator.kt`, `ChatController.kt`, and `OnDeviceLlmEngine`. Cloud-only users experience identical existing behavior.
8. **Platform (Android 14, OEM)**: Native `.litertlm` execution requires ARM64 / NEON support; graceful fallback ensures compatibility across all Android devices (even low-end or x86 emulators).
9. **Model Behaviour**: Prompt formatting matches existing `ContextBuilder.fullSystemPrompt`, with structured JSON response `{ "reply": "...", "calls": [...] }`.

---

## Evidence Log
- `ProviderOrchestrator.kt:49-54`: Currently rethrows `OfflineException` unconditionally. Needs check: `if (hasLocalTarget(chain.subList(index + 1, chain.size))) continue else throw e`.
- `SettingsCatalog.kt`: Defines `provider.fallback.endpoint`, `provider.fallback.key`, `provider.fallback.rpm`. We can add `provider.ondevice.enabled` and `provider.ondevice.model_path`.
- `ChatController.kt:1330-1360`: Builds provider chain. If `storage.getSetting("provider.ondevice.enabled") == "1"` (or `flag.ondevice_llm == "1"`), appends `Target(id = "local://ondevice", key = "local", limitRpm = 120)`.

---

## Decision
- Implement `OnDeviceLlmEngine` interface in `core/src/main/java/com/anchor/core/provider/OnDeviceLlmEngine.kt`.
- Update `ProviderOrchestrator.kt` to:
  1. Accept optional `onDeviceEngine: OnDeviceLlmEngine? = null`.
  2. Skip throwing `OfflineException` if a local target (`target.id.startsWith("local://")`) remains in the fallback chain.
  3. Stream from `onDeviceEngine.generateStream(prompt)` when `target.id.startsWith("local://")`.
- Provide `RuleBasedLocalLlmEngine` in `:core` that can parse offline user commands ("remind me in 10 minutes to...", "tomorrow at 5pm call mom", "what is on my schedule today?") into proper JSON tool calls (`propose_change`, `search_memory`) and replies, ensuring full offline capability even without gigabytes of weights downloaded.
- In `:app`, provide `AndroidOnDeviceLlmEngine` bridging to `LiteRtLm` when model files are present, falling back to `RuleBasedLocalLlmEngine`.

---

## Pre-Mortem (3 Potential Bugs & Preventative Measures)
1. **Bug 1: Infinite retry loop or hang on local target failure.**
   - *Fix*: The loop iterates `for (target in chain)` strictly once per target; if the local target throws, the exception is caught and turn terminates cleanly.
2. **Bug 2: Coroutine cancellation during local inference leaves thread running.**
   - *Fix*: Wrap generation in `currentCoroutineContext().ensureActive()` at every token/chunk emission; never swallow `CancellationException`.
3. **Bug 3: Partial remote text mixed with local fallback response.**
   - *Fix*: If cloud target emitted text before failing, `emittedText = true` prevents emitting `StreamEvent.Clear`, ensuring the local completion appends cleanly rather than duplicating or clobbering.

---

## What Input Would Break the Fix
- An offline user asking a complex metaphysical question when no 2GB model weights are downloaded. The rule-based engine should reply politely with an informative offline notice rather than failing or returning blank.
