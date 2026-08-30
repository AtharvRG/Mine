# Mine — Backend Robustness Issues Checklist

> Source: Backend audit performed on 2026-08-31 across all 42 Kotlin files (AI agents, data/DB, network, services/workers, DI/utils/ViewModels) cross-referenced against the 17 design docs.
>
> **How to use:** Work top-to-bottom by severity. When an item is addressed, tick the box `[x]` and fill the **Checked** column with the date. Add the commit/PR reference in the **Ref** column.
>
> Legend: `[ ]` open · `[x]` done · `[~]` in progress · `[!]` wontfix / deferred

---

## Audit Metadata

| Field | Value |
|-------|-------|
| Audit date | 2026-08-31 |
| Auditor | GitHub Copilot (automated) |
| Codebase audited | `d:\CnP\Mine` |
| Docs referenced | `D:\CnP\Mine1\Docs` (01–17) |
| Files audited | 42 Kotlin source files |
| Total findings | 18 critical/high + 30 medium/low + dependency health |
| Status | Audit only — nothing was modified |

---

## Tier 1 — Critical (data loss / security / throughput)

These are load-bearing defects. Fix before any further feature work.

| # | Done | Finding | File:Line | Checked | Ref |
|---|------|---------|-----------|---------|-----|
| C1 | [ ] | BuildConfig API keys ship in the APK and are trivially extractable (jadx/strings). Move to user-entered keys in EncryptedSharedPreferences. | `app/build.gradle.kts:42-44` | | |
| C2 | [ ] | Hardcoded SQLCipher DB password `"debug_super_secret_password"`. Same key for every install; defeats encryption. Derive from Keystore-backed master key. | `DatabaseModule.kt:25` | | |
| C3 | [ ] | `fallbackToDestructiveMigration()` + `exportSchema=false` → any schema bump silently wipes the entire encrypted DB. Add explicit `Migration` objects; set `exportSchema=true`. | `MineDatabase.kt:17`, `DatabaseModule.kt:33` | | |
| C4 | [ ] | `PRAGMA foreign_keys = ON` never set → every `ON DELETE CASCADE` in `GraphEntities.kt` is a no-op. Orphaned edges + embeddings accumulate. Add `RoomDatabase.Callback.onOpen`. | `DatabaseModule.kt` | | |
| C5 | [ ] | `RateLimiter` holds the `Mutex` across `delay()` → serializes all 3 API keys to ~39 RPM global, defeating the 120-RPM design. Move `delay()` outside the lock; use per-key locks. | `RateLimiter.kt:25-42` | | |
| C6 | [ ] | Embeddings generated OUTSIDE the `ToolExecutor` transaction → unembedded nodes silently lost to all future retrieval. Move inside transaction or add `unembedded` flag + backfill worker. | `ToolExecutor.kt:73-99` | | |
| C7 | [ ] | Embedding response order assumed = request order → wrong-node embeddings on order mismatch. Match by explicit index/id field, not array position. | `ToolExecutor.kt:84` | | |
| C8 | [ ] | O(n) full-table vector scan in Kotlin on every chat message AND every curation. No ANN, no pagination, no pre-normalization. Pre-normalize at insert; add vector index (sqlite-vec/HNSW) or `WHERE id IN` prefilter. | `CuratorAgent.kt:48`, `ChatAgent.kt:41`, `GraphDao.kt:71` | | |
| C9 | [ ] | Leaked unmanaged `CoroutineScope` in `ChatAgent` for memorization — never cancelled, no backpressure, no dedup. Replace with bounded queue or WorkManager. | `ChatAgent.kt:97` | | |
| C10 | [ ] | Sherpa denoiser `run()` return value (JNI peer holding native memory) never released → steady native-memory leak over hours. | `SherpaManager.kt:95-96` | | |
| C11 | [ ] | `pcmBytesToFloatArray` high-byte sign-extension bug — missing `and 0xFF` before `shl 8` → corrupts ~50% of audio samples. | `SherpaManager.kt:121` | | |
| C12 | [ ] | Stored prompt-injection loop: raw `node.content` injected into system prompts with no escaping/delimiters/length cap. A poisoned memory re-feeds itself forever. | `CuratorAgent.kt:63`, `ChatAgent.kt:50` | | |

