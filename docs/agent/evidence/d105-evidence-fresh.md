# D-105 fresh evidence — status-bar alarm icon (2026-11-06 16:00–16:01 IST)

**Status: NOT marked final. Verdict is the user's by-eye call.**

Device: moto g73 5G, Android 14 (API 34), 1080x2400, IST.
Build under test: minified release, `versionName=1.0.0`, `versionCode=10000`,
`firstInstallTime == lastUpdateTime == 2026-11-06 15:06:40`, `DEBUGGABLE` matches = 0.

Supersedes the Nov 5 `d105-evidence.md` for the icon question. That file's
`StatusIconContainer` delta was `681→736`; the fresh capture is `695→638`, a different
window and a different set of visible icons, so it cannot be cited as current.

---

## Method

The only LLM-free way to get an alarm-tier window is the Reminders tab button
**`Run 5-sec Self Test`** (bounds `[100,312][435,362]`, tapped at `(267,337)`), which
arms a deterministic `setAlarmClock` ~5 s out. Sequence per capture:

1. `adb shell input tap 405 177` (Reminders tab)
2. `adb shell input tap 267 337` (Run 5-sec Self Test)
3. `adb shell "dumpsys alarm | grep -A3 'Next alarm clock information'"`
4. `adb shell "dumpsys activity service com.android.systemui/.SystemUIService"`
5. `adb exec-out screencap -p > docs/agent/evidence/<name>.png`

Step 5 was run while the alarm was still pending (screenshot taken at
`+3s226ms` before trigger), so the icon is painted in the same window as the dump.

---

## Pair A — Alarm tier pending (icon PRESENT)

### `dumpsys alarm` — `Next alarm clock information` populated

```
  Next alarm clock information: 
    user:0 pendingSend:false time:1793961054005 = 2026-11-06 16:00:54.005 = +3s226ms
  
  110 pending alarms: 
```

### SystemUI — `StatusBarIconControllerImpl` icon views

```
        [2] icon=StatusBarIconView(slot='alarm_clock' alpha=1.0 icon=StatusBarIcon(icon=Icon(typ=RESOURCE pkg=com.android.systemui id=0x7f080b9a) visible user=0 ) visibleState=ICON iconColor=#0 visibleState=0 iconAppearAmount=1.0 translationZ=0.0 notification=null)
```

### SystemUI — `StatusBarIconList` holder

```
         5:(alarm_clock) holder=StatusBarIconHolder(type=ICON tag=0 idDual=false subId1=0 subId2=0 isErrorSim=false visible=true) 
```

### SystemUI — status icon container bounds

```
                com.android.systemui.statusbar.phone.StatusIconContainer{291a8df V.E...... ......ID 640,30-935,90 #7f0a086f app:id/statusIcons}
```

Width `935-640 = 295` px.

### Screenshot

`docs/agent/evidence/d105-fresh-alarm-tier.png` (111531 B)

---

## Pair B — No alarm pending (icon ABSENT)

Captured ~60 s later, after the self test fired and the alarm was consumed.

### `dumpsys alarm` — `Next alarm clock information` empty

```
  Next alarm clock information: 
  
  109 pending alarms: 
```

(The 109th alarm is `ELAPSED_WAKEUP ... com.facebook.services`, unrelated.)

### SystemUI — icon view

```
        [2] icon=StatusBarIconView(slot='alarm_clock' alpha=1.0 icon=StatusBarIcon(icon=Icon(typ=RESOURCE pkg=com.android.systemui id=0x7f080b9a) user=0 ) visibleState=HIDDEN iconColor=#0 visibleState=2 iconAppearAmount=0.0 translationZ=0.0 notification=null)
```

### SystemUI — container bounds

```
                com.android.systemui.statusbar.phone.StatusIconContainer{291a8df V.E...... .......D 695,30-935,90 #7f0a086f app:id/statusIcons}
```

Width `935-695 = 240` px — **55 px narrower than Pair A** (295 px), i.e. exactly one
icon slot. The right edge `935` is shared; the left edge moved `640 → 695`.

