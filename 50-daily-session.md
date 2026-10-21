
# Daily Session (Chat UI)

Status: v1     Version: 0.1     Depends on: 11-layers-raw-derived, 20-context-pack, 22-resolver, 30-reminder-engine, 31-nudge-engine, 32-briefings-checkins, 40-provider-layer, 41-offline-fallback

## 1. Purpose

The chat screen is the heart of the app. One idea defines it: **a day is one conversation.** There is no "new chat" button.
The user talks to MineAI at 9, at noon, at 4, and at 9 at night, and it is all one continuous scroll, visually
separated by time so the day still has shape. The screen must feel calm, fast, and alive, and it must never show
the user something that is not true (for example a change that was not actually applied).

## 2. Behavior (what the user sees)

- The app opens on today's conversation, scrolled to the latest message (or to the first unseen message from MineAI, with an "N new" pill).
- Scrolling up goes through earlier parts of today, then through **yesterday and older days in the same scroll**, each introduced by a day header.
  Older days are read-only. The composer always writes to the current day.
- Messages that are far apart in time are separated by a thin **time divider** ("21:05"). Long gaps also get a part-of-day label ("Evening").
- A slim **scrubber rail** on the right edge lets the user drag to travel fast through the day, or tap a mark to jump to the start of a group.
- MineAI's replies stream in word by word. If reasoning is on, a collapsed "Thought for 6 s" line sits above the reply.
- When MineAI changes something (cancels a dinner, completes a task), a small **receipt** appears under its reply, with an **Undo** chip.
- Questions that need a choice ("Which dinner?") show **tappable chips**. After the user picks, the chips collapse into a one-line result.
- MineAI's own messages (nudges, check-ins, reminder follow-ups) look like normal bubbles with a small tag, action chips, and a **Why?** action.
- Offline, messages show a small "queued" mark. A calm status chip explains the situation.

## 3. Design

### 3.1 Screen anatomy and navigation

```text
+--------------------------------------------------+
| Wed 21 Oct      [Cofounder v]  [status chip]  [s][...] |   top bar
|--------------------------------------------------|
|  ---- Tue 20 Oct ----                            |   day header (sticky)
|   ... older messages ...                         |
|  ---------- 21:05 · Evening ----------           |   time divider
|   [user bubble]                                  |
|        [assistant bubble]                        |
|        v Cancelled: Dinner, Wed 21 Oct 20:00 [Undo]   receipt
|                                          | rail  |   scrubber (right edge)
|  [ N new v ]                          [ jump to bottom ]
|--------------------------------------------------|
| [ message ........................ ] [mic*] [send] |   composer (*v2)
+--------------------------------------------------+
   Chat | Memory | Settings                            bottom navigation
```

- Three top-level destinations: **Chat** (home), **Memory** (`51`), **Settings** (`52`).
- Top bar: day label (tap to open the date picker), persona chip (tap to switch persona), status chip (only when something is off), search, overflow menu.
- The overflow menu holds: Today's agenda (shortcut into Memory), Pause nudges, Day summary, Reliability (when a warning exists).

### 3.2 Timeline model

The screen is one `LazyColumn` of **timeline items**, built from several tables:

| Item             | Source                                                   | Notes                                                                                                                 |
| ---------------- | -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `DayHeader`    | `day_key` change                                       | "Today", "Yesterday", then "Mon 12 Oct". Sticky while scrolling inside the day. Overflow: Day summary, Erase this day |
| `TimeDivider`  | `day_block`                                            | Between blocks of the same day (3.3)                                                                                  |
| `Message`      | `raw_message` (roles `user`, `assistant`)          | Bubbles                                                                                                               |
| `SystemEvent`  | `raw_message` (role `system_event`)                  | Single quiet line, for example "Alarm 18:00: you tapped Started"                                                      |
| `Receipt`      | `raw_event` rows whose `cause_msg_id` is the message | Under the assistant reply of that turn (3.8)                                                                          |
| `ProposalRow`  | `proposal` (open or recently resolved)                 | Chips, or the collapsed result line                                                                                   |
| `CheckinCard`  | `raw_message.attrs.cards`                              | Native charts from snapshots (`32`)                                                                                 |
| `StatusNotice` | Computed                                                 | For example "Offline: your messages are saved" (only in the live tail)                                                |