---

## Tier 2 — High (robustness / correctness / privacy)

| # | Done | Finding | File:Line | Checked | Ref |
|---|------|---------|-----------|---------|-----|
| H1 | [ ] | `GroqApi` never checks `isSuccess()` before `.body()` → errors surface as `JsonDecodeException` masking the real HTTP status. | `GroqApi.kt:24-39` | | |
| H2 | [ ] | `NimApi` logs full response bodies at `Log.e` (even on success) — PII + potential key echo. Remove success log; redact/truncate error bodies. | `NimApi.kt:30,33` | | |
| H3 | [ ] | Error bodies discarded — exception only carries status number as string. `QueueWorker` can't classify 401 vs 429 vs 500. Define typed `NimApiException(status, body)`. | `NimApi.kt:31` | | |
| H4 | [ ] | gRPC `ManagedChannel` rebuilt + torn down per Whisper call — full TLS handshake every chunk. Make it a singleton. | `NimWhisperClient.kt:24-27,55` | | |
| H5 | [ ] | No gRPC deadline/timeout — hung call blocks `Dispatchers.IO` indefinitely. Add `withDeadlineAfter`. | `NimWhisperClient.kt:38` | | |
| H6 | [ ] | No retry/backoff anywhere except `QueueWorker`. Chat/embedding/curator/chronos have zero retry. | `NimApi.kt`, all agents | | |
| H7 | [ ] | `NetworkMonitor` never calls `unregisterNetworkCallback` → leaks callback + Context. Add `@Singleton`, use `applicationContext`, unregister on teardown. | `NetworkMonitor.kt:11,27` | | |
| H8 | [ ] | `NetworkMonitor` uses `ExistingWorkPolicy.REPLACE` on `onCapabilitiesChanged` (fires often) → cancels in-flight `QueueWorker` drains → queue starvation. Use `KEEP`. | `NetworkMonitor.kt:42-47` | | |
| H9 | [ ] | `QueueWorker` processes only 10 items then returns `success` with no self-re-enqueue → drain stalls if network is stable. Re-enqueue if more pending remain. | `QueueWorker.kt:31` | | |
| H10 | [ ] | `PruningWorker` summarize+delete not in a transaction, not idempotent → retry after successful Curator call but failed delete = duplicate graph nodes. Wrap in DB transaction. | `PruningWorker.kt:56-60` | | |
| H11 | [ ] | `PruningWorker` scheduled with no `setInitialDelay` → runs at app launch, not midnight; drifts by launch time. Align to 00:00. | `MineApplication.kt:44` | | |
| H12 | [ ] | No `setConstraints(NetworkType.CONNECTED)` on any of the 3 workers — all make network calls but can fire offline → fail → retry → battery drain. | `MineApplication.kt:36,44` | | |
| H13 | [ ] | `AmbientListeningService` `START_STICKY` restart re-enters `startForeground` with no `RECORD_AUDIO` permission check → crash if permission revoked while dead. | `AmbientListeningService.kt:60-66` | | |
| H14 | [ ] | `stopListening()` cancels `listeningJob` fire-and-forget then `release()`s audio on next line — race on hardware. Use `cancelAndJoin()` before `release()`. | `AmbientListeningService.kt:184-189` | | |
| H15 | [ ] | `audioChannel` is `UNLIMITED` and never closed → if processor stalls, reader buffers PCM until OOM. Use bounded channel or `CONFLATED`. | `AmbientListeningService.kt:34` | | |
| H16 | [ ] | `processCompletedSentence` swallows all exceptions → online-but-failed transcripts silently dropped (not queued). Queue on network failure. | `AmbientListeningService.kt:180` | | |
| H17 | [ ] | `PersonaType.valueOf(prefs.currentPersona)` throws `IllegalArgumentException` on stale/empty pref → crashes `ChronosWorker`/`ChatAgent` before try block. Use safe lookup. | `ChronosAgent.kt:42`, `ChatAgent.kt:67` | | |
| H18 | [ ] | `"SKIP"` exact-match in Chronos is case/punctuation-sensitive → `"SKIP."` gets sent to user as a notification. Use `trim().equals("SKIP", ignoreCase=true)`. | `ChronosAgent.kt:74` | | |
| H19 | [ ] | `NotificationHelper` shows full nudge content on lock screen — no `setVisibility(VISIBILITY_PRIVATE)`, no redacted public version. | `NotificationHelper.kt:62-63` | | |
| H20 | [ ] | `DebugViewModel.addManualTodo` builds JSON via string interpolation → JSON injection. Use a JSON serializer. | `DebugViewModel.kt:139` | | |
| H21 | [ ] | `OnConflictStrategy.REPLACE` on FK-bearing tables (`insertNode`, `insertEdge`) — REPLACE cascades DELETE of dependents then re-inserts node without edges/embedding. Switch to `ABORT`/`IGNORE`+update. | `GraphDao.kt:18,21` | | |
| H22 | [ ] | `"update"` action appends text but never re-embeds → node's vector drifts from its text, retrieval accuracy decays. Re-embed on update. | `ToolExecutor.kt:71` | | |
| H23 | [ ] | `insertGroup` creates a new "Auto-Generated" row on every curation call → groups table pollution. | `ToolExecutor.kt:54` | | |
| H24 | [ ] | `catch (Exception)` swallows `CancellationException` in 3 places → breaks structured cancellation. Rethrow `CancellationException`. | `ToolExecutor.kt:108`, `ChronosAgent.kt:88`, `ChatAgent.kt:101` | | |
| H25 | [ ] | `PreferencesManager` EncryptedSharedPreferences init in field initializer with no try/catch — Keystore failure crashes app at launch. | `PreferencesManager.kt:13-21` | | |
| H26 | [ ] | `ChatViewModel.sendMessage` swallows all exceptions via `e.printStackTrace()` — user gets no feedback that message failed. Surface errors to UI. | `ChatViewModel.kt:41-43` | | |
| H27 | [ ] | Empty `choices`/`data[0]` not guarded → `IndexOutOfBoundsException`; `ChatAgent` persists `"..."` fallback to chat history → pollutes future context. | `CuratorAgent.kt:45`, `ChatAgent.kt:39,84` | | |
| H28 | [ ] | `NimEmbeddingRequest.inputType` defaults `"passage"` but `ChatAgent` uses it for query embedding → wrong input type, degrades retrieval. Use `"query"` for queries. | `NimDto.kt:69`, `ChatAgent.kt:34` | | |
| H29 | [ ] | `DebugViewModel` + debug UI ship in release APK (no variant isolation + optimization off) — exposes DB contents, forces AI calls, dumps transcripts. Isolate to debug variant. | `DebugViewModel.kt` | | |
| H30 | [ ] | Missing API key in `local.properties` silently becomes empty string `""` at runtime → 401s with no clear cause. Should fail the build via `gradle.fail(...)`. | `app/build.gradle.kts:33-35` | | |

