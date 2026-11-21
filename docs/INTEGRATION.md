# Mine — integrated app

The Compose design (formerly the standalone `com.mine.companion` UI) now runs on top of
the real engine (`:core`) and the Android platform layer (`:app`). There is no demo data
left anywhere: every screen reads from the engine, and every control writes to it.

## Drop-in steps

1. Replace your `app/`, `core/` and `docs/` folders with the ones in this bundle.
   Your root `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` and
   wrapper are unchanged and not included.
2. If `settings.gradle.kts` still includes the old standalone UI module (`include(":ui")`),
   remove that line and the folder. Its code now lives in `app/src/main/java/com/mine/companion/`
   and two copies would clash.
3. **Copy the fonts** (binary files can't travel in a text bundle) into
   `app/src/main/res/font/`:
   `tangeren_bold.otf`, `novaform_regular.otf`, `novaform_italic.otf`, `novaform_medium.otf`,
   `novaform_medium_italic.otf`, `novaform_semibold.otf`, `novaform_bold.otf`.
4. Sync, then run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`.

## How it fits together

```
 Compose UI (com.mine.companion.ui)          ← untouched visuals: orb, pill shader, pulsing border
        │  MineAction                ▲ MineUiState
        ▼                            │
 MineStore (AndroidViewModel)  ── reducer: instant UI feedback (optimistic bubbles, sheets)
        │  effect() → UiAction       ▲ EngineMapper.map(UiState + PlatformSnapshot)
        ▼                            │
 ChatController (:core)  ──  Gateway / Storage (SQLCipher) / Providers / Alarms
        ▲
 Platform buses: AmbientStatusBus · VoiceSession · Speaker · NotificationReader
```

* **One source of truth.** `ChatController.state` is merged with the platform buses and
  mapped into the UI model by `EngineMapper` (pure, unit-tested). The reducer only handles
  things that must feel instant; anything persistent goes through the engine.
* **Direct actions.** Taps that don't need the model (complete, snooze, reschedule, quick
  add, local-only, day window, breaks, nudge pause, prefs) use `UiAction.Direct.*`, handled by
  `CompanionActions` through the same `propose_change` path the model uses, so receipts,
  undo and validation are identical whether you tap or ask.
* **Feedback.** Every direct action returns an `ActionResult` → toast, with Undo when the
  engine issued an undo token. Optimistic overlays expire after 5 s if the engine disagrees.

## Reading notifications aloud (new)

Runs while ambient listening is on (or on its own, if you enable it without ambient).

* `MineNotificationListener` (NotificationListenerService) → `NotificationReader`
  → `NotificationReaderEngine` (:core, pure Kotlin, 30 tests) → `Speaker`.
* Triage: never reads ongoing/progress/system/alarm/call/transport notifications, group
  summaries, silent updates, or its own; respects DND, quiet hours (your day window),
  per-app allow/block lists, "messages only" scope, headphones-only and a per-minute cap.
  Bursts are held and summarised ("Also, 2 messages from Riya on WhatsApp…").
* Privacy: one-time codes are never read out; links become "a link", long numbers
  "a number". Content level is Full / Sender only / App only, and "Hide notification
  content" in Privacy forces App only.
* Speech: one queue shared with conversation replies. Conversation always pre-empts
  notifications, other audio is ducked, and ambient listening ignores slices that overlap
  Mine's own speech so it never transcribes itself.
* Suggestions: "call me at 6", "standup with Arjun at 10", deadlines become proposals in
  the inbox (marked *from a notification*), never silent writes.
* Settings: Settings → Listening & notifications (also from Home → Ambient listening).
  The "Heard" page lists what was read, with Save-as-reminder on each.

## Robustness work in the engine

* Friendly, classified errors (offline, auth, rate limit, quota, model missing, context
  overflow) with Retry on the failed message and a Settings shortcut when keys are wrong.
* Context overflow → automatic retry with a trimmed pack; oversize messages and system
  prompts are capped.
* Empty model replies are retried once, then a graceful fallback; tool loops that never
  commit end with a clear message instead of spinning.
* Casual chat never fails: greetings/thanks/small talk get a local reply when there is no
  key, no network or the provider errors, and the prompt now tells the model to just talk
  when the user is just talking.
* Provider "Test connection" with timeout before saving.

## What was verified in this environment

* `:core` main + tests type-check cleanly. New tests: `NotificationReadingTest` (30) and
  `ChatResilienceTest` (7) pass. The rest of the core suite gives **exactly the same
  per-class results as the original, untouched code** in this sandbox; its failures there
  come from the Kotlin serialization compiler plugin not being available offline, and
  should pass under Gradle as before.
* Store mapping (`EngineMapperTest`, `StoreHelpersTest`, 17 tests) passes.
* All UI ↔ store references (actions, icons, tokens, engine fields) were cross-checked by
  script, and every file's braces balance.
* **Not done here:** compiling the Android modules (no Android SDK in this environment).
  Expect at most small compile fixes on first Gradle sync; the logic is in place.