Loading and paging:

- The **current day** is loaded in full (up to 2,000 messages, beyond that by block, newest first).
- Older days load on demand, about 100 messages at a time, keyed by `(day_key, ts_utc, id)`. Keys are stable, so the list does not jump.
- Jump to a date or a message loads that region and sets an anchor. Scroll position is restored when returning to the screen.
- Stick-to-bottom: if the user is at the bottom, new messages scroll into view. If they have scrolled up, a "jump to bottom" button shows the number of new messages instead.
- Cold start target: interactive in under 1.5 s on a mid-range phone with 50,000 stored messages (to be measured, **verify**).

### 3.3 Time dividers and blocks

A **block** is a run of messages with no gap longer than the **divider gap** (default **45 minutes**, a setting).
Blocks are separated by a time divider showing the block's first time ("21:05").

| Gap since the previous message                                                                                | Divider                                                                                                          |
| ------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Less than the divider gap                                                                                     | None                                                                                                             |
| Gap to under 3 hours                                                                                          | Thin line, time chip only                                                                                        |
| 3 hours or more                                                                                               | Thin line, time chip, and the part-of-day label: Morning, Afternoon, Evening, or Night (windows from `30` 3.4) |
| New `day_key`                                                                                               | Day header (replaces the divider)                                                                                |

Optionally the divider text says the gap, "3 h later", when the gap is 3 hours or more (setting, default on).

`day_block` is a derived table, extended incrementally when a message is appended and rebuilt when the divider gap setting changes:

```sql
CREATE TABLE day_block (
  day_key       TEXT NOT NULL,
  idx           INTEGER NOT NULL,
  start_msg_id  TEXT NOT NULL,
  start_ts      INTEGER NOT NULL,
  end_ts        INTEGER NOT NULL,
  msg_count     INTEGER NOT NULL,
  part_of_day   TEXT,
  PRIMARY KEY (day_key, idx)
);
```

Block boundaries are based on **time only**, as designed. Check-ins and nudges are marked by their own tag and card, not by extra dividers.

### 3.4 Scrubber rail

- A thin rail on the right edge, hidden at rest, fading in while scrolling and for 1.5 s after. The touch target is 40 dp wide while the visible line is thin.
- It represents **the day currently in view** (the day of the topmost visible message). When the view crosses into another day, the rail morphs to that day.
- **Marks:** one per block. Each block's rail height is proportional to `sqrt(msg_count)` with a minimum, so a day with one 30-minute cluster at 9 and another at 21:00 does not leave 12 empty hours between them.
  The gaps between marks are drawn as small spaces.
- **Dragging:** the thumb moves along the rail and the list follows immediately. A floating bubble beside the finger shows the time of the message under the thumb and the block label ("Evening · 21:05").
  A light haptic tick fires when the finger crosses a block boundary.
- **Tapping a mark** smooth-scrolls to the start of that block.
- **Fast fling** also shows the rail, so the user can grab it mid-scroll.
- **Large days:** if the day has more than about 400 messages, the thumb position inside a block is interpolated by message index within the block.
- Day-level travel across many days uses the **date picker** (top bar), not the rail.
- Accessibility: the rail is also exposed as a list of "Jump to 21:05 Evening" actions in the TalkBack actions menu.

### 3.5 Message rendering