---

## Tier 3 — Medium / Low (hygiene, correctness, efficiency)

### Schema & DB

| # | Done | Finding | File:Line | Checked | Ref |
|---|------|---------|-----------|---------|-----|
| M1 | [ ] | Schema/prompt mismatch: `trigger_nudge` defined per-operation in prompt but only at root in `ToolSchemas` → per-op nudges silently dropped. | `CuratorAgent.kt:24` vs `ToolSchemas.kt:6` | | |
| M2 | [ ] | Threshold mismatch: `CuratorAgent` uses `> 0.1f`, `ChatAgent` uses `> 0.2f` — same DB, different recall behavior, undocumented. | `CuratorAgent.kt:57`, `ChatAgent.kt:46` | | |
| M3 | [ ] | `[SPATIAL CONTEXT]` label reports network type (Wi-Fi/Cellular), not a location — misleads the LLM. | `CuratorAgent.kt:66`, `ChatAgent.kt:64` | | |
| M4 | [ ] | Orphaned embeddings for trashed nodes never deleted → storage leak. | `ToolExecutor.kt:96` | | |
| M5 | [ ] | `"contradict"` hardcodes `confidence = 1.0f`, ignoring `op.confidence`. | `ToolExecutor.kt:78` | | |
| M6 | [ ] | `source = "ambient"` hardcoded for all created nodes even from chat → mislabels provenance. | `ToolExecutor.kt:65,79,93` | | |
| M7 | [ ] | FTS MATCH quoting crash — query containing `"` breaks the MATCH syntax. Escape `"` → `""`. | `GraphDao.kt:38` | | |
| M8 | [ ] | Missing indices: `nodes.created_at`, `nodes.updated_at`, `edges.created_at`, `transcripts.created_at`, `memory_queue(status, created_at)`, `branches.parent_branch_id`. | `GraphEntities.kt`, `SystemEntities.kt` | | |
| M9 | [ ] | `groups.name` not UNIQUE in entity despite doc saying `UNIQUE NOT NULL`. | `GraphEntities.kt:26` | | |
| M10 | [ ] | No uniqueness on `edges(source, target, relation)` → duplicate edges. | `GraphEntities.kt:60-61` | | |
| M11 | [ ] | `data class` + `ByteArray` broken `equals`/`hashCode` in `NodeEmbeddingEntity`, `MemoryQueueEntity`, `ApiQueueEntity`. Override with `contentEquals`/`contentHashCode`. | `GraphEntities.kt:104-106`, `SystemEntities.kt:33,41` | | |
| M12 | [ ] | `VectorUtils` silent failures: non-multiple-of-4 truncation, dimension mismatch returns `0.0f`, implicit byte order. Add validation + explicit `ByteOrder`. | `VectorUtils.kt:8,18,30` | | |
| M13 | [ ] | `api_queue` has no `status` column but `getPendingApiTasks` doesn't filter; `api_queue`/`rejected_logs` not in schema doc (drift). | `QueueDao.kt:24`, `SystemEntities.kt:39-46` | | |
| M14 | [ ] | `MemoryQueueEntity` allows both `rawText` and `audioBlob` null — unprocessable dead weight. Add CHECK or validation. | `SystemEntities.kt:32-33` | | |
| M15 | [ ] | `branches.parent_branch_id` no self-referencing FK, no index → orphans possible on parent delete. | `GraphEntities.kt:30` | | |
| M16 | [ ] | `ChatDao` duplicated query strings (`getRecentMessagesFlow` vs `getRecentMessagesList`) — drift risk. | `ChatDao.kt:11,15` | | |
| M17 | [ ] | `getOldMessagesList` loads all old messages with no LIMIT — unbounded memory if purge hasn't run. | `ChatDao.kt:19` | | |
| M18 | [ ] | FTS4 used instead of FTS5 (deprecated). Not a bug, but a smell. | `GraphEntities.kt:86` | | |

