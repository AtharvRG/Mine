# Branching Analysis: v1.1 Item 1 - Provider Setup Import

Target: `V1.1-01` (from `ROADMAP.md` P1.1 item 1 & blueprints 52 §360, 90 §3.3)

## Goal and invariant
- **Goal:** Allow importing provider configuration (base URL/endpoint, model, and optional API key) from:
  1. Plain JSON text or file content: `{"endpoint": "...", "model": "...", "key": "..."}`.
  2. URI format: `mine://provider?endpoint=...&model=...&key=...`.
  3. Interactive UI import dialog in `ProviderScreen` and deep-link intent handling in `MainActivity`.
- **Invariant:**
  - Key is strictly optional: if omitted or blank in the payload, any existing saved API key MUST NOT be overwritten or blanked.
  - Endpoint must be a valid HTTP or HTTPS URL (no `file://`, `content://`, or arbitrary schemes).
  - Pure parsing logic must live in `:core` (`com.anchor.core.provider.ProviderConfigParser`) with zero Android dependencies.
  - Failures (malformed JSON, invalid URLs, missing required fields) must emit user-friendly receipts and never crash or set `globalError`.

## Hypothesis table
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| 1 | Pure Kotlin parser in `:core` handling both JSON and `mine://` URI | Zero `android.*` imports, runs in JVM unit tests, 100% testable | Code check of `:core` dependencies | If Android platform URI classes are required | ALIVE: `java.net.URI` & `kotlinx.serialization` work in pure JVM |
| 2 | Import immediately overwrites all 3 settings including key | A payload with no key blanks the existing key | Inspect `SaveProviderAtomicityTest` | If key is optional, blanking existing key breaks user setup | KILLED: `key` must be optional and non-destructive |
| 3 | Import is only an external intent filter for QR scanner apps | User cannot import from file or clipboard paste | Check UI in `MainActivity.kt` | User receives config via text/chat/file and needs paste | KILLED: Must support both in-app paste/dialog and deep-link intent |
| 4 | Parser tolerates common field synonyms (`base_url`, `url`, `api_key`, `apiKey`) | Tech-savvy friend JSON configs import seamlessly without strict field name friction | Test parser with various JSON formats | Malformed or unrecognized keys without endpoint/model fail gracefully | ALIVE: Map synonyms to canonical fields with strict validation |

## Angles (one line each, mandatory)
- **data/schema:** No DB migration; `app_meta` already persists `ep`, `mod`, and `key` key-value pairs via `storage.saveSettings`.
- **concurrency/lifecycle:** Controller handles `UiAction.ImportProviderConfig` on `defaultDispatcher`, updating `UiState` via `_state.update` atomically.
- **failure paths:** Malformed JSON, non-HTTP/HTTPS URLs, missing endpoint/model, oversized payloads (>64KB) emit transient error receipts without crashing.
- **user-visible behaviour:** `ProviderScreen` gains an "Import Setup" action button opening a paste dialog; fields update with imported values; success/error receipt is shown.
- **privacy/security:** Endpoint URLs restricted to `http` or `https`; keys capped at 512 characters; existing keys preserved when payload omits key.
- **testability:** Fast pure JVM unit tests in `core` (`ProviderConfigParserTest`), controller edge tests in `core`, and UI compose/intent tests in `app`.
- **blast radius:** Limited to `ProviderConfigParser`, `ChatContract.kt`, `ChatController.kt`, `MainActivity.kt` (`ProviderScreen`), and `AndroidManifest.xml`.
- **platform (Android 14, OEM):** Intent filter for `mine://provider` declared with `android:exported="true"`, `android.intent.action.VIEW`, `DEFAULT`, `BROWSABLE`.
- **model behaviour:** N/A (pre-connection configuration).

## Evidence log
- Blueprint 52 §360: *"A provider setup import (a file or QR created by a tech-savvy friend that fills in base URL and model, optionally the key) is a v1.1 item (90 section 3.3)."*
- Blueprint 03 D-081: *"Provider setup import (file or QR) is a v1.1 item."*
- Blueprint 90 §3.3: *"Item 1: Provider setup import (a file or QR made by a tech-savvy friend: base URL and model, key optional)."*
- `gradle.properties`: pure `:core` is tested offline without Android runtime.

## Decision: chosen change, rejected options and why
- **Chosen:**
  1. Create `ProviderConfigParser` in `core/src/main/java/com/anchor/core/provider/ProviderConfigParser.kt`.
  2. Support JSON (`endpoint`, `model`, optional `key`, plus synonyms `base_url`/`url`, `api_key`/`apiKey`) and URI `mine://provider?endpoint=...&model=...&key=...`.
  3. Add `UiAction.ImportProviderConfig(val payload: String)` in `ChatContract.kt`.
  4. In `ChatController.kt`, parse the payload:
     - If key is present: save `ep`, `mod`, `key`.
     - If key is absent: save `ep`, `mod`, preserve existing `state.providerKey`.
  5. Add "Import Setup" button & dialog to `ProviderScreen` in `MainActivity.kt`.
  6. Add intent-filter `<data android:scheme="mine" android:host="provider" />` in `AndroidManifest.xml` and handle in `MainActivity.kt`.
- **Rejected:**
  - Wiping existing API key if payload has no key (violates "key optional" requirement and risks deleting user credentials).
  - Only supporting QR deep-link without in-app paste/file dialog (forces users to convert JSON to QR code images).

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?"
1. *Pre-mortem 1:* A user scanned a QR code that omitted the key, and Mine wiped their existing stored API key, causing all chat calls to fail with 401.
   *Protection:* Explicit logic in `ProviderConfigParser` and `ChatController`: if `parsed.key == null`, the existing key in storage and state is untouched.
2. *Pre-mortem 2:* An attacker crafts a `mine://provider?endpoint=file:///data/data/com.anchor.mine/databases/...` payload trying to read local files via the HTTP client.
   *Protection:* `ProviderConfigParser` validates scheme strictly against `http` and `https`, rejecting any other URI scheme.
3. *Pre-mortem 3:* An external QR scanner launches `MainActivity` with an invalid `mine://` URI, throwing an unhandled exception in `onCreate` or `onNewIntent` and crashing the app on startup.
   *Protection:* Deep-link extraction in `MainActivity` is wrapped in `runCatching` and dispatches `ImportProviderConfig` through the controller's safe exception boundary.

## What input would break the fix
- An oversized input (> 64 KB) designed to cause OOM during JSON parse -> Handled with length cap.
- A URL with malformed percent-encoding like `mine://provider?endpoint=https%3A%2F%2Fapi.openai.com%2Fv1&model=gpt-4o%ZZ` -> Handled by `runCatching` in URL decoder.