| Kind                                                                                                                                   | Look                                                                                                           | Behavior                                                                         |
| -------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| User message                                                                                                                           | Right-aligned bubble, time on tap (a small time under the last message of a minute group)                      | Queued mark when not yet sent. Long press for actions                            |
| Assistant reply                                                                                                                        | Left-aligned, no avatar clutter, persona name shown only when it changed ("Switched to Cofounder" system line) | Streams in. Markdown subset (3.7)                                                |
| Reasoning                                                                                                                              | A single collapsed line above the reply, "Thought for 6 s". Tap to expand                                      | Settings: collapsed (default), expanded, hidden. Never sent back to the provider |
| Nudge / proactive                                                                                                                      | Like an assistant bubble, with a small tag chip ("Check-in", "Deadline", "Question")                           | Action chips and Why? (3.9)                                                      |
| System event                                                                                                                           | One quiet centered line                                                                                        | Not interactive, except a link to the item if there is one                       |
| Check-in card                                                                                                                          | Native chart card under the check-in text                                                                      | Tap a segment: opens the items in Memory                                         |
| Long messages                                                                                                                          | Collapsed after about 1,200 characters with "Show more"                                                        | The full text is in the raw log either way                                       |
| Failed turn                                                                                                                            | "Couldn't get a reply. Saved. [Retry now]" under the user message                                              | Retry now respects limiter and cooldown (`40`)                                 |
| Interrupted reply                                                                                                                      | The partial reply with a tag "Stopped"                                                                         | Created by the Stop button                                                       |

Timestamps: the exact time is shown on tap or long press. Bubbles from the same sender within a minute are grouped with tight spacing.

### 3.6 Composer

- Multi-line text field growing to about 6 lines, then scrolling. Send button enabled when non-empty. "Enter sends" is a setting (default off, so Enter inserts a newline on phones).
- **Draft is saved** per keystroke batch to the operational layer and restored after process death. It belongs to "now", not to a day.
- A **reasoning toggle** (brain icon) flips the Chat slot's reasoning setting for the next turns (`40`). Hidden if the model profile says reasoning is unsupported.
- The mic slot is active when `flag.voice` is on (`60`); otherwise it is absent.
- First-run empty state shows three suggestion chips ("Plan my day", "Remind me to drink water at 6", "Tell me about yourself"). They disappear after the first message.
- Huge paste (more than about 20,000 characters): the user sees "Long text: Mine will keep all of it, but the AI will read the first part only". The full text is in raw, the pack gets a truncated version with a marker.
- No attachments in v1.

### 3.7 Sending, streaming, and stopping

1. Pressing send writes the message to the raw log **first** (`client_msg_id` for idempotency) and shows it instantly. A `chat_turn` job is queued in the outbox (`41`).
2. If a turn can start (not offline, not cooling down), a typing indicator appears, then the reply streams in. The partial text is held in an operational `stream_draft`.
   The assistant's `raw_message` is written **when the stream completes or is stopped** (raw is append-only, so partial text is not written twice).
3. If the process is killed mid-stream, the draft is discarded and the outbox retries the turn on restart. The user sees one reply, not two.
4. **Stop button:** replaces Send while streaming. It cancels the request. The partial text is saved with the tag `interrupted`, and receipts only reflect operations that already committed.
5. **New message during streaming:** it is appended immediately. The current stream continues to its end. Then a **single** follow-up turn handles all messages that arrived meanwhile (coalesced). One live turn runs at a time.
6. **Tool rounds** are shown as a subtle activity line ("Checking your agenda...") while they run. They never show raw tool names or arguments.
7. Markdown subset: paragraphs, bold, italic, bullet and numbered lists, inline code, code blocks, links (external links ask for confirmation), simple tables rendered as lists.
   No images and no HTML, so a model cannot inject active content.

### 3.8 Receipts, proposals, and Undo

The reply text is **never trusted** to say a change happened. The UI draws a receipt from the actual operation log (`raw_event`), so it is true by construction.

- **Receipt:** "Cancelled: Dinner, Wed 21 Oct 20:00" with `Undo` for 24 hours (`22` 3.9). After that, the history is in Memory > Timeline. Several receipts of one turn stack in order.
- **Ambiguous / needs confirm / duplicate:** a `ProposalRow` shows the question text from the proposal and chips, for example `Wed 21 Oct dinner`, `Thu 29 Oct dinner`, `Both`, `Neither`.
  Chip taps call the gateway directly (no model call), so they are instant (D-033). A `system_event` informs the Chat AI afterwards.
