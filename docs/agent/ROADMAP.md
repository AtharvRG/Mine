# MINE ROADMAP (agent version). Source: blueprints 90, 43, 44, 60, 61, 15, 21, 31, 32, 40, 42, plus UI spec E6.
Legend: [HG] = HUMAN-GATED (prepare, then stop and ask). flag.* = ships dark. Each task needs: tests, review, memory update.

## P1: FINISH v1 (entry gate: open now)
Goal: a trustworthy backend + plain test UI. Exit = blueprint 90 gates X-01..X-09 pass by evidence; X-10 (14 days daily use) is [HG].

P1-A Correctness blockers
 1. Provider endpoint: saving must not break the request URL; transport and saved value must agree (test: save, assert request URL).
 2. Import safety: swap DB only after validated commit; failed/tampered/truncated import leaves live DB unchanged (test).
 3. Reminder identity: store node_id column on reminder; look up by node_id in handleResolveChange, confirm_change, undo, cancel, reschedule. Prevent double alarms (CREATE with time vs schedule_reminder). Chip-cancel must cancel the OS alarm. Undo after reschedule restores due_at and the alarm.
 4. Alarm truth: registry is a hint; reconcile at app start, boot, TIME_SET/TIMEZONE, after every commit; compare fire time not only id; recover after force-stop. Receiver must fall back to a notification from intent extras if DB fails.
 5. Export: user passphrase dialog (no hardcoded value), API keys excluded by default, SAF save/share, verify footer hash and counts, fix ChunkedAes read-returns-0 and final-chunk edge case; real import (fresh DB, then swap).
P1-B Product correctness
 6. Receipts: structured, persisted, linked to the operation id, rendered from the log not model prose; chip-confirmed changes and undo results produce receipts; failures are shown; stop storing model "Done!" text.
 7. Ambiguity: chips carry titles and due times; open proposals and undo tokens appear in UiState and in the context pack; proposals expire (24 h).
 8. Mixed commit+error turn must surface the error. Loop-limit message fires only when the loop actually stops; no duplicate message ids.
 9. SQLite NULL ordering in urgent/agenda queries; fix and test against the real DB.
 10. Sweeper: notify first, delete second, dedupe; run at start, boot and periodically (WorkManager). Remove the polling coroutine in AndroidAlarmScheduler. Remove exact-alarm settings launch from MainActivity.
 11. Soul: proposed->active via accept/reject (UiAction) and by confidence rule in auto mode; inject confirmed, non-local_only facts into the context pack; sensitive categories only on explicit user intent.
 12. Streaming: extract the "reply" field incrementally from envelope JSON (or buffer) so no raw JSON shows; Stop is a real UiAction; no dead UiActions.
 13. Provider: eager+lazy failures, 5xx/timeouts fall through the chain, one 429 on a single target shows state and Retry now; trim key/endpoint; accept "data:" with or without a space; cap error bodies; thread-safe, persisted RateLimiter.
 14. DateResolver: injected clock, tz and rollover from day_rule, zero seconds, validated hours/minutes, vocabulary: in N min/mins/hours/hrs/days, in an hour/half an hour, at 5pm, 5:30pm, today at 7pm, tomorrow 4pm / at 9:30, tonight (never yesterday after midnight), next monday, weekday names; no am/pm -> null (ask); past "today" -> clear error. Prompt documents exactly the accepted forms. Full tests including 2am cases.
 15. Reminder policy per blueprint 30 (lead times, reminder only when asked or per default). Document the choice with the section number.
 16. UPDATE and RESCHEDULE intents implemented through gateway and resolver, with undo.
 17. Dedupe: same title near same due time -> needs_confirm. Input caps on titles/statements/categories; validate node types.
P1-C Hygiene and release
 18. Real GPLv3 LICENSE; delete stray files; remove committed device IP / .idea; headers everywhere; ui-contract.md accurate; squash migrations into the v1 schema ONLY at release (dev phones reinstall); release R8 build runs; proguard keeps for serializable classes.
 19. Recovery: keystore-lost/storage-failed states with reset (typed confirm) in UiState.
P1-D Backend data the UI needs (queries only, no UI)
 20. Day page by day_key (+ index raw_message(day_key), reminder(fire_at)); previous/next day existence; heat-map counts per day per month; reminder dates per month with basics; Home data (code-ranked priority list, daily cached quote hook with template fallback, at-risk flag, Inbox count, active threads, recently closed with undo validity); structured chat items (bubble, receipt, chips, nudge+reason, check-in snapshot, system line); UiAction set (send, stop, retry, undo, choose option, confirm, load day, search, create/edit/complete/cancel item, snooze/cancel reminder, soul accept/reject/edit, export, import, toggles, dev mode, reset). Document in docs/ui-contract.md.
P1-E Remaining v1 backend per blueprints (each with tests)
 21. day_rule/day_key monotonic, rollover (11, 50 s3.13). 22. Layers: raw/derived/operational, rebuild jobs skeleton (11). 23. FTS5 keyword search: verify FTS5 in the SQLCipher build ON DEVICE first; multi-token AND queries, status filter, rank order, try/catch, limit cap; Embedder interface stub (21). 24. Nudge engine wired: triggers, scoring, gates, one open thread, ignore-backoff, hidden fuse, pause, long-silence mode, templates, why/reason_json (31). 25. Check-ins: morning/evening/weekly facts blocks, validation against facts, template narration, snapshot cards (32). 26. Learning stats: signal_log, learned_pref, decay, exploration, reset (25). 27. Reliability: score, sweeper heartbeat, latency telemetry, self-test (42). 28. Offline outbox, batching of offline messages, templates, stale-job handling (41). 29. Personas incl. custom, core rules layering (24). 30. Memory viewer backend: edits as user_edit anchors, Trash (30 days), Erase scrub, merge people, inbox union query (51). 31. Settings catalog backend + onboarding_state (52). 32. Context pack full budget behaviour, compact mode (20). 33. Resolver: scale consistent with 22 s3.4, Unicode-aware tokens, stopwords/command words. 34. Prompt-injection and privacy audit test (E-15, E-16 in 93).
