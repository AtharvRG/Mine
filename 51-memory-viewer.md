
# Memory Viewer and Editor

Status: v1     Version: 0.1     Depends on: 12-soul, 13-nodes-edges-statuses, 22-resolver, 25-learning, 21-retrieval-embeddings, 16-security-privacy

## 1. Purpose

Trust. A companion that remembers everything is only acceptable if the user can see exactly what it believes and fix it in seconds.
The viewer answers "what do you know about me?" with the real tables, not a model's paraphrase, and it is also the **offline control panel**:
everything here works without a provider.

## 2. Behavior (what the user sees)

Memory is the second tab. Seven areas, plus Ambient when `flag.ambient` is on, shown as a row of chips at the top:

| Area                                                                                                                                                            | What it shows                                                                                    |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Agenda**                                                                                                                                                | Overdue, today, tomorrow, this week, later, and undated, in time order, with reminders           |
| **Items**                                                                                                                                                 | Every task, commitment, goal, event, note, habit, with filters and search                        |
| **Soul**                                                                                                                                                  | Facts about the user, grouped by category, with the core profile                                 |
| **People & places**                                                                                                                                       | Person and place items, with what is linked to them; a place-signal editor (`43`)                |
| **Inbox**                                                                                                                                                 | Everything waiting for a decision: proposals, duplicates, conflicts, stale items, erased sources |
| **Timeline**                                                                                                                                              | Search across all past conversation, and the activity log (who changed what, with Undo)          |
| **Learned**                                                                                                                                               | What MineAI has learned about how the user works, and why it messaged them                       |
| **Ambient**                                                                                                                                               | Reviewable ambient report items from `ambient_report_item`                                        |

A badge on Memory and on Inbox shows the number of open decisions. Asking MineAI "what do you know about me?" gets a short summary and a link to the Soul area.

## 3. Design

### 3.1 Common rules

1. **Everything here is read from the real tables.** No model call is needed to display or edit anything.
2. **Every edit goes through the gateway** (`23`): validated, logged in `raw_event` with actor `user`, and Undo-able.
3. **User edits become anchors:** an edited row gets `origin = user_edit` (`11` 4), so rebuilds never overwrite it and the Worker cannot silently change it (`22` 3.5).
4. **Remove versus erase** are different on purpose:
   - **Remove** is a tombstone (`deleted_at`). The row disappears from every list and from retrieval, stays restorable from **Trash** for 30 days, and keeps the operation log.
   - **Erase permanently** scrubs content from the row and its logs (`11` 3, `16` 3.5). It is irreversible and needs a typed confirmation.
5. **Bulk actions** work through multi-select (long press), always show the list of affected rows, create one grouped Undo, and never apply without a confirmation.
6. **Local only** toggle on every item and Soul fact (`16`). A small lock icon marks them.
7. **Provenance everywhere:** each row can show "Created from your message on Tue 14 Oct 21:10" and a tap opens the conversation at that message.
8. Lists page from the database and stay smooth with 50,000 rows. Sorting and filtering happen in SQL.

### 3.2 Agenda

- Sections: **Overdue**, **Today**, **Tomorrow**, **This week** (week start from settings), **Later**, **No date**. Empty sections are hidden.
- Each row: time, title, type icon, status chip, severity dot, a bell if a reminder is armed, and a conflict mark if it overlaps another item.
- Quick actions by swipe or buttons: **Done**, **Start**, **Snooze/Reschedule**, **Pause**, with an Undo toast.
- Tapping opens the item detail. A floating **+ Add** opens the manual form (3.3).
- A calendar grid view is planned (v1.1; see `90` section 3.3). Version 1 uses the sectioned list.

### 3.3 Items

**List.** Search box (hybrid search, `21`), filter chips (type, status group: open, closed, archived, proposed; priority; severity; source; origin; has reminder; local only), sort (due date, recently updated, severity, created), grouping (none, by status, by type, by day).

**Item detail** shows, in order:

1. Title, type, status, priority and severity, due and start (with precision), flexible flag, lane, expiry.
2. Body and notes.
3. **Relations:** edges grouped by type in plain words ("Depends on", "Part of", "Involves", "Conflicts with") with the other item's title. Add or remove a relation (type picker, item picker). Rules from `13` apply (allowed endpoint types, no `depends_on` cycles).
4. **Reminders:** list with state, tier, time, and snooze count. Add a reminder with a date and time picker, tier selector, and "converse" toggle. Works offline (`30` 7).
5. **Provenance:** source, origin, extractor version, source messages (tap to jump).
6. **History:** the operations on this item (`raw_event`), each with Undo when the item has not changed since.
7. **Actions:** Edit, Start, Pause, Complete, Cancel, Reopen, Archive, Remove, Keep local only, Create reminder.

