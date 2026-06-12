# External APIs & Rate Limiting

## Account Separation

| Provider | Account | Model | Quota | Role |
|----------|---------|-------|-------|------|
| Groq | Single | whisper-large-v3-turbo | 20 RPM, 2K/day | Primary ASR |
| Groq | Single | whisper-large-v3 | 20 RPM, 2K/day | Fallback ASR |
| NVIDIA NIM | Acc1 | kimi-k2-instruct-0905 | 40 RPM | Chat output, Nudge polish |
| NVIDIA NIM | Acc2 | gpt-oss-20b | 40 RPM | Background curator, Thinking planner |

## Groq Whisper Router

```kotlin
suspend fun transcribe(audio: ByteArray): String {
    return try {
        groq.turbo(audio)
    } catch (e: RateLimitException) {
        groq.largeV3(audio)
    }
}
```

Inspect response headers for `x-ratelimit-remaining-requests`. If both models return 429, queue audio to `memory_queue` for batch processing.

## Rate Limiter Implementation

```kotlin
class RateLimiter(private val rpm: Int) {
    private val bucket = AtomicInteger(rpm)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    init {
        scope.launch {
            while (isActive) {
                delay(60_000)
                bucket.set(rpm)
            }
        }
    }
    
    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            if (bucket.getAndDecrement() > 0) {
                return block()
            }
            delay(1000)
        }
    }
}
```

## NIM Warmup Strategy

On app launch, send a dummy request to Kimi to keep the GPU container warm.

```kotlin
suspend fun warmupKimi() {
    try {
        nimAcc1.chat(
            model = "kimi-k2-instruct-0905",
            messages = listOf(Message("user", "hi")),
            maxTokens = 1
        )
    } catch (e: Exception) {
        // Silently fail warmup
    }
}
```

## Error Handling Matrix

| Error | Action |
|-------|--------|
| Groq 429 (both models) | Queue audio to memory_queue. Switch to batch mode. |
| NIM 429 | Queue request to api_queue. Retry in 60s. |
| NIM Timeout (>30s) | Abort. Increment attempts. Retry if attempts < 3. |
| Network unreachable | Set DEGRADED state. Queue everything. |
| Invalid API Key | Show persistent banner. Block new requests. |

