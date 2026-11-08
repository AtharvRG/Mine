# B5 Verification Evidence Report: BootReceiver Action Filter + Exported Flag

**Target Device**: Motorola moto g73 5G (`10.175.15.5:5555`)
**Android OS Version**: Android 14 (API 34)
**Build**: Debug APK (`com.anchor.mine`, Version 1.0.0, Code 10000, built 2026-11-06 12:12, installed 12:57:20 via `adb install -r`)
**Target Specification**: B5 — BootReceiver must (1) declare an intent-filter limited to the intended system actions, (2) be `exported=true` (required on API 31+ for manifest receivers), and (3) ignore any non-intended broadcast with no side effects.
**Date**: 2026-11-06

---

## Executive Summary

Three independent evidence sources confirm the B5 contract:

1. **Installed-APK manifest** (`aapt2 dump xmltree`): `BootReceiver` is declared `exported=true` with a 4-action intent-filter (BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED). Two other receivers (`AlarmReceiver`, `QuoteAlarmReceiver`) are `exported=false`.
2. **Device package manager** (`dumpsys package com.anchor.mine`): the Receiver Resolver Table shows `.alarms.BootReceiver` registered under all four actions (single filter `cc5b617`), and `RECEIVE_BOOT_COMPLETED` is `granted=true`.
3. **Device behaviour test** (`BootReceiverIgnoreTest`, 1/1 PASS per JUnit XML, 2.249s): non-intended broadcasts (custom action, unrelated protected action, empty action) produce no sweep, no notification, no missed-fire record, and leave the canary reminder row intact; the intended action (BOOT_COMPLETED) does sweep the stale canary.

---

## 1. Manifest Declaration (installed APK)

Source: `aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk`
(raw output: `docs/agent/evidence/b5-manifest-dump.txt`)

```
E: receiver (line=52)
  A: android:name="com.anchor.mine.alarms.BootReceiver"
  A: android:exported=true
    E: intent-filter (line=55)
      E: action (line=56)  android:name="android.intent.action.BOOT_COMPLETED"
      E: action (line=57)  android:name="android.intent.action.MY_PACKAGE_REPLACED"
      E: action (line=58)  android:name="android.intent.action.TIME_SET"
      E: action (line=59)  android:name="android.intent.action.TIMEZONE_CHANGED"
```

- `AlarmReceiver` and `QuoteAlarmReceiver` are declared `exported=false` (no intent-filter; only reached via explicit in-app broadcasts/PendingIntents).
- All four filtered actions are **protected broadcasts** (sendable only by the system), so `exported=true` does not expose a spoofable entry point on stock Android; the in-code guard (`BootReceiver.kt:20-21`) is defence-in-depth and matches the manifest set exactly.
- `MY_PACKAGE_REPLACED` is delivered only to the app's own package, and `TIME_SET`/`TIMEZONE_CHANGED`/`BOOT_COMPLETED` are reserved by the platform.

## 2. Device Package Manager Registration

Source: `adb shell dumpsys package com.anchor.mine` after reinstall
(raw output: `docs/agent/evidence/b5-receiver-dump.txt`, 8652 bytes)

Receiver Resolver Table (Non-Data Actions) — `.alarms.BootReceiver` (filter `cc5b617`) is registered under each of the four actions:

```
android.intent.action.TIMEZONE_CHANGED:      d3e9a96 com.anchor.mine/.alarms.BootReceiver filter cc5b617
android.intent.action.TIME_SET:              d3e9a96 com.anchor.mine/.alarms.BootReceiver filter cc5b617
android.intent.action.BOOT_COMPLETED:        d3e9a96 com.anchor.mine/.alarms.BootReceiver filter cc5b617
android.intent.action.MY_PACKAGE_REPLACED:   d3e9a96 com.anchor.mine/.alarms.BootReceiver filter cc5b617
```

Permissions: `android.permission.RECEIVE_BOOT_COMPLETED: granted=true` (install permissions block).

Note: this ROM's `dumpsys package` output does not print per-component `exported=` ActivityInfo lines, which is why the manifest dump (source 1) is the authoritative evidence for the exported flag. `cmd package query-receivers -a android.intent.action.BOOT_COMPLETED` additionally lists `com.anchor.mine/.alarms.BootReceiver` (Receiver #231) among installed receivers.

## 3. Behaviour Test on Device

Source: JUnit XML `app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml`
(test file: `app/src/androidTest/java/com/anchor/mine/alarms/BootReceiverIgnoreTest.kt`)

`testBootReceiver_IgnoresNonIntendedActions_NoSideEffects` — **PASS** (1 test, 0 failures, 0 errors, 2.249s). Gradle exited 1 with "There were failing tests" — the known AGP 9.4.0 / UTP 32.4.0 exit-code defect (D-123, `docs/agent/work/connected-red-build-rootcause.md`); verdict is taken from the JUnit XML only.

The test proves, against the live app storage on device:

- `Intent("com.anchor.mine.FAKE_ACTION")`, `Intent(Intent.ACTION_POWER_CONNECTED)`, and `Intent("")` each produce **no** alarm fire (`AlarmReceiver.lastFiredId` null), **no** notification, **no** missed-fire record (`hasReminderFire(rId, "missed")` false), and the canary reminder row survives.
- Control: `Intent(Intent.ACTION_BOOT_COMPLETED)` does sweep the stale canary reminder (>60s old) and deletes its row — i.e. the receiver works for intended actions and ignores everything else.

---

## Conclusion

B5 is verified on the physical device (Motorola moto g73 5G, Android 14): the manifest intent-filter is limited to the four intended system actions, `exported=true` is confirmed from the installed APK's compiled manifest (with `RECEIVE_BOOT_COMPLETED` granted on device), and the ignore-guard for non-intended broadcasts is proven by a passing on-device behaviour test.