- **Resolved:** the chips collapse into a line, "You chose: Wed 21 Oct dinner".
- **Declined or expired:** a quiet line "Not changed".
- Several open proposals stack, newest at the bottom, with a counter ("2 questions waiting") in the top bar while any are open.
- **Typed answers** ("the first one") go through the Chat AI normally and call `confirm_change`.
- **Soul proposals** (manual mode) show as small cards under the reply: `Add to Soul?  [Accept] [Edit] [Reject]`.
- **Reminder suggestions** (ask mode) show as chips: `Set 17:35`, `Change`, `No`.
- A reply that claims success without a matching receipt is displayed as is. The receipt row simply does not exist, which keeps the UI honest (the model is told not to do this, `24`).

### 3.9 Proactive messages in the session

- **Notification versus inline:** if the app is in the foreground, the nudge engine posts inline (`31` 3.8). A small top banner and a light haptic mark a new message that arrives while the user is looking at a different part of the timeline.
- **Tag chip** names the trigger in plain words: Check-in, Deadline, Follow-up, Question, Reminder.
- **Action chips** per trigger (`Done`, `Started`, `Later`, `Not now`, `Reschedule`, `Drop it`). They call the gateway directly, and then show a result line.
- **Why?** opens a small sheet rendered from the nudge's logged `reason_json` (`25` 3.3), for example "Asked because the report is due in 2 days with no progress logged. You usually reply in the evening."
- **Feedback:** a thumbs up or down on the message (long press menu and the Why? sheet).
- **Unseen tracking:** `read_state.last_seen_ts` moves when a message has been visible for about 1 second. Opening the app clears the matching notifications.
  On open, the list scrolls to the first unseen **assistant** message when there is one, with the "N new" pill, otherwise to the bottom.
- **Reminder follow-ups and merges:** a merged reminder appears inside MineAI's normal reply. The inline fallback bubble (`30` 3.8) has a small bell tag.
- **Check-in cards** render under the check-in text from the stored snapshot, so old days show what they showed then.

### 3.10 Status chip and notices

The top bar shows **one calm chip** only when something needs saying:

| State                                                                                            | Chip                        | Tap                                                                         |
| ------------------------------------------------------------------------------------------------ | --------------------------- | --------------------------------------------------------------------------- |
| Offline                                                                                          | "Offline"                   | Explains that messages are saved and alarms still work                      |
| AI unavailable                                                                                   | "AI unavailable"            | Provider status, and Retry now                                              |
| Cooling down                                                                                     | "Cooling down until 14:35"  | Explains the rate limit, with Retry now (once per cooldown, with a warning) |
| Backup model                                                                                     | "Using backup"              | Which target is serving                                                     |
| Reliability problem                                                                              | "Reminders at risk"         | Opens the Reliability screen                                                |
| Notifications off                                                                                | "Notifications off"         | Opens the system settings                                                   |
| Nudges paused                                                                                    | "Nudges paused until 18:00" | Resume                                                                      |
| No AI connected                                                                                  | "Connect an AI"             | Opens the provider wizard                                                   |

Chips never stack more than one. The most urgent wins (reliability and notifications off first).

### 3.11 Message actions (long press)

| Action                                                                                 | On                | Notes                                                                  |
| -------------------------------------------------------------------------------------- | ----------------- | ---------------------------------------------------------------------- |
| Copy                                                                                   | any               | Whole message or selected text                                         |
| Create item from this                                                                  | user or assistant | Opens the manual item form prefilled (works offline)                   |
| Remind me about this                                                                   | any               | Opens the manual reminder form                                         |
| Why?                                                                                   | nudge             | Section 3.9                                                            |
| What was this based on?                                                                | assistant         | Opens the context inspector (`20`), only if the inspector is enabled |
| Thumbs up or down                                                                      | assistant         | Feeds learning (`25`)                                                |
| Erase message                                                                          | any               | Redaction with confirmation (`11` 3), never silent                   |
| Share text                                                                             | any               | Android share sheet, plain text                                        |