### Network & Rate Limiting

| # | Done | Finding | File:Line | Checked | Ref |
|---|------|---------|-----------|---------|-----|
| M19 | [ ] | `System.currentTimeMillis()` in `RateLimiter` — not monotonic (NTP/manual clock jumps). Use `SystemClock.elapsedRealtime()`. | `RateLimiter.kt:26` | | |
| M20 | [ ] | Token consumed before request in `NimApi` — failures burn rate budget; no refund on failure. | `NimApi.kt:21,37` | | |
| M21 | [ ] | `NimChatResponse` has no `usage` field → can't do token-based throttling or cost tracking. | `NimDto.kt:54-58` | | |
| M22 | [ ] | `NimEmbeddingData.embedding: List<Float>` boxes every dimension → GC pressure on hot path. Use `FloatArray` with custom serializer. | `NimDto.kt:78` | | |
| M23 | [ ] | Two separate `Json` instances (`NimApi.jsonParser` vs `NetworkModule` ContentNegotiation) — config drift risk. Consolidate. | `NimApi.kt:18`, `NetworkModule.kt:42` | | |
| M24 | [ ] | Base URL hardcoded twice in `NimApi` (chat vs embeddings) — easy to drift. Extract common base. | `NimApi.kt:17,39` | | |
| M25 | [ ] | `GroqApi` takes no `RateLimiter` → Groq calls unthrottled. | `GroqApi.kt:14` | | |
| M26 | [ ] | 120s connect timeout is excessive for mobile — dead network holds coroutine 2 min. Lower connect timeout to ~10-15s. | `NetworkModule.kt:21-23` | | |
| M27 | [ ] | Double timeout config (Ktor `HttpTimeout` + OkHttp engine) — redundant, can drift. Pick one layer. | `NetworkModule.kt:21-31` | | |
| M28 | [ ] | No OkHttp connection pool / dispatcher tuning for concurrent 3-endpoint traffic. | `NetworkModule.kt` | | |
| M29 | [ ] | `NimWhisperClient` hardcoded `functionId` and host — environment swaps require rebuild. Move to BuildConfig/config. | `NimWhisperClient.kt:19-21` | | |
| M30 | [ ] | `GroqApi` hardcoded `language="en"`, `model`, `filename="chunk.wav"`, `audio/wav` — not configurable. | `GroqApi.kt:28,31,33-34` | | |

