---
name: verify
description: Full verification pass with evidence. Triggers /verify slash command.
---

# Full Verification Pass

Perform a full verification pass across core and app test suites and device checks.

## Instructions

1. Run:
   - `./gradlew :core:test`
   - `./gradlew :app:assembleDebug`
   - `./gradlew :app:connectedDebugAndroidTest` (if a device is attached)
   - Once per milestone: minified release build.
2. Use `android-device` subagent to check:
   - Notification after force-stop
   - Alarm survives reboot (only if user approved)
   - `dumpsys alarm` matches the database
3. Report exit codes and key output lines.
4. Anything not run must be explicitly labeled "NOT VERIFIED".
5. Do not edit code during this check.
