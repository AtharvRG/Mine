---
description: Full verification pass with evidence
---
Run: ./gradlew :core:test, :app:assembleDebug, :app:connectedDebugAndroidTest (if a device is attached), and once per milestone the minified release build. Then use android-device to check: notification after force-stop, alarm survives reboot (only if I approved), dumpsys alarm matches the DB. Report exit codes and key lines. Anything not run is "NOT VERIFIED". Do not edit code.
