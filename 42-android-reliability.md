
# Android Background Reliability

Status: v1     Version: 0.1     Depends on: 30-reminder-engine, 31-nudge-engine, 41-offline-fallback, 16-security-privacy

## 1. Purpose

Reminders are the core promise, and Android (especially vendor builds) works against background apps. This file defines the platform
rules the app follows, the permissions it needs, the reliability screen that tells the user the truth, and the per-vendor guidance.
Several behaviors differ by Android version and manufacturer, so everything marked **verify** must be checked on real devices before release.

## 2. Behavior (what the user sees)

- A **Reliability** screen with a score and fix buttons: notifications allowed, alarms allowed, battery restrictions, vendor settings.
- A **Test reminders now** button: schedules an alarm one minute ahead, shows whether it fired and how late.
- A first-run guide, shown once, with steps matching the user's phone brand.
- A warning banner if reliability problems are detected later (missed reminder, large delay, force-stop detected).
- Notification channels the user can tune individually: chat, reminders, check-ins, and `mine_ambient_v1`.

## 3. Design

### 3.1 Principles

1. **No persistent service in normal operation.** Reminders and nudges use AlarmManager and WorkManager only. A foreground service exists only while ambient listening is on (v3).
2. **Alarm receivers are tiny.** They post the notification first, do database work second, and hand anything slow to expedited work.
3. **Layered arming** (`30-reminder-engine.md` 3.6): alarm, re-arm triggers, sweeper, self-test.
4. **Measure, do not assume.** Fire latency is recorded, and the user is told when the phone is unreliable.
5. **Be honest.** Some vendors can still kill the app. The app says so and explains how to reduce the risk.

### 3.2 Permissions (v1)

| Permission                                                                                                                                                                                     | Why                                        | When requested                                                                                                                                                                                                                                                                                                      |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `INTERNET`, `ACCESS_NETWORK_STATE`                                                                                                                                                         | Provider calls, connectivity state         | Install time                                                                                                                                                                                                                                                                                                        |
| `POST_NOTIFICATIONS` (Android 13+)                                                                                                                                                           | Show anything                              | During onboarding, with a rationale screen                                                                                                                                                                                                                                                                          |
| Exact alarms: `setAlarmClock` needs none. `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` for `notify` tier exactness                                                                      | Time-accurate reminders                    | Check `canScheduleExactAlarms()` at runtime. From Android 14 it is not pre-granted for new installs, so the user is sent to the system screen. **Verify** whether `USE_EXACT_ALARM` suits a sideloaded app (it is intended for alarm and calendar apps, and Play restricts it, which does not apply here) |
| `RECEIVE_BOOT_COMPLETED`                                                                                                                                                                     | Re-arm alarms after reboot                 | Install time                                                                                                                                                                                                                                                                                                        |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`                                                                                                                                                       | Ask to be exempt from battery optimization | First-run guide, user-initiated                                                                                                                                                                                                                                                                                     |
| `USE_FULL_SCREEN_INTENT`                                                                                                                                                                     | Full-screen alarm over the lock screen     | From Android 14 it is auto-granted only to calling and alarm apps. **Verify** the behavior for a sideloaded app. If denied, fall back to a heads-up notification plus sound                                                                                                                                   |
| `VIBRATE`, `WAKE_LOCK`                                                                                                                                                                     | Alarm feedback                             | Install time                                                                                                                                                                                                                                                                                                        |
| `USE_BIOMETRIC`                                                                                                                                                                              | Optional app lock                          | When the user enables the lock                                                                                                                                                                                                                                                                                      |

`ACTIVITY_RECOGNITION`, location permissions, `READ_PHONE_STATE` / optional call-log and contacts, `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, and `foregroundServiceType="microphone"` are requested just-in-time only when the feature is switched on, with a plain explanation first.

### 3.3 Notification channels

Android freezes a channel's behavior after creation, so channels carry a version suffix (`_v1`), and a change in defaults means a new channel id.

| Channel                                                                                                                                                                              | Importance   | Sound                                           | Used for                                            |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------ | ----------------------------------------------- | --------------------------------------------------- |
| `mine_reminders_v1`                                                                                                                                                                | High         | Alarm sound, vibration (alarm audio attributes) | Reminders and alarms                                |
| `mine_chat_v1`                                                                                                                                                                     | High         | Notification sound                              | Nudges, replies, quick-reply threads                |
| `mine_checkins_v1`                                                                                                                                                                 | Default      | Soft                                            | Morning, evening, weekly                            |
| `mine_system_v1`                                                                                                                                                                   | Low          | None                                            | Reliability warnings, export reminders, diagnostics |
| `mine_ambient_v1` (v3)                                                                                                                                                             | Low, ongoing | None                                            | The listening notification with a Stop action       |

Do Not Disturb: alarm-category sounds usually pass through. Bypassing DND per channel needs the user's grant of notification policy access, and the app only explains, never forces.

