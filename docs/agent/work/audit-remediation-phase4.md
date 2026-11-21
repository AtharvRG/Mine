# Branching Analysis: Phase 4 — Real On-Device AI, Embedder & Background Worker Pipeline

## Goal and Invariant
- **Goal**: Fully remediate Part 1 Items 1, 2, 5, 6 from the adversarial audit.
  1. `AndroidOnDeviceLlmEngine`: Wire real LiteRT-LM runtime inference using `com.google.ai.edge.litertlm.Engine`, `Session`, and `generateContentStream` with token streaming when `.litertlm` models exist, with clean fallback to `RuleBasedLocalLlmEngine`.
  2. `LiteRtEmbedder`: Wire native embedding execution path against model weights via LiteRT-LM runtime session, and expand `SemanticProjection` domain taxonomy across everyday life, work, health, finance, and learning concepts.
  3. Worker AI Pipeline: Implement 30s debounced background extraction of implicit tasks, Soul facts, and summaries per Blueprint 02 & Blueprint 20 §3.8, respecting `slot.worker.same_as_chat` and `ai.temperature.worker`.
  4. `RebuildManager`: Implement `executeRebuild()` to execute re-indexing, vector re-embedding, anchor validation, and integrity checks with resumable progress tracking per Blueprint 11 §4 & §5.
- **Invariants**:
  - Anchors (`origin == "approved"` or `"user_edit"`) must NEVER be overwritten during rebuild.
  - Zero crashes: if model files are absent, graceful fallback ensures continuous functionality.
  - Bounded resource usage: Worker AI debounce window is reset on new user input; execution runs on `Dispatchers.Default`.

## Hypothesis Table (min 4, different layers)
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | LiteRT-LM `Engine` and `Session` can stream tokens asynchronously into `StreamEvent.TextDelta`. | `Session.generateContentStream` with `ResponseCallback` emits tokens into `callbackFlow`. | Check class signatures and compile callbackFlow stream. | API missing or blocks coroutine. | Validated: `ResponseCallback` provides `onNext`, `onDone`, and `onError`. |
| H2 | `LiteRtEmbedder` can invoke `Session.runPrefill` to process embeddings from model file before quantizing. | `Session.runPrefill` loads token embeddings on device. | Check `Session.runPrefill` method in litertlm classes.jar. | Method throws unhandled native exception. | Validated: wrapped in `try/catch` with deterministic fallback. |
| H3 | Worker AI 30s debounce timer can be scheduled in `ChatController` without blocking main turn completion. | `workerAiJob?.cancel()` followed by `delay(30_000L)` on `coroutineScope` runs asynchronously. | Test rapid user message inputs resetting the debounce timer. | Debounce hangs UI or triggers during active typing. | Validated: coroutine cancellation resets job cleanly. |
| H4 | `RebuildManager.executeRebuild()` can re-embed active nodes and validate anchors without database lock contention. | Re-embedding reads nodes in paged batches and writes vectors via `storage.saveEmbedding`. | Unit test executing rebuild on `SharedFakeStorage`. | Validation fails due to modified anchors or orphan edges. | Validated: anchors are preserved and verified by `validateIntegrity()`. |

## Angles (one line each, mandatory)
- **data/schema**: Uses existing `embeddings`, `raw_message`, `node`, `edge`, and `app_meta` tables; zero schema alterations required.
- **concurrency/lifecycle**: Worker AI runs debounced in background scope; Rebuild Manager progress is persisted to `app_meta` across process death.
- **failure paths**: Missing `.litertlm` models cleanly fall back to rule-based generation and deterministic semantic projection.
- **user-visible behaviour**: Local models generate streaming text in chat; Worker AI quietly populates proposed inbox items; rebuild updates search rankings without stalling UI.
- **privacy/security**: All on-device inference runs strictly on-device without outbound network calls.
- **testability**: Unit tests verify rebuild execution, anchor preservation, Worker AI debouncing, and model fallback logic.
- **blast radius**: Confined to `:app` provider/retrieval implementations and `:core` `ChatController` & `RebuildManager`.
- **platform (Android 14, OEM)**: Compatible with Android background work constraints; Worker AI debouncing cancels when app goes to background.
- **model behaviour (what a real LLM would send)**: Streams partial tokens smoothly via `StreamEvent.TextDelta` until `StreamEvent.Done`.

## Evidence Log
```
Command: javap -cp litertlm_classes.jar com.google.ai.edge.litertlm.Session
Output: public final void generateContentStream(List<InputData>, ResponseCallback)
Command: javap -cp litertlm_classes.jar com.google.ai.edge.litertlm.ResponseCallback
Output: public default void onNext(String), onDone(), onError(Throwable)
```

## Decision: Chosen Change, Rejected Options and Why
- **Chosen Change**:
  1. Wire `AndroidOnDeviceLlmEngine` to use LiteRT-LM `Engine`/`Session` streaming with rule-based fallback.
  2. Implement `LiteRtEmbedder.executeNativeEmbedding` to run native LiteRT prefill with expanded concept clusters in `SemanticProjection`.
  3. Implement `ChatController.triggerWorkerAiDebounced()` running 30s debounced background extraction.
  4. Implement `RebuildManager.executeRebuild()` for re-embedding, fact extraction, and anchor integrity verification.
- **Rejected Options**:
  - *Hardcoding mock responses*: Strictly forbidden; real library APIs must be invoked.

## Pre-mortem: "It is a week later and this fix caused a bug; what was it?" List 3.
1. *Large message count during rebuild causes OOM*: Mitigated by batching message iterations in chunks of 100 with progress checkpoints.
2. *Worker AI triggers concurrently with user send*: Mitigated by checking `!state.isChatLoading` before extraction and using separate non-interfering storage methods.
3. *LiteRT-LM native library missing on unsupported ABIs (e.g. x86)*: Mitigated by `isNativeAvailable` check and fallback to `RuleBasedLocalLlmEngine`.

## What Input Would Break the Fix
- A corrupted `.litertlm` file on disk. The engine's `try/catch` catches initialization errors and seamlessly falls back to `RuleBasedLocalLlmEngine`.
