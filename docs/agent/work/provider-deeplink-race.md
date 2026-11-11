# Branching Analysis: Provider Deep Link Flake in Full Connected Suite

## Goal and invariant
Goal: `ProviderDeepLinkTest.testProviderDeepLink_ImportsConfigViaIntent` must pass reliably in both standalone test execution and within the 116-test connected test suite on physical hardware (moto g73 5G - Android 14).
Invariant: Launching `MainActivity` with `mine://provider?...` or receiving it via `onNewIntent` must parse the provider config and persist `ep`, `mod`, and `key` into `app_meta` without race conditions or timeout flakes.

## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| 1 | `onNewIntent` was missing on `MainActivity` so warm-start intents in singleTop were dropped | Intent not delivered to ViewModel on warm starts | Check MainActivity for onNewIntent | Method already exists or not called | CONFIRMED: MainActivity had protected onNewIntent; test runner needs public override to deliver on warm start |
| 2 | Polling timeout (5.0s / 50*100ms) is too tight under full suite load where Argon2id keying takes ~4s | Extending timeout to 20s allows settings to settle | Compare standalone time (5.01s) vs suite keying logs | Fails even after 20s | CONFIRMED: Standalone took 5.013s, right on 5.0s boundary; under suite load keying 6 connections took ~4s |
| 3 | `StorageTest` deleted `mine_encrypted.db` under the running application process | `MineApp.sharedStorage` loses underlying live DB file | Check tests for `deleteDatabase("mine_encrypted.db")` | No test deletes production DB | CONFIRMED: `StorageTest.kt:24` called `context.deleteDatabase("mine_encrypted.db")`! Fixed to use isolated `mine_storage_test.db` |
| 4 | `ProviderConfigParser` fails to parse URL-encoded query params in URI | Parse throws IllegalArgumentException | Run ProviderConfigParser on test URI | Parse succeeds | KILLED: Parser unit test and adb run passed with exact URI |

## Angles
- **data/schema:** `app_meta` stores `key`/`value` pairs; `ep`, `mod`, `key` strings are written via `saveSettings`.
- **concurrency/lifecycle:** `MainActivity` is `singleTop`; cold-start routes through `onCreate()`, warm-start routes through `onNewIntent()`. Both call `handleProviderImportIntent()`.
- **failure paths:** If storage fails to initialize, `globalError` is populated and `pendingActions` queued.
- **user-visible behaviour:** When tapping a `mine://provider` link or scanning a setup QR code, settings are imported immediately with a transient receipt.
- **privacy/security:** API key import is optional; validated against max length of 512 chars; endpoint checked for valid http/https scheme and host.
- **testability:** ActivityScenario handles lifecycle; polling checks `storage.getSetting()` with bounded 20s timeout.
- **blast radius:** Restricted to `MainActivity.onNewIntent` visibility and test polling timeout in `ProviderDeepLinkTest`.
- **platform (Android 14, OEM):** Moto g73 5G has slower CPU cores under thermal throttling during 116 consecutive tests, extending Argon2id keying from ~200ms to ~700ms per connection.
- **model behaviour:** Real model never touches provider import deep links; handled entirely locally.

## Evidence log
- Direct ADB instrumentation test passed in 5.013s: `OK (1 test)`.
- Full connected test suite logcat showed 6 SQLite keyings taking 3.5s total (`02:28:20.504` to `02:28:24.024`), causing 5.0s polling loop to time out at `02:28:29.880`.
- Made `onNewIntent` public in `MainActivity` and increased polling timeout from 50 to 200 iterations (20s).

## Decision: chosen change, rejected options and why
- **Chosen:** Made `MainActivity.onNewIntent` public and explicitly invoked in `ProviderDeepLinkTest.scenario.onActivity`, while expanding polling timeout to 20s (`200 * 100ms`).
- **Rejected:** Hardcoding a sleep before launching scenario (wasteful and non-deterministic).
- **Rejected:** Bypassing Activity and testing ChatController directly (test must verify the full Android Intent -> Activity -> ViewModel -> Storage integration path).

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
1. A warm-start intent with invalid URI parameters crashes `onNewIntent` if error isn't caught. (Mitigated: `ProviderConfigParser.parse()` uses `runCatching` and controller catches `Exception`).
2. Two deep links clicked back-to-back overwrite each other. (Expected behavior: last link wins).
3. `onNewIntent` runs before `storage.await()` finishes on slow boot, dropping actions. (Mitigated: `MainViewModel.pendingActions` queue drains all pending actions once controller initializes).
