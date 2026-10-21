
# Glossary

Status: core     Version: 0.1     Depends on: none

## 1. Purpose

One shared vocabulary. Other blueprints use these words exactly as defined here.

## 2. Terms

| Term                          | Definition                                                                                                                    |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| **Mine**                | The Android app.                                                                                                              |
| **MineAI**              | The AI the user talks to. It is a product-level name for the combination of Chat AI, Worker AI and the Scheduler.             |
| **Chat AI**             | The LLM role that converses with the user in the chosen persona. Mostly read access to memory.                                |
| **Worker AI**           | The LLM role that extracts events and facts from conversation and proposes changes. Never talks to the user directly.         |
| **Scheduler**           | Plain code (no LLM) that owns time: reminders, nudges, check-ins, expiry, rollover.                                           |
| **Slot**                | A configurable AI binding: provider + model + key. Two slots exist, *Chat* and *Worker*.                                  |
| **Provider**            | An OpenAI-compatible endpoint configured by the user (base URL, key, limits).                                                 |
| **Soul**                | The user's character definition: who they are, what they like and dislike, constraints, experiences. Stored as facts.         |
| **Soul fact**           | One statement in Soul with category, confidence, source and validity period.                                                  |
| **Core profile**        | The small, always-included subset of Soul (for example health constraints, name, strong preferences).                         |
| **Node**                | One item in the memory graph: a task, commitment, goal, event, note, person, place or habit.                                  |
| **Edge**                | A typed relation between two nodes (for example `depends_on`, `conflicts_with`).                                          |
| **Status**              | The lifecycle state of a node (`proposed`, `active`, `done`, ...).                                                      |
| **Lane**                | `standard` or `ephemeral`. Ephemeral nodes are tiny, short-lived items such as "drink water" that do not enter the graph. |
| **Event (op)**          | One recorded change in the operation log. Not to be confused with a calendar event node.                                      |
| **Calendar event**      | A node of type `event`: something happening at a time. Always written "event node" to avoid confusion.                      |
| **Raw layer**           | Immutable data: messages and the operation log. Never migrated.                                                               |
| **Derived layer**       | Data computed from raw: nodes, edges, soul facts, embeddings, summaries. Rebuildable.                                         |
| **Operational layer**   | Disposable machinery: outbox, alarm registry, caches, rate-limiter state.                                                     |
| **Extractor version**   | A stamp on derived rows saying which extraction logic produced them.                                                          |
| **Context pack**        | The assembled input for one model call: persona, Soul core, recent chat, retrieved memories, active nodes, sensed context.    |
| **Gateway**             | The single code path through which every memory write passes. Validates, resolves, logs.                                      |
| **Alias**               | A short per-turn handle (`n1`, `n2`) given to the model instead of a real ID.                                             |
| **Resolver**            | Code that turns a vague reference ("cancel the dinner") into a specific node or an ambiguity.                                 |
| **Proposal**            | A pending change (Soul fact, ambiguous edit, ambient extraction) waiting for approval.                                        |
| **Day session**         | The single continuous chat for one user-defined day.                                                                          |
| **Day window**          | The user's active hours (start to end). Outside it, MineAI does not initiate.                                                 |
| **Nudge**               | A message MineAI starts on its own initiative.                                                                                |
| **Open thread**         | A nudge the user has not replied to yet. At most one is open at a time.                                                       |
| **Backoff**             | The growing minimum gap after nudges the user ignored.                                                                        |
| **Reminder**            | A scheduled alarm attached to a node with guard conditions.                                                                   |
| **Guard**               | A condition checked at fire time (is the task still open, has there been progress).                                           |
| **Form Nudge**          | The hidden tag on a post-call nudge message (v2).                                                                             |
| **BYOK**                | Bring your own key.                                                                                                           |
| **Context pack budget** | The fixed token allowance per part of the context pack.                                                                       |
| **Place**               | A saved place Mine can recognize (`43`).                                                                                |
| **Context**             | Place plus motion.                                                                                                      |
| **Motion**              | Still, walking, or vehicle.                                                                                             |
| **Call nudge**          | The post-call question (`44`).                                                                                          |
| **Voice note**          | A short spoken message transcribed to text (`60`).                                                                      |
| **Voiceprint**          | The user's speaker embedding (`61`).                                                                                    |
| **Segment**             | A labeled portion of an ambient audio window, such as `user` or `other`.                                                |
| **Window**              | A bounded ambient listening interval considered for presence and transcription.                                         |
| **Gate**                | The user-presence gate that decides whether an ambient window may be uploaded (`61` 3.4).                              |
| **Ambient report**      | A reviewable summary of accepted ambient segments and proposals.                                                        |

## 3. Naming rules

- Statuses and edge types are lowercase snake_case strings in the database.
- IDs are ULIDs (sortable, creatable offline on any device).
- Times are stored as UTC epoch milliseconds plus the local offset at write time.

## 4. Changelog

- 0.1 (2026-10-21): first draft.