### 3.4 Alarm arming and re-arming

- Alarm tiers use `setAlarmClock()` (shows the system alarm icon, wakes the device from Doze, needs no special permission).
- `notify` tier uses `setExactAndAllowWhileIdle()` when exact alarms are allowed. In Doze, allow-while-idle alarms are rate-limited by the system
  (roughly once per several minutes per app, **verify**), so closely spaced ones may be late. Fallback: `setAlarmClock()` for important ones, an inexact window otherwise.
- Only the next 20 reminders (within 14 days) are registered, because the system limits an app to about 500 alarms.
- **Re-arm triggers:** boot completed, package replaced (app update), time set, timezone changed, exact-alarm permission state change, app start, the sweeper.
- **Force stop:** a force-stopped app receives no broadcasts and its alarms are cleared until the user opens it again. This cannot be prevented.
  On the next open, the app detects the gap (the sweeper heartbeat is stale and armed reminders are overdue), delivers missed ones as late, and shows
  "Mine was stopped, reminders were paused". The reliability guide says: do not force-stop Mine.
- Some vendors treat **swiping the app away from recents** like a force stop. This is part of the vendor guide.
- **Direct boot:** before the first unlock after a reboot, the encrypted database is unavailable, so alarms wait for unlock unless the v1.1 safety net is built.

### 3.5 WorkManager jobs

| Job                                                                                                           | Schedule                                             | Constraint                                    | Notes                                                                                                               |
| ------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `sweeper`                                                                                                   | Periodic, about 15 to 30 min (system minimum 15 min) | None                                          | Refills the alarm window, handles missed reminders, writes the heartbeat. Best effort, deferred by Doze and standby |
| `outbox_drain`                                                                                              | On network available, and on a timer                 | Network                                       | See `41`                                                                                                          |
| `embed_queue`                                                                                               | On demand                                            | Device idle or charging when the gate says so | On-device embeddings                                                                                                |
| `extraction`                                                                                                | Debounced                                            | Network                                       | Worker batches                                                                                                      |
| `summary_day`                                                                                               | After day rollover                                   | Network                                       | Low priority                                                                                                        |
| `checkin_scheduler`                                                                                         | Exact alarm per check-in, re-planned daily           | None                                          | Posts through the nudge pipeline                                                                                    |
| `housekeeping`                                                                                              | Daily                                                | Charging or idle                              | Expiry, retention, log pruning, limiter cleanup                                                                     |
| `export_reminder`                                                                                           | Weekly check                                         | None                                          | Prompts when the last export is old                                                                                 |

Expedited work is used for alarm follow-ups, and its quota is limited by the system, so work must stay short.

### 3.6 Standby buckets and restrictions

- Android places rarely used apps in lower **standby buckets**, which defer jobs and limit alarms. `setAlarmClock` is the most resilient, WorkManager jobs are the least.
- The reliability screen reads: battery optimization exemption, background restriction (`isBackgroundRestricted`), the app's standby bucket, notification and channel state, exact alarm capability, DND, and the vendor flags.
- Result: green (no problems), amber (could be delayed), red (reminders are likely to fail), each with a button to the relevant system screen.
- Because the user is meant to open the app daily (the daily session), the app naturally stays in a favorable bucket.

### 3.7 Vendor guidance

The in-app guide detects the manufacturer and shows three or four steps. Vendor wording changes between versions, so each guide has a "settings look different?" link and falls back to a generic page.
The guide data ships as a small JSON in the app, updated in releases, and mirrored in this repo.

| Vendor family                                                                                           | Typical places to check (wording varies, **verify per version**)                              |
| ------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Xiaomi, Redmi, POCO (MIUI, HyperOS)                                                                     | Autostart on. Battery saver: "No restrictions". Lock the app in the recents list                    |
| Samsung (One UI)                                                                                        | Battery: remove Mine from "Sleeping apps" and "Deep sleeping apps", or add to "Never sleeping apps" |
| Oppo, Realme, OnePlus (ColorOS, OxygenOS)                                                               | Allow auto-launch. Allow background activity. Battery: do not optimize                              |
| Vivo, iQOO (Funtouch, OriginOS)                                                                         | Autostart on. Background power consumption: allow high                                              |
| Huawei, Honor                                                                                           | App launch: manage manually, allow auto-launch, secondary launch, and background activity           |
| Pixel, Motorola, Nokia, Nothing and other near-stock                                                    | Usually only battery optimization and exact-alarm permission                                        |

Generic page for everything else: a link to a community-maintained "don't kill my app" style reference.

### 3.8 Measurement and warnings

- Every fire records `latency = fired_at - fire_at` in `reminder_fire` (`30`).
- If the p95 latency over the last 20 fires exceeds 2 minutes, or any reminder was missed, a warning appears with a link to the guide.
- The sweeper heartbeat shows when background work stopped running.
- The Test reminders button gives an immediate sample.
- Ambient uses a microphone foreground service; its heartbeat posts **"Ambient stopped"** when the service stops unexpectedly.
- A phone-state receiver records call start and end for call nudges without recording audio or content.
- The reliability score includes **"Sensing blind"** when activity/location sensing is unavailable and **"Call detection unverified"** when phone-state detection has not been tested.

