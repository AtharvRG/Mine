---
name: adb-checks
description: Recipes for verifying Mine on the real phone.
---
- adb devices; adb install -r app/build/outputs/apk/debug/app-debug.apk
- adb shell dumpsys alarm | grep -A3 com.anchor.mine   (armed alarms and times)
- adb shell dumpsys notification --noredact | grep -B2 -A10 com.anchor.mine
- adb logcat -d -t 300 --pid=$(adb shell pidof com.anchor.mine)
- Force-stop test: schedule 2 min reminder, adb shell am force-stop com.anchor.mine, reopen app, check reconcile (note: force-stop clears alarms by design).
- Swipe-away test: do manually; ask me to confirm the notification appeared.
- Reboot test only with my approval; after boot, dumpsys alarm again.
- Doze: adb shell dumpsys deviceidle force-idle (then unforce-idle).
- Vibration cannot be tested (motor broken).
