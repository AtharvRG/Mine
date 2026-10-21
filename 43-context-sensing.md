
# Context Sensing

Status: planned (v2)     Version: 0.1     Depends on: 13-nodes-edges-statuses, 20-context-pack, 23-tool-contracts, 25-learning, 31-nudge-engine, 16-security-privacy, 42-android-reliability

## 1. Purpose

MineAI should know roughly where the user is in life terms, not on a map: at home or away, still or walking or
in a vehicle. That lets it say "you're heading out, and you still have two errands" at the right moment, and
keeps it from chatting at someone who is driving. The design is deliberately coarse. Mine never builds a
location trail, and everything here is derived from signals the phone already has.

## 2. Behavior (what the user sees)

- Off by default. After a v2 update, MineAI offers it once in chat: "Want me to notice when you leave home or arrive somewhere?" The user can also enable it in Settings > Context.
- Setup is one tap: "I'm at home now, remember this as Home". Other places (work, college, shop) are added the same way, or from a place node in the memory viewer.
- The chat header can show a small chip, for example "Home, walking", only when the user turned on "Show context chip". Otherwise sensing is invisible.
- Nudges that come from sensing are rare and always relevant:
  - Leaving home with open errands: "Heading out? You still have the pharmacy and the library book."
  - Arriving at a place that has linked items: "You're at college. 'Submit form' is open."
  - Back home after a long time away with errands that were open: one gentle "How did the errands go?"
- Nothing is said when there is nothing relevant. There is no "goodbye" or "welcome back" chatter.
- While the user is in a vehicle, MineAI does not start conversations (reminders and alarms still ring).
- The "Why?" on a nudge says what happened, for example "You left Home 4 minutes ago and 2 errands were open."

## 3. Design

### 3.1 Two independent axes

Place and motion are separate state machines. Their combination is the **context**. This is what makes
"walking inside the house" a normal state: motion is `walking`, place is still `home`, and nothing "left home".

```mermaid
stateDiagram-v2
    state "Place axis" as P {
        [*] --> unknown
        unknown --> home: signals match Home
        unknown --> away: no home signal, other evidence
        home --> away: leave confirmed (3.3)
        away --> home: arrival confirmed (3.3)
        away --> place_x: matches a saved place
        place_x --> away: leave confirmed
        home --> unknown: signals unavailable 30 min
        away --> unknown: signals unavailable 30 min
    }
    --
    state "Motion axis" as M {
        [*] --> m_unknown
        m_unknown --> still
        still --> walking: 45 s of walking
        walking --> still: 60 s stable
        still --> vehicle: 60 s of vehicle
        walking --> vehicle
        vehicle --> still
        vehicle --> walking
        still --> m_unknown: sensor unavailable
    }
```

### 3.2 Signals

| Axis   | Signal                                                           | Notes                                                                                                                                              |
| ------ | ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Place  | Connected Wi-Fi (SSID plus a small set of BSSIDs)                | Cheapest and most private. Reading the network name needs the location permission on current Android (**verify** the exact rule per version) |
| Place  | Optional coarse geofence (rounded center, radius at least 150 m) | Off by default. Used when the home Wi-Fi is unreliable. Never stores a trail                                                                       |
| Place  | Optional Bluetooth car or headset names                          | Only as a vehicle hint, never as a place                                                                                                           |
| Motion | System activity-recognition transitions                          | Delivered by the OS, no polling by Mine. Needs Google Play services on the device (**verify**)                                               |
| Motion | Fallback: step-detector and accelerometer variance               | For phones without Play services. Coarser, `vehicle` is not available without other hints                                                        |
| Manual | Chips "I'm home" and "I'm out"                                   | Works with no permissions. Sets the place axis for 2 hours or until a signal contradicts it                                                        |

No background location permission is requested in the default (Wi-Fi) mode. If geofencing is turned on, the app
explains that Android asks for "all the time" location, and why.

