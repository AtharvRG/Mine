# Branching Analysis: Direct-Boot Alarm Safety Net (v1.1 Item 2)
Date: 2026-11-11
Author: Antigravity
Status: Decided

## Goal and Invariant
- **Goal:** Enable alarms to be re-armed and to fire when the device boots into Direct Boot mode (after a reboot before first user unlock with PIN/pattern/password), surviving reboots without depending on Credential Encrypted (CE) storage or user Keystore keys.
- **Invariant 1 (Privacy):** The Device-Protected (DE) storage table stores **NO private user text** (no node body, no reminder text, no titles, no contact names, no locations). Only: `reminder_id`, `fire_at`, `tier` (`ALARM` / `GENTLE`), and generic label (`"Reminder"` / `"Gentle Reminder"`).
- **Invariant 2 (Sync Integrity):** The DE table is updated in tandem whenever alarms are scheduled, cancelled, deleted, or reconciled in the live app.
- **Invariant 3 (Direct Boot Lifecycle):**
  - Receivers `AlarmReceiver` and `BootReceiver` must be marked `android:directBootAware="true"`.
  - `BootReceiver` must handle `Intent.ACTION_LOCKED_BOOT_COMPLETED` (fired before first unlock).
  - When running in Direct Boot (`UserManagerCompat.isUserUnlocked(context) == false`):
    - `BootReceiver` reads DE safety-net table via `context.createDeviceProtectedStorageContext()` and arms upcoming alarms.
    - `AlarmReceiver` fires: if user is locked, it posts a generic notification (e.g. `"Reminder"`) without touching CE storage.
  - When user unlocks (`ACTION_BOOT_COMPLETED`):
    - `BootReceiver` runs the full CE reconcile + sweeper.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|------------|------------|----------------|----------------|--------|
| H1 | SQLite database in Device-Protected Storage (`direct_boot_alarms.db`) | Clean transactional schema, fast queries by `fire_at`, handles hundreds of reminders cleanly | Open SQLiteOpenHelper on `context.createDeviceProtectedStorageContext()` | Fails before first unlock | **Selected (Clean, robust, blueprint-aligned)** |
| H2 | SharedPreferences JSON in DE Storage | Simple key-value file | Check `deContext.getSharedPreferences` | Concurrency races on rewrite, no transactional safety | Rejected |
| H3 | Pure Intent Extras without Boot re-arm | No storage needed | Inspect Android AlarmManager reboot behavior | AlarmManager completely drops all alarms on reboot; without boot receiver alarms are lost | Disproved |
| H4 | Hardware-backed keystore in DE storage | Strong encryption | Check Keystore availability before unlock | Keystore with user authentication is unavailable; Android OS already encrypts DE at block level | Over-engineered / Unneeded |

## Angles (Mandatory)
1. **Data / Schema:** A dedicated table `direct_boot_alarm (id TEXT PRIMARY KEY, fire_at INTEGER NOT NULL, label TEXT NOT NULL, tier TEXT NOT NULL)`. Kept in `direct_boot_alarms.db` in DE context.
2. **Concurrency / Lifecycle:** Synchronized safely on IO dispatcher. DE helper is a singleton or isolated helper.
3. **Failure Paths:** If DE storage write fails, logs warning; does not crash CE database transaction. If CE storage is unavailable, reads DE storage.
4. **User-Visible Behaviour:** After an unattended phone reboot at night (e.g. OS update), a 7:00 AM wake-up alarm still rings on time, displaying generic title `"Reminder"`. Once unlocked, full details resume.
5. **Privacy / Security:** Zero private user text in DE storage! Only UUID, epoch timestamp, tier, and static string `"Reminder"`.
6. **Testability:** Unit-testable with mock/in-memory SQLite, instrumented test verifying DE storage access before and after mock unlock.
7. **Blast Radius:** Scoped to `DirectBootAlarmStore`, `AndroidAlarmScheduler`, `AlarmReceiver`, and `BootReceiver`.
8. **Platform (Android 14, OEM):** `directBootAware="true"` in `AndroidManifest.xml` for `AlarmReceiver` and `BootReceiver`. `ACTION_LOCKED_BOOT_COMPLETED` receiver intent-filter.
9. **Model Behaviour:** Independent of LLM.

## Pre-Mortem: 3 Failure Scenarios
1. *DE store out of sync with CE store:* A cancelled reminder is removed from CE but remains in DE, firing an unwanted alarm after reboot. -> **Guard:** `cancel()` and `deleteReminder()` must explicitly delete from DE store; full sync on every CE reconcile.
2. *OEM blocks background service or direct boot broadcast:* Motorola/Xiaomi aggressive battery saver ignores `ACTION_LOCKED_BOOT_COMPLETED`. -> **Guard:** Use standard `AlarmManager.setAlarmClock`, which is the highest-priority wake alarm supported in Direct Boot mode.
3. *App crashes in Direct Boot due to accidental CE access:* Trying to access `sharedStorage` or CE SharedPreferences throws `IllegalStateException`. -> **Guard:** Wrap CE storage access in `UserManagerCompat.isUserUnlocked(context)` check and graceful try/catch.

## What Input Would Break the Fix
A device reboot that fires an alarm before unlock, where the code attempts to look up the node title by opening SQLCipher with a user key derived from Keystore. The guard ensures that if `isUserUnlocked()` is false, SQLCipher is NOT opened, and the generic notification is posted directly.
