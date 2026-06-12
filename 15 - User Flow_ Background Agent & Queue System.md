# User Flow: Background Agent & Queue System

## The 4-Stage Pipeline

```mermaid
flowchart TD
  A[Transcript enters<br/>memory_queue] --> B[Stage 1: Noise Gate]
  B --> C{Is Noise?}
  C -->|Yes| D[Move to rejected_logs]
  C -->|No| E[Stage 2: Context Fetch]
  E --> F[FTS5 Search<br/>Top 3 existing nodes]
  F --> G[Stage 3: Curator<br/>GPT-OSS-20B]
  G --> H[Parse JSON Tool Calls]
  H --> I[Stage 4: Tool Executor]
  I --> J{Actions}
  J -->|node_create| K[(SQLite Graph)]
  J -->|node_update| K
  J -->|edge_create| K
  J -->|node_mark_completed| K
  J -->|transcript_reject| D

  H --> L{nudge_worthy?}
  L -->|Yes + Cooldown OK| M[Queue to api_queue<br/>Priority 1]
  L -->|No| N[Done]
```

## SQLite Concurrency & Tool Execution

To prevent `SQLITE_BUSY` crashes when the user is chatting while the background agent is writing, Mine uses **Room with Write-Ahead Logging (WAL)** and **Kotlin Coroutines**.

All tool calls execute within a single SQLite transaction on `Dispatchers.IO`:

```mermaid
flowchart TD
  A[BEGIN TRANSACTION<br/>Dispatchers.IO] --> B[FOR each tool_call]
  B --> C[Execute tool]
  C --> D{Success?}
  D -->|No| E[ROLLBACK]
  E --> F[Log error<br/>Keep in local queue]
  D -->|Yes| G[Next tool]
  G --> H{More tools?}
  H -->|Yes| B
  H -->|No| I[COMMIT]
  I --> J{Has nudge?}
  J -->|Yes| K[Queue GPT-OSS-120B polish]
  J -->|No| L[Done]
```

## Offline & Rate-Limited Fallback

```mermaid
flowchart TD
  A[Real-time processing] --> B{API Success?}
  B -->|Yes| C[Normal flow]
  B -->|No: 429 or Offline| D[Set DEGRADED state]
  D --> E[Show indicator<br/>Mine is catching up]
  E --> F[Write to local queues]
  F --> G[Audio chunks to<br/>memory_queue]
  F --> H[API requests to<br/>api_queue]

  I[WorkManager Trigger] --> J{Network OK?}
  J -->|Yes| K[Drain queues via Dispatchers.IO]
  K --> L[Respect rate limits]
  L --> M[Process by priority]
  M --> N[Set ONLINE state]
  N --> O[Hide indicator]
  J -->|No| P[Wait next trigger]
```

## Queue Priorities

| Priority | Endpoint | Description |
|----------|----------|-------------|
| 1 | nim_gptoss(120b) | Nudge polish (user-facing, urgent) |
| 2 | groq_whisper | Transcription (blocks downstream) |
| 3 | nim_gptoss(20b) | Background curator |
| 5 | nim_gptoss(20b) | Batch summaries and pruning |