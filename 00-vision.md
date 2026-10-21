
# Vision

Status: core     Version: 0.1     Depends on: none

## 1. Purpose

Mine is a personal AI companion that lives on the user's Android phone. It is meant to be the one app a person
opens at any hour of the day or night: to chat, to track what they have promised and planned, to get reminded,
to report progress, and to get advice when they are stuck.

What makes it different from a normal chatbot:

| Normal chatbot                      | Mine                                                                                      |
| ----------------------------------- | ----------------------------------------------------------------------------------------- |
| Forgets you between sessions        | Keeps a permanent, inspectable model of who you are (**Soul**)                      |
| Has no idea what you owe the world  | Keeps a graph of your tasks, promises, goals and how they relate (**Memory graph**) |
| Waits for you to speak              | Speaks first, but only when it has a reason (**Nudge engine**)                      |
| Timer = a dumb alarm                | Reminder = a conversation that knows whether you already started                          |
| Your data lives on someone's server | Your data lives on your phone, encrypted                                                  |

## 2. Who it is for

1. **The author first.** Mine is built because the author needs it. Features serve the daily-driver case before anything else.
2. **Tinkerers.** People who find the GitHub repo and are comfortable getting an API key from a provider.
3. **Non-technical people.** Reached through word of mouth (for example, the APK shared over chat apps). They
   are supported by a guided onboarding, but they are not the design driver. If a choice makes the app worse
   for the author to make it easier for a stranger, the author wins.

## 3. Principles

The ten principles in the README apply to this app. The ones that shape the vision most:

- **Memory plus initiative.** Everything else (chat UI, voice, personas) is plumbing around those two things.
- **You own it.** Data stays on the device. The user brings their own AI endpoint and carries that
  responsibility with the freedom.
- **Honest software.** Say exactly what leaves the device (prompts go to the user's chosen provider), and let
  the user see and edit everything the app believes about them.
- **Built to last.** The app should run for years without a rewrite. Data is layered so that improving
  the AI never means migrating or losing the user's history.
- **Restraint.** A companion that nags gets uninstalled. Proactivity must feel like a thoughtful friend.

## 4. Experience in one day (target)

- 06:00 Day starts. A new day session opens in the single scroll.
- 09:00 No message from the user yet. MineAI opens with a short check on yesterday's open items.
- 11:30 The user says "I'll start the report in the evening". A reminder is scheduled with a guard.
- 18:00 The user started at 17:40. The 18:00 alarm becomes a progress check, not a "start now" nag.
- 21:30 Late-evening check-in: what is done, what is paused, what matters tomorrow.

## 5. Non-goals

- No cloud account, no server-side storage, no analytics service.
- No Play Store distribution (GitHub Releases only).
- No bundled AI provider. The user always supplies the endpoint.
- Not a general-purpose assistant for the whole world. It is a assistant about *this user's* life.
- No unattended destructive actions. The AI never deletes something the user did not clearly mean.

## 6. Success criteria for v1

- The author uses it daily for 3 weeks without going back to another to-do or reminder app.
- A reminder fires within the expected time on the author's phone in at least 95 percent of cases.
- The "which dinner?" ambiguity never causes a wrong deletion.
- Per-turn token cost on day 90 is within 10 percent of day 1.
- Exporting and re-importing the whole app on another phone loses nothing.

## 7. Open questions

- None at this stage.

## 8. Changelog

- 0.1 (2026-10-21): first draft.
