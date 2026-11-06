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

Width `935-695 = 240` px — **same container bounds as Pair A**.

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

## What this does NOT prove

- The screenshots are full-screen captures; the icon is a ~25 px glyph in the status bar.
  Whether it is legible to a human eye in the PNG is the user's call — this report only
  proves the SystemUI view state.
- Pair B is "no alarm pending", not "a gentle alarm pending" captured in the same session.
  The gentle-tier SystemUI state is cited from the Nov 5 file, which is the same build
  lineage but not the same capture window.

## Verdict

Machine-checkable evidence: **consistent with D-105's claim** — alarm-tier reminders
raise `slot='alarm_clock'` to `visibleState=ICON`, and it returns to `HIDDEN` when the
alarm is consumed.

Task D-105 remains **open** pending the user's by-eye check of
`d105-fresh-alarm-tier.png` vs `d105-fresh-no-alarm.png`.