### Services, Workers & Notifications

| # | Done | Finding | File:Line | Checked | Ref |
|---|------|---------|-----------|---------|-----|
| M31 | [ ] | `NotificationHelper` IDs use `currentTimeMillis().toInt()` — collisions within ~2.1s. Use `AtomicInteger`. | `NotificationHelper.kt:41,89` | | |
| M32 | [ ] | `NotificationHelper` `setDefaults(DEFAULT_ALL)` is dead code on Android 8+ (channel governs). | `NotificationHelper.kt:64,86` | | |
| M33 | [ ] | `NudgeReplyReceiver` logs reply text to logcat (PII) + unmanaged scope with no `withTimeout` → ANR risk. | `NudgeReplyReceiver.kt:29,32` | | |
| M34 | [ ] | `SherpaManager.copyAsset` re-extracts all ONNX models on every service start — wasted I/O. Skip if file exists + valid. | `SherpaManager.kt:56-63` | | |
| M35 | [ ] | `SherpaManager.isInitialized` non-atomic → double-init race. Use `Mutex`/`AtomicBoolean` + deferred. | `SherpaManager.kt:23` | | |
| M36 | [ ] | Call-state polling uses deprecated `telephonyManager.callState` in a 1s loop. Use `TelephonyCallback` (API 31+) and unregister. | `AmbientListeningService.kt:86-113` | | |
| M37 | [ ] | `isPausedByCall` non-`@Volatile` cross-coroutine flag. | `AmbientListeningService.kt:38` | | |
| M38 | [ ] | `ServiceStateHolder` shows "listening" during call-pause — state is a lie; no `isPaused` state. | `ServiceStateHolder.kt` | | |
| M39 | [ ] | `MineApplication` uses `ExistingPeriodicWorkPolicy.KEEP` → spec changes on app update don't take effect. Use `UPDATE`. | `MineApplication.kt:40,47` | | |
| M40 | [ ] | `QueueWorker` fatal-error detection by substring matching on error messages (`"401"`, `"400"`, `"403"`) — brittle. Parse HTTP status from typed exception. | `QueueWorker.kt:73-75` | | |
| M41 | [ ] | `ChronosWorker` has no try/catch around the network/AI call → any exception marks worker failed, no retry for 4h. | `ChronosWorker.kt:22-28` | | |
| M42 | [ ] | `PruningWorker` `chatTranscript.takeLast(15000)` builds full string then truncates — truncate before join. | `PruningWorker.kt:51` | | |
| M43 | [ ] | `getStartOfDayTimestamp` uses `ZoneId.systemDefault()` → timezone travel can shift "today" and prune prematurely. | `MineUtils.kt:18-22` | | |
| M44 | [ ] | `AmbientListeningService` mic-hijack recovery loop spins forever every 2s with no backoff/max-retry/user notification. | `AmbientListeningService.kt:119-127` | | |
| M45 | [ ] | `AmbientListeningService` call reflection only fires for OFFHOOK→IDLE, not RINGING→IDLE (missed call) → no reflection nudge. | `AmbientListeningService.kt:102` | | |
| M46 | [ ] | `bufferSize * 2` in `AudioRecord` construction — no validation if `getMinBufferSize` returns negative. | `AmbientListeningService.kt:79`, `AudioRecorderManager.kt:27` | | |
| M47 | [ ] | `AudioRecorderManager` `ByteArrayOutputStream` grows unbounded — long manual recording OOMs. Add max-size cap or file-backed sink. | `AudioRecorderManager.kt:21` | | |
| M48 | [ ] | `DebugViewModel.toggleRecording` no double-tap guard; `enrollUserVoice` leaks audio on cancel. | `DebugViewModel.kt:255-300,302-322` | | |
| M49 | [ ] | `DebugViewModel.fetchAndDisplayDatabase` triple-reads DB per action. | `DebugViewModel.kt:243-263` | | |
| M50 | [ ] | `MainActivity` reflection dialog state not in `SavedStateHandle` → lost on process death. | `MainActivity.kt` | | |

