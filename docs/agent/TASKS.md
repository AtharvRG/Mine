# TASKS (one at a time, smallest first). Status: [ ] open, [~] in progress, [x] done+reviewed
## Blockers
[x] T1 Provider endpoint: saving strips /chat/completions but the transport posts as-is -> 404. Test: save provider, assert request URL.
[x] T2 Import: DB swap runs in finally, so a failed import overwrites live DB. Swap only after validated commit; tampered/truncated file leaves live DB unchanged. Test it.
[x] T3 Reminder id scheme half-migrated (r_<id> vs rem_...). Look up reminders by nodeId everywhere (confirm_change, undo); CREATE-with-time auto-reminder vs schedule_reminder must not double-arm. Tests: chip-cancel removes alarm; undo after reschedule.
[x] T4 Alarm registry treated as truth; force-stop loses alarms; reconcile not run at startup. Run reconcile at start/boot/time change; treat registry as a hint.
[x] T5 Export: hardcoded passphrase "password123", API keys always included, file in cacheDir, no picker. Build passphrase dialog + SAF picker or hide export.
## Important
[x] T6 Receipts: lifecycle, persist, render next to message, show failures; chip-confirmed changes need receipts; stop storing model "Done!" text.
[x] T7 Ambiguity chips show node ids; carry titles and due times.
[x] T8 Mixed commit+error turn hides the error from the user.
[x] T9 getUrgentNodes ORDER BY due_at puts NULLs first; fix + real-DB test.
[x] T10 Loop-limit branch re-inserts botId (PK violation); "Loop limit reached" fires wrongly.
[x] T11 Remove polling coroutine in AndroidAlarmScheduler (while(isActive) delay(100)).
[x] T12 MainActivity opens exact-alarm settings every launch; not needed for setAlarmClock.
[x] T13 Sweeper may log same missed reminder twice; run on start/boot/periodic.
[x] T14 Soul facts: proposed never promoted; add accept/reject path and inject confirmed facts into context.
[x] T15 Stop button in UI; remove dead ScheduleIn action.
[x] T16 ChatScreen: imePadding, auto-scroll.
[x] T17 Chunked AES stream edge case: footer read before final-flag chunk; ChunkedAesInputStream.read can return 0.
## Housekeeping
[x] T18 Real GPLv3 LICENSE text; delete stray file "a"; .idea device IP; update docs/ui-contract.md.
[x] T19 Release (R8) build smoke test; proguard keep rules for Node, Reminder, SoulFact.
## Backend needed before UI (see UI spec E6)
[x] T20 Storage queries: day page by day_key, heat-map counts, reminder dates per month, Home priority list, structured chat items.
[x] T-A DateResolver 24h + ambiguity chips (D-103)
[x] T-B getActiveNodes paging + ContextBuilder budget + stale->Inbox (D-104)
[x] T-C Alarm tiers: Alarm (setAlarmClock) vs Gentle; tier stored on the reminder; icon disclosure text exposed to UI (D-105)
[x] T-D New edge type follow_up: migration + migration test + blueprint 13 patch + UiAction/Storage support (D-106)
[x] T-E Calendar queries: day exists with messages, reminder dates per month, heat counts per day, reminders for a day (tooltip)
[x] T-F Quote slots: table quote_slot(slot, day_key, text, generated_at, state_hash, source=preset|live), preset pool of generic quotes per slot, generation job (see Part 3, Home), setting flags
