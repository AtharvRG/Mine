# PROJECT_STATE (RETIRED)

This document is **RETIRED**. The canonical, authoritative source of truth for repository build, test, and architecture status is `docs/agent/STATE.md`.

## Resolution of Stale Historical Topics

1. **OkHttp vs HttpURLConnection**:
   - **Stale claim**: The project uses OkHttp for LLM model streaming.
   - **Ground Truth**: The project does not use OkHttp. `AndroidHttpTransport` (`app/src/main/java/com/anchor/mine/provider/AndroidHttpTransport.kt`) implements `HttpTransport` using standard `java.net.HttpURLConnection` with Kotlin coroutine streaming (`Flow<String>`). Zero third-party HTTP client libraries are imported in Gradle dependencies.

2. **RTC Bug (Gentle Reminders)**:
   - **Stale claim**: Gentle reminders use `AlarmManager.RTC` which fails to wake the CPU from deep sleep.
   - **Ground Truth**: Resolved in R11 / Priority Item 1. `AndroidAlarmScheduler.kt` uses `AlarmManager.RTC_WAKEUP` with `setAndAllowWhileIdle()` to wake the CPU while respecting Doze boundaries. Notifications are routed with `NotificationCompat.CATEGORY_REMINDER` (or `CATEGORY_ALARM` for alarms), and `alarmDisclosureText` in `ChatContract.kt` explicitly discloses Doze behavior.

3. **USE_EXACT_ALARM Permission**:
   - **Stale claim**: `USE_EXACT_ALARM` is forbidden under the zero-permission model.
   - **Ground Truth**: Resolved under Decision D-102 amended and D-102b. Android 14 (API 34, targetSdk 35) strictly throws an OS `SecurityException` (`Caller needs to hold android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM`) if `USE_EXACT_ALARM` is omitted when invoking `setAlarmClock()`. Empirical reproduction is documented in `docs/agent/evidence/security-exception.txt`. `USE_EXACT_ALARM` is declared in `app/src/main/AndroidManifest.xml` (an auto-granted normal permission for alarm apps). `SCHEDULE_EXACT_ALARM`, `VIBRATE`, and `WAKE_LOCK` remain strictly omitted.
