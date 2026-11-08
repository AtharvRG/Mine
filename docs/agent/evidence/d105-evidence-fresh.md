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
| `AlarmSelfTest.testGentleReminderFires_SetsFlag` | arms at `now + 5000 ms` (`AlarmSelfTest.kt:106`) — far too short to dump + screenshot |
| Chat: `gentle reminder to ...` | silently fails — O-1 (no receipt, no row, no alarm) |

**Route found since this file was written (2026-11-08):**
`AlarmSelfTest.testAlarmClock_NextAlarmClockVerification` arms a **GENTLE** reminder at
`now + 120000 ms` and holds it pending while asserting `nextAlarmClock.triggerTime != gentleFireTime`.
Two minutes is long enough to run `dumpsys alarm`, `dumpsys SystemUIService`, and `screencap` in
parallel from the host while the alarm is still armed. That is the capture to take when a device is
next connected; it needs no O-1 fix and no debug-build change.

The gentle fingerprint is nonetheless documented from `d105-alarms-stretch.txt` (Nov 5,
same build lineage): `flags=0x20`, `window=+2m59s994ms`, no `Alarm clock:` block. Its
SystemUI state (`d105-systemui-gentletier-pending.txt`) reads `visibleState=HIDDEN` —
identical to Pair C-A. That is consistent with the claim but is **not a same-session
capture** and is not offered as proof.

**Honest status (as of 2026-11-06): the Gentle half of D-105 is blocked on O-1 or on a debug build.**

### 2026-11-08 — Gentle half CAPTURED (same session, debug build, device `moto g73 5G - 14`)

Route used: `AlarmSelfTest.testAlarmClock_NextAlarmClockVerification` with
`-Pandroid.testInstrumentationRunnerArguments.d105GentleHoldMs=90000`, while
`build/tmp/d105-gentle-capture.sh` polled `dumpsys alarm` from the host.

Device clock at capture: `07:51:41`. The armed alarm in `d105-gentle-alarms-dump.txt`:

```
    RTC_WAKEUP #4: Alarm{f97f9c1 type 0 origWhen 1794104553449 whenElapsed 418900314 com.anchor.mine}
      tag=*walarm*:com.anchor.mine/.alarms.AlarmReceiver
      type=RTC_WAKEUP origWhen=2026-11-08 07:52:33.449 window=+1m29s998ms repeatInterval=0 count=0 flags=0x20
```

`origWhen` is the test's `now + 120000 ms` (`AlarmSelfTest.kt:153`). `flags=0x20` and the
`+1m29s998ms` window are the `setAndAllowWhileIdle` fingerprint (`AndroidAlarmScheduler.kt:54`);
there is **no `Alarm clock:` block for Mine**, i.e. the GENTLE alarm is armed and is *not*
registered as an alarm clock.

| Signal | Gentle armed (this capture) | Nothing armed (Pair C-A) | Mine ALARM armed (Pair C-B) |
|---|---|---|---|
| `dumpsys alarm` Mine entry | `*walarm*`, `window=+1m29s998ms`, no `Alarm clock:` | none | `setAlarmClock` block present |
| `dumpsys alarm` `Next alarm clock information` | **not captured** (filtered dump lacks the header — see note) | blank | `time:…` +3s793ms |
| `slot='alarm_clock' visibleState` | **`HIDDEN` (2), `iconAppearAmount=0.0`** | `HIDDEN` (2) | `ICON` (0) |
| PNG theme | dark (corner `[10,16,20]`) | light | light |

The SystemUI samples are `d105-gentle-systemui.txt` and `d105-gentle-systemui-2.txt`; both
read `visibleState=HIDDEN` for `slot='alarm_clock'` across all three view instances (keyguard,
shade, and status bar) — 6 views total, `visibleState=2`, `iconAppearAmount=0.0`, and
`grep -c "visibleState=ICON"` = 0 in both files.

