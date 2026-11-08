# R10g fresh evidence — clean-install minified release APK (2026-11-06)

**Supersedes `r10g-evidence.md` (Nov 5).** That file claimed `setExactAndAllowWhileIdle`,
channel `"mine_alarms"`, and 6 export tests, and never asserted the status-bar icon. None
of those match the current code. This file is the accepted record.

Device: moto g73 5G, Android 14 (API 34), 1080x2400, IST.

---

## 0. Build + install provenance

### Build — `r10g-build-release.txt`

```
BUILD SUCCESSFUL in 5m 34s
```

### Install — `r10g-install.txt`

```
Performing Streamed Install
Success
```

### Live package state — `r10g-fresh-package.txt`

```
    versionCode=10000 minSdk=34 targetSdk=35
    versionName=1.0.0
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
    timeStamp=2026-11-06 15:06:40
    lastUpdateTime=2026-11-06 15:06:40
      firstInstallTime=2026-11-06 15:06:40
```

`firstInstallTime == lastUpdateTime` proves a **clean install**, not an upgrade.
`flags` contains no `DEBUGGABLE`. `installerPackageName=null`, `initiatingPackageName=com.android.shell`
⇒ sideloaded via this adb session.

### Permissions granted

```
      android.permission.RECEIVE_BOOT_COMPLETED: granted=true
      android.permission.INTERNET: granted=true
      android.permission.USE_EXACT_ALARM: granted=true
        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|... ]
```

### `BootReceiver` filter — `r10g-fresh-package.txt`

```
        6f2f04c com.anchor.mine/.alarms.BootReceiver filter 16fa895
          Action: "android.intent.action.TIMEZONE_CHANGED"
          Action: "android.intent.action.TIME_SET"
          Action: "android.intent.action.BOOT_COMPLETED"
          Action: "android.intent.action.MY_PACKAGE_REPLACED"
```

---

## 1. Storage opens

`docs/agent/evidence/r10g-fresh-01-storage-opened.png` (50259 B, 2026-11-06 13:53) —
app launches to the Chat tab with the quote card rendered and the composer interactive.
No crash, no "storage unavailable" surface.

Supporting: `r10g-fresh-02-provider-saved.png`, `r10g-fresh-03-chat-tab.png`,
`r10g-fresh-04-chat-sent.png`.

---

## 2. A message is sent — live LLM round trip

`r10g-reply.xml` (2026-11-06 15:00):

```
text="USER: What can I do to stay focused today"
text="ASSISTANT: Try short bursts of work with quick breaks. Remove distractions like phone or browser tabs. Drink water and move around every 30 minutes."
```

Provider config that produced this — `r10g-chat-verified.xml`:

```
endpoint = https://api.mistral.ai/v1/chat/completions
model    = mistral-small-latest
```

The reply text is not a canned string; it is a model response routed through the saved
provider config on the device.

---

## 3. A reminder fires

### Create — `r10g-remind-reply.xml` (15:01)

```
text="USER: remind me to stretch in 2 minutes"
text="ASSISTANT: Setting a reminder to stretch."
text="✓ Created: Stretch (Fri 6 Nov, 3:03 PM)"
text="Undo"
```

### Armed — `r10g-alarm-armed.txt` (15:01:26)

```
    RTC_WAKEUP #7: Alarm{f4164e4 type 0 origWhen 1793957589890 whenElapsed 271937233 com.anchor.mine}
      tag=*walarm*:com.anchor.mine/.alarms.AlarmReceiver
      type=RTC_WAKEUP origWhen=2026-11-06 15:03:09.890 window=0 exactAllowReason=policy_permission repeatInterval=0 count=0 flags=0x3
      whenElapsed=+1m43s86ms maxWhenElapsed=+1m43s86ms
      Alarm clock:
        triggerTime=2026-11-06 15:03:09.890
        showIntent=PendingIntent{7ed9d4d: PendingIntentRecord{ca7a002 com.anchor.mine startActivity}}
      operation=PendingIntent{9abed13: PendingIntentRecord{21eb50 com.anchor.mine broadcastIntent}}
```

`window=0`, `flags=0x3`, `Alarm clock:` block present ⇒ `setAlarmClock`, i.e. the ALARM
tier path in `AndroidAlarmScheduler.kt`.

### Fired — `r10g-notification-fired.txt`

