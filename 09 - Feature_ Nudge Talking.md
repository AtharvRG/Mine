# Feature: Nudge Talking

## Philosophy
Nudges are the voice of the Ambient system. When the phone is in your pocket, the Nudge is how the AI taps you on the shoulder.

## Trigger Scenarios (Curator Agent)
1. **Intervention:** The user is breaking a commitment, slacking off, or making a bad choice (e.g., eating pizza on a diet).
2. **Clarification:** The user mentions something vague and there are multiple matching projects in the graph.

## Proactive Scenarios (Chronos Worker)
The `ChronosWorker` wakes up every 4 hours, checks the active nodes, and checks the clock. If a deadline is approaching or a task is stale, it generates a nudge.

## Pipeline
1. AI sets `trigger_nudge = true` and writes a `nudge_message`.
2. `ToolExecutor` intercepts the message.
3. `NotificationHelper` fires a `PRIORITY_MAX` Heads-Up Android Notification.
4. The message is simultaneously saved to the Chat UI with a ⚡ prefix.
5. **Direct Reply:** The user can reply directly from the lock-screen notification, which routes the text back to Kimi K2.6.
