# Current Task: R10g clean-install re-verification + D-105 fresh status-bar evidence

Status: R10g **Completed**. D-105 **evidence captured — awaiting user's by-eye verdict** (not final).
Final (HANDOFF + commits): **in progress**.

## Objective
1. **R10g** — clean-install the minified release APK on the physical phone and verify four capabilities live on current code: storage opens, a message is sent, a reminder fires, an export verifies. Save raw output under `docs/agent/evidence/`. The Nov 5 `r10g-evidence.md` was explicitly not accepted as final.
2. **D-105** — adb screenshots showing the status-bar alarm icon PRESENT for an Alarm-tier reminder and ABSENT for a Gentle reminder, saved under `docs/agent/evidence/`. Constraint: "Do not mark D-105 final; I check it by eye."
3. **Final** — HANDOFF block, full updated `03_CURRENT_TASK.md`, commits.

## Device & build under test
- moto g73 5G, Android 14 (API 34), 1080x2400, IST.
- Minified release APK, `versionName=1.0.0`, `versionCode=10000`.
- `firstInstallTime == lastUpdateTime == 2026-11-06 15:06:40` ⇒ clean install.
- No `DEBUGGABLE` flag; `initiatingPackageName=com.android.shell` (sideloaded via this adb session).
- `BUILD SUCCESSFUL in 5m 34s`; `Performing Streamed Install` / `Success`.

## R10g — results (4/4 PASS)

| Capability | Evidence | Result |
|---|---|---|
| Storage opens | `r10g-fresh-01-storage-opened.png` | PASS |
| A message is sent | `r10g-reply.xml` — live LLM reply via saved Mistral config (`r10g-chat-verified.xml`) | PASS |
| A reminder fires | `r10g-alarm-armed.txt` (`window=0 flags=0x3`, `Alarm clock:` block) + `r10g-notification-fired.txt` (`importance=4 channel=mine_reminders_v3 category=alarm`) + `r10g-notification-shade.xml` | PASS |
| Export verifies | `r10g-export-test.txt` — `tests 7 failures 0 errors 0` (gradle exit 1 = ROM teardown artifact) | PASS |

Full report: `docs/agent/evidence/r10g-fresh-evidence.md`.

### Permissions / receivers confirmed on the installed APK
- `USE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET` all `granted=true`.
- `BootReceiver` registered for `TIMEZONE_CHANGED`, `TIME_SET`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`.

## D-105 — evidence captured, NOT final

Method: `Run 5-sec Self Test` on the Reminders tab arms a deterministic `setAlarmClock` ~5 s out; capture `dumpsys alarm` + SystemUI + screenshot inside that window.

| Signal | Alarm tier pending | No alarm |
|---|---|---|
| `Next alarm clock information` | populated (`time:1793961054005 = +3s226ms`) | blank |
| `slot='alarm_clock' visibleState` | `ICON`, `iconAppearAmount=1.0` | `HIDDEN`, `iconAppearAmount=0.0` |
| `5:(alarm_clock) holder ... visible=` | `true` | `false` |
| `StatusIconContainer` bounds | `640,30-935,90` (295 px) | `695,30-935,90` (240 px) |

The 55 px container delta equals exactly one icon slot — an independent signal from the icon glyph itself.

Artifacts for the by-eye check:
- `docs/agent/evidence/d105-fresh-alarm-tier.png` (111531 B, 16:00)
- `docs/agent/evidence/d105-fresh-no-alarm.png` (98694 B, 16:01)

Full report: `docs/agent/evidence/d105-evidence-fresh.md`.

**D-105 is NOT marked final. The user checks these two PNGs by eye.**

## Defects found during this run (recorded, not fixed)

### O-1 — CREATE silently fails for `set alarm ...` phrasing
- Works: `remind me to stretch in 2 minutes` → `✓ Created: Stretch (Fri 6 Nov, 3:03 PM)` + armed `setAlarmClock`.
- Fails: `set alarm for drink water in 3 minutes`, `set alarm to buy milk in 3 minutes`, `read for 20 minutes`, `set alarm for water at 4pm` — all reply `Setting an alarm to X.` with **no receipt, no Active Reminders row, no armed alarm**.
- Reproduced live 3× this session (15:56:25, 15:57:53, 15:59:08).
- `set alarm for water at 4pm` also rewrote the title to "drink water" (prior-context bleed).
- Root cause **not determined**: minified release build emits zero app-level logcat, `run-as` fails with `package not debuggable`.
- Evidence: `d105-c36-reply.xml`, `d105-c44-reply.xml`, `d105-c-reply2.xml`, `d105-c41-state.xml`, `d105-after-milk-create.txt`.

### O-2 — Provider fields append instead of replace
- `r10g-chat-after-save.xml`: `https://api.openai.com/vhttps://api.mistral.ai/v1/chat/completions1/chat/completions`, `gpt-4o-minimistral-small-latest`.

## What is NOT verified
- D-105 by-eye verdict (user's call).
- O-1 root cause (requires a debug-build reproduction).
- Core/app/connected suites were not re-run this session (no source changed).

## Next Task
1. User checks `d105-fresh-alarm-tier.png` vs `d105-fresh-no-alarm.png` by eye → close D-105.
2. Triage O-1: reproduce `set alarm for drink water in 3 minutes` on a debug build and capture logcat.
3. Reconcile the test-count discrepancy (242 vs 247 vs 249 core) with one recount run.