**Editing.** A form with validation. Dates use pickers and the same date logic as the resolver (`22` 3.7). Saving writes one `update` op with before and after. Changing `due_at` also moves `relative_due` reminders (`30` 3.12) and tells the user which reminders moved.

**Manual create.** Type, title, optional due (date, time, or day only), priority, severity, optional reminder. This is also the offline way to add things when the AI is unreachable.
A manual item has `source = user_manual` and `origin = user_edit`.

**Status changes** follow the state machine in `13` 3.3. The UI only offers valid transitions.

**Bulk.** Select several: Complete, Cancel, Archive, Remove, change priority or severity, set local only.

### 3.4 Soul

- Grouped by category (`12` 3.2), each fact shown as one line with small marks: confidence dots (three levels), source icon (you said it, onboarding, inferred, ambient), pinned star (core profile), lock (local only), sensitive tag.
- Sections at the top: **Core profile** (what is always sent to the AI, with a live budget meter, "480 / 600 tokens") and **Proposed** (cards waiting for Accept, Edit, Reject; in auto mode a "Recently added" list with Undo).
- **Detail:** statement, category, attributes, validity period, history chain ("replaced: Smokes, quit in March"), source messages, last confirmed.
- **Actions:** Edit (creates a new version with `supersedes`, or corrects in place with a "just a typo" toggle), Retire ("no longer true", keeps history), Pin or unpin for the core profile, Local only, Remove.
- **Add a fact** manually: statement, category, optional validity. Source is `user_edit`, status `active`, no approval needed.
- **Soul mode** (manual or auto) is shown at the top with a switch (`12` 3.6).
- **Show me what is sent:** a button that renders the exact Soul text that would go into the next request (core profile plus the facts the last retrieval selected). It is the privacy transparency tool.
- **Rejected facts** are listed in a "Rejected" tab where the user can restore one or delete the suppression.

### 3.5 People and places

- A list of `person` and `place` items with counts of linked open items. Detail shows linked items (`involves`, `at`), facts about the person (Soul facts linked to them), and notes.
- Merge two people ("Mom" and "Mother") with a bulk-confirmation flow: edges are re-pointed, the duplicate is tombstoned, and one grouped Undo is created.
- Person facts are about **others**, and they are never mixed into the user's Soul (`12` 3.1).

### 3.6 Inbox

Everything waiting for the user, in one place, ordered by age. Each row has the exact actions it needs:

| Row                                                                                         | Source                                                       | Actions                                              |
| ------------------------------------------------------------------------------------------- | ------------------------------------------------------------ | ---------------------------------------------------- |
| Open question ("Which dinner?")                                                             | `proposal` (ambiguous, needs_confirm, duplicate)           | The same chips as in chat, with the expiry time      |
| Soul proposal                                                                               | `soul_fact` status `proposed`                            | Accept, Edit, Reject                                 |
| Reminder suggestion                                                                         | `proposal` kind `reminder`                               | Set, Change, No                                      |
| Conflict                                                                                    | Two items overlapping (conflict edges)                       | Keep both, Reschedule one, Cancel one                |
| Stale item                                                                                  | Hot-tier item with no activity for N days (`31` 3.2 stall) | Still on, Pause, Drop                                |
| Source erased                                                                               | A row citing an erased message (`11` 6)                    | Keep, Remove                                         |
| Rebuild conflict                                                                            | A rebuilt row conflicts with an anchor (`11` 4)            | Keep mine, Take rebuilt, Merge                       |
| Ambient proposals (v3)                                                                      | `status = proposed`, `source = ambient`                  | Accept, Edit, Reject, with the daily report (`61`) |

Rows resolve through the gateway, and resolved rows move to a short "Done" list for 7 days.

### 3.7 Timeline

Two parts:

1. **Search the past.** One search box for the whole history (hybrid, `21`) with filters (date range, sender, tags such as `nudge`, `reminder`, `checkin`). Results are chunks, grouped by day. Tapping opens the conversation at that point. No provider is needed.
2. **Activity log.** A filtered view of `raw_event`: who (you, MineAI chat, worker, scheduler, system), what, target, time. Filters by actor, type, and date. Each row has **Undo** when allowed.
   If the target changed since, Undo shows the difference and asks, instead of guessing (`22` 3.9). The log answers "what did MineAI change today?" at a glance.
   Redacted messages appear as "erased".

### 3.8 Learned