### DI & Config

| # | Done | Finding | File:Line | Checked | Ref |
|---|------|---------|-----------|---------|-----|
| M51 | [ ] | `AppModule.provideSherpaManager` duplicates `SherpaManager`'s own `@Inject constructor` — pick one. | `AppModule.kt:29-32` | | |
| M52 | [ ] | `AudioRecorderManager` and `SherpaManager` are app-lifetime singletons holding media/native resources even when unused. | `AppModule.kt:23-27` | | |
| M53 | [ ] | `PreferencesManager` properties hit disk synchronously with no cache; `currentPersona` called per-message. | `PreferencesManager.kt` | | |
| M54 | [ ] | `PreferencesManager` exposes no backing `Flow` — UI can't observe persona changes reactively. | `PreferencesManager.kt` | | |
| M55 | [ ] | `DebugViewModel.prefs` is public — exposes singleton directly to UI, bypassing the ViewModel. | `DebugViewModel.kt:44` | | |
| M56 | [ ] | `app/build.gradle.kts` wires `debug`-variant proto srcDirs into `main` — release proto generation not wired. | `app/build.gradle.kts:21-26` | | |
| M57 | [ ] | Hardcoded model strings (`openai/gpt-oss-120b` in Curator, `mistralai/mistral-small-4-119b-2603` in Chat/Chronos) — no central model config; easy to drift. | `CuratorAgent.kt:88`, `ChatAgent.kt`, `ChronosAgent.kt` | | |
| M58 | [ ] | `ChatAgent` nudge messages (`role="mine"`, `isNudge=true`, `"⚡ "` prefix) fed back to LLM as prior assistant turns — context pollution. | `ChatAgent.kt:76`, `ToolExecutor.kt:113` | | |
| M59 | [ ] | `ChatAgent` history truncation to 500 chars is arbitrary and not aligned with 2000-char embedding truncation elsewhere. | `ChatAgent.kt:74` | | |
| M60 | [ ] | Debug comments left in production code (`// <-- NEW`, `// --- FIX: ...`, `// Launch the new Tabbed UI!`). | multiple | | |

---

