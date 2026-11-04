# Current Task: R12c (Corrections & Hardening)

Status: In progress (Item 1 ready for review/commit; awaiting user confirmation)

## R12c Items:
- [ ] 1. Rollback fix: Gateway.handleConfirmChange rolls back via updateProposalStatus (preserving created_at and 24h expiry; implemented, tested with revert-proof unit test and real-SQLite test, reviews approved).
- [ ] 2. Atomicity: Honest atomicity analysis between CAS + compensating rollback vs transactional CAS inside effect; recommend one, await confirmation, record D-117.
- [ ] 3. Numbers: Direct test count reconciliation from JUnit XML files; connected tests run on clean install; reconcile count discrepancies (31/37/44/33).
- [ ] 4. Decisions: Append D-116 and D-117, formalise D-102b, document knowledge pack numbering differences.
- [ ] 5. USE_EXACT_ALARM: Prove or retract USE_EXACT_ALARM SecurityException on API 34.
- [ ] 6. Doc drift: Fix HANDOFF.md, STATE.md, TASKS.md, ui-contract.md; redact device IP/serials.
- [ ] 7. server.js: Remove unauthenticated server.js and package*.json.
- [ ] 8. Test-auditor on R12: Run test-auditor escape analysis for R12 tests.
