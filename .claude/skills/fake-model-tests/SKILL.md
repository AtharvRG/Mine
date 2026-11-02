---
name: fake-model-tests
description: How to write controller tests with a fake LLM without cheating.
---
The fake transport may read ONLY the request body it receives. Extract ids (proposal, undo token, node ids) from that body with the same text a real model would see. Assert on: stored messages (one assistant message per turn), model call count, final node/reminder state, and what UiState exposes. Include tests for: calls=[], null args, code fences and prose around JSON, mixed commit+error, ambiguity then answer, 429 eager and lazy, cancellation mid-stream, no API key, and the recorded real transcript (dinner with shyam / cafe meet with deux / cancel dinner / shyam).
