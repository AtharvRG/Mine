
# Parking Lot

Status: parked     Version: 0.1     Depends on: 90-v1-scope, 03-decisions

## 1. Purpose

A safe place for ideas that are good but not now. Parked is not rejected. An idea here has a reason it waits and
a signal that says when to look at it again. This keeps v1 small without losing anything.

## 2. Behavior (what the user sees)

Nothing. A parked idea that is promoted becomes a normal blueprint section and a line in `90-v1-scope.md`.

## 3. Design

### 3.1 How ideas enter

1. Write a row below with an ID (`P-nnn`), the idea in one sentence, where it came from, why it waits, and what would make it worth revisiting.
2. Do not design it here. Design happens after promotion.

### 3.2 Promotion

Review the lot after v1.0 is released, and after every real-use review. To promote an idea:

1. Add a decision line in `03-decisions.md`.
2. Write it into the matching blueprint (or a new numbered file).
3. Add it to the list in `90-v1-scope.md` for the target release.
4. Mark the row here as `promoted` with the date. Never delete rows.

### 3.3 The lot

| ID    | Idea                                                                              | From           | Why it waits                             | Revisit when                                                                |
| ----- | --------------------------------------------------------------------------------- | -------------- | ---------------------------------------- | --------------------------------------------------------------------------- |
| P-001 | Share a **redacted Soul** with a friend who is setting up Mine              | `12`         | Privacy design is non-trivial            | Provider setup import (v1.1; see `90` section 3.3) is in use and people ask for more |
| P-002 | **Mark a message private** (hidden from the AI)                             | `20`, `50` | Needs UI plus pack and retrieval rules   | After the first month of use                                                |
| P-003 | **Pin a message** into the pack, or keep it in view for the day             | `20`, `50` | Small value for the pack complexity      | Context pack tuning in v1.x                                                 |
| P-004 | **Edit a sent message** and **regenerate a reply**                    | `50`         | Conflicts with the append-only raw layer | A clear need appears, design as "correction events"                         |
| P-005 | `bulk_update` tool ("move everything from today to tomorrow")                   | `23`         | The confirmation list covers it for now  | If confirmation lists feel slow                                             |
| P-006 | **Persona by time of day** (gentler at night)                               | `24`         | Persona is already user-set              | After the learning data shows a pattern                                     |
| P-007 | **Month-to-date** section in the weekly review at month end                 | `32`         | Low value                                | Users ask                                                                   |
| P-008 | **Mood line** chart from the reflection prompt (opt-in)                     | `32`         | Sensitive, needs its own consent wording | After v1.1 (see `90` section 3.3)                                            |
| P-009 | **Tokens-per-minute** limiting                                              | `40`         | RPM is what the user asked for           | A provider enforces TPM and users hit it                                    |
| P-010 | **Small on-device LLM** as the last fallback                                | `41`         | Size, speed, quality                     | v3 or later; the `Provider` interface already allows one more chain entry |
| P-011 | **Panic wipe** shortcut                                                     | `16`         | Risk of accidental data loss             | Threat model review                                                         |
| P-012 | **Reproducible builds**                                                     | `16`         | Costly for a solo project                | The user base grows beyond friends                                          |
| P-013 | **User-hosted relay** for sync (own bucket or WebDAV, end-to-end encrypted) | `15`         | Sync itself is planned later             | Desktop client work starts                                                  |
| P-014 | **Desktop client** (reader first, editing after sync is stable)             | `15`         | After v2                                 | Sync design is accepted                                                     |
| P-015 | **Auto-open the vendor autostart screen** with per-vendor deep links        | `42`         | Links break across versions              | The OEM research gives stable intents                                       |
| P-016 | **128-dim embeddings** for very low-end phones                              | `21`         | Needs recall measurements                | Low-end test device shows slow indexing                                     |
| P-017 | **Month and year** summaries beyond hierarchical daily and weekly           | `21`         | Not needed at v1 scale                   | Retrieval quality drops on old data                                         |
| P-018 | Hands-free conversation mode (talk, hear, talk again) | `60` | Needs its own turn-taking and battery design | After v2 voice is in daily use |
| P-019 | "Remember the last 2 minutes" bookmark in the ambient notification | `61` | Gate design first | v4 |
| P-020 | Adaptive voiceprint | `61` | Drift is hard to debug | After ambient has real data |
| P-021 | VoIP call detection via notification access | `44` | Restricted for sideloaded apps on newer Android | If Android changes this |
| P-022 | Detecting media (TV, video) as a separate source | `61` | The gate covers most of it | If false proposals are common |
| P-023 | Acted emotion in TTS | `60` | Kokoro range is limited | A better small model |
| P-024 | Wake word or voice commands | `60`, `61` | Separate always-on cost | After ambient is stable |

### 3.4 Promoted (moved out of the lot)

Provider setup import, recurring reminders and habits, readable export, scheduled auto-export, quick add parser,
widget and share sheet, calendar grid and board view: all now in `90-v1-scope.md` section 3.3.

## 4. Data

None.

## 5. Settings

None.

## 6. Edge cases and failure modes

- **The lot grows without limit.** Review it at each release. Anything untouched for three releases gets a "still wanted?" note.
- **An idea blocks a v1 decision** (for example a schema choice). Record only the hook that is needed (a column or flag) and keep the idea here.

## 7. Offline behavior

Not applicable.

## 8. Tests

None.

## 9. Open questions

- None at this stage.

## 10. Changelog

- 0.1 (2026-10-21): first draft, seeded from the open questions of the other blueprints.