- **How you work:** the `learned_pref` table in plain sentences ("You usually reply between 20:00 and 22:00", "You start 'this evening' tasks around 18:40"), each with a confidence label, and actions Edit value, Lock, Reset (`25` 3.3).
- **Why did you message me?** The recent `nudge_log` with outcome and the Why? text. Thumbs feedback can be given here too.
- **Reset learning** removes `learned_pref` and `signal_log` derived data (the raw history stays), after a confirmation.
- A short note explains what learning can and cannot change (settings always bound it).

### 3.9 Archive and Trash

- **Archive** holds `archived` items (cold tier). Searchable, restorable (Reopen).
- **Trash** holds removed rows for 30 days with Restore. After 30 days they leave Trash (they remain as tombstones for sync, invisible to the user).
- **Erase permanently** is available from Trash and from detail screens.

### 3.10 What do you know about me?

- Typing that sentence in chat triggers `get_soul` and a summary, and the reply ends with a link to **Memory > Soul**.
- The Soul area is the source of truth. The summary is a convenience and may be wrong, which the reply says in one phrase.

### 3.11 Accessibility and performance

- All swipes have button alternatives. Rows read as "Report, task, due Friday 18:00, high severity, in progress".
- Lists use database paging and stable keys. Search results appear within 300 ms for typical queries (**verify** on the reference phone with 50,000 items).
- Filters and last tab are remembered in settings (`view_state.*`).

## 4. Data

No new core tables. Additions:

- `setting` keys under `view_state.*` for remembered filters.
- Soul fact `status = rejected` rows power the Rejected tab. Trash is a query on `deleted_at` in the last 30 days.
- Inbox is a **union query** across `proposal`, `soul_fact`, conflict edges, stale items (computed), source-erased flags (a derived flag column `attrs.flags`), rebuild conflicts (operational `rebuild_conflict` table written by `11` 5), and ambient rows.

```sql
CREATE TABLE rebuild_conflict (            -- operational
  id TEXT PRIMARY KEY, anchor_type TEXT NOT NULL, anchor_id TEXT NOT NULL,
  rebuilt_json TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'open'
);
```

## 5. Settings

| Setting                     | Default                                                   |
| --------------------------- | --------------------------------------------------------- |
| Trash retention (visible)   | 30 days                                                   |
| Stale item threshold        | 5 days (priority 2 and above), 10 days otherwise (`31`) |
| Confirm before bulk actions | Always (not changeable)                                   |
| Remember filters            | On                                                        |
| Show provenance links       | On                                                        |

## 6. Edge cases and failure modes

- **Editing an item while the Worker is processing a message about it.** The gateway uses the item's `updated_at` as an optimistic lock, and a stale write returns a conflict that the UI shows ("changed meanwhile, review").
- **Removing an item that others depend on.** The confirmation lists the dependents, and the dependents keep working with the edge tombstoned.
- **Removing a person linked to many items.** Items stay, the link is removed, and one grouped Undo is created.
- **Editing a Soul fact that supersedes another.** The chain is preserved.
- **Undo after later changes.** The difference view appears. If the change cannot apply safely, Undo is refused with a reason.
- **Huge Inbox after a long offline stretch.** Grouped by type with counts, with "Resolve all of this kind" actions that still show lists.
- **Restoring from Trash an item whose reminders were cancelled.** The item returns, and reminders do not (`30` 3.12). The UI offers to create a new one.
- **Local only toggle on a fact needed for safety.** A note next to the toggle explains the trade-off (`16` 6).
- **User sets a due date in the past on a manual item.** Allowed (it is "overdue"), and no reminder can be created for a past time.

## 7. Offline behavior

Everything in this file works offline. Only AI-created proposals depend on the Worker having run earlier.

## 8. Tests

1. Create an item manually offline, add a reminder, and it rings on time.
2. Edit an AI-created item, then run an extraction rebuild: the item is unchanged.
3. The Worker proposes a change to an edited item: a proposal appears in Inbox rather than a silent update.
4. Bulk-cancel five items: the confirmation lists all five, and one Undo restores all.
5. Retire a Soul fact: it leaves the core profile and the next request, but its history remains.
6. "Show me what is sent" matches a captured provider request.
7. Remove, then restore from Trash, then erase permanently: each state is verified in the raw log (erase scrubs content).
8. Merge two people: edges are re-pointed and one Undo reverses it.
9. Activity log Undo is refused with a clear diff when the target changed.
10. Inbox shows each source type, and resolving removes it from the badge count.
11. 50,000-item fixture: list scroll and search stay within the targets.

## 9. Open questions

- Should the Items list offer a "board" view (columns by status)? Parked for v1.1 (see `90` section 3.3).
- Should merged people keep an alias list so extraction recognizes both names? Proposed: yes, in `attrs.aliases`.

## 10. Changelog

- 0.1 (2026-10-21): first draft.
