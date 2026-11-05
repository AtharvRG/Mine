---
name: android-alarms
description: Rules and tests for reminders: setAlarmClock, reconcile, boot, sweeper, receiver fallback.
---
- Use setAlarmClock; requires USE_EXACT_ALARM declared in manifest on Android 14+ (throws SecurityException without it per D-102b). Do not add SCHEDULE_EXACT_ALARM, VIBRATE, WAKE_LOCK.
- Reminder lookup is by node_id column, never by string-concatenated ids.
- Arm only after the DB transaction commits. Registry (alarm_registry) is a hint, not truth: force-stop clears all alarms. Run reconcile at app start, boot, time/zone change, and after every commit. Compare fire time, not only ids.
- Receiver: tiny; reads the row by id; if DB unavailable, post a fallback notification from intent extras; never swallow silently.
- Sweeper: notify first, delete second, dedupe, and run on start/boot/periodic.
- Cancel/complete/undo must cancel the OS alarm. Reschedule must re-arm.
- Tests that matter: due reminder notifies and row removed; cancelled does not notify; missed on boot notifies "Missed"; reschedule fires at new time; reconcile after simulated force-stop; undo restores due_at and alarm.