### 3.9 Diagnostics (no analytics)

- A local **diagnostics log** (ring buffer, 7 days, no message content, no keys): alarm registrations, fires, latencies, job runs, network states, errors.
- **Share diagnostics** exports a redacted text file for a bug report. It is user-initiated, and the user can read it first.
- A local crash log keeps stack traces only. No crash-reporting SDK (D-060).

### 3.10 Update check

There is no in-app installer in v1. **Check for updates** opens the GitHub releases page in the browser (and compares the local version with the latest release tag when the user taps it).
Each release publishes the APK's SHA-256 and the signing certificate fingerprint (`16-security-privacy.md`). The signing key must be backed up (D-011).

### 3.11 Version behavior to verify

| Android                                                                                                                                                                                  | Things to check                                                                                                                           |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| 11 (API 30), min                                                                                                                                                                         | Alarm behavior, notification channels, package visibility, biometric authenticator combination (supported from API 30)                    |
| 12 (31, 32)                                                                                                                                                                              | Exact alarm permission appears, foreground service start limits from background                                                           |
| 13 (33)                                                                                                                                                                                  | Runtime notification permission, per-app language, themed behavior                                                                        |
| 14 (34)                                                                                                                                                                                  | Exact alarm not pre-granted for new installs, full-screen intent restricted, foreground service type declarations                         |
| 15 and later                                                                                                                                                                             | Boot receiver and foreground service restrictions, further background changes. Re-verify on each new release before raising `targetSdk` |

Policy: compile against the latest SDK, test on the oldest (API 30) and the newest, and on at least one device each from the Xiaomi, Samsung, and Oppo families before a public release.

## 4. Data

Uses `alarm_registry`, `reminder_fire` (`30`), and:

```sql
CREATE TABLE reliability_state (          -- operational, single row
  id INTEGER PRIMARY KEY CHECK (id = 1),
  last_check_at INTEGER, score TEXT,       -- green | amber | red
  issues_json TEXT,
  sweeper_heartbeat INTEGER,
  last_force_stop_detected INTEGER,
  p95_latency_ms INTEGER
);
CREATE TABLE diag_log (
  ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, msg TEXT NOT NULL    -- ring buffer, pruned at 7 days
);
```

## 5. Settings

| Setting                          | Default                     |
| -------------------------------- | --------------------------- |
| Ring even when the app is open   | Off                         |
| Notification channels            | System settings per channel |
| Reliability warnings             | On                          |
| Diagnostics log                  | On (local only, 7 days)     |
| Show first-run reliability guide | Once                        |

## 6. Edge cases and failure modes

- **User denies notifications.** Everything proactive is inactive. The home screen shows a persistent notice, and the reliability screen is red.
- **Exact alarm permission revoked later.** Fallback to `setAlarmClock`, with a banner.
- **Battery saver mode on.** Allow-while-idle and jobs may be delayed. The reliability screen mentions it.
- **Phone uses a custom launcher or a "clean memory" feature.** Vendor guide covers it. The self-test reveals it.
- **Alarm fires while the device is locked and encrypted.** Works after the first unlock since boot. Before that, see direct boot.
- **Timezone auto-update disabled.** The user's timezone setting is shown, and a change event triggers re-arming.
- **Receiver time limits.** Work beyond a few seconds is handed to expedited work, never done inline.
- **Low-RAM phones.** The embedding model is loaded only while needed. The alarm path does not touch it.
- **Notification listener restrictions on sideloaded apps (Android 13+).** Not needed by v1. Check again when call-state and notification features are added (**verify**).

## 7. Offline behavior

Everything in this file works without a network.

## 8. Tests

1. Schedule 5 reminders, reboot the phone, and all fire within 1 minute of schedule.
2. Test reminders now: the report shows the fire time and the delay.
3. Swipe the app away from recents on a Xiaomi, a Samsung and an Oppo-family phone: record whether alarms survive (this is research that feeds the vendor guides).
4. Force-stop, wait past a reminder, reopen: the missed reminder is delivered late with a label and the stopped notice appears.
5. Deny notifications: the app stays usable, the reliability score is red, and nothing crashes.
6. Revoke the exact alarm permission: reminders still fire through `setAlarmClock`.
7. 30 armed reminders stay within the alarm limit by windowed registration.
8. The alarm receiver completes its notification path within 1 second on the oldest test device.

## 9. Open questions

- Should the app offer to open the vendor autostart screen automatically (deep links differ per vendor and break across versions)? Proposed: attempt known intents with a safe fallback to the guide.

## 10. Changelog

- 0.1 (2026-10-21): first draft.
