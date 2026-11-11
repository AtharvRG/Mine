---
name: branching-analysis
description: Use before editing code for any bug or design choice. Forces breadth-first thinking: several hypotheses, many angles, cheap evidence first.
---
Create docs/agent/work/<task>.md with:
## Goal and invariant
## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
## Angles (one line each, mandatory): data/schema; concurrency/lifecycle; failure paths; user-visible behaviour; privacy/security; testability; blast radius; platform (Android 14, OEM); model behaviour (what a real LLM would send)
## Evidence log (commands and outputs)
## Decision: chosen change, rejected options and why
## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
Rules: run checks for all hypotheses before deepening any. Prefer evidence over reasoning. If two hypotheses stay alive, design a test that separates them. Finish with: what input would break the fix.