### Screenshot

`docs/agent/evidence/d105-fresh-no-alarm.png` (98694 B)

---

## Machine-checkable discriminator

| Signal | Alarm tier pending | No alarm |
|---|---|---|
| `Next alarm clock information` | populated (`time:1793961054005 = +3s226ms`) | blank |
| `slot='alarm_clock' ... visibleState` | `ICON` (int 0), `iconAppearAmount=1.0` | `HIDDEN` (int 2), `iconAppearAmount=0.0` |
| `5:(alarm_clock) holder ... visible=` | `true` | `false` |
| `StatusIconContainer` width | 295 px (`640,30-935,90`) | 240 px (`695,30-935,90`) |

The container widens by **55 px** (`695→640` on the left edge) when the alarm icon is
present, then narrows back to `695` once the alarm is consumed. 55 px ≈ one icon slot at
this density, which corroborates the `visibleState` flip independently of the icon glyph
itself.

## Gentle tier (from `d105-alarms-stretch.txt`, Nov 5, same build lineage)

```
      tag=*walarm*:com.anchor.mine/.alarms.AlarmReceiver
      type=RTC_WAKEUP origWhen=2026-11-06 15:25:43.459 window=+2m59s994ms repeatInterval=0 count=0 flags=0x20
      whenElapsed=+3m53s834ms maxWhenElapsed=+6m53m828ms
```

`flags=0x20` with a `+2m59s994ms` window and no `Alarm clock:` block is the gentle
fingerprint (`setAndAllowWhileIdle`). SystemUI for gentle shows `visibleState=HIDDEN`
(`d105-systemui-gentletier-pending.txt`), i.e. identical to Pair B above.

---

## Pair C — clean control pair (2026-11-06 19:41 IST)

### Why Pairs A/B needed a replacement

`slot='alarm_clock'` is **app-agnostic**: it reflects the system-wide next alarm clock,
set by `AlarmManager.setAlarmClock` from *any* app. On this device
`com.google.android.deskclock` held `setAlarmClock` alarms at 19:50 and 20:00 — **these
were the user's own alarms, deliberately set aside for their own use**, not an unrelated
app. Because they were pending system-wide, the icon read `ICON` even with zero Mine
reminders armed. A "no alarm" control taken before 20:00 is therefore invalid.

(Recorded correction 2026-11-06: an earlier revision of this file described those alarms
as "contamination from an unrelated app". That framing was wrong — they were the user's
own. The technical conclusion, that the icon is app-agnostic and therefore cannot be used
as a Mine-only signal, is unaffected.)

**Invalid attempt, retained for the record:** `d105-ctl-alarm-tier.png` (111641 B) and
`d105-ctl-after-fire.png` (99362 B). Both scan identically (light glyph columns from
x=690, count 30) because the user's own deskclock alarm clock was still pending in both. The
after-fire capture is **INVALID as a no-alarm control** and must not be cited.

### How the clean pair was obtained

`cmd alarm` exposes only `set-time`, `set-timezone`, `get-config-version` — there is no
`cancel`. The clock was advanced past the user's own deskclock alarms instead:

```
adb shell cmd alarm set-time 1793974790000   # 19:39:50
adb shell cmd alarm set-time 1793975390000   # 20:00:02
```

### Pair C-A — nothing armed (icon ABSENT)

```
  Next alarm clock information: 
  
  113 pending alarms: 
```

```
        [2] icon=StatusBarIconView(slot='alarm_clock' ... visibleState=HIDDEN iconColor=#0 visibleState=2 iconAppearAmount=0.0 ...)
                com.android.systemui.statusbar.phone.StatusIconContainer{291a8df ... 733,30-930,90 #7f0a086f app:id/statusIcons}
```

Width `930-733 = 197` px. Screenshot `d105-clean-no-alarm.png` (98417 B).
Pixel scan (x 600–760, y 30–95, `r>=251`): light columns start at **x=742, count=18**.

### Pair C-B — Mine alarm tier armed via `Run 5-sec Self Test` (icon PRESENT)