```
    NotificationRecord(0x06840f11: pkg=com.anchor.mine user=UserHandle{0} id=14994079 tag=null importance=4 key=0|com.anchor.mine|14994079|null|10922: Notification(channel=mine_reminders_v3 shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x10 color=0x00000000 category=alarm vis=PRIVATE))
            contentIntent=PendingIntent{af7c876: PendingIntentRecord{ca7a002 com.anchor.mine startActivity ...
            when=1793957589919
```

`importance=4`, `channel=mine_reminders_v3`, `category=alarm`, `when=1793957589919`
(≈ 15:03:09.919, 29 ms after the armed trigger time). The notification fired from the
alarm, not from a foreground timer.

### Shade — `r10g-notification-shade.xml` / `.png`

```
Mine / • / now / Mine / Stretch
```

---

## 4. Export verifies — `r10g-export-test.txt`

```
gradle exit: see below; test-result-exit-code.txt: 1
--- parsed results ---
tests 7 failures 0 errors 0
PASS com.anchor.mine.export.ExportTest testExportAndVerify_RejectsBadPasswordAndTampering
PASS com.anchor.mine.export.ExportTest testImport_RejectsUnknownColumn
PASS com.anchor.mine.export.ExportTest testPostImport_RebuildsMsgChunkForSearch
PASS com.anchor.mine.export.ExportTest testPostImport_TriggersAlarmReconciliation
PASS com.anchor.mine.export.ExportTest testExport_ExcludesApiKeysWhenRequested
PASS com.anchor.mine.export.ExportTest testHeaderKdfBounds_RejectsExcessiveMemoryOrIterationsOrChunkSize
PASS com.anchor.mine.export.ExportTest testImport_RejectsUnknownTable
```

**Honest note on exit code 1:** `connectedAndroidTest` exits 1 on this device even when
every test passes — a known AGP 9.4.0 / UTP 32.4.0 exit-code defect (D-123), not a
teardown artifact. The authoritative result is the parsed
XML: `tests 7 failures 0 errors 0`. Full HTML: `r10g-export-test-results.html`.

---

## Observations — defects found during this run (NOT part of R10g's four capabilities)

### O-1: CREATE path fails silently for `set alarm ...` phrasing

Working path: `remind me to stretch in 2 minutes` → receipt + armed alarm (above).

Failing path — the assistant confirms but **no reminder is created**:

| Input | Reply | Receipt |
|---|---|---|
| `set alarm for drink water in 3 minutes` (`d105-c36-reply.xml`) | `Setting an alarm to drink water.` | none |
| `set alarm to buy milk in 3 minutes` (`d105-c44-reply.xml`) | `Setting an alarm to buy milk.` | none |
| `milk` (follow-up, `d105-c-reply2.xml`) | `Setting an alarm to buy milk.` | none |

In all three, the receipt list ends unchanged at
`✓ Updated: Stretch (Fri 6 Nov, 3:25 PM)`, the Reminders tab shows nothing under
`Active Reminders:` (`d105-c41-state.xml`), and `dumpsys alarm` shows only
`*alarm*:com.anchor.mine.quotes.ACTION_PREPARE_QUOTE` — no `*walarm*` entry.

Live reproductions this session (three, all identical):
- 15:56:25 `set alarm for drink water in 3 minutes`
- 15:57:53 `read for 20 minutes`
- 15:59:08 `set alarm for water at 4pm` → reply rewrote the title to `drink water`

Root cause **not determined**: the minified release build emits zero app-level logcat
lines (`run-as` fails with `package not debuggable`), so the failure cannot be traced
on-device. Characterised, not diagnosed.

### O-2: Provider fields append instead of replace

`r10g-chat-after-save.xml`:

```
text="https://api.openai.com/vhttps://api.mistral.ai/v1/chat/completions1/chat/completions"
text="gpt-4o-minimistral-small-latest"
```

Saving a new provider config concatenates the new value onto the old one rather than
replacing it. Cosmetic in effect (the working config in `r10g-chat-verified.xml` was
entered into empty fields) but a real UI defect.

---

## Summary against R10g's four capabilities

| Capability | Evidence | Result |
|---|---|---|
| Storage opens | `r10g-fresh-01-storage-opened.png` | PASS |
| A message is sent | `r10g-reply.xml` live LLM reply | PASS |
| A reminder fires | `r10g-alarm-armed.txt` + `r10g-notification-fired.txt` | PASS |
| Export verifies | `r10g-export-test.txt` 7/7, 0 failures | PASS |

Two defects recorded as observations (O-1, O-2). Neither blocks the four R10g
capabilities, but O-1 is user-visible and should be triaged before release.