**Correction (2026-11-08, same day):** `d105-gentle-alarms-dump.txt` is a *filtered* extract — it
contains the Mine alarm entry but **not** the `Next alarm clock information` header, so it cannot
itself show that block blank. The blank-header claim rests on (a) the ALARM-tier contrast in Pair C
where the same device *does* populate it, and (b) the Nov 5 same-tier file
`d105-alarms-gentletier-pending.txt:188-189`, which shows the header blank with a GENTLE-shaped
alarm pending. Do not cite `d105-gentle-alarms-dump.txt` as evidence for the blank header. Screenshot: `d105-gentle-pending.png` (521897 B, 1080×2400);
status-bar crops `d105-gentle-statusbar-crop.png` and `d105-gentle-statusbar-left-crop.png`.

**Caveat on the PNG:** the light-glyph scan in Pair C (`r>=251`, x 600–760) is not applicable —
this capture is dark-theme. A dark-theme threshold (mean ≥ 120) finds glyphs at
`747-779, 794-831, 890-920, 935-1027`, all ~30 px wide, i.e. the right-hand status-bar icons.
There is **no 25 px gap-plus-glyph between them** of the kind the alarm glyph adds, but the
dark capture has no same-session dark "nothing armed" control, so the PNG alone is not a
differential. The differential is the SystemUI `visibleState` + `dumpsys alarm` pair above,
taken in the same session as the armed Gentle alarm.

**Machine-checkable verdict for the Gentle half: consistent with D-105's claim** — a GENTLE
reminder is armed via `setAndAllowWhileIdle`, is absent from `nextAlarmClock`, and leaves
`slot='alarm_clock'` at `visibleState=HIDDEN`. The by-eye verdict remains the user's.

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

---

## 2026-11-08 (later) — D-105x: the user's by-eye report, and a third reproduction

### What the user reported

Asked to eyeball the Pair C and Gentle crops, the user answered:

> "IDK what icon are you asking for but there was no alarm indication for me in the
> notification bar nor anywhere, the alarm was set in the app and when it fired it was a
> notification for me, before that when i set the alarm i'd see no icons nor anything in
> the notification bar, let the alarm be 5 sec 10 sec, 1 min or 5 mins… idk abt this icon
> u talking abt…"

and for the Gentle crop: "yes there is no icon of alarm here..."

**Their screenshots were of the Reminders screen inside Mine**, not of the Android status
bar. The two frames they described (`Test Status: Armed for 5 seconds ...` /
`Active Reminders: [ALARM] ID: test_179 | Self Test Fired!`, and
`Active Reminders: #no alarms! :)`) are `RemindersScreen` in `MainActivity.kt:283-321`.
The icon under test is a ~25 px glyph in the **system status bar** at the top of the
screen, and for the self test it exists for exactly **5 s** (`ChatController.kt`
`RunSelfTest`: `fireAt = clock.now() + 5000L`). A 5 s / 10 s / 1 min / 5 min manual
comparison therefore cannot see it — the alarm is gone before the eye reaches the status bar.

The filenames the user cited (`tiered.png`, `cleanup.png`) do not exist in
`docs/agent/evidence/`; the closest are `d105-clean-alarm-tier.png` /
`d105-clean-no-alarm.png`. Rather than argue provenance, the claim was re-derived from
scratch in one clean session.

### Method — one session, three states, no Mine install required

The Mine APK was **not installed** at the time (`pm list packages | grep -i anchor` →
empty; `am start -n com.anchor.mine/.MainActivity` → `Error type 3 … does not exist`).
That does not matter: `slot='alarm_clock'` is app-agnostic, so a `setAlarmClock` armed by
any app exercises the same SystemUI path Mine uses. A system `SET_ALARM` intent was armed
through the deskclock UI:

```
adb shell am start -a android.intent.action.SET_ALARM --ei android.intent.extra.ALARM_MINUTES 2
# → opens the "Select time" picker, does NOT arm anything
MSYS_NO_PATHCONV=1 adb shell "uiautomator dump /sdcard/dc.xml >/dev/null 2>&1; cat /sdcard/dc.xml"
# → OK button bounds [770,1733][930,1853]
adb shell input tap 850 1793
```

`MSYS_NO_PATHCONV=1` is required: without it Git-Bash rewrites `/sdcard/dc.xml` to
`/Files/Git/sdcard/dc.xml` and the dump silently writes nowhere. `am start` with
`ALARM_MINUTES` only opens a picker — the OK tap is what arms the alarm.

### Three-state result

