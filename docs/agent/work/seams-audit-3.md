# Branching Analysis: Audit #3 Polish Seams

Date: 2026-11-12
Goal: Resolve the 3 subtle seams identified in the review:
1. `CallStateReceiver`: Wrap alarm scheduling in `try/catch(SecurityException)` and use `setAndAllowWhileIdle` when exact alarm permission is absent.
2. `MainViewModel`: Auto-export must not hardcode `"MineBackup2026!"`. Read user passphrase from settings (`export.passphrase` or `backup.passphrase`), write directly to user's SAF folder `DocumentFile`, prune old backups via `AutoExportScheduler.findFilesToPrune`, and cleanly delete temporary cache files in `finally`.
3. `ContextSensingManager` & `MineApp`: Gate sensor and network observer registration behind `flag.sensing == "1" && sensing.enabled == "1"`. Emit `MotionAxis.STILL` when steps stop for >60 seconds.

---

## 1. Hypotheses & Design Angles

### Seam 1: `CallStateReceiver` Alarm Scheduling Robustness
- **Hypothesis**: On Android 12+ (API 31+), calling `setExactAndAllowWhileIdle` throws `SecurityException` if `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` permission is revoked or not granted.
- **Angle 1 (API Alignment)**: `AndroidAlarmScheduler.kt` and `QuoteAlarmScheduler.kt` already handle this:
  ```kotlin
  try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager?.canScheduleExactAlarms() == true) {
          alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, decision.fireAt, pending)
      } else {
          alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, decision.fireAt, pending)
      }
  } catch (e: SecurityException) {
      alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, decision.fireAt, pending)
  } catch (_: Exception) {}
  ```
- **Angle 2 (Graceful Degradation)**: A 20-second delay post-call does not strictly require microsecond precision—if exact alarm is unavailable, `setAndAllowWhileIdle` fires safely within Doze maintenance windows.

### Seam 2: `MainViewModel` Auto-Export Passphrase & SAF Folder Storage
- **Hypothesis**: Hardcoded `"MineBackup2026!"` violates Blueprint 15 §3.5 and P1-A #5. Auto-export requires a user-configured passphrase; if none exists, auto-export must be skipped safely. Writing to `application.filesDir` fills internal app storage rather than the user's picked SAF folder.
- **Angle 1 (Security & Settings)**:
  - Check `loadedStorage.getSetting("export.passphrase") ?: loadedStorage.getSetting("backup.passphrase")`.
  - If null/blank, log info and skip auto-export (never back up with a default/predictable passphrase).
- **Angle 2 (SAF File Writing & Pruning)**:
  - Write temporary backup to `File(application.cacheDir, "temp_auto_backup_${clock.now()}.minebackup")`.
  - Parse `uriStr` to `Uri`, obtain `DocumentFile.fromTreeUri(application, treeUri)`.
  - Check `docFolder.canWrite()`. Create file `mine_backup_${clock.now()}.minebackup`.
  - Stream copy from temp file to `application.contentResolver.openOutputStream(newDoc.uri)`.
  - Prune old backups using `AutoExportScheduler.findFilesToPrune(fileInfos, clock.now())`.
  - Delete temporary cache file in `finally { tempFile.delete() }`.

### Seam 3: `ContextSensingManager` Gating & Motion Timeout Return to `STILL`
- **Hypothesis**:
  1. `MineApp.onCreate()` unconditionally starts `ContextSensingManager`, wasting battery on a 30s coroutine ticker and sensor listener even when sensing is disabled.
  2. `onSensorChanged` only ever transitions motion to `WALKING`. When steps stop, motion remains `WALKING` until the 30-minute unknown timeout.
- **Angle 1 (Gating on Settings)**:
  - In `ContextSensingManager.start()`: Query `storage.getSetting("flag.sensing") == "1" && storage.getSetting("sensing.enabled") == "1"`. If false, log and return early without registering listeners or starting ticker.
  - In `MineApp.kt`: After `runSettlingStartup` finishes and `sharedStorage` is ready, only call `sensingMgr.start()` if sensing is enabled.
- **Angle 2 (Motion Debounce & Still Transition)**:
  - Record `@Volatile private var lastStepTimeMs = 0L` on step detection.
  - In `startTimeoutTicker()`: If `lastStepTimeMs > 0L` and `System.currentTimeMillis() - lastStepTimeMs > 60_000L` and `contextEngine.getState().motion == MotionAxis.WALKING`, call `contextEngine.onMotionSignal(MotionAxis.STILL, confidence = 0.8f)`.
  - This guarantees that 60s after walking stops, motion transitions back to `STILL`!

---

## 2. Pre-Mortem & Blast Radius
- Will `DocumentFile.fromTreeUri` crash on invalid URIs? No, it returns `null` safely. We check `docFolder != null && docFolder.canWrite()`.
- Will `startTimeoutTicker` race sensor events? `lastStepTimeMs` is marked `@Volatile`.
- Will unit tests be affected? Unit tests in `:core` do not depend on Android framework. Connected tests in `:app` run on real device with `fakeStorage` or real SQLCipher.