Parked: edit a sent message, regenerate a reply, mark private (`91-parking-lot.md`).

### 3.12 Search and jump

- The search icon opens a full-screen search (hybrid retrieval, `21`) over chat chunks, with filters for date range and sender, and results grouped by day. Tapping a result jumps to the message in the timeline and flashes it.
- The date picker (calendar) marks days that have messages. Selecting a day loads and jumps to it.
- Deep links from other places (Memory item provenance, notifications) use the same "open the timeline at message X" function.

### 3.13 Day rollover and the day rule

- The day starts at the user's **day start** (default set in onboarding). `day_key` is the calendar date of the most recent day start at or before the message time.
  A message at 02:00 with a 06:00 day start belongs to the previous day's session (the "day and night" case).
- A **day rule** is a versioned record: `(effective_from, start_minute, end_minute, tz_id)`. The rule in force at a message's timestamp decides its `day_key`.
- **Changes take effect at the next rollover**, never mid-day, so a day never splits or goes backwards. Changing the timezone also takes effect at the next day start computed in the new zone.
- **`day_key` is monotonic:** a new message's `day_key` is never earlier than the previous message's. This protects against clock changes and travel.
- At rollover with the app open, a **day header** is inserted live ("Thu 22 Oct") and the scroll continues. The day summary job runs (`21` 3.7), and check-ins re-plan.
- Quiet hours (outside the day window) only mean MineAI does not **start** conversations. The user can chat any time.

### 3.14 Quick capture

The easiest way to add a thought is the one that needs no opening.

- **Reply on any MineAI notification** (inline reply, `RemoteInput`): the text is written to the raw log as a user message with tag `quick_reply`, processed like any message, and MineAI's reply is posted back as a messaging-style notification.
  Until the reply arrives the notification shows "Sending...". Offline it shows "Saved".
- **Quick capture notification (optional, off by default):** a quiet persistent notification with a "Tell Mine" reply box.
- **Launcher shortcut:** long press the app icon: "New note", opens the composer focused.
- **Planned (v1.1; see `90` section 3.3):** home screen widget, share sheet target ("Share to Mine" creates a user message with the shared text, tag `shared`).
- Notification text respects the lock-screen privacy setting (`16`). On a locked phone, inline reply may require unlocking, depending on the system.

### 3.15 Accessibility, theming, performance

- TalkBack: every control labelled, bubbles read as "You, 21:05: ..." and "MineAI, 21:05: ...", chips announced as buttons, charts have text equivalents (`32`).
- Large fonts (up to the system maximum), high contrast, reduced motion setting (no streaming animation, instant scroll), color-independent state cues.
- Light, dark, and (on Android 12 and later) dynamic color. Material 3 components.
- Right-to-left layouts, mixed scripts, emoji, and long words must not break bubbles.
- 60 fps scrolling on mid-range devices: stable item keys, no heavy work in composition, markdown parsed off the main thread and cached per message.
- Strings are externalized from day one (English first). Dates and times follow the system 12 or 24 hour setting and locale.

## 4. Data

```sql
-- derived
-- day_block: see 3.3

CREATE TABLE day_rule (                    -- derived layer, user-authored settings history
  id             TEXT PRIMARY KEY,
  effective_from INTEGER NOT NULL,         -- UTC ms
  start_minute   INTEGER NOT NULL,         -- minutes after local midnight
  end_minute     INTEGER NOT NULL,         -- may be less than start (crosses midnight)
  tz_id          TEXT NOT NULL,
  created_at     INTEGER NOT NULL
);

-- operational
CREATE TABLE read_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  last_seen_ts INTEGER, last_scroll_msg_id TEXT, last_scroll_offset INTEGER
);
CREATE TABLE composer_draft (
  id INTEGER PRIMARY KEY CHECK (id = 1), text TEXT, updated_at INTEGER
);
CREATE TABLE stream_draft (
  turn_id TEXT PRIMARY KEY, text TEXT, reasoning TEXT, updated_at INTEGER
);
```

