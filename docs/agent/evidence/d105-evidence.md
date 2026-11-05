# D-105 Verification Evidence Report: Status-Bar Alarm Icon (Alarm Tier vs Gentle Tier)

**Target Device**: Motorola moto g73 5G (`10.175.15.5:5555`)  
**Android OS Version**: Android 14 (API 34)  
**Build**: Release APK (`com.anchor.mine`, Version 1.0.0, Code 10000)  
**Target Specification**: D-105 — Status-bar alarm icon presence for `AlarmTier.ALARM` and its absence for `AlarmTier.GENTLE`.  
**Date**: 2026-11-05  

---

## Executive Summary

Per specification **D-105**:
- An `AlarmTier.ALARM` reminder uses `AlarmManager.setAlarmClock()`, which registers with the Android OS alarm clock system and causes the Android SystemUI status bar to display the system alarm clock icon.
- An `AlarmTier.GENTLE` reminder uses `AlarmManager.setAndAllowWhileIdle()`, which acts as an exact wake-up alarm but intentionally does NOT register as an OS alarm clock, leaving the status bar without the alarm clock icon.

Both tiers were scheduled live on the physical test device (Motorola moto g73 5G). SystemUI dumpsys and AlarmManager state were verified, and full device screenshots were captured via adb.

---

## 1. Alarm Tier Verification (`AlarmTier.ALARM`)

### Implementation
- Method: `AndroidAlarmScheduler.schedule(reminder)`
- API: `AlarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAtMillis, pendingShow), pendingIntent)`

### Device State Evidence
- **AlarmManager Dumpsys** (`dumpsys alarm`):
  ```
  Next alarm clock information: 
    user:0 pendingSend:false time:1793883049088 = 2026-11-05 18:20:49.088 = +9m55s755ms
  
  110 pending alarms: 
    RTC #1: Alarm{ae6337 type 1 origWhen 1793839849088 whenElapsed 196801443 com.android.systemui}
      tag=*alarm*:lock_screen_next_alarm
  ```

- **SystemUI Dumpsys** (`dumpsys activity service com.android.systemui`):
  ```
  StatusBarIconView(slot='alarm_clock' alpha=1.0 icon=StatusBarIcon(...) visible user=0 ) visibleState=ICON visibleState=0 iconAppearAmount=1.0
  5:(alarm_clock) holder=StatusBarIconHolder(type=ICON tag=0 idDual=false subId1=0 subId2=0 isErrorSim=false visible=true)
  StatusIconContainer{291a8df V.E...... ......ID 681,30-933,90 #7f0a086f app:id/statusIcons}
  ```
  Notice that `slot='alarm_clock'` transitioned to `visibleState=ICON` (`visibleState=0`), `visible=true`, and `StatusIconContainer` expanded its left boundary from `736` to `681` to display the alarm clock icon.

### Visual Evidence
- **Full Screen Screenshot**: `docs/agent/evidence/d105-alarm-icon.png`
- **Status Bar Crop**: `docs/agent/evidence/d105-alarm-statusbar-crop.png`

---

## 2. Gentle Tier Verification (`AlarmTier.GENTLE`)

### Implementation
- Method: `AndroidAlarmScheduler.schedule(reminder)`
- API: `AlarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)`

### Device State Evidence
- **AlarmManager Dumpsys** (`dumpsys alarm`):
  ```
  Next alarm clock information: 
  
  108 pending alarms:
  ```
  No next alarm clock registered.

- **SystemUI Dumpsys** (`dumpsys activity service com.android.systemui`):
  ```
  StatusBarIconView(slot='alarm_clock' alpha=1.0 icon=StatusBarIcon(...) visibleState=HIDDEN visibleState=2 iconAppearAmount=0.0
  5:(alarm_clock) holder=StatusBarIconHolder(type=ICON tag=0 idDual=false subId1=0 subId2=0 isErrorSim=false visible=false)
  StatusIconContainer{291a8df V.E...... ......ID 736,30-933,90 #7f0a086f app:id/statusIcons}
  ```
  Notice that `slot='alarm_clock'` transitioned to `visibleState=HIDDEN` (`visibleState=2`), `visible=false`, and `StatusIconContainer` contracted from `681` back to `736`.

### Visual Evidence
- **Full Screen Screenshot**: `docs/agent/evidence/d105-gentle-no-icon.png`
- **Status Bar Crop**: `docs/agent/evidence/d105-gentle-statusbar-crop.png`

---

## 3. Side-by-Side Status Bar Comparison

- **Combined Comparison**: `docs/agent/evidence/d105-statusbar-comparison.png`
  - **Top**: `AlarmTier.ALARM` — Alarm clock icon visible in status bar next to battery/network cluster.
  - **Bottom**: `AlarmTier.GENTLE` — Alarm clock icon absent from status bar.

---

## Conclusion

Task **D-105** is verified live on the physical test hardware (Motorola moto g73 5G, Android 14) with both system dumpsys logs and adb raw screenshots confirming presence of the status-bar alarm icon for `AlarmTier.ALARM` and its absence for `AlarmTier.GENTLE`.
