# Blueprint Patches

This file documents all proposed modifications to the upstream architecture blueprints in `D:\CnP\MDocs (Attempt 3)`.
Upstream blueprint files are strictly read-only; all deviations or additions must be recorded here with exact references, current text, proposed text, and rationale.

---

## Patch 1: Add `follow_up` Edge Type

- **Target File**: `13-nodes-edges-statuses.md`
- **Section**: `3.6 Edge types`
- **Architectural Decision**: `D-106`

### Current Text
```markdown
| Edge               | Meaning                                        | Direction                 | Effect in the app                                   |
| ------------------ | ---------------------------------------------- | ------------------------- | --------------------------------------------------- |
| `part_of`        | Sub-task of a goal or project                  | child to parent           | Progress rolls up                                   |
| `depends_on`     | Cannot proceed until the target is done        | dependent to prerequisite | Nudge the prerequisite, not the dependent           |
| `supports`       | Helps the target succeed                       | from to target            | Context for advice                                  |
| `conflicts_with` | Contradicts or collides with the target        | symmetric                 | Conflict nudge candidate                            |
| `elevates`       | Raises the importance or urgency of the target | from to target            | Priority boost                                      |
| `negates`        | Makes the target moot                          | from to target            | Proposes cancelling the target (never auto-cancels) |
| `supersedes`     | Newer replaces older                           | new to old                | Old becomes `superseded`                          |
| `involves`       | Mentions a person                              | node to person            | People lookup                                       |
| `at`            | Takes place at a place                         | node to place            | Sensing triggers (`43` 3.5)                 |
| `constrained_by` | Limited by a Soul fact                         | node to soul fact         | Warnings (for example sweets at a party)            |
| `relates_to`     | Weak generic link                              | from to target            | Retrieval only                                      |
```

### Proposed Text
```markdown
| Edge               | Meaning                                        | Direction                 | Effect in the app                                   |
| ------------------ | ---------------------------------------------- | ------------------------- | --------------------------------------------------- |
| `part_of`        | Sub-task of a goal or project                  | child to parent           | Progress rolls up                                   |
| `depends_on`     | Cannot proceed until the target is done        | dependent to prerequisite | Nudge the prerequisite, not the dependent           |
| `follow_up`      | Subsequent action or next step after a task   | prior to follow-up        | Sequenced follow-up execution and relationship link |
| `supports`       | Helps the target succeed                       | from to target            | Context for advice                                  |
| `conflicts_with` | Contradicts or collides with the target        | symmetric                 | Conflict nudge candidate                            |
| `elevates`       | Raises the importance or urgency of the target | from to target            | Priority boost                                      |
| `negates`        | Makes the target moot                          | from to target            | Proposes cancelling the target (never auto-cancels) |
| `supersedes`     | Newer replaces older                           | new to old                | Old becomes `superseded`                          |
| `involves`       | Mentions a person                              | node to person            | People lookup                                       |
| `at`            | Takes place at a place                         | node to place            | Sensing triggers (`43` 3.5)                 |
| `constrained_by` | Limited by a Soul fact                         | node to soul fact         | Warnings (for example sweets at a party)            |
| `relates_to`     | Weak generic link                              | from to target            | Retrieval only                                      |
```

### Rationale
Per Decision D-106, a dedicated `follow_up` edge type was introduced to link follow-up tasks arising from existing nodes. Overloading `depends_on` was rejected because follow-ups are sequential extensions rather than blocking prerequisites. Overloading `relates_to` was rejected because it lacks the semantic directionality needed for follow-up traversals and UI state wiring (`UiAction.AddFollowUp`, `UiAction.RemoveFollowUp`).
