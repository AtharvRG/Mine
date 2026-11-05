# Current Task: R12d (Corrections: Transactional CAS inside effect & Guarded Rollback)

Status: Complete (Approved by developer; all 7 items implemented, verified, reviewed, and committed)

## R12d Items:
- [x] 1. Implement D-118 across Gateway.handleConfirmChange (CREATE time-chip, CANCEL, COMPLETE, RESCHEDULE, UPDATE-reminder). Single-winner semantics: two concurrent confirms => exactly one effect, other gets "Proposal already resolved". In-transaction consume CAS (UPDATE proposal SET status='consumed' WHERE id=? AND status='open') aborting effect transaction if rowsAffected==0 in AndroidStorage and SharedFakeStorage. Terminal failures (node not found / not active) consume proposal; non-terminal failures leave it open. Record D-118 in DECISIONS.md.
- [x] 2. Guarded rollback: replace blind updateProposalStatus rollback with reopenProposal(id) = UPDATE proposal SET status='open' WHERE id=? AND status='consumed' in Storage, AndroidStorage, and SharedFakeStorage. Parity check: fake getProposal expiry strictly matches SQLite (no clock.now() fallback for missing created_at).
- [x] 3. Failing-first revert-proof tests:
  - crash-window: effect transaction throws => proposal still open and no effect row.
  - concurrent confirm => one effect, one "already resolved" (real SQLite in androidTest).
  - rollback cannot reopen a proposal consumed by someone else.
  - expiry parity fake vs SQLite.
  - update fresh-install smoke test in DatabaseMigrationTest.kt to call every new Storage method.
- [x] 4. Evidence: Save raw outputs in docs/agent/evidence/: :core:test, :app:testDebugUnitTest, clean-install connected run (adb uninstall first), and SecurityException stack trace from R12c item 5.
- [x] 5. Doc drift: Fix HANDOFF title/body, STATE "Known broken", TASKS statuses; fix or retire stale text in PROJECT_STATE.md (OkHttp, RTC bug, USE_EXACT_ALARM).
- [x] 6. Test-auditor & Reviews: Document "what it would NOT catch" per test in docs/agent/work/r12d-review.md; Reviewer + Migration Guard reports.
- [x] 7. Commit with git commit -s per item. Stop after R12d.


