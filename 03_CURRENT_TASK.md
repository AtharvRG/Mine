# Current Task: R12c (Corrections & Hardening)

Status: Complete (All 8 items implemented, verified on physical device, reviewed, and documented; awaiting user OK to commit)

## R12c Items:
- [x] 1. Rollback fix: Gateway.handleConfirmChange rolls back via updateProposalStatus (preserving created_at and 24h expiry; implemented across Storage/AndroidStorage/SharedFakeStorage, tested with revert-proof unit test and real-SQLite test, reviews approved).
- [x] 2. Atomicity: Honest atomicity analysis between CAS + compensating rollback vs transactional CAS inside effect; recommend Two-Phase CAS + Compensating Rollback, recorded D-117.
- [x] 3. Numbers: Direct test count reconciliation from JUnit XML files (194 core, 9 app unit, 45 connected on physical moto g73 5G - Android 14 / API 34; total 248); reconciled historical count discrepancies (31/37/44/33).
- [x] 4. Decisions: Appended D-116 and D-117, formalised D-102b, documented knowledge pack numbering differences in DECISIONS.md.
- [x] 5. USE_EXACT_ALARM: Empirically proven SecurityException on API 34 when permission is absent; updated ManifestPermissionTest, D-102b, CLAUDE.md rule 7, and android-alarms skills.
- [x] 6. Doc drift: Fixed ui-contract.md (UiState & UiAction alignment), redacted device IP/serials in r3b.md, updated HANDOFF.md and STATE.md.
- [x] 7. server.js: Removed unauthenticated server.js, package.json, package-lock.json, and node_modules/.
- [x] 8. Test-auditor on R12: Run test-auditor escape analysis for R12 tests (what they prove vs what they would NOT catch: process death mid-CAS, reboot between CAS and commit, SQLite locking under background I/O, multi-process concurrency, digitizer debounce, TLS/HTTP2 teardown); documented in docs/agent/work/r12c-review.md.

