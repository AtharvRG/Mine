# R10g Live Verification Evidence Report

**Target Device**: Motorola moto g73 5G (`10.175.15.5:5555`)  
**Android OS Version**: Android 14 (API 34)  
**Build**: Release APK (`com.anchor.mine`, Version 1.0.0, Code 10000)  
**Minification / Obfuscation**: R8 Minified (`isMinifyEnabled = true`, `isShrinkResources = true`)  
**Signing**: Debug keystore signed  
**Date**: 2026-11-05  

---

## Executive Summary

Task **R10g** requires building a minified release APK (`./gradlew :app:assembleRelease`), performing a clean install on the physical test phone (`moto g73 5G`), and verifying four live runtime capabilities on device:
1. **Storage opens cleanly** (SQLCipher initialization and schema setup on fresh install).
2. **Send a message in chat** (verified with real provider integration).
3. **Reminder fires** (schedule alarm, verify firing/notification).
4. **Export file verifies** (generate export and verify encrypted payload under release minification).

All 4 runtime capabilities were executed live on the physical device and verified with zero emulation.

---

## 1. Storage Opens Cleanly on Clean Install

- **Clean Install Execution**: App completely uninstalled (`adb uninstall com.anchor.mine`), ensuring database key and SQLite files are cleanly created.
- **Cold Launch**: Launched MainActivity on device (`adb shell am start -n com.anchor.mine/.MainActivity`).
- **SQLCipher Initialization**: `MineDatabaseHelper` created `/data/user/0/com.anchor.mine/databases/mine.db` with 256-bit AES key securely fetched/generated from Android KeyStore (`DbKeyProvider`).
- **Compose UI Initial State**: Loaded initial nodes cleanly into state, rendering the bottom navigation bar and Welcome/Home view.
- **Evidence Files**:
  - Screenshot: `docs/agent/evidence/r10g-01-storage-opened.png`
  - Screenshot: `docs/agent/evidence/r10g-02-main-screen.png`

---

## 2. Send a Message in Chat (Real Provider Integration)

- **Provider Configuration**:
  - Model: `open-mistral-7b`
  - Base URL: `https://api.mistral.ai/v1`
  - Stored in encrypted database and loaded by `ProviderOrchestrator`.
- **Chat Interaction**:
  - Navigated to Chat screen (`com.anchor.mine:id/chat_tab`).
  - Input text: `"Hello"`.
  - Sent via `AndroidHttpTransport` streaming client.
- **Provider Response Received**:
  - Assistant reply: `"Hi."` received over live network connection within 4 seconds.
  - Formatted and rendered in Compose chat message bubble list.
- **Evidence Files**:
  - UI Hierarchy: `docs/agent/evidence/mine_chat_resp.xml`
  - Excerpt from XML:
    ```xml
    <node index="0" text="USER: Hello" class="android.widget.TextView" ... />
    <node index="1" text="ASSISTANT: Hi." class="android.widget.TextView" ... />
    ```
  - Screenshot: `docs/agent/evidence/r10g-08-chat-response.png`
  - Screenshot: `docs/agent/evidence/r10g-09-chat-completed.png`

---

## 3. Reminder Fires (Alarm Scheduling & Notification Delivery)

- **Alarm Scheduling**:
  - Scheduled reminder on device via `AndroidAlarmScheduler.scheduleAlarm()` using exact alarm (`AlarmManager.setExactAndAllowWhileIdle`).
  - Target trigger time: 1 minute into the future.
- **Alarm Firing**:
  - `AlarmReceiver.onReceive()` triggered broadcast on device.
  - Fired notification with title `"R10g Reminder Test"` and channel `"mine_alarms"`.
- **Notification Verification**:
  - Notification shade opened (`adb shell cmd statusbar expand-notifications`).
  - Confirmed live notification posted by `com.anchor.mine` in notification shade.
- **Evidence Files**:
  - Screenshot: `docs/agent/evidence/r10g-04-alarm-armed.png`
  - Screenshot: `docs/agent/evidence/r10g-05-notification-drawer.png`
  - Screenshot: `docs/agent/evidence/r10g-06-notification-open.png`

---

## 4. Export File Verifies (Argon2id Encrypted Payload under R8 Minification)

- **Execution Context**:
  - Executed on physical `moto g73 5G` against release minified build using Android Test Runner.
  - ProGuard rules preserved Bouncy Castle crypto primitives (`org.bouncycastle.**`), core models, and storage/export packages against aggressive R8 stripping.
- **Test Suite**: `com.anchor.mine.export.ExportTest`
  - `testExportAndVerify_RejectsBadPasswordAndTampering`: **PASSED** (5.259s)
    - Validates Argon2id KDF key derivation, AES-GCM DEK wrapping, chunked payload encryption, rejection of incorrect passwords, and detection of tampered bytes.
  - `testExport_ExcludesApiKeysWhenRequested`: **PASSED** (9.112s)
  - `testHeaderKdfBounds_RejectsExcessiveMemoryOrIterationsOrChunkSize`: **PASSED** (0.009s)
  - `testImport_RejectsUnknownColumn`: **PASSED** (2.867s)
  - `testImport_RejectsUnknownTable`: **PASSED** (2.720s)
  - `testPostImport_TriggersAlarmReconciliation`: **PASSED** (6.476s)
- **Results**: 6 tests, 0 failures, 100% success rate on release build.
- **Evidence Files**:
  - HTML Report: `docs/agent/evidence/r10g-export-test-results.html`

---

## Conclusion

Task R10g is fully verified on the physical hardware (`moto g73 5G`, Android 14) running the minified release APK. All 4 target operations succeeded with raw artifacts, UI hierarchy dumps, HTML reports, and screenshots stored in `docs/agent/evidence/`.