Exit gate P1: all P1 tasks [x]; X-01..X-09 evidence; X-10 [HG] 14-day daily use with defect log; then developer says "open P1.1".

## P1.1: v1.1 (entry: v1.0 released and in use)
 1 Provider setup import (file/QR; key optional). 2 Direct-boot alarm safety net (device-protected table of fire_at + generic label; no private text) [HG verify on device after reboot before first unlock]. 3 Recurring reminders and habits (promote attrs to columns, streaks) with migration. 4 Readable export (Markdown, loud warning, not importable). 5 Scheduled auto-export to a SAF folder with Keystore-held KEK; files still openable by passphrase alone. 6 Rule-based offline quick add. 7 Share target and widget data source. 8 Calendar-grid and board-view queries. 9 Graph neighbourhood query (1-2 hops, cap ~150) and edge type `followup` (decision D-1xx, ask first). 10 Template language packs, "what's new" bundled card. 11 Embedding model manager + EmbeddingGemma 2 text-only via LiteRT behind Embedder, int8 256-dim, device gate, rebuild jobs [HG: check model licence and measure on device]. 12 Hybrid retrieval with RRF, time parsing, summaries (day/week/month) per 21.
Exit: P1.1 tests green; release checklist (92 s3.6).

## P2: v2 (entry: developer says open P2; v1 used 2-3 weeks)
All behind flag.sensing / flag.call_nudge / flag.voice.
 Sensing (43): place and motion as independent axes; Wi-Fi first, optional geofence; debounce rules; flap guard; vehicle hold; place and place_signal (device-local, never exported); sensing_event pruning; context triggers; Now-block line; learning aggregates. [HG: permissions per Android 14; test walking-at-home on device].
 Call nudge (44): phone-state receiver, min duration 45 s, delay, coalesce, expiry 3 h, notification actions, reply path with source=call_nudge, ignore-3-times question; call_event device-local; no number stored; contact name only with know_contact (v2.1). [HG: device test on dual-SIM if available; Android restrictions].
 Voice (60): recorder (16 kHz mono in memory), STT provider slot + adapters + probe, on-device sherpa-onnx STT fallback, transcript review before send, audio wiped after transcription (24 h max); TTS on demand: system TTS fallback, Kokoro via sherpa-onnx; model manager (download, checksum, terms shown, delete); audio focus; no TTS with TalkBack by default; limiter integration (STT counts, 50 RPM ceiling, 429 cooldown). Private build flavor hook for the unofficial TTS only if the developer opens it. [HG: model licences; measure battery/latency].
Exit: tests + device checks + privacy audit (no SSID/coords/contact in requests).

## P3: v3 ambient (entry: developer says open P3 AND approves the consent text). Everything here is [HG] for legal/consent wording and device soak tests.
 61: foreground-service state machine (Off/Sleeping/Listening/Processing), user-tap start only, heartbeat "tap to resume", blockers (window, battery, thermal, charging-only, mic busy, don't-listen places, no STT path, caps). Silero VAD + speaker embedding via sherpa-onnx (check licences), voiceprint device-local and never exported, labels user/other with confidence tiers, presence gate, RAM-only ring buffer, number scrubbing, dialogue windows, extraction proposal-only with attribution_uncertain/possible_duplicate flags, ambient_* staging tables with TTLs (transcripts 48 h or review, proposals 7 d), daily report, delete-all, STT cap 90 min/day and listening cap 10 h/day, P4 priority in provider layer, measured battery stats. Soak test 8 h on two devices; record drain, kills, missed windows. Never auto-restart the service silently. Never write ambient content except as `proposed`.
Exit: soak data + consent versioning + privacy audit.

## P4: v4 (entry: developer says open P4)
 Sync (15 s3.6): hlc columns populated by gateway, op-log + row-level LWW, conflict policy per table, redaction wins, device-local tables excluded, tombstone GC, pairing via QR (X25519 + confirmation code, trust list, revoke), file-bundle deltas first, LAN sync second; primary device for nudges; ring device per reminder; handshake version checks. [HG: security review of pairing before release]. Pure Kotlin core reused by a desktop client (separate module/targets; desktop UI is developer-owned).
 Ambient extras: bookmark "remember last 2 minutes", hands-free conversation mode. Optional on-device LLM as last fallback chain entry (P-010). Insights queries (weekly/monthly, activity strip, opt-in mood). Large-screen state support.
Exit: two-device sync tests with fake network, conflict scenarios, redaction propagation.

## UI INTEGRATION PHASE (opened only by the developer, any time after P1-D)
Input: the developer's React export + docs/ui-spec. You: diff the UI's needs against UiState/UiAction/Storage queries; list gaps, risky performance points and what to cut (keep/change/drop table); then port screens to Compose one at a time with screenshot tests, real-phone checks (release build, mid-range), reduced-motion and large-font states. Animations (pill morph, date dial, overscroll, toggle panel) are hand-built in Compose with AnchoredDraggable / SharedTransitionLayout / spring specs, plus predictive back. No haptics.

## STANDING CHECKS EVERY PHASE
Privacy audit of captured provider requests; release (R8) build; migrations from every earlier schema; battery/ANR sanity on device; docs/ui-contract.md and blueprints reconciled to the code; decision lines added for every contract change.
