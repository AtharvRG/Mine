# Branching Analysis: P1-E 33 (Resolver Scale Consistent with Blueprint 22 §3.4, Unicode Tokens, Stopwords/Command Words)

## Goal and Invariant
- **Goal:** Update `Resolver.kt` so that candidate scoring aligns with Blueprint 22 §3.4 (scale 0.0..1.0, weighted features: text 0.35, time 0.25, recency 0.20, status 0.10, type 0.10), tokenization uses locale-independent Unicode matching (`\p{L}\p{M}\p{N}` with `Locale.ROOT`), and command words / stopwords are cleanly filtered while preserving exact title match and disambiguation invariants.
- **Invariant 1:** All existing resolver tests (`WhichDinnerTest`, `ResolverSingleLowMatchChipTest`, `GatewayTest`, `R1RescheduleAmbiguityTest`, `R2CandidateSelectionTest`, `R4TimeChipsTest`) must pass without regression.
- **Invariant 2:** Single candidate below auto-commit threshold but > 0.0 text overlap must return `Resolution.Ambiguous(listOf(candidate))` (H-14 / R-19).
- **Invariant 3:** Exact title match must win over longer title containing hint ("Dinner" vs "Dinner with Alice" for hint "dinner").
- **Invariant 4:** Closing intents (`CANCEL`, `COMPLETE`) must require >= 0.80 score and >= 0.25 margin (D-110 / Blueprint 22 §3.4).

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|------------|------------|----------------|----------------|--------|
| H1 | Fixed un-normalized weights (0.35 text, 0.25 time, etc.) without dynamic normalization will cause all queries without a time hint to score <= 0.55, breaking all non-time clear winners. | Queries like "cancel dinner" will return Ambiguous instead of ClearWinner because 0.55 < 0.80. | Trace score calculation with time=null | Any test expecting ClearWinner with time=null fails | Confirmed: dynamic normalization across active signals is required. |
| H2 | Re-normalizing active weights (or defaulting missing signals neutrally) allows scores to span 0.0..1.0 cleanly. | When time is null, text (0.35) and status/type weights are normalized to sum to 1.0. A 100% text match on an active node scores 1.0 >= 0.80. | Math check on 0.35/(0.35+0.10+0.10) | Weak matches (e.g. 50%) exceed threshold | Confirmed: 50% text match yields < 0.70 score. |
| H3 | Using `Locale.getDefault()` in tokenization causes subtle bugs on non-English Android devices (e.g. Turkish `i` -> `İ`/`ı`). | `Locale.ROOT` provides deterministic ASCII and Unicode lowercase folding. | Compare `"IN".lowercase(Locale.ROOT)` vs Turkish locale | Any failure in Unicode test | Confirmed: standardizing to `Locale.ROOT`. |
| H4 | Command words stripped from hint must not remove legitimate titles named after actions (e.g. task titled "Update"). | If title is "Update" and hint is "Update", exact title match takes precedence before command stripping. | Test `resolve(UPDATE, "update", null, [Node(title="Update")])` | Returns NotFound | Confirmed: exact match runs before command token filtering. |

## Angles
- **data/schema:** In-memory algorithm only; no DB schema changes, stays at v6.
- **concurrency/lifecycle:** `Resolver` is stateless, thread-safe, and pure function.
- **failure paths:** If tokens are empty after stopwords, return `Resolution.NotFound` cleanly.
- **user-visible behaviour:** Ambiguity chips remain accurate, single candidates ask rather than false-reject, time hints break ties decisively.
- **privacy/security:** Plain code, no LLM similarity hallucination, prevents accidental auto-cancellations.
- **testability:** Fully unit-testable in `:core:test` without Android framework dependencies.
- **blast radius:** `Resolver.kt` only; `Gateway.kt` calls `resolver.resolve` which keeps backwards-compatible signature via default arguments.
- **platform:** Runs on standard JVM and Android ART; zero vibration, zero android imports in `:core`.
- **model behaviour:** Real models emit hints with command verbs ("cancel the dentist appointment") or times ("reschedule dinner tomorrow"); resolver strips command noise and extracts distinguishing tokens.

## Decision
1. Normalize weights across active signals:
   - Text: base weight 0.35
   - Time: base weight 0.25 (active when `targetTimeMillis != null`)
   - Recency: base weight 0.20 (active when `recentlyDiscussedNodeIds.isNotEmpty()`)
   - Status fit: base weight 0.10 (active for closing intents: 1.0 if active, 0.0 if closed; active for reopen: 1.0 if closed, 0.0 if active)
   - Type fit: base weight 0.10 (1.0 for candidate nodes)
2. Add `recentlyDiscussedNodeIds: List<String> = emptyList()` default parameter to `resolve(...)`.
3. Standardize tokenization on `Locale.ROOT` and expand stopwords/command words:
   - Action verbs: "cancel", "complete", "finish", "done", "delete", "remove", "update", "rename", "change", "edit", "mark", "reschedule", "postpone", "move", "drop", "clear"
   - Common prepositions & articles: "with", "the", "and", "for", "to", "at", "my", "a", "an", "of", "on", "in", "about", "by", "from"
   - Conversational filler: "please", "can", "could", "would", "i", "you", "me", "want", "need"
   - Relative time words: "tomorrow", "today", "yesterday", "tonight", "am", "pm"
4. Retain exact normalized title match fast-path and H-14 single candidate chip guarantee.

## Pre-mortem: 3 Potential Regressions & Mitigations
1. *Risk:* A node titled "Please" or "Update" becomes unresolvable because its only token is a stopword.
   *Mitigation:* Exact normalized title match runs *before* stopword stripping.
2. *Risk:* Multi-candidate margin fails when two candidates have identical titles and different dates.
   *Mitigation:* Blueprint 22 §3.4 rule: exact matches with target time check date window (< 24h). If only one matches, it is ClearWinner; otherwise Ambiguous.
3. *Risk:* A closing intent auto-commits on a vague hint because status fit and type fit boost the score.
   *Mitigation:* `top.second.textMatch >= autoCommitThreshold` check is strictly preserved alongside total score.
