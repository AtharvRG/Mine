
# Learning

Status: v1     Version: 0.1     Depends on: 12-soul, 31-nudge-engine, 22-resolver

## 1. Purpose

MineAI should get better at being *this user's* assistant over time, in every area. "Learns everything" is
made safe by one rule: **all learning is visible, explainable, bounded and resettable.** There are two kinds:

|           | Behavioral learning                                                 | Knowledge about the user                 |
| --------- | ------------------------------------------------------------------- | ---------------------------------------- |
| What      | How the user behaves and reacts (timing, tone, length, reliability) | Who the user is (facts, tastes, history) |
| Method    | Plain statistics in code, no LLM                                    | LLM proposals, user approval             |
| Stored in | `learned_pref` table                                              | Soul (`12-soul.md`) and graph          |
| Shown in  | "What I've learned about how you work" screen                       | Soul view and memory viewer              |

## 2. Behavior (what the user sees)

- A screen lists learned behaviors in plain words: "You usually answer messages around 8 to 10 pm", "You tend to
  start tasks about 3 hours after saying 'in the evening'", "You ignore nudges about X". Each has a confidence indicator.
- Each can be corrected, locked ("keep this"), or reset. A global "Reset learning" exists.
- Any nudge has a **Why?** action: "I asked because: the report is due in 2 days and no progress was logged. You usually reply in the evening."
- Quick feedback on nudges: a thumbs up or down in the notification or the chat bubble.

## 3. Design

### 3.1 What is learned (behavioral)

| Area                 | Signal                                                | Used for                                           |
| -------------------- | ----------------------------------------------------- | -------------------------------------------------- |
| Nudge timing         | Reply rate by trigger type and time bucket            | When to speak                                      |
| Response latency     | Time from nudge to reply                              | Spacing, urgency                                   |
| Ignore patterns      | Ignored by trigger type                               | Backoff, dropping triggers                         |
| Length preference    | Reply length vs the user's message length, feedback   | Reply verbosity                                    |
| Time promises        | "this evening" mapped to actual start time            | Reminder time for vague phrases                    |
| Task estimation      | Stated vs actual completion gaps                      | Better lead times                                  |
| Daily rhythm         | First and last activity times                         | Suggest the day window, check-in times             |
| Snooze habits        | Typical snooze length                                 | Default snooze                                     |
| Topic engagement     | Replies to silly questions, Soul questions, check-ins | Which kinds of messages to keep                    |
| Persona fit          | Thumbs up or down per persona                         | Suggest a persona change (never change silently)   |
| Resolver corrections | Undo after an auto-pick, repeated choice patterns     | Resolver weights (tuned in the test set, not live) |

New areas can be added by adding a `key` namespace (no migration).

### 3.2 Method

Keep it simple and inspectable:

- **Counters and Beta distributions** for yes/no outcomes (replied or not) per `(trigger_type, time_bucket)`.
- **Exponential moving averages** for durations (latency, gaps).
- **Minimum samples** before a learned value is used (default 8). Below it, the default applies.
- **Decay:** recent behavior counts more, and old evidence fades with a half-life of about 60 days.
- **Exploration:** about 10 percent of the time the engine tries a less-favored time bucket, so it does not lock in early mistakes.
- Learned values only **adjust within the bounds of the user's own settings** (they never shorten the minimum gap, enter quiet hours, or exceed the hidden fuse). Learning tunes, settings limit.

### 3.3 Explainability

Every decision by the nudge engine stores a `reason_json` (candidates, scores, gates passed, learned values used). The **Why?**
action renders it in words. This is the debugging tool for tuning too.

### 3.4 Knowledge learning (about the user)

- The Worker proposes Soul facts (see `12-soul.md`). Manual by default.
- Feedback in chat is turned into **style facts**: "stop using emojis" proposes `communication_style: dislikes emojis`
  (auto-accepted when the user states it explicitly, since an explicit instruction is high confidence).
- Corrections ("no, I meant Thursday") update nodes through the gateway and are logged as resolver signals.
- **No hidden prompt memory.** Nothing the AI "learned" lives only in a prompt or a model. If it is not in a table the
  user can open, it is not learned.

### 3.5 Learning from raw history

Because the raw log is permanent, behavioral stats can be **recomputed from scratch** (`signal_log` plus `nudge_log`)
when the method improves. The `learned_pref` table is derived, like everything else.

### 3.6 Cold start

New users have defaults and a short question set in onboarding (when they usually wake up, when they want check-ins).
Learned values slowly take over. The author's own phone is the first test bed, so the screen doubles as a tuning tool.

## 4. Data

```sql
CREATE TABLE signal_log (                -- raw-ish events used for learning (derived, rebuildable from messages and nudge_log)
  id TEXT PRIMARY KEY,
  ts_utc INTEGER NOT NULL,
  kind TEXT NOT NULL,                    -- nudge_reply | nudge_ignored | nudge_feedback | undo_after_commit | snooze | task_start | ...
  subject TEXT,                          -- trigger type, node id, persona id ...
  value REAL,
  attrs TEXT
);

CREATE TABLE learned_pref (
  key         TEXT NOT NULL,             -- e.g. nudge.reply_rate
  scope       TEXT NOT NULL,             -- e.g. trigger=deadline|bucket=20-22
  value_json  TEXT NOT NULL,             -- counters, mean, variance
  samples     INTEGER NOT NULL,
  confidence  REAL NOT NULL,
  locked      INTEGER NOT NULL DEFAULT 0,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (key, scope)
);
```

## 5. Settings

| Setting          | Default    |
| ---------------- | ---------- |
| Learning on      | On         |
| Minimum samples  | 8          |
| Half-life        | 60 days    |
| Exploration rate | 10 percent |
| Reset learning   | Button     |

## 6. Edge cases and failure modes

- **User behavior changes** (new job, travel). Decay adapts, and a "routine changed?" check can be proposed when stats shift sharply.
- **Sparse data.** Defaults apply, and the screen says "not enough data yet".
- **Feedback noise** (a thumbs down by accident). One signal changes little because of minimum samples and decay.
- **Echo chamber** (it only speaks at the times already known to work). Exploration prevents it.
- **Sensitive inference** (guessing that the user is depressed from patterns). Behavioral learning stays at timing and style. It never labels the user's mental state, and no learned preference becomes a Soul fact without the normal proposal flow.
- **Learning cost.** None, since it is plain code.

## 7. Offline behavior

Fully local. Signals are recorded offline and learned offline.

## 8. Tests

- Simulated 30 days of replies at 8 to 10 pm: the engine prefers that window but still explores others.
- Ignored nudges of a type lower its rate and the type is eventually dropped (within settings bounds).
- Reset learning clears `learned_pref` and the defaults return.
- The Why? text matches the stored `reason_json`.
- Learned values never violate quiet hours or the min gap.

## 9. Open questions

- Should the app suggest changing the day window when the observed rhythm differs from the setting for 2+ weeks? Proposed: yes, as a one-time suggestion.

## 10. Changelog

- 0.1 (2026-10-21): first draft.