### 3.3 Debounce rules (code, not the model)

| Event         | Rule                                                                                                                                                         |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Home to away  | Home signals gone for `away_confirm_min` (default 4 min). **Fast path:** Wi-Fi lost and motion is walking or vehicle continuously for 90 s           |
| Away to home  | Home Wi-Fi connected, or inside the geofence, for `home_confirm_min` (default 2 min)                                                                       |
| Flap guard    | Wi-Fi drops while the phone is `still` and was home: stay home for up to 15 min (router restart, Wi-Fi sleep). Leaving needs motion as well as signal loss |
| Motion change | A new motion state must hold for `motion_min_s` (default 45 s)                                                                                             |
| Unknown       | No usable signal for 30 min (permission revoked, Wi-Fi scanning off): the axis becomes `unknown`, and the Now block omits it                               |
| Cooldown      | At most one context candidate per transition, and the same trigger cannot repeat within 30 min                                                               |

### 3.4 What the model sees

- The **Now block** (`20`) gets a short line: `Context: at Home, walking (since 14:05)`. About 15 tokens. If an axis is `unknown`, it is left out, and MineAI is told never to guess it.
- `get_context` (`23`) returns the same fields.
- The model never receives Wi-Fi names, coordinates, or radii. Only labels the user chose ("Home", "College") appear.

### 3.5 Context triggers (feed `31-nudge-engine.md`)

| Trigger                | Detector                             | Priority | Rule                                                                                                                                                                  |
| ---------------------- | ------------------------------------ | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `context_left_home`  | Place home to away confirmed         | medium   | Only if at least one open node is "outside-relevant": linked by an `at` edge to a non-home place, or tagged as an errand, or due today with a location in `attrs` |
| `context_arrived`    | Away to a saved place                | medium   | Only if open nodes are linked to that place by `at` edges                                                                                                           |
| `context_home_again` | Away to home after at least 2 h away | low      | Only if outside-relevant items were open when the user left                                                                                                           |

All three pass through the normal engine (`31`): scoring, one open thread, backoff, gates. Two additions:

- **Vehicle hold:** while motion is `vehicle`, conversational candidates wait (default on). They expire if still unsent 30 min after the vehicle state ends. Reminders and alarms are never held.
- **Silence on empty:** with no relevant open items, the transition is only logged.

### 3.6 Learning

Sensing feeds visible behavioral learning (`25`): usual leave and return times, typical active hours, and how
often context nudges get replies. Nothing is learned about movement beyond those aggregates. Sensing events
themselves are pruned (section 4).

## 4. Data

```sql
CREATE TABLE place (                       -- synced (label and kind only)
  id          TEXT PRIMARY KEY,            -- ULID
  node_id     TEXT,                        -- the graph node of type place (13), may be NULL for Home
  kind        TEXT NOT NULL,               -- home | work | other
  label       TEXT NOT NULL,
  updated_at  INTEGER NOT NULL, deleted_at INTEGER, hlc TEXT
);
CREATE TABLE place_signal (                -- device-local, never exported
  place_id    TEXT NOT NULL,
  wifi_json   TEXT,                        -- {"ssid": "...", "bssids": ["..."]}
  geo_lat     REAL, geo_lon REAL,          -- rounded to about 100 m
  geo_radius_m INTEGER,
  PRIMARY KEY (place_id)
);
CREATE TABLE sensing_event (               -- device-local, pruned
  id          TEXT PRIMARY KEY,
  ts_utc      INTEGER NOT NULL,
  axis        TEXT NOT NULL,               -- place | motion
  from_state  TEXT, to_state TEXT NOT NULL,
  place_id    TEXT,
  confidence  REAL, source TEXT,           -- wifi | geofence | activity | sensor | manual
  attrs       TEXT
);
CREATE INDEX idx_sensing_event_ts ON sensing_event(ts_utc);
```