```
  Next alarm clock information: 
    user:0 pendingSend:false time:1793975426370 = 2026-11-06 20:00:26.370 = +3s793ms
  
  112 pending alarms: 
```

```
        [2] icon=StatusBarIconView(slot='alarm_clock' ... visibleState=ICON iconColor=#0 visibleState=0 iconAppearAmount=1.0 ...)
                com.android.systemui.statusbar.phone.StatusIconContainer{291a8df V.E...... ......ID 678,30-930,90 #7f0a086f app:id/statusIcons}
```

Width `930-678 = 252` px. Screenshot `d105-clean-alarm-tier.png` (111140 B).
Pixel scan: light columns start at **x=690, count=50**.

### Pair C discriminator

| Signal | Mine alarm armed | Nothing armed |
|---|---|---|
| `Next alarm clock information` | `time:1793975426370 = +3s793ms` | blank |
| `slot='alarm_clock' visibleState` | `ICON` (0), `iconAppearAmount=1.0` | `HIDDEN` (2), `iconAppearAmount=0.0` |
| `StatusIconContainer` | `678,30-930,90` = **252 px** | `733,30-930,90` = **197 px** |
| Light glyph columns (x 600–760) | start **690**, count **50** | start **742**, count **18** |

**The 55 px delta reproduces exactly** — `295→240` in Pairs A/B and `252→197` in Pair C,
at two different absolute container positions. The delta is the invariant; the absolute
left edge depends on which other status-bar icons are present.

Pair C is the first pair in which the "absent" half is provably free of any other
`setAlarmClock` (including the user's own deskclock alarms).

---

## Gentle tier — NOT captured, and not reachable on this build

D-105's spec asks for the icon **absent for a Gentle reminder**. That half is **not
satisfied** by any capture in this file, and cannot be produced on the current minified
release build:

| Route to a Gentle reminder | Why it fails |
|---|---|
| `Run 5-sec Self Test` (Reminders tab) | ALARM tier only — hardcoded `setAlarmClock` path |
| `AlarmSelfTest.testGentleReminderFires_SetsFlag` | arms at `now + 1500 ms` — far too short to dump + screenshot |
| Chat: `gentle reminder to ...` | silently fails — O-1 (no receipt, no row, no alarm) |

The gentle fingerprint is nonetheless documented from `d105-alarms-stretch.txt` (Nov 5,
same build lineage): `flags=0x20`, `window=+2m59s994ms`, no `Alarm clock:` block. Its
SystemUI state (`d105-systemui-gentletier-pending.txt`) reads `visibleState=HIDDEN` —
identical to Pair C-A. That is consistent with the claim but is **not a same-session
capture** and is not offered as proof.

**Honest status: the Gentle half of D-105 is blocked on O-1 or on a debug build.**

## What this does NOT prove

- The screenshots are full-screen captures; the icon is a ~25 px glyph in the status bar.
  Whether it is legible to a human eye in the PNG is the user's call — this report only
  proves the SystemUI view state.
- **The Gentle half is not proven at all.** No same-session Gentle capture exists; see the
  section above. Pairs A/B/C all contrast ALARM-tier against *nothing armed*.
- Pair C-A is "nothing armed", not "a Gentle alarm armed". They are expected to be
  identical (both leave `slot='alarm_clock'` unset), but that expectation is untested here.

## Verdict

Machine-checkable evidence for the **ALARM half**: **consistent with D-105's claim** —
an alarm-tier reminder raises `slot='alarm_clock'` to `visibleState=ICON` and widens
`StatusIconContainer` by 55 px; both revert when the alarm is consumed or absent.
Reproduced in two independent sessions (Pairs A/B and Pair C).

Machine-checkable evidence for the **GENTLE half**: **none this session.** Blocked on O-1
or a debug build.

Task D-105 remains **open** pending the user's by-eye check of
`d105-clean-alarm-tier.png` vs `d105-clean-no-alarm.png` (Pair C — prefer these over the
Pair A/B PNGs, whose "absent" half could not rule out the user's own deskclock alarms).
