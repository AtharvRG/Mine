# HANDOFF (Session 1 - Remediation R0/R0b)

## 1. What Changed

- **Commit `3ed71b3`**: Baseline commit capturing initial repo state prior to remediation review.
- **Commit `81ec87a`** (`chore: record correction, hygiene, R1-R10 plan`):
  - Recorded architectural decisions D-109, D-110, D-111, and clarified D-105 gate status in `docs/agent/DECISIONS.md`.
  - Re-opened tasks T-A, T-B, T-C, T-D, T6, T13, T20 to in-progress `[~]` in `docs/agent/TASKS.md`.
  - Appended remediation tasks R1 through R10 and restored dropped roadmap backlog items (P1-B 15, 17, DateResolver weekday names, P1-D 20 remainder, P1-E 21..34) in `docs/agent/TASKS.md`.
  - Corrected toolchain versions, accurate test counts, and device offline reality in `docs/agent/STATE.md`.
  - Redacted device identifiers to git-ignored `device-local.properties`.
  - Deleted 0-byte dead stub `core/src/main/java/com/anchor/core/alarms/AlarmReceiver.kt`.
  - Hardened `scripts/ralph.py` against blind `git add -A`, `--allow-empty`, and continued loop on test failure.
  - Repointed hook commands in `.openclaude/settings.json` to `.openclaude/hooks/*.py`.
  - Aligned `docs/ui-contract.md` to remove unimplemented storage query claims.
  - Created `docs/agent/BLUEPRINT_PATCHES.md` with Patch 1 for Blueprint 13 s3.6 (`follow_up` edge type).
- **Commit `982dbc7` (R0b)**:
  - Created `docs/agent/work/session1-remediation-spec.md` preserving the full verbatim session 1 specification prompt.
  - Added pointer to `session1-remediation-spec.md` at top of `docs/agent/TASKS.md`.
  - Added historical warning banner to `docs/agent/INBOX_HANDOFF.md`, `docs/agent/work/session0-plan.md`, and `docs/agent/work/t-a.md` through `t-f.md`.
  - Reconciled all cited test names in `docs/agent/work/session0-plan.md` to verified existing test names or marked "does not exist".
  - Replaced `getEdgesFrom`/`getEdgesTo` with `getEdgesForSource`/`getEdgesForDestination` in `docs/ui-contract.md` and verified all UiState fields, UiActions, and Storage methods exist in `:core`.
  - Verified directory junction `docs/blueprints -> D:\CnP\MDocs (Attempt 3)`, added `docs/blueprints/` to `.gitignore`, and added deny rules for `Edit` and `Write` on `docs/blueprints/**` in both `.openclaude/settings.json` and `.claude/settings.json`.
    - *Safety Note*: The `Edit`/`Write` deny rules on `docs/blueprints/**` only block those two tools, not Bash, and may not match once the path resolves to `D:\CnP\...`. The developer will set the source folder to read-only.
  - Added developer-lock comment to `scripts/ralph.py` and gated `scripts/ralph_prompt.txt` behind `RALPH: ENABLED`.
  - Re-ran `:core:test`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:compileDebugAndroidTestKotlin`, and `:app:lintDebug` with full status captured.
- **Current Commit (R0c)**:
  - Verified blueprint junction directory listing and gitignore compliance (`git check-ignore -v docs/blueprints` matches; `git ls-files docs/blueprints` empty).
  - Split `:app:compileDebugAndroidTestKotlin` and `:app:testDebugUnitTest` in `STATE.md` and re-ran `:app:testDebugUnitTest` (SUCCESS, exit 0, `NO-SOURCE`).
  - Corrected `docs/ui-contract.md` for `AcceptSoulFact` (sets status to "active") and `inboxCount` (proposals + soul facts; stale items excluded pending R4).
  - Appended R1 notes to `docs/agent/work/session1-remediation-spec.md` (v5 migration assertions update, `MineDatabaseCallback.onUpgrade` trigger limitation recorded).
  - Logged trigger limitation task in `docs/agent/TASKS.md`.

## 2. Verified Working (with Evidence)

### Command: `./gradlew :core:test` (Exit code: 0)
72 unit tests executed, 72 passed, 0 failures, 0 skipped.
Last 15 lines of output:
```
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:494:115 The corresponding parameter in the supertype 'HttpTransport' is named 'target'. This may cause problems when calling this function with named arguments.
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:494:126 The corresponding parameter in the supertype 'HttpTransport' is named 'body'. This may cause problems when calling this function with named arguments.
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:521:47 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt:521:76 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/EndToEndChatTest.kt:67:34 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/EndToEndChatTest.kt:67:63 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/SystemPromptTest.kt:64:23 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
w: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/core/src/test/java/com/anchor/core/ui/SystemPromptTest.kt:64:52 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'

> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

[Incubating] Problems report is available at: file:///C:/Users/Banespire/AndroidStudioProjects/Mine/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 6s
4 actionable tasks: 4 executed
Configuration cache entry reused.
```

### Other Verified Builds:
- `./gradlew :app:assembleDebug`: SUCCESS (exit 0)
- `./gradlew :app:assembleRelease`: SUCCESS (exit 0, R8 minification + lintVitalRelease)
- `./gradlew :app:compileDebugAndroidTestKotlin`: SUCCESS (exit 0, compiles all 23 instrumented test methods across 6 files; re-run in R0b)
- `./gradlew :app:testDebugUnitTest`: SUCCESS (exit 0, NO-SOURCE; app/src/test contains no tests; re-run in R0c)
- `./gradlew :app:lintDebug`: SUCCESS (exit 0)

## 3. NOT Verified
- **Device Execution**: NOT VERIFIED on device since the physical test phone is offline (23 androidTest methods across 6 files in `app/src/androidTest` are compiled but unverified on real hardware).
- **D-105 Phone Gate**: Status-bar alarm clock icon display for Alarm-tier reminders is postponed while phone is offline. Task T-C remains open.
- **T-E (Calendar Queries) & T-F (Home Quotes)**: Unit-verified only.
- **Remediation Tasks R1 through R10**: Open, pending execution.
- **B5 Export UI**: SAF picker and passphrase dialog dark-shipped pending v1.1.

## 4. Operating Rules
- Commit per task using `git commit -s`.
- Never commit failing tests.
- One task per run; stop and await user approval before proceeding to the next task.

## 5. Next Step
- **Task R1**: Reschedule/cancel/complete after ambiguity (D-110, D-111).
  - First step: Read spec in `docs/agent/work/session1-remediation-spec.md` (R1 section), create branching analysis `docs/agent/work/r1.md`, and write failing tests first.