## Tier 4 — Dependency Health (`gradle/libs.versions.toml`)

| # | Done | Dependency | Version | Issue | Checked | Ref |
|---|------|------------|---------|-------|---------|-----|
| D1 | [ ] | `ktor` | 2.3.12 | Old line; 3.x is current. No new features. | | |
| D2 | [ ] | `room` | 2.6.1 | Stale; 2.7.x current. | | |
| D3 | [ ] | `sqlcipher` (`net.zetetic:android-database-sqlcipher`) | 4.5.4 | EOL artifact. Migrate to `net.zetetic:sqlcipher`. | | |
| D4 | [ ] | `security-crypto` | 1.1.0-alpha06 | Alpha for secret storage in production. Use stable 1.0.0 or accept risk knowingly. | | |
| D5 | [ ] | `grpc` | 1.62.2 | Early 2024; check CVEs in later 1.6x/1.7x. | | |
| D6 | [ ] | `protobuf` | 3.25.3 | 3.25.x had CVEs (e.g., CVE-2024-7254 DoS); verify patched. | | |
| D7 | [ ] | `tomcat-annotations-api` | 6.0.53 | Ancient (2009); red flag in scans. Use `jakarta.annotation-api`. | | |
| D8 | [ ] | `ksp` vs `kotlin` | 2.3.9 vs 2.3.0 | Verify KSP/Kotlin compatibility. | | |
| D9 | [ ] | `room3CommonJvm` | 3.0.0-alpha06 | Dead catalog entry — unused. Remove. | | |
| D10 | [ ] | `workManager` | 2.9.0 | Slightly behind; 2.10.x current. | | |
| D11 | [ ] | `coreKtx` | 1.15.0 | Slightly behind; 1.16.x current. | | |

---

## Tier 5 — Doc-vs-Code Drift

| # | Done | Doc claim | Reality | Checked | Ref |
|---|------|-----------|---------|---------|-----|
| DD1 | [ ] | "3 keys × 40 RPM = 120 RPM" | `RateLimiter` serializes all keys → ~39 RPM global | | |
| DD2 | [ ] | "strict Mutex Token Bucket" | Sliding-window counter; mutex held during `delay` | | |
| DD3 | [ ] | "Keys not in code; injected via BuildConfig" | BuildConfig ships in APK — extractable | | |
| DD4 | [ ] | "Database encrypted at rest" | Hardcoded password — not actually protected | | |
| DD5 | [ ] | "Poison Pill Protection in QueueWorker" | `NimApi`/`GroqApi` throw generic `Exception` with no status → can't classify | | |
| DD6 | [ ] | "PruningWorker runs once every 24 hours" | No `setInitialDelay` → runs at app launch, drifts | | |
| DD7 | [ ] | `api_queue`, `rejected_logs` tables | Not documented in `03 - Database Schema.md` | | |
| DD8 | [ ] | `groups.name UNIQUE NOT NULL` | Entity has no unique index | | |
| DD9 | [ ] | `branches.parent_branch_id` FK | No FK declared, no index | | |

---

## Suggested Fix Order

If you choose to act, the recommended sequence is:

1. **Tier 1 (C1–C12)** — stop the bleeding (data/security/throughput).
2. **Tier 2 (H1–H30)** — make it robust and private.
3. **Tier 3 (M1–M60)** — hygiene, correctness, efficiency.
4. **Tier 4 (D1–D11)** — dependency upgrades.
5. **Tier 5 (DD1–DD9)** — reconcile docs with code.

The two highest-leverage fixes are **C5 (rate limiter)** and **C8 (vector scan)** — they silently cap the product's ceiling regardless of how good the AI prompts are.

---

## Progress Log

Use this section to record milestones as you work through the checklist.

| Date | Milestone | Notes |
|------|-----------|-------|
| 2026-08-31 | Audit completed; checklist created | 18 critical/high + 30 medium/low + 11 deps + 9 doc-drift findings |
| | | |
| | | |