Message tags used by this screen (`raw_message.tags`): `nudge:<trigger>`, `reminder:<id>`, `checkin:<kind>`, `quick_reply`, `shared`, `interrupted`, `form_nudge`, `voice` (the `voice` tag joins `form_nudge`).

## 5. Settings

| Setting                                  | Default   |
| ---------------------------------------- | --------- |
| Divider gap                              | 45 min    |
| Show gap text on dividers of 3 h or more | On        |
| Reasoning display                        | Collapsed |
| Enter sends                              | Off       |
| Font scale                               | System    |
| Haptics                                  | On        |
| Reduced motion                           | System    |
| Markdown rendering                       | On        |
| Show fallback marker ("via backup")      | On        |
| Quick capture notification               | Off       |
| Open at first unseen message             | On        |

## 6. Edge cases and failure modes

- **User sends several messages while offline and then closes the app.** All are in the raw log with queued marks and are batched on reconnect (`41`).
- **Clock change or timezone travel.** `day_key` is monotonic, and the day rule takes effect at the next rollover.
- **Day rollover while a reply is streaming.** The reply belongs to the day of the message that triggered it (`day_key` is copied from the cause message).
- **Very large day** (thousands of messages). Block-wise loading, and the rail interpolates inside blocks.
- **Erased message inside a block.** It shows as "Message erased", and the block layout stays stable.
- **Provider returns an empty or malformed reply.** One retry, then the failed-turn line with Retry now.
- **User opens the app from a notification for an old nudge.** The timeline opens at that message and flashes it.
- **Receipts for operations later undone.** The receipt line changes to "Undone", and history is in the log.
- **The user changes the persona mid-conversation.** A system line "Switched to Cofounder" is added, and later messages use the new voice.
- **Rollover with the keyboard open.** The new header appears and the composer keeps its text.
- **Models that stream markdown tables or HTML.** Rendered as plain text, so there is no injection surface.

## 7. Offline behavior

Typing, sending (queued), reading, searching (on-device), jumping, scrubbing, chips, Undo, and quick capture all work offline. Replies wait (`41`).

## 8. Tests

1. A day with blocks at 09:00 to 09:30, 10:00, 12:00, 15:00, 16:30 and 21:00: dividers appear exactly where the gap exceeds 45 minutes, and the part-of-day labels at 3 h gaps.
2. Rail: dragging to the 21:00 block lands on its first message. A haptic tick fires per block boundary. TalkBack actions list the blocks.
3. 50,000-message fixture: cold start under 1.5 s on the reference phone, and smooth fast scroll.
4. Airplane mode: send three messages, see queued marks. Reconnect yields one reply.
5. Kill the process during streaming: one reply after restart, with no duplicate and no half message.
6. Stop mid-stream: partial text saved as `interrupted`, with no phantom receipts.
7. "Cancel the dinner" with two dinners: chips appear, nothing is changed before the tap, and the chip tap works offline.
8. A reply that says "Done!" without a commit shows no receipt.
9. Rollover at 06:00 with the app open: header inserted, the scroll continues, and the day summary job is queued.
10. Changing the day start at 15:00: today's `day_key` does not change, and the rule applies from the next rollover.
11. A nudge arrives while scrolled up: banner and counter appear, with no forced scroll.
12. Reply from a notification while the app is closed: the message appears in the session with the `quick_reply` tag and a reply arrives.
13. Right-to-left, large font, and TalkBack passes.

## 9. Open questions

- Should the rail also offer day-level marks (a mini calendar strip) for long scrolls? Proposed: no, use the date picker.
- Pinned messages for the day ("keep this in view")? Parked.

## 10. Changelog

- 0.1 (2026-10-21): first draft.