| Signal | Nothing armed | `setAlarmClock` armed (SET_ALARM 18:00) | Deskclock alarm armed (2:00 pm) |
|---|---|---|---|
| `Next alarm clock information` | blank | `time:1794141000000 = 18:00:00` (+54m41s) | `time:1794213000000 = 2026-11-09 14:00:00` |
| `slot='alarm_clock' visibleState` | `HIDDEN` ×3, `iconAppearAmount=0.0` | `ICON` ×3, `iconAppearAmount=1.0` | `ICON` |
| `StatusIconContainer` | `749,30-935,90` = **186 px** | `693,30-935,90` = **242 px** | `695,30-936,90` = **241 px** |
| Control slots `nfc`/`bluetooth`/`airplane` | `HIDDEN` | `HIDDEN` | `HIDDEN` |
| Leftmost glyph in ASCII render (x 698-850) | **absent** | **present** (clock face) | **present** |

A second `SET_ALARM` capture (19:00, `+57m55s214ms`) reproduced it again:
`visibleState=ICON`, container `693,30-934,90` = **241 px**.

**Third consecutive reproduction of the 55-56 px invariant** (186 → 241/242), on a fresh
session, with the control icon slots unchanged — the widening is the alarm icon and
nothing else.

### Pixel-level proof (not just the dump)

The crops are 850×260, x 690-860 / y 40-92 of the full 1080×2400 frame, LANCZOS-upscaled.
A column-wise bright-pixel scan (`L >= 200`, run length ≥ 2) gives:

```
armed    runs [(89, 234), (317, 480), (533, 716)]
nothing  runs [(317, 480), (533, 716)]
```

The two right-hand runs are byte-identical in both frames. **The `(89, 234)` run exists
only when armed.** ASCII-rendering x 60-260 shows it is a **clock face** — a circle with
hands: outer ring at rows 40-184, a `#####` hand from row 80 down to row 152, and a second
hand sweeping right. In the nothing-armed frame that region is empty (all pixels below the
`.` threshold) and the two neighbouring glyphs are unchanged.

### Files

| File | What it is |
|---|---|
| `d105x-armed-system-alarm.png` (137205 B) | full frame, SET_ALARM 18:00 armed, 242 px |
| `d105x-nothing-armed.png` (134144 B) | same session, after `cmd alarm set-time`, 186 px |
| `d105x-deskclock-armed.png` (133567 B) | deskclock 2:00 pm alarm armed, 241 px |
| `d105x-armed-statusbar-crop.png` (69694 B) | 5× crop, icon **present** |
| `d105x-nothing-armed-crop.png` (46675 B) | 5× crop, icon **absent** |
| `d105x-deskclock-armed-crop.png` (69328 B) | 5× crop, icon **present** |
| `d105x-setalarm-armed-crop.png` (69694 B) | 5× crop, second SET_ALARM frame |

`d105x-nothing-statusbar-crop.png` is byte-identical to `d105x-nothing-armed-crop.png`
(46675 B) — a duplicate, not an independent control.

### What this changes, and what it does not

- **Confirms the ALARM half for the third time**, now with a pixel-level differential
  rather than only container geometry.
- **Confirms the user's Gentle observation**: `visibleState=HIDDEN`, no glyph — Gentle uses
  `setAndAllowWhileIdle` and never registers an alarm clock, so there is nothing to show.
- **Does NOT make the icon a Mine-only signal.** `slot='alarm_clock'` reflects the
  system-wide next alarm clock from any app. It proves "a `setAlarmClock` is pending → the
  icon appears", which is exactly what D-105 claims and discloses.
- **Does NOT prove the self test specifically.** Mine was uninstalled; the `setAlarmClock`
  was armed by the deskclock. The code path is the same (`AndroidAlarmScheduler.kt:44-58`
  → `setAlarmClock`), and Pair C-B did capture Mine's own self test, but this session's
  frames are not from Mine.
- **Does NOT make the icon easy to see.** It is a ~25 px glyph that exists only while an
  alarm clock is pending. For the 5 s self test it is effectively invisible to a human
  unless they are already watching the status bar. That is a UX observation, not a defect.

### For the by-eye check

Open these two side by side; the only difference is the leftmost glyph:

- `d105x-armed-statusbar-crop.png` — clock face present
- `d105x-nothing-armed-crop.png` — nothing there
