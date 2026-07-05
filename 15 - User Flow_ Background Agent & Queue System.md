# User Flow: Background Agent & Queue System

## The Offline Queue (`QueueWorker`)
If the internet drops, ambient audio is saved as a BLOB in the `memory_queue` table.
When Wi-Fi returns, `NetworkMonitor` triggers the `QueueWorker` using `enqueueUniqueWork(REPLACE)` to prevent network spam.
- The worker drains the queue, transcribes the audio, and sends it to the Curator.
- **Poison Pill Protection:** Individual items are wrapped in `try/catch`. If an item fails, it is marked `failed` so it doesn't brick the queue.
- **Cleanup:** Processed items are deleted immediately to prevent SQLite bloat.

## The Nightly Pruning (`PruningWorker`)
Runs once every 24 hours via WorkManager.
1. Deletes `trashed` nodes older than 7 days.
2. Deletes `failed` queue items older than 7 days.
3. Grabs all chat messages from the day, truncates them to 15,000 characters (to prevent Token Overflow), sends them to the Curator for summarization, and deletes the raw logs.

## The Proactive AI (`ChronosWorker`)
Runs every 4 hours.
Fetches active nodes, injects the current timestamp, and asks Kimi K2.6 if the user needs a reminder. If yes, it fires a Nudge notification and inserts the message into the Chat UI.