- `place_signal` and `sensing_event` are **device-local** (like embeddings and limiter state, `15`), and the database encryption covers them (`10`).
- A place **node** (the thing in the graph that tasks link to with `at`) and a `place` **row** (what sensing matches against) are joined by `node_id`. Deleting the node tombstones the row.
- `sensing_event` is kept for `sensing.keep_events_days` (default 30), then deleted. The learned aggregates stay.

## 5. Settings

| Key                            | Default            | Scope | Notes                                      |
| ------------------------------ | ------------------ | ----- | ------------------------------------------ |
| `flag.sensing`               | off (shipped dark) | D     | Turned on by an update (`52`)            |
| `sensing.enabled`            | off                | D     | The user's switch                          |
| `sensing.use_wifi`           | on                 | D     |                                            |
| `sensing.use_geofence`       | off                | D     | Needs extra permission, explained          |
| `sensing.away_confirm_min`   | 4                  | S     |                                            |
| `sensing.home_confirm_min`   | 2                  | S     |                                            |
| `sensing.motion_min_s`       | 45                 | S     |                                            |
| `sensing.context_nudges`     | on                 | S     | When off, sensing only fills the Now block |
| `sensing.hold_while_vehicle` | on                 | S     |                                            |
| `sensing.show_chip`          | off                | S     |                                            |
| `sensing.keep_events_days`   | 30                 | S     |                                            |

## 6. Edge cases and failure modes

- **Walking at home** (the case that started this design): motion `walking`, place `home`. No trigger. Correct by construction.
- **Home Wi-Fi off, router rebooting, or phone Wi-Fi sleeping:** the flap guard keeps `home`.
- **Guests, or the phone at home while the user is out:** the phone cannot know. A `still` phone on home Wi-Fi is "home". Accepted.
- **Two homes, hostels, hotels:** extra saved places are normal places. A new network that is not saved is just `away`.
- **A public network with a common name** ("Guest", "Free WiFi"): matching uses SSID plus BSSIDs, and the setup refuses networks that look generic, with an explanation.
- **Airplane mode or Wi-Fi off:** place falls back to the geofence if on, otherwise to `unknown` after 30 min.
- **Permission revoked or OEM kills the receiver:** the axis goes `unknown`, the reliability score (`42`) shows "Sensing blind", and nothing crashes.
- **Time zone changes while traveling:** place logic is unaffected. The day rule handles the clock (`50`).
- **User in a bus or train:** `vehicle` and conversational nudges are held, which is the intended result.
- **Battery:** no polling. Transitions are system-delivered, and the app wakes only on a transition.

## 7. Offline behavior

All sensing is local. Context nudges use templates when no model is reachable.

## 8. Tests

1. Walk around the house for 10 minutes: no `context_left_home`, the motion axis changes, and place stays `home`.
2. Turn the router off for 5 minutes with the phone on a desk: still `home`.
3. Leave on foot: `context_left_home` fires once after the fast path, with relevant items. With no relevant items, nothing is sent.
4. Leave by bike or vehicle: conversational nudges are held, and one is sent after the vehicle state ends only if it is still relevant.
5. Arrive at a saved place with linked items: `context_arrived` fires once and respects one-open-thread.
6. Revoke the permission: the axis becomes `unknown` in 30 min, the Now block drops the line, and the nudge engine does not fire.
7. Manual chip "I'm out" is overridden by a contradicting signal after the guard time.
8. `sensing_event` older than the retention is deleted, and learned aggregates remain.
9. Export and import: `place` rows travel, `place_signal` does not, and the app asks to set up Home again.
10. The model never sees an SSID or coordinates: captured-request audit (`93` E-16).

## 9. Open questions

- On phones without Google Play services, is the step and accelerometer fallback good enough, or should sensing require Play services? Proposed: support the fallback, mark `vehicle` as unavailable.
- Should `context_home_again` exist in the first v2 release? Proposed: yes, but it is the first to cut.

## 10. Changelog

- 0.1 (2026-10-21): first draft.
