# External APIs & Rate Limiting

## The 3-Key Architecture
To maximize throughput and prevent ambient listening from blocking chat, Mine uses three separate NVIDIA NIM API keys, yielding a total of 120 Requests Per Minute (RPM).

| Key | Model | Quota | Role |
|-----|-------|-------|------|
| `NVIDIA_API_KEY_WHISPER` | Riva Whisper Large v3 | 40 RPM | Ambient audio transcription (gRPC) |
| `NVIDIA_API_KEY_AGENT` | gpt-oss-120b & nv-embed-v1 | 40 RPM | Background Curator, Chronos, Embeddings |
| `NVIDIA_API_KEY_CHAT` | kimi-k2.6 | 40 RPM | Instant Chat UI responses |

## Rate Limiter Implementation
A strict Coroutine `Mutex` Token Bucket ensures no key ever exceeds 39 RPM.

```kotlin
suspend fun waitAndConsume(apiKey: String) {
mutex.withLock {
val now = System.currentTimeMillis()
val logs = requestLogs.getOrPut(apiKey) { mutableListOf() }
logs.removeAll { it < now - 60_000L }

if (logs.size >= 39) {
val waitTime = (logs.first() + 60_000L) - now + 100
delay(waitTime)
// ...
```

## Network Resilience
- **Ktor Timeouts:** Configured to wait 120 seconds for massive 120B model responses.
- **Exception Handling:** `expectSuccess = false` ensures Ktor doesn't swallow 400/401 JSON error messages.
- **Poison Pill Protection:** The `QueueWorker` wraps individual offline items in `try/catch` blocks so one corrupted audio file doesn't brick the entire queue.
